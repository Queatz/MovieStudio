package app.moviestudio.service

import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AssetVersion
import app.moviestudio.GenerationSetup
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.VoiceClone
import app.moviestudio.WordTiming
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.VoiceCloneRepository
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The payload carried by every [app.moviestudio.JobType.AI_GEN] job. It contains the full,
 * reusable [GenerationSetup] plus bookkeeping for regeneration and audio-extraction tasks.
 */
@Serializable
data class AiJobPayload(
    val setup: GenerationSetup = GenerationSetup(kind = "video"),
    // When set, the generated media replaces this asset's media (regeneration): the previous
    // media is pushed onto the asset's version history.
    val assetId: String? = null,
    // Optional explicit target asset type name (VIDEO/IMAGE/MUSIC/VOICE/AUDIO).
    val targetType: String? = null,
    // For extract-audio tasks: the media URL to pull the audio track from.
    val sourceUrl: String? = null
)

interface AIGenerationService {
    /**
     * Auto-generates (or regenerates) a transcript with per-word timings for a voice/voiceover
     * [asset], persists the transcript and word timings on the asset and returns the updated asset.
     */
    suspend fun generateTranscript(asset: Asset): Asset

    /**
     * Small synchronous LLM helper used for lyric writing, theme suggestions and skeleton
     * planning. Returns the model's raw text response.
     */
    suspend fun generateText(system: String, user: String): String

    /**
     * Enrolls a new cloned voice (Qwen voice cloning, China mainland) from the given sample audio
     * and persists it. Returns the stored [VoiceClone].
     */
    suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone

    suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit)

    companion object : AIGenerationService {
        private var delegate: AIGenerationService = MockAIService

        fun setInstance(service: AIGenerationService) {
            delegate = service
        }

        override suspend fun generateTranscript(asset: Asset): Asset =
            delegate.generateTranscript(asset)

        override suspend fun generateText(system: String, user: String): String =
            delegate.generateText(system, user)

        override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone =
            delegate.createVoiceClone(name, audioUrl)

        override suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) =
            delegate.executeAiGenerationJob(job, onProgress)
    }
}

/**
 * Shared helpers for turning transcript text into evenly-distributed [WordTiming]s across an
 * asset's duration. Used by both the mock and Qwen-backed services so word timings are always
 * present alongside the transcript text stored in the database.
 */
object TranscriptUtil {
    fun buildWordTimings(text: String, durationSeconds: Double): List<WordTiming> =
        app.moviestudio.buildWordTimings(text, durationSeconds)
}

/**
 * Logic shared by [MockAIService] and [QwenAIService]: payload parsing (including the legacy
 * `{prompt, type}` payload shape), asset creation vs. in-place regeneration with version
 * history, and job completion bookkeeping.
 */
object GenerationCommon {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a job payload, accepting both [AiJobPayload] and the legacy `{prompt, type}` shape. */
    fun parsePayload(rawPayload: String): AiJobPayload {
        if (rawPayload.isBlank()) return AiJobPayload()
        try {
            val obj = json.parseToJsonElement(rawPayload).jsonObject
            if (obj.containsKey("setup")) {
                return json.decodeFromString(AiJobPayload.serializer(), rawPayload)
            }
            // Legacy shape: {"prompt": "...", "type": "VIDEO"}.
            val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
            val type = (obj["type"]?.jsonPrimitive?.contentOrNull ?: "VIDEO").uppercase()
            val kind = when (type) {
                "MUSIC", "AUDIO" -> "music"
                "VOICE" -> "tts"
                "IMAGE" -> "image"
                else -> "video"
            }
            return AiJobPayload(
                setup = GenerationSetup(kind = kind, prompt = prompt, theme = prompt, voice = "Cherry"),
                targetType = type
            )
        } catch (e: Exception) {
            return AiJobPayload()
        }
    }

    /** Resolves the [AssetType] a payload should produce. */
    fun assetTypeFor(payload: AiJobPayload): AssetType {
        payload.targetType?.let { raw ->
            runCatching { return AssetType.valueOf(raw.uppercase()) }
        }
        return when (payload.setup.kind) {
            "image" -> AssetType.IMAGE
            "music" -> AssetType.MUSIC
            "tts" -> AssetType.VOICE
            "sfx", "extract-audio" -> AssetType.AUDIO
            else -> AssetType.VIDEO
        }
    }

