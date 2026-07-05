package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.FilmRepository
import app.moviestudio.database.NoteRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/**
 * Timeline notes: the text-only plot-builder markers pinned to timeline positions. Covers the
 * repository (CRUD, time-sorted queries, validation) and the REST endpoints under
 * /api/movies/{movieId}/notes, including the delete-with-movie cascade.
 */
class TimelineNotesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // Initialize ArangoDB and truncate collections for clean slate
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("notes").truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("notes").truncate()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testNoteRepository() {
        val movieId = UUID.randomUUID().toString()
        val note = TimelineNote(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            atSeconds = 12.5,
            text = "The heist begins",
            createdAt = System.currentTimeMillis()
        )

        // Insert + get
        val inserted = NoteRepository.insert(note)
        assertEquals(note, inserted)
        val retrieved = NoteRepository.getById(note.id)
        assertNotNull(retrieved)
        assertEquals("The heist begins", retrieved.text)
        assertEquals(12.5, retrieved.atSeconds, 0.0001)

        // Update (text and marker position)
        NoteRepository.update(note.copy(text = "The heist begins at dawn", atSeconds = 30.0))
        val updated = NoteRepository.getById(note.id)
        assertNotNull(updated)
        assertEquals("The heist begins at dawn", updated.text)
        assertEquals(30.0, updated.atSeconds, 0.0001)

        // queryByMovieId returns only the movie's notes, sorted by time
        val earlier = TimelineNote(UUID.randomUUID().toString(), movieId, 5.0, "Opening shot")
        NoteRepository.insert(earlier)
        val otherMovieNote = TimelineNote(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1.0, "Other")
        NoteRepository.insert(otherMovieNote)
        val notes = NoteRepository.queryByMovieId(movieId)
        assertEquals(listOf(earlier.id, note.id), notes.map { it.id })

        // Validation: blank text and negative positions are rejected
        assertFailsWith<IllegalArgumentException> {
            NoteRepository.insert(note.copy(id = UUID.randomUUID().toString(), text = "   "))
        }
        assertFailsWith<IllegalArgumentException> {
            NoteRepository.insert(note.copy(id = UUID.randomUUID().toString(), atSeconds = -1.0))
        }

        // deleteByMovieId removes the movie's notes and nothing else
        NoteRepository.deleteByMovieId(movieId)
        assertTrue(NoteRepository.queryByMovieId(movieId).isEmpty())
        assertNotNull(NoteRepository.getById(otherMovieNote.id))

        NoteRepository.delete(otherMovieNote.id)
        assertNull(NoteRepository.getById(otherMovieNote.id))
    }

    @Test
    fun testNotesEndpoints() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        FilmRepository.insert(
            Film(
                id = movieId,
                title = "Notes Movie",
                totalDuration = 0.0,
                status = FilmStatus.DRAFT,
                createdAt = System.currentTimeMillis()
            )
        )

        // 1. Create a note
        val note = TimelineNote(UUID.randomUUID().toString(), movieId, 7.25, "Hero meets the mentor")
        val createResponse = client.post("/api/movies/$movieId/notes") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TimelineNote.serializer(), note))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = json.decodeFromString(TimelineNote.serializer(), createResponse.bodyAsText())
        assertEquals(note.id, created.id)
        assertTrue(created.createdAt > 0, "Server should stamp createdAt")

        // Creating a note (past the last clip — here there are no clips) extends the movie's
        // auto-calculated length to the note's position.
        assertEquals(7.25, FilmRepository.getById(movieId)?.totalDuration ?: -1.0, 0.0001)

        // 2. Blank note text is rejected
        val blankResponse = client.post("/api/movies/$movieId/notes") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    TimelineNote.serializer(),
                    note.copy(id = UUID.randomUUID().toString(), text = "   ")
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, blankResponse.status)

        // 3. List is sorted by timeline position
        val earlier = TimelineNote(UUID.randomUUID().toString(), movieId, 2.0, "Cold open")
        client.post("/api/movies/$movieId/notes") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TimelineNote.serializer(), earlier))
        }
        val listResponse = client.get("/api/movies/$movieId/notes")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val notes = json.decodeFromString<List<TimelineNote>>(listResponse.bodyAsText())
        assertEquals(listOf(earlier.id, note.id), notes.map { it.id })

        // 4. Update the note's text and marker position
        val updateResponse = client.put("/api/movies/$movieId/notes/${note.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    TimelineNote.serializer(),
                    created.copy(text = "Hero refuses the call", atSeconds = 9.0)
                )
            )
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = json.decodeFromString(TimelineNote.serializer(), updateResponse.bodyAsText())
        assertEquals("Hero refuses the call", updated.text)
        assertEquals(9.0, updated.atSeconds, 0.0001)

        // Re-pinning the furthest note updates the movie's length accordingly.
        assertEquals(9.0, FilmRepository.getById(movieId)?.totalDuration ?: -1.0, 0.0001)

        // 5. Delete one note
        val deleteResponse = client.delete("/api/movies/$movieId/notes/${earlier.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        val afterDelete =
            json.decodeFromString<List<TimelineNote>>(client.get("/api/movies/$movieId/notes").bodyAsText())
        assertEquals(listOf(note.id), afterDelete.map { it.id })

        // 6. Deleting the movie removes its remaining notes
        val deleteMovieResponse = client.delete("/api/movies/$movieId")
        assertEquals(HttpStatusCode.OK, deleteMovieResponse.status)
        assertTrue(NoteRepository.queryByMovieId(movieId).isEmpty())
    }
}
