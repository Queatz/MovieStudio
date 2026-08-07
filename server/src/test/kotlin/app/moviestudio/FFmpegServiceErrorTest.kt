package app.moviestudio

import app.moviestudio.service.FFmpegService
import app.moviestudio.service.MediaUtil
import java.io.File
import kotlin.test.*

class FFmpegServiceErrorTest {

    @Test
    fun summarizeSkipsProgressLinesAndKeepsTheError() {
        val logTail = listOf(
            "frame=  120 fps= 30 q=28.0 size=    2048kB time=00:00:04.00 bitrate=4194.3kbits/s speed=1x",
            "[error] Error while decoding stream #2:0: Invalid data found when processing input",
            "Conversion failed!"
        )

        val summary = FFmpegService.summarizeFfmpegError(logTail)

        assertTrue(summary.startsWith("FFmpeg output: "), "should be prefixed for context")
        assertTrue(summary.contains("Conversion failed!"), "keeps the final error line")
        assertTrue(summary.contains("Invalid data found"), "keeps the underlying reason")
        assertFalse(summary.contains("frame="), "drops noisy progress lines")
    }

    @Test
    fun summarizeFallsBackWhenNothingMeaningful() {
        val summary = FFmpegService.summarizeFfmpegError(emptyList())

        assertEquals("See the server log for the full FFmpeg output.", summary)
    }

    @Test
    fun summarizeIsTruncatedForOverlongOutput() {
        val longLine = "e".repeat(2000)

        val summary = FFmpegService.summarizeFfmpegError(listOf(longLine))

        // Prefix ("FFmpeg output: ") plus at most 500 chars of the distilled reason.
        assertTrue(summary.length <= "FFmpeg output: ".length + 500)
    }

    @Test
    fun voronoiExprOnlyUsesTheTenAvailableEvalSlots() {
        // FFmpeg's expression evaluator clamps st()/ld() indices to 0..9; any slot >= 10 silently
        // aliases slot 9 and corrupts the result (this was the original bug — the effect rendered a
        // flat color). Guard that the generated expression never references an out-of-range slot.
        val expr = FFmpegService.voronoiGeqExpression(1.0)
        val slots = Regex("""(?:st|ld)\((\d+)""").findAll(expr).map { it.groupValues[1].toInt() }.toList()

        assertTrue(slots.isNotEmpty(), "expression should use st()/ld() slots")
        assertTrue(slots.all { it in 0..9 }, "all eval slots must be within 0..9, found: ${slots.filter { it !in 0..9 }}")
    }

    @Test
    fun voronoiExprMirrorsTheShaderMathAndAnimation() {
        val expr = FFmpegService.voronoiGeqExpression(2.5)

        // Animated cell size: cellPx = max(1, 60 * (1 - progress)), progress = T/dur — matching the
        // WebGL shader's `cellPx = max(1, 60 * voronoiFraction)` with voronoiFraction = 1 - progress.
        assertTrue(expr.contains("max(1,60*(1-min(T/2.5,1)))"), "cellPx must animate with T over the window")
        // The Dave-Hoskins hash22 constants the shader uses for the per-cell random seeds.
        assertTrue(expr.contains("0.1031") && expr.contains("0.1030") && expr.contains("0.0973"), "hash22 constants")
        assertTrue(expr.contains("33.33"), "hash22 mixing constant")
        // Samples the frame at the nearest seed (a true voronoi displacement), clamped to the frame.
        assertTrue(expr.contains("p(clip("), "must sample the frame at the (clamped) nearest seed")
    }

    @Test
    fun voronoiExprSearchesTheFullThreeByThreeNeighbourhood() {
        val expr = FFmpegService.voronoiGeqExpression(1.0)
        // Nine candidate cells (3x3) are compared, exactly like the shader's nested -1..1 loops; each
        // keeps the nearest via `lt(ld(5),ld(0))`.
        val comparisons = Regex("""lt\(ld\(5\),ld\(0\)\)""").findAll(expr).count()
        assertEquals(9, comparisons, "should evaluate all 9 cells of the 3x3 neighbourhood")
    }

