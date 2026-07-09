package app.moviestudio.routing

import app.moviestudio.service.GoogleFontsService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/** Body of `POST /api/fonts/pin`: pins or unpins a font family in the picker. */
@Serializable
data class PinFontRequest(val family: String, val pinned: Boolean = true)

/** Body of `POST /api/fonts/ensure`: requests the OSS-hosted file of a family's variant. */
@Serializable
data class EnsureFontRequest(val family: String, val variant: String = "regular")

/**
 * Google Fonts catalog endpoints backing the text/caption font picker: catalog search with
 * language (subset) and category filters, pinned/recently-used preferences, and the ensure
 * endpoint that resolves a family+variant to a durable OSS-hosted font file (downloaded from
 * Google only the first time it is ever requested, then reused across all movies).
 */
fun Route.fontRoutes() {
    route("/api/fonts") {
        get {
            try {
                val query = call.request.queryParameters["q"].orEmpty()
                val subset = call.request.queryParameters["subset"]
                val category = call.request.queryParameters["category"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 120
                call.respond(GoogleFontsService.search(query, subset, category, limit))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        get("/prefs") {
            try {
                call.respond(GoogleFontsService.prefs())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post("/pin") {
            try {
                val received = call.receive<PinFontRequest>()
                if (received.family.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Font family must not be blank")
                }
                call.respond(GoogleFontsService.setPinned(received.family, received.pinned))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post("/ensure") {
            try {
                val received = call.receive<EnsureFontRequest>()
                if (received.family.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Font family must not be blank")
                }
                call.respond(GoogleFontsService.ensureFont(received.family, received.variant))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid font request")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
