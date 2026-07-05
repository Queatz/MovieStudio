package app.moviestudio.service

import app.moviestudio.MovieTimeline
import app.moviestudio.TrackWithClips
import app.moviestudio.calculatedDuration
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.MovieRepository
import app.moviestudio.database.NoteRepository
import app.moviestudio.database.TrackRepository

/**
 * Assembles the full timeline (movie + tracks + clips + notes) and keeps the movie's
 * auto-calculated duration in sync with what is placed on it. Shared by the REST routes, the
 * render pipeline and the skeleton planner.
 */
object TimelineService {

    fun assemble(movieId: String): MovieTimeline? {
        val movie = MovieRepository.getById(movieId) ?: return null
        val tracks = TrackRepository.queryByMovieId(movieId)
        val trackIds = tracks.map { it.id }
        val clips = if (trackIds.isNotEmpty()) ClipRepository.queryByTrackIds(trackIds) else emptyList()
        val tracksWithClips = tracks.map { track ->
            TrackWithClips(track, clips.filter { it.trackId == track.id }.sortedBy { it.timelineStart })
        }
        val notes = NoteRepository.queryByMovieId(movieId).sortedBy { it.atSeconds }
        return MovieTimeline(movie, tracksWithClips, notes)
    }

    /**
     * Recalculates and persists the movie's [app.moviestudio.Movie.totalDuration] from its
     * timeline. Movies have no pre-set length, so this runs whenever clips or notes change.
     */
    fun refreshMovieDuration(movieId: String) {
        val timeline = assemble(movieId) ?: return
        val duration = timeline.calculatedDuration()
        if (timeline.movie.totalDuration != duration) {
            MovieRepository.update(timeline.movie.copy(totalDuration = duration))
        }
    }
}
