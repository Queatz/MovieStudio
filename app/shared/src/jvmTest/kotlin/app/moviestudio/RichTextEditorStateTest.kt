package app.moviestudio

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * State-level checks for the document editor's rich text engine: toggling a span style on a
 * selection must produce styled content that survives the HTML round-trip used by auto-save
 * (the UI-side pitfalls — toolbar buttons stealing focus — are handled in DocumentsPanel).
 */
class RichTextEditorStateTest {

    @Test
    fun togglingBoldOnASelectionSurvivesTheHtmlRoundTrip() {
        val state = RichTextState()
        state.setHtml("<p>hello world</p>")
        state.selection = TextRange(0, 5)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))

        val html = state.toHtml()
        assertTrue(
            "<b>" in html || "<strong>" in html || "font-weight" in html,
            "Expected bold markup after toggling bold, got: $html"
        )

        // Reload the saved HTML the way the editor does and make sure the style is still there.
        val restored = RichTextState()
        restored.setHtml(html)
        restored.selection = TextRange(0, 5)
        assertTrue(
            restored.currentSpanStyle.fontWeight == FontWeight.Bold,
            "Expected the restored selection to still be bold, html: $html"
        )
    }

    @Test
    fun togglingItalicOnACollapsedSelectionStylesWhatIsTypedNext() {
        val state = RichTextState()
        state.setHtml("<p>hello</p>")
        state.selection = TextRange(5, 5)
        state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        assertTrue(
            state.currentSpanStyle.fontStyle == FontStyle.Italic,
            "Expected the typing style to become italic after toggling on a collapsed selection"
        )
    }
}
