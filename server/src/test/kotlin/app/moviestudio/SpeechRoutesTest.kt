package app.moviestudio

import app.moviestudio.service.QwenConfig
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration coverage for the `/api/speech/ws` realtime dictation endpoint. The assertion adapts
 * to the environment: without a Qwen API key the relay must immediately answer with a clear
 * "not configured" error frame (so the browser drops out of dictation cleanly); when a key *is*
 * configured we only assert the socket upgrades and accepts the opening frame (the upstream ASR
 * call is not exercised in tests).
 */
class SpeechRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun speechWebSocketUpgradesAndHandlesStartFrame() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket("/api/speech/ws") {
            send(Frame.Text("""{"action":"start","sampleRate":16000,"format":"pcm"}"""))

            if (!QwenConfig.isConfigured) {
                // Deterministic offline path: the relay replies with an error frame right away.
                val frame = withTimeout(15_000) { incoming.receive() }
                assertTrue(frame is Frame.Text, "expected a JSON text frame")
                val payload = json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals("error", payload["type"]!!.jsonPrimitive.content)
                assertTrue(
                    payload["message"]!!.jsonPrimitive.content.contains("not configured"),
                    "error should explain realtime transcription is not configured"
                )
            }
            // Closing the browser side must not throw; the server tears the relay down.
        }
    }
}
