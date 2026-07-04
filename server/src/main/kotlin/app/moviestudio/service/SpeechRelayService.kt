package app.moviestudio.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Bridges the browser hold-to-dictate microphone stream to the Alibaba Model Studio (DashScope)
 * realtime speech-recognition service so dictation still works on browsers that lack the Web
 * Speech API (notably Firefox, which has no `SpeechRecognition`).
 *
 * Wire protocol with the browser (over our own `/api/speech/ws` WebSocket):
 * - the browser first sends a JSON text frame `{"action":"start","sampleRate":16000,"format":"pcm"}`;
 * - it then streams raw little-endian 16-bit PCM as binary frames;
 * - it ends with `{"action":"stop"}` (or by closing the socket).
 *
 * We answer with JSON text frames:
 * - `{"type":"transcript","text":"<running transcript>"}` after every recognizer update, and
 * - `{"type":"error","message":"..."}` when realtime recognition is unavailable.
 *
 * Upstream we speak DashScope's duplex "run-task" / "finish-task" protocol and relay the running
 * transcript back to the browser. When no API key is configured we tell the browser immediately
 * so it can drop out of dictation cleanly.
 */
object SpeechRelayService {
    private val logger = LoggerFactory.getLogger(SpeechRelayService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val wsClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(WebSockets)
            // The dictation socket is long-lived (no request timeout), but the initial handshake
            // must fail fast so a misconfigured/unreachable ASR host surfaces an error to the
            // browser instead of hanging the microphone open.
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
            }
        }
    }

    /** Handles one browser dictation session for the lifetime of its WebSocket connection. */
    suspend fun relay(browser: DefaultWebSocketServerSession) {
        // The first frame carries the audio format the browser will stream.
        val config = awaitStartConfig(browser) ?: return
        if (!QwenConfig.isConfigured) {
            browser.sendJson(errorFrame("Realtime transcription is not configured on the server."))
            return
        }
        try {
            relayToDashScope(browser, config)
        } catch (e: Exception) {
            logger.warn("Realtime ASR relay failed: ${e.message}", e)
            runCatching { browser.sendJson(errorFrame("Realtime transcription failed.")) }
        }
    }

    internal data class StartConfig(val sampleRate: Int, val format: String)

    /** Reads (and tolerates the absence of) the browser's opening `start` frame. */
    private suspend fun awaitStartConfig(browser: DefaultWebSocketServerSession): StartConfig? {
        for (frame in browser.incoming) {
            if (frame !is Frame.Text) continue
            val obj = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
            if (obj["action"]?.jsonPrimitive?.contentOrNull == "start") {
                val rate = obj["sampleRate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 16000
                val format = obj["format"]?.jsonPrimitive?.contentOrNull ?: "pcm"
                return StartConfig(rate, format)
            }
        }
        return null
    }

    private suspend fun relayToDashScope(browser: DefaultWebSocketServerSession, config: StartConfig) {
        val taskId = UUID.randomUUID().toString()
        wsClient.webSocket(
            urlString = QwenConfig.realtimeAsrWsUrl,
            request = {
                header("Authorization", "bearer ${QwenConfig.apiKey}")
                header("X-DashScope-DataInspection", "enable")
            }
        ) {
            val dash = this
            dash.send(Frame.Text(runTaskFrame(taskId, config)))

            val started = CompletableDeferred<Boolean>()
            val transcript = RunningTranscript()

            coroutineScope {
                // DashScope -> browser: forward recognizer results as running transcripts.
                val downstream = launch {
                    for (frame in dash.incoming) {
                        if (frame !is Frame.Text) continue
                        when (val event = parseDashScopeEvent(frame.readText())) {
                            is DashScopeEvent.Started -> if (!started.isCompleted) started.complete(true)
                            is DashScopeEvent.Finished -> break
                            is DashScopeEvent.Failed -> {
                                logger.warn("DashScope realtime ASR task failed: ${event.message}")
                                break
                            }
                            is DashScopeEvent.Transcript -> {
                                val running = transcript.update(event.text, event.ended)
                                if (running.isNotBlank()) browser.sendJson(transcriptFrame(running))
                            }
                            DashScopeEvent.Ignored -> Unit
                        }
                    }
                    if (!started.isCompleted) started.complete(false)
                }

                // Wait for the task to start so early audio isn't dropped, then stream mic PCM up.
                withTimeoutOrNull(10_000) { started.await() }
                try {
                    for (frame in browser.incoming) {
                        when (frame) {
                            is Frame.Binary -> dash.send(Frame.Binary(true, frame.data))
                            is Frame.Text -> if (isStopFrame(frame.readText())) break
                            else -> Unit
                        }
                    }
                } finally {
                    runCatching { dash.send(Frame.Text(finishTaskFrame(taskId))) }
                }

                // Give DashScope a moment to flush the last recognized words before closing.
                withTimeoutOrNull(5_000) { downstream.join() }
                downstream.cancel()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pure protocol helpers (kept `internal` so they can be unit-tested without a live socket).
    // ------------------------------------------------------------------------------------------

    /** A DashScope realtime-ASR event decoded from one of its JSON text frames. */
    internal sealed interface DashScopeEvent {
        data object Started : DashScopeEvent
        data object Finished : DashScopeEvent
        data class Failed(val message: String?) : DashScopeEvent
        data class Transcript(val text: String, val ended: Boolean) : DashScopeEvent
        data object Ignored : DashScopeEvent
    }

    internal fun parseDashScopeEvent(raw: String): DashScopeEvent {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return DashScopeEvent.Ignored
        return when (root["header"]?.jsonObject?.get("event")?.jsonPrimitive?.contentOrNull) {
            "task-started" -> DashScopeEvent.Started
            "task-finished" -> DashScopeEvent.Finished
            "task-failed" -> DashScopeEvent.Failed(
                root["header"]?.jsonObject?.get("error_message")?.jsonPrimitive?.contentOrNull
            )
            "result-generated" -> {
                val sentence = root["payload"]?.jsonObject
                    ?.get("output")?.jsonObject
                    ?.get("sentence")?.jsonObject
                    ?: return DashScopeEvent.Ignored
                DashScopeEvent.Transcript(
                    text = sentence["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ended = sentence.isSentenceEnd()
                )
            }
            else -> DashScopeEvent.Ignored
        }
    }

    internal fun isStopFrame(raw: String): Boolean = runCatching {
        json.parseToJsonElement(raw).jsonObject["action"]?.jsonPrimitive?.contentOrNull == "stop"
    }.getOrDefault(false)

    /**
     * Accumulates the running transcript across DashScope sentences. The recognizer emits the
     * (growing) current sentence on every update and marks it complete either with a
     * `sentence_end` flag or a non-null `end_time`; completed sentences are kept as a prefix.
     */
    internal class RunningTranscript {
        private val finalized = StringBuilder()
        private var current: String = ""

        fun update(text: String, ended: Boolean): String {
            current = text
            val running = buildString {
                append(finalized)
                append(current)
            }.trim()
            if (ended && text.isNotBlank()) {
                finalized.append(text.trim()).append(' ')
                current = ""
            }
            return running
        }
    }

    private fun JsonObject.isSentenceEnd(): Boolean {
        this["sentence_end"]?.jsonPrimitive?.let { prim ->
            runCatching { return prim.boolean }
        }
        return this["end_time"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() != null
    }

    internal fun runTaskFrame(taskId: String, config: StartConfig): String = buildJsonObject {
        putJsonObject("header") {
            put("action", "run-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        }
        putJsonObject("payload") {
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("model", QwenConfig.realtimeAsrModel)
            putJsonObject("parameters") {
                put("format", config.format)
                put("sample_rate", config.sampleRate)
            }
            putJsonObject("input") {}
        }
    }.toString()

    internal fun finishTaskFrame(taskId: String): String = buildJsonObject {
        putJsonObject("header") {
            put("action", "finish-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        }
        putJsonObject("payload") {
            putJsonObject("input") {}
        }
    }.toString()

    internal fun transcriptFrame(text: String): String = buildJsonObject {
        put("type", "transcript")
        put("text", text)
    }.toString()

    internal fun errorFrame(message: String): String = buildJsonObject {
        put("type", "error")
        put("message", message)
    }.toString()

    private suspend fun DefaultWebSocketServerSession.sendJson(text: String) {
        send(Frame.Text(text))
    }
}
