package app.moviestudio

import app.moviestudio.routing.JobWebSocketManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [JobWebSocketManager.eventMatchesSubscription], the predicate that decides whether a
 * background-job event is delivered to a given WebSocket subscription. This is the contract the
 * client's timeline/library reload depends on: the app opens a single GLOBAL subscription, so if a
 * completed generation's terminal event is not matched here it never reaches the client and the
 * timeline silently fails to reload/populate.
 */
class JobWebSocketManagerTest {

    @Test
    fun `global subscription receives events for any movie and job`() {
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = null, subJobId = null,
                eventMovieId = "movie-1", eventJobId = "job-1"
            )
        )
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = null, subJobId = null,
                eventMovieId = "movie-2", eventJobId = "job-2"
            )
        )
    }

    @Test
    fun `blank movie filter is treated as global, not a filter that matches nothing`() {
        // The client subscribes with an empty movieId in some flows (e.g. an empty `?movieId=`
        // query); that must behave like a global subscription rather than silently dropping every
        // event because "" never equals a real movie id.
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = "", subJobId = null,
                eventMovieId = "movie-1", eventJobId = "job-1"
            )
        )
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = "   ", subJobId = "",
                eventMovieId = "movie-9", eventJobId = "job-9"
            )
        )
    }

    @Test
    fun `movie-scoped subscription only receives its own movie's events`() {
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = "movie-1", subJobId = null,
                eventMovieId = "movie-1", eventJobId = "job-1"
            )
        )
        assertFalse(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = "movie-1", subJobId = null,
                eventMovieId = "movie-2", eventJobId = "job-1"
            )
        )
    }

    @Test
    fun `job-scoped subscription only receives its own job's events`() {
        assertTrue(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = null, subJobId = "job-1",
                eventMovieId = "movie-1", eventJobId = "job-1"
            )
        )
        assertFalse(
            JobWebSocketManager.eventMatchesSubscription(
                subMovieId = null, subJobId = "job-1",
                eventMovieId = "movie-1", eventJobId = "job-2"
            )
        )
    }
}