    @Test
    fun vignetteExprIsAnAspectMatchedFeatheredOvalRevealMatchingTheShader() {
        val expr = FFmpegService.vignetteAlphaExpression(2.0)

        // Aspect-matched oval: each axis is normalized by its half-extent (W/2, H/2) so the reveal is
        // an ellipse in pixel space, mirroring the WebGL shader's (vPos-0.5)*2 normalized coords.
        assertTrue(expr.contains("(X-W/2)/(W/2)"), "x axis normalized by its half-extent")
        assertTrue(expr.contains("(Y-H/2)/(H/2)"), "y axis normalized by its half-extent")
        assertTrue(expr.contains("hypot("), "elliptical distance via hypot, like the circle mask")
        // Divided by the center-to-corner distance sqrt(2) so it is 0 at the center and 1 at a corner.
        assertTrue(expr.contains("1.41421356"), "normalized by the sqrt(2) corner distance")
        // Animated over the window: the reveal radius grows with progress = min(T/dur, 1) past 1 so
        // the corners finish fully opaque (the *(1+0.15) feather headroom).
        assertTrue(expr.contains("min(T/2.0,1)"), "reveal radius animates with T over the window")
        assertTrue(expr.contains("(1+0.15)"), "reveal grows past 1 so corners end fully opaque")
        // Produces a valid, clamped 0..255 alpha ramp with a soft feathered edge.
        assertTrue(expr.startsWith("clip(") && expr.endsWith(",0,255)"), "clamped to a valid alpha, got: $expr")
    }

    @Test
    fun roundedRectAlphaMaskRoundsCornersAndKeepsTheBodyOpaque() {
        // Captions get their rounded background from a geq alpha mask (drawtext's own box= can only
        // draw square corners). The body of the box keeps the translucent alpha; only the four
        // corner regions are cut away outside the rounding radius.
        val alpha = (0.45 * 255).toInt()
        val expr = FFmpegService.roundedRectAlphaExpression(radius = 12, alpha = alpha)

        assertTrue(expr.contains("hypot("), "corners are rounded via a circular distance test")
        assertTrue(expr.contains("12"), "the corner radius must be embedded in the expression")
        // A pixel outside the corner radius is transparent (0); everything else keeps the box alpha.
        assertTrue(expr.contains(",$alpha,0)"), "a pixel beyond the corner radius is transparent, got: $expr")
        assertTrue(expr.trimEnd().endsWith(",$alpha)"), "pixels off the corners keep the box alpha, got: $expr")
    }

    @Test
    fun roundedRectAlphaRadiusIsClampedToAtLeastOne() {
        // A zero/negative radius would make the corner math degenerate; it is clamped to >= 1 so the
        // generated expression stays valid.
        val expr = FFmpegService.roundedRectAlphaExpression(radius = 0, alpha = 255)
        assertFalse(expr.contains("W/2-0"), "radius 0 must be clamped, not used verbatim, got: $expr")
        assertTrue(expr.contains("W/2-1"), "radius clamps to 1, got: $expr")
    }

    @Test
    fun captionEndIsCutOffWhenTheNextCaptionBegins() {
        // Two overlapping voice clips: caption A runs 0..5s, but caption B starts at 3s. A must be
        // cut off at 3s the instant B begins so the two never render on top of each other (the bug),
        // exactly like the live preview where a newly started voice caption replaces the earlier one.
        val starts = listOf(0.0, 3.0)
        assertEquals(3.0, FFmpegService.captionVisibleEnd(visStart = 0.0, visEnd = 5.0, sortedCaptionStarts = starts))
        // B itself has no later caption, so it keeps its natural end.
        assertEquals(8.0, FFmpegService.captionVisibleEnd(visStart = 3.0, visEnd = 8.0, sortedCaptionStarts = starts))
    }

    @Test
    fun summarizeDetectsSigtermAsMemoryKill() {
        // earlyoom (and similar userspace OOM killers) terminate oversized FFmpeg runs with SIGTERM,
        // which FFmpeg reports as "Exiting normally, received signal 15." Surface that as a clear
        // memory-pressure message instead of a cryptic exit code.
        val summary = FFmpegService.summarizeFfmpegError(
            listOf(
                "[libx264 @ 0x1] kb/s:543.12",
                "Exiting normally, received signal 15."
            )
        )
        assertTrue(summary.contains("memory", ignoreCase = true), summary)
        assertTrue(
            summary.contains("terminated by the system", ignoreCase = true) ||
                summary.contains("SIGTERM", ignoreCase = true),
            summary
        )
    }

