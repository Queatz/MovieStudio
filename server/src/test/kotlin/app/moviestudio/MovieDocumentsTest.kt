package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.DbCollection
import app.moviestudio.database.DocumentRepository
import app.moviestudio.database.MovieRepository
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
 * Movie documents: the rich-text long-form writing attached to a movie (full script, research...).
 * Covers the repository (CRUD, per-movie queries, validation) and the REST endpoints under
 * /api/movies/{movieId}/documents, including auto-save history checkpoints, nesting with the
 * cycle guard, the recursive subtree delete and the delete-with-movie cascade.
 */
class MovieDocumentsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // Initialize ArangoDB and truncate collections for clean slate
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection(DbCollection.MOVIES.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.DOCUMENTS.collectionName).truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection(DbCollection.MOVIES.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.DOCUMENTS.collectionName).truncate()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testDocumentRepository() {
        val movieId = UUID.randomUUID().toString()
        val document = MovieDocument(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            title = "Full script",
            content = "<p>FADE IN</p>",
            createdAt = System.currentTimeMillis()
        )

        // Insert + get
        val inserted = DocumentRepository.insert(document)
        assertEquals(document, inserted)
        val retrieved = DocumentRepository.getById(document.id)
        assertNotNull(retrieved)
        assertEquals("Full script", retrieved.title)
        assertEquals("<p>FADE IN</p>", retrieved.content)
        assertNull(retrieved.parentId)

        // Update (rename + re-parent + content); replace persists a cleared parentId too
        val parent = MovieDocument(UUID.randomUUID().toString(), movieId, "Acts", sortIndex = 1)
        DocumentRepository.insert(parent)
        DocumentRepository.update(document.copy(title = "Act I", parentId = parent.id, content = "<p>INT. LAB</p>"))
        val updated = DocumentRepository.getById(document.id)
        assertNotNull(updated)
        assertEquals("Act I", updated.title)
        assertEquals(parent.id, updated.parentId)
        DocumentRepository.update(updated.copy(parentId = null))
        assertNull(DocumentRepository.getById(document.id)?.parentId)

        // queryByMovieId returns only the movie's documents, sorted by sortIndex
        val otherMovieDoc = MovieDocument(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Other")
        DocumentRepository.insert(otherMovieDoc)
        val documents = DocumentRepository.queryByMovieId(movieId)
        assertEquals(listOf(document.id, parent.id), documents.map { it.id })

        // Validation: blank titles and self-parenting are rejected
        assertFailsWith<IllegalArgumentException> {
            DocumentRepository.insert(document.copy(id = UUID.randomUUID().toString(), title = "   "))
        }
        assertFailsWith<IllegalArgumentException> {
            val selfId = UUID.randomUUID().toString()
            DocumentRepository.insert(MovieDocument(selfId, movieId, "Loop", parentId = selfId))
        }

        // deleteByMovieId removes the movie's documents and nothing else
        DocumentRepository.deleteByMovieId(movieId)
        assertTrue(DocumentRepository.queryByMovieId(movieId).isEmpty())
        assertNotNull(DocumentRepository.getById(otherMovieDoc.id))

        DocumentRepository.delete(otherMovieDoc.id)
        assertNull(DocumentRepository.getById(otherMovieDoc.id))
    }

    @Test
    fun testDocumentEndpoints() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        MovieRepository.insert(
            Movie(
                id = movieId,
                title = "Documents Movie",
                totalDuration = 0.0,
                status = MovieStatus.DRAFT,
                createdAt = System.currentTimeMillis()
            )
        )

        suspend fun putDocument(document: MovieDocument): HttpResponse =
            client.put("/api/movies/$movieId/documents/${document.id}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(MovieDocument.serializer(), document))
            }

        // 1. Create a document: the server stamps createdAt and the sibling-based sortIndex
        val script = MovieDocument(UUID.randomUUID().toString(), movieId, "Script")
        val createResponse = client.post("/api/movies/$movieId/documents") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MovieDocument.serializer(), script))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = json.decodeFromString(MovieDocument.serializer(), createResponse.bodyAsText())
        assertEquals(script.id, created.id)
        assertTrue(created.createdAt > 0, "Server should stamp createdAt")
        assertEquals(0, created.sortIndex)

        val research = MovieDocument(UUID.randomUUID().toString(), movieId, "Research")
        val researchCreated = json.decodeFromString(
            MovieDocument.serializer(),
            client.post("/api/movies/$movieId/documents") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(MovieDocument.serializer(), research))
            }.bodyAsText()
        )
        assertEquals(1, researchCreated.sortIndex, "New documents land after their siblings")

        // 2. Blank document titles are rejected
        val blankResponse = client.post("/api/movies/$movieId/documents") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    MovieDocument.serializer(),
                    script.copy(id = UUID.randomUUID().toString(), title = "   ")
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, blankResponse.status)

        // 3. List returns the movie's documents in tree order
        val listResponse = client.get("/api/movies/$movieId/documents")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val documents = json.decodeFromString<List<MovieDocument>>(listResponse.bodyAsText())
        assertEquals(listOf(script.id, research.id), documents.map { it.id })

        // 4. Auto-save history: the first content lands without a checkpoint (nothing replaced),
        // the next change checkpoints the replaced content, and an immediate follow-up save is
        // rate-limited (no near-identical checkpoints).
        var saved = json.decodeFromString(
            MovieDocument.serializer(),
            putDocument(created.copy(content = "<p>v1</p>")).bodyAsText()
        )
        assertTrue(saved.history.isEmpty(), "Replacing blank content should not checkpoint")
        saved = json.decodeFromString(
            MovieDocument.serializer(),
            putDocument(saved.copy(content = "<p>v2</p>")).bodyAsText()
        )
        assertEquals(listOf("<p>v1</p>"), saved.history.map { it.content })
        saved = json.decodeFromString(
            MovieDocument.serializer(),
            putDocument(saved.copy(content = "<p>v3</p>")).bodyAsText()
        )
        assertEquals(1, saved.history.size, "Rapid follow-up saves should not add checkpoints")
        assertEquals("<p>v3</p>", saved.content)

        // 5. Nesting: research becomes a child of script; nesting script under its own child fails
        val nested = json.decodeFromString(
            MovieDocument.serializer(),
            putDocument(researchCreated.copy(parentId = script.id)).bodyAsText()
        )
        assertEquals(script.id, nested.parentId)
        val cycleResponse = putDocument(saved.copy(parentId = research.id))
        assertEquals(HttpStatusCode.BadRequest, cycleResponse.status)

        // 6. Deleting a document removes its whole subtree
        val deleteResponse = client.delete("/api/movies/$movieId/documents/${script.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        val afterDelete =
            json.decodeFromString<List<MovieDocument>>(client.get("/api/movies/$movieId/documents").bodyAsText())
        assertTrue(afterDelete.isEmpty(), "The nested child must be deleted with its parent")

        // 7. Deleting the movie removes its remaining documents
        client.post("/api/movies/$movieId/documents") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    MovieDocument.serializer(),
                    MovieDocument(UUID.randomUUID().toString(), movieId, "Leftover")
                )
            )
        }
        val deleteMovieResponse = client.delete("/api/movies/$movieId")
        assertEquals(HttpStatusCode.OK, deleteMovieResponse.status)
        assertTrue(DocumentRepository.queryByMovieId(movieId).isEmpty())
    }
}
