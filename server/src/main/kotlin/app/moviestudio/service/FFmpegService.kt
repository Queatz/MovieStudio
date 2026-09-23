package app.moviestudio.service

import app.moviestudio.*
import app.moviestudio.config.Env
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.MovieRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.PendingRenderUploadRepository
import app.moviestudio.database.RenderRepository
import app.moviestudio.database.TrackRepository
import app.moviestudio.routing.JobWebSocketManager
import app.moviestudio.storage.OssService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Final movie renderer. Compiles the timeline into a single H.264/AAC MP4 with FFmpeg:
 *
 * - Video clips are trimmed, center-crop fit to the movie's aspect ratio and overlaid in track
 *   z-order at their timeline position (PTS-shifted so content lines up with the playhead).
 * - Images are looped for the clip duration (pre-scaled so multi-megapixel stills don't balloon
 *   decode memory).
 * - Non-overlapping clips on a video track are concatenated into one stream per track (not one
 *   full-timeline overlay per clip) so long image slideshows stay within RAM; tracks composite
 *   in z-order with a single overlay each.
 * - Description-only (skeleton) items render as large centered white text, matching the preview.
 * - Overlap transitions (alpha / noise / voronoi / slide / circle / pixelate) blend a clip in
 *   over the media playing underneath it.
 * - Audio clips honor their source offset (clipped sound effects), volume and position; voice
 *   track clips get an extra [VOICE_VOLUME_BOOST] on top of their envelope so dialogue sits above
 *   beds. Voice clips with captions enabled get word-timed captions burned in via a single libass
 *   pass (an `.ass` sidecar), not one filter-graph layer per caption chunk.
 *
 * The finished file is uploaded to Alibaba OSS and recorded as a [RenderRecord] so every render
 * remains replayable and downloadable.
 */
