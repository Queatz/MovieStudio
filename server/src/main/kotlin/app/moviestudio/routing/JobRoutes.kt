package app.moviestudio.routing

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.websocket.*
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.database.JobRepository
import app.moviestudio.job.JobQueueWorker
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("JobRoutes")

fun Route.jobRoutes() {
    route("/api/jobs") {
        // Lists jobs for the background-generations panel. `movieId` restricts to one movie;
        // `active=true` returns only PENDING/RUNNING jobs, plus FAILED ones when
        // `includeFailed=true` (so users can retry or dismiss them).
        get {
            try {
                val movieId = call.request.queryParameters["movieId"]
                val activeOnly = call.request.queryParameters["active"]?.toBoolean() ?: false
                val includeFailed = call.request.queryParameters["includeFailed"]?.toBoolean() ?: false
                call.respond(JobRepository.queryJobs(movieId, activeOnly, includeFailed))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        get("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                val job = JobRepository.getById(id) ?: return@get call.respond(HttpStatusCode.NotFound, "Job not found")
                call.respond(job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val job = call.receive<Job>()
                val stamped = if (job.createdAt == 0L) job.copy(createdAt = System.currentTimeMillis()) else job
                val saved = JobRepository.insert(stamped)
                // A new job was started: make sure the queue worker is running to pick it up.
                JobQueueWorker.start()
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        // Re-queues a FAILED job: back to PENDING (the queue worker picks it up again).
        post("/{id}/retry") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val job = JobRepository.getById(id) ?: return@post call.respond(HttpStatusCode.NotFound, "Job not found")
                if (job.status != JobStatus.FAILED) {
                    return@post call.respond(HttpStatusCode.Conflict, "Only failed jobs can be retried")
                }
                val requeued = job.copy(status = JobStatus.PENDING, error = null)
                val updated = JobRepository.update(requeued)
                // A job was re-queued: make sure the queue worker is running to pick it up.
                JobQueueWorker.start()
                call.respond(updated)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        // Dismisses (deletes) a job from the panel — used to clear failed generations.
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                JobRepository.delete(id)
                call.respond(HttpStatusCode.NoContent)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        webSocket("/ws") {
            val movieId = call.request.queryParameters["movieId"]
            val jobId = call.request.queryParameters["jobId"]

            JobWebSocketManager.addSubscription(this, movieId, jobId)
            try {
                for (frame in incoming) {
                    // We can just consume and ignore incoming messages or respond to ping
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        logger.info("Received WS text frame: $text")
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in WS connection: ${e.message}")
            } finally {
                JobWebSocketManager.removeSubscription(this)
            }
        }
    }
}
