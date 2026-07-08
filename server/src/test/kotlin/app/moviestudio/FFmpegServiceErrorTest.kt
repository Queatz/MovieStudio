package app.moviestudio

import app.moviestudio.service.FFmpegService
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
    fun textLineSpacingKeepsASingleLineAtTheIdealSpacing() {
        // A single line has nothing to overflow vertically, so spacing is untouched.
        assertEquals(48 * 1.25, FFmpegService.textLineSpacing(canvasHeight = 720, fontSize = 48, lineCount = 1))
    }

    @Test
    fun textLineSpacingCompressesManyLinesToFitInsideTheSafeArea() {
        // 8 lines at a large font size would, at the ideal fontSize*1.25 spacing, stack far taller
        // than the frame — the top/bottom lines would run past the canvas edges. Spacing must shrink
        // so the whole block still fits inside the vertically padded safe area.
        val canvasHeight = 480
        val fontSize = 100
        val lineCount = 8
        val padding = FFmpegService.textSafeAreaPadding(canvasHeight)

        val spacing = FFmpegService.textLineSpacing(canvasHeight, fontSize, lineCount)
        val blockHeight = (lineCount - 1) * spacing

        assertTrue(spacing < fontSize * 1.25, "spacing must be compressed below the ideal for a tall block")
        assertTrue(
            blockHeight <= (canvasHeight - 2 * padding) + 0.001,
            "the stacked block ($blockHeight px) must fit inside the padded safe area"
        )
    }
}
