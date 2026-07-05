package app.moviestudio

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.moviestudio.ui.parseInlineMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the display-side inline Markdown parser used to render AI chat bubbles. Unlike the
 * editor's length-preserving transformation, this one strips the syntax markers and applies real
 * styles, so the assertions cover both the visible text (markers gone) and the emitted spans.
 */
class MarkdownTextTest {

    @Test
    fun plainTextIsUnchangedAndUnstyled() {
        val result = parseInlineMarkdown("just some text")
        assertEquals("just some text", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun boldMarkersAreStrippedAndTheRunIsBold() {
        val result = parseInlineMarkdown("say **hello** now")
        assertEquals("say hello now", result.text)
        val boldSpan = result.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("hello", result.text.substring(boldSpan.start, boldSpan.end))
    }

    @Test
    fun italicMarkersAreStrippedAndTheRunIsItalic() {
        val result = parseInlineMarkdown("an *emphasised* word")
        assertEquals("an emphasised word", result.text)
        val italicSpan = result.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("emphasised", result.text.substring(italicSpan.start, italicSpan.end))
    }

    @Test
    fun tripleStarIsBothBoldAndItalic() {
        val result = parseInlineMarkdown("***wow***")
        assertEquals("wow", result.text)
        val span = result.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
    }

    @Test
    fun inlineCodeIsStrippedAndTakenVerbatim() {
        val result = parseInlineMarkdown("run `a**b**c` please")
        // The inner `**` is NOT treated as bold inside code: it survives verbatim.
        assertEquals("run a**b**c please", result.text)
    }

    @Test
    fun linkKeepsLabelAndDropsTheUrl() {
        val result = parseInlineMarkdown("see [the docs](https://example.com) today")
        assertEquals("see the docs today", result.text)
    }

    @Test
    fun unterminatedMarkerIsEmittedLiterally() {
        val result = parseInlineMarkdown("2 * 3 = 6 and **oops")
        assertEquals("2 * 3 = 6 and **oops", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
