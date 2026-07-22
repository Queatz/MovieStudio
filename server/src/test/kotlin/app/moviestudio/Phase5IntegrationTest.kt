package app.moviestudio

import app.moviestudio.database.*
import app.moviestudio.job.JobQueueWorker
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.MusicSynthesizer
import app.moviestudio.service.QwenAIService
import app.moviestudio.service.SkeletonService
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
import java.io.File
import java.util.UUID
import kotlin.test.*

class Phase5IntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val collections = listOf(
        DbCollection.MOVIES, DbCollection.ASSETS, DbCollection.TRACKS, DbCollection.CLIPS, DbCollection.JOBS,
        DbCollection.CHARACTERS, DbCollection.SCENES, DbCollection.VISUAL_STYLES, DbCollection.VOICE_CLONES,
        DbCollection.RENDERS
    )

    @Before
    fun setUp() {
        JobQueueWorker.stop()
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        JobQueueWorker.stop()
        AIGenerationService.setInstance(QwenAIService)
        try {
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
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
        MovieRepository.insert(
            Movie(movieId, "Skeleton Movie", 0.0, MovieStatus.DRAFT, System.currentTimeMillis())
        )
        TrackRepository.insert(Track(UUID.randomUUID().toString(), movieId, TrackType.VIDEO, 0))

        // Queue skeleton generation via the REST endpoint.
        val response = client.post("/api/movies/$movieId/skeleton") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "A joyful day at a seaside carnival", "atSeconds": 3.0}""")
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
        val movie = MovieRepository.getById(movieId)
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
            assertTrue(wavPeakAmplitude(file) > 0.05, "Rendered sequence must be audibly non-silent")
        } finally {
            file.delete()
        }
    }

    @Test
    fun testMusicSynthesizerHonorsNoteLengthsPerNoteInstrumentsAndSlowTempo() {
        // A held note (dragged across 4 steps) rings ~4x longer than a single-step note.
        fun audibleSeconds(note: SequencerNote): Double {
            val file = File.createTempFile("seq_len", ".wav")
            try {
                MusicSynthesizer.renderToWav(
                    MusicSequence(tempoBpm = 120, steps = 16, loops = 1, waveform = "sine", notes = listOf(note)),
                    file
                )
                val samples = wavSamples(file)
                val lastAudible = samples.indexOfLast { kotlin.math.abs(it) > 0.01 }
                return lastAudible / 44100.0
            } finally {
                file.delete()
            }
        }
        val short = audibleSeconds(SequencerNote(0, 0))
        val held = audibleSeconds(SequencerNote(0, 0, lengthSteps = 4))
        assertTrue(held > short * 2, "A 4-step note should ring much longer ($held vs $short)")

        // Per-note instruments: notes carrying their own waveform render without error and are
        // audible even when they differ from the sequence-level instrument.
        val mixed = MusicSequence(
            tempoBpm = 120,
            steps = 16,
            loops = 1,
            waveform = "sine",
            notes = listOf(
                SequencerNote(0, 0, waveform = "square"),
                SequencerNote(4, 2, waveform = "saw", lengthSteps = 2),
                SequencerNote(8, 4) // legacy note: falls back to the sequence waveform
            )
        )
        val mixedFile = File.createTempFile("seq_mixed", ".wav")
        try {
            MusicSynthesizer.renderToWav(mixed, mixedFile)
            assertTrue(wavPeakAmplitude(mixedFile) > 0.05)
        } finally {
            mixedFile.delete()
        }

        // The tempo floor is 20 BPM: 16 steps at 20bpm = 12s pattern + 0.5s tail.
        val slow = MusicSequence(tempoBpm = 20, steps = 16, loops = 1, notes = listOf(SequencerNote(0, 0)))
        val slowFile = File.createTempFile("seq_slow", ".wav")
        try {
            val slowDuration = MusicSynthesizer.renderToWav(slow, slowFile)
            assertTrue(slowDuration in 12.4..12.6, "20 BPM must be honored, was $slowDuration")
        } finally {
            slowFile.delete()
        }
    }

    @Test
    fun testMusicSynthesizerRendersMultipleMeasuresAndOffKeyPitches() {
        fun renderSeconds(seq: MusicSequence): Double {
            val f = File.createTempFile("seq_meas", ".wav")
            try {
                return MusicSynthesizer.renderToWav(seq, f)
            } finally {
                f.delete()
            }
        }
        // A 2-measure (32-step) pattern renders about twice as long as a 1-measure pattern:
        // 16 steps at 120bpm = 2.0s + 0.5s tail; 32 steps = 4.0s + 0.5s tail.
        val oneBar = MusicSequence(tempoBpm = 120, steps = 16, loops = 1, notes = listOf(SequencerNote(0, 0)))
        val twoBar = MusicSequence(
            tempoBpm = 120, steps = 32, loops = 1,
            notes = listOf(SequencerNote(0, 0), SequencerNote(16, 4))
        )
        assertTrue(renderSeconds(oneBar) in 2.4..2.6)
        assertTrue(renderSeconds(twoBar) in 4.4..4.6, "A 2-measure pattern should render ~4.5s")

        // Off-key chromatic pitches still render audibly (the piano roll allows any semitone).
        val offKey = MusicSequence(
            tempoBpm = 120, steps = 16, loops = 1, scale = "major",
            notes = listOf(SequencerNote(0, 25)) // a black key relative to C major
        )
        val f = File.createTempFile("seq_off", ".wav")
        try {
            MusicSynthesizer.renderToWav(offKey, f)
            assertTrue(wavPeakAmplitude(f) > 0.05, "Off-key notes must still be audible")
        } finally {
            f.delete()
        }
    }

    /** Decodes the 16-bit mono PCM samples of a WAV [file] into -1..1 doubles. */
    private fun wavSamples(file: File): DoubleArray {
        val bytes = file.readBytes()
        val data = bytes.drop(44)
        return DoubleArray(data.size / 2) { i ->
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            ((hi shl 8) or lo) / Short.MAX_VALUE.toDouble()
        }
    }

    /** The peak absolute amplitude (0..1) of a rendered WAV [file]. */
    private fun wavPeakAmplitude(file: File): Double =
        wavSamples(file).maxOfOrNull { kotlin.math.abs(it) } ?: 0.0

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

        // Visual styles CRUD.
        val createStyle = client.post("/api/styles") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"id": "", "name": "Pretty Anime",
                    "style": "semi-realistic cute/beautiful anime with faint outlines"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, createStyle.status)
        val style = json.decodeFromString(VisualStyle.serializer(), createStyle.bodyAsText())
        assertTrue(style.id.isNotBlank())
        assertEquals("Pretty Anime", style.name)

        val blankStyle = client.post("/api/styles") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "", "name": "No Text", "style": ""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankStyle.status)

        client.delete("/api/styles/${style.id}")
        val stylesAfterDelete = json.decodeFromString<List<VisualStyle>>(client.get("/api/styles").bodyAsText())
        assertFalse(stylesAfterDelete.any { it.id == style.id })

        // Voice options: presets + clones (created via the mock cloning service).
        AIGenerationService.setInstance(object : AIGenerationService {
            override suspend fun generateTranscript(asset: Asset): Asset = asset

            override suspend fun generateText(system: String, user: String): String = ""

            override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone {
                val clone = VoiceClone(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    qwenVoiceId = "mock-voice-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                    sourceAudioUrl = audioUrl,
                    createdAt = System.currentTimeMillis()
                )
                VoiceCloneRepository.insert(clone)
                return clone
            }

            override suspend fun executeAiGenerationJob(job: Job, onProgress: suspend (progress: Int, message: String) -> Unit) = Unit
        })
        val cloneRes = client.post("/api/voice/clones") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "My Voice", "audioUrl": "https://oss.com/sample.mp3"}""")
        }
        assertEquals(HttpStatusCode.Created, cloneRes.status)
        val optionsRes = client.get("/api/voice/options")
        assertEquals(HttpStatusCode.OK, optionsRes.status)
        val options = json.decodeFromString(VoiceOptions.serializer(), optionsRes.bodyAsText())
        assertEquals(QWEN_VOICE_CATALOG, options.presets)
        assertTrue(options.presets.any { it.id == "Cherry" && it.languages.isNotEmpty() })
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
        MovieRepository.insert(
            Movie(movieId, "Render History Movie", 3.0, MovieStatus.DRAFT, System.currentTimeMillis())
        )

        // Rendering an empty timeline is rejected — nothing to produce.
        val emptyRender = client.post("/api/movies/$movieId/render") {
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.BadRequest, emptyRender.status)

        // Seed one clip so the timeline is renderable.
        val trackId = UUID.randomUUID().toString()
        TrackRepository.insert(Track(trackId, movieId, TrackType.VIDEO, 0))
        val assetId = UUID.randomUUID().toString()
        AssetRepository.insert(
            Asset(
                id = assetId,
                type = AssetType.VIDEO,
                ossUrl = "https://mock-oss.invalid/mock-video.mp4",
                durationSeconds = 3.0,
                movieId = movieId,
                tags = emptyList(),
                aiPrompt = null,
                description = "Render test clip",
                createdAt = System.currentTimeMillis()
            )
        )
        ClipRepository.insert(
            Clip(
                id = UUID.randomUUID().toString(),
                trackId = trackId,
                assetId = assetId,
                timelineStart = 0f,
                trimIn = 0f,
                trimOut = 3f,
                effectsConfig = "{}"
            )
        )

        // Kick off the render through the endpoint (job + RENDERING status).
        val response = client.post("/api/movies/$movieId/render") {
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val job = json.decodeFromString(Job.serializer(), response.bodyAsText())
        assertEquals(JobType.FFMPEG_RENDER, job.type)
        assertEquals(MovieStatus.RENDERING, MovieRepository.getById(movieId)?.status)

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
        assertEquals(MovieStatus.COMPLETED, MovieRepository.getById(movieId)?.status)
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

        val failedJobId = UUID.randomUUID().toString()
        JobRepository.insert(
            Job(
                id = failedJobId,
                movieId = movieId,
                type = JobType.AI_GEN,
                status = JobStatus.FAILED,
                payload = "{}",
                resultUrl = null,
                label = "Video: broken",
                error = "Model exploded",
                createdAt = System.currentTimeMillis()
            )
        )

        // Stop the worker so the pending job stays pending during assertions.
        JobQueueWorker.stop()

        val activeRes = client.get("/api/jobs?movieId=$movieId&active=true")
        assertEquals(HttpStatusCode.OK, activeRes.status)
        val active = json.decodeFromString<List<Job>>(activeRes.bodyAsText())
        assertTrue(active.all { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING })

        // With includeFailed=true, failed jobs stay listed (with their reason) for retry/dismiss.
        val withFailedRes = client.get("/api/jobs?movieId=$movieId&active=true&includeFailed=true")
        val withFailed = json.decodeFromString<List<Job>>(withFailedRes.bodyAsText())
        val failedListed = withFailed.firstOrNull { it.id == failedJobId }
        assertNotNull(failedListed, "Failed jobs must stay visible in the panel listing")
        assertEquals("Model exploded", failedListed.error)

        val allRes = client.get("/api/jobs?movieId=$movieId")
        val all = json.decodeFromString<List<Job>>(allRes.bodyAsText())
        assertEquals(3, all.size)
    }

    @Test
    fun testFailedJobRetryAndDismiss() = testApplication {
        application {
            module()
        }
        JobQueueWorker.stop()

        val movieId = UUID.randomUUID().toString()
        fun failedJob() = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.FAILED,
            payload = "{}",
            resultUrl = null,
            label = "Video: broken",
            error = "Model exploded",
            createdAt = System.currentTimeMillis()
        )

        // Retry re-queues the job and clears its error.
        val retryable = JobRepository.insert(failedJob())
        val retryRes = client.post("/api/jobs/${retryable.id}/retry")
        assertEquals(HttpStatusCode.OK, retryRes.status)
        val requeued = json.decodeFromString(Job.serializer(), retryRes.bodyAsText())
        assertEquals(JobStatus.PENDING, requeued.status)
        assertNull(requeued.error)

        // Only failed jobs can be retried.
        val conflictRes = client.post("/api/jobs/${retryable.id}/retry")
        assertEquals(HttpStatusCode.Conflict, conflictRes.status)

        // Dismiss deletes the job entirely.
        val dismissible = JobRepository.insert(failedJob())
        val dismissRes = client.delete("/api/jobs/${dismissible.id}")
        assertEquals(HttpStatusCode.NoContent, dismissRes.status)
        assertNull(JobRepository.getById(dismissible.id))
    }
}
