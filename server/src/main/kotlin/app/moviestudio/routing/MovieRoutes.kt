package app.moviestudio.routing

import app.moviestudio.Clip
import app.moviestudio.Film
import app.moviestudio.FilmStatus
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.TimelineNote
import app.moviestudio.Track
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.FilmRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.NoteRepository
import app.moviestudio.database.RenderRepository
import app.moviestudio.database.TrackRepository
import app.moviestudio.service.TimelineService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SkeletonRequest(val prompt: String, val atSeconds: Double = 0.0)

fun Route.movieRoutes() {
    route("/api/movies") {
        get {
            try {
                val movies = FilmRepository.listAll()
                call.respond(movies)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        get("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                val movie = FilmRepository.getById(id) ?: return@get call.respond(HttpStatusCode.NotFound, "Movie not found")
                call.respond(movie)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val movie = call.receive<Film>()
                val stamped = if (movie.createdAt == 0L) movie.copy(createdAt = System.currentTimeMillis()) else movie
                val saved = FilmRepository.insert(stamped)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val movie = call.receive<Film>()
                val movieWithId = if (movie.id != id) movie.copy(id = id) else movie
                val updated = FilmRepository.update(movieWithId)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                // Remove the movie's tracks, clips and timeline notes along with it.
                TrackRepository.queryByMovieId(id).forEach { track ->
                    ClipRepository.deleteByTrackId(track.id)
                    TrackRepository.delete(track.id)
                }
                NoteRepository.deleteByMovieId(id)
                FilmRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Timeline & Track/Clip endpoints
        get("/{movieId}/timeline") {
            try {
                val movieId = call.parameters["movieId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val timeline = TimelineService.assemble(movieId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Movie not found")
                call.respond(timeline)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        post("/{movieId}/tracks") {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val track = call.receive<Track>()
                val trackWithMovieId = if (track.movieId != movieId) track.copy(movieId = movieId) else track
                val saved = TrackRepository.insert(trackWithMovieId)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Updates a track (rename, reorder via zIndex).
        put("/{movieId}/tracks/{trackId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val trackId = call.parameters["trackId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing trackId")
                val track = call.receive<Track>()
                val trackWithIds = track.copy(id = trackId, movieId = movieId)
                val updated = TrackRepository.update(trackWithIds)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        delete("/{movieId}/tracks/{trackId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val trackId = call.parameters["trackId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing trackId")
                ClipRepository.deleteByTrackId(trackId)
                TrackRepository.delete(trackId)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        post("/{movieId}/clips") {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val clip = call.receive<Clip>()
                val saved = ClipRepository.insert(clip)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        put("/{movieId}/clips/{clipId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val clipId = call.parameters["clipId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing clipId")
                val clip = call.receive<Clip>()
                val clipWithId = if (clip.id != clipId) clip.copy(id = clipId) else clip
                val updated = ClipRepository.update(clipWithId)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        delete("/{movieId}/clips/{clipId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val clipId = call.parameters["clipId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing clipId")
                ClipRepository.delete(clipId)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Timeline notes: text-only plot-builder markers pinned to timeline positions. Shown on
        // the timeline as blue markers and managed from the editor's notes side panel.
        get("/{movieId}/notes") {
            try {
                val movieId = call.parameters["movieId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                call.respond(NoteRepository.queryByMovieId(movieId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        post("/{movieId}/notes") {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val note = call.receive<TimelineNote>()
                val stamped = note.copy(
                    movieId = movieId,
                    createdAt = if (note.createdAt == 0L) System.currentTimeMillis() else note.createdAt
                )
                val saved = NoteRepository.insert(stamped)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        put("/{movieId}/notes/{noteId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val noteId = call.parameters["noteId"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing noteId")
                val note = call.receive<TimelineNote>()
                val updated = NoteRepository.update(note.copy(id = noteId, movieId = movieId))
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Validation failed")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        delete("/{movieId}/notes/{noteId}") {
            try {
                val movieId = call.parameters["movieId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val noteId = call.parameters["noteId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing noteId")
                NoteRepository.delete(noteId)
                TimelineService.refreshMovieDuration(movieId)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Movie skeleton generation: plans placeholder items with Qwen and inserts them on the
        // timeline. Runs asynchronously on the server; a WebSocket event announces completion.
        post("/{movieId}/skeleton") {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                FilmRepository.getById(movieId) ?: return@post call.respond(HttpStatusCode.NotFound, "Movie not found")
                val request = call.receive<SkeletonRequest>()
                if (request.prompt.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Prompt must not be blank")
                }
                val job = Job(
                    id = UUID.randomUUID().toString(),
                    movieId = movieId,
                    type = JobType.SKELETON,
                    status = JobStatus.PENDING,
                    payload = """{"prompt": ${kotlinx.serialization.json.JsonPrimitive(request.prompt)}, "atSeconds": ${request.atSeconds}}""",
                    resultUrl = null,
                    label = "Skeleton: ${request.prompt.take(60)}",
                    createdAt = System.currentTimeMillis()
                )
                JobRepository.insert(job)
                call.respond(HttpStatusCode.Accepted, job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Kicks off a final movie render job (async). The movie enters RENDERING status.
        // Rendering an empty timeline is rejected: there is nothing to produce.
        post("/{movieId}/render") {
            try {
                val movieId = call.parameters["movieId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                val movie = FilmRepository.getById(movieId) ?: return@post call.respond(HttpStatusCode.NotFound, "Movie not found")
                val timeline = TimelineService.assemble(movieId)
                if (timeline == null || timeline.tracks.all { it.clips.isEmpty() }) {
                    return@post call.respond(HttpStatusCode.BadRequest, "The timeline is empty — add media before rendering")
                }
                val job = Job(
                    id = UUID.randomUUID().toString(),
                    movieId = movieId,
                    type = JobType.FFMPEG_RENDER,
                    status = JobStatus.PENDING,
                    payload = "{}",
                    resultUrl = null,
                    label = "Render: ${movie.title.take(60)}",
                    createdAt = System.currentTimeMillis()
                )
                JobRepository.insert(job)
                FilmRepository.update(movie.copy(status = FilmStatus.RENDERING))
                call.respond(HttpStatusCode.Accepted, job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Every completed render of the movie, newest first. Replayable/downloadable at any time.
        get("/{movieId}/renders") {
            try {
                val movieId = call.parameters["movieId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing movieId")
                call.respond(RenderRepository.queryByMovieId(movieId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
