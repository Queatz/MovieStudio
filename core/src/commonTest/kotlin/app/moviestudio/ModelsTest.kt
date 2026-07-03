package app.moviestudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {

    @Test
    fun effectsConfigRoundTripsTransitionCaptionsAndVolume() {
        val config = EffectsConfig(
            transition = TransitionSpec(TransitionType.VORONOI, 2.5),
            captions = CaptionConfig(enabled = true, fontFamily = "Serif", fontSizeSp = 32, color = "#FFE45E", position = "top"),
            volume = 0.75
        )
        val encoded = encodeEffectsConfig(config)
        val decoded = parseEffectsConfig(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun parseEffectsConfigToleratesLegacyAndMalformedInput() {
        // Legacy color-grading JSON written by the old editor must not break parsing.
        val legacy = parseEffectsConfig("""{"colorbalance":{"rs":0.1},"brightness":0.2}""")
        assertNull(legacy.transition)
        assertEquals(1.0, legacy.volume)

        assertEquals(EffectsConfig(), parseEffectsConfig(null))
        assertEquals(EffectsConfig(), parseEffectsConfig(""))
        assertEquals(EffectsConfig(), parseEffectsConfig("not json at all"))
    }

    @Test
    fun buildWordTimingsDistributesWordsEvenly() {
        val timings = buildWordTimings("one two three four", 8.0)
        assertEquals(4, timings.size)
        assertEquals(0.0, timings[0].start)
        assertEquals(2.0, timings[0].end, 0.0001)
        assertEquals(6.0, timings[3].start, 0.0001)
        assertEquals(8.0, timings[3].end, 0.0001)
        assertTrue(buildWordTimings("", 5.0).isEmpty())
    }

    @Test
    fun calculatedDurationIsFurthestClipEnd() {
        val movie = Film("m1", "T", 0.0, FilmStatus.DRAFT, 0)
        val track = Track("t1", "m1", TrackType.VIDEO, 0)
        val timeline = FilmTimeline(
            movie = movie,
            tracks = listOf(
                TrackWithClips(
                    track,
                    listOf(
                        Clip("c1", "t1", "a1", 0f, 0f, 5f, "{}"),
                        Clip("c2", "t1", "a2", 10f, 1f, 4f, "{}") // ends at 13
                    )
                )
            )
        )
        assertEquals(13.0, timeline.calculatedDuration(), 0.001)
        assertEquals(0.0, FilmTimeline(movie, emptyList()).calculatedDuration())
    }

    @Test
    fun aspectRatioParsingFallsBackTo16x9() {
        assertEquals(16f / 9f, aspectRatioToFloat("16:9"))
        assertEquals(1f, aspectRatioToFloat("1:1"))
        assertEquals(9f / 16f, aspectRatioToFloat("9:16"))
        assertEquals(16f / 9f, aspectRatioToFloat("garbage"))
        assertEquals(16f / 9f, aspectRatioToFloat("0:0"))
    }

    @Test
    fun generationSetupResolvesWanModelPredictably() {
        // Prompt only -> T2V.
        assertEquals("t2v", GenerationSetup(kind = "video", prompt = "p").resolveVideoModelKind())
        // Start image -> I2V.
        assertEquals("i2v", GenerationSetup(kind = "video", prompt = "p", imageUrl = "u").resolveVideoModelKind())
        // Reference images / characters / scenes -> R2V (takes precedence).
        assertEquals(
            "r2v",
            GenerationSetup(kind = "video", prompt = "p", imageUrl = "u", referenceImages = listOf("r")).resolveVideoModelKind()
        )
        assertEquals("r2v", GenerationSetup(kind = "video", characterIds = listOf("c")).resolveVideoModelKind())
        assertEquals("r2v", GenerationSetup(kind = "video", sceneIds = listOf("s")).resolveVideoModelKind())
        // Non-video kinds pass through.
        assertEquals("image", GenerationSetup(kind = "image").resolveVideoModelKind())
    }

    @Test
    fun descriptionOnlyAssetsAreDetectedByBlankMedia() {
        val base = Asset(
            id = "a", type = AssetType.VIDEO, ossUrl = "", durationSeconds = 5.0,
            movieId = null, tags = emptyList(), aiPrompt = null, description = "shot"
        )
        assertTrue(base.isDescriptionOnly)
        assertTrue(!base.copy(ossUrl = "https://oss/x.mp4").isDescriptionOnly)
    }

    @Test
    fun movieStatusesCoverTheProductionLifecycle() {
        // The user can move a movie from DRAFT into several other statuses.
        val names = FilmStatus.entries.map { it.name }
        assertTrue(names.containsAll(listOf("DRAFT", "IN_PRODUCTION", "RENDERING", "REVIEW", "COMPLETED", "ARCHIVED")))
        assertEquals("In production", FilmStatus.IN_PRODUCTION.displayName())
    }
}
