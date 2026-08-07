package app.moviestudio

import app.moviestudio.ui.buildPrintableDocumentHtml
import app.moviestudio.ui.markdownToPrintHtml
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun testCollectPreloadMediaOnlyWarmsUpcomingWindow() {
        val assets = listOf(
            preloadAsset("a", AssetType.VIDEO, "https://oss/a.mp4"),
            preloadAsset("b", AssetType.VIDEO, "https://oss/b.mp4"),
            preloadAsset("c", AssetType.VIDEO, "https://oss/c.mp4"),
            preloadAsset("d", AssetType.VIDEO, "https://oss/d.mp4")
        )
        // A long timeline: scattered clips on a single track.
        val timeline = timelineOf(
            preloadTrack("t", TrackType.VIDEO, 0) to listOf(
                // Already finished before the current position (ends at 5s).
                preloadClip("c0", "t", "a").copy(timelineStart = 0f, trimIn = 0f, trimOut = 5f),
                // Currently PLAYING at the current position (started at 100s, runs to 130s): its
                // media is already live, so it must NOT be preloaded again (that would starve the
                // clip that is actually playing).
                preloadClip("c1", "t", "b").copy(timelineStart = 100f, trimIn = 0f, trimOut = 30f),
                // Starts within the next minute (140s), so it is worth warming ahead of time.
                preloadClip("c2", "t", "c").copy(timelineStart = 140f, trimIn = 0f, trimOut = 20f),
                // Far in the future (starts at 500s): must NOT be preloaded yet.
                preloadClip("c3", "t", "d").copy(timelineStart = 500f, trimIn = 0f, trimOut = 20f)
            )
        )

        // From 110s, only the clip that STARTS inside (110, 170] is warmed: the finished clip, the
        // currently-playing clip (started at 100s) and the far-future clip are all skipped.
        assertEquals(
            listOf(PreloadMediaItem("https://oss/c.mp4", PreloadKind.VIDEO)),
            collectPreloadMedia(timeline, assets, fromSeconds = 110f)
        )

        // Default position (0s) warms only the media starting in the first minute.
        assertEquals(
            listOf(PreloadMediaItem("https://oss/a.mp4", PreloadKind.VIDEO)),
            collectPreloadMedia(timeline, assets)
        )
    }

    @Test
    fun testToggledSelection() {
        // Toggling adds a missing id and removes a present one, leaving the rest untouched.
        assertEquals(setOf("a"), toggledSelection(emptySet(), "a"))
        assertEquals(setOf("a", "b"), toggledSelection(setOf("a"), "b"))
        assertEquals(setOf("a"), toggledSelection(setOf("a", "b"), "b"))
        assertEquals(emptySet(), toggledSelection(setOf("a"), "a"))
    }

    @Test
    fun testPickResizeEdge() {
        // Wide clip: the two grab zones don't overlap, so the nearest edge always wins.
        assertEquals(ResizeEdge.LEFT, pickResizeEdge(2f, 0f, 100f, 10f, trimIn = 5f, trimOut = 10f, trimOutCap = 0f))
        assertEquals(ResizeEdge.RIGHT, pickResizeEdge(98f, 0f, 100f, 10f, trimIn = 5f, trimOut = 10f, trimOutCap = 0f))
        // Outside both grab zones: no resize.
        assertEquals(null, pickResizeEdge(50f, 0f, 100f, 10f, trimIn = 5f, trimOut = 10f, trimOutCap = 0f))

        // Tiny clip: pointer is within EDGE_GRAB of both edges at once.
        // Left already at the source media's start (trimIn == 0) -> pick the right edge instead.
        assertEquals(
            ResizeEdge.RIGHT,
            pickResizeEdge(5f, 0f, 8f, 10f, trimIn = 0f, trimOut = 5f, trimOutCap = 0f)
        )
        // Right already at the asset's length cap -> pick the left edge instead.
        assertEquals(
            ResizeEdge.LEFT,
            pickResizeEdge(5f, 0f, 8f, 10f, trimIn = 2f, trimOut = 5f, trimOutCap = 5f)
        )
        // Both edges could still expand: default to the left edge (prior behavior).
        assertEquals(
            ResizeEdge.LEFT,
            pickResizeEdge(5f, 0f, 8f, 10f, trimIn = 2f, trimOut = 5f, trimOutCap = 20f)
        )
        // Neither edge can expand (uncommon, but shouldn't crash): still defaults to left.
        assertEquals(
            ResizeEdge.LEFT,
            pickResizeEdge(5f, 0f, 8f, 10f, trimIn = 0f, trimOut = 5f, trimOutCap = 5f)
        )
    }

    @Test
    fun testMovedClipGroup() {
        val tracks = listOf(
            TrackWithClips(preloadTrack("v0", TrackType.VIDEO, 0), listOf(preloadClip("c1", "v0", "a").copy(timelineStart = 2f))),
            TrackWithClips(preloadTrack("v1", TrackType.VIDEO, 1), emptyList()),
            TrackWithClips(preloadTrack("m0", TrackType.MUSIC, 2), listOf(preloadClip("c2", "m0", "a").copy(timelineStart = 5f)))
        )
        val movers = listOf(
            MovingClip(tracks[0].clips[0], 0, TrackType.VIDEO),
            MovingClip(tracks[2].clips[0], 2, TrackType.MUSIC)
        )

        // A horizontal-only move shifts every start by the same delta and keeps each track.
        val shifted = movedClipGroup(movers, tracks, deltaSeconds = 3f, rowDelta = 0)
        assertEquals(5f, shifted[0].timelineStart)
        assertEquals("v0", shifted[0].trackId)
        assertEquals(8f, shifted[1].timelineStart)
        assertEquals("m0", shifted[1].trackId)

        // Row +1: the video clip re-homes onto the compatible video row below it; the music clip's
        // destination row doesn't exist, so it stays on its own track.
        val down = movedClipGroup(movers, tracks, deltaSeconds = 0f, rowDelta = 1)
        assertEquals("v1", down[0].trackId)
        assertEquals("m0", down[1].trackId)

        // Dragging far left clamps each clip's start at 0 (never negative).
        val clamped = movedClipGroup(movers, tracks, deltaSeconds = -100f, rowDelta = 0)
        assertEquals(0f, clamped[0].timelineStart)
        assertEquals(0f, clamped[1].timelineStart)
    }

    @Test
    fun testClipEnd() {
        val clip = preloadClip("c", "t", "a").copy(timelineStart = 10f, trimIn = 2f, trimOut = 7f)
        // End is start + (trimOut - trimIn) = 10 + 5.
        assertEquals(15f, clipEnd(clip))
    }

    @Test
    fun testRippleShiftedClips() {
        // Clip A 0–5, B starts at 5 (flush after A), C starts at 12, D starts at 3 (overlaps A).
        val a = preloadClip("a", "v", "x").copy(timelineStart = 0f, trimIn = 0f, trimOut = 5f)
        val b = preloadClip("b", "v", "x").copy(timelineStart = 5f, trimIn = 0f, trimOut = 3f)
        val c = preloadClip("c", "m", "x").copy(timelineStart = 12f, trimIn = 0f, trimOut = 4f)
        val d = preloadClip("d", "v", "x").copy(timelineStart = 3f, trimIn = 0f, trimOut = 4f)
        val all = listOf(a, b, c, d)

        // Moving/resizing A: threshold = A's end (5). B and C shift; D (starts before 5) stays.
        val pushed = rippleShiftedClips(all, excludeClipIds = setOf("a"), thresholdSeconds = 5f, deltaSeconds = 2f)
        assertEquals(listOf("b", "c"), pushed.map { it.id })
        assertEquals(7f, pushed[0].timelineStart)
        assertEquals(14f, pushed[1].timelineStart)

        // Pull left by 2s from the same threshold.
        val pulled = rippleShiftedClips(all, excludeClipIds = setOf("a"), thresholdSeconds = 5f, deltaSeconds = -2f)
        assertEquals(listOf("b", "c"), pulled.map { it.id })
        assertEquals(3f, pulled[0].timelineStart)
        assertEquals(10f, pulled[1].timelineStart)

        // Excluded ids never move (the item being edited).
        assertEquals(
            emptyList(),
            rippleShiftedClips(all, excludeClipIds = setOf("a", "b", "c", "d"), thresholdSeconds = 0f, deltaSeconds = 5f)
        )

        // Zero delta still returns followers at their original starts so a live drag that
        // returns home can restore them (local updates only touch listed clips).
        val restored = rippleShiftedClips(all, excludeClipIds = setOf("a"), thresholdSeconds = 5f, deltaSeconds = 0f)
        assertEquals(listOf("b", "c"), restored.map { it.id })
        assertEquals(5f, restored[0].timelineStart)
        assertEquals(12f, restored[1].timelineStart)

        // Pulling past 0 clamps starts at 0.
        val clamped = rippleShiftedClips(
            listOf(b.copy(timelineStart = 1f)),
            excludeClipIds = emptySet(),
            thresholdSeconds = 0f,
            deltaSeconds = -5f
        )
        assertEquals(0f, clamped.single().timelineStart)

        // Reapplying from the same snapshot with a new total delta does not compound.
        val once = rippleShiftedClips(all, setOf("a"), 5f, 2f)
        val again = rippleShiftedClips(all, setOf("a"), 5f, 3f)
        assertEquals(7f, once.first { it.id == "b" }.timelineStart)
        assertEquals(8f, again.first { it.id == "b" }.timelineStart)

        // Near-adjacent: next starts a hair before the cut (tiny overlap / float residue)
        // still ripples; a clear mid-clip overlap still does not.
        val almostFlush = preloadClip("near", "v", "x").copy(timelineStart = 5f - 0.0005f, trimIn = 0f, trimOut = 2f)
        val floatEnd = 5f + 1e-6f // edited end slightly past an exact 5.0 start
        val nearIncluded = rippleShiftedClips(
            listOf(almostFlush, b.copy(timelineStart = 5f), d),
            excludeClipIds = setOf("a"),
            thresholdSeconds = floatEnd,
            deltaSeconds = 1f
        )
        assertEquals(listOf("near", "b"), nearIncluded.map { it.id })
        // Well before the cut (more than RIPPLE_THRESHOLD_EPSILON) stays put.
        assertTrue(nearIncluded.none { it.id == "d" })
    }

    @Test
    fun testTrackInsertIndexAfterLastOfKind() {
        // Default movie layout: Video, Music, Voice.
        val tracks = listOf(
            TrackWithClips(preloadTrack("v0", TrackType.VIDEO, 0), emptyList()),
            TrackWithClips(preloadTrack("m0", TrackType.MUSIC, 1), emptyList()),
            TrackWithClips(preloadTrack("o0", TrackType.VOICE, 2), emptyList())
        )
        // A new video goes after the last video (index 0 → insert at 1), not at the end.
        assertEquals(1, trackInsertIndex(tracks, TrackType.VIDEO))
        // A new music goes after the last music (index 1 → insert at 2).
        assertEquals(2, trackInsertIndex(tracks, TrackType.MUSIC))
        // A new voice goes after the last voice (index 2 → insert at 3 = end).
        assertEquals(3, trackInsertIndex(tracks, TrackType.VOICE))
        // A type that isn't present yet is appended at the end.
        assertEquals(3, trackInsertIndex(tracks, TrackType.EFFECTS))
        // Empty timeline: first track of any kind lands at 0.
        assertEquals(0, trackInsertIndex(emptyList(), TrackType.VIDEO))
    }

    @Test
    fun testShiftedZIndexesAfterInsert() {
        // Inserting at index 1 into 3 tracks: [0, 1, 2] → existing become [0, 2, 3] (new takes 1).
        assertEquals(listOf(0, 2, 3), shiftedZIndexesAfterInsert(3, 1))
        // Appending at the end leaves existing zIndexes alone.
        assertEquals(listOf(0, 1, 2), shiftedZIndexesAfterInsert(3, 3))
        // Inserting at the front shifts everyone up.
        assertEquals(listOf(1, 2, 3), shiftedZIndexesAfterInsert(3, 0))
    }

    @Test
    fun testMarkdownToPrintHtml() {
        // Headings, inline styles and both list kinds render to their HTML equivalents.
        assertEquals("<h1>Title</h1>\n", markdownToPrintHtml("# Title"))
        assertEquals("<h3>Sub</h3>\n", markdownToPrintHtml("### Sub"))
        assertEquals("<p><strong>bold</strong></p>\n", markdownToPrintHtml("**bold**"))
        assertEquals("<p><em>it</em></p>\n", markdownToPrintHtml("*it*"))
        assertEquals("<p><strong><em>bi</em></strong></p>\n", markdownToPrintHtml("***bi***"))
        assertEquals("<p><del>gone</del></p>\n", markdownToPrintHtml("~~gone~~"))
        assertEquals("<p><u>under</u></p>\n", markdownToPrintHtml("<u>under</u>"))
        assertEquals("<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n", markdownToPrintHtml("- one\n- two"))
        assertEquals("<ol>\n<li>first</li>\n</ol>\n", markdownToPrintHtml("1. first"))
    }

    @Test
    fun testMarkdownToPrintHtmlEscapesUnsafeText() {
        // Stray angle brackets / ampersands become inert entities (no injected markup).
        assertEquals("<p>a &lt;script&gt; &amp; b</p>\n", markdownToPrintHtml("a <script> & b"))
    }

    @Test
    fun testBuildPrintableDocumentHtmlIsA4AndAutoPrints() {
        val html = buildPrintableDocumentHtml("My & Doc", "# Hi")
        assertTrue(html.contains("size: A4"), "page should be A4")
        assertTrue(html.contains("margin: 24px auto"), "page should be horizontally centered")
        assertTrue(html.contains("window.print()"), "page should auto-open the print dialog")
        assertTrue(html.contains("<title>My &amp; Doc</title>"), "title should be escaped")
        assertTrue(html.contains("<h1>Hi</h1>"), "content should be rendered from markdown")
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
