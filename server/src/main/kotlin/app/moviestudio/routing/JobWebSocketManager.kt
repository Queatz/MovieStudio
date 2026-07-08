package app.moviestudio.routing

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import app.moviestudio.JobProgressEvent
import app.moviestudio.JobStatus
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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

    /**
     * True when [event] should be delivered to a subscription filtered by [subMovieId]/[subJobId].
     * A blank/empty movieId or jobId is treated as "no filter" (i.e. global), so a client that
     * connects with an empty `?movieId=` query — or with no filter at all — still receives every
     * event. This is the contract the client's timeline/library reload depends on: if a completed
     * generation's terminal event is not matched here it never reaches the client and the timeline
     * silently fails to reload.
     */
    fun eventMatchesSubscription(
        subMovieId: String?,
        subJobId: String?,
        eventMovieId: String,
        eventJobId: String
    ): Boolean {
        val movieFilter = subMovieId?.takeIf { it.isNotBlank() }
        val jobFilter = subJobId?.takeIf { it.isNotBlank() }
        return (movieFilter == null && jobFilter == null) ||
                (movieFilter != null && movieFilter == eventMovieId) ||
                (jobFilter != null && jobFilter == eventJobId)
    }

    suspend fun addSubscription(session: DefaultWebSocketServerSession, movieId: String?, jobId: String?) {
        // Normalize a blank query param (e.g. an empty `?movieId=`) to null so it means "global".
        val normalizedMovieId = movieId?.takeIf { it.isNotBlank() }
        val normalizedJobId = jobId?.takeIf { it.isNotBlank() }
        subscriptions.add(Subscription(session, normalizedMovieId, normalizedJobId))
        logger.info("Added WS subscription for movieId=$normalizedMovieId, jobId=$normalizedJobId. Total active: ${subscriptions.size}")
        // Replay events so a client that (re)connects just after — or long after — a job finished
        // still receives its notification instead of waiting forever:
        //  - a per-job subscription gets the latest known event for that job (see lastEventByJob);
        //  - a movie-wide / global subscription gets every recent terminal event it cares about.
        val toReplay: List<JobProgressEvent> = if (normalizedJobId != null) {
            listOfNotNull(lastEventByJob[normalizedJobId])
        } else {
            recentTerminalEvents.filter { normalizedMovieId == null || it.movieId == normalizedMovieId }
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
            val matches = eventMatchesSubscription(sub.movieId, sub.jobId, event.movieId, event.jobId)

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