    /** The main prompt text describing a generation, used for labels/descriptions. */
    fun promptFor(setup: GenerationSetup): String = when (setup.kind) {
        "music" -> setup.theme.ifBlank { setup.lyric.take(80) }.ifBlank { setup.prompt }
        "tts" -> setup.prompt
        else -> setup.prompt
    }

    /**
     * Persists the outcome of a generation: either updates the target asset in place (pushing the
     * previous media onto its restorable history) or inserts a brand-new asset. Also marks the
     * job as COMPLETED with the media URL.
     */
    fun finalize(
        job: Job,
        payload: AiJobPayload,
        ossUrl: String,
        durationSeconds: Double,
        transcript: String? = null,
        wordTimings: List<WordTiming> = emptyList(),
        sourceOffsetSeconds: Double = 0.0
    ): Asset {
        val now = System.currentTimeMillis()
        val setupJson = json.encodeToString(GenerationSetup.serializer(), payload.setup)
        val prompt = promptFor(payload.setup)

        val existing = payload.assetId?.let { AssetRepository.getById(it) }
        val asset = if (existing != null) {
            val history = if (existing.ossUrl.isNotBlank()) {
                listOf(
                    AssetVersion(
                        ossUrl = existing.ossUrl,
                        durationSeconds = existing.durationSeconds,
                        createdAt = now,
                        prompt = existing.aiPrompt
                    )
                ) + existing.history
            } else {
                existing.history
            }
            val updated = existing.copy(
                ossUrl = ossUrl,
                durationSeconds = durationSeconds,
                aiPrompt = prompt.ifBlank { existing.aiPrompt },
                generationConfig = setupJson,
                history = history,
                voice = payload.setup.voice.ifBlank { existing.voice },
                transcript = transcript ?: existing.transcript,
                wordTimings = wordTimings.ifEmpty { existing.wordTimings },
                sourceOffsetSeconds = sourceOffsetSeconds
            )
            AssetRepository.update(updated)
            updated
        } else {
            val created = Asset(
                id = UUID.randomUUID().toString(),
                type = assetTypeFor(payload),
                ossUrl = ossUrl,
                durationSeconds = durationSeconds,
                movieId = job.movieId.ifBlank { null },
                tags = listOf("ai-generated", payload.setup.kind),
                aiPrompt = prompt,
                description = prompt,
                generationConfig = setupJson,
                voice = payload.setup.voice.ifBlank { null },
                transcript = transcript,
                wordTimings = wordTimings,
                sourceOffsetSeconds = sourceOffsetSeconds,
                createdAt = now
            )
            AssetRepository.insert(created)
            created
        }

        JobRepository.update(job.copy(status = JobStatus.COMPLETED, resultUrl = ossUrl))
        return asset
    }
}

object MockAIService : AIGenerationService {
    private val logger = LoggerFactory.getLogger(MockAIService::class.java)

    const val PLACEHOLDER_VIDEO = "https://movie-studio-bucket.oss-cn-hangzhou.aliyuncs.com/placeholder-video.mp4"
    const val PLACEHOLDER_MUSIC = "https://movie-studio-bucket.oss-cn-hangzhou.aliyuncs.com/placeholder-music.mp3"
    const val PLACEHOLDER_AUDIO = "https://movie-studio-bucket.oss-cn-hangzhou.aliyuncs.com/placeholder-audio.mp3"
    const val PLACEHOLDER_IMAGE = "https://movie-studio-bucket.oss-cn-hangzhou.aliyuncs.com/placeholder-image.png"

    override suspend fun generateTranscript(asset: Asset): Asset {
        delay(300)
        val transcript = (asset.transcript?.takeIf { it.isNotBlank() }
            ?: asset.aiPrompt?.takeIf { it.isNotBlank() }
            ?: "This is an automatically generated voiceover transcript.")
        val updated = asset.copy(
            transcript = transcript,
            wordTimings = TranscriptUtil.buildWordTimings(transcript, asset.durationSeconds)
        )
        AssetRepository.update(updated)
        logger.info("Generated mock transcript for asset ${asset.id}")
        return updated
    }

