package app.moviestudio.service

import app.moviestudio.AiChatMessage
import app.moviestudio.AiChatRole
import app.moviestudio.AiLedgerEntry
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AssetVersion
import app.moviestudio.GenerationSetup
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.VoiceClone
import app.moviestudio.WordTiming
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.VoiceCloneRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
     * Multi-turn variant of [generateText] used by the AI prompt dialogs: the full conversation
     * (alternating user/assistant turns, newest last) is sent with the [system] prompt so
     * follow-up messages can refine the previous response. The default implementation folds the
     * conversation into a single user prompt for backends without native chat support.
     */
    suspend fun generateChat(system: String, messages: List<AiChatMessage>): String =
        generateText(system, foldChatIntoPrompt(messages))

    /**
     * Enrolls a new cloned voice (Qwen voice cloning, China mainland) from the given sample audio
     * and persists it. Returns the stored [VoiceClone].
     */
    suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone

    suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit)

    companion object : AIGenerationService {
        private var delegate: AIGenerationService = QwenAIService

        fun setInstance(service: AIGenerationService) {
            delegate = service
        }

        override suspend fun generateTranscript(asset: Asset): Asset =
            delegate.generateTranscript(asset)

        override suspend fun generateText(system: String, user: String): String =
            delegate.generateText(system, user)

        override suspend fun generateChat(system: String, messages: List<AiChatMessage>): String =
            delegate.generateChat(system, messages)

        override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone =
            delegate.createVoiceClone(name, audioUrl)

        override suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) =
            delegate.executeAiGenerationJob(job, onProgress)
    }
}

/**
 * Folds a chat conversation into a single user prompt for text backends that only accept one
 * system + one user message. Single-turn conversations pass through unchanged.
 */
fun foldChatIntoPrompt(messages: List<AiChatMessage>): String {
    if (messages.size <= 1) return messages.firstOrNull()?.content.orEmpty()
    return messages.joinToString("\n\n") { message ->
        val speaker = if (message.role == AiChatRole.ASSISTANT) "Your previous response" else "User"
        "$speaker:\n${message.content}"
    } + "\n\nRespond to the latest user message, keeping the same output format."
}

/**
 * Shared helpers for turning transcript text into evenly-distributed [WordTiming]s across an
 * asset's duration. Used by the Qwen-backed service so word timings are always present alongside
 * the transcript text stored in the database.
 */
object TranscriptUtil {
    fun buildWordTimings(text: String, durationSeconds: Double): List<WordTiming> =
        app.moviestudio.buildWordTimings(text, durationSeconds)
}

/**
 * Logic used by [QwenAIService]: payload parsing (including the legacy `{prompt, type}` payload
 * shape), asset creation vs. in-place regeneration with version history, and job completion
 * bookkeeping.
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
        sourceOffsetSeconds: Double = 0.0,
        // AI calls made while producing this media; appended to the asset's cost ledger.
        ledgerEntries: List<AiLedgerEntry> = emptyList()
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
                        prompt = existing.aiPrompt,
                        type = existing.type
                    )
                ) + existing.history
            } else {
                existing.history
            }
            val updated = existing.copy(
                // A regeneration can convert the asset between media types (e.g. editing an
                // image into a video or vice versa), so the type always follows the payload.
                type = assetTypeFor(payload),
                ossUrl = ossUrl,
                durationSeconds = durationSeconds,
                aiPrompt = prompt.ifBlank { existing.aiPrompt },
                generationConfig = setupJson,
                history = history,
                ledger = existing.ledger + ledgerEntries,
                voice = payload.setup.voice.ifBlank { existing.voice },
                transcript = transcript ?: existing.transcript,
                wordTimings = wordTimings.ifEmpty { existing.wordTimings },
                sourceOffsetSeconds = sourceOffsetSeconds
            )
            AssetRepository.update(updated)
            // A (re)generation replaces the asset's media entirely, so any timeline clip that
            // referenced it must follow the freshly generated media instead of the stale
            // placeholder/previous length (e.g. a skeleton-planned VOICE clip kept at its planned
            // duration while the generated voiceover is longer/shorter). Resize referencing clips
            // to span the full new media, then refresh the movie's auto-calculated duration.
            val fullTrimOut = durationSeconds.toFloat()
            var clipsChanged = false
            ClipRepository.queryByAssetId(existing.id).forEach { clip ->
                if (clip.trimIn != 0f || clip.trimOut != fullTrimOut) {
                    ClipRepository.update(clip.copy(trimIn = 0f, trimOut = fullTrimOut))
                    clipsChanged = true
                }
            }
            if (clipsChanged) {
                job.movieId.takeIf { it.isNotBlank() }?.let { TimelineService.refreshMovieDuration(it) }
            }
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
                ledger = ledgerEntries,
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