    @Test
    fun summarizeDetectsRepeatedSignalsAsMemoryKill() {
        // When earlyoom keeps SIGTERM'ing a stuck multi-GB FFmpeg, the process hard-exits with
        // "Received > 3 system signals" (exit 123) instead of a clean "signal 15" line.
        val summary = FFmpegService.summarizeFfmpegError(
            listOf(
                "[out#0/mp4 @ 0x1] Task finished with error code: -1414092869 (Immediate exit requested)",
                "Received > 3 system signals, hard exiting"
            )
        )
        assertTrue(summary.contains("memory", ignoreCase = true), summary)
        assertTrue(summary.contains("terminated by the system", ignoreCase = true), summary)
    }

    @Test
    fun canConcatComposeTrackAllowsSequentialNonOverlappingClips() {
        val clips = listOf(
            Clip(id = "a", trackId = "t", assetId = "1", timelineStart = 0f, trimIn = 0f, trimOut = 5f, effectsConfig = "{}"),
            Clip(id = "b", trackId = "t", assetId = "2", timelineStart = 5f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}"),
            Clip(
                id = "c", trackId = "t", assetId = "3", timelineStart = 8f, trimIn = 0f, trimOut = 2f,
                effectsConfig = """{"transition":{"type":"ALPHA"}}"""
            )
        )
        assertTrue(FFmpegService.canConcatComposeTrack(clips))
    }

    @Test
    fun canConcatComposeTrackRejectsOverlapsAndSlide() {
        val overlapping = listOf(
            Clip(id = "a", trackId = "t", assetId = "1", timelineStart = 0f, trimIn = 0f, trimOut = 5f, effectsConfig = "{}"),
            Clip(id = "b", trackId = "t", assetId = "2", timelineStart = 4f, trimIn = 0f, trimOut = 3f, effectsConfig = "{}")
        )
        assertFalse(FFmpegService.canConcatComposeTrack(overlapping))

        val slide = listOf(
            Clip(
                id = "s", trackId = "t", assetId = "1", timelineStart = 0f, trimIn = 0f, trimOut = 2f,
                effectsConfig = """{"transition":{"type":"SLIDE"}}"""
            )
        )
        assertFalse(FFmpegService.canConcatComposeTrack(slide))
    }

    @Test
    fun appendConcatTrackEmitsConcatNotPerClipOverlay() {
        // Filter-graph helper (used by unit tests / small graphs): sequential clips become one
        // concat, not N full-timeline overlays.
        val clips = listOf(
            Clip(id = "c1", trackId = "t", assetId = "a1", timelineStart = 0f, trimIn = 0f, trimOut = 2f, effectsConfig = "{}"),
            Clip(id = "c2", trackId = "t", assetId = "a2", timelineStart = 2f, trimIn = 0f, trimOut = 2f, effectsConfig = "{}"),
            Clip(id = "c3", trackId = "t", assetId = "a3", timelineStart = 4f, trimIn = 0f, trimOut = 2f, effectsConfig = "{}")
        )
        val assets = listOf("a1", "a2", "a3").associateWith { id ->
            Asset(
                id = id, type = AssetType.IMAGE, ossUrl = "http://example/$id.png",
                durationSeconds = 2.0, movieId = "m", tags = emptyList(), aiPrompt = null
            )
        }
        val filters = mutableListOf<String>()
        FFmpegService.appendConcatTrack(
            filters = filters,
            trackClips = clips,
            assetsById = assets,
            assetInputMap = mapOf("a1" to 2, "a2" to 3, "a3" to 4),
            totalDuration = 10.0,
            canvasWidth = 640,
            canvasHeight = 360,
            chain = 0,
            outputTag = "v_track_t"
        )
        assertTrue(
            filters.any { it.contains("concat=n=") && it.contains("[v_track_t]") },
            "expected a concat into v_track_t, got:\n${filters.joinToString("\n")}"
        )
        assertTrue(
            filters.any { it.contains("concat=n=4:v=1:a=0") },
            "3 clips + trailing gap should concat n=4, got:\n${filters.joinToString("\n")}"
        )
        assertTrue(filters.none { it.contains("overlay=") })
    }