    override suspend fun generateText(system: String, user: String): String {
        delay(200)
        return when {
            system.contains("SKELETON_PLANNER") -> {
                // Deterministic four-item plan starting at the playhead position mentioned in
                // the user prompt (falls back to 0).
                val at = Regex("playhead position: ([0-9.]+)").find(user)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                // Only a sanitized snippet of the request may be embedded in the JSON string.
                val requestSnippet = (Regex("User request: (.*)").find(user)?.groupValues?.get(1) ?: user)
                    .replace("\"", "'")
                    .replace("\\", "/")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(90)
                """
                [
                  {"trackType": "VIDEO", "assetType": "VIDEO", "description": "Establishing shot: $requestSnippet", "startSeconds": $at, "durationSeconds": 6},
                  {"trackType": "VIDEO", "assetType": "VIDEO", "description": "Close-up reaction shot continuing the story", "startSeconds": ${at + 6}, "durationSeconds": 5},
                  {"trackType": "MUSIC", "assetType": "MUSIC", "description": "Soft underscore building tension", "startSeconds": $at, "durationSeconds": 11},
                  {"trackType": "VOICE", "assetType": "VOICE", "description": "Narrator introduces the scene", "startSeconds": ${at + 1}, "durationSeconds": 5}
                ]
                """.trimIndent()
            }
            system.contains("LYRIC", ignoreCase = true) ->
                "Verse 1:\nCity lights are calling out my name\nEvery street remembers where we came\n\n" +
                    "Chorus:\nWe run, we rise, we glow\nThrough the night we go\n\n" +
                    "Verse 2:\nShadows fade behind us as we fly\nPainting silver dreams across the sky"
            system.contains("THEME", ignoreCase = true) ->
                "Uplifting cinematic electro-pop with soaring strings and a driving beat"
            else -> "Mock response: ${user.take(160)}"
        }
    }

    override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone {
        delay(300)
        val clone = VoiceClone(
            id = UUID.randomUUID().toString(),
            name = name,
            qwenVoiceId = "mock-voice-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
            sourceAudioUrl = audioUrl,
            createdAt = System.currentTimeMillis()
        )
        VoiceCloneRepository.insert(clone)
        logger.info("Created mock voice clone ${clone.id} (${clone.qwenVoiceId})")
        return clone
    }

    override suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) {
        logger.info("Executing mock AI generation job ${job.id} for movie ${job.movieId}")
        val payload = GenerationCommon.parsePayload(job.payload)
        val setup = payload.setup

        onProgress(15, "Analyzing prompt...")
        delay(600)
        onProgress(45, "Generating ${setup.kind} (mock)...")
        delay(900)
        onProgress(80, "Uploading media...")
        delay(400)

        val assetType = GenerationCommon.assetTypeFor(payload)
        val ossUrl = when {
            payload.setup.kind == "extract-audio" -> payload.sourceUrl ?: PLACEHOLDER_AUDIO
            assetType == AssetType.VIDEO -> PLACEHOLDER_VIDEO
            assetType == AssetType.IMAGE -> PLACEHOLDER_IMAGE
            assetType == AssetType.MUSIC -> PLACEHOLDER_MUSIC
            else -> PLACEHOLDER_AUDIO
        }
        val duration = when (assetType) {
            AssetType.VIDEO -> setup.durationSeconds.takeIf { it > 0 } ?: 5.0
            AssetType.IMAGE -> 5.0
            AssetType.MUSIC -> 30.0
            AssetType.VOICE -> {
                val words = setup.prompt.split(Regex("\\s+")).count { it.isNotBlank() }
                (words * 0.42).coerceAtLeast(2.0)
            }
            else -> setup.durationSeconds.takeIf { it > 0 } ?: 5.0
        }

        val transcript = if (assetType == AssetType.VOICE) setup.prompt.ifBlank { null } else null
        val timings = transcript?.let { TranscriptUtil.buildWordTimings(it, duration) } ?: emptyList()

        var asset = GenerationCommon.finalize(job, payload, ossUrl, duration, transcript, timings)

        // Voice media auto-generates a transcript with word timings when none was provided.
        if (assetType == AssetType.VOICE && asset.transcript.isNullOrBlank()) {
            onProgress(92, "Transcribing voiceover...")
            asset = generateTranscript(asset)
        }

        onProgress(100, "Generation completed")
        logger.info("Mock AI generation job ${job.id} completed. Asset: ${asset.id}")
    }
}
