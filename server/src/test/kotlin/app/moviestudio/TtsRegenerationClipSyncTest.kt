package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.database.MovieRepository
import app.moviestudio.database.TrackRepository
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.GenerationCommon
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test for the voiceover regeneration bug: [GenerationCommon.finalize] used to update
 * only the asset's media/duration when a (re)generation completed (e.g. Qwen TTS via
 * `executeTts`), leaving the timeline clip that referenced it stuck at its stale
 * skeleton-planned/previous length. As a result the freshly generated voiceover never "applied"
 * to the timeline. Finalizing must now resize referencing clips to span the new media and refresh
 * the movie's auto-calculated duration.
 */
class TtsRegenerationClipSyncTest {

    private val collections = listOf("movies", "assets", "tracks", "clips", "jobs")
    private var dbAvailable = true

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            dbAvailable = false
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        if (!dbAvailable) return
        try {
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun regeneratingVoiceResizesTimelineClipToNewMedia() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val movieId = UUID.randomUUID().toString()
        MovieRepository.insert(
            Movie(movieId, "Voiceover Movie", 0.0, MovieStatus.DRAFT, System.currentTimeMillis())
        )

        val trackId = UUID.randomUUID().toString()
        TrackRepository.insert(Track(trackId, movieId, TrackType.VOICE, 0))

        // A skeleton-planned placeholder VOICE asset (5s planned) and a clip sized to that plan.
        val assetId = UUID.randomUUID().toString()
        AssetRepository.insert(
            Asset(
                id = assetId,
                type = AssetType.VOICE,
                ossUrl = "",
                durationSeconds = 5.0,
                movieId = movieId,
                tags = listOf("skeleton", "description-only"),
                aiPrompt = "Narrator introduces the scene",
                description = "Narrator introduces the scene",
                createdAt = System.currentTimeMillis()
            )
        )
        val clipId = UUID.randomUUID().toString()
        ClipRepository.insert(
            Clip(
                id = clipId,
                trackId = trackId,
                assetId = assetId,
                timelineStart = 0f,
                trimIn = 0f,
                trimOut = 5f,
                effectsConfig = "{}"
            )
        )

        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.RUNNING,
            payload = "",
            resultUrl = null,
            createdAt = System.currentTimeMillis()
        )
        JobRepository.insert(job)

        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "tts", prompt = "Narrator introduces the scene", voice = "Serena"),
            assetId = assetId,
            targetType = "VOICE"
        )

        // The generated voiceover turned out to be much longer than the 5s placeholder.
        val newDuration = 15.34
        val transcript = "Narrator introduces the scene"
        val timings = buildWordTimings(transcript, newDuration)
        GenerationCommon.finalize(job, payload, "https://oss.example/voice.mp3", newDuration, transcript, timings)

        // The asset's media + duration were updated.
        val savedAsset = AssetRepository.getById(assetId)
        assertNotNull(savedAsset)
        assertEquals("https://oss.example/voice.mp3", savedAsset.ossUrl)
        assertEquals(newDuration, savedAsset.durationSeconds, 0.001)

        // The referencing timeline clip now spans the freshly generated voiceover (was stuck at 5s).
        val savedClip = ClipRepository.getById(clipId)
        assertNotNull(savedClip)
        assertEquals(0f, savedClip.trimIn)
        assertEquals(newDuration.toFloat(), savedClip.trimOut)

        // The movie's auto-calculated duration reflects the resized clip.
        val movie = MovieRepository.getById(movieId)
        assertNotNull(movie)
        assertEquals(newDuration, movie.totalDuration, 0.01)
    }
}
