package app.moviestudio

import app.moviestudio.database.*
import app.moviestudio.job.JobQueueWorker
import app.moviestudio.service.MockAIService
import app.moviestudio.service.MusicSynthesizer
import app.moviestudio.service.SkeletonService
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.test.*

class Phase5IntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val collections = listOf(
        "movies", "assets", "tracks", "clips", "jobs", "characters", "scenes", "voiceclones", "renders"
    )

    @Before
    fun setUp() {
        JobQueueWorker.stop()
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        JobQueueWorker.stop()
        try {
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testMockAiVideoGenerationJob(): Unit = runBlocking {
        val movieId = UUID.randomUUID().toString()
        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.PENDING,
            payload = """{"prompt":"A futuristic sci-fi city","type":"VIDEO"}""", // legacy payload shape
            resultUrl = null
        )
        JobRepository.insert(job)

        MockAIService.executeAiGenerationJob(job) { _, _ -> }

        val assets = AssetRepository.queryByMovieId(movieId)
        assertEquals(1, assets.size)
        assertEquals(AssetType.VIDEO, assets[0].type)
        assertTrue(assets[0].ossUrl.contains("placeholder-video.mp4"))
        assertEquals("A futuristic sci-fi city", assets[0].aiPrompt)

        val dbJob = JobRepository.getById(job.id)
        assertNotNull(dbJob)
        assertEquals(JobStatus.COMPLETED, dbJob.status)
    }

    @Test
    fun testMockTtsJobStoresTranscriptAndWordTimings(): Unit = runBlocking {
        val movieId = UUID.randomUUID().toString()
        val setup = GenerationSetup(kind = "tts", prompt = "Hello brave new world", voice = "Cherry")
        val payload = """{"setup": ${json.encodeToString(GenerationSetup.serializer(), setup)}}"""
        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.PENDING,
            payload = payload,
            resultUrl = null
        )
        JobRepository.insert(job)

        MockAIService.executeAiGenerationJob(job) { _, _ -> }

        val assets = AssetRepository.queryByMovieId(movieId)
        assertEquals(1, assets.size)
        val voice = assets[0]
        assertEquals(AssetType.VOICE, voice.type)
        assertEquals("Hello brave new world", voice.transcript)
        assertEquals(4, voice.wordTimings.size)
        assertEquals("Cherry", voice.voice)
        assertTrue(voice.wordTimings.first().start < voice.wordTimings.last().end)
    }

    @Test
    fun testRegenerationPushesPreviousMediaToHistory(): Unit = runBlocking {
        // Existing description-only asset gets generated, then regenerated: the first media
        // version must be restorable from its history.
        val asset = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.VIDEO,
            ossUrl = "https://oss.com/first-version.mp4",
            durationSeconds = 4.0,
            movieId = null,
            tags = emptyList(),
            aiPrompt = "city lights",
            description = "city lights"
        )
        AssetRepository.insert(asset)

        val setup = GenerationSetup(kind = "video", prompt = "city lights")
        val payload =
            """{"setup": ${json.encodeToString(GenerationSetup.serializer(), setup)}, "assetId": "${asset.id}"}"""
        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = "",
            type = JobType.AI_GEN,
            status = JobStatus.PENDING,
            payload = payload,
            resultUrl = null
        )
        JobRepository.insert(job)

        MockAIService.executeAiGenerationJob(job) { _, _ -> }

        val updated = AssetRepository.getById(asset.id)
        assertNotNull(updated)
        assertTrue(updated.ossUrl.contains("placeholder-video.mp4"))
        assertEquals(1, updated.history.size)
        assertEquals("https://oss.com/first-version.mp4", updated.history[0].ossUrl)
        assertNotNull(updated.generationConfig, "The generation setup must be stored for retry/tweak")
    }

    @Test
    fun testSkeletonPlanParsing() {
        val raw = """
            Sure! Here is the plan:
            ```json
            [
              {"trackType": "VIDEO", "assetType": "VIDEO", "description": "Opening shot", "startSeconds": 2.0, "durationSeconds": 6.0},
              {"trackType": "MUSIC", "assetType": "MUSIC", "description": "Score", "startSeconds": 2.0, "durationSeconds": 10.0}
            ]
            ```
        """.trimIndent()
        val items = SkeletonService.parseItems(raw)
        assertEquals(2, items.size)
        assertEquals("Opening shot", items[0].description)
        assertEquals(2.0, items[1].startSeconds)
    }

    @Test
    fun testSkeletonEndpointPlansTimelineItems() = testApplication {
        val clientWithWebSockets = createClient {
            install(WebSockets)
        }

        application {
            module()
        }

        // Movie with one existing empty video track.
        val movieId = UUID.randomUUID().toString()
        FilmRepository.insert(
            Film(movieId, "Skeleton Movie", 0.0, FilmStatus.DRAFT, System.currentTimeMillis())
        )
        TrackRepository.insert(Track(UUID.randomUUID().toString(), movieId, TrackType.VIDEO, 0))

        // Queue skeleton generation via the REST endpoint.
        val response = client.post("/api/movies/$movieId/skeleton") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "A short heist story", "atSeconds": 3.0}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val job = json.decodeFromString(Job.serializer(), response.bodyAsText())
        assertEquals(JobType.SKELETON, job.type)

        // Watch the job over WebSocket until completion (worker runs it in the background).
        clientWithWebSockets.webSocket("/api/jobs/ws?jobId=${job.id}") {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val event = json.decodeFromString(JobProgressEvent.serializer(), frame.readText())
                    assertEquals(JobType.SKELETON, event.jobType, "Events must carry the job type for client reloads")
                    if (event.status == JobStatus.COMPLETED || event.status == JobStatus.FAILED) break
                }
            }
        }

        val dbJob = JobRepository.getById(job.id)
        assertNotNull(dbJob)
        assertEquals(JobStatus.COMPLETED, dbJob.status)

        // The mock planner creates description-only assets and places clips on the timeline.
        val tracks = TrackRepository.queryByMovieId(movieId)
        val clips = ClipRepository.queryByTrackIds(tracks.map { it.id })
        assertTrue(clips.isNotEmpty(), "Skeleton should insert timeline items")

        val assets = AssetRepository.queryByMovieId(movieId)
        assertTrue(assets.isNotEmpty())
        assertTrue(assets.all { it.ossUrl.isBlank() }, "Skeleton items are description-only")
        assertTrue(assets.all { !(it.description ?: "").isBlank() })

        // Items start at the requested playhead position.
        assertTrue(clips.any { it.timelineStart >= 3.0f - 0.001f })

        // The movie's duration was refreshed from the new clips.
        val movie = FilmRepository.getById(movieId)
        assertNotNull(movie)
        assertTrue(movie.totalDuration > 0.0)
    }

    @Test
    fun testMusicSynthesizerProducesPlayableWav() {
        val sequence = MusicSequence(
            name = "Test",
            tempoBpm = 120,
            steps = 16,
            loops = 1,
            waveform = "sine",
            notes = listOf(SequencerNote(0, 0), SequencerNote(4, 2), SequencerNote(8, 4))
        )
        val file = File.createTempFile("seq_test", ".wav")
        try {
            val duration = MusicSynthesizer.renderToWav(sequence, file)
            // 16 sixteenth-steps at 120bpm = 2.0s pattern + 0.5s release tail.
            assertTrue(duration in 2.4..2.6, "One 16-step pattern at 120bpm should be ~2.5s incl. tail, was $duration")
            assertTrue(file.length() > 44, "WAV must contain data beyond the header")
            val header = file.readBytes().take(4).toByteArray().decodeToString()
            assertEquals("RIFF", header)
        } finally {
            file.delete()
        }
    }

    @Test
    fun testMusicSequenceEndpointCreatesAsset() = testApplication {
        application {
            module()
        }

        val body = """
            {"sequence": {"name": "Endpoint Seq", "tempoBpm": 140, "steps": 16, "loops": 1,
             "waveform": "square", "notes": [{"step": 0, "pitch": 0}, {"step": 8, "pitch": 5}]}}
        """.trimIndent()
        val response = client.post("/api/music/sequence") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val asset = json.decodeFromString(Asset.serializer(), response.bodyAsText())
        assertEquals(AssetType.MUSIC, asset.type)
        assertTrue(asset.durationSeconds > 1.0)
        assertNotNull(asset.generationConfig)
        assertTrue(asset.generationConfig!!.contains("Endpoint Seq"), "The pattern is stored for later editing")

        // Empty sequences are rejected.
        val emptyRes = client.post("/api/music/sequence") {
            contentType(ContentType.Application.Json)
            setBody("""{"sequence": {"notes": []}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyRes.status)
    }

    @Test
    fun testCharacterSceneAndVoiceEndpoints() = testApplication {
        application {
            module()
        }

        // Characters CRUD (reference images capped at 3).
        val createCharacter = client.post("/api/characters") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"id": "", "name": "Mira", "description": "A captain",
                    "referenceImages": ["u1", "u2", "u3", "u4"]}"""
            )
        }
        assertEquals(HttpStatusCode.Created, createCharacter.status)
        val character = json.decodeFromString(Character.serializer(), createCharacter.bodyAsText())
        assertEquals(3, character.referenceImages.size, "Reference images are capped at 3")
        assertTrue(character.id.isNotBlank())

        val listCharacters = json.decodeFromString<List<Character>>(client.get("/api/characters").bodyAsText())
        assertTrue(listCharacters.any { it.id == character.id })

        // Scenes CRUD.
        val createScene = client.post("/api/scenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "", "name": "Neon Harbor", "description": "Docks at night", "referenceImages": []}""")
        }
        assertEquals(HttpStatusCode.Created, createScene.status)
        val scene = json.decodeFromString(Scene.serializer(), createScene.bodyAsText())

        client.delete("/api/scenes/${scene.id}")
        val scenesAfterDelete = json.decodeFromString<List<Scene>>(client.get("/api/scenes").bodyAsText())
        assertFalse(scenesAfterDelete.any { it.id == scene.id })

        // Voice options: presets + clones (created via the mock cloning service).
        val cloneRes = client.post("/api/voice/clones") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "My Voice", "audioUrl": "https://oss.com/sample.mp3"}""")
        }
        assertEquals(HttpStatusCode.Created, cloneRes.status)
        val optionsRes = client.get("/api/voice/options")
        assertEquals(HttpStatusCode.OK, optionsRes.status)
        val options = json.decodeFromString(VoiceOptions.serializer(), optionsRes.bodyAsText())
        assertEquals(QWEN_VOICE_PRESETS, options.presets)
        assertTrue(options.clones.any { it.name == "My Voice" })
    }

    @Test
    fun testRenderJobRecordsRenderHistory() = testApplication {
        val clientWithWebSockets = createClient {
            install(WebSockets)
        }

        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        FilmRepository.insert(
            Film(movieId, "Render History Movie", 3.0, FilmStatus.DRAFT, System.currentTimeMillis())
        )

        // Kick off the render through the endpoint (job + RENDERING status).
        val response = client.post("/api/movies/$movieId/render") {
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val job = json.decodeFromString(Job.serializer(), response.bodyAsText())
        assertEquals(JobType.FFMPEG_RENDER, job.type)
        assertEquals(FilmStatus.RENDERING, FilmRepository.getById(movieId)?.status)

        clientWithWebSockets.webSocket("/api/jobs/ws?jobId=${job.id}") {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val event = json.decodeFromString(JobProgressEvent.serializer(), frame.readText())
                    if (event.status == JobStatus.COMPLETED || event.status == JobStatus.FAILED) break
                }
            }
        }

        // A render record was persisted and is listed by the endpoint.
        val rendersRes = client.get("/api/movies/$movieId/renders")
        assertEquals(HttpStatusCode.OK, rendersRes.status)
        val renders = json.decodeFromString<List<RenderRecord>>(rendersRes.bodyAsText())
        assertEquals(1, renders.size)
        assertEquals(movieId, renders[0].movieId)
        assertTrue(renders[0].url.isNotBlank())

        // The movie left RENDERING when the render finished.
        assertEquals(FilmStatus.COMPLETED, FilmRepository.getById(movieId)?.status)
    }

    @Test
    fun testJobsListEndpoint() = testApplication {
        application {
            module()
        }

        val movieId = UUID.randomUUID().toString()
        JobRepository.insert(
            Job(
                id = UUID.randomUUID().toString(),
                movieId = movieId,
                type = JobType.AI_GEN,
                status = JobStatus.PENDING,
                payload = "{}",
                resultUrl = null,
                label = "Video: test",
                createdAt = System.currentTimeMillis()
            )
        )
        JobRepository.insert(
            Job(
                id = UUID.randomUUID().toString(),
                movieId = movieId,
                type = JobType.AI_GEN,
                status = JobStatus.COMPLETED,
                payload = "{}",
                resultUrl = "https://oss.com/done.mp4",
                label = "Video: done",
                createdAt = System.currentTimeMillis()
            )
        )

        // Stop the worker so the pending job stays pending during assertions.
        JobQueueWorker.stop()

        val activeRes = client.get("/api/jobs?movieId=$movieId&active=true")
        assertEquals(HttpStatusCode.OK, activeRes.status)
        val active = json.decodeFromString<List<Job>>(activeRes.bodyAsText())
        assertTrue(active.all { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING })

        val allRes = client.get("/api/jobs?movieId=$movieId")
        val all = json.decodeFromString<List<Job>>(allRes.bodyAsText())
        assertEquals(2, all.size)
    }
}
