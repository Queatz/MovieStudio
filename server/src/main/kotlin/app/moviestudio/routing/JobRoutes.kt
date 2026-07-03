package app.moviestudio.routing

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import app.moviestudio.Job
import app.moviestudio.JobProgressEvent
import app.moviestudio.database.JobRepository
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("JobRoutes")

object JobWebSocketManager {
    private val logger = LoggerFactory.getLogger(JobWebSocketManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    class Subscription(
        val session: DefaultWebSocketServerSession,
        val movieId: String?,
        val jobId: String?
    )

    private val subscriptions = ConcurrentHashMap.newKeySet<Subscription>()

    fun addSubscription(session: DefaultWebSocketServerSession, movieId: String?, jobId: String?) {
        subscriptions.add(Subscription(session, movieId, jobId))
        logger.info("Added WS subscription for movieId=$movieId, jobId=$jobId. Total active: ${subscriptions.size}")
    }

    fun removeSubscription(session: DefaultWebSocketServerSession) {
        subscriptions.removeIf { it.session == session }
        logger.info("Removed WS subscription. Total active: ${subscriptions.size}")
    }

    suspend fun broadcast(event: JobProgressEvent) {
        val messageText = json.encodeToString(JobProgressEvent.serializer(), event)
        logger.info("Broadcasting job event: $messageText")
        for (sub in subscriptions) {
            val matches = (sub.movieId == null && sub.jobId == null) ||
                    (sub.movieId != null && sub.movieId == event.movieId) ||
                    (sub.jobId != null && sub.jobId == event.jobId)

            if (matches) {
                try {
                    sub.session.send(Frame.Text(messageText))
                } catch (e: Exception) {
                    logger.error("Failed to send frame to session, removing", e)
                    removeSubscription(sub.session)
                }
            }
        }
    }
}

fun Route.jobRoutes() {
    route("/api/jobs") {
        // Lists jobs for the background-generations panel. `movieId` restricts to one movie;
        // `active=true` returns only PENDING/RUNNING jobs.
        get {
            try {
                val movieId = call.request.queryParameters["movieId"]
                val activeOnly = call.request.queryParameters["active"]?.toBoolean() ?: false
                call.respond(JobRepository.queryJobs(movieId, activeOnly))
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
                call.respond(HttpStatusCode.Created, saved)
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
