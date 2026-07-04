package app.moviestudio.service

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
 * - QWEN_VIDEO_MODEL_T2V       WAN text-to-video model (default wan2.7-t2v).
 * - QWEN_VIDEO_MODEL_I2V       WAN image-to-video model (default wan2.7-i2v).
 * - QWEN_VIDEO_MODEL_R2V       WAN reference-to-video model (default wan2.7-r2v).
 * - QWEN_IMAGE_MODEL           Text-to-image model (default wanx2.1-t2i-turbo).
 * - QWEN_IMAGE_EDIT_MODEL      Image-to-image editing model (default wanx2.1-imageedit).
 * - QWEN_MUSIC_MODEL           Music generation model (default fun-music-preview).
 * - QWEN_TTS_MODEL             Text-to-speech model (default qwen-tts).
 * - QWEN_VOICE_ENROLL_MODEL    Voice cloning/enrollment model (default voice-enrollment).
 * - QWEN_VOICE_CLONE_TARGET    TTS model cloned voices target (default cosyvoice-v2).
 * - QWEN_AUDIO_MODEL           Sound-effect model, used both text-to-audio and video-driven
 *                              (default audio-generation-v1); verified against the
 *                              audio-generation/audio-synthesis endpoint.
 * - QWEN_POLL_INTERVAL_MS      Async task poll interval in ms (default 3000).
 * - QWEN_POLL_TIMEOUT_MS       Async task max wait in ms (default 300000).
 */
object QwenConfig {
    private val logger = LoggerFactory.getLogger(QwenConfig::class.java)

    val apiKey: String = Env.get("QWEN_API_KEY", "")
    val apiHost: String = Env.get("QWEN_API_HOST", "https://mock-host.maas.aliyuncs.com")
    val openAiBaseUrl: String = Env.get("QWEN_OPENAI_BASE_URL", "$apiHost/compatible-mode/v1")
    val dashScopeBaseUrl: String = Env.get("QWEN_DASHSCOPE_BASE_URL", "$apiHost/api/v1")

    val chatModel: String = Env.get("QWEN_CHAT_MODEL", "qwen3.7-plus")

    // WAN 2.7 video model family. The concrete model is chosen predictably from the generation
    // setup: text only -> T2V, first-frame image -> I2V, reference images/characters/scenes -> R2V.
    val videoModelT2V: String = Env.get("QWEN_VIDEO_MODEL_T2V", Env.get("QWEN_VIDEO_MODEL", "wan2.7-t2v"))
    val videoModelI2V: String = Env.get("QWEN_VIDEO_MODEL_I2V", "wan2.7-i2v")
    val videoModelR2V: String = Env.get("QWEN_VIDEO_MODEL_R2V", "wan2.7-r2v")

    val imageModel: String = Env.get("QWEN_IMAGE_MODEL", "wanx2.1-t2i-turbo")
    val imageEditModel: String = Env.get("QWEN_IMAGE_EDIT_MODEL", "wanx2.1-imageedit")
    val musicModel: String = Env.get("QWEN_MUSIC_MODEL", "fun-music-preview")
    val ttsModel: String = Env.get("QWEN_TTS_MODEL", "qwen-tts")
    val voiceEnrollModel: String = Env.get("QWEN_VOICE_ENROLL_MODEL", "voice-enrollment")
    val voiceCloneTargetModel: String = Env.get("QWEN_VOICE_CLONE_TARGET", "cosyvoice-v2")

    // Sound-effect model backing the audio-generation/audio-synthesis endpoint, for both the
    // direct text-to-audio and video-driven (GenerationSetup.sfxModel == "fun-audiogen" /
    // "fun-audiogen-vd") pipelines; the two modes differ only in whether a video_url is attached.
    val audioModel: String = Env.get("QWEN_AUDIO_MODEL", "audio-generation-v1")
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
    val pollTimeoutMs: Long = Env.get("QWEN_POLL_TIMEOUT_MS", "300000").toLongOrNull() ?: 300_000L

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
