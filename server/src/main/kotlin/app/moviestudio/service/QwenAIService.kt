package app.moviestudio.service

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

/**
 * Production implementation of [AIGenerationService] backed by the Alibaba Model Studio
 * (Qwen / DashScope) APIs.
 *
 * Supported generation tasks (all executed asynchronously through the job queue):
 * - video: WAN 2.7 family — T2V (prompt only), I2V (first-frame image), R2V (reference images).
 * - image: text-to-image, or image-to-image editing when a base image is attached.
 * - music: Fun-Music (`fun-music-preview`) with lyrics/theme/instrumental options.
 * - tts:   Qwen TTS with preset or cloned voices; transcripts + word timings are stored.
 * - sfx:   WAN video generation followed by ffmpeg audio extraction (sound-effects pipeline).
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

    override suspend fun generateText(system: String, user: String): String {
        // Graceful degradation: without Model Studio credentials, answer offline with
        // deterministic canned responses so planning/lyrics/themes keep working in dev.
        if (!QwenConfig.isConfigured) return offlineGenerateText(system, user)
        val body = buildJsonObject {
            put("model", QwenConfig.chatModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
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
        return content.trim()
    }

    /**
     * Best-effort prompt enrichment via the OpenAI-compatible Qwen chat endpoint.
     * Returns the original prompt if the call fails so generation can still proceed.
     */
    private suspend fun refinePrompt(prompt: String, mediaKind: String): String {
        if (prompt.isBlank()) return prompt
        return try {
            generateText(
                system = "You expand short prompts into a single vivid, concise $mediaKind generation prompt. " +
                    "Respond with only the improved prompt, no preamble.",
                user = prompt
            )
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

        when (setup.kind) {
            "music" -> executeMusic(job, payload, onProgress)
            "tts" -> executeTts(job, payload, onProgress)
            "sfx" -> executeSoundEffect(job, payload, onProgress)
            "extract-audio" -> executeExtractAudio(job, payload, onProgress)
            "image" -> executeImage(job, payload, onProgress)
            else -> executeVideo(job, payload, onProgress)
        }
        onProgress(100, "Generation completed")
    }

    /** WAN 2.7 video generation: model picked predictably by the setup (T2V / I2V / R2V). */
    private suspend fun executeVideo(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val modelKind = setup.resolveVideoModelKind()
        val model = when (modelKind) {
            "i2v" -> QwenConfig.videoModelI2V
            "r2v" -> QwenConfig.videoModelR2V
            else -> QwenConfig.videoModelT2V
        }

        onProgress(12, "Refining prompt with Qwen...")
        val refinedPrompt = refinePrompt(setup.prompt, "video")

        onProgress(20, "Submitting $modelKind task ($model)...")
        val requestBody = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("prompt", refinedPrompt)
                if (setup.negativePrompt.isNotBlank()) put("negative_prompt", setup.negativePrompt)
                when (modelKind) {
                    "i2v" -> put("img_url", setup.imageUrl ?: "")
                    "r2v" -> put("ref_images_url", buildJsonArray {
                        setup.referenceImages.take(4).forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
            putJsonObject("parameters") {
                if (modelKind == "t2v") put("size", setup.resolution.ifBlank { "1280*720" })
                val dur = setup.durationSeconds.toInt()
                if (dur in 1..15) put("duration", dur)
            }
        }
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/video-generation/video-synthesis",
            requestBody = requestBody,
            mediaUrlKeys = listOf("video_url", "url"),
        ) { pct -> onProgress((20 + pct * 0.6).toInt().coerceIn(20, 80), "Generating video... $pct%") }

        onProgress(85, "Uploading generated video to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "video", "mp4", setup.durationSeconds.takeIf { it > 0 } ?: 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration)
    }

    /**
     * Image generation. Text-to-image by default; when the setup carries a base image
     * ([app.moviestudio.GenerationSetup.imageUrl]) the image-edit (image-to-image) model is used
     * instead, repainting the base image according to the prompt (repose, restyle, etc.).
     */
    private suspend fun executeImage(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val baseImageUrl = setup.imageUrl?.takeIf { it.isNotBlank() }
        val (submitUrl, requestBody) = if (baseImageUrl != null) {
            onProgress(15, "Submitting image edit task (${QwenConfig.imageEditModel})...")
            "${QwenConfig.dashScopeBaseUrl}/services/aigc/image2image/image-synthesis" to buildJsonObject {
                put("model", QwenConfig.imageEditModel)
                putJsonObject("input") {
                    put("function", "description_edit")
                    put("prompt", setup.prompt)
                    put("base_image_url", baseImageUrl)
                    if (setup.negativePrompt.isNotBlank()) put("negative_prompt", setup.negativePrompt)
                }
                putJsonObject("parameters") {
                    put("n", 1)
                }
            }
        } else {
            onProgress(15, "Submitting image task (${QwenConfig.imageModel})...")
            "${QwenConfig.dashScopeBaseUrl}/services/aigc/text2image/image-synthesis" to buildJsonObject {
                put("model", QwenConfig.imageModel)
                putJsonObject("input") {
                    put("prompt", setup.prompt)
                    if (setup.negativePrompt.isNotBlank()) put("negative_prompt", setup.negativePrompt)
                }
                putJsonObject("parameters") {
                    put("n", 1)
                    put("size", setup.resolution.replace("x", "*").ifBlank { "1024*1024" })
                }
            }
        }
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = submitUrl,
            requestBody = requestBody,
            mediaUrlKeys = listOf("url", "img_url"),
        ) { pct -> onProgress((20 + pct * 0.6).toInt().coerceIn(20, 80), "Generating image... $pct%") }

        onProgress(85, "Uploading generated image to Alibaba OSS...")
        val extension = mediaUrl.substringBefore('?').substringAfterLast('.', "png").take(4)
        val (ossUrl, _) = rehost(mediaUrl, job.movieId, "image", extension, 5.0)
        GenerationCommon.finalize(job, payload, ossUrl, 5.0)
    }

    /**
     * Music generation via Fun-Music (`fun-music-preview`): a synchronous API accepting a theme
     * prompt, optional full lyrics and an instrumental switch, returning a 24h OSS URL that we
     * immediately re-host on our own bucket.
     */
    private suspend fun executeMusic(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(20, "Composing music with ${QwenConfig.musicModel}...")
        val requestBody = buildJsonObject {
            put("model", QwenConfig.musicModel)
            putJsonObject("input") {
                val theme = setup.theme.ifBlank { setup.prompt }
                if (theme.isNotBlank()) put("prompt", theme)
                if (setup.lyric.isNotBlank() && !setup.instrumental) put("lyrics", setup.lyric)
                put("instrumental", setup.instrumental)
            }
        }
        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/audio/music/generation",
            requestBody,
            async = false,
            timeoutSeconds = 300
        )
        val audio = response["output"]?.jsonObject?.get("audio")?.jsonObject
        val mediaUrl = audio?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Fun-Music response missing output.audio.url: $response")

        onProgress(80, "Uploading generated music to Alibaba OSS...")
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "music", "mp3", 30.0)
        GenerationCommon.finalize(job, payload, ossUrl, duration)
    }

    /** Qwen TTS with a preset or cloned voice. The input text becomes the stored transcript. */
    private suspend fun executeTts(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        val voice = setup.voice.ifBlank { "Cherry" }
        val isClonedVoice = VoiceCloneRepository.listAll().any { it.qwenVoiceId == voice }
        val model = if (isClonedVoice) QwenConfig.voiceCloneTargetModel else QwenConfig.ttsModel
        onProgress(20, "Synthesizing speech with $model (voice: $voice)...")

        val requestBody = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("text", setup.prompt)
                put("voice", voice)
            }
        }
        val response = postJson(
            "${QwenConfig.dashScopeBaseUrl}/services/aigc/multimodal-generation/generation",
            requestBody,
            async = false,
            timeoutSeconds = 180
        )
        val output = response["output"]?.jsonObject
        val mediaUrl = output?.get("audio")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: extractMediaUrl(output ?: buildJsonObject {}, listOf("audio_url", "url"))
            ?: throw IllegalStateException("Qwen TTS response missing audio url: $response")

        onProgress(75, "Uploading voiceover to Alibaba OSS...")
        val fallback = (setup.prompt.split(Regex("\\s+")).count { it.isNotBlank() } * 0.42).coerceAtLeast(2.0)
        val (ossUrl, duration) = rehost(mediaUrl, job.movieId, "voice", "mp3", fallback)

        onProgress(88, "Building transcript timings...")
        val transcript = setup.prompt
        val timings = TranscriptUtil.buildWordTimings(transcript, duration)
        GenerationCommon.finalize(job, payload, ossUrl, duration, transcript, timings)
    }

    /**
     * Sound-effect pipeline: generate a short WAN video for the prompt, then extract its audio
     * track with ffmpeg. Falls back to the raw video file when extraction is unavailable
     * (browsers and the renderer can both play the audio track of an mp4).
     */
    private suspend fun executeSoundEffect(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
        val setup = payload.setup
        onProgress(12, "Generating source video for sound effect...")
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
        val mediaUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/aigc/video-generation/video-synthesis",
            requestBody = requestBody,
            mediaUrlKeys = listOf("video_url", "url"),
        ) { pct -> onProgress((15 + pct * 0.5).toInt().coerceIn(15, 65), "Generating sound source... $pct%") }

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
            GenerationCommon.finalize(job, payload, ossUrl, duration)
            runCatching { audioFile?.delete() }
        } finally {
            runCatching { videoFile.delete() }
        }
    }

    /** Extracts the audio track from an existing media URL into a new sound asset. */
    private suspend fun executeExtractAudio(job: Job, payload: AiJobPayload, onProgress: suspend (Int, String) -> Unit) {
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
                GenerationCommon.finalize(job, payload, ossUrl, duration)
                runCatching { audioFile.delete() }
            } else {
                // No extraction available: reference the source directly (players read its audio track).
                val duration = MediaUtil.probeDurationSeconds(sourceFile) ?: 5.0
                GenerationCommon.finalize(job, payload, sourceUrl, duration)
            }
        } finally {
            runCatching { sourceFile.delete() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Transcription
    // ------------------------------------------------------------------------------------------

    override suspend fun generateTranscript(asset: Asset): Asset {
        val (text, timings) = try {
            transcribe(asset.ossUrl, asset.durationSeconds)
        } catch (e: Exception) {
            logger.warn("Qwen transcription failed for asset ${asset.id}, falling back to prompt text: ${e.message}")
            val fallback = asset.transcript?.takeIf { it.isNotBlank() }
                ?: asset.aiPrompt?.takeIf { it.isNotBlank() }
                ?: "Auto-generated voiceover transcript."
            fallback to TranscriptUtil.buildWordTimings(fallback, asset.durationSeconds)
        }
        val updated = asset.copy(transcript = text, wordTimings = timings)
        AssetRepository.update(updated)
        logger.info("Generated transcript for asset ${asset.id} (${timings.size} words)")
        return updated
    }

    /**
     * Best-effort speech-to-text via the DashScope async ASR (paraformer) endpoint. Returns the
     * transcript text and per-word timings extracted from the recognizer output. Any failure is
     * surfaced to the caller, which falls back to a prompt-derived transcript.
     */
    private suspend fun transcribe(audioUrl: String, durationSeconds: Double): Pair<String, List<WordTiming>> {
        val transcriptionUrl = runAsyncGenerationTask(
            submitUrl = "${QwenConfig.dashScopeBaseUrl}/services/audio/asr/transcription",
            requestBody = buildJsonObject {
                put("model", QwenConfig.transcriptionModel)
                putJsonObject("input") {
                    put("file_urls", buildJsonArray { add(JsonPrimitive(audioUrl)) })
                }
            },
            mediaUrlKeys = listOf("transcription_url", "url"),
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

    private suspend fun postJson(url: String, body: JsonObject, async: Boolean, timeoutSeconds: Long = 60): JsonObject {
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
            timeout { requestTimeoutMillis = 60_000 }
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
