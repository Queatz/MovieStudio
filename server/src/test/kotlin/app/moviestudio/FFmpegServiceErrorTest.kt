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
}
