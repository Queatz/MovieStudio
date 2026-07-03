package app.moviestudio.service

import app.moviestudio.FilmTimeline
import app.moviestudio.TrackWithClips
import app.moviestudio.calculatedDuration
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.FilmRepository
import app.moviestudio.database.TrackRepository

/**
 * Assembles the full timeline (movie + tracks + clips) and keeps the movie's auto-calculated
 * duration in sync with the media placed on it. Shared by the REST routes, the render pipeline
 * and the skeleton planner.
 */
object TimelineService {

    fun assemble(movieId: String): FilmTimeline? {
        val movie = FilmRepository.getById(movieId) ?: return null
        val tracks = TrackRepository.queryByMovieId(movieId)
        val trackIds = tracks.map { it.id }
        val clips = if (trackIds.isNotEmpty()) ClipRepository.queryByTrackIds(trackIds) else emptyList()
        val tracksWithClips = tracks.map { track ->
            TrackWithClips(track, clips.filter { it.trackId == track.id }.sortedBy { it.timelineStart })
        }
        return FilmTimeline(movie, tracksWithClips)
    }

    /**
     * Recalculates and persists the movie's [app.moviestudio.Film.totalDuration] from its
     * timeline. Movies have no pre-set length, so this runs whenever clips change.
     */
    fun refreshMovieDuration(movieId: String) {
        val timeline = assemble(movieId) ?: return
        val duration = timeline.calculatedDuration()
        if (timeline.movie.totalDuration != duration) {
            FilmRepository.update(timeline.movie.copy(totalDuration = duration))
        }
    }
}
