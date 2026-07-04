package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.FilmRepository
import app.moviestudio.routing.UploadUrlRequest
import app.moviestudio.routing.UploadUrlResponse
import app.moviestudio.storage.OssService
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

class Phase2IntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // Initialize ArangoDB and truncate collections for clean slate
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("assets").truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("assets").truncate()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testOssServiceUrlGeneration() {
        val objectKey = "test-video-${UUID.randomUUID()}.mp4"
        val url = OssService.generatePreSignedUploadUrl(objectKey)
        assertNotNull(url)
        assertTrue(url.contains(objectKey), "URL should contain the object key: $url")
        assertTrue(url.startsWith("http://") || url.startsWith("https://"), "URL should be http/https: $url")

        // The read URL for the object (plain public URL on the unconfigured dev/CI path).
        val downloadUrl = OssService.downloadUrl(objectKey)
        assertTrue(downloadUrl.contains(objectKey), "Download URL should contain the object key: $downloadUrl")
        assertTrue(downloadUrl.startsWith("https://"), "Download URL should be https: $downloadUrl")

        // The Content-Type the PUT must send is derived from the object key's extension and is
        // part of the upload URL's signature.
        assertEquals("video/mp4", OssService.uploadContentType(objectKey))
        assertEquals("image/png", OssService.uploadContentType("uploads/123-picture.png"))
        assertEquals("audio/wav", OssService.uploadContentType("music-sequences/seq.wav"))
        assertEquals("application/octet-stream", OssService.uploadContentType("uploads/unknown.bin"))
    }

    @Test
    fun testEnsureBucketCorsIsNoOpWhenUnconfigured() {
        // On the unconfigured dev/CI path (mock credentials) this must not touch the network or throw.
        OssService.ensureBucketCors()
    }

    @Test
    fun testFilmRepository() {
        val movieId = UUID.randomUUID().toString()
        val movie = Film(
            id = movieId,
            title = "Test Movie Title",
            totalDuration = 180.0,
            status = FilmStatus.DRAFT,
            createdAt = System.currentTimeMillis()
        )

        // Insert
        val inserted = FilmRepository.insert(movie)
        assertEquals(movie, inserted)

        // Get by ID
        val retrieved = FilmRepository.getById(movieId)
        assertNotNull(retrieved)
        assertEquals(movie.title, retrieved.title)
        assertEquals(movie.totalDuration, retrieved.totalDuration)
        assertEquals(movie.status, retrieved.status)

        // Update — movies can move between all the lifecycle statuses
        val updatedMovie = movie.copy(title = "Updated Movie Title", status = FilmStatus.IN_PRODUCTION)
        val updated = FilmRepository.update(updatedMovie)
        assertEquals("Updated Movie Title", updated.title)

        val retrievedUpdated = FilmRepository.getById(movieId)
        assertNotNull(retrievedUpdated)
        assertEquals("Updated Movie Title", retrievedUpdated.title)
        assertEquals(FilmStatus.IN_PRODUCTION, retrievedUpdated.status)

        // List
        val allMovies = FilmRepository.listAll()
        assertTrue(allMovies.any { it.id == movieId })

        // Delete
        FilmRepository.delete(movieId)
        assertNull(FilmRepository.getById(movieId))
    }

    @Test
    fun testAssetRepository() {
        val movieId = UUID.randomUUID().toString()

        val globalAsset = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.MUSIC,
            ossUrl = "https://oss.com/global.mp3",
            durationSeconds = 120.0,
            movieId = null,
            tags = listOf("global", "ambient"),
            aiPrompt = null
        )

        val movieAsset = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.VIDEO,
            ossUrl = "https://oss.com/movie.mp4",
            durationSeconds = 45.5,
            movieId = movieId,
            tags = listOf("sci-fi", "intro"),
            aiPrompt = "A cinematic space intro"
        )

        // Insert
        AssetRepository.insert(globalAsset)
        AssetRepository.insert(movieAsset)

        // Get by ID
        val retrievedGlobal = AssetRepository.getById(globalAsset.id)
        assertNotNull(retrievedGlobal)
        assertEquals(globalAsset.ossUrl, retrievedGlobal.ossUrl)
        assertNull(retrievedGlobal.movieId)

        val retrievedMovieAsset = AssetRepository.getById(movieAsset.id)
        assertNotNull(retrievedMovieAsset)
        assertEquals(movieAsset.ossUrl, retrievedMovieAsset.ossUrl)
        assertEquals(movieId, retrievedMovieAsset.movieId)

        // Query by movieId
        val movieAssets = AssetRepository.queryByMovieId(movieId)
        assertEquals(1, movieAssets.size)
        assertEquals(movieAsset.id, movieAssets[0].id)

        // Query global
        val globalAssets = AssetRepository.queryByMovieId(null)
        assertTrue(globalAssets.any { it.id == globalAsset.id })

        // Delete
        AssetRepository.delete(globalAsset.id)
        assertNull(AssetRepository.getById(globalAsset.id))
    }

    @Test
    fun testKtorEndpoints() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        val movie = Film(
            id = movieId,
            title = "Ktor Test Movie",
            totalDuration = 240.0,
            status = FilmStatus.DRAFT,
            createdAt = System.currentTimeMillis()
        )

        // 1. Create Movie
        val postResponse = client.post("/api/movies") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Film.serializer(), movie))
        }
        assertEquals(HttpStatusCode.Created, postResponse.status)
        val createdMovie = json.decodeFromString(Film.serializer(), postResponse.bodyAsText())
        assertEquals(movieId, createdMovie.id)

        // 2. Get Single Movie
        val getResponse = client.get("/api/movies/$movieId")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val retrievedMovie = json.decodeFromString(Film.serializer(), getResponse.bodyAsText())
        assertEquals("Ktor Test Movie", retrievedMovie.title)

        // 3. List All Movies
        val listResponse = client.get("/api/movies")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val moviesList = json.decodeFromString<List<Film>>(listResponse.bodyAsText())
        assertTrue(moviesList.any { it.id == movieId })

        // 4. Request Upload URL
        val uploadRequest = UploadUrlRequest("uploads/assets/test.mp4")
        val uploadUrlResponse = client.post("/api/assets/upload-url") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UploadUrlRequest.serializer(), uploadRequest))
        }
        assertEquals(HttpStatusCode.OK, uploadUrlResponse.status)
        val uploadUrlBody = json.decodeFromString(UploadUrlResponse.serializer(), uploadUrlResponse.bodyAsText())
        assertNotNull(uploadUrlBody.uploadUrl)
        assertEquals("uploads/assets/test.mp4", uploadUrlBody.objectKey)
        assertTrue(
            uploadUrlBody.downloadUrl.contains("uploads/assets/test.mp4"),
            "Download URL should point at the object: ${uploadUrlBody.downloadUrl}"
        )
        assertEquals("video/mp4", uploadUrlBody.contentType)

        // 5. Save Asset
        val assetId = UUID.randomUUID().toString()
        val asset = Asset(
            id = assetId,
            type = AssetType.VIDEO,
            ossUrl = "https://bucket.oss-cn-hangzhou.aliyuncs.com/uploads/assets/test.mp4",
            durationSeconds = 15.0,
            movieId = movieId,
            tags = listOf("action"),
            aiPrompt = null
        )
        val saveAssetResponse = client.post("/api/assets") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Asset.serializer(), asset))
        }
        assertEquals(HttpStatusCode.Created, saveAssetResponse.status)
        val savedAsset = json.decodeFromString(Asset.serializer(), saveAssetResponse.bodyAsText())
        assertEquals(assetId, savedAsset.id)
        assertTrue(savedAsset.createdAt > 0, "Server should stamp createdAt")

        // 6. Retrieve Asset with movieId
        val getAssetsResponse = client.get("/api/assets?movieId=$movieId")
        assertEquals(HttpStatusCode.OK, getAssetsResponse.status)
        val assetsList = json.decodeFromString<List<Asset>>(getAssetsResponse.bodyAsText())
        assertEquals(1, assetsList.size)
        assertEquals(assetId, assetsList[0].id)

        // 7. Delete Movie
        val deleteResponse = client.delete("/api/movies/$movieId")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        // Verify deletion
        val getDeletedResponse = client.get("/api/movies/$movieId")
        assertEquals(HttpStatusCode.NotFound, getDeletedResponse.status)
    }

    @Test
    fun testAssetVersionRestoreEndpoint() = testApplication {
        application {
            module()
        }

        // Asset with media and one previous version in its history.
        val assetId = UUID.randomUUID().toString()
        val asset = Asset(
            id = assetId,
            type = AssetType.VIDEO,
            ossUrl = "https://oss.com/current.mp4",
            durationSeconds = 8.0,
            movieId = null,
            tags = emptyList(),
            aiPrompt = "current version",
            history = listOf(
                AssetVersion(ossUrl = "https://oss.com/older.mp4", durationSeconds = 5.0, createdAt = 1L, prompt = "older")
            )
        )
        AssetRepository.insert(asset)

        // Restore version 0.
        val restoreResponse = client.post("/api/assets/$assetId/restore") {
            contentType(ContentType.Application.Json)
            setBody("""{"versionIndex": 0}""")
        }
        assertEquals(HttpStatusCode.OK, restoreResponse.status)
        val restored = json.decodeFromString(Asset.serializer(), restoreResponse.bodyAsText())
        assertEquals("https://oss.com/older.mp4", restored.ossUrl)
        assertEquals(5.0, restored.durationSeconds)
        // The previously-current media went back onto the history stack.
        assertEquals(1, restored.history.size)
        assertEquals("https://oss.com/current.mp4", restored.history[0].ossUrl)
    }

    @Test
    fun testAudioClipEndpointCreatesWindowedSoundEffect() = testApplication {
        application {
            module()
        }

        val sourceId = UUID.randomUUID().toString()
        val source = Asset(
            id = sourceId,
            type = AssetType.AUDIO,
            ossUrl = "https://oss.com/long-sound.mp3",
            durationSeconds = 30.0,
            movieId = null,
            tags = emptyList(),
            aiPrompt = null,
            sourceOffsetSeconds = 2.0
        )
        AssetRepository.insert(source)

        val clipResponse = client.post("/api/assets/$sourceId/clip") {
            contentType(ContentType.Application.Json)
            setBody("""{"startSeconds": 4.0, "endSeconds": 9.5, "name": "Impact tail"}""")
        }
        assertEquals(HttpStatusCode.Created, clipResponse.status)
        val clipped = json.decodeFromString(Asset.serializer(), clipResponse.bodyAsText())
        assertEquals(AssetType.AUDIO, clipped.type)
        assertEquals(source.ossUrl, clipped.ossUrl)
        assertEquals(5.5, clipped.durationSeconds, 0.0001)
        // Offsets accumulate: source offset (2.0) + window start (4.0).
        assertEquals(6.0, clipped.sourceOffsetSeconds, 0.0001)
        assertEquals("Impact tail", clipped.description)

        // Invalid window is rejected.
        val badResponse = client.post("/api/assets/$sourceId/clip") {
            contentType(ContentType.Application.Json)
            setBody("""{"startSeconds": 9.0, "endSeconds": 4.0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badResponse.status)
    }
}
