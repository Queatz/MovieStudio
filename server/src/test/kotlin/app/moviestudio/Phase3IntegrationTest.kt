package app.moviestudio

import app.moviestudio.database.*
import app.moviestudio.job.JobQueueWorker
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.*

class Phase3IntegrationTest {

    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    @Before
    fun setUp() {
        JobQueueWorker.stop()
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("assets").truncate()
            ArangoDatabase.db.collection("tracks").truncate()
            ArangoDatabase.db.collection("clips").truncate()
            ArangoDatabase.db.collection("jobs").truncate()
        } catch (e: Exception) {
            println("Skipping DB setup: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        JobQueueWorker.stop()
        try {
            ArangoDatabase.db.collection("movies").truncate()
            ArangoDatabase.db.collection("assets").truncate()
            ArangoDatabase.db.collection("tracks").truncate()
            ArangoDatabase.db.collection("clips").truncate()
            ArangoDatabase.db.collection("jobs").truncate()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testTrackRepository() {
        val movieId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val track = Track(
            id = trackId,
            movieId = movieId,
            type = TrackType.VIDEO,
            zIndex = 1
        )

        // Insert
        val inserted = TrackRepository.insert(track)
        assertEquals(track, inserted)

        // Get by ID
        val retrieved = TrackRepository.getById(trackId)
        assertNotNull(retrieved)
        assertEquals(movieId, retrieved.movieId)
        assertEquals(TrackType.VIDEO, retrieved.type)
        assertEquals(1, retrieved.zIndex)

        // Update
        val updatedTrack = track.copy(zIndex = 2)
        val updated = TrackRepository.update(updatedTrack)
        assertEquals(2, updated.zIndex)

        // Query by movieId
        val tracks = TrackRepository.queryByMovieId(movieId)
        assertEquals(1, tracks.size)
        assertEquals(trackId, tracks[0].id)

        // Delete
        TrackRepository.delete(trackId)
        assertNull(TrackRepository.getById(trackId))
    }

    @Test
    fun testClipRepositoryAndValidation() {
        val trackId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val clipId = UUID.randomUUID().toString()

        val validClip = Clip(
            id = clipId,
            trackId = trackId,
            assetId = assetId,
            timelineStart = 5.0f,
            trimIn = 1.0f,
            trimOut = 10.0f,
            effectsConfig = "{}"
        )

        // Validate success
        ClipRepository.validate(validClip)

        // Validate failure: negative timelineStart
        assertFailsWith<IllegalArgumentException> {
            ClipRepository.validate(validClip.copy(timelineStart = -1.0f))
        }

        // Validate failure: negative trimIn
        assertFailsWith<IllegalArgumentException> {
            ClipRepository.validate(validClip.copy(trimIn = -0.5f))
        }

        // Validate failure: trimOut < trimIn
        assertFailsWith<IllegalArgumentException> {
            ClipRepository.validate(validClip.copy(trimOut = 0.5f, trimIn = 1.0f))
        }

        // Insert valid clip
        val inserted = ClipRepository.insert(validClip)
        assertEquals(validClip, inserted)

        // Get by ID
        val retrieved = ClipRepository.getById(clipId)
        assertNotNull(retrieved)
        assertEquals(trackId, retrieved.trackId)
        assertEquals(5.0f, retrieved.timelineStart)

        // Query by trackId
        val clips = ClipRepository.queryByTrackId(trackId)
        assertEquals(1, clips.size)
        assertEquals(clipId, clips[0].id)

        // Query by multiple trackIds
        val multipleClips = ClipRepository.queryByTrackIds(listOf(trackId, "other"))
        assertEquals(1, multipleClips.size)

        // Delete by trackId
        ClipRepository.deleteByTrackId(trackId)
        assertEquals(0, ClipRepository.queryByTrackId(trackId).size)
    }

    @Test
    fun testAssetRepositoryQueryLibrary() {
        val movieId = UUID.randomUUID().toString()
        val asset1 = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.VIDEO,
            ossUrl = "https://oss.com/v1.mp4",
            durationSeconds = 10.0,
            movieId = movieId,
            tags = listOf("sci-fi", "stars"),
            aiPrompt = null
        )
        val asset2 = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.MUSIC,
            ossUrl = "https://oss.com/m1.mp3",
            durationSeconds = 180.0,
            movieId = null, // Global
            tags = listOf("chill", "retro"),
            aiPrompt = null
        )

        AssetRepository.insert(asset1)
        AssetRepository.insert(asset2)

        // Query by specific movieId
        val movieAssets = AssetRepository.queryLibrary(movieId, null, null)
        assertEquals(1, movieAssets.size)
        assertEquals(asset1.id, movieAssets[0].id)

        // Query global
        val globalAssets = AssetRepository.queryLibrary("global", null, null)
        assertTrue(globalAssets.any { it.id == asset2.id })

        // Query by type
        val musicAssets = AssetRepository.queryLibrary(null, AssetType.MUSIC, null)
        assertTrue(musicAssets.any { it.id == asset2.id })
        assertFalse(musicAssets.any { it.id == asset1.id })

        // Query by tag
        val scifiAssets = AssetRepository.queryLibrary(null, null, listOf("sci-fi"))
        assertEquals(1, scifiAssets.size)
        assertEquals(asset1.id, scifiAssets[0].id)
    }

    @Test
    fun testTimelineAndTrackClipEndpoints() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        val movie = Movie(
            id = movieId,
            title = "Test Movie Phase 3",
            totalDuration = 120.0,
            status = MovieStatus.DRAFT,
            createdAt = System.currentTimeMillis()
        )

        // Create movie
        val createMovieRes = client.post("/api/movies") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Movie.serializer(), movie))
        }
        assertEquals(HttpStatusCode.Created, createMovieRes.status)

        // Append Track
        val trackId = UUID.randomUUID().toString()
        val track = Track(id = trackId, movieId = movieId, type = TrackType.VIDEO, zIndex = 0)
        val createTrackRes = client.post("/api/movies/$movieId/tracks") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Track.serializer(), track))
        }
        assertEquals(HttpStatusCode.Created, createTrackRes.status)

        // Append Clip
        val assetId = UUID.randomUUID().toString()
        val clipId = UUID.randomUUID().toString()
        val clip = Clip(
            id = clipId,
            trackId = trackId,
            assetId = assetId,
            timelineStart = 10.0f,
            trimIn = 0.0f,
            trimOut = 5.0f,
            effectsConfig = ""
        )
        val createClipRes = client.post("/api/movies/$movieId/clips") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Clip.serializer(), clip))
        }
        assertEquals(HttpStatusCode.Created, createClipRes.status)

        // Verify Clip Validation (Negative value fails)
        val invalidClip = clip.copy(id = UUID.randomUUID().toString(), timelineStart = -5.0f)
        val createInvalidClipRes = client.post("/api/movies/$movieId/clips") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Clip.serializer(), invalidClip))
        }
        assertEquals(HttpStatusCode.BadRequest, createInvalidClipRes.status)

        // GET Timeline
        val timelineRes = client.get("/api/movies/$movieId/timeline")
        assertEquals(HttpStatusCode.OK, timelineRes.status)
        val timeline = json.decodeFromString(MovieTimeline.serializer(), timelineRes.bodyAsText())
        assertEquals(movieId, timeline.movie.id)
        assertEquals(1, timeline.tracks.size)
        assertEquals(trackId, timeline.tracks[0].track.id)
        assertEquals(1, timeline.tracks[0].clips.size)
        assertEquals(clipId, timeline.tracks[0].clips[0].id)

        // The movie's duration is auto-calculated from the clips on the timeline.
        val movieAfterClip = json.decodeFromString(Movie.serializer(), client.get("/api/movies/$movieId").bodyAsText())
        assertEquals(15.0, movieAfterClip.totalDuration, 0.001)

        // PUT/Update Clip
        val updatedClip = clip.copy(timelineStart = 20.0f)
        val updateClipRes = client.put("/api/movies/$movieId/clips/$clipId") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Clip.serializer(), updatedClip))
        }
        assertEquals(HttpStatusCode.OK, updateClipRes.status)
        val retrievedUpdated = json.decodeFromString(Clip.serializer(), updateClipRes.bodyAsText())
        assertEquals(20.0f, retrievedUpdated.timelineStart)

        // DELETE Track (cascades and deletes clips)
        val deleteTrackRes = client.delete("/api/movies/$movieId/tracks/$trackId")
        assertEquals(HttpStatusCode.OK, deleteTrackRes.status)

        // Verify timeline is empty of tracks
        val finalTimelineRes = client.get("/api/movies/$movieId/timeline")
        val finalTimeline = json.decodeFromString(MovieTimeline.serializer(), finalTimelineRes.bodyAsText())
        assertEquals(0, finalTimeline.tracks.size)
    }

    @Test
    fun testGlobalLibraryEndpoint() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        val asset1 = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.VOICE,
            ossUrl = "https://oss.com/vo.mp3",
            durationSeconds = 5.0,
            movieId = movieId,
            tags = listOf("dialogue"),
            aiPrompt = null
        )
        AssetRepository.insert(asset1)

        // Query via HTTP with type
        val res = client.get("/api/library?movieId=$movieId&type=VOICE")
        assertEquals(HttpStatusCode.OK, res.status)
        val list = json.decodeFromString<List<Asset>>(res.bodyAsText())
        assertEquals(1, list.size)
        assertEquals(asset1.id, list[0].id)

        // Query with invalid type should return BadRequest 400
        val badTypeRes = client.get("/api/library?type=INVALID_TYPE")
        assertEquals(HttpStatusCode.BadRequest, badTypeRes.status)
    }

    @Test
    fun testBackgroundJobEngineAndWebSocket() = testApplication {
        val clientWithWebSockets = createClient {
            install(WebSockets)
        }

        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        val movie = Movie(
            id = movieId,
            title = "Test Rendering Movie",
            totalDuration = 5.0,
            status = MovieStatus.DRAFT,
            createdAt = System.currentTimeMillis()
        )
        MovieRepository.insert(movie)

        val jobId = UUID.randomUUID().toString()
        val job = Job(
            id = jobId,
            movieId = movieId,
            type = JobType.FFMPEG_RENDER,
            status = JobStatus.PENDING,
            payload = "{}",
            resultUrl = null
        )

        // Insert pending job
        JobRepository.insert(job)

        // Connect to websocket to monitor the progress of the job
        clientWithWebSockets.webSocket("/api/jobs/ws?jobId=$jobId") {
            // Wait for WebSocket broadcasts
            val events = mutableListOf<JobProgressEvent>()
            
            // Collect messages. The job runs in the background and updates its status.
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val event = json.decodeFromString(JobProgressEvent.serializer(), frame.readText())
                    events.add(event)
                    if (event.status == JobStatus.COMPLETED || event.status == JobStatus.FAILED) {
                        break
                    }
                }
            }

            assertTrue(events.isNotEmpty(), "Should have received progress broadcast events")
            assertTrue(events.any { it.status == JobStatus.RUNNING }, "Should have locked job to RUNNING")
            assertTrue(events.any { it.status == JobStatus.COMPLETED }, "Should have finished job with COMPLETED")
        }

        // Verify database entry is COMPLETED
        val dbJob = JobRepository.getById(jobId)
        assertNotNull(dbJob)
        assertEquals(JobStatus.COMPLETED, dbJob.status)
        assertNotNull(dbJob.resultUrl)
    }
}