    @Test
    fun renderTrackViaSegmentFilesKeepsRamFlatAndMatchesDuration() {
        // Real render path: each still is encoded in its OWN FFmpeg process, then concat-demuxed.
        // This is what stops earlyoom from SIGTERM'ing a 40-image slideshow.
        val temp = java.nio.file.Files.createTempDirectory("ms_seg_test_").toFile()
        try {
            val imgA = File(temp, "a.png")
            val imgB = File(temp, "b.png")
            val imgC = File(temp, "c.png")
            // Tiny solid PNGs via ffmpeg lavfi.
            val pngs: List<Pair<File, String>> = listOf(
                imgA to "red",
                imgB to "green",
                imgC to "blue"
            )
            for ((f, color) in pngs) {
                FFmpegService.runFfmpegChecked(
                    listOf(
                        MediaUtil.ffmpegBinary, "-y",
                        "-f", "lavfi", "-i", "color=c=$color:s=320x180:d=0.1",
                        "-frames:v", "1", f.absolutePath
                    ),
                    label = "test-png"
                )
            }
            val clips = listOf(
                Clip(id = "c1", trackId = "t", assetId = "a", timelineStart = 0f, trimIn = 0f, trimOut = 1f, effectsConfig = "{}"),
                Clip(id = "c2", trackId = "t", assetId = "b", timelineStart = 1f, trimIn = 0f, trimOut = 1f, effectsConfig = "{}"),
                Clip(id = "c3", trackId = "t", assetId = "c", timelineStart = 2f, trimIn = 0f, trimOut = 1f, effectsConfig = "{}")
            )
            val assets = mapOf(
                "a" to Asset(id = "a", type = AssetType.IMAGE, ossUrl = "http://x/a.png", durationSeconds = 1.0, movieId = "m", tags = emptyList(), aiPrompt = null),
                "b" to Asset(id = "b", type = AssetType.IMAGE, ossUrl = "http://x/b.png", durationSeconds = 1.0, movieId = "m", tags = emptyList(), aiPrompt = null),
                "c" to Asset(id = "c", type = AssetType.IMAGE, ossUrl = "http://x/c.png", durationSeconds = 1.0, movieId = "m", tags = emptyList(), aiPrompt = null)
            )
            val files: Map<String, File> = mapOf("a" to imgA, "b" to imgB, "c" to imgC)
            val out = FFmpegService.renderTrackViaSegmentFiles(
                tempDir = temp,
                trackId = "t",
                trackClips = clips,
                assetsById = assets,
                downloadedAssets = files,
                totalDuration = 4.0, // 3s clips + 1s trailing black
                canvasWidth = 320,
                canvasHeight = 180
            )
            assertTrue(out.exists() && out.length() > 0L, "track file should exist")
            // Duration ~4s (allow small encoder tolerance).
            val probe = ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", out.absolutePath
            ).redirectErrorStream(true).start()
            val durStr = probe.inputStream.bufferedReader().readText().trim()
            probe.waitFor()
            val dur = durStr.toDoubleOrNull() ?: 0.0
            assertTrue(dur in 3.5..4.5, "expected ~4s track, got $dur")
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun formatAssTimeUsesCentiseconds() {
        assertEquals("0:00:00.00", FFmpegService.formatAssTime(0.0))
        assertEquals("0:00:01.50", FFmpegService.formatAssTime(1.5))
        assertEquals("0:01:01.00", FFmpegService.formatAssTime(61.0))
        assertEquals("1:01:01.23", FFmpegService.formatAssTime(3661.23))
    }

    @Test
    fun assColourIsAabbggrrWithInvertedAlpha() {
        // Opaque white: alpha byte 00, BGR = FF FF FF.
        assertEquals("&H00FFFFFF", FFmpegService.assColour("FFFFFF", alpha = 1.0))
        // Opaque red (#FF0000) -> BGR 00 00 FF.
        assertEquals("&H000000FF", FFmpegService.assColour("FF0000", alpha = 1.0))
        // 45% opaque black matches the preview chip; transparency byte ≈ 0x8C.
        val back = FFmpegService.assColour("000000", alpha = 0.45)
        assertTrue(back.startsWith("&H"), back)
        assertTrue(back.endsWith("000000"), back)
        val alphaByte = back.substring(2, 4).toInt(16)
        assertTrue(alphaByte in 0x80..0x95, "expected ~0x8C transparency for 0.45 opacity, got $alphaByte")
    }

    @Test
    fun collectCaptionEventsChunksWordsAndCutsOverlaps() {
        // 8 words -> two 4-word chunks. A second voice clip that starts mid-way must cut the first
        // caption short so the two never stack (mirrors captionVisibleEnd).
        val words = (0 until 8).map { i ->
            WordTiming(word = "w$i", start = i.toDouble(), end = (i + 1).toDouble())
        }
        val assetA = Asset(
            id = "a", type = AssetType.VOICE, ossUrl = "http://example/a.mp3",
            durationSeconds = 8.0, movieId = "m", tags = emptyList(), aiPrompt = null,
            wordTimings = words
        )
        val assetB = Asset(
            id = "b", type = AssetType.VOICE, ossUrl = "http://example/b.mp3",
            durationSeconds = 4.0, movieId = "m", tags = emptyList(), aiPrompt = null,
            wordTimings = listOf(
                WordTiming("x", 0.0, 1.0),
                WordTiming("y", 1.0, 2.0),
                WordTiming("z", 2.0, 3.0),
                WordTiming("w", 3.0, 4.0)
            )
        )
        val clipA = Clip(
            id = "c1", trackId = "t", assetId = "a",
            timelineStart = 0f, trimIn = 0f, trimOut = 8f,
            effectsConfig = """{"captions":{"enabled":true}}"""
        )
        val clipB = Clip(
            id = "c2", trackId = "t", assetId = "b",
            timelineStart = 2f, trimIn = 0f, trimOut = 4f,
            effectsConfig = """{"captions":{"enabled":true}}"""
        )
        val events = FFmpegService.collectCaptionEvents(
            clips = listOf(clipA, clipB),
            assetsById = mapOf("a" to assetA, "b" to assetB),
            canvasHeight = 720
        )
        // clipA: chunks at 0..4 and 4..8; clipB: chunk at 2..6. After overlap cutting there should
        // still be 3 events, and the first must end when clipB's caption begins at t=2.
        assertEquals(3, events.size)
        val first = events.first { it.text.startsWith("w0") }
        assertEquals(0.0, first.start, absoluteTolerance = 1e-6)
        assertEquals(2.0, first.end, absoluteTolerance = 1e-6)
        assertTrue(events.any { it.text.startsWith("x") })
    }

    @Test
    fun writeAssCaptionsFileEmitsStylesAndDialogue() {
        val dir = kotlin.io.path.createTempDirectory("ass-test").toFile()
        try {
            val ass = java.io.File(dir, "captions.ass")
            val fonts = java.io.File(dir, "fonts").apply { mkdirs() }
            FFmpegService.writeAssCaptionsFile(
                assFile = ass,
                fontsDir = fonts,
                canvasWidth = 1280,
                canvasHeight = 720,
                events = listOf(
                    FFmpegService.CaptionEvent(
                        start = 1.0, end = 2.5, text = "Hello {world}",
                        fontFamily = "Default", fontUrl = "", fontSize = 42,
                        colorHex = "FFFFFF", position = "bottom", bold = true
                    )
                )
            )
            val body = ass.readText()
            assertTrue(body.contains("[Script Info]"), body)
            assertTrue(body.contains("PlayResX: 1280"), body)
            assertTrue(body.contains("PlayResY: 720"), body)
            // Outline-only text style (BorderStyle=1); rounded chip is a separate vector layer.
            assertTrue(body.contains(",1,2,0,5,"), "expected BorderStyle=1 outline style, got:\n$body")
            assertFalse(body.contains(",3,"), "BorderStyle=3 square box must not be used, got:\n$body")
            // Layer 0 = rounded-rect drawing, layer 1 = text, same timing window.
            assertTrue(body.contains("Dialogue: 0,0:00:01.00,0:00:02.50,"), body)
            assertTrue(body.contains("Dialogue: 1,0:00:01.00,0:00:02.50,"), body)
            assertTrue(body.contains("\\p1"), "rounded chip must be an ASS drawing, got:\n$body")
            assertTrue(body.contains("\\pos("), "chip and text share an absolute center, got:\n$body")
            // Curly braces must be escaped so libass does not treat them as override blocks.
            assertTrue(body.contains("Hello \\{world\\}"), body)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun assRoundedRectDrawingHasFourBezierCorners() {
        val path = FFmpegService.assRoundedRectDrawing(width = 200, height = 60, radius = 12)
        // Move to start of top edge, four cubic corners, close via final bezier back to start.
        assertTrue(path.startsWith("m 12 0 "), path)
        assertEquals(4, Regex("""\bb\b""").findAll(path).count(), "four corner beziers, got: $path")
        assertTrue(path.contains("l 188 0"), "top edge to before TR corner, got: $path")
        assertTrue(path.contains("l 200 48"), "right edge, got: $path")
        assertTrue(path.contains("l 12 60") || path.contains("l 12 60 "), path)
        // Radius is clamped when larger than half the short side.
        val tight = FFmpegService.assRoundedRectDrawing(width = 20, height = 10, radius = 100)
        assertTrue(tight.startsWith("m 5 0 "), "radius clamps to half height (5), got: $tight")
    }

    @Test
    fun escapeFilterPathEscapesColonAndQuotes() {
        assertEquals("/tmp/foo", FFmpegService.escapeFilterPath("/tmp/foo"))
        assertEquals("/tmp/foo\\:bar", FFmpegService.escapeFilterPath("/tmp/foo:bar"))
        assertEquals("/tmp/foo\\'bar", FFmpegService.escapeFilterPath("/tmp/foo'bar"))
    }

    @Test
    fun captionEndIsNeverExtendedByALaterCaption() {
        // A non-overlapping later caption (starts after this one ends) must not stretch this window.
        val starts = listOf(0.0, 6.0)
        assertEquals(4.0, FFmpegService.captionVisibleEnd(visStart = 0.0, visEnd = 4.0, sortedCaptionStarts = starts))
        // The last caption of the movie (only its own start is present) keeps its natural end.
        assertEquals(10.0, FFmpegService.captionVisibleEnd(visStart = 6.0, visEnd = 10.0, sortedCaptionStarts = listOf(6.0)))
    }

    @Test
    fun captionEndIgnoresCaptionsStartingAtTheSameInstant() {
        // Two captions that start at (essentially) the same moment must not clamp each other to a
        // zero-length window — only a strictly-later caption cuts the current one off.
        val starts = listOf(2.0, 2.0)
        assertEquals(5.0, FFmpegService.captionVisibleEnd(visStart = 2.0, visEnd = 5.0, sortedCaptionStarts = starts))
    }

    @Test
    fun textColorHelpersConvertHexToFfmpegColorAndAlpha() {
        // #RRGGBB is opaque; the FFmpeg literal drops the leading '#' and adds a 0x prefix.
        assertEquals("0xFF0000", FFmpegService.ffmpegColorHex("#FF0000"))
        assertEquals(1.0, FFmpegService.ffmpegColorAlpha("#FF0000"))
        // #AARRGGBB: the alpha byte is split out (0..1) and only the RGB survives in the literal.
        assertEquals("0x00FF00", FFmpegService.ffmpegColorHex("#8000FF00"))
        assertEquals(0x80 / 255.0, FFmpegService.ffmpegColorAlpha("#8000FF00"))
        // Fully transparent background (the TextConfig default) -> alpha 0, so lower video shows.
        assertEquals(0.0, FFmpegService.ffmpegColorAlpha("#00000000"))
        // Malformed input falls back to white rather than producing an invalid FFmpeg color.
        assertEquals("0xFFFFFF", FFmpegService.ffmpegColorHex("nonsense"))
    }

    @Test
    fun drawtextColorAppendsAlphaOnlyWhenNotOpaque() {
        // Opaque colors need no @alpha suffix; translucent ones carry it so drawtext blends the text.
        assertEquals("0xFFFFFF", FFmpegService.ffmpegDrawtextColor("#FFFFFF"))
        assertTrue(FFmpegService.ffmpegDrawtextColor("#80FF0000").startsWith("0xFF0000@"))
    }

    @Test
    fun ffFormatsNumbersAsPlainDecimalsNotScientificNotation() = with(FFmpegService) {
        // The exact value from the crash report: float drift (2^-16) that Kotlin's toString() renders
        // as "1.52587890625E-5", which FFmpeg rejects ("Unable to parse option value ... as duration").
        val tiny = (1.52587890625E-5).ff()
        assertFalse(tiny.contains("E", ignoreCase = true), "must not use scientific notation, got: $tiny")
        assertTrue(tiny.startsWith("0.0000"), "should be a plain fixed-point decimal, got: $tiny")
        // Ordinary values stay readable; whole numbers collapse to a bare integer.
        assertEquals("30", 30.0.ff())
        assertEquals("2.5", 2.5.ff())
        assertEquals("0", 0.0.ff())
        // A Float carrying the same drift (as clip trim/timeline values do) is handled too.
        assertFalse((1.52587890625E-5f).ff().contains("E", ignoreCase = true))
    }

    @Test
    fun volumeEnvelopeExpressionAvoidsScientificNotation() {
        // A keyframe time with float drift must not leak scientific notation into the volume filter.
        val expr = FFmpegService.volumeEnvelopeExpression(
            listOf(VolumePoint(time = 1.52587890625E-5, volume = 0.0), VolumePoint(time = 3.0, volume = 1.0))
        )
        assertFalse(expr.contains("E-", ignoreCase = true), "volume expression must be FFmpeg-parseable, got: $expr")
    }

    @Test
    fun textSafeAreaPaddingReservesAMarginOnEachSide() {
        // A TEXT element's block must not be laid out across the full canvas — some fraction of it
        // is reserved as margin on every side (mirrors the preview's horizontal padding on TextClip).
        val padding = FFmpegService.textSafeAreaPadding(1280)
        assertTrue(padding > 0, "padding must be positive, or text can span the full width and touch the edges")
        assertTrue(2 * padding < 1280, "the two margins must leave real room for content")
    }

    @Test
    fun textSafeAreaWrapWidthIsNarrowerThanTheFullCanvasWidthEstimate() {
        // Regression guard for the overflow bug: wrapping used to be based on the *full* canvas
        // width, so a maximal line could run edge-to-edge. The padded estimate must wrap earlier.
        val canvasWidth = 1280
        val fontSize = 48
        val fullCanvasEstimate = (canvasWidth * 1.8 / fontSize).toInt()
        val paddedWrapWidth = FFmpegService.textSafeAreaWrapWidth(canvasWidth, fontSize)

        assertTrue(
            paddedWrapWidth < fullCanvasEstimate,
            "wrap width must shrink once safe-area padding is reserved: padded=$paddedWrapWidth full=$fullCanvasEstimate"
        )
    }

    @Test
    fun textSafeAreaXExprClampsIntoThePaddedSafeArea() {
        val canvasWidth = 1280
        val padding = FFmpegService.textSafeAreaPadding(canvasWidth)
        val expr = FFmpegService.textSafeAreaXExpr(canvasWidth)

        // Centered by default, but hard-clamped between [padding, w-text_w-padding] so a line whose
        // rendered width was under-estimated by the wrap heuristic still can't reach the frame edge.
        assertEquals("'max($padding,min(w-text_w-$padding,(w-text_w)/2))'", expr)
    }

    @Test
    fun textLineSpacingIsTheNaturalIdealSpacing() {
        // The spacing is the natural fontSize*1.25, independent of line count / canvas height.
        assertEquals(48 * 1.25, FFmpegService.textLineSpacing(fontSize = 48))
    }

    @Test
    fun textLineSpacingIsNotCompressedSoTallBlocksCanOverflowAndScroll() {
        // Regression guard for the "text doesn't scroll" bug: spacing must NOT be squeezed to fit a
        // tall multi-line block inside the frame. It stays at the natural ideal even for many lines,
        // so the block overflows the canvas — which is exactly what drives the vertical scroll in
        // renderTextElementLayer. Compressing it (as we used to) pinned the scroll offset to ~0.
        val fontSize = 100
        val spacing = FFmpegService.textLineSpacing(fontSize)
        assertEquals(fontSize * 1.25, spacing, "spacing must stay at the natural ideal, not compress")

        // 8 such lines stack far taller than a short canvas' safe area — i.e. they overflow and scroll.
        val canvasHeight = 480
        val padding = FFmpegService.textSafeAreaPadding(canvasHeight)
        val blockHeight = (8 - 1) * spacing
        assertTrue(
            blockHeight > (canvasHeight - 2 * padding),
            "a tall block ($blockHeight px) must overflow the safe area so it can scroll"
        )
    }

    @Test
    fun revealTransitionsMultiplyTheLayersOwnAlphaSoTextBackgroundsStayTransparent() {
        // Regression guard for the "text layers render without transparency" bug. The geq-based
        // reveal transitions (CIRCLE / VIGNETTE) are shared by opaque media clips AND transparent
        // text-element layers. They must MULTIPLY the layer's existing alpha (`alpha(X,Y)*mask`),
        // not overwrite it with `...,255,0` — otherwise a transparent text background turned fully
        // opaque once the reveal filled the frame, hiding the video underneath.
        for (type in listOf(TransitionType.CIRCLE, TransitionType.VIGNETTE)) {
            val filters = mutableListOf<String>()
            FFmpegService.buildTransitionFilters(
                filters, TransitionSpec(type = type, durationSeconds = 0.5),
                transitionDur = 0.5, start = 0.0, canvasWidth = 640, canvasHeight = 360
            )
            val geq = filters.single { it.startsWith("geq=") }
            assertTrue(
                geq.contains("a='alpha(X,Y)*"),
                "$type must multiply the layer's own alpha, not overwrite it, got: $geq"
            )
            assertFalse(
                geq.contains(",255,0)"),
                "$type must not hard-set alpha to opaque (that broke text transparency), got: $geq"
            )
        }
    }

    @Test
    fun voronoiTransitionKeepsAnAlphaPlaneSoTextBackgroundsStayTransparent() {
        // VORONOI used to convert the layer to `gbrp`, which has no alpha channel — so a transparent
        // text-element background came back fully opaque after the transition. It must use `gbrap`
        // (GBR + alpha) and displace the alpha plane with the same voronoi sampling as the colors.
        val filters = mutableListOf<String>()
        FFmpegService.buildTransitionFilters(
            filters, TransitionSpec(type = TransitionType.VORONOI, durationSeconds = 0.5),
            transitionDur = 0.5, start = 0.0, canvasWidth = 640, canvasHeight = 360
        )
        assertTrue(filters.any { it == "format=gbrap" }, "VORONOI must keep an alpha plane via gbrap")
        assertFalse(filters.any { it == "format=gbrp" }, "VORONOI must not drop alpha via alpha-less gbrp")
        val geq = filters.single { it.startsWith("geq=") }
        val voronoiExpr = FFmpegService.voronoiGeqExpression(0.5)
        assertTrue(geq.contains("a='$voronoiExpr'"), "VORONOI must displace the alpha plane too, got: $geq")
    }

    @Test
    fun appBundledFontsAreOnTheServerClasspath() {
        // The render uses the app-bundled Asap/Yuyu fonts (matching the preview) by extracting them
        // from the server's classpath resources for FFmpeg drawtext. If they were not packaged with
        // the server the render would silently fall back to a system font — the bug this guards.
        for (font in listOf("/fonts/asap.ttf", "/fonts/yuyu.ttf")) {
            val stream = FFmpegService::class.java.getResourceAsStream(font)
            assertNotNull(stream, "bundled font resource must be on the server classpath: $font")
            stream.use {
                assertTrue(it.readBytes().isNotEmpty(), "bundled font must not be empty: $font")
            }
        }
    }
}
