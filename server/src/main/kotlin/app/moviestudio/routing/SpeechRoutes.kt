package app.moviestudio.routing

import app.moviestudio.service.SpeechRelayService
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SpeechRoutes")

/**
 * Realtime dictation endpoint used by the hold-to-dictate fallback in the web client. Browsers
 * without the Web Speech API (Firefox) open this socket, stream microphone PCM up and receive a
 * running transcript back — see [SpeechRelayService] for the wire protocol.
 */
fun Route.speechRoutes() {
    route("/api/speech") {
        webSocket("/ws") {
            try {
                SpeechRelayService.relay(this)
            } catch (e: Exception) {
                logger.warn("Speech dictation socket closed with error: ${e.message}")
            }
        }
    }
}
