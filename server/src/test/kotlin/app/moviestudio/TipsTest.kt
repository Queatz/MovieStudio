package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.DbCollection
import app.moviestudio.database.TipRepository
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
 * Studio-wide tips: reusable advice not tied to any movie. Covers the repository (insert/get,
 * newest-first listing, case-insensitive search) and the REST endpoints under /api/tips.
 */
class TipsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection(DbCollection.TIPS.collectionName).truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection(DbCollection.TIPS.collectionName).truncate()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testTipRepository() {
        val older = Tip(UUID.randomUUID().toString(), "Use keyboard shortcuts", "Space toggles playback", createdAt = 1_000L)
        val newer = Tip(UUID.randomUUID().toString(), "Snap clips", "Drag near another clip to snap", createdAt = 2_000L)
        TipRepository.insert(older)
        TipRepository.insert(newer)

        // getById round-trips
        val retrieved = TipRepository.getById(newer.id)
        assertNotNull(retrieved)
        assertEquals("Snap clips", retrieved.title)

        // listAll is newest first
        assertEquals(listOf(newer.id, older.id), TipRepository.listAll().map { it.id })

        // search matches title/content case-insensitively, still newest first
        assertEquals(listOf(newer.id), TipRepository.search("SNAP").map { it.id })
        assertEquals(listOf(older.id), TipRepository.search("playback").map { it.id })
        // blank query returns everything
        assertEquals(listOf(newer.id, older.id), TipRepository.search("  ").map { it.id })
        // no match
        assertTrue(TipRepository.search("nonexistent-term").isEmpty())
    }

    @Test
    fun testTipEndpoints() = testApplication {
        application {
            module()
        }

        // 1. Create a tip; the server stamps id/createdAt
        val tip = Tip(id = "", title = "Render overnight", content = "Long renders finish faster while you sleep")
        val createResponse = client.post("/api/tips") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Tip.serializer(), tip))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = json.decodeFromString(Tip.serializer(), createResponse.bodyAsText())
        assertTrue(created.id.isNotBlank(), "Server should assign an id")
        assertTrue(created.createdAt > 0, "Server should stamp createdAt")

        // 2. Blank title is rejected
        val blankResponse = client.post("/api/tips") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Tip.serializer(), tip.copy(title = "   ")))
        }
        assertEquals(HttpStatusCode.BadRequest, blankResponse.status)

        // 3. A second (newer) tip lands at the top of the list
        val second = Tip(id = "", title = "Name your tracks", content = "Clear track names save time")
        val secondCreated = json.decodeFromString(
            Tip.serializer(),
            client.post("/api/tips") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(Tip.serializer(), second))
            }.bodyAsText()
        )
        val listResponse = client.get("/api/tips")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val tips = json.decodeFromString<List<Tip>>(listResponse.bodyAsText())
        assertEquals(listOf(secondCreated.id, created.id), tips.map { it.id })

        // 4. Search narrows the list
        val searchResponse = client.get("/api/tips?q=render")
        val matches = json.decodeFromString<List<Tip>>(searchResponse.bodyAsText())
        assertEquals(listOf(created.id), matches.map { it.id })

        // 5. Updating a tip (edit + toggle read) preserves id/createdAt
        val edited = created.copy(title = "Render overnight (edited)", read = true)
        val updateResponse = client.put("/api/tips/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Tip.serializer(), edited))
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = json.decodeFromString(Tip.serializer(), updateResponse.bodyAsText())
        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals("Render overnight (edited)", updated.title)
        assertTrue(updated.read, "Read flag should persist")

        // 6. Deleting a tip removes it from the list
        val deleteResponse = client.delete("/api/tips/${secondCreated.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        val afterDelete = json.decodeFromString<List<Tip>>(client.get("/api/tips").bodyAsText())
        assertEquals(listOf(created.id), afterDelete.map { it.id })
    }
}
