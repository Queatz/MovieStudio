package app.moviestudio.routing

import app.moviestudio.AiChatMessage
import app.moviestudio.AiChatRole
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.GenerationSetup
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.MusicSequence
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.CharacterRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.SceneRepository
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.GenerationCommon
import app.moviestudio.service.MusicSynthesizer
import app.moviestudio.storage.OssService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Serializable
data class GenerateMediaRequest(
    val movieId: String? = null,
    val setup: GenerationSetup,
    // When set, regenerate this asset in place (previous media goes to its history).
    val assetId: String? = null
)

@Serializable
data class GenerateTextRequest(
    val prompt: String = "",
    val movieTitle: String = "",
    // Optional multi-turn conversation (newest message last). When present it takes precedence
    // over [prompt], letting the AI prompt dialogs send follow-up refinements.
    val messages: List<AiChatMessage> = emptyList()
)

/** The conversation to send: the explicit [GenerateTextRequest.messages], or a single-turn one. */
private fun GenerateTextRequest.chatMessages(defaultPrompt: String): List<AiChatMessage> =
    messages.ifEmpty {
        listOf(AiChatMessage(role = AiChatRole.USER, content = prompt.ifBlank { defaultPrompt }))
    }

@Serializable
data class GeneratedTextResponse(val text: String)

@Serializable
data class MusicSequenceRequest(val movieId: String? = null, val sequence: MusicSequence)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Expands character/scene references into concrete reference images + prompt context so the
 * generation service only deals with plain URLs and text. The original ids stay on the setup so
 * the generation can be re-edited later.
 */
private fun expandReferences(setup: GenerationSetup): GenerationSetup {
    if (setup.characterIds.isEmpty() && setup.sceneIds.isEmpty()) return setup
    val referenceImages = setup.referenceImages.toMutableList()
    val promptAdditions = StringBuilder()
    for (id in setup.characterIds) {
        val character = CharacterRepository.getById(id) ?: continue
        referenceImages.addAll(character.referenceImages)
        promptAdditions.append(" Featuring character \"${character.name}\": ${character.description}.")
    }
    for (id in setup.sceneIds) {
        val scene = SceneRepository.getById(id) ?: continue
        referenceImages.addAll(scene.referenceImages)
        promptAdditions.append(" Set in \"${scene.name}\": ${scene.description}.")
    }
    return setup.copy(
        prompt = (setup.prompt + promptAdditions).trim(),
        referenceImages = referenceImages.distinct().take(6)
    )
}

private fun labelFor(setup: GenerationSetup): String {
    val what = when (setup.kind) {
        "image" -> if (setup.imageUrl.isNullOrBlank()) "Image" else "Image edit"
        "music" -> "Music"
        "tts" -> "Voice"
        "sfx" -> "Sound effect"
        else -> "Video (${setup.resolveVideoModelKind().uppercase()})"
    }
    val text = GenerationCommon.promptFor(setup)
    return "$what: ${text.take(60)}"
}

