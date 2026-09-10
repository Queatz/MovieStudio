package app.moviestudio.service

import app.moviestudio.DEFAULT_IMAGE_MODEL_ID
import app.moviestudio.DEFAULT_VIDEO_MODEL_ID
import app.moviestudio.config.Env
import org.slf4j.LoggerFactory

/**
 * Configuration holder for the Alibaba Model Studio (Qwen) AI integration.
 *
 * All secrets live in the `.env` file (see [Env]), so no credentials are ever
 * checked into version control. Mock/placeholder defaults are used for the
 * host/endpoint values when they are absent.
 *
 * Expected `.env` entries:
 * - QWEN_API_KEY               Model Studio API key (sk-...).
 * - QWEN_API_HOST              Base API host, e.g. https://<workspace>.<region>.maas.aliyuncs.com
 * - QWEN_OPENAI_BASE_URL       OpenAI-compatible endpoint (<host>/compatible-mode/v1).
 * - QWEN_DASHSCOPE_BASE_URL    DashScope endpoint (<host>/api/v1).
 * - QWEN_CHAT_MODEL            LLM model used to refine prompts / plan skeletons (default qwen-plus).
 * - QWEN_VIDEO_MODEL           Unified WAN 3.0 video model (default [DEFAULT_VIDEO_MODEL_ID];
 *                              use wan3.0-video-prime for the faster sibling). T2V / I2V /
 *                              R2V / edit are media inputs on this one model.
 * - QWEN_VIDEO_MODEL_T2V       Optional override for prompt-only video (defaults to QWEN_VIDEO_MODEL).
 * - QWEN_VIDEO_MODEL_I2V       Optional override for image-to-video (defaults to QWEN_VIDEO_MODEL).
 * - QWEN_VIDEO_MODEL_R2V       Optional override for reference-to-video (defaults to QWEN_VIDEO_MODEL).
 * - QWEN_VIDEO_MODEL_EDIT      Optional override for video-edit (defaults to QWEN_VIDEO_MODEL).
 * - QWEN_IMAGE_MODEL           Text-to-image model (default [DEFAULT_IMAGE_MODEL_ID]).
 * - QWEN_IMAGE_EDIT_MODEL      Image-to-image editing model. Qwen Image is a single unified model
 *                              for both text-to-image and image-editing (unlike the older "max"
 *                              tier, there is no separate "-edit-" model name), so this also
 *                              defaults to [DEFAULT_IMAGE_MODEL_ID].
 * - QWEN_MUSIC_MODEL           Music generation model (default fun-music-v1).
 * - QWEN_TTS_MODEL             Text-to-speech model (default qwen-tts).
 * - QWEN_TTS_INSTRUCT_MODEL    Instruction-following TTS model used when voice instructions
 *                              (e.g. "happy", "sad", "excited") are supplied (default
 *                              qwen3-tts-instruct).
 * - QWEN_VOICE_ENROLL_MODEL    Voice cloning/enrollment model (default voice-enrollment).
 * - QWEN_VOICE_CLONE_TARGET    TTS model cloned voices target (default cosyvoice-v3.5-plus).
 * - QWEN_AUDIO_MODEL           Sound-effect model (Alibaba ThinkSound), used both text-to-audio
 *                              and video-driven (default thinksound-v1); served by the
 *                              audio-generation/audio-synthesis endpoint.
 * - QWEN_POLL_INTERVAL_MS      Async task poll interval in ms (default 3000).
 * - QWEN_POLL_TIMEOUT_MS       Async task max wait in ms (default 300000).
 * - QWEN_IMAGE_USD_PER_CALL    Flat USD price per generated/edited image, since Qwen-Image bills
 *                              per image rather than per token (default 0.05).
 */
object QwenConfig {
    private val logger = LoggerFactory.getLogger(QwenConfig::class.java)

    val apiKey: String = Env.get("QWEN_API_KEY", "")
    val apiHost: String = Env.get("QWEN_API_HOST", "https://mock-host.maas.aliyuncs.com")
    val openAiBaseUrl: String = Env.get("QWEN_OPENAI_BASE_URL", "$apiHost/compatible-mode/v1")
    val dashScopeBaseUrl: String = Env.get("QWEN_DASHSCOPE_BASE_URL", "$apiHost/api/v1")

    val chatModel: String = Env.get("QWEN_CHAT_MODEL", "qwen-plus")

    // Unified Wan 3.0 video model. T2V / I2V / R2V / edit are different `input.media` shapes on
    // the same id; optional per-kind env vars still exist for overrides.
    val videoModel: String = Env.get("QWEN_VIDEO_MODEL", DEFAULT_VIDEO_MODEL_ID)
    val videoModelT2V: String = Env.get("QWEN_VIDEO_MODEL_T2V", videoModel)
    val videoModelI2V: String = Env.get("QWEN_VIDEO_MODEL_I2V", videoModel)
    val videoModelR2V: String = Env.get("QWEN_VIDEO_MODEL_R2V", videoModel)
    val videoModelEdit: String = Env.get("QWEN_VIDEO_MODEL_EDIT", videoModel)

