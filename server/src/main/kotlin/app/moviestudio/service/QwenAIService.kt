package app.moviestudio.service

import app.moviestudio.AiChatMessage
import app.moviestudio.AiChatRole
import app.moviestudio.AiLedgerEntry
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.GenerationSetup
import app.moviestudio.Job
import app.moviestudio.VoiceClone
import app.moviestudio.WordTiming
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.VoiceCloneRepository
import app.moviestudio.storage.OssService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * Production implementation of [AIGenerationService] backed by the Alibaba Model Studio
 * (Qwen / DashScope) APIs.
 *
 * Supported generation tasks (all executed asynchronously through the job queue):
 * - video: WAN 2.7 family — T2V (prompt only), I2V (first-frame image), R2V (reference images).
 * - image: text-to-image, or image-to-image editing when a base image is attached.
 * - music: Fun-Music (`fun-music-v1`) with lyrics/theme/instrumental options.
 * - tts:   Qwen TTS with preset or cloned voices; transcripts + word timings are stored.
 * - sfx:   sound effects via the mode picked in the setup — direct text-to-audio, video-driven
 *          (scoring a WAN source video) both backed by [QwenConfig.audioModel], or the legacy
 *          WAN video generation followed by ffmpeg audio extraction.
 * - extract-audio: pulls the audio track out of an existing media URL.
 *
 * Generated media is always downloaded and re-hosted on our own Alibaba OSS bucket, and the
 * media duration is probed with ffprobe so timeline placement is accurate.
 */
