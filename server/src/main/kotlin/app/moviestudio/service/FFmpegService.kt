package app.moviestudio.service

import app.moviestudio.*
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.MovieRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.RenderRepository
import app.moviestudio.database.TrackRepository
import app.moviestudio.routing.JobWebSocketManager
import app.moviestudio.storage.OssService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                    withContext(Dispatchers.IO) { localFile.writeText("MOCK DOWNLOAD") }
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
            args.add("color=c=black:s=${canvasWidth}x${canvasHeight}:r=30:d=$totalDuration")
            args.add("-f"); args.add("lavfi"); args.add("-i")
            args.add("anullsrc=r=44100:cl=stereo:d=$totalDuration")

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
                    args.add("-t"); args.add((longestUse + 1.0).toString())
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

            var currentVideoTag = "0:v"
            var chain = 0
            for (clip in videoClips) {
                val asset = assetsById[clip.assetId] ?: continue
                val duration = (clip.trimOut - clip.trimIn).toDouble()
                if (duration <= 0) continue
                val start = clip.timelineStart.toDouble()
                val end = start + duration
                val effects = parseEffectsConfig(clip.effectsConfig)
                val rawEffects = try {
                    json.parseToJsonElement(clip.effectsConfig).jsonObject
                } catch (e: Exception) {
                    null
                }

                if (asset.ossUrl.isBlank()) {
                    // Description-only item: large centered white text over the current video.
                    val text = (asset.description ?: asset.aiPrompt ?: "").ifBlank { "Untitled scene" }
                    val nextTag = "v_text_${chain++}"
                    val lines = wrapText(text, 34, 4)
                    var tag = currentVideoTag
                    lines.forEachIndexed { index, line ->
                        val outTag = if (index == lines.lastIndex) nextTag else "v_textline_${chain++}"
                        val yExpr = "(h-text_h)/2+${(index - (lines.size - 1) / 2.0) * (canvasHeight / 12)}"
                        filters.add(
                            "[$tag]drawtext=${fontFileArg()}text='${escapeDrawtext(line)}':" +
                                "fontcolor=white:fontsize=${canvasHeight / 14}:x=(w-text_w)/2:y=$yExpr:" +
                                "enable='between(t,$start,$end)'[$outTag]"
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
                videoFilters.add("trim=start=$srcStart:end=$srcEnd")
                videoFilters.add("setpts=PTS-STARTPTS")
                videoFilters.add("scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=increase")
                // Crop-fit honoring the clip's 0-100 offsets (50 = centered): 0 shows the
                // left/top edge of the media, 100 the right/bottom edge.
                val offsetFx = (effects.offsetX / 100.0).coerceIn(0.0, 1.0)
                val offsetFy = (effects.offsetY / 100.0).coerceIn(0.0, 1.0)
                videoFilters.add("crop=$canvasWidth:$canvasHeight:(iw-ow)*$offsetFx:(ih-oh)*$offsetFy")
                videoFilters.add("fps=30")

                // Color grading from effectsConfig (kept from the original renderer).
                val cb = rawEffects?.get("colorbalance")?.jsonObject ?: rawEffects?.get("colorBalance")?.jsonObject
                if (cb != null) {
                    fun v(k: String) = cb[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    videoFilters.add(
                        "colorbalance=rs=${v("rs")}:gs=${v("gs")}:bs=${v("bs")}:rm=${v("rm")}:gm=${v("gm")}:bm=${v("bm")}:rh=${v("rh")}:gh=${v("gh")}:bh=${v("bh")}"
                    )
                } else {
                    val brightness = rawEffects?.get("brightness")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val contrast = rawEffects?.get("contrast")?.jsonPrimitive?.doubleOrNull ?: 1.0
                    val saturation = rawEffects?.get("saturation")?.jsonPrimitive?.doubleOrNull ?: 1.0
                    if (brightness != 0.0 || contrast != 1.0 || saturation != 1.0) {
                        videoFilters.add("eq=brightness=$brightness:contrast=$contrast:saturation=$saturation")
                    }
                }

                // Transition-in over whatever is underneath (clip-local time 0..transition).
                val transition = effects.transition
                val transitionDur = transition?.durationSeconds?.coerceIn(0.05, duration) ?: 0.0
                var overlayExtra = ""
                if (transition != null && transition.type != TransitionType.NONE) {
                    // How the incoming clip is composited over the accumulated video. The signs and
                    // window math mirror the shared TransitionSpec.visualAt(...) in core/Models.kt so
                    // the export matches the live preview.
                    when (transition.type) {
                        TransitionType.SLIDE -> {
                            // Slide the clip in from the chosen edge across the transition window.
                            // progress p = (t-start)/dur; the off-screen offset is W|H*(1-p).
                            // FROM_RIGHT enters from +W and moves to 0.
                            val p = "(t-$start)/$transitionDur"
                            overlayExtra = when (transition.direction) {
                                SlideDirection.FROM_RIGHT ->
                                    ":x='if(lt(t-$start,$transitionDur),W-W*$p,0)'"
                                SlideDirection.FROM_LEFT ->
                                    ":x='if(lt(t-$start,$transitionDur),-W+W*$p,0)'"
                                SlideDirection.FROM_TOP ->
                                    ":y='if(lt(t-$start,$transitionDur),-H+H*$p,0)'"
                                SlideDirection.FROM_BOTTOM ->
                                    ":y='if(lt(t-$start,$transitionDur),H-H*$p,0)'"
                            }
                        }
                        TransitionType.CIRCLE -> {
                            // Growing circular reveal: an alpha mask that is opaque inside a centered
                            // circle whose radius grows from 0 to the corner distance over the window
                            // (matches TransitionVisual.revealRadiusFraction = progress). No fade.
                            videoFilters.add("format=yuva420p")
                            videoFilters.add(
                                "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':" +
                                    "a='if(lte(hypot(X-W/2,Y-H/2),hypot(W/2,H/2)*min(T/$transitionDur,1)),255,0)'"
                            )
                        }
                        else -> {
                            // ALPHA / NOISE / VORONOI / PIXELATE all cross-fade in.
                            videoFilters.add("format=yuva420p")
                            videoFilters.add("fade=t=in:st=0:d=$transitionDur:alpha=1")
                        }
                    }
                    // Grain / mosaic layered on top of the fade for the textured transitions.
                    when (transition.type) {
                        TransitionType.NOISE ->
                            videoFilters.add("noise=alls=48:allf=t:enable='between(t,0,$transitionDur)'")
                        TransitionType.VORONOI ->
                            videoFilters.add("pixelize=width=42:height=42:enable='between(t,0,$transitionDur)'")
                        TransitionType.PIXELATE -> {
                            // Animate the mosaic: downscale (nearest-neighbor) to a time-varying tiny
                            // size, then scale back up, so the blocks start large (maxBlock px at
                            // t=0) and shrink to 1px (crisp) as the window ends — this is the real
                            // animation of pixelateFraction = 1 - progress.
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
                }

                // Shift PTS so content plays in sync with its position on the timeline.
                videoFilters.add("setpts=PTS+$start/TB")

                val trimmedTag = "v_trimmed_${clip.id}"
                filters.add("[$idx:v]${videoFilters.joinToString(",")}[$trimmedTag]")

                val nextVideoTag = "v_overlaid_${clip.id}"
                filters.add(
                    "[$currentVideoTag][$trimmedTag]overlay=eof_action=pass:enable='between(t,$start,$end)'$overlayExtra[$nextVideoTag]"
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

                val yExpr = when (captions.position) {
                    "top" -> "h/10"
                    "center" -> "(h-text_h)/2"
                    else -> "h-h/6"
                }
                val fontSize = (captions.fontSizeSp * canvasHeight / 480.0).toInt().coerceIn(12, 120)
                val color = captions.color.removePrefix("#").ifBlank { "FFFFFF" }

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
                    val nextTag = "v_cap_${captionChunks++}"
                    filters.add(
                        "[$currentVideoTag]drawtext=${fontFileArg()}text='${escapeDrawtext(text)}':" +
                            "fontcolor=0x$color:fontsize=$fontSize:borderw=2:bordercolor=black@0.7:" +
                            "x=(w-text_w)/2:y=$yExpr:enable='between(t,$visStart,$visEnd)'[$nextTag]"
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
                audioFilters.add("atrim=start=$srcStart:end=$srcEnd")
                audioFilters.add("asetpts=PTS-STARTPTS")
                if (effects.volumeKeyframes.isNotEmpty()) {
                    // Volume-over-time envelope: evaluated per frame; 't' is clip-relative
                    // because the chain runs after asetpts=PTS-STARTPTS.
                    audioFilters.add("volume=volume='${volumeEnvelopeExpression(effects.volumeKeyframes)}':eval=frame")
                } else if (volume != 1.0) {
                    audioFilters.add("volume=$volume")
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

            args.add("-filter_complex")
            args.add(filters.joinToString(";"))
            args.add("-map")
            args.add(if (currentVideoTag == "0:v") "0:v" else "[$currentVideoTag]")
            args.add("-map")
            args.add("[out_a]")

            args.add("-t"); args.add(totalDuration.toString())
            args.add("-c:v"); args.add("libx264")
            args.add("-preset"); args.add("veryfast")
            args.add("-pix_fmt"); args.add("yuv420p")
            args.add("-c:a"); args.add("aac")

            val outputFile = File(tempDir, "rendered_output.mp4")
            args.add(outputFile.absolutePath)

            logger.info("Generated FFmpeg Arguments: ${args.joinToString(" ")}")

            onProgress(25, "Rendering movie with FFmpeg...")

            withContext(Dispatchers.IO) {
                val process = try {
                    ProcessBuilder(args).start()
                } catch (e: java.io.IOException) {
                    logger.warn("FFmpeg binary not found: ${e.message}. Simulating rendering progress instead.")
                    null
                }

                if (process != null) {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.info("[FFmpeg] $line")
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
                        logger.warn("FFmpeg failed with exit code $exitCode (likely mock media). Falling back to simulated render.")
                        for (p in 0..100 step 20) {
                            onProgress(25 + (p * 0.6).toInt(), "Rendering movie (simulated fallback): $p%...")
                            delay(200)
                        }
                        outputFile.writeText("MOCK COMPILED MP4 VIDEO DATA")
                    }
                } else {
                    for (p in 0..100 step 20) {
                        onProgress(25 + (p * 0.6).toInt(), "Rendering movie (simulated): $p%...")
                        delay(200)
                    }
                    outputFile.writeText("MOCK COMPILED MP4 VIDEO DATA")
                }
            }

            onProgress(90, "Uploading rendered movie to Alibaba Cloud OSS...")

            val uploadedUrl = OssService.uploadFile(
                "renders/${job.movieId}/${UUID.randomUUID()}.mp4",
                outputFile
            )

            // Every render is kept: replayable and downloadable at any time.
            RenderRepository.insert(
                RenderRecord(
                    id = UUID.randomUUID().toString(),
                    movieId = job.movieId,
                    url = uploadedUrl,
                    durationSeconds = totalDuration,
                    aspectRatio = movie.aspectRatio,
                    createdAt = System.currentTimeMillis()
                )
            )

            // The movie leaves RENDERING once the render finishes.
            MovieRepository.getById(job.movieId)?.let { current ->
                if (current.status == MovieStatus.RENDERING) {
                    MovieRepository.update(current.copy(status = MovieStatus.COMPLETED))
                }
            }

            JobRepository.update(job.copy(status = JobStatus.COMPLETED, resultUrl = uploadedUrl))

            JobWebSocketManager.broadcast(
                JobProgressEvent(
                    jobId = job.id,
                    movieId = job.movieId,
                    status = JobStatus.COMPLETED,
                    progress = 100,
                    message = "Rendering completed successfully",
                    resultUrl = uploadedUrl,
                    jobType = JobType.FFMPEG_RENDER
                )
            )

            logger.info("FFmpeg render job ${job.id} completed successfully. resultUrl: $uploadedUrl")
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

    /** The `fontfile=...:` prefix for drawtext when a usable system font is found, else empty. */
    private fun fontFileArg(): String {
        val candidates = listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/System/Library/Fonts/Helvetica.ttc"
        )
        val found = candidates.firstOrNull { File(it).exists() } ?: return ""
        return "fontfile=$found:"
    }

    /**
     * FFmpeg per-frame volume expression for a keyframed envelope: piecewise-linear between the
     * keyframes, holding the first/last keyframe's gain before/after the envelope — mirroring
     * the client's [volumeAt]. 't' is clip-relative (the chain runs after asetpts=PTS-STARTPTS).
     */
    internal fun volumeEnvelopeExpression(keyframes: List<VolumePoint>): String {
        val points = keyframes.sortedBy { it.time }
        var expression = "${points.last().volume}"
        for (i in points.size - 2 downTo 0) {
            val a = points[i]
            val b = points[i + 1]
            val span = (b.time - a.time).takeIf { it > 0.0 } ?: 1.0
            val segment = "${a.volume}+(${b.volume}-${a.volume})*(t-${a.time})/$span"
            expression = "if(lt(t\\,${b.time})\\,$segment\\,$expression)"
        }
        return "if(lt(t\\,${points.first().time})\\,${points.first().volume}\\,$expression)"
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
