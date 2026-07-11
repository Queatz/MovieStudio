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

/**
 * Final movie renderer. Compiles the timeline into a single H.264/AAC MP4 with FFmpeg:
 *
 * - Video clips are trimmed, center-crop fit to the movie's aspect ratio and overlaid in track
 *   z-order at their timeline position (PTS-shifted so content lines up with the playhead).
 * - Images are looped for the clip duration.
 * - Description-only (skeleton) items render as large centered white text, matching the preview.
 * - Overlap transitions (alpha / noise / voronoi / slide / circle / pixelate) blend a clip in
 *   over the media playing underneath it.
 * - Audio clips honor their source offset (clipped sound effects), volume and position; voice
 *   clips with captions enabled get word-timed captions burned in.
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

            onProgress(20, "Compiling timeline and filters...")

            var totalDuration = clips.maxOfOrNull { (it.timelineStart + (it.trimOut - it.trimIn)).toDouble() } ?: 0.0
            if (totalDuration <= 0) totalDuration = movie.totalDuration
            if (totalDuration <= 0) totalDuration = 10.0

            val (canvasWidth, canvasHeight) = resolutionForAspectRatio(movie.aspectRatio)

            val args = mutableListOf<String>()
            args.add(MediaUtil.ffmpegBinary)
            args.add("-y")

            // Input 0: black canvas; input 1: silence.
            args.add("-f"); args.add("lavfi"); args.add("-i")
            args.add("color=c=black:s=${canvasWidth}x${canvasHeight}:r=30:d=${totalDuration.ff()}")
            args.add("-f"); args.add("lavfi"); args.add("-i")
            args.add("anullsrc=r=44100:cl=stereo:d=${totalDuration.ff()}")

            // Media inputs, starting from index 2. Images are looped for their longest clip use.
            val assetIdOrder = downloadedAssets.keys.toList()
            val assetInputMap = mutableMapOf<String, Int>()
            var inputIdx = 2
            for (assetId in assetIdOrder) {
                val asset = assetsById[assetId] ?: continue
                val localFile = downloadedAssets[assetId]!!
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
            val videoTrackIds = tracks.filter { it.type == TrackType.VIDEO }.map { it.id }.toSet()
            val videoClips = clips.filter { it.trackId in videoTrackIds }
                .sortedWith(
                    compareBy<Clip> { clip -> tracks.first { it.id == clip.trackId }.zIndex }
                        .thenBy { it.timelineStart }
                )

            // Clips grouped by their track, so each clip's coverage can be held until the NEXT clip
            // on its own track begins when only a sub-frame sliver separates them (see below).
            val clipsByTrack = clips.groupBy { it.trackId }

            var currentVideoTag = "0:v"
            var chain = 0
            for (clip in videoClips) {
                val asset = assetsById[clip.assetId] ?: continue
                val duration = (clip.trimOut - clip.trimIn).toDouble()
                if (duration <= 0) continue
                val start = clip.timelineStart.toDouble()
                val end = start + duration
                // How long this clip stays on screen: its natural end, stretched to the next clip's
                // start when only a sub-frame sliver separates them, so the black canvas never peeks
                // through between two clips placed back-to-back (mirrors the preview's active-clip
                // window via the shared [bridgedClipEnd]). The video overlay below holds its last
                // frame (eof_action=repeat) across any such sliver.
                val displayEnd = bridgedClipEnd(clip, clipsByTrack[clip.trackId] ?: listOf(clip)).toDouble()
                val effects = parseEffectsConfig(clip.effectsConfig)
                val rawEffects = try {
                    json.parseToJsonElement(clip.effectsConfig).jsonObject
                } catch (e: Exception) {
                    null
                }

                if (asset.ossUrl.isBlank()) {
                    if (asset.isTextElement) {
                        // A first-class text element: styled text (color / font size / background)
                        // composited over the current video with the clip's transition — the exact
                        // analog of the styled TextClip in the preview. Built as its own canvas-sized
                        // layer (a color source + drawtext) so transitions apply to it like any clip.
                        currentVideoTag = renderTextElementLayer(
                            filters, currentVideoTag, clip, asset, effects,
                            start, displayEnd, duration, canvasWidth, canvasHeight
                        )
                        continue
                    }
                    // Placeholder (description-only) item: large centered white text over the video.
                    val text = (asset.description ?: asset.aiPrompt ?: "").ifBlank { "Untitled scene" }
                    val nextTag = "v_text_${chain++}"
                    val lines = wrapText(text, 34, 4)
                    var tag = currentVideoTag
                    lines.forEachIndexed { index, line ->
                        val outTag = if (index == lines.lastIndex) nextTag else "v_textline_${chain++}"
                        val yExpr = "(h-text_h)/2+${((index - (lines.size - 1) / 2.0) * (canvasHeight / 12)).ff()}"
                        filters.add(
                            "[$tag]drawtext=${fontFileArg()}text='${escapeDrawtext(line)}':" +
                                "fontcolor=white:fontsize=${canvasHeight / 14}:x=(w-text_w)/2:y=$yExpr:" +
                                "enable='between(t,${start.ff()},${displayEnd.ff()})'[$outTag]"
                        )
                        tag = outTag
                    }
                    currentVideoTag = nextTag
                    continue
                }

                val idx = assetInputMap[clip.assetId] ?: continue
                val srcStart = asset.sourceOffsetSeconds + clip.trimIn
                val srcEnd = asset.sourceOffsetSeconds + clip.trimOut

                val videoFilters = mutableListOf<String>()
                videoFilters.add("trim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
                videoFilters.add("setpts=PTS-STARTPTS")
                videoFilters.add("scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=increase")
                // Crop-fit honoring the clip's 0-100 offsets (50 = centered): 0 shows the
                // left/top edge of the media, 100 the right/bottom edge.
                val offsetFx = (effects.offsetX / 100.0).coerceIn(0.0, 1.0)
                val offsetFy = (effects.offsetY / 100.0).coerceIn(0.0, 1.0)
                videoFilters.add("crop=$canvasWidth:$canvasHeight:(iw-ow)*${offsetFx.ff()}:(ih-oh)*${offsetFy.ff()}")
                videoFilters.add("fps=30")

                // Color grading from effectsConfig (kept from the original renderer).
                val cb = rawEffects?.get("colorbalance")?.jsonObject ?: rawEffects?.get("colorBalance")?.jsonObject
                if (cb != null) {
                    fun v(k: String) = cb[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    videoFilters.add(
                        "colorbalance=rs=${v("rs").ff()}:gs=${v("gs").ff()}:bs=${v("bs").ff()}:rm=${v("rm").ff()}:gm=${v("gm").ff()}:bm=${v("bm").ff()}:rh=${v("rh").ff()}:gh=${v("gh").ff()}:bh=${v("bh").ff()}"
                    )
                } else {
                    val brightness = rawEffects?.get("brightness")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val contrast = rawEffects?.get("contrast")?.jsonPrimitive?.doubleOrNull ?: 1.0
                    val saturation = rawEffects?.get("saturation")?.jsonPrimitive?.doubleOrNull ?: 1.0
                    if (brightness != 0.0 || contrast != 1.0 || saturation != 1.0) {
                        videoFilters.add("eq=brightness=${brightness.ff()}:contrast=${contrast.ff()}:saturation=${saturation.ff()}")
                    }
                }

                // Transition-in over whatever is underneath (clip-local time 0..transition).
                val transition = effects.transition
                val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
                val overlayExtra = buildTransitionFilters(
                    videoFilters, transition, transitionDur, start, canvasWidth, canvasHeight
                )

                // Shift PTS so content plays in sync with its position on the timeline.
                videoFilters.add("setpts=PTS+${start.ff()}/TB")

                val trimmedTag = "v_trimmed_${clip.id}"
                filters.add("[$idx:v]${videoFilters.joinToString(",")}[$trimmedTag]")

                val nextVideoTag = "v_overlaid_${clip.id}"
                // eof_action=repeat holds this clip's LAST decoded frame once its (trimmed) content
                // ends, instead of dropping to the black canvas — so a sub-frame content tail, and
                // any bridged sliver up to displayEnd, keep showing the frame rather than flashing
                // black between this clip and the next.
                filters.add(
                    "[$currentVideoTag][$trimmedTag]overlay=eof_action=repeat:enable='between(t,${start.ff()},${displayEnd.ff()})'$overlayExtra[$nextVideoTag]"
                )
                currentVideoTag = nextVideoTag
            }

            // Captions for voice clips (burned in over the final video).
            val voiceTrackIds = tracks.filter { it.type == TrackType.VOICE }.map { it.id }.toSet()
            var captionChunks = 0
            for (clip in clips.filter { it.trackId in voiceTrackIds }) {
                val asset = assetsById[clip.assetId] ?: continue
                val captions = parseEffectsConfig(clip.effectsConfig).captions ?: continue
                if (!captions.enabled || asset.wordTimings.isEmpty()) continue

                val fontSize = (captions.fontSizeSp * canvasHeight / 480.0).toInt().coerceIn(12, 120)
                val color = captions.color.removePrefix("#").ifBlank { "FFFFFF" }
                val captionFont = fontFileArg(captions.fontFamily, captions.fontUrl)
                // Padding around the caption text, proportional to the font size, mirroring the
                // preview chip's `padding(horizontal = 12.dp, vertical = 4.dp)` (roughly a 3:1 ratio).
                val padH = (fontSize * 0.4).toInt().coerceAtLeast(6)
                val padV = (fontSize * 0.2).toInt().coerceAtLeast(3)
                // Estimated single-line text height (drawtext's text_h is ~1.2x the font size).
                val textH = (fontSize * 1.2).toInt()
                val boxHeight = textH + 2 * padV
                // Corner radius mirrors the preview chip's RoundedCornerShape(8.dp), scaled to the
                // render canvas the same way the font size is (both authored against a 480px stage).
                val baseRadius = (8.0 * canvasHeight / 480.0).toInt().coerceAtLeast(2)
                // Translucent black background (matches the preview's `Color.Black.copy(alpha=0.45f)`).
                val boxAlpha255 = (0.45 * 255).toInt()

                // Where the whole caption chip (box + text baked in) sits vertically. `h` here is the
                // overlay input's height, i.e. the chip's own `boxHeight`, so `(H-h)/2` centers the
                // chip in the frame; the top/bottom variants offset it by `padV` so the text inside
                // ends up at the same line it used to. The text is centered WITHIN the chip below, so
                // this only positions the chip — it never affects text-vs-box vertical alignment.
                val boxYExpr = when (captions.position) {
                    "top" -> "H/10-$padV"
                    "center" -> "(H-h)/2"
                    else -> "H-H/6-$padV"
                }

                for (chunk in asset.wordTimings.chunked(4)) {
                    if (captionChunks >= 90) break
                    val chunkStart = chunk.first().start
                    val chunkEnd = chunk.last().end
                    // Word timings are asset-relative; map into output time via the clip window.
                    val visStart = clip.timelineStart + (chunkStart - clip.trimIn)
                    val visEnd = clip.timelineStart + (chunkEnd - clip.trimIn)
                    if (visEnd <= clip.timelineStart.toDouble() ||
                        visStart >= (clip.timelineStart + (clip.trimOut - clip.trimIn)).toDouble()
                    ) continue
                    val text = chunk.joinToString(" ") { it.word }
                    val chunkIdx = captionChunks++

                    // Rounded background behind the caption. `drawtext`'s own `box=` only draws SQUARE
                    // corners, so instead we build a box-sized color layer, round its corners with a
                    // `geq` alpha mask and overlay it behind the text (the issue's workaround) — giving
                    // the same rounded chip as the preview. The box is sized to an ESTIMATE of the text
                    // width (drawtext's exact `text_w` isn't known here); a small over-estimate merely
                    // leaves a little extra side padding, while both stay horizontally centered.
                    val estTextWidth = (text.length * fontSize * 0.6).toInt()
                    val boxWidth = (estTextWidth + 2 * padH).coerceIn(2 * padH + 1, canvasWidth)
                    val radius = minOf(baseRadius, boxHeight / 2, boxWidth / 2).coerceAtLeast(1)

                    val boxTag = "v_capbox_$chunkIdx"
                    val nextTag = "v_cap_$chunkIdx"

                    // Build the whole caption chip as one canvas-independent layer: a box-sized
                    // color plane, its corners rounded by a `geq` alpha mask, with the text drawn
                    // ON the plane and centered inside it via drawtext's OWN `text_h`
                    // (`y=(h-text_h)/2`). Baking the text into the box this way keeps it perfectly
                    // centered regardless of any text-height estimate — the box position below only
                    // moves the finished chip, it can no longer misalign the text vs the box.
                    filters.add(
                        "color=c=black:s=${boxWidth}x${boxHeight}:r=30:d=${totalDuration.ff()},format=yuva420p," +
                            "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':" +
                            "a='${roundedRectAlphaExpression(radius, boxAlpha255)}'," +
                            "drawtext=${captionFont}text='${escapeDrawtext(text)}':" +
                            "fontcolor=0x$color:fontsize=$fontSize:borderw=2:bordercolor=black@0.7:" +
                            "x=(w-text_w)/2:y=(h-text_h)/2[$boxTag]"
                    )
                    // Overlay the finished chip at the caption position, only during its window.
                    filters.add(
                        "[$currentVideoTag][$boxTag]overlay=x=(W-w)/2:y=$boxYExpr:" +
                            "enable='between(t,${visStart.ff()},${visEnd.ff()})'[$nextTag]"
                    )
                    currentVideoTag = nextTag
                }
            }

            // ------------------------------------------------------------------ audio pipeline
            // Every clip that carries an audio stream contributes to the mix — including video
            // clips on video tracks, whose embedded audio must be preserved. Clips with no audio
            // stream (images, silent video, description-only items) are filtered out below.
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
                val volume = effects.volume
                val srcStart = asset.sourceOffsetSeconds + clip.trimIn
                val srcEnd = asset.sourceOffsetSeconds + clip.trimOut

                val audioFilters = mutableListOf<String>()
                audioFilters.add("atrim=start=${srcStart.ff()}:end=${srcEnd.ff()}")
                audioFilters.add("asetpts=PTS-STARTPTS")
                if (effects.volumeKeyframes.isNotEmpty()) {
                    // Volume-over-time envelope: evaluated per frame; 't' is clip-relative
                    // because the chain runs after asetpts=PTS-STARTPTS.
                    audioFilters.add("volume=volume='${volumeEnvelopeExpression(effects.volumeKeyframes)}':eval=frame")
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
                    ProcessBuilder(args).start()
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
                if (exitCode != 0) {
                    // Previously a non-zero exit silently fell back to a placeholder file and the
                    // render was reported as successful. Surface the real failure instead.
                    logger.error(
                        "FFmpeg render failed for job ${job.id} (exit code $exitCode). FFmpeg output tail:\n" +
                            ffmpegLogTail.joinToString("\n")
                    )
                    throw Exception("FFmpeg exited with code $exitCode. ${summarizeFfmpegError(ffmpegLogTail)}")
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
     * be read) across the clip's duration — the same behavior as the preview's `TextClip`.
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

        // Vertical scroll for overflowing text — mirrors TextClip in the preview. The block's full
        // height is the constant inter-line spacing plus the runtime single-line `text_h`; when it
        // exceeds the canvas height (`h`) the text overflows and scrolls from its top down past its
        // bottom across the clip instead of staying centered. `t` here is clip-local (the layer is
        // PTS-shifted to `start` only afterwards), so progress = min(t/duration,1).
        val blockConstPx = (lines.size - 1) * lineSpacing
        val blockHeightExpr = "(${blockConstPx.ff()}+text_h)"
        val overflowExpr = "max(0,$blockHeightExpr-h)"
        // When it overflows, pad ~2 (blank) line-heights above the first line AND scroll ~2 extra
        // past the last, so the viewer has a moment to start and finish reading (the preview's
        // topPadPx / bottomPadPx). `gt` is 1 only while overflowing, so text that fits gets no
        // padding/extra scroll and stays centered.
        val padPerSideExpr = "2*${lineSpacing.ff()}*gt($blockHeightExpr,h)"
        val scrollExtentExpr = "($overflowExpr+2*$padPerSideExpr)"
        // translationY: 0 (centered) when it fits; otherwise ramps the block from half its overflow
        // plus 2 blank lines above (progress 0) down past its bottom + 2 blank lines (progress 1).
        val scrollExpr = if (duration > 0.0) {
            "+($overflowExpr*0.5+$padPerSideExpr-min(t/${duration.ff()},1)*$scrollExtentExpr)"
        } else {
            ""
        }

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
     * The `fontfile=...:` prefix for drawtext for the given [fontFamily] display name, so the
     * export uses the same font as the live preview. A non-blank [fontUrl] (the OSS-hosted `.ttf`
     * of a Google Fonts variant, from [TextConfig.fontUrl] / [CaptionConfig.fontUrl]) wins and is
     * downloaded once per process (see [downloadedFontFile]); the app-bundled "Asap"/"Yuyu" fonts
     * are extracted from the classpath (see [bundledFontFile]); any other family (including
     * "Default"/null) falls back to the first available system font. Returns "" when no usable
     * font file is found at all.
     */
    private fun fontFileArg(fontFamily: String? = null, fontUrl: String? = null): String {
        if (!fontUrl.isNullOrBlank()) downloadedFontFile(fontUrl)?.let { return "fontfile=$it:" }
        bundledFontFile(fontFamily)?.let { return "fontfile=$it:" }
        val candidates = listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/System/Library/Fonts/Helvetica.ttc"
        )
        val found = candidates.firstOrNull { File(it).exists() } ?: return ""
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
     */
    internal fun summarizeFfmpegError(logTail: Collection<String>): String {
        val meaningful = logTail
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("frame=") }
        val reason = meaningful.takeLast(3).joinToString(" | ")
            .ifBlank { logTail.joinToString(" | ").trim() }
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
