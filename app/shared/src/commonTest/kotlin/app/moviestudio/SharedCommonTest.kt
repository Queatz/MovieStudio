package app.moviestudio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun testBaseUrlAndConfig() {
        assertEquals(BuildConfig.BASE_URL, getBaseUrl())
        // Ensure default URL is localhost:8080 when not overridden or matched
        if (BuildConfig.ENV == "dev") {
            assertEquals("http://localhost:8080", getBaseUrl())
        }
    }

    @Test
    fun testMovieSerialization() {
        val movie = Movie(
            id = "movie-123",
            title = "Awesome Movie",
            totalDuration = 120.5,
            status = MovieStatus.DRAFT,
            createdAt = 1625292000000L
        )
        val json = Json.encodeToString(movie)
        val decoded = Json.decodeFromString<Movie>(json)
        assertEquals(movie, decoded)
    }

    @Test
    fun testClosestSizeForAspect() {
        // Generation sizes auto-match the movie's aspect ratio.
        assertEquals("1280*720", closestSizeForAspect(listOf("1280*720", "720*1280", "960*960"), "16:9"))
        assertEquals("720*1280", closestSizeForAspect(listOf("1280*720", "720*1280", "960*960"), "9:16"))
        assertEquals("960*960", closestSizeForAspect(listOf("1280*720", "720*1280", "960*960"), "1:1"))
        assertEquals("1024*768", closestSizeForAspect(SUPPORTED_IMAGE_SIZES, "4:3"))
        // 21:9 has no exact match; the widest landscape size wins.
        assertEquals("1280*720", closestSizeForAspect(listOf("1280*720", "960*960"), "21:9"))
    }

    @Test
    fun testSequencerNoteLengthAndCompatibility() {
        // Old patterns (step/pitch only) decode with a 1-step length and no per-note instrument.
        val legacy = Json.decodeFromString<SequencerNote>("""{"step": 3, "pitch": 5}""")
        assertEquals(1, legacy.lengthSteps)
        assertEquals(null, legacy.waveform)

        // covers() spans the dragged length.
        val held = SequencerNote(step = 2, pitch = 0, lengthSteps = 3, waveform = "square")
        assertEquals(false, held.covers(1))
        assertEquals(true, held.covers(2))
        assertEquals(true, held.covers(4))
        assertEquals(false, held.covers(5))
    }

    @Test
    fun testSequencerScaleMembership() {
        // Major key: C D E F G A B are in key; the black keys are not.
        assertEquals(true, isPitchInScale(0, "major"))   // C
        assertEquals(false, isPitchInScale(1, "major"))  // C#
        assertEquals(true, isPitchInScale(4, "major"))   // E
        assertEquals(false, isPitchInScale(6, "major"))  // F#
        // Membership repeats every octave.
        assertEquals(true, isPitchInScale(12, "major"))
        assertEquals(false, isPitchInScale(13, "major"))

        // Natural minor differs from major (e.g. the minor third is in key, the major third is not).
        assertEquals(true, isPitchInScale(3, "minor"))   // Eb
        assertEquals(false, isPitchInScale(4, "minor"))  // E
        // Unknown scale names fall back to major.
        assertEquals(true, isPitchInScale(4, "lydian"))
    }

    @Test
    fun testSequencerFrequencyOctaves() {
        // A pitch one octave up (12 semitones) is exactly double the frequency.
        val low = sequencerRowFrequency(24)
        val high = sequencerRowFrequency(36)
        assertEquals(true, kotlin.math.abs(high / low - 2.0) < 1e-6)
        // The root (pitch 0) is the C2 base frequency.
        assertEquals(true, kotlin.math.abs(sequencerRowFrequency(0) - SEQUENCER_BASE_FREQUENCY) < 1e-6)
    }

    @Test
    fun testAssetTypeForDroppedFile() {
        // Media extensions map to their asset type (case-insensitively).
        assertEquals(AssetType.IMAGE, assetTypeForFile("poster.png"))
        assertEquals(AssetType.IMAGE, assetTypeForFile("Shot.JPEG"))
        assertEquals(AssetType.VIDEO, assetTypeForFile("clip.mp4"))
        assertEquals(AssetType.VIDEO, assetTypeForFile("trailer.MOV"))
        assertEquals(AssetType.AUDIO, assetTypeForFile("song.mp3"))
        assertEquals(AssetType.AUDIO, assetTypeForFile("take.wav"))
        // Text drops become a TEXT asset (turned into a placeholder voice by the caller).
        assertEquals(AssetType.TEXT, assetTypeForFile("narration.txt"))
        assertEquals(AssetType.TEXT, assetTypeForFile("script.md"))
        // Unknown / extension-less files are unsupported.
        assertEquals(null, assetTypeForFile("archive.zip"))
        assertEquals(null, assetTypeForFile("README"))
    }

    @Test
    fun testMusicSequenceMeasures() {
        // Measures are derived from the step count (16 steps per measure, minimum one measure).
        assertEquals(1, MusicSequence(steps = 16).measures)
        assertEquals(3, MusicSequence(steps = 48).measures)
        assertEquals(1, MusicSequence(steps = 8).measures)
        // The scale defaults to major and round-trips through serialization.
        val decoded = Json.decodeFromString<MusicSequence>(
            Json.encodeToString(MusicSequence(steps = 32, scale = "minor"))
        )
        assertEquals("minor", decoded.scale)
        assertEquals(2, decoded.measures)
    }

    @Test
    fun testCollectPreloadMedia() {
        // A null timeline preloads nothing.
        assertEquals(emptyList(), collectPreloadMedia(null, emptyList()))

        val assets = listOf(
            preloadAsset("vid", AssetType.VIDEO, "https://oss/clip.mp4"),
            preloadAsset("img", AssetType.IMAGE, "https://oss/poster.png"),
            preloadAsset("song", AssetType.MUSIC, "https://oss/song.mp3"),
            preloadAsset("vo", AssetType.VOICE, "https://oss/vo.wav"),
            // A description-only placeholder (blank ossUrl) has nothing to preload.
            preloadAsset("desc", AssetType.TEXT, "")
        )
        val timeline = timelineOf(
            preloadTrack("t-video", TrackType.VIDEO, 0) to listOf(
                preloadClip("c1", "t-video", "vid"),
                preloadClip("c2", "t-video", "img"),
                preloadClip("c3", "t-video", "desc"),
                // A second clip reusing the same video asset must not duplicate the URL.
                preloadClip("c4", "t-video", "vid")
            ),
            preloadTrack("t-music", TrackType.MUSIC, 1) to listOf(
                preloadClip("c5", "t-music", "song")
            ),
            preloadTrack("t-voice", TrackType.VOICE, 2) to listOf(
                preloadClip("c6", "t-voice", "vo"),
                // A clip referencing a missing asset is skipped.
                preloadClip("c7", "t-voice", "ghost")
            )
        )

        val result = collectPreloadMedia(timeline, assets)

        // Each media URL appears once, in first-seen order; kind derives from the asset type.
        assertEquals(
            listOf(
                PreloadMediaItem("https://oss/clip.mp4", PreloadKind.VIDEO),
                PreloadMediaItem("https://oss/poster.png", PreloadKind.IMAGE),
                PreloadMediaItem("https://oss/song.mp3", PreloadKind.AUDIO),
                PreloadMediaItem("https://oss/vo.wav", PreloadKind.AUDIO)
            ),
            result
        )
    }

    private fun preloadAsset(id: String, type: AssetType, ossUrl: String) = Asset(
        id = id,
        type = type,
        ossUrl = ossUrl,
        durationSeconds = 5.0,
        movieId = "movie-1",
        tags = emptyList(),
        aiPrompt = null
    )

    private fun preloadTrack(id: String, type: TrackType, zIndex: Int) = Track(
        id = id,
        movieId = "movie-1",
        type = type,
        zIndex = zIndex
    )

    private fun preloadClip(id: String, trackId: String, assetId: String) = Clip(
        id = id,
        trackId = trackId,
        assetId = assetId,
        timelineStart = 0f,
        trimIn = 0f,
        trimOut = 5f,
        effectsConfig = ""
    )

    private fun timelineOf(vararg tracks: Pair<Track, List<Clip>>) = MovieTimeline(
        movie = Movie(
            id = "movie-1",
            title = "Test Movie",
            totalDuration = 0.0,
            status = MovieStatus.DRAFT,
            createdAt = 0L
        ),
        tracks = tracks.map { (track, clips) -> TrackWithClips(track, clips) }
    )
}
