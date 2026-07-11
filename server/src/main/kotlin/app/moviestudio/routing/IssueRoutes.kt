package app.moviestudio.routing

import app.moviestudio.Issue
import app.moviestudio.database.IssueRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * User-reported issues: studio-wide (not tied to any movie), each with a title, description,
 * workflow status and an open/closed flag. Issues are searchable (optional `q` query parameter
 * matches title/description) and returned open-first, then newest first.
 */
fun Route.issueRoutes() {
    route("/api/issues") {
        get {
            try {
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(IssueRepository.search(query))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val received = call.receive<Issue>()
                if (received.title.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Issue title must not be blank")
                }
                val issue = received.copy(
                    id = received.id.ifBlank { UUID.randomUUID().toString() },
                    createdAt = if (received.createdAt == 0L) System.currentTimeMillis() else received.createdAt
                )
                call.respond(HttpStatusCode.Created, IssueRepository.insert(issue))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val received = call.receive<Issue>()
                if (received.title.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, "Issue title must not be blank")
                }
                val existing = IssueRepository.getById(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, "Issue not found")
                val issue = received.copy(id = id, createdAt = existing.createdAt)
                call.respond(HttpStatusCode.OK, IssueRepository.update(issue))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                IssueRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
