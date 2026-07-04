package app.moviestudio.service

import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.Clip
import app.moviestudio.FilmTimeline
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.Track
import app.moviestudio.TrackType
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.TrackRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Movie-skeleton generation: gives Qwen the user's prompt together with instructions and context
 * about the current playhead position and the items already on the timeline, then materializes
 * the returned plan as description-only assets inserted on the timeline where Qwen said to put
 * them. Runs entirely on the server as a [app.moviestudio.JobType.SKELETON] job; the worker
 * pushes a WebSocket event when it completes so clients reload the timeline.
 */
object SkeletonService {
    private val logger = LoggerFactory.getLogger(SkeletonService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Payload of a SKELETON job. */
    @Serializable
    data class SkeletonPayload(val prompt: String = "", val atSeconds: Double = 0.0)

    /** One planned timeline item returned by the model. */
    @Serializable
    data class SkeletonItem(
        val trackType: String = "VIDEO",
        val assetType: String = "VIDEO",
        val description: String = "",
        val startSeconds: Double = 0.0,
        val durationSeconds: Double = 5.0
    )

    private const val SYSTEM_PROMPT =
        "You are SKELETON_PLANNER, a movie pre-production planner inside a movie studio app. " +
            "Given a movie description, the current playhead position and the existing timeline items, " +
            "you plan new placeholder timeline items (video shots, background music, narration). " +
            "Respond ONLY with a JSON array. Each element must be an object with exactly these keys: " +
            "trackType (one of VIDEO, MUSIC, VOICE), assetType (one of VIDEO, IMAGE, MUSIC, VOICE), " +
            "description (a vivid, self-contained description of the media to produce later), " +
            "startSeconds (number), durationSeconds (number, 2-30). " +
            "Rules: place items starting at the given playhead position unless the user asks otherwise; " +
            "avoid overlapping the existing items on the same track; keep a coherent story flow; " +
            "video shots should cover the span contiguously; typically add one MUSIC bed spanning the new section " +
            "and a VOICE narration item when narration fits. Plan 3-8 items. No markdown, no commentary."

    suspend fun executeSkeletonJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) {
        logger.info("Executing skeleton job ${job.id} for movie ${job.movieId}")
        val payload = try {
            json.decodeFromString(SkeletonPayload.serializer(), job.payload)
        } catch (e: Exception) {
            SkeletonPayload()
        }

        onProgress(10, "Reading the current timeline...")
        val timeline = TimelineService.assemble(job.movieId)
            ?: throw IllegalStateException("Movie not found: ${job.movieId}")

        val user = buildUserPrompt(payload, timeline)
        onProgress(25, "Asking Qwen to plan the skeleton...")
        val raw = AIGenerationService.generateText(SYSTEM_PROMPT, user)

        onProgress(60, "Placing planned items on the timeline...")
        val items = parseItems(raw)
        if (items.isEmpty()) {
            throw IllegalStateException("Skeleton planner returned no items")
        }

        val existingTracks = TrackRepository.queryByMovieId(job.movieId).toMutableList()
        var created = 0
        for (item in items.take(12)) {
            val trackType = runCatching { TrackType.valueOf(item.trackType.uppercase()) }.getOrDefault(TrackType.VIDEO)
            val assetType = runCatching { AssetType.valueOf(item.assetType.uppercase()) }.getOrDefault(AssetType.VIDEO)
            if (item.description.isBlank()) continue

            val track = existingTracks.firstOrNull { it.type == trackType } ?: run {
                val newTrack = Track(
                    id = UUID.randomUUID().toString(),
                    movieId = job.movieId,
                    type = trackType,
                    zIndex = (existingTracks.maxOfOrNull { it.zIndex } ?: -1) + 1
                )
                TrackRepository.insert(newTrack)
                existingTracks.add(newTrack)
                newTrack
            }

            // Description-only asset: no media yet, just the plan. Generated later on demand.
            val asset = Asset(
                id = UUID.randomUUID().toString(),
                type = assetType,
                ossUrl = "",
                durationSeconds = item.durationSeconds.coerceIn(1.0, 120.0),
                movieId = job.movieId,
                tags = listOf("skeleton", "description-only"),
                aiPrompt = item.description,
                description = item.description,
                createdAt = System.currentTimeMillis()
            )
            AssetRepository.insert(asset)

            val clip = Clip(
                id = UUID.randomUUID().toString(),
                trackId = track.id,
                assetId = asset.id,
                timelineStart = item.startSeconds.coerceAtLeast(0.0).toFloat(),
                trimIn = 0f,
                trimOut = asset.durationSeconds.toFloat(),
                effectsConfig = "{}"
            )
            ClipRepository.insert(clip)
            created++
        }

        onProgress(90, "Updating movie duration...")
        TimelineService.refreshMovieDuration(job.movieId)

        JobRepository.update(job.copy(status = JobStatus.COMPLETED, resultUrl = null))
        onProgress(100, "Skeleton created: $created items")
        logger.info("Skeleton job ${job.id} completed with $created items")
    }

    private fun buildUserPrompt(payload: SkeletonPayload, timeline: FilmTimeline): String {
        val sb = StringBuilder()
        sb.appendLine("Movie: \"${timeline.movie.title}\" (aspect ${timeline.movie.aspectRatio}).")
        sb.appendLine("Current movie duration: ${timeline.movie.totalDuration} seconds.")
        sb.appendLine("Current playhead position: ${payload.atSeconds} seconds.")
        sb.appendLine("Existing timeline items:")
        var any = false
        for (trackWithClips in timeline.tracks) {
            for (clip in trackWithClips.clips) {
                val asset = AssetRepository.getById(clip.assetId)
                val length = clip.trimOut - clip.trimIn
                val desc = asset?.description ?: asset?.aiPrompt ?: "(no description)"
                sb.appendLine("- [${trackWithClips.track.type}] ${clip.timelineStart}s..${clip.timelineStart + length}s: ${desc.take(120)}")
                any = true
            }
        }
        if (!any) sb.appendLine("- (the timeline is empty)")
        sb.appendLine()
        sb.appendLine("User request: ${payload.prompt}")
        return sb.toString()
    }

    /** Extracts the JSON array from the model response (tolerating markdown fences/preambles). */
    fun parseItems(raw: String): List<SkeletonItem> {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val body = cleaned.substring(start, end + 1)
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SkeletonItem.serializer()), body)
        } catch (e: Exception) {
            logger.warn("Failed to parse skeleton plan: ${e.message}. Raw: ${body.take(300)}")
            emptyList()
        }
    }
}