    // Default model id comes from core [DEFAULT_IMAGE_MODEL_ID] (SSOT); env can still override.
    val imageModel: String = Env.get("QWEN_IMAGE_MODEL", DEFAULT_IMAGE_MODEL_ID)
    // Qwen Image is a single unified model used for both text-to-image and image-editing
    // (the API selects the behavior based on whether an image is attached to the request), so
    // this defaults to the same model name as [imageModel] rather than a distinct "-edit-" model.
    val imageEditModel: String = Env.get("QWEN_IMAGE_EDIT_MODEL", DEFAULT_IMAGE_MODEL_ID)
    val musicModel: String = Env.get("QWEN_MUSIC_MODEL", "fun-music-v1")
    val ttsModel: String = Env.get("QWEN_TTS_MODEL", "qwen-audio-3.0-tts-plus")
    // Instruction-following TTS (Qwen instruct): selected when the generation setup carries voice
    // instructions describing how the line should be delivered (e.g. "happy", "sad", "excited").
    val ttsInstructModel: String = Env.get("QWEN_TTS_INSTRUCT_MODEL", "qwen-audio-3.0-tts-plus")
    val voiceEnrollModel: String = Env.get("QWEN_VOICE_ENROLL_MODEL", "voice-enrollment")
    val voiceCloneTargetModel: String = Env.get("QWEN_VOICE_CLONE_TARGET", "cosyvoice-v3.5-plus")

    // Sound-effect model (Alibaba ThinkSound) backing the audio-generation/audio-synthesis
    // endpoint, for both the direct text-to-audio and video-driven (GenerationSetup.sfxModel ==
    // "fun-audiogen" / "fun-audiogen-vd") pipelines; the two modes differ only in whether a
    // video_url is attached. Note: "fun-audiogen"/"fun-audiogen-vd" are the UI mode selectors, not
    // the DashScope model id, which is thinksound-v1.
    val audioModel: String = Env.get("QWEN_AUDIO_MODEL", "thinksound-v1")
    val transcriptionModel: String = Env.get("QWEN_TRANSCRIPTION_MODEL", "paraformer-v2")

    // Realtime (streaming) speech recognition used for hold-to-dictate on browsers without the
    // Web Speech API (e.g. Firefox). DashScope exposes it over a duplex WebSocket.
    val realtimeAsrModel: String = Env.get("QWEN_REALTIME_ASR_MODEL", "paraformer-realtime-v2")
    val realtimeAsrWsUrl: String = Env.get(
        "QWEN_REALTIME_ASR_WS_URL",
        apiHost.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            .removeSuffix("/") + "/api-ws/v1/inference"
    )

    val pollIntervalMs: Long = Env.get("QWEN_POLL_INTERVAL_MS", "3000").toLongOrNull() ?: 3000L
    val pollTimeoutMs: Long = Env.get("QWEN_POLL_TIMEOUT_MS", "3000000").toLongOrNull() ?: 3_000_000L

    // --- AI-cost ledger pricing ---------------------------------------------------------------
    // USD price per one million tokens for each billed model family, overridable via env. The
    // resolved per-token price is stored on every ledger entry, so the client never needs any
    // pricing knowledge of its own.
    private val chatUsdPerMillionTokens: Double = Env.get("QWEN_CHAT_USD_PER_MTOK", "0.40").toDoubleOrNull() ?: 0.40
    private val ttsUsdPerMillionTokens: Double = Env.get("QWEN_TTS_USD_PER_MTOK", "8.40").toDoubleOrNull() ?: 8.40
    private val defaultUsdPerMillionTokens: Double = Env.get("QWEN_DEFAULT_USD_PER_MTOK", "2.00").toDoubleOrNull() ?: 2.00

    /**
     * USD price charged per token for [model]. TTS / cloned-voice models bill at the TTS rate, the
     * chat model at the chat rate, and everything else falls back to the default rate. All rates
     * are overridable via env (see the `QWEN_*_USD_PER_MTOK` entries above).
     */
    fun usdPerToken(model: String): Double {
        val perMillion = when {
            model.equals(chatModel, ignoreCase = true) -> chatUsdPerMillionTokens
            model.contains("tts", ignoreCase = true) ||
                model.equals(voiceCloneTargetModel, ignoreCase = true) -> ttsUsdPerMillionTokens
            else -> defaultUsdPerMillionTokens
        }
        return perMillion / 1_000_000.0
    }

    // Qwen-Image (see [DEFAULT_IMAGE_MODEL_ID]; used for both text-to-image and image-editing) is
    // billed per generated image at a flat price (Alibaba tiers it by resolution on their side),
    // not per token, so its response carries no `input_tokens`/`output_tokens` usage block -
    // unlike the ledger's other AI calls. Overridable via env.
    private val imageUsdPerCall: Double = Env.get("QWEN_IMAGE_USD_PER_CALL", "0.05").toDoubleOrNull() ?: 0.05

    /** Flat USD price for one generated/edited image call, regardless of resolution. */
    fun usdPerImage(): Double = imageUsdPerCall

    /** True when a real API key has been supplied. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    fun logStatus() {
        if (isConfigured) {
            logger.info("Qwen (Alibaba Model Studio) configured. Host: {}", apiHost)
        } else {
            logger.warn("Qwen (Alibaba Model Studio) not configured; using mock defaults. Set QWEN_API_KEY to enable.")
        }
    }
}