object QwenAIService : AIGenerationService {
    private val logger = LoggerFactory.getLogger(QwenAIService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // All outbound HTTP goes through this Ktor client (CIO engine).
    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 300_000
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Text (chat) helper: lyric writing, theme ideas, skeleton planning, prompt refinement
    // ------------------------------------------------------------------------------------------

    override suspend fun generateText(system: String, user: String): String =
        generateChat(system, listOf(AiChatMessage(role = AiChatRole.USER, content = user)))

    override suspend fun generateChat(system: String, messages: List<AiChatMessage>): String =
        chatCompletion(system, messages).first

    /**
     * Runs a chat completion, returning the response text plus the raw response JSON so callers
     * that bill the call to an asset's cost ledger can read its `usage` token counts. Offline mode
     * returns a deterministic canned response with no usage (null).
     */
    private suspend fun chatCompletion(system: String, messages: List<AiChatMessage>): Pair<String, JsonObject?> {
        // Graceful degradation: without Model Studio credentials, answer offline with
        // deterministic canned responses so planning/lyrics/themes keep working in dev.
        if (!QwenConfig.isConfigured) return offlineGenerateText(system, foldChatIntoPrompt(messages)) to null
        val body = buildJsonObject {
            put("model", QwenConfig.chatModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
        }
        val response = postJson("${QwenConfig.openAiBaseUrl}/chat/completions", body, async = false)
        val content = response["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
        if (content.isNullOrBlank()) {
            throw IllegalStateException("Qwen chat returned an empty response")
        }
        return content.trim() to response
    }

    // ------------------------------------------------------------------------------------------
    // AI-cost ledger: capturing token usage from Model Studio responses
    // ------------------------------------------------------------------------------------------

    /**
     * Total tokens reported in a Model Studio / OpenAI-compatible response's top-level `usage`
     * block, accepting the `total_tokens` shortcut or the `input`/`output` (a.k.a.
     * `prompt`/`completion`) split. Returns null when the response carries no token usage.
     */
    private fun parseUsageTokens(response: JsonObject): Long? {
        val usage = response["usage"]?.jsonObject ?: return null
        fun tokenCount(key: String): Long? =
            usage[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
        tokenCount("total_tokens")?.let { return it }
        val input = tokenCount("input_tokens") ?: tokenCount("prompt_tokens")
        val output = tokenCount("output_tokens") ?: tokenCount("completion_tokens")
        return if (input != null || output != null) (input ?: 0L) + (output ?: 0L) else null
    }

    /**
     * Appends a ledger entry recording an AI [model] call to this per-job ledger, resolving the
     * call's token usage from [response] and its per-token price from [QwenConfig]. Every AI call
     * connected to an asset is recorded, even ones the API bills by other units (0 tokens).
     */
    private fun MutableList<AiLedgerEntry>.recordCall(description: String, model: String, response: JsonObject) {
        add(
            AiLedgerEntry(
                description = description,
                model = model,
                tokens = parseUsageTokens(response) ?: 0L,
                costPerToken = QwenConfig.usdPerToken(model),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Builds the ledger entry for an image-generation/edit call. Unlike the chat/TTS models,
     * Qwen-Image (`qwen-image-max` / `qwen-image-edit-max`) is billed per generated image at a
     * flat price, so its response never carries an `input_tokens`/`output_tokens` usage block -
     * feeding it through [recordCall] always logged 0 tokens and $0.00, which is the bug this
     * works around. We still record a meaningful, non-zero token figure by converting the image's
     * pixel dimensions into the same 28x28-patch vision-token count Qwen-VL models report, then
     * back-derive the per-token price so the entry's total cost still equals the real flat
     * per-image price ([QwenConfig.usdPerImage]). If a future/alternate image model ever does
     * report real token usage, that's honored instead.
     */
    internal fun buildImageLedgerEntry(description: String, model: String, response: JsonObject, resolution: String): AiLedgerEntry {
        parseUsageTokens(response)?.takeIf { it > 0 }?.let { tokens ->
            return AiLedgerEntry(
                description = description,
                model = model,
                tokens = tokens,
                costPerToken = QwenConfig.usdPerToken(model),
                createdAt = System.currentTimeMillis()
            )
        }
        val (width, height) = parseImageDimensions(response, resolution)
        val tokens = (ceil(width / 28.0) * ceil(height / 28.0)).toLong().coerceAtLeast(1L)
        return AiLedgerEntry(
            description = description,
            model = model,
            tokens = tokens,
            costPerToken = QwenConfig.usdPerImage() / tokens,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Pixel dimensions for an image call: prefers the response's own `usage.width`/`usage.height`
     * (when the API reports them), falling back to the requested `WxH`/`W*H` [resolution] string.
     */
    internal fun parseImageDimensions(response: JsonObject, resolution: String): Pair<Double, Double> {
        val usage = response["usage"]?.jsonObject
        val usageWidth = usage?.get("width")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val usageHeight = usage?.get("height")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (usageWidth != null && usageHeight != null) return usageWidth to usageHeight
        val parts = resolution.split("x", "X", "*")
        val width = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 1024.0
        val height = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1024.0
        return width to height
    }

    /**
     * Best-effort prompt enrichment via the OpenAI-compatible Qwen chat endpoint.
     * Returns the original prompt if the call fails so generation can still proceed.
     */
    private suspend fun refinePrompt(prompt: String, mediaKind: String, ledger: MutableList<AiLedgerEntry>): String {
        if (prompt.isBlank()) return prompt
        return try {
            val (refined, response) = chatCompletion(
                system = "You expand short prompts into a single vivid, concise $mediaKind generation prompt. " +
                    "Respond with only the improved prompt, no preamble.",
                messages = listOf(AiChatMessage(role = AiChatRole.USER, content = prompt))
            )
            response?.let { ledger.recordCall("Refined $mediaKind prompt", QwenConfig.chatModel, it) }
            refined
        } catch (e: Exception) {
            logger.warn("Prompt refinement failed, using original prompt: ${e.message}")
            prompt
        }
    }

    // ------------------------------------------------------------------------------------------
    // Voice cloning (Qwen voice enrollment, China mainland)
    // ------------------------------------------------------------------------------------------

    override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone {
        // Graceful degradation: enroll an offline mock voice when Qwen is not configured.
        if (!QwenConfig.isConfigured) {
            logger.warn("Qwen not configured; enrolling offline mock voice clone for '{}'.", name)
            val clone = VoiceClone(
                id = UUID.randomUUID().toString(),
                name = name,
                qwenVoiceId = "mock-voice-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                sourceAudioUrl = audioUrl,
                createdAt = System.currentTimeMillis()
            )
            VoiceCloneRepository.insert(clone)
            return clone
        }
        val prefix = name.lowercase().replace(Regex("[^a-z0-9]"), "").take(9).ifBlank { "voice" }
        val body = buildJsonObject {
            put("model", QwenConfig.voiceEnrollModel)
            putJsonObject("input") {
                put("action", "create_voice")
                put("target_model", QwenConfig.voiceCloneTargetModel)
                put("prefix", prefix)
                put("url", audioUrl)
            }
        }
        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/audio/tts/customization",
            body,
            async = false
        )
        val voiceId = response["output"]?.jsonObject?.get("voice_id")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Voice enrollment response missing voice_id: $response")
        val clone = VoiceClone(
            id = UUID.randomUUID().toString(),
            name = name,
            qwenVoiceId = voiceId,
            sourceAudioUrl = audioUrl,
            createdAt = System.currentTimeMillis()
        )
        VoiceCloneRepository.insert(clone)
        logger.info("Created Qwen voice clone {} ({})", clone.id, voiceId)
        return clone
    }

    // ------------------------------------------------------------------------------------------
    // Job execution
    // ------------------------------------------------------------------------------------------

    override suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) {
        logger.info("Executing Qwen AI generation job ${job.id} for movie ${job.movieId} ")
        onProgress(8, "Parsing generation request...")
        val payload = GenerationCommon.parsePayload(job.payload)
        val setup = payload.setup
        // Every AI call this job makes is recorded here and folded into the asset's cost ledger.
        val ledger = mutableListOf<AiLedgerEntry>()

        when (setup.kind) {
            "music" -> executeMusic(job, payload, ledger, onProgress)
            "tts" -> executeTts(job, payload, ledger, onProgress)
            "sfx" -> executeSoundEffect(job, payload, ledger, onProgress)
            "extract-audio" -> executeExtractAudio(job, payload, ledger, onProgress)
            "image" -> executeImage(job, payload, ledger, onProgress)
            else -> executeVideo(job, payload, ledger, onProgress)
        }
        onProgress(100, "Generation completed")
    }

    /** WAN 2.7 video generation: model picked predictably by the setup (T2V / I2V / R2V / video-edit). */
    private suspend fun executeVideo(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val modelKind = setup.resolveVideoModelKind()
        val model = when (modelKind) {
            "videoedit" -> QwenConfig.videoModelEdit
            "i2v" -> QwenConfig.videoModelI2V
            "r2v" -> QwenConfig.videoModelR2V
            else -> QwenConfig.videoModelT2V
        }

        onProgress(12, "Refining prompt with Qwen...")
        val refinedPrompt = refinePrompt(setup.prompt, "video", ledger)

        onProgress(20, "Submitting $modelKind task ($model)...")
        val requestBody = buildVideoRequestBody(setup, refinedPrompt, modelKind, model)
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/video-generation/video-synthesis",
            requestBody = requestBody,
            mediaUrlKeys = listOf("video_url", "url"),
            ledger = ledger,
            ledgerModel = model,
            ledgerDescription = "Generated video ($model)",
        ) { pct -> onProgress((20 + pct * 0.6).toInt().coerceIn(20, 80), "Generating video... $pct%") }

        onProgress(85, "Uploading generated video to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "video", "mp4", setup.durationSeconds.takeIf { it > 0 } ?: 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
    }

    /**
     * Builds the DashScope video-synthesis request body for the WAN 2.7 family. Pure and
     * network-free so the per-[modelKind] input shape can be unit-tested (see
     * `QwenVideoRequestTest`); [modelKind] comes from [GenerationSetup.resolveVideoModelKind].
     *
     * The kind decides which optional input the body carries under `input`:
     * - i2v: the first-frame image as a list of media objects under `media`, where each entry is
     *        `{ "type": "first_frame", "url": <url> }` — the shape WAN 2.7 image-to-video
     *        documents (see the Model Studio "Wan 2.7 - image-to-video" API reference, which also
     *        allows `last_frame`/`driving_audio`/`first_clip` entries). Model Studio rejects the
     *        legacy `img_url` with "Field required: input.media", a scalar `media` with "Input
     *        should be a valid list: input.media", a list of bare URL strings with the same
     *        message, and a `{ "image": <url> }` entry with "Field required: input.media.0.url &
     *        Field required: input.media.0.type".
     * - r2v: up to four reference images as a list of media objects under `input.media`, each
     *        `{ "type": "reference_image", "url": <url> }`. Model Studio only accepts the media
     *        types `reference_image` / `reference_video` / `first_frame` (a `reference` type is
     *        rejected with "Input should be 'reference_image', 'reference_video' or 'first_frame':
     *        input.media.0.type"), and sending references under the legacy `ref_images_url` is
     *        rejected with "Field required: input.media". R2V also needs an explicit output
     *        `size` — without it the task fails with "'NoneType' object has no attribute
     *        'resolution'".
     * - videoedit: the base `video_url`, plus optional reference images and a guiding first frame.
     */
    internal fun buildVideoRequestBody(
        setup: GenerationSetup,
        refinedPrompt: String,
        modelKind: String,
        model: String,
    ): JsonObject = buildJsonObject {
        put("model", model)
        putJsonObject("input") {
            put("prompt", refinedPrompt)
            if (setup.negativePrompt.isNotBlank()) put("negative_prompt", setup.negativePrompt)
            when (modelKind) {
                // WAN 2.7 I2V expects the first-frame image as a list of media objects under
                // `input.media`, each `{ "type": ..., "url": ... }`. A `first_frame` entry drives
                // basic image-to-video; a bare URL or an `{ "image": <url> }` entry is rejected
                // with "Field required: input.media.0.url & Field required: input.media.0.type".
                // An optional `last_frame` entry (the end image) makes the clip interpolate from
                // the first frame to it.
                "i2v" -> put("media", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "first_frame")
                        put("url", setup.imageUrl?.let(OssService::freshDownloadUrl) ?: "")
                    })
                    val endImageUrl = setup.endImageUrl
                    if (!endImageUrl.isNullOrBlank()) add(buildJsonObject {
                        put("type", "last_frame")
                        put("url", OssService.freshDownloadUrl(endImageUrl))
                    })
                })
                // WAN 2.7 R2V expects the reference images as a list of media objects under
                // `input.media`, each `{ "type": "reference_image", "url": <url> }`. Only the
                // media types reference_image / reference_video / first_frame are accepted (a
                // `reference` type is rejected with "Input should be 'reference_image',
                // 'reference_video' or 'first_frame': input.media.0.type"), and the legacy
                // `ref_images_url` field is rejected with "Field required: input.media".
                "r2v" -> put("media", buildJsonArray {
                    setup.referenceImages.take(4).forEach {
                        add(buildJsonObject {
                            put("type", "reference_image")
                            put("url", OssService.freshDownloadUrl(it))
                        })
                    }
                })
                // Full wan2.7-videoedit support: the base video to edit, plus every optional
                // guidance input the model accepts — reference images and a guiding first frame.
                "videoedit" -> {
                    put("video_url", setup.videoUrl?.let(OssService::freshDownloadUrl) ?: "")
                    if (setup.referenceImages.isNotEmpty()) put("ref_images_url", buildJsonArray {
                        setup.referenceImages.take(4).forEach { add(JsonPrimitive(OssService.freshDownloadUrl(it))) }
                    })
                    val imageUrl = setup.imageUrl
                    if (!imageUrl.isNullOrBlank()) put("img_url", OssService.freshDownloadUrl(imageUrl))
                }
            }
        }
        putJsonObject("parameters") {
            // T2V, R2V and video-edit need an explicit output size; only I2V infers it from its
            // first-frame image. R2V without a size fails with "'NoneType' object has no
            // attribute 'resolution'".
            if (modelKind != "i2v") {
                put("size", setup.resolution.ifBlank { "1280*720" })
            }
            val dur = setup.durationSeconds.toInt()
            if (dur in 1..15) put("duration", dur)
        }
    }

    /**
     * Image generation. Text-to-image by default; when the setup carries a base image
     * ([app.moviestudio.GenerationSetup.imageUrl]) the image-edit (image-to-image) model is used
     * instead, repainting the base image according to the prompt (repose, restyle, etc.).
     */
    private suspend fun executeImage(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val baseImageUrl = setup.imageUrl?.takeIf { it.isNotBlank() }?.let(OssService::freshDownloadUrl)
        val model = if (baseImageUrl != null) QwenConfig.imageEditModel else QwenConfig.imageModel
        onProgress(15, if (baseImageUrl != null) "Submitting image edit task ($model)..." else "Submitting image task ($model)...")
        // The qwen-image family (both the plain and edit variants) is only exposed through the
        // chat-style multimodal-generation/generation endpoint - the legacy Wanx
        // aigc/text2image and aigc/image2image endpoints reject qwen-image-* model names with
        // HTTP 400 "url error, please check url！" (model name / API endpoint mismatch).
        val requestBody = buildImageRequestBody(setup, model)
        // Unlike the WAN video/audio models, the qwen-image family only supports synchronous
        // invocation: submitting with the `X-DashScope-Async` header set (as the shared
        // runAsyncGenerationTask helper does for every other media type) is rejected with
        // HTTP 403 "current user api does not support asynchronous calls". The image is
        // returned directly in this response, so no task polling is needed.
        onProgress(40, "Generating image ($model)...")
        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/aigc/multimodal-generation/generation",
            requestBody,
            async = false,
            timeoutSeconds = 180
        )
        val mediaUrl = response["output"]?.jsonObject
            ?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("image")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Model Studio image response has no media URL: $response")
        ledger.add(buildImageLedgerEntry("Generated image ($model)", model, response, setup.resolution))

        onProgress(85, "Uploading generated image to Alibaba OSS...")
        val extension = mediaUrl.substringBefore('?').substringAfterLast('.', "png").take(4)
        val (ossUrl, _) = rehost(mediaUrl, job.movieId, "image", extension, 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, 5.0, ledgerEntries = ledger)
    }

    internal fun buildImageRequestBody(setup: GenerationSetup, model: String): JsonObject = buildJsonObject {
        val baseImageUrl = setup.imageUrl?.takeIf { it.isNotBlank() }?.let(OssService::freshDownloadUrl)
        put("model", model)
        putJsonObject("input") {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (baseImageUrl != null) {
                            add(buildJsonObject { put("image", baseImageUrl) })
                        }
                        add(buildJsonObject { put("text", setup.prompt) })
                    })
                })
            })
        }
        putJsonObject("parameters") {
            put("n", 1)
            if (setup.negativePrompt.isNotBlank()) put("negative_prompt", setup.negativePrompt)
            put("size", setup.resolution.replace("x", "*").ifBlank { "1024*1024" })
        }
    }

    /**
     * Music generation via Fun-Music (`fun-music-v1`): a synchronous API accepting a theme
     * prompt, optional full lyrics and an instrumental switch, returning a 24h OSS URL that we
     * immediately re-host on our own bucket.
     */
    private suspend fun executeMusic(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(20, "Composing music with ${QwenConfig.musicModel}...")
        val requestBody = buildMusicRequestBody(setup, QwenConfig.musicModel)
        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/audio/music/generation",
            requestBody,
            async = false,
            timeoutSeconds = 300
        )
        val audio = response["output"]?.jsonObject?.get("audio")?.jsonObject
        val mediaUrl = audio?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Fun-Music response missing output.audio.url: $response")
        ledger.recordCall("Composed music (${QwenConfig.musicModel})", QwenConfig.musicModel, response)

        onProgress(80, "Uploading generated music to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "music", "mp3", 30.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
    }

    /**
     * Builds the Fun-Music (`fun-music-v1`) request body. Pure and network-free so its `input`
     * shape can be unit-tested (see `QwenMusicRequestTest`).
     *
     * The theme (falling back to the plain prompt) becomes `input.prompt`; full lyrics ride along
     * as `input.lyrics` and the preferred vocal `gender` ("female"/"male") as `input.gender` -
     * both only when the track has vocals ([GenerationSetup.instrumental] is false). A blank
     * gender is omitted so the model picks the voice itself.
     */
    internal fun buildMusicRequestBody(setup: GenerationSetup, model: String): JsonObject = buildJsonObject {
        put("model", model)
        putJsonObject("input") {
            val theme = setup.theme.ifBlank { setup.prompt }
            if (theme.isNotBlank()) put("prompt", theme)
            if (!setup.instrumental) {
                if (setup.lyric.isNotBlank()) put("lyrics", setup.lyric)
                if (setup.gender.isNotBlank()) put("gender", setup.gender)
            }
            put("instrumental", setup.instrumental)
        }
    }

    /**
     * Qwen TTS with a preset or cloned voice. The input text becomes the stored transcript.
     *
     * Two distinct backends are used depending on the voice:
     * - Preset voices (and the instruction-following variant when the setup carries voice
     *   instructions like "happy"/"sad"/"excited") use the qwen-tts family on the synchronous
     *   `multimodal-generation/generation` endpoint, with the instructions riding along as the
     *   `instruct` input.
     * - Cloned voices are enrolled against the CosyVoice family ([QwenConfig.voiceCloneTargetModel]),
     *   which is NOT exposed through `multimodal-generation/generation` - that endpoint rejects
     *   CosyVoice model names with HTTP 400 "url error, please check url！" (the same model
     *   name / API endpoint mismatch documented for the qwen-image family in [executeImage]).
     *   They are synthesized through the DashScope speech-synthesis endpoint instead
     *   (see [executeClonedVoiceTts]).
     */
    private suspend fun executeTts(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val voice = setup.voice.ifBlank { "Cherry" }
        val isClonedVoice = VoiceCloneRepository.listAll().any { it.qwenVoiceId == voice }
        if (isClonedVoice) {
            executeClonedVoiceTts(job, payload, voice, ledger, onProgress)
            return
        }

        val instructions = setup.instructions.trim()
        val model = if (instructions.isNotBlank()) QwenConfig.ttsInstructModel else QwenConfig.ttsModel
        onProgress(20, "Synthesizing speech with $model (voice: $voice)...")

        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/aigc/multimodal-generation/generation",
            buildTtsRequestBody(setup, voice, model, instructions),
            async = false,
            timeoutSeconds = 180
        )
        val output = response["output"]?.jsonObject
        val mediaUrl = output?.get("audio")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: extractMediaUrl(output ?: buildJsonObject {}, listOf("audio_url", "url"))
            ?: throw IllegalStateException("Qwen TTS response missing audio url: $response")
        ledger.recordCall("Synthesized speech ($model)", model, response)

        onProgress(75, "Uploading voiceover to Alibaba OSS...")
        finalizeVoiceover(job, payload, setup, mediaUrl, ledger, onProgress)
    }

    /**
     * Cloned-voice (Qwen voice replication) speech synthesis. The enrolled voice targets the
     * CosyVoice family ([QwenConfig.voiceCloneTargetModel]), whose synthesis is asynchronous and
     * lives under the DashScope `audio/tts` namespace (the sibling of the `audio/tts/customization`
     * enrollment endpoint used by [createVoiceClone]) - not the qwen-tts
     * `multimodal-generation/generation` endpoint. The enrolled `voice_id` rides in `input.voice`.
     */
    private suspend fun executeClonedVoiceTts(
        job: Job,
        payload: AiJobPayload,
        voice: String,
        ledger: MutableList<AiLedgerEntry>,
        onProgress: suspend (Int, String) -> Unit
    ) {
        val setup = payload.setup
        val model = QwenConfig.voiceCloneTargetModel
        onProgress(20, "Synthesizing speech with cloned voice ($model)...")

        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/audio/tts/generation",
            requestBody = buildClonedVoiceTtsRequestBody(setup, voice, model),
            mediaUrlKeys = listOf("audio_url", "url"),
            ledger = ledger,
            ledgerModel = model,
            ledgerDescription = "Synthesized speech ($model)",
        ) { pct -> onProgress((20 + pct * 0.5).toInt().coerceIn(20, 70), "Synthesizing speech... $pct%") }

        onProgress(75, "Uploading voiceover to Alibaba OSS...")
        finalizeVoiceover(job, payload, setup, mediaUrl, ledger, onProgress)
    }

    /** Re-hosts the synthesized audio, derives transcript timings and finalizes the voice asset. */
    private suspend fun finalizeVoiceover(
        job: Job,
        payload: AiJobPayload,
        setup: GenerationSetup,
        mediaUrl: String,
        ledger: MutableList<AiLedgerEntry>,
        onProgress: suspend (Int, String) -> Unit
    ) {
        val fallback = (setup.prompt.split(Regex("\\s+")).count { it.isNotBlank() } * 0.42).coerceAtLeast(2.0)
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "voice", "mp3", fallback)

        onProgress(88, "Building transcript timings...")
        val transcript = setup.prompt
        val timings = TranscriptUtil.buildWordTimings(transcript, duration)
        GenerationCommon.finalize(job, payload, ossUrl, duration, transcript, timings, ledgerEntries = ledger)
    }

    /**
     * Builds the qwen-tts request body for a preset voice. When [instructions] are supplied they
     * ride along as the `instruct` input (used with [QwenConfig.ttsInstructModel]). Pure and
     * network-free so its `input` shape can be unit-tested.
     */
    internal fun buildTtsRequestBody(setup: GenerationSetup, voice: String, model: String, instructions: String): JsonObject =
        buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("text", setup.prompt)
                put("voice", voice)
                if (instructions.isNotBlank()) put("instruct", instructions)
            }
        }

    /**
     * Builds the CosyVoice (cloned-voice) speech-synthesis request body. The enrolled `voice_id`
     * rides in `input.voice`; CosyVoice does not support the qwen-tts `instruct` steering input,
     * so voice instructions are intentionally omitted here. Pure and network-free so its shape can
     * be unit-tested.
     */
    internal fun buildClonedVoiceTtsRequestBody(setup: GenerationSetup, voice: String, model: String): JsonObject =
        buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("text", setup.prompt)
                put("voice", voice)
            }
            putJsonObject("parameters") {
                put("format", "mp3")
            }
        }

    /**
     * Sound-effect generation. The mode is picked by [GenerationSetup.sfxModel], both DashScope
     * modes backed by the same verified [QwenConfig.audioModel] (`fun-audiogen-v1`):
     * - "fun-audiogen": synthesizes the audio directly from the text prompt.
     * - "fun-audiogen-vd": video-driven, scores a freshly generated WAN source video with
     *   audio matching its visuals.
     * - anything else (default "wan"): the legacy WAN + ffmpeg extraction pipeline.
     */
    private suspend fun executeSoundEffect(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        when (payload.setup.sfxModel) {
            "fun-audiogen" -> executeSoundEffectAudioGen(job, payload, ledger, onProgress)
            "fun-audiogen-vd" -> executeSoundEffectAudioGenVd(job, payload, ledger, onProgress)
            else -> executeSoundEffectWan(job, payload, ledger, onProgress)
        }
    }

    /** Direct text-to-audio: synthesizes the sound effect straight from the text prompt. */
    private suspend fun executeSoundEffectAudioGen(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(15, "Generating sound effect with ${QwenConfig.audioModel}...")
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/audio-generation/audio-synthesis",
            requestBody = audioGenRequestBody(QwenConfig.audioModel, setup, videoUrl = null),
            mediaUrlKeys = listOf("audio_url", "url"),
            ledger = ledger,
            ledgerModel = QwenConfig.audioModel,
            ledgerDescription = "Generated sound effect (${QwenConfig.audioModel})",
        ) { pct -> onProgress((20 + pct * 0.6).toInt().coerceIn(20, 80), "Generating sound effect... $pct%") }

        onProgress(85, "Uploading sound effect to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "sfx", "mp3", setup.durationSeconds.takeIf { it > 0 } ?: 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
    }

    /**
     * Video-driven: generates a short WAN source video for the prompt, then has the audio model
     * score it with audio matching its visuals.
     */
    private suspend fun executeSoundEffectAudioGenVd(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(12, "Generating source video for sound effect...")
        val videoUrl = generateSfxSourceVideo(setup, ledger) { pct ->
            onProgress((15 + pct * 0.35).toInt().coerceIn(15, 50), "Generating sound source... $pct%")
        }

        onProgress(55, "Scoring source video with ${QwenConfig.audioModel}...")
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/audio-generation/audio-synthesis",
            requestBody = audioGenRequestBody(QwenConfig.audioModel, setup, videoUrl = videoUrl),
            mediaUrlKeys = listOf("audio_url", "url"),
            ledger = ledger,
            ledgerModel = QwenConfig.audioModel,
            ledgerDescription = "Generated sound effect (${QwenConfig.audioModel})",
        ) { pct -> onProgress((55 + pct * 0.25).toInt().coerceIn(55, 80), "Generating sound effect... $pct%") }

        onProgress(85, "Uploading sound effect to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "sfx", "mp3", setup.durationSeconds.takeIf { it > 0 } ?: 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
    }

    /** The DashScope request body shared by the text-to-audio and video-driven sound-effect modes. */
    private fun audioGenRequestBody(model: String, setup: GenerationSetup, videoUrl: String?) = buildJsonObject {
        put("model", model)
        putJsonObject("input") {
            put("prompt", setup.prompt)
            if (videoUrl != null) put("video_url", videoUrl)
        }
        putJsonObject("parameters") {
            val dur = setup.durationSeconds.toInt()
            if (dur in 1..30) put("duration", dur)
        }
    }

    /** Generates the short WAN source video used by the sound-effect pipelines. */
    private suspend fun generateSfxSourceVideo(setup: GenerationSetup, ledger: MutableList<AiLedgerEntry>, onPollProgress: suspend (Int) -> Unit): String {
        val requestBody = buildJsonObject {
            put("model", QwenConfig.videoModelT2V)
            putJsonObject("input") {
                put("prompt", "${setup.prompt}. Rich, clear sound design; the audio is the star.")
            }
            putJsonObject("parameters") {
                put("size", "1280*720")
                val dur = setup.durationSeconds.toInt()
                if (dur in 1..15) put("duration", dur)
            }
        }
        return runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/video-generation/video-synthesis",
            requestBody = requestBody,
            mediaUrlKeys = listOf("video_url", "url"),
            ledger = ledger,
            ledgerModel = QwenConfig.videoModelT2V,
            ledgerDescription = "Generated sound-effect source video (${QwenConfig.videoModelT2V})",
            onPollProgress = onPollProgress,
        )
    }

    /**
     * Legacy WAN sound-effect pipeline: generate a short WAN video for the prompt, then extract
     * its audio track with ffmpeg. Falls back to the raw video file when extraction is
     * unavailable (browsers and the renderer can both play the audio track of an mp4).
     */
    private suspend fun executeSoundEffectWan(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(12, "Generating source video for sound effect...")
        val mediaUrl = generateSfxSourceVideo(setup, ledger) { pct ->
            onProgress((15 + pct * 0.5).toInt().coerceIn(15, 65), "Generating sound source... $pct%")
        }

        onProgress(70, "Extracting audio track with ffmpeg...")
        val videoFile = MediaUtil.downloadToTemp(mediaUrl, ".mp4")
        try {
            val audioFile = MediaUtil.extractAudio(videoFile)
            val (uploadFile, extension) = if (audioFile != null) audioFile to "mp3" else videoFile to "mp4"
            val duration = MediaUtil.probeDurationSeconds(uploadFile)
                ?: setup.durationSeconds.takeIf { it > 0 } ?: 5.0
            onProgress(85, "Uploading sound effect to Alibaba OSS...")
            val objectKey = "ai-generated/${job.movieId}/sfx-${UUID.randomUUID()}.$extension"
            val ossUrl = OssService.uploadFile(objectKey, uploadFile)
            GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
            runCatching { audioFile?.delete() }
        } finally {
            runCatching { videoFile.delete() }
        }
    }

    /** Extracts the audio track from an existing media URL into a new sound asset. */
    private suspend fun executeExtractAudio(job: Job, payload: AiJobPayload, ledger: MutableList<AiLedgerEntry>, onProgress: suspend (Int, String) -> Unit) {
        val sourceUrl = payload.sourceUrl
            ?: throw IllegalStateException("extract-audio job ${job.id} missing sourceUrl")
        onProgress(20, "Downloading source media...")
        val extensionGuess = sourceUrl.substringBefore('?').substringAfterLast('.', "mp4").take(4)
        val sourceFile = MediaUtil.downloadToTemp(sourceUrl, ".$extensionGuess")
        try {
            onProgress(50, "Extracting audio track with ffmpeg...")
            val audioFile = MediaUtil.extractAudio(sourceFile)
            if (audioFile != null) {
                val duration = MediaUtil.probeDurationSeconds(audioFile) ?: 5.0
                onProgress(80, "Uploading extracted audio to Alibaba OSS...")
                val objectKey = "ai-generated/${job.movieId}/audio-${UUID.randomUUID()}.mp3"
                val ossUrl = OssService.uploadFile(objectKey, audioFile)
                GenerationCommon.finalize(job, payload, ossUrl, duration, ledgerEntries = ledger)
                runCatching { audioFile.delete() }
            } else {
                // No extraction available: reference the source directly (players read its audio track).
                val duration = MediaUtil.probeDurationSeconds(sourceFile) ?: 5.0
                GenerationCommon.finalize(job, payload, sourceUrl, duration, ledgerEntries = ledger)
            }
        } finally {
            runCatching { sourceFile.delete() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Transcription
    // ------------------------------------------------------------------------------------------

    override suspend fun generateTranscript(asset: Asset): Asset {
        val ledger = mutableListOf<AiLedgerEntry>()
        val (text, timings) = try {
            transcribe(asset.ossUrl, asset.durationSeconds, ledger)
        } catch (e: Exception) {
            logger.warn("Qwen transcription failed for asset ${asset.id}, falling back to prompt text: ${e.message}")
            val fallback = asset.transcript?.takeIf { it.isNotBlank() }
                ?: asset.aiPrompt?.takeIf { it.isNotBlank() }
                ?: ""
            fallback to TranscriptUtil.buildWordTimings(fallback, asset.durationSeconds)
        }
        val updated = asset.copy(transcript = text, wordTimings = timings, ledger = asset.ledger + ledger)
        AssetRepository.update(updated)
        logger.info("Generated transcript for asset ${asset.id} (${timings.size} words)")
        return updated
    }

    /**
     * Best-effort speech-to-text via the DashScope async ASR (paraformer) endpoint. Returns the
     * transcript text and per-word timings extracted from the recognizer output. Any failure is
     * surfaced to the caller, which falls back to a prompt-derived transcript.
     */
    private suspend fun transcribe(audioUrl: String, durationSeconds: Double, ledger: MutableList<AiLedgerEntry>): Pair<String, List<WordTiming>> {
        val transcriptionUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/audio/asr/transcription",
            requestBody = buildJsonObject {
                put("model", QwenConfig.transcriptionModel)
                putJsonObject("input") {
                    put("file_urls", buildJsonArray { add(JsonPrimitive(audioUrl)) })
                }
            },
            mediaUrlKeys = listOf("transcription_url", "url"),
            ledger = ledger,
            ledgerModel = QwenConfig.transcriptionModel,
            ledgerDescription = "Auto-transcribed voiceover (${QwenConfig.transcriptionModel})",
        ) { /* no-op progress */ }

        // The transcription document lives on a plain (pre-signed) URL: no auth headers needed.
        val resultJson = httpClient.get(transcriptionUrl).bodyAsText()
        return parseTranscription(resultJson, durationSeconds)
    }

    /**
     * Parses a paraformer transcription document into transcript text and [WordTiming]s. Uses the
     * recognizer's own millisecond timestamps when available, otherwise evenly distributes words.
     */
    private fun parseTranscription(rawJson: String, durationSeconds: Double): Pair<String, List<WordTiming>> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val transcripts = root["transcripts"]?.jsonArray
        val firstTranscript = transcripts?.firstOrNull()?.jsonObject
        val text = firstTranscript?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val timings = mutableListOf<WordTiming>()
        firstTranscript?.get("sentences")?.jsonArray?.forEach { sentence ->
            sentence.jsonObject["words"]?.jsonArray?.forEach { wordEl ->
                val obj = wordEl.jsonObject
                val word = (obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["word"]?.jsonPrimitive?.contentOrNull)?.trim()
                val beginMs = obj["begin_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val endMs = obj["end_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (!word.isNullOrBlank() && beginMs != null && endMs != null) {
                    timings.add(WordTiming(word, beginMs / 1000.0, endMs / 1000.0))
                }
            }
        }

        val resolvedText = if (text.isNotBlank()) text else timings.joinToString(" ") { it.word }
        if (resolvedText.isBlank()) {
            throw IllegalStateException("Transcription document had no usable text")
        }
        val resolvedTimings = timings.ifEmpty { TranscriptUtil.buildWordTimings(resolvedText, durationSeconds) }
        return resolvedText to resolvedTimings
    }

    // ------------------------------------------------------------------------------------------
    // DashScope plumbing
    // ------------------------------------------------------------------------------------------

    /**
     * Submits an asynchronous DashScope generation task and polls until it succeeds,
     * returning the generated media URL.
     */
    private suspend fun runAsyncGenerationTask(
        submitUrl: String,
        requestBody: JsonObject,
        mediaUrlKeys: List<String>,
        // When supplied, the succeeded task's token usage is recorded to this per-job ledger.
        ledger: MutableList<AiLedgerEntry>? = null,
        ledgerModel: String? = null,
        ledgerDescription: String? = null,
        onPollProgress: suspend (percent: Int) -> Unit,
    ): String {
        val submitResponse = postJson(submitUrl, requestBody, async = true)
        val output = submitResponse["output"]?.jsonObject
            ?: throw IllegalStateException("Model Studio submit response missing 'output': $submitResponse")
        val taskId = output["task_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Model Studio submit response missing 'task_id': $submitResponse")

        logger.info("Submitted Model Studio task {}", taskId)

        val deadline = System.currentTimeMillis() + QwenConfig.pollTimeoutMs
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("Model Studio task $taskId timed out after ${QwenConfig.pollTimeoutMs}ms")
            }
            delay(QwenConfig.pollIntervalMs)

            val taskResponse = getJson("${QwenConfig.dashScopeBaseUrl}/tasks/$taskId")
            val taskOutput = taskResponse["output"]?.jsonObject ?: continue
            when (taskOutput["task_status"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
                "SUCCEEDED" -> {
                    val url = extractMediaUrl(taskOutput, mediaUrlKeys)
                        ?: throw IllegalStateException("Succeeded task $taskId has no media URL: $taskResponse")
                    if (ledger != null && ledgerModel != null) {
                        ledger.recordCall(ledgerDescription ?: "AI generation", ledgerModel, taskResponse)
                    }
                    onPollProgress(100)
                    return url
                }
                "FAILED", "CANCELED", "UNKNOWN" -> {
                    val message = taskOutput["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
                    throw IllegalStateException("Model Studio task $taskId failed: $message")
                }
                else -> {
                    // PENDING / RUNNING: report indeterminate progress.
                    onPollProgress(50)
                }
            }
        }
    }

    /** Pulls a media URL out of a DashScope task output, checking direct keys and a `results` array. */
    private fun extractMediaUrl(output: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            output[key]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        }
        val results = output["results"]?.jsonArray ?: return null
        for (element in results) {
            val obj = element.jsonObject
            for (key in keys) {
                obj[key]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
            }
        }
        return null
    }

    /**
     * Downloads generated media, probes its real duration with ffprobe and re-hosts it on our
     * OSS bucket. Returns the public URL and the resolved duration.
     */
    private suspend fun rehost(
        mediaUrl: String,
        movieId: String,
        kind: String,
        extension: String,
        fallbackDuration: Double
    ): Pair<String, Double> {
        val tempFile: File = MediaUtil.downloadToTemp(mediaUrl, ".$extension")
        try {
            val duration = MediaUtil.probeDurationSeconds(tempFile) ?: fallbackDuration
            val objectKey = "ai-generated/$movieId/${kind}-${UUID.randomUUID()}.$extension"
            return OssService.uploadFile(objectKey, tempFile) to duration
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    /**
     * Offline stand-in for [generateText] used when no Model Studio credentials are configured:
     * deterministic responses shaped for the known callers (skeleton planning, lyrics, themes).
     */
    private fun offlineGenerateText(system: String, user: String): String {
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
                  {"trackType": "MUSIC", "assetType": "MUSIC", "description": "Warm, uplifting underscore", "startSeconds": $at, "durationSeconds": 11},
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
            else -> "Offline response: ${user.take(160)}"
        }
    }

    private suspend fun postJson(url: String, body: JsonObject, async: Boolean, timeoutSeconds: Long = 240): JsonObject {
        val response = httpClient.post(url) {
            header("Authorization", "Bearer ${QwenConfig.apiKey}")
            if (async) header("X-DashScope-Async", "enable")
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = timeoutSeconds * 1000 }
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        return parseJsonResponse(response)
    }

    private suspend fun getJson(url: String): JsonObject {
        val response = httpClient.get(url) {
            header("Authorization", "Bearer ${QwenConfig.apiKey}")
            timeout { requestTimeoutMillis = 240_000 }
        }
        return parseJsonResponse(response)
    }

    private suspend fun parseJsonResponse(response: HttpResponse): JsonObject {
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Model Studio request to ${response.request.url} failed with HTTP ${response.status.value}: $bodyText"
            )
        }
        return json.parseToJsonElement(bodyText).jsonObject
    }
}
