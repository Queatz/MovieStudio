package app.moviestudio.routing

import app.moviestudio.Tip
import app.moviestudio.database.TipRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Studio-wide tips: short reusable pieces of advice, not tied to any movie. Tips are searchable
 * (optional `q` query parameter matches title/content) and always returned newest first.
 */
fun Route.tipRoutes() {
    route("/api/tips") {
        get {
            try {
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(TipRepository.search(query))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val received = call.receive<Tip>()
                if (received.title.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Tip title must not be blank")
                }
                val tip = received.copy(
                    id = received.id.ifBlank { UUID.randomUUID().toString() },
                    createdAt = if (received.createdAt == 0L) System.currentTimeMillis() else received.createdAt
                )
                call.respond(HttpStatusCode.Created, TipRepository.insert(tip))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val received = call.receive<Tip>()
                if (received.title.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, "Tip title must not be blank")
                }
                val existing = TipRepository.getById(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, "Tip not found")
                val tip = received.copy(id = id, createdAt = existing.createdAt)
                call.respond(HttpStatusCode.OK, TipRepository.update(tip))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                TipRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
