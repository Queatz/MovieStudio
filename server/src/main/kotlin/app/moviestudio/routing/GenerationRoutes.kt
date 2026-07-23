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
import app.moviestudio.database.VisualStyleRepository
import app.moviestudio.job.JobQueueWorker
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
    val assetId: String? = null,
    // The library asset this generation was launched from (the asset whose details dialog opened
    // this generate/regenerate dialog), if any. Recorded on the job so the UI can track which
    // asset's generations are still in flight. Note this differs from [assetId]: a regeneration of
    // real media has a null [assetId] (the result becomes a brand-new asset) but still carries the
    // originating asset here.
    val sourceAssetId: String? = null
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
 * The most reference images WAN 2.7 R2V accepts in one generation (the `reference_image` entries
 * under `input.media`). This is the single budget the reference photos are balanced within — see
 * [balanceReferenceImages] — so it must stay in sync with the `take(...)` cap the R2V request
 * builder applies (`QwenAIService.buildVideoRequestBody`).
 */
internal const val MAX_R2V_REFERENCE_IMAGES = 4

/**
 * A named group of reference images that must stay bound to its subject in the generation prompt.
 * [images] are that subject's reference photos in preference order; [describe] renders the prompt
 * sentence that introduces the subject given the 1-based indices its images ended up at in the
 * final reference-image list. Empty indices mean the subject contributed no surviving photo, so it
 * is introduced by name/description alone (the extra-reference-photos group has no name and renders
 * nothing).
 */
internal data class ReferenceSubject(
    val images: List<String>,
    val describe: (imageIndices: List<Int>) -> String,
)

/** "Reference image 3" / "Reference images 3-4" for a contiguous 1-based index range (empty => ""). */
private fun referenceImagePhrase(indices: List<Int>): String = when {
    indices.isEmpty() -> ""
    indices.size == 1 -> "Reference image ${indices.first()}"
    else -> "Reference images ${indices.first()}-${indices.last()}"
}

/**
 * Balances a limited reference-image [budget] across every selected [subjects] (the user's extra
 * reference photos, each character and each scene) with a round-robin: the first pass gives every
 * subject its first photo before any subject receives a second, so as many distinct selections as
 * possible survive the cap. This replaces the previous first-come-first-served flattening where a
 * whole character's photos (or a scene's) could be silently dropped, leaving that subject named in
 * the prompt with no image at all. Duplicate URLs are collapsed so a photo shared by two subjects
 * only occupies one slot.
 *
 * Returns the final, de-duplicated image list (each subject's images kept contiguous) paired with
 * the prompt additions whose 1-based image references line up with that list, so every photo is
 * explicitly bound to its character/scene name instead of landing in an anonymous pool.
 */
internal fun balanceReferenceImages(
    subjects: List<ReferenceSubject>,
    budget: Int = MAX_R2V_REFERENCE_IMAGES,
): Pair<List<String>, String> {
    // Round-robin selection: one fresh image per subject per pass until the budget is spent.
    val picked = List(subjects.size) { mutableListOf<String>() }
    val seen = mutableSetOf<String>()
    val cursors = IntArray(subjects.size)
    var total = 0
    var progressed = true
    while (total < budget && progressed) {
        progressed = false
        for (i in subjects.indices) {
            if (total >= budget) break
            val images = subjects[i].images
            // Advance past already-picked / duplicate URLs to this subject's next fresh photo.
            while (cursors[i] < images.size && images[cursors[i]] in seen) cursors[i]++
            if (cursors[i] < images.size) {
                seen.add(images[cursors[i]])
                picked[i].add(images[cursors[i]])
                cursors[i]++
                total++
                progressed = true
            }
        }
    }

    // Emit the surviving images grouped by subject (contiguous) and build the matching prompt text
    // referencing each subject's 1-based image indices.
    val finalImages = mutableListOf<String>()
    val prompt = StringBuilder()
    for (i in subjects.indices) {
        val start = finalImages.size + 1
        finalImages.addAll(picked[i])
        val indices = (start until start + picked[i].size).toList()
        prompt.append(subjects[i].describe(indices))
    }
    return finalImages to prompt.toString()
}