fun Route.generationRoutes() {
    route("/api/generate") {
        // Queues an AI media generation job (video/image/music/tts/sfx). All generation happens
        // asynchronously on the server; progress streams over the jobs WebSocket.
        post("/media") {
            try {
                val request = call.receive<GenerateMediaRequest>()
                val expanded = expandReferences(request.setup)
                val payload = AiJobPayload(setup = expanded, assetId = request.assetId)
                val job = Job(
                    id = UUID.randomUUID().toString(),
                    movieId = request.movieId ?: "",
                    type = JobType.AI_GEN,
                    status = JobStatus.PENDING,
                    payload = json.encodeToString(AiJobPayload.serializer(), payload),
                    resultUrl = null,
                    label = labelFor(expanded),
                    createdAt = System.currentTimeMillis()
                )
                JobRepository.insert(job)
                call.respond(HttpStatusCode.Accepted, job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Writes complete song lyrics for the music editor (AI-generate icon button).
        post("/lyrics") {
            try {
                val request = call.receive<GenerateTextRequest>()
                val text = AIGenerationService.generateChat(
                    system = "You are a professional LYRICist for movie soundtracks. Write complete, well-structured " +
                        "song lyrics (with [verse]/[chorus] section markers) matching the requested theme. " +
                        "Respond with only the lyrics, no commentary.",
                    messages = request.chatMessages("An original song for the movie \"${request.movieTitle}\"")
                )
                call.respond(GeneratedTextResponse(text))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Generic writing assistant backing the app-wide AI assist (Alt+Enter in any studio text
        // field). Writes whatever text the user asked for so it can be inserted into the field.
        post("/text") {
            try {
                val request = call.receive<GenerateTextRequest>()
                val text = AIGenerationService.generateChat(
                    system = "You are a helpful writing assistant embedded in a movie studio app. " +
                        "Given the user's request, write the exact text they want inserted into a " +
                        "text field. Respond with only that text, no commentary or surrounding quotes.",
                    messages = request.chatMessages("Write text for the movie \"${request.movieTitle}\"")
                )
                call.respond(GeneratedTextResponse(text))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Suggests a musical theme/style description (AI-generate icon button).
        post("/theme") {
            try {
                val request = call.receive<GenerateTextRequest>()
                val text = AIGenerationService.generateChat(
                    system = "You are a music supervisor. Given a movie or mood, respond with a single-sentence " +
                        "musical THEME description (genre, instrumentation, tempo, mood) suitable as a " +
                        "music-generation prompt. Respond with only that sentence.",
                    messages = request.chatMessages("A soundtrack theme for the movie \"${request.movieTitle}\"")
                )
                call.respond(GeneratedTextResponse(text))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }

    route("/api/music") {
        // Renders a mini-sequencer pattern to a WAV, uploads it and stores it as a MUSIC asset.
        // The pattern itself is kept on the asset so it can be reopened and edited later.
        post("/sequence") {
            try {
                val request = call.receive<MusicSequenceRequest>()
                if (request.sequence.notes.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Add at least one note to the sequence")
                }
                val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("moviestudio_seq_").toFile() }
                try {
                    val wavFile = File(tempDir, "sequence.wav")
                    // Sample instruments: decode every referenced library sound into PCM first
                    // (per-note instruments plus the sequence-level fallback). Notes whose sample
                    // cannot be decoded fall back to the sine waveform.
                    val sampleUrls = buildSet {
                        request.sequence.notes.forEach { note ->
                            val waveform = note.waveform ?: request.sequence.waveform
                            val url = note.sampleUrl ?: request.sequence.sampleUrl
                            if (waveform == "sample" && !url.isNullOrBlank()) add(url)
                        }
                    }
                    val samplePcmByUrl = sampleUrls.mapNotNull { url ->
                        MusicSynthesizer.loadSamplePcm(url)?.let { url to it }
                    }.toMap()
                    val duration = withContext(Dispatchers.IO) {
                        MusicSynthesizer.renderToWav(request.sequence, wavFile, samplePcmByUrl)
                    }
                    val objectKey = "music-sequences/${UUID.randomUUID()}.wav"
                    val ossUrl = OssService.uploadFile(objectKey, wavFile)
                    val asset = Asset(
                        id = UUID.randomUUID().toString(),
                        type = AssetType.MUSIC,
                        ossUrl = ossUrl,
                        durationSeconds = duration,
                        movieId = request.movieId,
                        tags = listOf("sequencer", "music"),
                        aiPrompt = null,
                        description = request.sequence.name.ifBlank { "Sequencer track" },
                        generationConfig = json.encodeToString(MusicSequence.serializer(), request.sequence),
                        createdAt = System.currentTimeMillis()
                    )
                    AssetRepository.insert(asset)
                    call.respond(HttpStatusCode.Created, asset)
                } finally {
                    withContext(Dispatchers.IO) { runCatching { tempDir.deleteRecursively() } }
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
