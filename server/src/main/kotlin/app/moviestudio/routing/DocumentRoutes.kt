package app.moviestudio.routing

import app.moviestudio.DocumentVersion
import app.moviestudio.MovieDocument
import app.moviestudio.database.DocumentRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** How many previous versions a document keeps in its restorable history. */
private const val MAX_DOCUMENT_HISTORY = 100

/**
 * Minimum gap between two auto-saved history checkpoints. Auto-save fires on every typing pause,
 * so without this the history would fill with near-identical entries.
 */
private const val HISTORY_CHECKPOINT_INTERVAL_MS = 2 * 60 * 1000L

/** True when [candidateAncestorId] is [document] itself or one of its ancestors in [all]. */
private fun isSelfOrDescendant(all: List<MovieDocument>, document: MovieDocument, candidateAncestorId: String): Boolean {
    if (candidateAncestorId == document.id) return true
    val byId = all.associateBy { it.id }
    var current: MovieDocument? = byId[candidateAncestorId]
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current.id)) {
        if (current.id == document.id) return true
        current = current.parentId?.let { byId[it] }
    }
    return false
}

/**
 * Applies the history checkpoint policy when a document's content changes: the previous content
 * is pushed onto the history (most recent first) unless the newest checkpoint is fresher than
 * [HISTORY_CHECKPOINT_INTERVAL_MS] — auto-save fires on every typing pause, so checkpoints are
 * rate-limited to keep the history meaningful. The history is capped at [MAX_DOCUMENT_HISTORY].
 */
private fun historyFor(existing: MovieDocument, incoming: MovieDocument, now: Long): List<DocumentVersion> {
    if (incoming.content == existing.content || existing.content.isBlank()) return existing.history
    val newestCheckpoint = existing.history.firstOrNull()
    return if (newestCheckpoint == null || now - newestCheckpoint.savedAt >= HISTORY_CHECKPOINT_INTERVAL_MS) {
        (listOf(DocumentVersion(content = existing.content, savedAt = now)) + existing.history)
            .take(MAX_DOCUMENT_HISTORY)
    } else {
        existing.history
    }
}

/**
 * Movie documents: rich-text long-form writing attached to a movie (full script, research...).
 * Documents form a tree (parentId + sortIndex), auto-save from the client and keep a restorable
 * content history maintained here on every content-changing update.
 */
fun Route.documentRoutes() {
    route("/api/movies/{movieId}/documents") {
        get {
            try {
                val movieId = call.parameters["movieId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                call.respond(DocumentRepository.queryByMovieId(movieId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        post {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val document = call.receive<MovieDocument>()
                val now = System.currentTimeMillis()
                val siblings = DocumentRepository.queryByMovieId(movieId).filter { it.parentId == document.parentId }
                val stamped = document.copy(
                    movieId = movieId,
                    // New documents land after their existing siblings.
                    sortIndex = if (document.sortIndex == 0) (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1 else document.sortIndex,
                    createdAt = if (document.createdAt == 0L) now else document.createdAt,
                    updatedAt = now
                )
                val saved = DocumentRepository.insert(stamped)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        put("/{documentId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val documentId = call.parameters["documentId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing documentId")
                val incoming = call.receive<MovieDocument>().copy(id = documentId, movieId = movieId)
                val existing = DocumentRepository.getById(documentId)
                    ?: return@put call.respond(HttpStatusCode.NotFound, "Document not found")
                // Re-parenting a document under itself or one of its own descendants would orphan
                // the whole subtree.
                val newParentId = incoming.parentId
                if (newParentId != null && newParentId != existing.parentId) {
                    val all = DocumentRepository.queryByMovieId(movieId)
                    if (isSelfOrDescendant(all, existing, newParentId)) {
                        return@put call.respond(HttpStatusCode.BadRequest, "A document cannot be nested under itself or its own child")
                    }
                }
                val now = System.currentTimeMillis()
                val updated = DocumentRepository.update(
                    incoming.copy(
                        history = historyFor(existing, incoming, now),
                        createdAt = existing.createdAt,
                        updatedAt = now
                    )
                )
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        delete("/{documentId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val documentId = call.parameters["documentId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing documentId")
                // Deleting a document removes its whole subtree of child documents with it.
                val all = DocumentRepository.queryByMovieId(movieId)
                val toDelete = mutableListOf(documentId)
                var index = 0
                while (index < toDelete.size) {
                    val parentId = toDelete[index]
                    all.filter { it.parentId == parentId }.forEach { toDelete.add(it.id) }
                    index++
                }
                toDelete.forEach { DocumentRepository.delete(it) }
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