/**
 * Expands character/scene references into concrete reference images + prompt context so the
 * generation service only deals with plain URLs and text. The original ids stay on the setup so
 * the generation can be re-edited later. When a [GenerationSetup.styleId] is set, the matching
 * visual style's text is appended after those reference additions as
 * `"\n\nVisual style: <style text>\n\n"`.
 *
 * The reference-image slots are shared fairly across every selection (extra reference photos, each
 * character and each scene) via [balanceReferenceImages], guaranteeing at least one photo from each
 * selection survives before any subject contributes a second — even when the user picks more
 * subjects than WAN can accept. Each surviving photo is bound to its subject's name in the prompt
 * (e.g. `Reference image 1 shows character "Alice": ...`) so the model no longer has to guess which
 * anonymous photo belongs to which name.
 *
 * For video generations, each character with a non-blank [Character.mainLanguage] also contributes
 * `"<name> speaks in <mainLanguage> unless otherwise specified."` so dialogue defaults stay
 * consistent unless the prompt overrides them.
 */
internal fun expandReferences(setup: GenerationSetup): GenerationSetup {
    var expanded = setup

    if (setup.characterIds.isNotEmpty() || setup.sceneIds.isNotEmpty()) {
        val subjects = mutableListOf<ReferenceSubject>()

        // The user's extra reference photos are one selection: they carry no name, so they are simply
        // included (up to their share of the budget) with no prompt sentence of their own.
        if (setup.referenceImages.isNotEmpty()) {
            subjects.add(ReferenceSubject(images = setup.referenceImages) { "" })
        }

        for (id in setup.characterIds) {
            val character = CharacterRepository.getById(id) ?: continue
            subjects.add(
                ReferenceSubject(images = character.referenceImages) { indices ->
                    val phrase = referenceImagePhrase(indices)
                    val base = if (phrase.isEmpty()) {
                        " Featuring character \"${character.name}\": ${character.description}."
                    } else {
                        val verb = if (indices.size == 1) "shows" else "show"
                        " $phrase $verb character \"${character.name}\": ${character.description}."
                    }
                    // Video prompts carry each character's default spoken language so dialogue
                    // stays consistent unless the user overrides it in the prompt itself.
                    val language = character.mainLanguage.trim()
                    if (setup.kind == "video" && language.isNotEmpty()) {
                        base + " ${character.name} speaks in $language unless otherwise specified."
                    } else {
                        base
                    }
                }
            )
        }

        for (id in setup.sceneIds) {
            val scene = SceneRepository.getById(id) ?: continue
            subjects.add(
                ReferenceSubject(images = scene.referenceImages) { indices ->
                    val phrase = referenceImagePhrase(indices)
                    if (phrase.isEmpty()) {
                        " Set in \"${scene.name}\": ${scene.description}."
                    } else {
                        val verb = if (indices.size == 1) "shows" else "show"
                        " $phrase $verb the scene \"${scene.name}\": ${scene.description}."
                    }
                }
            )
        }

        val (referenceImages, promptAdditions) = balanceReferenceImages(subjects)
        expanded = expanded.copy(
            prompt = (setup.prompt + promptAdditions).trim(),
            referenceImages = referenceImages
        )
    }

    // Visual style rides after every reference-photo/character/scene prompt addition so the look
    // stays consistent across independent visual generations without competing with subject text.
    val styleId = expanded.styleId?.takeIf { it.isNotBlank() }
    if (styleId != null) {
        val styleText = VisualStyleRepository.getById(styleId)?.style?.trim().orEmpty()
        if (styleText.isNotEmpty()) {
            expanded = expanded.copy(prompt = expanded.prompt.trimEnd() + "\n\nVisual style: $styleText\n\n")
        }
    }

    return expanded
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
                    createdAt = System.currentTimeMillis(),
                    sourceAssetId = request.sourceAssetId ?: request.assetId
                )
                JobRepository.insert(job)
                // A new job was started: make sure the queue worker is running to pick it up.
                JobQueueWorker.start()
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

        // Generic writing assistant backing the app-wide AI chat (Alt+Enter in any studio text
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