object FFmpegService {
    private val logger = LoggerFactory.getLogger(FFmpegService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** How many trailing lines of FFmpeg's stderr to retain for surfacing a failure reason. */
    private const val FFMPEG_LOG_TAIL_LINES = 40

    /**
     * Fraction of the canvas width/height reserved as a margin on each side so a TEXT element's
     * block never reaches (let alone overflows) the frame edges — mirrors the live preview's
     * `padding(horizontal = 24.dp)` on `TextClip` (see `PreviewPanel.kt`).
     */
    private const val TEXT_SAFE_AREA_PADDING_FRACTION = 0.06

    suspend fun executeRenderJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) {
        logger.info("Executing FFmpeg render job ${job.id} for movie ${job.movieId}")

        onProgress(5, "Initializing rendering pipeline...")

        val tempDir = Files.createTempDirectory("moviestudio_render_${job.movieId}_").toFile()
        logger.info("Created temporary scratch directory: ${tempDir.absolutePath}")

        try {
            val movie = MovieRepository.getById(job.movieId) ?: throw Exception("Movie not found: ${job.movieId}")
            val tracks = TrackRepository.queryByMovieId(job.movieId)
            val trackIds = tracks.map { it.id }
            val clips = if (trackIds.isNotEmpty()) ClipRepository.queryByTrackIds(trackIds) else emptyList()
            val assetsById = clips.mapNotNull { AssetRepository.getById(it.assetId) }.associateBy { it.id }

            onProgress(10, "Downloading timeline assets...")

            // Download every asset that actually has media (description-only items render as text).
            val downloadedAssets = mutableMapOf<String, File>()
            val assetHasAudio = mutableMapOf<String, Boolean>()
            for (clip in clips) {
                val asset = assetsById[clip.assetId] ?: continue
                if (asset.ossUrl.isBlank() || downloadedAssets.containsKey(asset.id)) continue
                val extension = asset.ossUrl.substringBefore('?').substringAfterLast('.', "mp4").take(4)
                val localFile = File(tempDir, "asset_${asset.id}.$extension")
                logger.info("Downloading asset ${asset.id} from ${asset.ossUrl}")
                try {
                    withContext(Dispatchers.IO) {
                        java.net.URI(asset.ossUrl).toURL().openStream().use { input ->
                            localFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to download asset ${asset.id}: ${e.message}")
                    withContext(Dispatchers.IO) { runCatching { localFile.delete() } }
                    continue
                }
                downloadedAssets[asset.id] = localFile
                assetHasAudio[asset.id] = MediaUtil.probeHasAudio(localFile)
            }

            var totalDuration = clips.maxOfOrNull { (it.timelineStart + (it.trimOut - it.trimIn)).toDouble() } ?: 0.0
            if (totalDuration <= 0) totalDuration = movie.totalDuration
            if (totalDuration <= 0) totalDuration = 10.0

            val (canvasWidth, canvasHeight) = resolutionForAspectRatio(movie.aspectRatio)

            // Cap still-image decode size before the main graph. AI stills are often 2K–4K PNGs;
            // holding dozens of them open as `-loop 1` inputs (and scale/crop'ing each inside a
            // deep overlay chain) is what pushed FFmpeg past ~15 GiB RSS on long slideshows.
            // Fit inside a box a bit larger than the canvas so per-clip offset-crop still has room.
            onProgress(18, "Preparing still images...")
            val imageMaxSide = maxOf(canvasWidth, canvasHeight) * 2
            for ((assetId, srcFile) in downloadedAssets.toList()) {
                val asset = assetsById[assetId] ?: continue
                if (asset.type != AssetType.IMAGE) continue
                val fitted = File(tempDir, "imgfit_${assetId}.png")
                val prepared = withContext(Dispatchers.IO) {
                    prepareStillImage(srcFile, fitted, imageMaxSide)
                }
                if (prepared != null) {
                    downloadedAssets[assetId] = prepared
                    if (prepared.absolutePath != srcFile.absolutePath) {
                        withContext(Dispatchers.IO) { runCatching { srcFile.delete() } }
                    }
                }
            }

            onProgress(20, "Compiling timeline and filters...")

            val videoTracksSorted = tracks.filter { it.type == TrackType.VIDEO }.sortedBy { it.zIndex }
            val clipsByTrack = clips.groupBy { it.trackId }

            // ------------------------------------------------------------------ video pre-pass
            //
            // IMPORTANT: holding dozens of `-loop 1` stills open in ONE FFmpeg process (whether
            // overlaid or concat-filtered) still peaks at multi-GB RSS — measured ~15–19 GiB for
            // ~40 AI PNGs — and earlyoom then SIGTERM-storms FFmpeg (exit 255 / exit 123
            // "Received > 3 system signals"). 
            //
            // For sequential non-overlapping tracks (the common image-slideshow case) we pre-render
            // EACH clip to a short H.264 segment in its own FFmpeg process (peak RAM ≈ one still),
            // concat-demuxer them into one track file, and only feed that file into the main graph.
            // Tracks that overlap or use SLIDE stay on the legacy per-clip overlay path (typically
            // few clips, so safe).
            val preRenderedTrackFiles = linkedMapOf<String, File>() // trackId -> mp4, z-order preserved
            val preRenderedTrackIds = mutableSetOf<String>()
            var renderedPieceCount = 0
            val totalPiecesEstimate = videoTracksSorted.sumOf { t ->
                val tc = clipsByTrack[t.id] ?: emptyList()
                if (canConcatComposeTrack(tc.filter {
                        (it.trimOut - it.trimIn) > 0f && assetsById.containsKey(it.assetId)
                    })) tc.size else 0
            }.coerceAtLeast(1)

            for (track in videoTracksSorted) {
                val trackClips = (clipsByTrack[track.id] ?: emptyList())
                    .sortedBy { it.timelineStart }
                    .filter { (it.trimOut - it.trimIn) > 0f && assetsById.containsKey(it.assetId) }
                if (trackClips.isEmpty()) continue
                if (!canConcatComposeTrack(trackClips)) continue
                // Only file-pre-render when it actually saves memory (many pieces). A single clip
                // is cheaper as a normal overlay input.
                if (trackClips.size < 3) continue

                logger.info(
                    "Pre-rendering video track ${track.id} via per-clip segments " +
                        "(${trackClips.size} clip(s)) to keep FFmpeg RAM flat"
                )
                val trackFile = withContext(Dispatchers.IO) {
                    renderTrackViaSegmentFiles(
                        tempDir = tempDir,
                        trackId = track.id,
                        trackClips = trackClips,
                        assetsById = assetsById,
                        downloadedAssets = downloadedAssets,
                        totalDuration = totalDuration,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight
                    )
                }
                renderedPieceCount += trackClips.size
                val pct = (20 + ((renderedPieceCount.toDouble() / totalPiecesEstimate) * 5).toInt())
                    .coerceIn(20, 25)
                onProgress(pct, "Prepared video track (${trackClips.size} clips)...")
                preRenderedTrackFiles[track.id] = trackFile
                preRenderedTrackIds += track.id
            }

            // Assets that still need to be raw inputs in the MAIN graph: anything used by
            // non-pre-rendered video clips, plus every clip that contributes audio.
            val assetsNeededAsInputs = linkedSetOf<String>()
            for (clip in clips) {
                val asset = assetsById[clip.assetId] ?: continue
                val track = tracks.find { it.id == clip.trackId }
                val isPreRenderedVideo =
                    track?.type == TrackType.VIDEO && clip.trackId in preRenderedTrackIds
                if (isPreRenderedVideo) {
                    // Video pixels already baked; only pull the asset in if it also has audio we
                    // must mix (unusual for pure IMAGE slideshows).
                    if (assetHasAudio[asset.id] == true) assetsNeededAsInputs += asset.id
                } else if (track?.type == TrackType.VIDEO) {
                    if (asset.ossUrl.isNotBlank()) assetsNeededAsInputs += asset.id
                } else if (asset.ossUrl.isNotBlank() && assetHasAudio[asset.id] == true) {
                    assetsNeededAsInputs += asset.id
                }
            }

            val args = mutableListOf<String>()
            args.add(MediaUtil.ffmpegBinary)
            args.add("-y")

            // Input 0: black canvas; input 1: silence.
            args.add("-f"); args.add("lavfi"); args.add("-i")
            args.add("color=c=black:s=${canvasWidth}x${canvasHeight}:r=30:d=${totalDuration.ff()}")
            args.add("-f"); args.add("lavfi"); args.add("-i")
            args.add("anullsrc=r=44100:cl=stereo:d=${totalDuration.ff()}")

            // Pre-rendered track files next (full-timeline H.264), then remaining raw media.
            val trackInputMap = mutableMapOf<String, Int>()
            var inputIdx = 2
            for ((trackId, trackFile) in preRenderedTrackFiles) {
                args.add("-i"); args.add(trackFile.absolutePath)
                trackInputMap[trackId] = inputIdx
                inputIdx++
            }

            val assetInputMap = mutableMapOf<String, Int>()
            for (assetId in assetsNeededAsInputs) {
                val asset = assetsById[assetId] ?: continue
                val localFile = downloadedAssets[assetId] ?: continue
                if (asset.type == AssetType.IMAGE) {
                    val longestUse = clips.filter { it.assetId == assetId }
                        .maxOfOrNull { (it.trimOut - it.trimIn).toDouble() } ?: 5.0
                    args.add("-loop"); args.add("1")
                    args.add("-t"); args.add((longestUse + 1.0).ff())
                    args.add("-i"); args.add(localFile.absolutePath)
                } else {
                    args.add("-i"); args.add(localFile.absolutePath)
                }
                assetInputMap[assetId] = inputIdx
                inputIdx++
            }

            val filters = mutableListOf<String>()

            // ------------------------------------------------------------------ video pipeline
            var currentVideoTag = "0:v"
            var chain = 0

            // 1) Composite pre-rendered track files in z-order (one overlay each).
            //
            // Pre-rendered tracks are opaque yuv420p with solid-black gap frames (concat-demuxer
            // cannot carry alpha). Overlaying the whole stream would paint those gaps over every
            // lower track — the first video track goes fully black in multi-track movies. Gate
            // each overlay to the track's clip windows so gaps pass the layer underneath through.
            // Fade-style transitions are applied as overlay alpha here (not baked into the
            // segment files) so a crossfade blends over the track below instead of fading from black.
            for (track in videoTracksSorted) {
                val tIdx = trackInputMap[track.id] ?: continue
                val trackClipsForOverlay = (clipsByTrack[track.id] ?: emptyList())
                    .sortedBy { it.timelineStart }
                    .filter { (it.trimOut - it.trimIn) > 0f && assetsById.containsKey(it.assetId) }
                val nextTag = "v_preroll_${track.id}"
                filters.add(
                    preRenderedTrackOverlayFilter(
                        currentVideoTag = currentVideoTag,
                        overlayInputIndex = tIdx,
                        outputTag = nextTag,
                        trackClips = trackClipsForOverlay
                    )
                )
                currentVideoTag = nextTag
                logger.info("Composited pre-rendered track ${track.id} (input $tIdx)")
            }

            // 2) Legacy per-clip overlay for tracks we did not pre-render.
            for (track in videoTracksSorted) {
                if (track.id in preRenderedTrackIds) continue
                val trackClips = (clipsByTrack[track.id] ?: emptyList())
                    .sortedBy { it.timelineStart }
                    .filter { (it.trimOut - it.trimIn) > 0f && assetsById.containsKey(it.assetId) }
                if (trackClips.isEmpty()) continue
                logger.info(
                    "Composing video track ${track.id} via per-clip overlay " +
                        "(${trackClips.size} clip(s))"
                )
                for (clip in trackClips) {
                    val result = overlayClipOntoVideo(
                        filters = filters,
                        currentVideoTag = currentVideoTag,
                        clip = clip,
                        trackClips = trackClips,
                        assetsById = assetsById,
                        assetInputMap = assetInputMap,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        chain = chain
                    ) ?: continue
                    currentVideoTag = result.videoTag
                    chain = result.chain
                }
            }

            // Captions for voice clips (burned in over the final video).
            //
            // IMPORTANT: captions used to be one full-movie `color`+`geq`+`drawtext` layer and one
            // `overlay` per 4-word chunk (capped at 90). A long narrated movie — e.g. 1000+ words
            // over a ~6 minute image sequence — produced ~90 full-duration filter branches and
            // drove FFmpeg past 14 GiB RSS, after which earlyoom sent SIGTERM (exit 255,
            // "Exiting normally, received signal 15"). Burn them in a single libass pass instead:
            // collect every caption event, write one `.ass` sidecar, and apply one `ass=` filter.
            // That keeps memory flat regardless of caption count and lifts the artificial 90-chunk
            // cap so the whole transcript is subtitled.
            val voiceTrackIds = tracks.filter { it.type == TrackType.VOICE }.map { it.id }.toSet()
            val captionEvents = collectCaptionEvents(
                clips = clips.filter { it.trackId in voiceTrackIds },
                assetsById = assetsById,
                canvasHeight = canvasHeight
            )
            if (captionEvents.isNotEmpty()) {
                val fontsDir = File(tempDir, "caption_fonts").apply { mkdirs() }
                val assFile = File(tempDir, "captions.ass")
                writeAssCaptionsFile(
                    assFile = assFile,
                    fontsDir = fontsDir,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    events = captionEvents
                )
                val nextTag = "v_captions"
                val assPath = escapeFilterPath(assFile.absolutePath)
                val fontsPath = escapeFilterPath(fontsDir.absolutePath)
                filters.add(
                    "[$currentVideoTag]ass=filename='$assPath':fontsdir='$fontsPath':" +
                        "original_size=${canvasWidth}x${canvasHeight}[$nextTag]"
                )
                currentVideoTag = nextTag
                logger.info("Burning ${captionEvents.size} caption event(s) via libass (${assFile.name})")
            }

            // ------------------------------------------------------------------ audio pipeline
            // Every clip that carries an audio stream contributes to the mix — including video
            // clips on video tracks, whose embedded audio must be preserved. Clips with no audio
            // stream (images, silent video, description-only items) are filtered out below.
            // Voice-track clips get VOICE_VOLUME_BOOST on top of their stored volume / envelope so
            // dialogue is not buried by unity-gain music and video beds (mirrors live preview).
            val tracksById = tracks.associateBy { it.id }
            val audioClips = clips.sortedBy { it.timelineStart }
            val audioStreamTags = mutableListOf<String>()
            for (clip in audioClips) {
                val asset = assetsById[clip.assetId] ?: continue
                if (asset.ossUrl.isBlank()) continue // description-only: nothing to play yet
                if (assetHasAudio[asset.id] != true) continue // no audio stream (or mock file)
                val idx = assetInputMap[clip.assetId] ?: continue
                val duration = clip.trimOut - clip.trimIn
                if (duration <= 0) continue

                val effects = parseEffectsConfig(clip.effectsConfig)
                val boost = trackVolumeBoost(tracksById[clip.trackId]?.type ?: TrackType.VIDEO)
                val volume = effects.volume * boost
                val srcStart = asset.sourceOffsetSeconds + clip.trimIn
                val srcEnd = asset.sourceOffsetSeconds + clip.trimOut

                val audioFilters = mutableListOf<String>()
                audioFilters.add("atrim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
                audioFilters.add("asetpts=PTS-STARTPTS")
                if (effects.volumeKeyframes.isNotEmpty()) {
                    // Volume-over-time envelope: evaluated per frame; 't' is clip-relative
                    // because the chain runs after asetpts=PTS-STARTPTS. Scale keyframe gains by
                    // the same voice boost used for flat volume.
                    val keyframes = if (boost != 1.0) {
                        effects.volumeKeyframes.map { it.copy(volume = it.volume * boost) }
                    } else {
                        effects.volumeKeyframes
                    }
                    audioFilters.add("volume=volume='${volumeEnvelopeExpression(keyframes)}':eval=frame")
                } else if (volume != 1.0) {
                    audioFilters.add("volume=${volume.ff()}")
                }

                val trimmedTag = "a_trimmed_${clip.id}"
                filters.add("[$idx:a]${audioFilters.joinToString(",")}[$trimmedTag]")

                val delayMs = (clip.timelineStart * 1000).toInt()
                val delayedTag = "a_delayed_${clip.id}"
                filters.add("[$trimmedTag]adelay=$delayMs|$delayMs[$delayedTag]")
                audioStreamTags.add(delayedTag)
            }

            val mixInputs = mutableListOf("[1:a]")
            mixInputs.addAll(audioStreamTags.map { "[$it]" })
            filters.add("${mixInputs.joinToString("")}amix=inputs=${mixInputs.size}:duration=first:normalize=0[out_a]")

            // The compiled filtergraph can be enormous (VORONOI `geq` expressions plus one
            // `drawtext` clause per caption), easily exceeding the OS single-argument limit
            // (Linux MAX_ARG_STRLEN, a hard 128 KiB) and making execve() reject the launch with
            // E2BIG ("Argument list too long"). Feed the graph from a file via
            // `-filter_complex_script` so nothing large is placed on the command line.
            val filterScript = File(tempDir, "filtergraph.txt")
            filterScript.writeText(filters.joinToString(";"))
            args.add("-filter_complex_script")
            args.add(filterScript.absolutePath)
            args.add("-map")
            args.add(if (currentVideoTag == "0:v") "0:v" else "[$currentVideoTag]")
            args.add("-map")
            args.add("[out_a]")

            args.add("-t"); args.add(totalDuration.ff())
            args.add("-c:v"); args.add("libx264")
            args.add("-preset"); args.add("veryfast")
            args.add("-pix_fmt"); args.add("yuv420p")
            args.add("-c:a"); args.add("aac")

            val outputFile = File(tempDir, "rendered_output.mp4")
            args.add(outputFile.absolutePath)

            logger.info("Generated FFmpeg Arguments: ${args.joinToString(" ")}")

            onProgress(25, "Rendering movie with FFmpeg...")

            // Retain a bounded tail of FFmpeg's stderr so a failure surfaces the actual reason (the
            // last lines almost always carry the error) instead of a generic message. The full
            // output still streams to the server log at INFO.
            val ffmpegLogTail = ArrayDeque<String>()

            withContext(Dispatchers.IO) {
                val process = try {
                    ProcessBuilder(args)
                        // Inherit nothing from the server's stdin; keep stdout/stderr as pipes so
                        // progress parsing and "argument list too long" diagnostics stay reliable.
                        .redirectInput(ProcessBuilder.Redirect.PIPE)
                        .start()
                } catch (e: java.io.IOException) {
                    // Previously ANY IOException here was treated as "ffmpeg not installed" and the
                    // render silently produced/uploaded a 28-byte mock file that was reported as a
                    // successful render. That masked real, actionable failures - most notably E2BIG
                    // ("Argument list too long", errno 7) when execve() rejects the launch itself.
                    // Surface it as a genuine failure instead of degrading to a placeholder.
                    logger.error(
                        "Failed to launch FFmpeg binary '${MediaUtil.ffmpegBinary}' for job ${job.id}: ${e.message}",
                        e
                    )
                    throw Exception("Failed to launch FFmpeg (${MediaUtil.ffmpegBinary}): ${e.message}", e)
                }

                try {
                    // Drain stdout on a side thread so a chatty build of FFmpeg can never fill the
                    // pipe and deadlock while we only consume stderr for progress.
                    val stdoutDrainer = Thread({
                        runCatching { process.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) }
                    }, "ffmpeg-stdout-${job.id}").apply { isDaemon = true; start() }

                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.info("[FFmpeg] $line")
                            ffmpegLogTail.addLast(line)
                            while (ffmpegLogTail.size > FFMPEG_LOG_TAIL_LINES) ffmpegLogTail.removeFirst()
                            val seconds = parseFfmpegTime(line)
                            if (seconds != null && totalDuration > 0) {
                                val progress = ((seconds / totalDuration) * 100).toInt().coerceIn(0, 100)
                                val scaledProgress = 25 + (progress * 0.6).toInt()
                                onProgress(scaledProgress, "Rendering movie: $progress%...")
                            }
                        }
                    }
                    val exitCode = process.waitFor()
                    runCatching { stdoutDrainer.join(5_000) }
                    if (exitCode != 0) {
                        // Previously a non-zero exit silently fell back to a placeholder file and the
                        // render was reported as successful. Surface the real failure instead.
                        logger.error(
                            "FFmpeg render failed for job ${job.id} (exit code $exitCode). FFmpeg output tail:\n" +
                                ffmpegLogTail.joinToString("\n")
                        )
                        throw Exception("FFmpeg exited with code $exitCode. ${summarizeFfmpegError(ffmpegLogTail)}")
                    }
                } finally {
                    // If the coroutine is cancelled mid-render (or we throw after launch), make sure
                    // the child cannot linger and keep eating RAM — the failure mode that previously
                    // left multi-GB FFmpeg processes around until earlyoom reaped them.
                    if (process.isAlive) {
                        process.destroy()
                        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                        }
                    }
                }
            }

            // A zero-exit FFmpeg run can still leave nothing usable behind (e.g. a filtergraph that
            // produced no frames), which previously slipped through as a "successful" 0-byte render.
            // Verify there is real output before uploading and reporting success.
            if (!outputFile.exists() || outputFile.length() == 0L) {
                logger.error(
                    "FFmpeg reported success but produced no output for job ${job.id}. FFmpeg output tail:\n" +
                        ffmpegLogTail.joinToString("\n")
                )
                throw Exception("FFmpeg produced an empty (0-byte) output file. ${summarizeFfmpegError(ffmpegLogTail)}")
            }

            onProgress(90, "Uploading rendered movie to Alibaba Cloud OSS...")

            val objectKey = "renders/${job.movieId}/${UUID.randomUUID()}.mp4"
            val uploadedUrl = try {
                OssService.uploadFile(objectKey, outputFile)
            } catch (e: Exception) {
                // The render itself succeeded but the upload to OSS failed (e.g. a transient
                // network/credentials issue). Don't discard the finished movie: copy it to a
                // durable location and record it so RenderUploadRetryService can upload it later.
                // Leave the movie in RENDERING and the job RUNNING; the retry worker finalizes both
                // once the upload eventually succeeds.
                logger.error(
                    "Failed to upload rendered movie for job ${job.id}; persisting it locally to retry the upload later.",
                    e
                )
                persistPendingUpload(job, objectKey, outputFile, totalDuration, movie.aspectRatio, e)
                onProgress(90, "Upload to storage failed; the movie was saved locally and will be uploaded automatically once storage is reachable.")
                logger.info("FFmpeg render job ${job.id} finished rendering but its upload is pending retry.")
                return
            }

            finalizeSuccessfulUpload(job.id, job.movieId, uploadedUrl, totalDuration, movie.aspectRatio)

            logger.info("FFmpeg render job ${job.id} completed successfully. resultUrl: $uploadedUrl")
        } catch (e: Exception) {
            // Surface the failure (the caller marks the job FAILED with this message) and take the
            // movie back out of RENDERING so it isn't left stuck in that state after a failed render.
            logger.error("FFmpeg render job ${job.id} failed: ${e.message}", e)
            MovieRepository.getById(job.movieId)?.let { current ->
                if (current.status == MovieStatus.RENDERING) {
                    MovieRepository.update(current.copy(status = MovieStatus.DRAFT))
                }
            }
            throw e
        } finally {
            try {
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                    logger.info("Cleaned up scratch temp directory: ${tempDir.absolutePath}")
                }
            } catch (e: Exception) {
                logger.error("Failed to clean up scratch temp directory: ${tempDir.absolutePath}", e)
            }
        }
    }

    /**
     * The durable directory where rendered movies whose OSS upload failed are kept while they wait
     * to be re-uploaded. Configurable via `PENDING_RENDER_DIR`; defaults to a stable folder under
     * the system temp dir (which, unlike the per-render scratch dir, is not deleted after a render).
     */
    private val pendingUploadDir: File by lazy {
        val configured = Env.get(
            "PENDING_RENDER_DIR",
            File(System.getProperty("java.io.tmpdir"), "moviestudio_pending_renders").absolutePath
        )
        File(configured).apply { runCatching { mkdirs() } }
    }

    /**
     * Copies a finished render whose OSS upload failed into [pendingUploadDir] and records a
     * [PendingRenderUpload] so [RenderUploadRetryService] can retry the upload later. The durable
     * copy (not the caller's scratch temp dir, which is cleaned up) is what the retry worker uploads.
     */
    private suspend fun persistPendingUpload(
        job: Job,
        objectKey: String,
        outputFile: File,
        durationSeconds: Double,
        aspectRatio: String,
        cause: Exception
    ) {
        val id = UUID.randomUUID().toString()
        val durableFile = File(pendingUploadDir, "$id.mp4")
        withContext(Dispatchers.IO) {
            outputFile.copyTo(durableFile, overwrite = true)
            PendingRenderUploadRepository.insert(
                PendingRenderUpload(
                    id = id,
                    jobId = job.id,
                    movieId = job.movieId,
                    objectKey = objectKey,
                    localFilePath = durableFile.absolutePath,
                    durationSeconds = durationSeconds,
                    aspectRatio = aspectRatio,
                    lastError = cause.message,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        logger.info("Persisted pending render upload $id for job ${job.id} at ${durableFile.absolutePath}")
        // Make sure the retry worker is running so this upload is re-attempted periodically.
        RenderUploadRetryService.start()
    }

    /**
     * Records the completed [RenderRecord], marks the movie COMPLETED (if still RENDERING) and the
     * job COMPLETED with its [uploadedUrl], and broadcasts the success event. Shared by the initial
     * render and [RenderUploadRetryService] so a retried upload finishes exactly like a first-try one.
     */
    suspend fun finalizeSuccessfulUpload(
        jobId: String,
        movieId: String,
        uploadedUrl: String,
        durationSeconds: Double,
        aspectRatio: String
    ) {
        withContext(Dispatchers.IO) {
            // Every render is kept: replayable and downloadable at any time.
            RenderRepository.insert(
                RenderRecord(
                    id = UUID.randomUUID().toString(),
                    movieId = movieId,
                    url = uploadedUrl,
                    durationSeconds = durationSeconds,
                    aspectRatio = aspectRatio,
                    createdAt = System.currentTimeMillis()
                )
            )

            // The movie leaves RENDERING once the render finishes.
            MovieRepository.getById(movieId)?.let { current ->
                if (current.status == MovieStatus.RENDERING) {
                    MovieRepository.update(current.copy(status = MovieStatus.COMPLETED))
                }
            }

            JobRepository.getById(jobId)?.let { current ->
                JobRepository.update(current.copy(status = JobStatus.COMPLETED, resultUrl = uploadedUrl, error = null))
            }
        }

        JobWebSocketManager.broadcast(
            JobProgressEvent(
                jobId = jobId,
                movieId = movieId,
                status = JobStatus.COMPLETED,
                progress = 100,
                message = "Rendering completed successfully",
                resultUrl = uploadedUrl,
                jobType = JobType.FFMPEG_RENDER
            )
        )
    }

    /**
     * Retries the OSS upload for a single [PendingRenderUpload]. On success the render is finalized
     * (see [finalizeSuccessfulUpload]), the durable local file and the pending record are removed,
     * and `true` is returned. On failure the record's attempt counters/error are updated and `false`
     * is returned so the caller keeps it for the next retry sweep. A pending record whose local file
     * has gone missing is dropped (returns `true`) since it can never be uploaded.
     */
    suspend fun retryPendingUpload(pending: PendingRenderUpload): Boolean {
        val file = File(pending.localFilePath)
        if (!file.exists()) {
            logger.warn(
                "Pending render upload ${pending.id} references a missing local file (${pending.localFilePath}); dropping it."
            )
            withContext(Dispatchers.IO) { runCatching { PendingRenderUploadRepository.delete(pending.id) } }
            return true
        }
        return try {
            logger.info("Retrying OSS upload for pending render ${pending.id} (job ${pending.jobId}), attempt ${pending.attempts + 1}.")
            val uploadedUrl = withContext(Dispatchers.IO) { OssService.uploadFile(pending.objectKey, file) }
            finalizeSuccessfulUpload(
                pending.jobId, pending.movieId, uploadedUrl, pending.durationSeconds, pending.aspectRatio
            )
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
                runCatching { PendingRenderUploadRepository.delete(pending.id) }
            }
            logger.info("Pending render ${pending.id} uploaded successfully on retry. resultUrl: $uploadedUrl")
            true
        } catch (e: Exception) {
            logger.warn("Retry of pending render upload ${pending.id} failed: ${e.message}")
            withContext(Dispatchers.IO) {
                runCatching {
                    PendingRenderUploadRepository.update(
                        pending.copy(
                            attempts = pending.attempts + 1,
                            lastAttemptAt = System.currentTimeMillis(),
                            lastError = e.message
                        )
                    )
                }
            }
            false
        }
    }

    /**
     * Downscales a still into [outFile] so neither side exceeds [maxSide]. Returns the usable file
     * (fitted output on success, otherwise the original [src]) — never null unless [src] is missing.
     */
    internal fun prepareStillImage(src: File, outFile: File, maxSide: Int): File? {
        if (!src.exists() || src.length() == 0L) return null
        if (maxSide < 2) return src
        return try {
            val pb = ProcessBuilder(
                MediaUtil.ffmpegBinary, "-y",
                "-i", src.absolutePath,
                "-vf", "scale=w=$maxSide:h=$maxSide:force_original_aspect_ratio=decrease",
                outFile.absolutePath
            ).redirectErrorStream(true)
            val proc = pb.start()
            // Drain so a chatty ffmpeg can't fill the pipe.
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && outFile.exists() && outFile.length() > 0L) {
                outFile
            } else {
                logger.warn(
                    "Still-image prepare failed for ${src.name} (exit $code); using original. $output"
                        .take(400)
                )
                src
            }
        } catch (e: Exception) {
            logger.warn("Still-image prepare threw for ${src.name}: ${e.message}; using original.")
            src
        }
    }

    /**
     * True when [trackClips] can be built with the memory-safe concat path: no timeline overlaps
     * (after bridging) and no transitions that need a per-clip overlay against the layer underneath
     * (SLIDE x/y, CIRCLE / VIGNETTE / VORONOI spatial masks). ALPHA / NOISE / PIXELATE stay here
     * and are applied as overlay alpha when the opaque track file is composited.
     */
    internal fun canConcatComposeTrack(trackClips: List<Clip>): Boolean {
        if (trackClips.size <= 1) {
            val only = trackClips.singleOrNull() ?: return true
            return !needsPerClipOverlayTransition(only)
        }
        val sorted = trackClips.sortedBy { it.timelineStart }
        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val aEnd = bridgedClipEnd(a, sorted)
            // Anything past a hair of float noise counts as a real overlap → overlay path.
            if (aEnd > b.timelineStart + 1e-4f) return false
        }
        return sorted.none { needsPerClipOverlayTransition(it) }
    }

    /** Spatial / positional transitions that the opaque concat path cannot express against the base. */
    private fun needsPerClipOverlayTransition(clip: Clip): Boolean {
        val type = parseEffectsConfig(clip.effectsConfig).transition?.type ?: return false
        return type == TransitionType.SLIDE ||
            type == TransitionType.CIRCLE ||
            type == TransitionType.VIGNETTE ||
            type == TransitionType.VORONOI
    }

    /**
     * Overlay that composites a pre-rendered (opaque H.264) track onto [currentVideoTag].
     *
     * Segment files bake timeline gaps as solid black, so the overlay is `enable=`-gated to the
     * union of the track's clip windows. Without that gate a higher-z track's black gaps fully
     * cover whatever was already composited — the first video track renders as solid black.
     *
     * Fade-style transitions cannot live in those opaque segments (that was a fade-from-black).
     * When any clip fades, the overlay is converted to yuva with a time-varying alpha so the
     * incoming clip alpha-blends over whatever is already composited — matching the live preview.
     */
    internal fun preRenderedTrackOverlayFilter(
        currentVideoTag: String,
        overlayInputIndex: Int,
        outputTag: String,
        trackClips: List<Clip>
    ): String {
        val enable = trackClipsEnableExpression(trackClips)
        val enableArg = if (enable.isEmpty()) "" else ":enable='$enable'"
        val alpha = trackClipsAlphaExpression(trackClips)
        if (alpha.isEmpty()) {
            return "[$currentVideoTag][$overlayInputIndex:v]overlay=eof_action=pass:format=auto$enableArg[$outputTag]"
        }
        val alphaTag = "${outputTag}_a"
        return "[$overlayInputIndex:v]format=yuva420p," +
            "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='$alpha'[$alphaTag];" +
            "[$currentVideoTag][$alphaTag]overlay=eof_action=pass:format=auto$enableArg[$outputTag]"
    }

    /**
     * FFmpeg `enable` expression that is 1 during any of [trackClips]' display windows and 0 in
     * the gaps between them. `between()` results are summed so overlapping windows still enable.
     */
    internal fun trackClipsEnableExpression(trackClips: List<Clip>): String {
        if (trackClips.isEmpty()) return ""
        val sorted = trackClips.sortedBy { it.timelineStart }
        return sorted.joinToString("+") { clip ->
            val start = quantizeTimelineSeconds(clip.timelineStart.toDouble())
            val end = quantizeTimelineSeconds(bridgedClipEnd(clip, sorted).toDouble())
            "between(t,${start.ff()},${end.ff()})"
        }
    }

    /**
     * FFmpeg `geq` alpha expression for a pre-rendered track overlay. 0 in gaps, a 0→255 ramp
     * across each clip's fade-in window, then 255 for the rest of the clip. Empty when no clip
     * has a fade-style transition, so the opaque overlay path can skip the yuva conversion.
     */
    internal fun trackClipsAlphaExpression(trackClips: List<Clip>): String {
        if (trackClips.none { clipTransitionFadeSeconds(it) > 0.0 }) return ""
        val sorted = trackClips.sortedBy { it.timelineStart }
        return sorted.joinToString("+") { clip ->
            // Same frame grid as the segment files, so the ramp opens on the frame the clip
            // pixels actually start — not several frames after they have already appeared.
            val start = quantizeTimelineSeconds(clip.timelineStart.toDouble())
            val end = quantizeTimelineSeconds(bridgedClipEnd(clip, sorted).toDouble())
            val fade = clipTransitionFadeSeconds(clip)
            val window = "between(T,${start.ff()},${end.ff()})"
            if (fade <= 0.0) {
                "$window*255"
            } else {
                val fadeEnd = start + fade
                "$window*if(lt(T,${fadeEnd.ff()}),255*(T-${start.ff()})/${fade.ff()},255)"
            }
        }
    }

    /**
     * Duration of a fade-style transition on [clip], or 0 when there is none (including SLIDE,
     * which is positional rather than an alpha ramp). Clamped to the clip's media duration.
     */
    internal fun clipTransitionFadeSeconds(clip: Clip): Double {
        val transition = parseEffectsConfig(clip.effectsConfig).transition ?: return 0.0
        if (transition.type == TransitionType.NONE || transition.type == TransitionType.SLIDE) return 0.0
        val clipDur = (clip.trimOut - clip.trimIn).toDouble()
        if (clipDur <= 0.0) return 0.0
        return transition.durationSeconds.coerceIn(TRANSITION_MIN_SECONDS, clipDur)
    }

    /** Export frame rate. Segment files, the canvas, and fade windows all share this grid. */
    internal const val RENDER_FPS = 30

    /**
     * Timeline time snapped onto the [RENDER_FPS] grid. Segment files are built from these indices
     * so a clip's first pixel and its fade window land on the same frame.
     */
    internal fun timelineFrameIndex(seconds: Double): Int =
        (seconds * RENDER_FPS).roundToInt().coerceAtLeast(0)

    internal fun quantizeTimelineSeconds(seconds: Double): Double =
        timelineFrameIndex(seconds) / RENDER_FPS.toDouble()

    /**
     * Pre-renders a sequential track to a single H.264 file: one short FFmpeg invocation per clip
     * (and per gap), then a concat demuxer pass. Peak RSS stays near one still/decode instead of
     * holding every image input open at once.
     *
     * Each piece is an exact frame count on the [RENDER_FPS] grid, and a short source is cloned
     * out to fill its slot. Without that, concat packs later clips early (generated videos often
     * have fewer frames than the timeline duration) and a fade window — keyed to timeline time —
     * opens several frames after the new clip is already on screen.
     */
    internal fun renderTrackViaSegmentFiles(
        tempDir: File,
        trackId: String,
        trackClips: List<Clip>,
        assetsById: Map<String, Asset>,
        downloadedAssets: Map<String, File>,
        totalDuration: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        onPiece: () -> Unit = {}
    ): File {
        val pieces = mutableListOf<File>()
        var cursorFrame = 0
        val sorted = trackClips.sortedBy { it.timelineStart }
        var gapIdx = 0

        fun addBlackGapFrames(frames: Int) {
            if (frames <= 0) return
            val gapFile = File(tempDir, "gap_${trackId}_${gapIdx++}.mp4")
            renderSolidColorSegmentFile(
                outFile = gapFile,
                frameCount = frames,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                color = "black"
            )
            pieces += gapFile
            onPiece()
        }

        for (clip in sorted) {
            val asset = assetsById[clip.assetId] ?: continue
            val start = clip.timelineStart.toDouble()
            val duration = (clip.trimOut - clip.trimIn).toDouble()
            if (duration <= 0) continue
            val displayEnd = bridgedClipEnd(clip, sorted).toDouble()
            val startFrame = timelineFrameIndex(start)
            val endFrame = timelineFrameIndex(displayEnd).coerceAtLeast(startFrame + 1)
            // A previous slot that rounded onto this one wins; don't emit an overlapping piece.
            if (cursorFrame >= endFrame) continue
            val slotStart = maxOf(startFrame, cursorFrame)
            if (slotStart > cursorFrame) addBlackGapFrames(slotStart - cursorFrame)
            val clipFrames = endFrame - slotStart

            val segFile = File(tempDir, "seg_${clip.id}.mp4")
            when {
                asset.ossUrl.isBlank() -> {
                    // Placeholder / text element: render via a tiny lavfi+drawtext graph.
                    renderPlaceholderSegmentFile(
                        outFile = segFile,
                        asset = asset,
                        effects = parseEffectsConfig(clip.effectsConfig),
                        frameCount = clipFrames,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight
                    )
                }
                else -> {
                    val src = downloadedAssets[asset.id]
                        ?: throw Exception("Missing local file for asset ${asset.id} while pre-rendering")
                    renderMediaClipSegmentFile(
                        outFile = segFile,
                        clip = clip,
                        asset = asset,
                        srcFile = src,
                        effects = parseEffectsConfig(clip.effectsConfig),
                        duration = duration,
                        frameCount = clipFrames,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight
                    )
                }
            }
            pieces += segFile
            onPiece()
            cursorFrame = slotStart + clipFrames
        }

        val totalFrames = timelineFrameIndex(totalDuration)
        if (cursorFrame < totalFrames) addBlackGapFrames(totalFrames - cursorFrame)
        if (pieces.isEmpty()) {
            val blank = File(tempDir, "track_${trackId}_blank.mp4")
            renderSolidColorSegmentFile(
                blank,
                timelineFrameIndex(totalDuration).coerceAtLeast(1),
                canvasWidth,
                canvasHeight,
                "black"
            )
            return blank
        }

        val outFile = File(tempDir, "track_${trackId}.mp4")
        concatVideoFilesDemuxer(pieces, outFile)
        return outFile
    }

    /** Solid-color H.264 segment used for timeline gaps in pre-rendered tracks. */
    internal fun renderSolidColorSegmentFile(
        outFile: File,
        frameCount: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        color: String
    ) {
        val frames = frameCount.coerceAtLeast(1)
        // Generate a hair extra and cut with -frames:v so the piece is exactly [frames] long.
        // Truncating (duration * fps).toInt() used to drop a fraction of a frame per gap, and
        // those fractions accumulated into a multi-frame shift by the end of a long track.
        val dur = frames / RENDER_FPS.toDouble() + 0.1
        runFfmpegChecked(
            listOf(
                MediaUtil.ffmpegBinary, "-y",
                "-f", "lavfi",
                "-i", "color=c=$color:s=${canvasWidth}x${canvasHeight}:r=$RENDER_FPS:d=${dur.ff()}",
                "-frames:v", frames.toString(),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-an",
                outFile.absolutePath
            ),
            label = "solid-segment ${outFile.name}"
        )
    }

    /**
     * Encodes one media clip to a canvas-sized H.264 segment. Transitions are NOT baked in — the
     * concat demuxer cannot carry alpha, and an RGB fade-from-black would cover the track below.
     * Fade-style transitions are applied as overlay alpha in [preRenderedTrackOverlayFilter].
     */
    internal fun renderMediaClipSegmentFile(
        outFile: File,
        clip: Clip,
        asset: Asset,
        srcFile: File,
        effects: EffectsConfig,
        duration: Double,
        frameCount: Int,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val rawEffects = try {
            json.parseToJsonElement(clip.effectsConfig).jsonObject
        } catch (_: Exception) {
            null
        }
        val srcStart = asset.sourceOffsetSeconds + clip.trimIn
        val srcEnd = asset.sourceOffsetSeconds + clip.trimOut
        val vf = mutableListOf<String>()
        if (asset.type != AssetType.IMAGE) {
            vf.add("trim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
            vf.add("setpts=PTS-STARTPTS")
        }
        vf.add("scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=increase")
        val offsetFx = (effects.offsetX / 100.0).coerceIn(0.0, 1.0)
        val offsetFy = (effects.offsetY / 100.0).coerceIn(0.0, 1.0)
        vf.add("crop=$canvasWidth:$canvasHeight:(iw-ow)*${offsetFx.ff()}:(ih-oh)*${offsetFy.ff()}")
        vf.add("fps=$RENDER_FPS")
        appendColorGrading(vf, rawEffects)
        // Clone the last decoded frame out to the slot length. Source files are often shorter
        // than the timeline duration (stream frame count truncated below the container duration);
        // without this, concat shifts every following clip early and its fade starts late.
        val frames = frameCount.coerceAtLeast(1)
        vf.add("tpad=stop_mode=clone:stop_duration=${(frames / RENDER_FPS.toDouble() + 0.5).ff()}")
        vf.add("setsar=1")
        vf.add("format=yuv420p")

        val args = mutableListOf<String>()
        args.add(MediaUtil.ffmpegBinary); args.add("-y")
        if (asset.type == AssetType.IMAGE) {
            args.add("-loop"); args.add("1")
            args.add("-t"); args.add((duration + 0.25).ff())
            args.add("-i"); args.add(srcFile.absolutePath)
        } else {
            args.add("-i"); args.add(srcFile.absolutePath)
        }
        args.add("-vf"); args.add(vf.joinToString(","))
        args.add("-an")
        args.add("-c:v"); args.add("libx264")
        args.add("-preset"); args.add("ultrafast")
        args.add("-pix_fmt"); args.add("yuv420p")
        args.add("-frames:v"); args.add(frames.toString())
        args.add(outFile.absolutePath)
        runFfmpegChecked(args, label = "clip-segment ${clip.id}")
    }

    /** Description-only / simple text stand-in as an H.264 segment. */
    private fun renderPlaceholderSegmentFile(
        outFile: File,
        asset: Asset,
        effects: EffectsConfig,
        frameCount: Int,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val text = (asset.description ?: asset.aiPrompt ?: "")
        val lines = wrapText(text, 34, 4)
        val draws = lines.mapIndexed { index, line ->
            val yExpr =
                "(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * (canvasHeight / 12.0)).ff()}"
            "drawtext=${fontFileArg()}text='${escapeDrawtext(line)}':" +
                "fontcolor=white:fontsize=${canvasHeight / 14}:x=(w-text_w)/2:y=$yExpr"
        }
        // effects reserved for future text-element styling in the pre-render path.
        @Suppress("UNUSED_PARAMETER")
        val _effects = effects
        val frames = frameCount.coerceAtLeast(1)
        val dur = frames / RENDER_FPS.toDouble() + 0.1
        runFfmpegChecked(
            listOf(
                MediaUtil.ffmpegBinary, "-y",
                "-f", "lavfi",
                "-i", "color=c=black:s=${canvasWidth}x${canvasHeight}:r=$RENDER_FPS:d=${dur.ff()}",
                "-vf", draws.joinToString(","),
                "-an", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-frames:v", frames.toString(),
                outFile.absolutePath
            ),
            label = "placeholder-segment ${asset.id}"
        )
    }

    /**
     * Joins [pieces] end-to-end with the concat demuxer (re-encode to normalize timebases). Each
     * piece must already be canvas-sized yuv420p H.264.
     */
    internal fun concatVideoFilesDemuxer(pieces: List<File>, outFile: File) {
        require(pieces.isNotEmpty()) { "concatVideoFilesDemuxer requires at least one piece" }
        if (pieces.size == 1) {
            pieces[0].copyTo(outFile, overwrite = true)
            return
        }
        val listFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}_concat.txt")
        listFile.writeText(
            pieces.joinToString("\n") { piece ->
                // Single quotes in paths break the concat demuxer list syntax; escape them.
                val p = piece.absolutePath.replace("'", "'\\''")
                "file '$p'"
            } + "\n"
        )
        runFfmpegChecked(
            listOf(
                MediaUtil.ffmpegBinary, "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePath,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-an",
                outFile.absolutePath
            ),
            label = "concat-demuxer ${outFile.name}"
        )
    }

    /** Runs FFmpeg and throws with stderr tail when the exit code is non-zero. */
    internal fun runFfmpegChecked(args: List<String>, label: String) {
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        val proc = pb.start()
        val output = StringBuilder()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (output.length < 8_000) output.appendLine(line)
            }
        }
        val code = proc.waitFor()
        if (code != 0) {
            logger.error("FFmpeg $label failed (exit $code):\n$output")
            throw Exception(
                "FFmpeg $label failed (exit $code). ${summarizeFfmpegError(output.lines())}"
            )
        }
    }

    /**
     * Builds one full-timeline yuva track stream into [outputTag] by concatenating clip segments
     * (and transparent gaps). Returns the next free [chain] counter value.
     *
     * Prefer [renderTrackViaSegmentFiles] for real renders (keeps RAM flat). This filter-only
     * helper remains for unit tests and small in-graph compositions.
     */
    internal fun appendConcatTrack(
        filters: MutableList<String>,
        trackClips: List<Clip>,
        assetsById: Map<String, Asset>,
        assetInputMap: Map<String, Int>,
        totalDuration: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        chain: Int,
        outputTag: String
    ): Int {
        var nextChain = chain
        val pieceTags = mutableListOf<String>()
        var cursor = 0.0

        fun addTransparentGap(duration: Double) {
            if (duration <= 0.001) return
            val tag = "v_gap_${nextChain++}"
            filters.add(
                "color=c=black@0.0:s=${canvasWidth}x${canvasHeight}:r=30:d=${duration.ff()}," +
                    "format=yuva420p,setsar=1,fps=30[$tag]"
            )
            pieceTags.add(tag)
        }

        val sorted = trackClips.sortedBy { it.timelineStart }
        for (clip in sorted) {
            val asset = assetsById[clip.assetId] ?: continue
            val start = clip.timelineStart.toDouble()
            val duration = (clip.trimOut - clip.trimIn).toDouble()
            if (duration <= 0) continue
            val displayEnd = bridgedClipEnd(clip, sorted).toDouble()
            val displayDur = (displayEnd - start).coerceAtLeast(duration)

            if (start > cursor + 0.001) addTransparentGap(start - cursor)

            val pieceTag = "v_piece_${clip.id}"
            val effects = parseEffectsConfig(clip.effectsConfig)
            when {
                asset.ossUrl.isBlank() && asset.isTextElement -> {
                    appendTextElementSegment(
                        filters = filters,
                        clip = clip,
                        asset = asset,
                        effects = effects,
                        duration = duration,
                        displayDur = displayDur,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        outputTag = pieceTag
                    )
                }
                asset.ossUrl.isBlank() -> {
                    appendPlaceholderSegment(
                        filters = filters,
                        asset = asset,
                        duration = displayDur,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        outputTag = pieceTag
                    )
                }
                else -> {
                    val idx = assetInputMap[clip.assetId]
                    if (idx == null) {
                        // Missing download — keep timeline length with a transparent stand-in.
                        addTransparentGap(displayDur)
                        cursor = maxOf(cursor, displayEnd)
                        continue
                    }
                    appendMediaClipSegment(
                        filters = filters,
                        clip = clip,
                        asset = asset,
                        inputIndex = idx,
                        effects = effects,
                        duration = duration,
                        displayDur = displayDur,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        outputTag = pieceTag,
                        timelineStartForSlide = 0.0 // clip-local; concat path never uses SLIDE
                    )
                }
            }
            pieceTags.add(pieceTag)
            cursor = maxOf(cursor, displayEnd)
        }

        if (cursor < totalDuration - 0.001) addTransparentGap(totalDuration - cursor)

        when {
            pieceTags.isEmpty() -> {
                // Degenerate track: full-duration transparency.
                filters.add(
                    "color=c=black@0.0:s=${canvasWidth}x${canvasHeight}:r=30:d=${totalDuration.ff()}," +
                        "format=yuva420p,setsar=1,fps=30[$outputTag]"
                )
            }
            pieceTags.size == 1 -> {
                filters.add("[${pieceTags[0]}]format=yuva420p,setsar=1,fps=30[$outputTag]")
            }
            else -> {
                filters.add(
                    "${pieceTags.joinToString("") { "[$it]" }}" +
                        "concat=n=${pieceTags.size}:v=1:a=0,format=yuva420p,setsar=1[$outputTag]"
                )
            }
        }
        return nextChain
    }

    /**
     * Clip-local media segment (trim → scale/crop → effects → optional transition → pad) written
     * as yuva420p into [outputTag]. Used by the concat track builder.
     */
    private fun appendMediaClipSegment(
        filters: MutableList<String>,
        clip: Clip,
        asset: Asset,
        inputIndex: Int,
        effects: EffectsConfig,
        duration: Double,
        displayDur: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        outputTag: String,
        timelineStartForSlide: Double
    ) {
        val rawEffects = try {
            json.parseToJsonElement(clip.effectsConfig).jsonObject
        } catch (_: Exception) {
            null
        }
        val srcStart = asset.sourceOffsetSeconds + clip.trimIn
        val srcEnd = asset.sourceOffsetSeconds + clip.trimOut
        val videoFilters = mutableListOf<String>()
        videoFilters.add("trim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
        videoFilters.add("setpts=PTS-STARTPTS")
        videoFilters.add("scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=increase")
        val offsetFx = (effects.offsetX / 100.0).coerceIn(0.0, 1.0)
        val offsetFy = (effects.offsetY / 100.0).coerceIn(0.0, 1.0)
        videoFilters.add(
            "crop=$canvasWidth:$canvasHeight:(iw-ow)*${offsetFx.ff()}:(ih-oh)*${offsetFy.ff()}"
        )
        videoFilters.add("fps=30")
        appendColorGrading(videoFilters, rawEffects)

        val transition = effects.transition
        val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
        buildTransitionFilters(
            videoFilters, transition, transitionDur, timelineStartForSlide, canvasWidth, canvasHeight
        )

        val pad = displayDur - duration
        if (pad > 0.001) {
            // Hold the last frame across a bridged sub-frame sliver (mirrors eof_action=repeat).
            videoFilters.add("tpad=stop_mode=clone:stop_duration=${pad.ff()}")
        }
        videoFilters.add("format=yuva420p")
        videoFilters.add("setsar=1")
        filters.add("[$inputIndex:v]${videoFilters.joinToString(",")}[$outputTag]")
    }

    /** Description-only placeholder as a canvas-sized yuva segment with centered white text. */
    private fun appendPlaceholderSegment(
        filters: MutableList<String>,
        asset: Asset,
        duration: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        outputTag: String
    ) {
        val text = (asset.description ?: asset.aiPrompt ?: "")
        val lines = wrapText(text, 34, 4)
        val parts = mutableListOf<String>()
        parts.add(
            "color=c=black@0.0:s=${canvasWidth}x${canvasHeight}:r=30:d=${duration.ff()},format=yuva420p"
        )
        lines.forEachIndexed { index, line ->
            val yExpr =
                "(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * (canvasHeight / 12.0)).ff()}"
            parts.add(
                "drawtext=${fontFileArg()}text='${escapeDrawtext(line)}':" +
                    "fontcolor=white:fontsize=${canvasHeight / 14}:x=(w-text_w)/2:y=$yExpr"
            )
        }
        parts.add("setsar=1")
        parts.add("fps=30")
        filters.add("${parts.joinToString(",")}[$outputTag]")
    }

    /**
     * First-class TEXT element as a clip-local yuva segment (no timeline PTS shift / overlay).
     * Transition math runs in clip-local time so it matches the overlay path's fade/reveal.
     */
    private fun appendTextElementSegment(
        filters: MutableList<String>,
        clip: Clip,
        asset: Asset,
        effects: EffectsConfig,
        duration: Double,
        displayDur: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        outputTag: String
    ) {
        val textCfg = effects.text ?: TextConfig()
        val rawText = (asset.description ?: asset.aiPrompt ?: "").ifBlank { "" }
        val fontSize =
            (textCfg.fontSizeSp * canvasHeight / TEXT_REFERENCE_HEIGHT).toInt().coerceIn(8, canvasHeight)
        val wrapWidth = textSafeAreaWrapWidth(canvasWidth, fontSize)
        val lines = wrapText(rawText, wrapWidth, 40)
        val fontColor = ffmpegDrawtextColor(textCfg.color)
        val bgColor = ffmpegColorHex(textCfg.backgroundColor)
        val bgAlpha = ffmpegColorAlpha(textCfg.backgroundColor)
        val lineSpacing = textLineSpacing(fontSize)
        val xExpr = textSafeAreaXExpr(canvasWidth)

        // Credits crawl when enabled; otherwise the automatic overflow read-through. Clip-local `t`.
        val scrollExpr = textElementYScrollExpr(textCfg, duration, lines.size, lineSpacing)

        val layer = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val yExpr =
                "'(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * lineSpacing).ff()}$scrollExpr'"
            layer.add(
                "drawtext=${fontFileArg(textCfg.fontFamily, textCfg.fontUrl)}text='${escapeDrawtext(line)}':" +
                    "fontcolor=$fontColor:fontsize=$fontSize:x=$xExpr:y=$yExpr"
            )
        }
        val transition = effects.transition
        val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
        // Clip-local transition window (start=0).
        buildTransitionFilters(layer, transition, transitionDur, 0.0, canvasWidth, canvasHeight)

        val pad = displayDur - duration
        if (pad > 0.001) {
            layer.add("tpad=stop_mode=clone:stop_duration=${pad.ff()}")
        }
        layer.add("format=yuva420p")
        layer.add("setsar=1")
        layer.add("fps=30")

        val head =
            "color=c=$bgColor@${bgAlpha.ff()}:s=${canvasWidth}x${canvasHeight}:r=30:d=${(displayDur + 0.05).ff()},format=yuva420p"
        filters.add("$head,${layer.joinToString(",")}[$outputTag]")
    }

    private data class OverlayStepResult(val videoTag: String, val chain: Int)

    /**
     * Legacy per-clip overlay: processes one clip and composites it onto [currentVideoTag] with
     * `enable='between(t,start,displayEnd)'`. Used when a track can't take the concat path.
     */
    private fun overlayClipOntoVideo(
        filters: MutableList<String>,
        currentVideoTag: String,
        clip: Clip,
        trackClips: List<Clip>,
        assetsById: Map<String, Asset>,
        assetInputMap: Map<String, Int>,
        canvasWidth: Int,
        canvasHeight: Int,
        chain: Int
    ): OverlayStepResult? {
        val asset = assetsById[clip.assetId] ?: return null
        val duration = (clip.trimOut - clip.trimIn).toDouble()
        if (duration <= 0) return null
        val start = clip.timelineStart.toDouble()
        val displayEnd = bridgedClipEnd(clip, trackClips).toDouble()
        val effects = parseEffectsConfig(clip.effectsConfig)
        var nextChain = chain

        if (asset.ossUrl.isBlank()) {
            if (asset.isTextElement) {
                val tag = renderTextElementLayer(
                    filters, currentVideoTag, clip, asset, effects,
                    start, displayEnd, duration, canvasWidth, canvasHeight
                )
                return OverlayStepResult(tag, nextChain)
            }
            val text = (asset.description ?: asset.aiPrompt ?: "")
            val nextTag = "v_text_${nextChain++}"
            val lines = wrapText(text, 34, 4)
            var tag = currentVideoTag
            lines.forEachIndexed { index, line ->
                val outTag = if (index == lines.lastIndex) nextTag else "v_textline_${nextChain++}"
                val yExpr =
                    "(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * (canvasHeight / 12.0)).ff()}"
                filters.add(
                    "[$tag]drawtext=${fontFileArg()}text='${escapeDrawtext(line)}':" +
                        "fontcolor=white:fontsize=${canvasHeight / 14}:x=(w-text_w)/2:y=$yExpr:" +
                        "enable='between(t,${start.ff()},${displayEnd.ff()})'[$outTag]"
                )
                tag = outTag
            }
            return OverlayStepResult(nextTag, nextChain)
        }

        val idx = assetInputMap[clip.assetId] ?: return null
        val rawEffects = try {
            json.parseToJsonElement(clip.effectsConfig).jsonObject
        } catch (_: Exception) {
            null
        }
        val srcStart = asset.sourceOffsetSeconds + clip.trimIn
        val srcEnd = asset.sourceOffsetSeconds + clip.trimOut
        val videoFilters = mutableListOf<String>()
        videoFilters.add("trim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
        videoFilters.add("setpts=PTS-STARTPTS")
        videoFilters.add("scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=increase")
        val offsetFx = (effects.offsetX / 100.0).coerceIn(0.0, 1.0)
        val offsetFy = (effects.offsetY / 100.0).coerceIn(0.0, 1.0)
        videoFilters.add(
            "crop=$canvasWidth:$canvasHeight:(iw-ow)*${offsetFx.ff()}:(ih-oh)*${offsetFy.ff()}"
        )
        videoFilters.add("fps=30")
        appendColorGrading(videoFilters, rawEffects)

        val transition = effects.transition
        val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
        val overlayExtra = buildTransitionFilters(
            videoFilters, transition, transitionDur, start, canvasWidth, canvasHeight
        )
        videoFilters.add("setpts=PTS+${start.ff()}/TB")

        val trimmedTag = "v_trimmed_${clip.id}"
        filters.add("[$idx:v]${videoFilters.joinToString(",")}[$trimmedTag]")
        val nextVideoTag = "v_overlaid_${clip.id}"
        filters.add(
            "[$currentVideoTag][$trimmedTag]overlay=eof_action=repeat:" +
                "enable='between(t,${start.ff()},${displayEnd.ff()})'$overlayExtra[$nextVideoTag]"
        )
        return OverlayStepResult(nextVideoTag, nextChain)
    }

    /** Shared color-grading branch used by both concat segments and legacy overlay clips. */
    private fun appendColorGrading(videoFilters: MutableList<String>, rawEffects: JsonObject?) {
        val cb = rawEffects?.get("colorbalance")?.jsonObject
            ?: rawEffects?.get("colorBalance")?.jsonObject
        if (cb != null) {
            fun v(k: String) = cb[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
            videoFilters.add(
                "colorbalance=rs=${v("rs").ff()}:gs=${v("gs").ff()}:bs=${v("bs").ff()}:" +
                    "rm=${v("rm").ff()}:gm=${v("gm").ff()}:bm=${v("bm").ff()}:" +
                    "rh=${v("rh").ff()}:gh=${v("gh").ff()}:bh=${v("bh").ff()}"
            )
        } else {
            val brightness = rawEffects?.get("brightness")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val contrast = rawEffects?.get("contrast")?.jsonPrimitive?.doubleOrNull ?: 1.0
            val saturation = rawEffects?.get("saturation")?.jsonPrimitive?.doubleOrNull ?: 1.0
            if (brightness != 0.0 || contrast != 1.0 || saturation != 1.0) {
                videoFilters.add(
                    "eq=brightness=${brightness.ff()}:contrast=${contrast.ff()}:saturation=${saturation.ff()}"
                )
            }
        }
    }

    /**
     * Resolves a render resolution for the given aspect ratio, keeping the longest side at 1280px
     * and forcing even dimensions (required by the yuv420p pixel format).
     */
    private fun resolutionForAspectRatio(aspectRatio: String): Pair<Int, Int> {
        val ratio = aspectRatioToFloat(aspectRatio)
        val longest = 1280
        val (w, h) = if (ratio >= 1f) {
            longest to (longest / ratio).toInt()
        } else {
            (longest * ratio).toInt() to longest
        }
        fun even(v: Int) = (if (v % 2 == 0) v else v + 1).coerceAtLeast(2)
        return even(w) to even(h)
    }

    /**
     * Appends the FFmpeg filters that composite the incoming layer over the accumulated video for
     * [transition] (running clip-local 0..[transitionDur] from the clip's [start]) and returns the
     * extra `overlay=...` arguments (slide x/y). Shared by media clips and text elements so both
     * use identical transition math, mirroring `TransitionSpec.visualAt(...)` in core/Models.kt so
     * the export matches the live preview. Returns "" (and adds nothing) when there is no transition.
     */
    internal fun buildTransitionFilters(
        videoFilters: MutableList<String>,
        transition: TransitionSpec?,
        transitionDur: Double,
        start: Double,
        canvasWidth: Int,
        canvasHeight: Int
    ): String {
        var overlayExtra = ""
        if (transition == null || transition.type == TransitionType.NONE) return overlayExtra
        when (transition.type) {
            TransitionType.SLIDE -> {
                // Slide the clip in from the chosen edge across the transition window.
                val s = start.ff()
                val d = transitionDur.ff()
                val p = "(t-$s)/$d"
                overlayExtra = when (transition.direction) {
                    SlideDirection.FROM_RIGHT -> ":x='if(lt(t-$s,$d),W-W*$p,0)'"
                    SlideDirection.FROM_LEFT -> ":x='if(lt(t-$s,$d),-W+W*$p,0)'"
                    SlideDirection.FROM_TOP -> ":y='if(lt(t-$s,$d),-H+H*$p,0)'"
                    SlideDirection.FROM_BOTTOM -> ":y='if(lt(t-$s,$d),H-H*$p,0)'"
                }
            }
            TransitionType.CIRCLE -> {
                // Growing centered circular reveal, applied as a MULTIPLIER on the layer's own alpha
                // (`alpha(X,Y)*mask`) rather than overwriting it: a fully-transparent text-element
                // background stays see-through inside the revealed circle, while opaque media clips
                // (alpha 255) are unaffected. Overwriting with `a='...,255,0'` made every text layer
                // opaque once the circle filled the frame — the transparency bug this fixes.
                videoFilters.add("format=yuva420p")
                videoFilters.add(
                    "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':" +
                        "a='alpha(X,Y)*if(lte(hypot(X-W/2,Y-H/2),hypot(W/2,H/2)*min(T/$transitionDur,1)),1,0)'"
                )
            }
            TransitionType.VIGNETTE -> {
                // Growing centered, aspect-matched elliptical reveal with a soft feathered edge (an
                // alpha mask), no fade — the vignette iris. The default DOM preview falls back to a
                // plain fade; this and the WebGL shader draw the real oval.
                videoFilters.add("format=yuva420p")
                // As with CIRCLE, the feathered oval mask MULTIPLIES the layer's own alpha (the
                // 0..255 ramp is normalized to 0..1) instead of replacing it, so a transparent
                // text-element background stays see-through; opaque media clips are unchanged.
                videoFilters.add(
                    "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='alpha(X,Y)*(${vignetteAlphaExpression(transitionDur)})/255'"
                )
            }
            TransitionType.VORONOI -> {
                // Resolve out of animated voronoi cells while cross-fading in. Use `gbrp` + alpha
                // (`gbrap`) rather than plain `gbrp` and displace the ALPHA plane with the same
                // voronoi sampling as the color planes: `gbrp` has no alpha, so converting a
                // transparent text-element layer through it dropped its transparency (the layer came
                // back fully opaque), making the text background solid. Keeping the alpha plane lets
                // the transparent background resolve out of the cells too; opaque media clips keep
                // their solid alpha and look identical.
                videoFilters.add("format=gbrap")
                val voronoiExpr = voronoiGeqExpression(transitionDur)
                videoFilters.add(
                    "geq=r='$voronoiExpr':g='$voronoiExpr':b='$voronoiExpr':a='$voronoiExpr':" +
                        "enable='between(t,0,$transitionDur)'"
                )
                videoFilters.add("format=yuva420p")
                videoFilters.add("fade=t=in:st=0:d=$transitionDur:alpha=1")
            }
            else -> {
                // ALPHA / NOISE / PIXELATE all cross-fade in.
                videoFilters.add("format=yuva420p")
                videoFilters.add("fade=t=in:st=0:d=$transitionDur:alpha=1")
            }
        }
        // Grain / mosaic layered on top of the fade for the textured transitions.
        when (transition.type) {
            TransitionType.NOISE ->
                videoFilters.add("noise=c3s=48:c3f=t:enable='between(t,0,$transitionDur)'")
            TransitionType.PIXELATE -> {
                val maxBlock = 48.0
                val blockPx = "max(1,$maxBlock*(1-min(t/$transitionDur,1)))"
                videoFilters.add(
                    "scale=w='max(2,2*floor($canvasWidth/($blockPx)/2))':" +
                        "h='max(2,2*floor($canvasHeight/($blockPx)/2))':eval=frame:flags=neighbor"
                )
                videoFilters.add("scale=$canvasWidth:$canvasHeight:flags=neighbor")
            }
            else -> {}
        }
        return overlayExtra
    }

    /**
     * Builds the FFmpeg filter chain for a first-class TEXT element and overlays it over
     * [currentVideoTag], returning the new current video tag. The element is its own canvas-sized
     * layer: a color source painted with the [TextConfig.backgroundColor] (fully transparent by
     * default, so lower video shows through), the text drawn (wrapped, centered) in the configured
     * color / font size, then the clip's transition applied via [buildTransitionFilters] and the
     * whole layer PTS-shifted to [start] and overlaid between [start] and [end] — mirroring the
     * styled text preview so the export matches it.
     *
     * Text that fits stays vertically centered. Text that overflows the canvas height instead
     * scrolls smoothly from its top to its bottom (plus ~2 extra blank lines so the last line can
     * be read) across the clip's duration — unless a credits crawl is enabled, in which case the
     * block travels from a percentage of the frame below the bottom edge to a percentage above the
     * top edge. Both match the preview's `TextClip`.
     */
    private fun renderTextElementLayer(
        filters: MutableList<String>,
        currentVideoTag: String,
        clip: Clip,
        asset: Asset,
        effects: EffectsConfig,
        start: Double,
        end: Double,
        duration: Double,
        canvasWidth: Int,
        canvasHeight: Int
    ): String {
        val textCfg = effects.text ?: TextConfig()
        val rawText = (asset.description ?: asset.aiPrompt ?: "").ifBlank { "" }
        // Font size is authored relative to a TEXT_REFERENCE_HEIGHT-tall canvas (same as the preview).
        val fontSize = (textCfg.fontSizeSp * canvasHeight / TEXT_REFERENCE_HEIGHT).toInt().coerceIn(8, canvasHeight)
        // Wrap within a horizontally padded safe area (mirrors the preview's horizontal padding on
        // TextClip) so wrapped lines never reach/overflow the frame's edges. Vertically the block is
        // allowed to grow past the canvas (a generous line cap keeps the full text) — that overflow
        // is exactly what the scroll below animates, mirroring the preview's unbounded TextClip.
        val wrapWidth = textSafeAreaWrapWidth(canvasWidth, fontSize)
        val lines = wrapText(rawText, wrapWidth, 40)
        val fontColor = ffmpegDrawtextColor(textCfg.color)
        val bgColor = ffmpegColorHex(textCfg.backgroundColor)
        val bgAlpha = ffmpegColorAlpha(textCfg.backgroundColor)
        // Natural, uncompressed line spacing so a tall block genuinely overflows the canvas and
        // scrolls. Compressing it to fit (as we used to) kept every block inside the frame, so the
        // scroll term below was always ~0 and the text never moved.
        val lineSpacing = textLineSpacing(fontSize)
        val xExpr = textSafeAreaXExpr(canvasWidth)

        // Vertical offset added to every centered line. Credits scroll (when enabled) crawls from
        // a percentage of the frame below the bottom edge to a percentage above the top edge.
        // Otherwise overflowing text keeps the automatic read-through scroll. `t` is clip-local
        // (this layer is PTS-shifted to `start` only afterwards).
        val scrollExpr = textElementYScrollExpr(textCfg, duration, lines.size, lineSpacing)

        val layer = mutableListOf<String>()
        // Draw each wrapped line, centered, stacking symmetrically around the vertical center (plus
        // the shared scroll offset above). The x position is clamped into the horizontally padded
        // safe area as a hard guarantee against overflow (the wrap estimate above is only a
        // heuristic and actual glyph metrics can vary).
        lines.forEachIndexed { index, line ->
            val yExpr = "'(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * lineSpacing).ff()}$scrollExpr'"
            layer.add(
                "drawtext=${fontFileArg(textCfg.fontFamily, textCfg.fontUrl)}text='${escapeDrawtext(line)}':" +
                    "fontcolor=$fontColor:fontsize=$fontSize:x=$xExpr:y=$yExpr"
            )
        }
        // Transition-in over whatever plays beneath (clip-local 0..transitionDur).
        val transition = effects.transition
        val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
        val overlayExtra = buildTransitionFilters(layer, transition, transitionDur, start, canvasWidth, canvasHeight)
        // Shift the layer's PTS so it appears at the clip's timeline position.
        layer.add("setpts=PTS+${start.ff()}/TB")

        // A canvas-sized color source is this layer's background (transparent when bgAlpha = 0).
        val head = "color=c=$bgColor@${bgAlpha.ff()}:s=${canvasWidth}x${canvasHeight}:r=30:d=${(duration + 1.0).ff()},format=yuva420p"
        val layerTag = "v_text_layer_${clip.id}"
        filters.add("$head,${layer.joinToString(",")}[$layerTag]")

        val nextVideoTag = "v_overlaid_text_${clip.id}"
        filters.add(
            "[$currentVideoTag][$layerTag]overlay=eof_action=pass:enable='between(t,${start.ff()},${end.ff()})'$overlayExtra[$nextVideoTag]"
        )
        return nextVideoTag
    }

    /** FFmpeg `0xRRGGBB` color literal from a `#RRGGBB`/`#AARRGGBB` hex (defaults to white). */
    internal fun ffmpegColorHex(hex: String): String {
        val cleaned = hex.removePrefix("#")
        val isHex = cleaned.isNotEmpty() && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        val rgb = when {
            isHex && cleaned.length == 8 -> cleaned.substring(2) // AARRGGBB -> RRGGBB
            isHex && cleaned.length == 6 -> cleaned
            else -> "FFFFFF"
        }
        return "0x$rgb"
    }

    /** Alpha (0..1) from a `#RRGGBB`/`#AARRGGBB` hex; opaque (1.0) when there is no alpha channel. */
    internal fun ffmpegColorAlpha(hex: String): Double {
        val cleaned = hex.removePrefix("#")
        val isHex = cleaned.length == 8 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        return if (isHex) (cleaned.substring(0, 2).toIntOrNull(16) ?: 255) / 255.0 else 1.0
    }

    /** A `drawtext` fontcolor argument (`0xRRGGBB`, plus `@alpha` when the color isn't opaque). */
    internal fun ffmpegDrawtextColor(hex: String): String {
        val alpha = ffmpegColorAlpha(hex)
        val base = ffmpegColorHex(hex)
        return if (alpha >= 0.999) base else "$base@$alpha"
    }

    /**
     * The per-pixel `geq` expression that reproduces the WebGL preview's VORONOI transition (see the
     * fragment shader in `WebGLPreview.wasmJs.kt`): each pixel is sampled at the nearest random cell
     * seed found over a 3x3 grid of cells whose size shrinks from 60px to 1px as the transition
     * completes, so the clip resolves out of voronoi cells. It mirrors the shader exactly:
     *
     * - `cellPx = max(1, 60 * voronoiFraction)` with `voronoiFraction = 1 - progress` and
     *   `progress = T / transitionDur` (T is clip-local here — the chain runs before the PTS shift).
     * - grid coord `g = pixel / cellPx`; over the 3x3 neighbourhood of `floor(g)` the seed of a cell
     *   is `cell + hash22(cell)` (the same Dave-Hoskins `hash22`), and the nearest seed's position
     *   (in pixels, `seed * cellPx`) is where the frame is sampled — a true voronoi displacement.
     *
     * FFmpeg's expression evaluator only exposes 10 `st()`/`ld()` slots (indices 0-9, higher indices
     * are clamped), so cheap values (cellPx, the grid coord) are recomputed inline instead of stored:
     * slot 0 holds the nearest squared distance, slots 1/2 the nearest seed (in cell units), and
     * slots 3-9 are per-neighbour scratch. The whole chain is gated to the transition window with the
     * caller's `enable='between(t,0,dur)'`, so the clip plays crisp afterwards.
     */
    internal fun voronoiGeqExpression(transitionDur: Double): String {
        val cell = "max(1,60*(1-min(T/$transitionDur,1)))"
        val sb = StringBuilder()
        // 0 = best (nearest) squared distance, 1/2 = nearest seed X/Y in cell units (default = g).
        sb.append("st(0,100000);st(1,X/($cell));st(2,Y/($cell))")
        for (dy in -1..1) {
            for (dx in -1..1) {
                // The candidate cell (grid coord of this neighbour): 3 = cellX, 4 = cellY.
                sb.append(";st(3,floor(X/($cell))+($dx));st(4,floor(Y/($cell))+($dy))")
                // hash22(cell): p3 = fract(cell.xyx * (0.1031, 0.1030, 0.0973)) -> slots 5,6,7.
                sb.append(";st(5,ld(3)*0.1031);st(5,ld(5)-floor(ld(5)))")
                sb.append(";st(6,ld(4)*0.1030);st(6,ld(6)-floor(ld(6)))")
                sb.append(";st(7,ld(3)*0.0973);st(7,ld(7)-floor(ld(7)))")
                // p3 += dot(p3, p3.yzx + 33.33) -> a/b/c reuse slots 5/6/7.
                sb.append(";st(8,ld(5)*(ld(6)+33.33)+ld(6)*(ld(7)+33.33)+ld(7)*(ld(5)+33.33))")
                sb.append(";st(5,ld(5)+ld(8));st(6,ld(6)+ld(8));st(7,ld(7)+ld(8))")
                // seed = cell + fract((a+b)*c, (a+c)*b) -> seedX in slot 8, seedY in slot 9.
                sb.append(";st(8,ld(3)+((ld(5)+ld(6))*ld(7)-floor((ld(5)+ld(6))*ld(7))))")
                sb.append(";st(9,ld(4)+((ld(5)+ld(7))*ld(6)-floor((ld(5)+ld(7))*ld(6))))")
                // Squared distance from g to this seed -> slot 5; keep it when it is the new nearest.
                sb.append(";st(5,(X/($cell)-ld(8))*(X/($cell)-ld(8))+(Y/($cell)-ld(9))*(Y/($cell)-ld(9)))")
                sb.append(";st(6,lt(ld(5),ld(0)))")
                sb.append(";st(1,ld(6)*ld(8)+(1-ld(6))*ld(1))")
                sb.append(";st(2,ld(6)*ld(9)+(1-ld(6))*ld(2))")
                sb.append(";st(0,ld(6)*ld(5)+(1-ld(6))*ld(0))")
            }
        }
        // Sample the (already cover-cropped) frame at the nearest seed, converted back to pixels.
        sb.append(";p(clip(ld(1)*($cell),0,W-1),clip(ld(2)*($cell),0,H-1))")
        return sb.toString()
    }

    /**
     * The per-pixel `geq` alpha expression that reproduces the WebGL preview's VIGNETTE transition
     * (see the fragment shader in `WebGLPreview.wasmJs.kt`): a centered, aspect-matched *ellipse*
     * with a soft, feathered edge that grows from the center outward — a vignette iris. Each pixel's
     * normalized elliptical distance is
     *
     * - `en = hypot((X-W/2)/(W/2), (Y-H/2)/(H/2)) / sqrt(2)` — 0 at the center, 1 at a corner. Each
     *   axis is normalized by its half-extent so the reveal is an ellipse in pixel space matching the
     *   movie's aspect, exactly like the shader's `(vPos-0.5)*2` in normalized stage space.
     *
     * The reveal radius grows to `1 + feather` over the window (`progress = min(T/dur, 1)`) so the
     * corners finish fully opaque, and the alpha ramps linearly across a `feather`-wide band:
     * `a = clip(255 * (progress*(1+feather) - en) / feather, 0, 255)`. `T` is clip-local (the chain
     * runs before the PTS shift); the caller does not gate it because the mask saturates to fully
     * opaque once the window ends, so the clip plays crisp afterwards.
     */
    internal fun vignetteAlphaExpression(transitionDur: Double): String {
        val feather = "0.15"
        val corner = "1.41421356" // sqrt(2): center-to-corner distance in the normalized ellipse space
        // Aspect-matched oval: normalize each axis by its half-extent (W/2, H/2) so the reveal is an
        // ellipse in pixel space, then divide by the corner distance so en is 0 at center, 1 at a corner.
        val en = "hypot((X-W/2)/(W/2),(Y-H/2)/(H/2))/$corner"
        // The reveal radius grows past 1 (up to 1+feather) so the corners are fully opaque at the end.
        val reveal = "min(T/$transitionDur,1)*(1+$feather)"
        return "clip(255*(($reveal)-($en))/$feather,0,255)"
    }

    /**
     * The per-pixel `geq` alpha expression for a rounded rectangle with the given corner [radius]
     * (in pixels), evaluated over the box layer's own `WxH`: pixels inside the rounded body get
     * [alpha] (0..255), pixels beyond the rounded corners get 0. Used to give burned-in captions
     * the same rounded, translucent background as the live preview's caption chip
     * (`RoundedCornerShape(8.dp)` + `Color.Black.copy(alpha=0.45f)`), which `drawtext`'s own `box=`
     * option cannot produce (it only draws square corners — see the issue). Mirrors the community
     * workaround: a pixel in a corner region is opaque only when it lies within [radius] of that
     * corner's rounding center; everywhere else in the rectangle is opaque.
     */
    internal fun roundedRectAlphaExpression(radius: Int, alpha: Int): String {
        val r = radius.coerceAtLeast(1)
        return "if(gt(abs(W/2-X),W/2-$r)*gt(abs(H/2-Y),H/2-$r)," +
            "if(lte(hypot($r-(W/2-abs(W/2-X)),$r-(H/2-abs(H/2-Y))),$r),$alpha,0),$alpha)"
    }

    /**
     * The on-timeline end at which a caption chunk starting at [visStart] (with natural end
     * [visEnd]) must disappear so it never overlaps the NEXT caption: the earliest start in
     * [sortedCaptionStarts] that is strictly after [visStart], but only ever SHORTENING the window
     * (never extending it). This makes a newly started voice caption immediately replace any earlier
     * one — mirroring the live preview — so captions from overlapping voice clips never render on top
     * of each other. With no later caption the natural [visEnd] is kept. A tiny epsilon guards
     * against captions that begin at (essentially) the same instant clamping each other to a
     * zero-length window.
     */
    internal fun captionVisibleEnd(visStart: Double, visEnd: Double, sortedCaptionStarts: List<Double>): Double {
        val nextStart = sortedCaptionStarts.firstOrNull { it > visStart + 1e-6 } ?: return visEnd
        return minOf(visEnd, nextStart)
    }

    /**
     * One burned-in caption chip: on-timeline [start]..[end] window, display [text], and the
     * resolved style fields used when writing the `.ass` sidecar (see [writeAssCaptionsFile]).
     */
    internal data class CaptionEvent(
        val start: Double,
        val end: Double,
        val text: String,
        val fontFamily: String,
        val fontUrl: String,
        val fontSize: Int,
        val colorHex: String,
        val position: String,
        val bold: Boolean
    )

    /**
     * Collects every caption event that should be burned into the export from the given voice
     * [clips]. Chunks word timings 4-at-a-time (same as the live preview), maps them into timeline
     * time via each clip's window, and shortens each event so a newly started caption immediately
     * replaces any earlier one ([captionVisibleEnd]). No artificial chunk cap — libass handles
     * thousands of dialogue lines cheaply.
     */
    internal fun collectCaptionEvents(
        clips: List<Clip>,
        assetsById: Map<String, Asset>,
        canvasHeight: Int
    ): List<CaptionEvent> {
        data class Raw(
            val visStart: Double,
            val visEnd: Double,
            val text: String,
            val fontFamily: String,
            val fontUrl: String,
            val fontSize: Int,
            val colorHex: String,
            val position: String,
            val bold: Boolean
        )
        val raw = mutableListOf<Raw>()
        for (clip in clips) {
            val asset = assetsById[clip.assetId] ?: continue
            val captions = parseEffectsConfig(clip.effectsConfig).captions ?: continue
            if (!captions.enabled || asset.wordTimings.isEmpty()) continue

            val fontSize = (captions.fontSizeSp * canvasHeight / 480.0).toInt().coerceIn(12, 120)
            val colorHex = captions.color.removePrefix("#").ifBlank { "FFFFFF" }
            val clipEnd = (clip.timelineStart + (clip.trimOut - clip.trimIn)).toDouble()

            for (chunk in asset.wordTimings.chunked(4)) {
                val visStart = clip.timelineStart + (chunk.first().start - clip.trimIn)
                val visEnd = clip.timelineStart + (chunk.last().end - clip.trimIn)
                if (visEnd <= clip.timelineStart.toDouble() || visStart >= clipEnd) continue
                raw.add(
                    Raw(
                        visStart = visStart,
                        visEnd = visEnd,
                        text = chunk.joinToString(" ") { it.word },
                        fontFamily = captions.fontFamily,
                        fontUrl = captions.fontUrl,
                        fontSize = fontSize,
                        colorHex = colorHex,
                        position = captions.position,
                        bold = captions.fontWeight >= 600
                    )
                )
            }
        }
        if (raw.isEmpty()) return emptyList()
        val starts = raw.map { it.visStart }.sorted()
        return raw.map { event ->
            val end = captionVisibleEnd(event.visStart, event.visEnd, starts)
            CaptionEvent(
                start = event.visStart,
                end = end.coerceAtLeast(event.visStart + 0.05),
                text = event.text,
                fontFamily = event.fontFamily,
                fontUrl = event.fontUrl,
                fontSize = event.fontSize,
                colorHex = event.colorHex,
                position = event.position,
                bold = event.bold
            )
        }.filter { it.end > it.start }
    }

    /**
     * Writes an Advanced SubStation Alpha sidecar that libass can burn in with a single `ass=`
     * filter. Caption chips match the live preview: translucent black **rounded** background
     * (ASS vector drawing — `BorderStyle=3` only supports square boxes) at alpha ≈ 0.45, white
     * (or configured) text with a thin outline, and top/center/bottom placement via `\pos`.
     * Any referenced font files are copied into [fontsDir] so the `ass` filter's `fontsdir=`
     * can find them.
     */
    internal fun writeAssCaptionsFile(
        assFile: File,
        fontsDir: File,
        canvasWidth: Int,
        canvasHeight: Int,
        events: List<CaptionEvent>
    ) {
        fontsDir.mkdirs()
        // One ASS Style per unique (font, size, color, bold) combo so mixed voice clips keep their
        // own caption look without duplicating style lines for every dialogue event. Position is
        // applied per-event via \pos, not via style Alignment/MarginV.
        data class StyleKey(
            val fontFamily: String,
            val fontUrl: String,
            val fontSize: Int,
            val colorHex: String,
            val bold: Boolean
        )
        val styleKeys = linkedMapOf<StyleKey, String>()
        fun styleNameFor(event: CaptionEvent): String {
            val key = StyleKey(
                event.fontFamily, event.fontUrl, event.fontSize,
                event.colorHex, event.bold
            )
            return styleKeys.getOrPut(key) { "Cap${styleKeys.size}" }
        }
        events.forEach { styleNameFor(it) }

        // Stage fonts into fontsDir once per unique source file.
        val stagedFontNames = mutableMapOf<String, String>() // styleName -> font family name for ASS
        for ((key, styleName) in styleKeys) {
            val fontPath = resolveFontFilePath(key.fontFamily, key.fontUrl)
            val assFontName = if (fontPath != null) {
                val dest = File(fontsDir, "${styleName}_${File(fontPath).name}")
                if (!dest.exists()) {
                    runCatching { File(fontPath).copyTo(dest, overwrite = true) }
                        .onFailure { logger.warn("Could not stage caption font '$fontPath': ${it.message}") }
                }
                // Prefer the user-facing family name so libass matches the staged file by family;
                // fall back to a sensible default when the family is "Default".
                when {
                    !key.fontFamily.isNullOrBlank() &&
                        !key.fontFamily.equals("Default", ignoreCase = true) -> key.fontFamily
                    else -> "DejaVu Sans"
                }
            } else {
                "DejaVu Sans"
            }
            stagedFontNames[styleName] = assFontName
        }

        // Preview chip is Color.Black.copy(alpha=0.45f). ASS primary-alpha is inverted (00=opaque).
        val chipFill = assColour("000000", alpha = 0.45)
        val chipFillRgb = chipFill.substring(4) // BBGGRR after &HAA
        val chipFillAlpha = chipFill.substring(2, 4) // AA transparency byte
        val outline = assColour("000000", alpha = 0.7)
        // Corner radius mirrors the preview chip's RoundedCornerShape(8.dp), scaled to the render
        // canvas the same way the font size is (both authored against a 480px stage).
        val baseRadius = (8.0 * canvasHeight / 480.0).toInt().coerceAtLeast(2)

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: $canvasWidth")
        sb.appendLine("PlayResY: $canvasHeight")
        sb.appendLine("WrapStyle: 2")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine("YCbCr Matrix: None")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine(
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, " +
                "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, " +
                "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"
        )
        for ((key, styleName) in styleKeys) {
            val fontName = stagedFontNames[styleName] ?: "DejaVu Sans"
            val primary = assColour(key.colorHex, alpha = 1.0)
            val boldFlag = if (key.bold) -1 else 0
            // BorderStyle=1 (outline only) — the translucent rounded chip is drawn as a separate
            // vector layer per event. Outline width ~2px matches the previous drawtext borderw=2.
            sb.appendLine(
                "Style: $styleName,$fontName,${key.fontSize},$primary,&H000000FF,$outline,&H00000000," +
                    "$boldFlag,0,0,0,100,100,0,0,1,2,0,5,0,0,0,1"
            )
        }
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (event in events.sortedBy { it.start }) {
            val style = styleNameFor(event)
            val start = formatAssTime(event.start)
            val end = formatAssTime(event.end)
            val text = escapeAssText(event.text)

            // Chip geometry mirrors the pre-ASS geq path / live preview padding ratios.
            val padH = (event.fontSize * 0.4).toInt().coerceAtLeast(6)
            val padV = (event.fontSize * 0.2).toInt().coerceAtLeast(3)
            val textH = (event.fontSize * 1.2).toInt()
            val boxHeight = textH + 2 * padV
            val estTextWidth = (event.text.length * event.fontSize * 0.6).toInt()
            val boxWidth = (estTextWidth + 2 * padH).coerceIn(2 * padH + 1, canvasWidth)
            val radius = minOf(baseRadius, boxHeight / 2, boxWidth / 2).coerceAtLeast(1)

            // Top of chip (same placement as the old overlay y= expressions), then center for \an5.
            val boxTop = when (event.position) {
                "top" -> canvasHeight / 10.0 - padV
                "center" -> (canvasHeight - boxHeight) / 2.0
                else -> canvasHeight - canvasHeight / 6.0 - padV
            }
            val cx = canvasWidth / 2
            val cy = kotlin.math.round(boxTop + boxHeight / 2.0).toInt()
            val drawing = assRoundedRectDrawing(boxWidth, boxHeight, radius)

            // Layer 0: rounded translucent chip. Layer 1: text centered on the same point.
            sb.appendLine(
                "Dialogue: 0,$start,$end,$style,,0,0,0,," +
                    "{\\an5\\pos($cx,$cy)\\p1\\bord0\\shad0\\1c&H$chipFillRgb&\\1a&H$chipFillAlpha&}" +
                    "$drawing{\\p0}"
            )
            sb.appendLine(
                "Dialogue: 1,$start,$end,$style,,0,0,0,," +
                    "{\\an5\\pos($cx,$cy)}$text"
            )
        }
        assFile.writeText(sb.toString())
    }

    /**
     * ASS `\p1` drawing commands for a rounded rectangle of [width]×[height] with corner
     * [radius], origin at the top-left of the box. Cubic Bézier corners use the standard
     * quarter-circle kappa (0.55228475·r) so libass renders a smooth chip matching the preview's
     * `RoundedCornerShape`.
     */
    internal fun assRoundedRectDrawing(width: Int, height: Int, radius: Int): String {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val r = radius.coerceIn(1, minOf(w, h) / 2)
        val c = r * 0.5522847498
        fun n(v: Double): Int = kotlin.math.round(v).toInt()
        return buildString {
            append("m $r 0 ")
            append("l ${w - r} 0 ")
            append("b ${n(w - r + c)} 0 $w ${n(r - c)} $w $r ")
            append("l $w ${h - r} ")
            append("b $w ${n(h - r + c)} ${n(w - r + c)} $h ${w - r} $h ")
            append("l $r $h ")
            append("b ${n(r - c)} $h 0 ${n(h - r + c)} 0 ${h - r} ")
            append("l 0 $r ")
            append("b 0 ${n(r - c)} ${n(r - c)} 0 $r 0")
        }
    }

    /**
     * ASS `&HAABBGGRR` colour literal. [alpha] is opacity 0..1 (1 = fully opaque); ASS stores the
     * inverted transparency byte (00 = opaque, FF = transparent).
     */
    internal fun assColour(rgbHex: String, alpha: Double = 1.0): String {
        val cleaned = rgbHex.removePrefix("#").let { c ->
            when {
                c.length == 8 -> c.substring(2) // AARRGGBB -> RRGGBB
                c.length == 6 -> c
                else -> "FFFFFF"
            }
        }.uppercase()
        val rr = cleaned.substring(0, 2)
        val gg = cleaned.substring(2, 4)
        val bb = cleaned.substring(4, 6)
        val a = ((1.0 - alpha.coerceIn(0.0, 1.0)) * 255.0).toInt().coerceIn(0, 255)
        return "&H%02X%s%s%s".format(a, bb, gg, rr)
    }

    /** ASS dialogue timestamp `H:MM:SS.cs` (centiseconds). */
    internal fun formatAssTime(seconds: Double): String {
        val totalCs = kotlin.math.round(seconds.coerceAtLeast(0.0) * 100.0).toLong().coerceAtLeast(0L)
        val h = totalCs / 360_000
        val m = (totalCs % 360_000) / 6_000
        val s = (totalCs % 6_000) / 100
        val cs = totalCs % 100
        return "$h:%02d:%02d.%02d".format(m.toInt(), s.toInt(), cs.toInt())
    }

    /** Escapes caption text for an ASS `Dialogue` payload. */
    internal fun escapeAssText(text: String): String = text
        .replace("\\", "\\\\")
        .replace("{", "\\{")
        .replace("}", "\\}")
        .replace("\r\n", "\\N")
        .replace("\n", "\\N")
        .replace("\r", "\\N")

    /** Escapes a filesystem path for use inside an FFmpeg filtergraph option value. */
    internal fun escapeFilterPath(path: String): String = path
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "\\'")

    /**
     * Absolute path of the font file that [fontFileArg] would feed to drawtext for the given
     * family/url, or null when nothing usable is available. Shared by drawtext and the ASS
     * caption path so both renderers resolve fonts identically.
     */
    private fun resolveFontFilePath(fontFamily: String?, fontUrl: String?): String? {
        if (!fontUrl.isNullOrBlank()) downloadedFontFile(fontUrl)?.let { return it }
        bundledFontFile(fontFamily)?.let { return it }
        val candidates = listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/System/Library/Fonts/Helvetica.ttc"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    /**
     * The `fontfile=...:` prefix for drawtext for the given [fontFamily] display name, so the
     * export uses the same font as the live preview. A non-blank [fontUrl] (the OSS-hosted `.ttf`
     * of a Google Fonts variant, from [TextConfig.fontUrl] / [CaptionConfig.fontUrl]) wins and is
     * downloaded once per process (see [downloadedFontFile]); the app-bundled "Asap"/"Yuyu" fonts
     * are extracted from the classpath (see [bundledFontFile]); any other family (including
     * "Default"/null) falls back to the first available system font. Returns "" when no usable
     * font file is found at all.
     */
    private fun fontFileArg(fontFamily: String? = null, fontUrl: String? = null): String {
        val found = resolveFontFilePath(fontFamily, fontUrl) ?: return ""
        return "fontfile=$found:"
    }

    /** Local temp-file paths of downloaded (Google Fonts) font files, keyed by OSS object key. */
    private val downloadedFontPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The absolute path of the OSS-hosted font file at [fontUrl], downloaded to a temp file the
     * first time a render needs it and cached for the lifetime of the process. The cache key is
     * the underlying OSS object key, so re-signed URLs of the same font share one download (the
     * font itself is already persisted durably on OSS by `GoogleFontsService.ensureFont`, so this
     * is only a local scratch copy for FFmpeg's `fontfile=`). Returns null when the download
     * fails, so the caller falls back to a bundled/system font instead of failing the render.
     */
    private fun downloadedFontFile(fontUrl: String): String? {
        val cacheKey = OssService.objectKeyFromUrl(fontUrl) ?: fontUrl.substringBefore('?')
        downloadedFontPaths[cacheKey]?.let { return it }
        return try {
            val suffix = "." + cacheKey.substringAfterLast('.', "ttf").ifBlank { "ttf" }
            val tempFile = File.createTempFile("moviestudio_font_dl_", suffix)
            tempFile.deleteOnExit()
            java.net.URI(fontUrl).toURL().openStream().use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val path = tempFile.absolutePath
            downloadedFontPaths[cacheKey] = path
            path
        } catch (e: Exception) {
            logger.warn("Failed to download clip font '$fontUrl': ${e.message}")
            null
        }
    }

    /** Extracted temp-file paths of the app-bundled fonts, keyed by family name (extract once). */
    private val bundledFontPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The absolute path to an app-bundled font ("Asap"/"Yuyu"), extracted from the server's
     * classpath resources (under `fonts/`) to a temp file the first time it is requested so
     * FFmpeg's `drawtext` (which needs a real file path) can read it. The result is cached so the
     * font is only extracted once. Returns null for any other family (including "Default"/null) or
     * when the resource is missing / extraction fails, so the caller falls back to a system font.
     */
    private fun bundledFontFile(fontFamily: String?): String? {
        val resource = when (fontFamily) {
            "Asap" -> "/fonts/asap.ttf"
            "Yuyu" -> "/fonts/yuyu.ttf"
            else -> return null
        }
        bundledFontPaths[fontFamily]?.let { return it }
        return try {
            val stream = FFmpegService::class.java.getResourceAsStream(resource)
                ?: return null
            val tempFile = File.createTempFile("moviestudio_font_${fontFamily}_", ".ttf")
            tempFile.deleteOnExit()
            stream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            val path = tempFile.absolutePath
            bundledFontPaths[fontFamily!!] = path
            path
        } catch (e: Exception) {
            logger.warn("Failed to extract bundled font '$fontFamily': ${e.message}")
            null
        }
    }

    /**
     * FFmpeg per-frame volume expression for a keyframed envelope: piecewise-linear between the
     * keyframes, holding the first/last keyframe's gain before/after the envelope — mirroring
     * the client's [volumeAt]. 't' is clip-relative (the chain runs after asetpts=PTS-STARTPTS).
     */
    internal fun volumeEnvelopeExpression(keyframes: List<VolumePoint>): String {
        val points = keyframes.sortedBy { it.time }
        var expression = points.last().volume.ff()
        for (i in points.size - 2 downTo 0) {
            val a = points[i]
            val b = points[i + 1]
            val span = (b.time - a.time).takeIf { it > 0.0 } ?: 1.0
            val segment = "${a.volume.ff()}+(${b.volume.ff()}-${a.volume.ff()})*(t-${a.time.ff()})/${span.ff()}"
            expression = "if(lt(t\\,${b.time.ff()})\\,$segment\\,$expression)"
        }
        return "if(lt(t\\,${points.first().time.ff()})\\,${points.first().volume.ff()}\\,$expression)"
    }

    /**
     * Formats a number as a plain, fixed-point decimal string for FFmpeg. Kotlin's `toString()`
     * renders very small (or large) magnitudes in scientific notation (e.g. a tiny trim value like
     * `1.52587890625E-5`, which comes from float drift in a clip's [Clip.trimIn]/[Clip.timelineStart]),
     * and FFmpeg's option/expression parsers reject that ("Unable to parse option value ... as
     * duration"). This always yields a representation FFmpeg can parse.
     */
    internal fun Number.ff(): String {
        val d = toDouble()
        if (!d.isFinite()) return "0"
        if (d == 0.0) return "0"
        return java.math.BigDecimal.valueOf(d)
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /** Escapes text for use inside a drawtext `text='...'` argument. */
    internal fun escapeDrawtext(text: String): String = text
        .replace("\\", "\\\\")
        .replace("'", "\u2019") // typographic apostrophe avoids quote-escaping headaches
        .replace(":", "\\:")
        .replace("%", "\\%")
        .replace(",", "\\,")
        .replace(";", "\\;")
        .replace("[", "(")
        .replace("]", ")")
        .replace("\n", " ")

    /** Wraps [text] into at most [maxLines] lines of roughly [width] characters. */
    internal fun wrapText(text: String, width: Int, maxLines: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + word.length + 1 > width) {
                lines.add(current.toString())
                current = StringBuilder()
                if (lines.size == maxLines) break
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines.add(current.toString())
        if (lines.size == maxLines && words.joinToString(" ").length > lines.sumOf { it.length } + maxLines) {
            lines[maxLines - 1] = lines[maxLines - 1].take(width - 1) + "…"
        }
        return lines.ifEmpty { listOf(text.take(width)) }
    }

    /** The pixel margin reserved on each side of [canvasSize] so a TEXT element's block stays inside a safe area. */
    internal fun textSafeAreaPadding(canvasSize: Int): Int = (canvasSize * TEXT_SAFE_AREA_PADDING_FRACTION).toInt()

    /**
     * The character-count wrap width for a TEXT element's [fontSize], reduced by
     * [textSafeAreaPadding] on each side so a wrapped line stays within a horizontally padded safe
     * area instead of spanning the full canvas width — which would let it touch/overflow the frame
     * edges. Same avg-glyph-advance heuristic (~0.55em) as the description-only placeholder text.
     */
    internal fun textSafeAreaWrapWidth(canvasWidth: Int, fontSize: Int): Int {
        val availableWidth = (canvasWidth - 2 * textSafeAreaPadding(canvasWidth)).coerceAtLeast(1)
        return (availableWidth * 1.8 / fontSize).toInt().coerceIn(8, 80)
    }

    /**
     * The natural vertical spacing (px) between stacked TEXT element lines: `fontSize * 1.25`.
     *
     * It is intentionally NOT compressed to squeeze a multi-line block inside the canvas. A tall
     * block is meant to overflow the frame and scroll (see [renderTextElementLayer]), exactly like
     * the preview's unbounded `TextClip`. Compressing it kept every block inside the safe area,
     * which pinned the scroll offset to ~0 so overflowing text never moved.
     */
    internal fun textLineSpacing(fontSize: Int): Double = fontSize * 1.25

    /**
     * The `drawtext` y-offset (added to the centered line position, +down) for a TEXT element.
     *
     * When [TextConfig.scrollEnabled] is set, the block crawls from [TextConfig.scrollStartPercent]
     * of the frame height below the bottom edge to [TextConfig.scrollEndPercent] of the frame height
     * above the top edge across [duration] — the same motion as [textScrollTranslationY]. Negative
     * percents park that edge on screen instead of past it. The block height is the constant
     * inter-line spacing plus the runtime single-line `text_h`.
     *
     * Otherwise overflowing text keeps the automatic read-through scroll (two blank lines of lead-in
     * and lead-out); text that fits stays centered (the offset collapses to ~0). Returns "" when
     * there is no duration to scroll across.
     */
    internal fun textElementYScrollExpr(
        config: TextConfig,
        duration: Double,
        lineCount: Int,
        lineSpacing: Double
    ): String {
        if (duration <= 0.0 || lineCount <= 0) return ""
        val blockConstPx = (lineCount - 1) * lineSpacing
        val blockHeightExpr = "(${blockConstPx.ff()}+text_h)"
        val progress = "min(t/${duration.ff()},1)"
        return if (config.scrollEnabled) {
            val startFrac = (config.scrollStartPercent
                .coerceIn(TEXT_SCROLL_MIN_PERCENT, TEXT_SCROLL_MAX_PERCENT) / 100.0).ff()
            val endFrac = (config.scrollEndPercent
                .coerceIn(TEXT_SCROLL_MIN_PERCENT, TEXT_SCROLL_MAX_PERCENT) / 100.0).ff()
            val startOffset = "(h*0.5+h*$startFrac+($blockHeightExpr)*0.5)"
            val endOffset = "(-h*0.5-h*$endFrac-($blockHeightExpr)*0.5)"
            "+($startOffset+($endOffset-$startOffset)*$progress)"
        } else {
            val overflowExpr = "max(0,$blockHeightExpr-h)"
            val padPerSideExpr = "2*${lineSpacing.ff()}*gt($blockHeightExpr,h)"
            val scrollExtentExpr = "($overflowExpr+2*$padPerSideExpr)"
            "+($overflowExpr*0.5+$padPerSideExpr-$progress*$scrollExtentExpr)"
        }
    }

    /**
     * The `drawtext` `x=` expression for a TEXT element line: centered by default, but clamped into
     * the horizontally padded safe area of [canvasWidth] as a hard guarantee against overflow (the
     * [textSafeAreaWrapWidth] estimate above is only a heuristic and actual glyph metrics can vary).
     */
    internal fun textSafeAreaXExpr(canvasWidth: Int): String {
        val padding = textSafeAreaPadding(canvasWidth)
        return "'max($padding,min(w-text_w-$padding,(w-text_w)/2))'"
    }

    /**
     * Distills a short, human-readable reason from FFmpeg's stderr [logTail]: the last few
     * non-empty, non-progress lines (which almost always carry the actual error). Kept short so it
     * fits a job's error field and the progress toast; the full output is in the server log.
     *
     * Special-cases SIGTERM ("received signal 15") — on developer machines this is almost always
     * the userspace OOM killer (earlyoom) reaping an oversized FFmpeg filtergraph, so the message
     * points at memory pressure rather than a mysterious exit code.
     */
    internal fun summarizeFfmpegError(logTail: Collection<String>): String {
        val meaningful = logTail
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("frame=") }
        val reason = meaningful.takeLast(3).joinToString(" | ")
            .ifBlank { logTail.joinToString(" | ").trim() }
        val looksLikeOomKill =
            reason.contains("signal 15", ignoreCase = true) ||
                reason.contains("SIGTERM", ignoreCase = true) ||
                // earlyoom / repeated SIGTERM: FFmpeg hard-exits after several signals (exit 123).
                reason.contains("Received > 3 system signals", ignoreCase = true) ||
                reason.contains("Immediate exit requested", ignoreCase = true)
        if (looksLikeOomKill) {
            return "FFmpeg was terminated by the system (often the out-of-memory killer reaping " +
                "an oversized render). Try again; if it keeps happening, free memory or shorten " +
                "the timeline. FFmpeg output: ${reason.take(300)}"
        }
        return if (reason.isBlank()) {
            "See the server log for the full FFmpeg output."
        } else {
            "FFmpeg output: ${reason.take(500)}"
        }
    }

    private fun parseFfmpegTime(line: String): Double? {
        val regex = """time=([\d:.]+)""".toRegex()
        val match = regex.find(line) ?: return null
        val timeStr = match.groupValues[1]
        val parts = timeStr.split(":")
        return if (parts.size == 3) {
            val hours = parts[0].toDoubleOrNull() ?: 0.0
            val minutes = parts[1].toDoubleOrNull() ?: 0.0
            val seconds = parts[2].toDoubleOrNull() ?: 0.0
            hours * 3600.0 + minutes * 60.0 + seconds
        } else {
            timeStr.toDoubleOrNull()
        }
    }
}
