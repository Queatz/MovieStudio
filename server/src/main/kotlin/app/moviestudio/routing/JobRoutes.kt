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
import app.moviestudio.JobStatus
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

    // Latest event per job id. Replayed to per-job subscribers on connect, so a client that
    // subscribes just after a fast job finished still receives its terminal event instead of
    // waiting forever.
    private val lastEventByJob = ConcurrentHashMap<String, JobProgressEvent>()

    // Recent terminal (COMPLETED/FAILED) events, oldest first, capped at [MAX_RECENT_TERMINAL].
    // Replayed to a movie-wide / global subscription when it (re)connects, so a client that was
    // restarted — or whose socket dropped and reconnected — still learns about every job that
    // finished while it had no live channel. Together with the client's auto-reconnect this keeps
    // completion notifications reliable even across broken connections.
    private val recentTerminalEvents = java.util.concurrent.ConcurrentLinkedDeque<JobProgressEvent>()
    private const val MAX_RECENT_TERMINAL = 100

    suspend fun addSubscription(session: DefaultWebSocketServerSession, movieId: String?, jobId: String?) {
        subscriptions.add(Subscription(session, movieId, jobId))
        logger.info("Added WS subscription for movieId=$movieId, jobId=$jobId. Total active: ${subscriptions.size}")
        // Replay events so a client that (re)connects just after — or long after — a job finished
        // still receives its notification instead of waiting forever:
        //  - a per-job subscription gets the latest known event for that job (see lastEventByJob);
        //  - a movie-wide / global subscription gets every recent terminal event it cares about.
        val toReplay: List<JobProgressEvent> = if (jobId != null) {
            listOfNotNull(lastEventByJob[jobId])
        } else {
            recentTerminalEvents.filter { movieId == null || it.movieId == movieId }
        }
        for (event in toReplay) {
            try {
                session.send(Frame.Text(json.encodeToString(JobProgressEvent.serializer(), event)))
            } catch (e: Exception) {
                logger.error("Failed to replay event to new session", e)
            }
        }
    }

    fun removeSubscription(session: DefaultWebSocketServerSession) {
        subscriptions.removeIf { it.session == session }
        logger.info("Removed WS subscription. Total active: ${subscriptions.size}")
    }

    suspend fun broadcast(event: JobProgressEvent) {
        lastEventByJob[event.jobId] = event
        // Keep terminal events around so a later-connecting client can be caught up (see
        // recentTerminalEvents). Supersede any earlier terminal event for the same job.
        if (event.status == JobStatus.COMPLETED || event.status == JobStatus.FAILED) {
            recentTerminalEvents.removeIf { it.jobId == event.jobId }
            recentTerminalEvents.addLast(event)
            while (recentTerminalEvents.size > MAX_RECENT_TERMINAL) {
                recentTerminalEvents.pollFirst()
            }
        }
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
                call.respond(JobRepository.update(requeued))
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
