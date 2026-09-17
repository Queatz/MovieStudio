package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.ClipRepository
import app.moviestudio.database.DbCollection
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
import kotlin.test.assertTrue

/**
 * Extract-voice jobs produce a VOICE library asset and drop a muted, captions-on clip onto the
 * voice track at the source video clip's window — creating the track when the movie has none.
 */
class ExtractVoicePlacementTest {

    private val collections = listOf(
        DbCollection.MOVIES,
        DbCollection.ASSETS,
        DbCollection.TRACKS,
        DbCollection.CLIPS,
        DbCollection.JOBS
    )
    private var dbAvailable = true

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            dbAvailable = false
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        if (!dbAvailable) return
        try {
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun extractVoiceKindProducesVoiceAssetType() {
        val payload = AiJobPayload(setup = GenerationSetup(kind = "extract-voice"))
        assertEquals(AssetType.VOICE, GenerationCommon.assetTypeFor(payload))
    }

    @Test
    fun extractAudioKindStillProducesAudioAssetType() {
        val payload = AiJobPayload(setup = GenerationSetup(kind = "extract-audio"))
        assertEquals(AssetType.AUDIO, GenerationCommon.assetTypeFor(payload))
    }

    @Test
    fun placingExtractedVoiceCreatesMutedCaptionedClipAtSourceWindow() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val movieId = UUID.randomUUID().toString()
        MovieRepository.insert(
            Movie(movieId, "Extract Voice Movie", 0.0, MovieStatus.DRAFT, System.currentTimeMillis())
        )

        val videoTrackId = UUID.randomUUID().toString()
        TrackRepository.insert(Track(videoTrackId, movieId, TrackType.VIDEO, 0))

        val videoAssetId = UUID.randomUUID().toString()
        AssetRepository.insert(
            Asset(
                id = videoAssetId,
                type = AssetType.VIDEO,
                ossUrl = "https://oss.example/clip.mp4",
                durationSeconds = 12.0,
                movieId = movieId,
                tags = emptyList(),
                aiPrompt = "A talking head",
                description = "A talking head",
                createdAt = System.currentTimeMillis()
            )
        )

        val sourceClipId = UUID.randomUUID().toString()
        ClipRepository.insert(
            Clip(
                id = sourceClipId,
                trackId = videoTrackId,
                assetId = videoAssetId,
                timelineStart = 4.5f,
                trimIn = 1f,
                trimOut = 6f,
                effectsConfig = "{}"
            )
        )

        val voiceAssetId = UUID.randomUUID().toString()
        val voiceAsset = Asset(
            id = voiceAssetId,
            type = AssetType.VOICE,
            ossUrl = "https://oss.example/voice.mp3",
            durationSeconds = 12.0,
            movieId = movieId,
            tags = listOf("ai-generated", "extract-voice"),
            aiPrompt = "Voice from: A talking head",
            description = "Voice from: A talking head",
            createdAt = System.currentTimeMillis()
        )
        AssetRepository.insert(voiceAsset)

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
            setup = GenerationSetup(kind = "extract-voice", prompt = "Voice from: A talking head"),
            sourceUrl = "https://oss.example/clip.mp4",
            sourceClipId = sourceClipId,
            targetType = AssetType.VOICE.name
        )

        GenerationCommon.placeExtractedVoiceClip(job, payload, voiceAsset)

        val tracks = TrackRepository.queryByMovieId(movieId)
        val voiceTrack = tracks.firstOrNull { it.type == TrackType.VOICE }
        assertNotNull(voiceTrack)

        val voiceClips = ClipRepository.queryByTrackId(voiceTrack.id)
        assertEquals(1, voiceClips.size)
        val placed = voiceClips.single()
        assertEquals(voiceAssetId, placed.assetId)
        assertEquals(4.5f, placed.timelineStart)
        assertEquals(1f, placed.trimIn)
        assertEquals(6f, placed.trimOut)

        val effects = parseEffectsConfig(placed.effectsConfig)
        assertEquals(0.0, effects.volume, 0.0001)
        assertNotNull(effects.captions)
        assertTrue(effects.captions!!.enabled)

        val movie = MovieRepository.getById(movieId)
        assertNotNull(movie)
        // Window is 5s long starting at 4.5s → movie duration 9.5s.
        assertEquals(9.5, movie.totalDuration, 0.01)
    }

    @Test
    fun placingExtractedVoiceReusesAnExistingVoiceTrack() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val movieId = UUID.randomUUID().toString()
        MovieRepository.insert(
            Movie(movieId, "Extract Voice Movie", 0.0, MovieStatus.DRAFT, System.currentTimeMillis())
        )
        val videoTrackId = UUID.randomUUID().toString()
        TrackRepository.insert(Track(videoTrackId, movieId, TrackType.VIDEO, 0))
        val existingVoiceTrackId = UUID.randomUUID().toString()
        TrackRepository.insert(Track(existingVoiceTrackId, movieId, TrackType.VOICE, 1))

        val videoAssetId = UUID.randomUUID().toString()
        AssetRepository.insert(
            Asset(
                id = videoAssetId,
                type = AssetType.VIDEO,
                ossUrl = "https://oss.example/clip.mp4",
                durationSeconds = 8.0,
                movieId = movieId,
                tags = emptyList(),
                aiPrompt = "Talking head",
                createdAt = System.currentTimeMillis()
            )
        )
        val sourceClipId = UUID.randomUUID().toString()
        ClipRepository.insert(
            Clip(
                id = sourceClipId,
                trackId = videoTrackId,
                assetId = videoAssetId,
                timelineStart = 0f,
                trimIn = 0f,
                trimOut = 8f,
                effectsConfig = "{}"
            )
        )
        val voiceAsset = Asset(
            id = UUID.randomUUID().toString(),
            type = AssetType.VOICE,
            ossUrl = "https://oss.example/voice.mp3",
            durationSeconds = 8.0,
            movieId = movieId,
            tags = emptyList(),
            aiPrompt = "Voice from talking head",
            createdAt = System.currentTimeMillis()
        )
        AssetRepository.insert(voiceAsset)

        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.RUNNING,
            payload = "",
            resultUrl = null,
            createdAt = System.currentTimeMillis()
        )
        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "extract-voice"),
            sourceClipId = sourceClipId
        )
        GenerationCommon.placeExtractedVoiceClip(job, payload, voiceAsset)

        val voiceTracks = TrackRepository.queryByMovieId(movieId).filter { it.type == TrackType.VOICE }
        assertEquals(1, voiceTracks.size)
        assertEquals(existingVoiceTrackId, voiceTracks.single().id)
        val placed = ClipRepository.queryByTrackId(existingVoiceTrackId)
        assertEquals(1, placed.size)
        assertEquals(voiceAsset.id, placed.single().assetId)
    }
}
