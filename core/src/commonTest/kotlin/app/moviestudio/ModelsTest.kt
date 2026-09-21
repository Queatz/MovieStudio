package app.moviestudio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun visualAtMapsTexturedTransitionsToTheirPrimitives() {
        // Halfway through the window, each textured transition cross-fades in (alpha = progress)
        // while its own primitive resolves from strongest (1 - progress) toward crisp/clean.
        val pixelate = TransitionSpec(TransitionType.PIXELATE).visualAt(0.25f)
        assertEquals(0.25f, pixelate.alpha, 0.0001f)
        assertEquals(0.75f, pixelate.pixelateFraction, 0.0001f)
        assertEquals(0f, pixelate.noiseFraction, 0.0001f)
        assertEquals(0f, pixelate.voronoiFraction, 0.0001f)

        val noise = TransitionSpec(TransitionType.NOISE).visualAt(0.25f)
        assertEquals(0.25f, noise.alpha, 0.0001f)
        assertEquals(0.75f, noise.noiseFraction, 0.0001f)
        assertEquals(0f, noise.pixelateFraction, 0.0001f)
        assertEquals(0f, noise.voronoiFraction, 0.0001f)

        val voronoi = TransitionSpec(TransitionType.VORONOI).visualAt(0.25f)
        assertEquals(0.25f, voronoi.alpha, 0.0001f)
        assertEquals(0.75f, voronoi.voronoiFraction, 0.0001f)
        assertEquals(0f, voronoi.pixelateFraction, 0.0001f)
        assertEquals(0f, voronoi.noiseFraction, 0.0001f)

        // A plain ALPHA fade never touches the textured primitives.
        val alpha = TransitionSpec(TransitionType.ALPHA).visualAt(0.4f)
        assertEquals(0.4f, alpha.alpha, 0.0001f)
        assertEquals(0f, alpha.pixelateFraction, 0.0001f)
        assertEquals(0f, alpha.noiseFraction, 0.0001f)
        assertEquals(0f, alpha.voronoiFraction, 0.0001f)

        // Fully settled (progress 1): every textured transition is crisp/clean and fully opaque.
        for (type in listOf(TransitionType.PIXELATE, TransitionType.NOISE, TransitionType.VORONOI)) {
            val settled = TransitionSpec(type).visualAt(1f)
            assertEquals(1f, settled.alpha, 0.0001f)
            assertEquals(0f, settled.pixelateFraction, 0.0001f)
            assertEquals(0f, settled.noiseFraction, 0.0001f)
            assertEquals(0f, settled.voronoiFraction, 0.0001f)
        }
        // NONE always yields the identity visual (no effect).
        assertEquals(NO_TRANSITION, TransitionSpec(TransitionType.NONE).visualAt(0.5f))
    }

    @Test
    fun visualAtMapsCircleAndVignetteToTheirRevealPrimitives() {
        // CIRCLE is a pure hard circular reveal that grows with progress at full opacity (no fade).
        val circle = TransitionSpec(TransitionType.CIRCLE).visualAt(0.3f)
        assertEquals(1f, circle.alpha, 0.0001f)
        assertEquals(0.3f, circle.revealRadiusFraction, 0.0001f)
        assertEquals(1f, circle.vignetteRevealFraction, 0.0001f)

        // VIGNETTE grows its aspect-matched soft oval (vignetteRevealFraction = progress) and ALSO
        // exposes an alpha = progress cross-fade so the default renderer can fall back to a plain
        // fade while the WebGL / FFmpeg renderers draw the real oval iris.
        val half = TransitionSpec(TransitionType.VIGNETTE).visualAt(0.5f)
        assertEquals(0.5f, half.alpha, 0.0001f)
        assertEquals(0.5f, half.vignetteRevealFraction, 0.0001f)
        // It is neither a circle nor a slide, and leaves the textured primitives untouched.
        assertEquals(1f, half.revealRadiusFraction, 0.0001f)
        assertEquals(0f, half.translateXFraction, 0.0001f)
        assertEquals(0f, half.pixelateFraction, 0.0001f)
        assertEquals(0f, half.noiseFraction, 0.0001f)
        assertEquals(0f, half.voronoiFraction, 0.0001f)

        // At the very start nothing is revealed and the clip is transparent; fully settled it is
        // opaque and fully revealed (no mask).
        val start = TransitionSpec(TransitionType.VIGNETTE).visualAt(0f)
        assertEquals(0f, start.alpha, 0.0001f)
        assertEquals(0f, start.vignetteRevealFraction, 0.0001f)
        val settled = TransitionSpec(TransitionType.VIGNETTE).visualAt(1f)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(1f, settled.vignetteRevealFraction, 0.0001f)

        // The new type is offered in the transition picker with a friendly label.
        assertEquals("Vignette", TransitionType.VIGNETTE.displayName())
    }

    @Test
    fun volumeEnvelopeInterpolatesLinearlyBetweenKeyframes() {
        val config = EffectsConfig(
            volume = 0.8,
            volumeKeyframes = listOf(
                VolumePoint(time = 2.0, volume = 1.0),
                VolumePoint(time = 6.0, volume = 0.0)
            )
        )
        // Before the first keyframe and after the last one the envelope holds its edge values.
        assertEquals(1.0, config.volumeAt(0.0), 0.0001)
        assertEquals(0.0, config.volumeAt(9.0), 0.0001)
        // Between keyframes the gain fades linearly.
        assertEquals(1.0, config.volumeAt(2.0), 0.0001)
        assertEquals(0.5, config.volumeAt(4.0), 0.0001)
        assertEquals(0.25, config.volumeAt(5.0), 0.0001)
        assertEquals(0.0, config.volumeAt(6.0), 0.0001)
    }

    @Test
    fun volumeEnvelopeFallsBackToFlatVolumeAndSortsKeyframes() {
        // No keyframes: the flat volume applies everywhere.
        assertEquals(0.75, EffectsConfig(volume = 0.75).volumeAt(3.0), 0.0001)
        // Unsorted keyframes are handled (the editor may hand them over in any order).
        val unsorted = EffectsConfig(
            volumeKeyframes = listOf(
                VolumePoint(time = 4.0, volume = 2.0),
                VolumePoint(time = 0.0, volume = 0.0)
            )
        )
        assertEquals(1.0, unsorted.volumeAt(2.0), 0.0001)
    }

    @Test
    fun effectsConfigRoundTripsVolumeKeyframesAndStaysBackwardCompatible() {
        val config = EffectsConfig(
            volume = 1.0,
            volumeKeyframes = listOf(VolumePoint(0.0, 0.0), VolumePoint(1.5, 1.2))
        )
        assertEquals(config, parseEffectsConfig(encodeEffectsConfig(config)))
        // Clips saved before the envelope existed parse with no keyframes (flat volume).
        val legacy = parseEffectsConfig("""{"volume":0.6}""")
        assertTrue(legacy.volumeKeyframes.isEmpty())
        assertEquals(0.6, legacy.volumeAt(10.0), 0.0001)
    }

    @Test
    fun clipCarriesAudioForAudioTracksAndVideoAssetsOnly() {
        // Every clip on an audio track carries audio, whatever the asset type (or even none yet).
        for (trackType in listOf(TrackType.MUSIC, TrackType.VOICE, TrackType.EFFECTS)) {
            assertTrue(clipCarriesAudio(trackType, AssetType.AUDIO))
            assertTrue(clipCarriesAudio(trackType, AssetType.VIDEO))
            assertTrue(clipCarriesAudio(trackType, null))
        }
        // On the video track only video media carries audio; images and text cards do not.
        assertTrue(clipCarriesAudio(TrackType.VIDEO, AssetType.VIDEO))
        assertFalse(clipCarriesAudio(TrackType.VIDEO, AssetType.IMAGE))
        assertFalse(clipCarriesAudio(TrackType.VIDEO, AssetType.TEXT))
        assertFalse(clipCarriesAudio(TrackType.VIDEO, null))
    }

    @Test
    fun trackVolumeBoostDoublesVoiceOnly() {
        assertEquals(2.0, VOICE_VOLUME_BOOST, 0.0001)
        assertEquals(VOICE_VOLUME_BOOST, trackVolumeBoost(TrackType.VOICE), 0.0001)
        assertEquals(1.0, trackVolumeBoost(TrackType.MUSIC), 0.0001)
        assertEquals(1.0, trackVolumeBoost(TrackType.EFFECTS), 0.0001)
        assertEquals(1.0, trackVolumeBoost(TrackType.VIDEO), 0.0001)
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
        val movie = Movie("m1", "T", 0.0, MovieStatus.DRAFT, 0)
        val track = Track("t1", "m1", TrackType.VIDEO, 0)
        val timeline = MovieTimeline(
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
        assertEquals(0.0, MovieTimeline(movie, emptyList()).calculatedDuration())
    }

    @Test
    fun calculatedDurationCountsNotesPinnedPastTheLastClip() {
        val movie = Movie("m1", "T", 0.0, MovieStatus.DRAFT, 0)
        val track = Track("t1", "m1", TrackType.VIDEO, 0)
        val tracks = listOf(
            TrackWithClips(track, listOf(Clip("c1", "t1", "a1", 0f, 0f, 5f, "{}"))) // ends at 5
        )

        // A note pinned past the last clip extends the timeline length to the note's position.
        val withLateNote = MovieTimeline(
            movie = movie,
            tracks = tracks,
            notes = listOf(TimelineNote("n1", "m1", atSeconds = 12.0, text = "climax"))
        )
        assertEquals(12.0, withLateNote.calculatedDuration(), 0.001)

        // A note before the last clip does not shorten the timeline (clips still win).
        val withEarlyNote = MovieTimeline(
            movie = movie,
            tracks = tracks,
            notes = listOf(TimelineNote("n2", "m1", atSeconds = 2.0, text = "setup"))
        )
        assertEquals(5.0, withEarlyNote.calculatedDuration(), 0.001)

        // Notes drive the length even when there are no clips at all.
        val notesOnly = MovieTimeline(
            movie = movie,
            tracks = emptyList(),
            notes = listOf(TimelineNote("n3", "m1", atSeconds = 8.0, text = "outline"))
        )
        assertEquals(8.0, notesOnly.calculatedDuration(), 0.001)
    }

    @Test
    fun bridgedClipEndHoldsPreviousClipAcrossSubFrameGaps() {
        // A clip's natural end is timelineStart + (trimOut - trimIn).
        val a = Clip("a", "t1", "a1", timelineStart = 0f, trimIn = 0f, trimOut = 2f, effectsConfig = "{}")
        assertEquals(2f, a.timelineEnd(), 0.0001f)

        // Exactly adjacent: no gap to bridge, so the end stays the natural end.
        val bAdjacent = Clip("b", "t1", "a2", timelineStart = 2f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}")
        assertEquals(2f, bridgedClipEnd(a, listOf(a, bAdjacent)), 0.0001f)

        // A sub-frame sliver (<= MAX_BRIDGE_GAP_SECONDS) is bridged: hold `a` until `b` begins.
        val bSliver = Clip("b", "t1", "a2", timelineStart = 2.02f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}")
        assertEquals(2.02f, bridgedClipEnd(a, listOf(a, bSliver)), 0.0001f)

        // A gap larger than the threshold is a deliberate gap and is left as the natural end.
        val bFarGap = Clip("b", "t1", "a2", timelineStart = 2.5f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}")
        assertEquals(2f, bridgedClipEnd(a, listOf(a, bFarGap)), 0.0001f)

        // The last clip on a track (no following clip) keeps its natural end.
        assertEquals(2f, bridgedClipEnd(a, listOf(a)), 0.0001f)

        // An overlapping later clip (starts before `a` ends) never shortens `a`.
        val overlapping = Clip("b", "t1", "a2", timelineStart = 1.5f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}")
        assertEquals(2f, bridgedClipEnd(a, listOf(a, overlapping)), 0.0001f)

        // The NEAREST following clip's start wins when several follow within the window.
        val near = Clip("near", "t1", "a3", timelineStart = 2.03f, trimIn = 0f, trimOut = 1f, effectsConfig = "{}")
        val far = Clip("far", "t1", "a4", timelineStart = 2.04f, trimIn = 0f, trimOut = 1f, effectsConfig = "{}")
        assertEquals(2.03f, bridgedClipEnd(a, listOf(a, far, near)), 0.0001f)
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
        // A video used as the start frame (its last frame is extracted) -> I2V, just like a still
        // start image.
        assertEquals(
            "i2v",
            GenerationSetup(kind = "video", prompt = "p", startFrameVideoUrl = "clip.mp4").resolveVideoModelKind()
        )
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
    fun assetMovieAssociationAndDisassociation() {
        val associated = Asset(
            id = "a", type = AssetType.IMAGE, ossUrl = "https://oss/x.png", durationSeconds = 5.0,
            movieId = "movie-1", tags = emptyList(), aiPrompt = null
        )
        // Only the matching open movie counts as an association (drives remove / hides add).
        assertTrue(associated.isAssociatedWithMovie("movie-1"))
        assertFalse(associated.isAssociatedWithMovie("movie-2"))
        assertFalse(associated.isAssociatedWithMovie(null))
        // Clearing movieId leaves the asset in the global library (disassociated).
        val global = associated.copy(movieId = null)
        assertFalse(global.isAssociatedWithMovie("movie-1"))
        assertNull(global.movieId)
        // Moving to another movie replaces the previous association (assets belong to one movie).
        val moved = associated.copy(movieId = "movie-2")
        assertFalse(moved.isAssociatedWithMovie("movie-1"))
        assertTrue(moved.isAssociatedWithMovie("movie-2"))
        // Round-trip keeps a cleared association as null (not the previous movie id).
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(Asset.serializer(), json.encodeToString(Asset.serializer(), global))
        assertNull(decoded.movieId)
    }

    @Test
    fun textAssetsDistinguishPlaceholdersFromRenderedTextElements() {
        val text = Asset(
            id = "t", type = AssetType.TEXT, ossUrl = "", durationSeconds = 5.0,
            movieId = null, tags = emptyList(), aiPrompt = null, description = "Chapter One"
        )
        // Defaults to a placeholder (backward compatible with dropped/described text).
        assertTrue(text.isPlaceholder)
        assertTrue(text.isPlaceholderAsset)
        assertFalse(text.isTextElement)
        // Turning the placeholder flag off makes it a first-class, rendered text element.
        val element = text.copy(isPlaceholder = false)
        assertFalse(element.isPlaceholderAsset)
        assertTrue(element.isTextElement)
        // Non-text description-only assets are always placeholders, never text elements.
        val imagePlaceholder = text.copy(type = AssetType.IMAGE, isPlaceholder = false)
        assertFalse(imagePlaceholder.isTextElement)
        assertTrue(imagePlaceholder.isDescriptionOnly)
    }

    @Test
    fun textConfigRoundTripsInEffectsAndLegacyClipsDecodeWithoutIt() {
        val config = EffectsConfig(
            text = TextConfig(color = "#FF0000", fontFamily = "Serif", fontSizeSp = 60, backgroundColor = "#8000FF00")
        )
        assertEquals(config, parseEffectsConfig(encodeEffectsConfig(config)))
        // Clips saved before text styling existed parse with no text config (null).
        assertNull(parseEffectsConfig("""{"volume":1.0}""").text)
        // A brand-new text config carries sensible, fully-transparent-background defaults.
        assertEquals(TRANSPARENT_COLOR, TextConfig().backgroundColor)
    }

    @Test
    fun assetPlaceholderFlagRoundTripsAndLegacyAssetsDefaultToPlaceholder() {
        val json = Json { ignoreUnknownKeys = true }
        val element = Asset(
            id = "t1", type = AssetType.TEXT, ossUrl = "", durationSeconds = 5.0,
            movieId = "m1", tags = listOf("text"), aiPrompt = "Title", description = "Title",
            isPlaceholder = false
        )
        val decoded = json.decodeFromString(Asset.serializer(), json.encodeToString(Asset.serializer(), element))
        assertEquals(element, decoded)
        assertFalse(decoded.isPlaceholder)
        // Assets saved before the placeholder flag existed decode as placeholders (no crash).
        val legacy = json.decodeFromString(
            Asset.serializer(),
            """{"id":"t2","type":"TEXT","ossUrl":"","durationSeconds":0.0,"movieId":null,"tags":[],"aiPrompt":null}"""
        )
        assertTrue(legacy.isPlaceholder)
    }

    @Test
    fun movieStatusesCoverTheProductionLifecycle() {
        // The user can move a movie from DRAFT into several other statuses.
        val names = MovieStatus.entries.map { it.name }
        assertTrue(names.containsAll(listOf("DRAFT", "IN_PRODUCTION", "RENDERING", "REVIEW", "COMPLETED", "ARCHIVED")))
        assertEquals("In production", MovieStatus.IN_PRODUCTION.displayName())
    }

    @Test
    fun movieDescriptionRoundTripsAndLegacyMoviesDecodeWithBlankDescription() {
        val json = Json { ignoreUnknownKeys = true }
        // A movie's multi-line description survives a serialization round-trip.
        val movie = Movie(
            id = "m1", title = "Heist", totalDuration = 42.0, status = MovieStatus.IN_PRODUCTION,
            createdAt = 123L, aspectRatio = "16:9",
            description = "A crew plans one\nlast big score."
        )
        val decoded = json.decodeFromString(Movie.serializer(), json.encodeToString(Movie.serializer(), movie))
        assertEquals(movie, decoded)
        assertEquals("A crew plans one\nlast big score.", decoded.description)
        // Movies saved before the description field existed decode with a blank description.
        val legacy = json.decodeFromString(
            Movie.serializer(),
            """{"id":"m2","title":"Old","totalDuration":0.0,"status":"DRAFT","createdAt":0}"""
        )
        assertEquals("", legacy.description)
    }

    @Test
    fun movieLastGenerationSettingsRoundTripAndLegacyMoviesDecodeAsNull() {
        val json = Json { ignoreUnknownKeys = true }
        // A movie's remembered last-used image/video/voice generation settings survive a round-trip.
        val movie = Movie(
            id = "m1", title = "Heist", totalDuration = 42.0, status = MovieStatus.IN_PRODUCTION,
            createdAt = 123L, aspectRatio = "16:9",
            lastImageResolution = "1328*1328", lastImageModel = "wan3.0-image-pro",
            lastVideoResolution = "1280*720",
            lastVoice = "Ethan",
            lastStyleId = "style-pretty-anime"
        )
        val decoded = json.decodeFromString(Movie.serializer(), json.encodeToString(Movie.serializer(), movie))
        assertEquals(movie, decoded)
        assertEquals("1328*1328", decoded.lastImageResolution)
        assertEquals("wan3.0-image-pro", decoded.lastImageModel)
        assertEquals("1280*720", decoded.lastVideoResolution)
        assertEquals("Ethan", decoded.lastVoice)
        assertEquals("style-pretty-anime", decoded.lastStyleId)
        // Movies saved before these fields existed decode with them null (no crash).
        val legacy = json.decodeFromString(
            Movie.serializer(),
            """{"id":"m2","title":"Old","totalDuration":0.0,"status":"DRAFT","createdAt":0}"""
        )
        assertNull(legacy.lastImageResolution)
        assertNull(legacy.lastImageModel)
        assertNull(legacy.lastVideoResolution)
        assertNull(legacy.lastVoice)
        assertNull(legacy.lastStyleId)
    }

    @Test
    fun characterMainLanguageAndVoiceIdRoundTripsAndLegacyCharactersDecodeBlank() {
        val json = Json { ignoreUnknownKeys = true }
        val character = Character(
            id = "c1",
            name = "Mira",
            description = "A captain",
            mainLanguage = "Mandarin Chinese",
            voiceId = "Cherry",
            movieId = "m1",
            createdAt = 42L
        )
        val decoded = json.decodeFromString(
            Character.serializer(),
            json.encodeToString(Character.serializer(), character)
        )
        assertEquals(character, decoded)
        assertEquals("Mandarin Chinese", decoded.mainLanguage)
        assertEquals("Cherry", decoded.voiceId)

        // Characters saved before mainLanguage/voiceId existed decode with blank defaults.
        val legacy = json.decodeFromString(
            Character.serializer(),
            """{"id":"c2","name":"Old","description":"veteran","referenceImages":[]}"""
        )
        assertEquals("", legacy.mainLanguage)
        assertEquals("", legacy.voiceId)
    }

    @Test
    fun visualStyleAndAssetStyleIdRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val style = VisualStyle(
            id = "s1",
            name = "Pretty Anime",
            style = "semi-realistic cute/beautiful anime with faint outlines",
            movieId = "m1",
            createdAt = 42L
        )
        val decodedStyle = json.decodeFromString(VisualStyle.serializer(), json.encodeToString(VisualStyle.serializer(), style))
        assertEquals(style, decodedStyle)

        val asset = Asset(
            id = "a1", type = AssetType.IMAGE, ossUrl = "https://oss/x.png", durationSeconds = 5.0,
            movieId = "m1", tags = emptyList(), aiPrompt = "a meadow", styleId = "s1"
        )
        val decodedAsset = json.decodeFromString(Asset.serializer(), json.encodeToString(Asset.serializer(), asset))
        assertEquals("s1", decodedAsset.styleId)

        val setup = GenerationSetup(kind = "image", prompt = "a meadow", styleId = "s1")
        val decodedSetup = json.decodeFromString(
            GenerationSetup.serializer(),
            json.encodeToString(GenerationSetup.serializer(), setup)
        )
        assertEquals("s1", decodedSetup.styleId)

        // Legacy assets/setups without styleId decode cleanly.
        val legacyAsset = json.decodeFromString(
            Asset.serializer(),
            """{"id":"a2","type":"IMAGE","ossUrl":"","durationSeconds":1.0,"movieId":null,"tags":[],"aiPrompt":null}"""
        )
        assertNull(legacyAsset.styleId)
        val legacySetup = json.decodeFromString(
            GenerationSetup.serializer(),
            """{"kind":"image","prompt":"hi"}"""
        )
        assertNull(legacySetup.styleId)
    }

    @Test
    fun voicesUsedInMovieReturnsDistinctMostRecentFirst() {
        fun voiceAsset(id: String, movieId: String?, voice: String?, createdAt: Long) = Asset(
            id = id,
            type = AssetType.VOICE,
            ossUrl = "https://oss/$id.mp3",
            durationSeconds = 2.0,
            movieId = movieId,
            tags = emptyList(),
            aiPrompt = "hi",
            voice = voice,
            createdAt = createdAt
        )
        val assets = listOf(
            voiceAsset("a1", "m1", "Cherry", createdAt = 10),
            voiceAsset("a2", "m1", "Ethan", createdAt = 30),
            voiceAsset("a3", "m1", "Cherry", createdAt = 20), // duplicate of Cherry, older than Ethan
            voiceAsset("a4", "m1", null, createdAt = 40), // no voice stored
            voiceAsset("a5", "m1", "  ", createdAt = 50), // blank voice
            voiceAsset("a6", "m2", "Serena", createdAt = 60), // other movie
            Asset( // non-voice asset with a voice field should be ignored
                id = "img", type = AssetType.IMAGE, ossUrl = "", durationSeconds = 1.0,
                movieId = "m1", tags = emptyList(), aiPrompt = null, voice = "Vivian", createdAt = 70
            )
        )
        // Most recent unique voices for m1: Ethan (30) then Cherry (20, de-duped over 10).
        assertEquals(listOf("Ethan", "Cherry"), voicesUsedInMovie(assets, "m1"))
        assertEquals(listOf("Serena"), voicesUsedInMovie(assets, "m2"))
        assertEquals(emptyList(), voicesUsedInMovie(assets, null))
        assertEquals(emptyList(), voicesUsedInMovie(assets, ""))
        assertEquals("Cherry", DEFAULT_VOICE_ID)
    }

    @Test
    fun aiLedgerComputesPerCallCostAndTotals() {
        val ledger = listOf(
            AiLedgerEntry(description = "Refined video prompt", model = "qwen-plus", tokens = 1000, costPerToken = 0.0000004),
            AiLedgerEntry(description = "Generated video (wan3.0-video)", model = "wan3.0-video", tokens = 0, costPerToken = 0.000002)
        )
        // Each entry's USD cost is tokens × the per-token price (0 tokens -> free).
        assertEquals(0.0004, ledger[0].costUsd, 1e-9)
        assertEquals(0.0, ledger[1].costUsd, 1e-9)
        // The ledger totals aggregate every AI call.
        assertEquals(1000L, ledger.totalTokens())
        assertEquals(0.0004, ledger.totalCostUsd(), 1e-9)
        // A freshly created asset has an empty ledger.
        val bare = Asset(
            id = "a", type = AssetType.VIDEO, ossUrl = "", durationSeconds = 1.0,
            movieId = null, tags = emptyList(), aiPrompt = null
        )
        assertTrue(bare.ledger.isEmpty())
        assertEquals(0L, bare.ledger.totalTokens())
        assertEquals(0.0, bare.ledger.totalCostUsd(), 1e-9)
    }

    @Test
    fun assetLedgerRoundTripsAndLegacyAssetsDecodeWithEmptyLedger() {
        val json = Json { ignoreUnknownKeys = true }
        val asset = Asset(
            id = "a1", type = AssetType.VOICE, ossUrl = "https://oss/v.mp3", durationSeconds = 3.0,
            movieId = "m1", tags = listOf("ai-generated"), aiPrompt = "hi",
            ledger = listOf(AiLedgerEntry("Synthesized speech (qwen-tts)", "qwen-tts", 42, 0.0000084))
        )
        val decoded = json.decodeFromString(Asset.serializer(), json.encodeToString(Asset.serializer(), asset))
        assertEquals(asset, decoded)
        // Assets saved before the ledger existed decode with an empty ledger (no crash).
        val legacy = json.decodeFromString(
            Asset.serializer(),
            """{"id":"a2","type":"IMAGE","ossUrl":"","durationSeconds":0.0,"movieId":null,"tags":[],"aiPrompt":null}"""
        )
        assertTrue(legacy.ledger.isEmpty())
    }

    @Test
    fun jobSourceAssetIdRoundTripsAndLegacyJobsDecodeWithNull() {
        val json = Json { ignoreUnknownKeys = true }
        // A job started from an asset carries that asset's id, surviving a round-trip.
        val job = Job(
            id = "j1", movieId = "m1", type = JobType.AI_GEN, status = JobStatus.PENDING,
            payload = "{}", resultUrl = null, label = "Video: p", createdAt = 7L,
            sourceAssetId = "asset-42"
        )
        val decoded = json.decodeFromString(Job.serializer(), json.encodeToString(Job.serializer(), job))
        assertEquals(job, decoded)
        assertEquals("asset-42", decoded.sourceAssetId)
        // Jobs saved before the field existed decode with a null sourceAssetId (no crash).
        val legacy = json.decodeFromString(
            Job.serializer(),
            """{"id":"j2","movieId":"m1","type":"FFMPEG_RENDER","status":"RUNNING","payload":"{}","resultUrl":null}"""
        )
        assertNull(legacy.sourceAssetId)
    }

    @Test
    fun movieTimelineViewRoundTripsAndLegacyMoviesDecodeWithDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        // A movie remembers its last timeline scroll offset and zoom, surviving a round-trip.
        val movie = Movie(
            id = "m1", title = "My Movie", totalDuration = 12.0, status = MovieStatus.DRAFT,
            createdAt = 5L, lastTimelineOffset = 8.5f, lastTimelineZoom = 64f
        )
        val decoded = json.decodeFromString(Movie.serializer(), json.encodeToString(Movie.serializer(), movie))
        assertEquals(movie, decoded)
        assertEquals(8.5f, decoded.lastTimelineOffset)
        assertEquals(64f, decoded.lastTimelineZoom)
        // Movies saved before these fields existed decode with the default view (offset 0, zoom 20).
        val legacy = json.decodeFromString(
            Movie.serializer(),
            """{"id":"m2","title":"Old","totalDuration":0.0,"status":"DRAFT","createdAt":1}"""
        )
        assertEquals(Movie.DEFAULT_TIMELINE_OFFSET, legacy.lastTimelineOffset)
        assertEquals(Movie.DEFAULT_TIMELINE_ZOOM, legacy.lastTimelineZoom)
    }

    @Test
    fun imageModelLookupFallsBackToTheDefaultModel() {
        // A known id resolves to its model (case-insensitively).
        assertEquals(IMAGE_MODEL_WAN_PRO, imageModelById("wan3.0-image-pro"))
        assertEquals(IMAGE_MODEL_WAN, imageModelById("WAN3.0-IMAGE"))
        // Blank / null / unknown ids fall back to the configured default (Qwen Image 3.0).
        assertEquals(DEFAULT_IMAGE_MODEL_ID, imageModelById("").id)
        assertEquals(DEFAULT_IMAGE_MODEL_ID, imageModelById(null).id)
        assertEquals(DEFAULT_IMAGE_MODEL_ID, imageModelById("no-such-model").id)
        assertEquals(IMAGE_MODEL_QWEN.id, DEFAULT_IMAGE_MODEL_ID)
    }

    @Test
    fun generationSetupModelRoundTripsAndDefaultsToBlank() {
        // The model field defaults to blank (server picks its default) and survives a round-trip.
        assertEquals("", GenerationSetup(kind = "image").model)
        val setup = GenerationSetup(kind = "image", prompt = "p", model = "wan3.0-image-pro")
        val decoded = Json.decodeFromString(GenerationSetup.serializer(), Json.encodeToString(GenerationSetup.serializer(), setup))
        assertEquals(setup, decoded)
        assertEquals("wan3.0-image-pro", decoded.model)
    }

    @Test
    fun resolutionValidationHonorsEachModelsPixelAndSideLimits() {
        // Every preset a model advertises must itself validate for that model.
        for (model in SUPPORTED_IMAGE_MODELS) {
            for (preset in model.presetResolutions) {
                assertTrue(isResolutionValidForModel(preset, model), "$preset should be valid for ${model.id}")
            }
        }
        // Qwen is total-pixel limited (max 2048×2048 = 4.19M px): 2048×2048 fits, 3840×2160 does not.
        assertTrue(isResolutionValidForModel("2048*2048", IMAGE_MODEL_QWEN))
        assertFalse(isResolutionValidForModel("3840*2160", IMAGE_MODEL_QWEN))
        // Wan Pro reaches 4K.
        assertTrue(isResolutionValidForModel("3840*2160", IMAGE_MODEL_WAN_PRO))
        // Sides below the minimum and malformed input are rejected with a reason.
        assertNotNull(validateResolutionForModel("100*100", IMAGE_MODEL_WAN))
        assertNotNull(validateResolutionForModel("not-a-size", IMAGE_MODEL_WAN))
    }

    @Test
    fun presetsAreGroupedByOrientationAndLabeledByAspect() {
        val model = IMAGE_MODEL_QWEN
        // Grouping only returns resolutions of the requested orientation.
        assertTrue(model.presetsFor(ResolutionOrientation.LANDSCAPE).all { resolutionOrientation(it) == ResolutionOrientation.LANDSCAPE })
        assertTrue(model.presetsFor(ResolutionOrientation.PORTRAIT).all { resolutionOrientation(it) == ResolutionOrientation.PORTRAIT })
        assertTrue(model.presetsFor(ResolutionOrientation.SQUARE).all { resolutionOrientation(it) == ResolutionOrientation.SQUARE })
        // Every preset is covered by exactly one orientation group.
        val grouped = ResolutionOrientation.entries.sumOf { model.presetsFor(it).size }
        assertEquals(model.presetResolutions.size, grouped)
        // Aspect labels pick the closest supported ratio.
        assertEquals("16:9", aspectRatioLabelFor("1664*928"))
        assertEquals("9:16", aspectRatioLabelFor("928*1664"))
        assertEquals("1:1", aspectRatioLabelFor("1328*1328"))
    }

    @Test
    fun customResolutionCompletionMatchesTheRequestedAspect() {
        // Completing from a width yields the matching height for 16:9, and vice-versa.
        assertEquals("1920*1080", resolutionForAspect(1920, knownIsWidth = true, aspectRatio = "16:9"))
        assertEquals("1920*1080", resolutionForAspect(1080, knownIsWidth = false, aspectRatio = "16:9"))
        assertEquals("1000*1000", resolutionForAspect(1000, knownIsWidth = true, aspectRatio = "1:1"))
    }

    @Test
    fun wan30VideoSizesIncludeTwoKAndThirtySecondCap() {
        // WAN 3.0 video sizes cover the new 2K tier alongside the existing 480p/720p/1080p sets.
        assertTrue("2560*1440" in SUPPORTED_VIDEO_SIZES)
        assertTrue("1440*2560" in SUPPORTED_VIDEO_SIZES)
        assertTrue("1920*1920" in SUPPORTED_VIDEO_SIZES)
        assertTrue("3360*1440" in SUPPORTED_VIDEO_SIZES)
        assertTrue("1440*3360" in SUPPORTED_VIDEO_SIZES)
        // Shared image size list stays aligned with the video 2K tiers.
        assertTrue("2560*1440" in SUPPORTED_IMAGE_SIZES)
        // Generation length is 2–30 seconds.
        assertEquals(2, MIN_VIDEO_DURATION_SECONDS)
        assertEquals(30, MAX_VIDEO_DURATION_SECONDS)
        assertEquals("wan3.0-video", DEFAULT_VIDEO_MODEL_ID)
        // Pixel sizes map onto Wan 3.0's resolution/ratio parameters.
        assertEquals("720P", wanVideoResolutionTier("1280*720"))
        assertEquals("1080P", wanVideoResolutionTier("1920*1080"))
        assertEquals("1080P", wanVideoResolutionTier("2560*1440"))
        assertEquals("480P", wanVideoResolutionTier("832*480"))
        assertEquals("16:9", wanVideoRatio("1280*720"))
        assertEquals("9:16", wanVideoRatio("720*1280"))
        assertEquals("1:1", wanVideoRatio("960*960"))
        assertEquals("adaptive", wanVideoRatio("1680*720"))
    }

    @Test
    fun wanRequestedDurationUsesSmartDurationSentinel() {
        assertEquals(
            WAN_SMART_DURATION_SECONDS,
            GenerationSetup(kind = "video", smartDuration = true).wanRequestedDuration()
        )
        // Smart Duration wins over a slider value that would otherwise be sent.
        assertEquals(
            WAN_SMART_DURATION_SECONDS,
            GenerationSetup(kind = "video", durationSeconds = 12.0, smartDuration = true).wanRequestedDuration()
        )
        assertEquals(5, GenerationSetup(kind = "video").wanRequestedDuration())
        assertEquals(30, GenerationSetup(kind = "video", durationSeconds = 30.0).wanRequestedDuration())
        assertNull(GenerationSetup(kind = "video", durationSeconds = 1.0).wanRequestedDuration())
    }

    @Test
    fun generationSetupSmartDurationDefaultsFalseAndRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(GenerationSetup.serializer(), """{"kind":"video"}""")
        assertFalse(decoded.smartDuration)
        val encoded = json.encodeToString(GenerationSetup.serializer(), GenerationSetup(kind = "video", smartDuration = true))
        val roundTripped = json.decodeFromString(GenerationSetup.serializer(), encoded)
        assertTrue(roundTripped.smartDuration)
    }

    @Test
    fun voiceCloneSpeakingPromptIsAboutTwiceTheOriginalLength() {
        val originalWords = 22
        val words = DEFAULT_VOICE_CLONE_SPEAKING_PROMPT.split(Regex("\\s+")).filter { it.isNotBlank() }
        assertTrue(words.size >= originalWords * 2 - 4, "expected ~2x original ($originalWords words), got ${words.size}")
        assertTrue(DEFAULT_VOICE_CLONE_SPEAKING_PROMPT.contains("morning sun"))
        assertTrue(DEFAULT_VOICE_CLONE_SPEAKING_PROMPT.contains("river"))
    }

    @Test
    fun voiceEnrollmentLanguagesCoverModelStudioHintsAndHaveFlags() {
        val codes = VOICE_ENROLLMENT_LANGUAGES.map { it.code }.toSet()
        assertEquals(VOICE_ENROLLMENT_LANGUAGES.size, codes.size)
        assertTrue(
            codes.containsAll(
                listOf("zh", "en", "fr", "de", "ja", "ko", "ru", "pt", "th", "id", "vi", "it", "es", "ms", "fil", "ar")
            )
        )
        VOICE_ENROLLMENT_LANGUAGES.forEach { language ->
            assertTrue(language.englishName.isNotBlank())
            assertTrue(language.nativeName.isNotBlank())
            assertTrue(language.flag.isNotBlank())
        }
        assertEquals(DEFAULT_VOICE_ENROLLMENT_LANGUAGE, VOICE_ENROLLMENT_LANGUAGES.first())
    }

    @Test
    fun voiceEnrollmentLanguageSearchMatchesEnglishAndNativeNames() {
        assertEquals(VOICE_ENROLLMENT_LANGUAGES, filterVoiceEnrollmentLanguages(""))
        assertEquals(VOICE_ENROLLMENT_LANGUAGES, filterVoiceEnrollmentLanguages("   "))
        assertEquals(listOf("ja"), filterVoiceEnrollmentLanguages("japan").map { it.code })
        assertEquals(listOf("ja"), filterVoiceEnrollmentLanguages("日本語").map { it.code })
        assertEquals(listOf("zh"), filterVoiceEnrollmentLanguages("中文").map { it.code })
        assertEquals(listOf("es"), filterVoiceEnrollmentLanguages("español").map { it.code })
        assertEquals(listOf("de"), filterVoiceEnrollmentLanguages("GERMAN").map { it.code })
        assertTrue(filterVoiceEnrollmentLanguages("zzzz-nope").isEmpty())
    }

    @Test
    fun voiceCloneTranslationPromptAndUnwrap() {
        val french = VOICE_ENROLLMENT_LANGUAGES.first { it.code == "fr" }
        val prompt = buildVoiceCloneTranslationPrompt("Hello valley", french)
        assertTrue(prompt.contains("French"))
        assertTrue(prompt.contains("Français"))
        assertTrue(prompt.contains("Hello valley"))
        assertEquals("Bonjour", unwrapTranslatedSpeakingPrompt("  \"Bonjour\"  "))
        assertEquals("Bonjour", unwrapTranslatedSpeakingPrompt("“Bonjour”"))
        assertEquals("Bonjour", unwrapTranslatedSpeakingPrompt("Bonjour"))
    }
}
