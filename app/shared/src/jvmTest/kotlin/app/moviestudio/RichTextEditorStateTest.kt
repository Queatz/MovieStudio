package app.moviestudio

import androidx.compose.ui.text.TextRange
import app.moviestudio.ui.MarkdownEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * State-level checks for the document editor's own Markdown engine (we dropped the third-party
 * rich-text widget). Toggling a style must edit the Markdown source in place and round-trip
 * verbatim through the save/history string, the "active" flags must reflect the caret, a toggle
 * with no selection must act on the word under the caret, and Enter must continue / leave lists.
 */
class RichTextEditorStateTest {

    private fun stateAt(markdown: String, start: Int, end: Int = start) =
        MarkdownEditorState(markdown).apply {
            value = value.copy(selection = TextRange(start, end))
        }

    @Test
    fun togglingBoldWrapsTheSelectionInMarkdown() {
        val state = stateAt("hello world", 0, 5)
        state.toggleBold()
        assertEquals("**hello** world", state.markdown)
        // The caret now sits inside the bold run, so the toolbar reports it as active.
        assertTrue(state.isBold)
    }

    @Test
    fun togglingBoldAgainUnwrapsIt() {
        val state = stateAt("**hello** world", 2, 7) // selection = "hello"
        state.toggleBold()
        assertEquals("hello world", state.markdown)
        assertFalse(state.isBold)
    }

    @Test
    fun togglingItalicWrapsWithSingleStars() {
        val state = stateAt("hello", 0, 5)
        state.toggleItalic()
        assertEquals("*hello*", state.markdown)
        assertTrue(state.isItalic)
    }

    @Test
    fun togglingUnderlineUsesHtmlTags() {
        val state = stateAt("hello", 0, 5)
        state.toggleUnderline()
        assertEquals("<u>hello</u>", state.markdown)
        assertTrue(state.isUnderline)
    }

    @Test
    fun togglingBulletListPrefixesAndUnprefixesTheLine() {
        val state = stateAt("item one", 0)
        state.toggleBulletList()
        assertEquals("- item one", state.markdown)
        assertTrue(state.isBulletList)
        state.toggleBulletList()
        assertEquals("item one", state.markdown)
        assertFalse(state.isBulletList)
    }

    @Test
    fun togglingNumberedListNumbersEachSelectedLine() {
        val state = stateAt("first\nsecond", 0, "first\nsecond".length)
        state.toggleNumberedList()
        assertEquals("1. first\n2. second", state.markdown)
    }

    @Test
    fun blockStylesAreMutuallyExclusive() {
        val state = stateAt("title", 0)
        state.toggleHeading()
        assertEquals("# title", state.markdown)
        state.toggleBulletList()
        assertEquals("- title", state.markdown)
        assertFalse(state.isHeading)
        assertTrue(state.isBulletList)
    }

    @Test
    fun boldWithNoSelectionStylesTheWordUnderTheCaret() {
        val state = stateAt("hello world", 2) // collapsed caret inside "hello"
        state.toggleBold()
        assertEquals("**hello** world", state.markdown)
        assertTrue(state.isBold)
    }

    @Test
    fun boldWithNoSelectionInsideABoldWordRemovesIt() {
        val state = stateAt("**hello** world", 4) // collapsed caret inside the bold word
        state.toggleBold()
        assertEquals("hello world", state.markdown)
        assertFalse(state.isBold)
    }

    @Test
    fun enterOnABulletItemStartsTheNextItem() {
        val state = stateAt("- first", "- first".length)
        assertTrue(state.continueList())
        assertEquals("- first\n- ", state.markdown)
    }

    @Test
    fun enterOnAnEmptyBulletLeavesTheList() {
        val text = "- first\n- "
        val state = stateAt(text, text.length)
        assertTrue(state.continueList())
        assertEquals("- first\n", state.markdown)
    }

    @Test
    fun enterOnANumberedItemIncrementsTheNumber() {
        val state = stateAt("1. first", "1. first".length)
        assertTrue(state.continueList())
        assertEquals("1. first\n2. ", state.markdown)
    }

    @Test
    fun enterOutsideAListIsNotHandled() {
        val state = stateAt("plain text", "plain text".length)
        assertFalse(state.continueList())
        assertEquals("plain text", state.markdown)
    }

    @Test
    fun markdownRoundTripsThroughSaveVerbatim() {
        val saved = "# Heading\n\n**bold** and *italic* and <u>underline</u>\n\n- one\n- two"
        val state = MarkdownEditorState(saved)
        assertEquals(saved, state.markdown)
        // Reload the way history restore does.
        state.setMarkdown(saved)
        assertEquals(saved, state.markdown)
    }
}
