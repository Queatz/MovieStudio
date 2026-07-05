package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tiny, self-contained rich-text editor for Markdown.
 *
 * We deliberately do NOT depend on a third-party rich-text widget: the underlying [TextFieldValue]
 * simply holds real Markdown source (`**bold**`, `*italic*`, `<u>underline</u>`, `~~strike~~`,
 * `# heading`, `- bullet`, `1. numbered`) and a *length-preserving* [VisualTransformation] paints
 * the styled ranges live. Because the transformation never changes the character count it can use
 * [OffsetMapping.Identity], which keeps cursor/selection behaviour rock solid across every Compose
 * version — that was exactly the fragile part of the old library on this project's Compose runtime.
 *
 * Toggling a style just wraps the current selection (inline styles) or prefixes the selected lines
 * (block styles), so the value stays valid Markdown that round-trips through save/history verbatim.
 */
class MarkdownEditorState(initialMarkdown: String = "") {

    /** The editable source of truth: the raw Markdown plus the current selection. */
    var value by mutableStateOf(
        TextFieldValue(initialMarkdown, selection = TextRange(initialMarkdown.length))
    )

    /** The current Markdown text. */
    val markdown: String get() = value.text

    /** Replaces the whole content (used for initial load and history restore). */
    fun setMarkdown(markdown: String) {
        if (markdown != value.text) {
            value = TextFieldValue(markdown, selection = TextRange(markdown.length))
        }
    }

    // ------------------------------------------------------------------- inline (wrapped) styles

    fun toggleBold() = wrapSelection("**")
    fun toggleItalic() = wrapSelection("*")
    fun toggleStrikethrough() = wrapSelection("~~")
    fun toggleUnderline() = wrapSelection("<u>", "</u>")

    val isBold: Boolean get() = caretInside(BOLD)
    val isItalic: Boolean get() = caretInside(ITALIC)
    val isStrikethrough: Boolean get() = caretInside(STRIKE)
    val isUnderline: Boolean get() = caretInside(UNDERLINE)

    /**
     * Wraps the current selection in [prefix]/[suffix] (e.g. `**`…`**`). Toggling is symmetric: if
     * the selection is already wrapped — either the markers sit just outside it or are part of the
     * selected text — they are removed instead.
     */
    private fun wrapSelection(prefix: String, suffix: String = prefix) {
        val text = value.text
        var start = value.selection.min
        var end = value.selection.max

        // With no selection, act on the word under the caret so toolbar clicks / shortcuts style
        // the current word — a much friendlier default than inserting a pair of empty markers.
        if (start == end) {
            wordBoundsAt(start)?.let { (wordStart, wordEnd) ->
                start = wordStart
                end = wordEnd
            }
        }

        // Markers immediately surrounding the selection -> unwrap them.
        if (start >= prefix.length && end + suffix.length <= text.length &&
            text.regionMatches(start - prefix.length, prefix, 0, prefix.length) &&
            text.regionMatches(end, suffix, 0, suffix.length)
        ) {
            val newText = text.substring(0, start - prefix.length) +
                text.substring(start, end) +
                text.substring(end + suffix.length)
            value = TextFieldValue(
                newText,
                selection = TextRange(start - prefix.length, end - prefix.length)
            )
            return
        }

        // The markers are part of the selected text itself -> unwrap them.
        val selected = text.substring(start, end)
        if (selected.length >= prefix.length + suffix.length &&
            selected.startsWith(prefix) && selected.endsWith(suffix)
        ) {
            val inner = selected.substring(prefix.length, selected.length - suffix.length)
            val newText = text.substring(0, start) + inner + text.substring(end)
            value = TextFieldValue(newText, selection = TextRange(start, start + inner.length))
            return
        }

        // Otherwise wrap the selection, keeping the inner text selected.
        val newText = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
        value = TextFieldValue(
            newText,
            selection = TextRange(start + prefix.length, start + prefix.length + selected.length)
        )
    }

    private fun caretInside(descriptor: InlineDescriptor): Boolean {
        val pos = value.selection.min
        for (match in descriptor.regex.findAll(value.text)) {
            val innerStart = match.range.first + descriptor.prefixLen
            val innerEnd = match.range.last + 1 - descriptor.suffixLen
            if (pos in innerStart..innerEnd) return true
        }
        return false
    }

    // ------------------------------------------------------------------- block (line-prefix) styles

    fun toggleHeading() = toggleLines(HEADING_LINE) { _, line -> "# $line" }
    fun toggleBulletList() = toggleLines(BULLET_LINE) { _, line -> "- $line" }
    fun toggleNumberedList() = toggleLines(NUMBERED_LINE) { index, line -> "${index + 1}. $line" }

    val isHeading: Boolean get() = currentLine().matches(HEADING_LINE)
    val isBulletList: Boolean get() = currentLine().matches(BULLET_LINE)
    val isNumberedList: Boolean get() = currentLine().matches(NUMBERED_LINE)

    /**
     * Adds or removes a block prefix on every line the selection touches. If all non-blank lines
     * already match [active] the prefix is stripped (toggle off); otherwise any existing block
     * prefix is replaced with the new one produced by [addPrefix] (block styles are exclusive).
     */
    private fun toggleLines(active: Regex, addPrefix: (index: Int, line: String) -> String) {
        val text = value.text
        val selStart = value.selection.min
        val selEnd = value.selection.max
        val blockStart = lineStartOf(selStart)
        val blockEnd = lineEndOf(selEnd)
        val block = text.substring(blockStart, blockEnd)
        val lines = block.split("\n")
        val relevant = lines.filter { it.isNotBlank() }
        val allActive = relevant.isNotEmpty() && relevant.all { it.matches(active) }

        val transformed = lines.mapIndexed { index, line ->
            if (line.isBlank()) {
                line
            } else {
                val stripped = line.replaceFirst(ANY_BLOCK_PREFIX, "")
                if (allActive) stripped else addPrefix(index, stripped)
            }
        }
        val newBlock = transformed.joinToString("\n")
        val firstDelta = transformed.first().length - lines.first().length
        val totalDelta = newBlock.length - block.length
        val newText = text.substring(0, blockStart) + newBlock + text.substring(blockEnd)
        value = TextFieldValue(
            newText,
            selection = TextRange(
                (selStart + firstDelta).coerceIn(blockStart, newText.length),
                (selEnd + totalDelta).coerceIn(0, newText.length)
            )
        )
    }

    // ------------------------------------------------------------------- list continuation (Enter)

    /**
     * Handles Enter inside a list: on a non-empty bullet/numbered item it starts the next item
     * (same bullet, or the next number); on an *empty* item it leaves the list (the marker is
     * removed). Returns true when it handled the key — so the editor can consume the event — or
     * false to let a normal newline be inserted.
     */
    fun continueList(): Boolean {
        // Only auto-continue for a collapsed caret; a ranged selection gets a plain newline.
        if (value.selection.min != value.selection.max) return false
        val text = value.text
        val caret = value.selection.min
        val lineStart = lineStartOf(caret)
        val line = text.substring(lineStart, lineEndOf(caret))

        val bullet = BULLET_MARKER.find(line)
        val numbered = NUMBERED_MARKER.find(line)
        val marker: String
        val nextMarker: String
        when {
            bullet != null -> {
                marker = bullet.value
                nextMarker = marker
            }
            numbered != null -> {
                marker = numbered.value
                val n = numbered.groupValues[1].toIntOrNull() ?: 0
                nextMarker = "${n + 1}. "
            }
            else -> return false
        }

        // An empty item (just the marker) leaves the list: drop the marker, keep the blank line.
        if (line.substring(marker.length).isBlank()) {
            val newText = text.substring(0, lineStart) + text.substring(lineEndOf(caret))
            value = TextFieldValue(newText, selection = TextRange(lineStart))
            return true
        }

        // Otherwise split at the caret and open a fresh item with the next marker.
        val insert = "\n$nextMarker"
        val newText = text.substring(0, caret) + insert + text.substring(caret)
        value = TextFieldValue(newText, selection = TextRange(caret + insert.length))
        return true
    }

    /** The letter/digit run touching [pos], or null when the caret isn't on a word. */
    private fun wordBoundsAt(pos: Int): Pair<Int, Int>? {
        val text = value.text
        var s = pos
        var e = pos
        while (s > 0 && text[s - 1].isLetterOrDigit()) s--
        while (e < text.length && text[e].isLetterOrDigit()) e++
        return if (e > s) s to e else null
    }

    private fun currentLine(): String {
        val pos = value.selection.min
        return value.text.substring(lineStartOf(pos), lineEndOf(pos))
    }

    private fun lineStartOf(pos: Int): Int {
        if (pos <= 0) return 0
        val newline = value.text.lastIndexOf('\n', pos - 1)
        return if (newline < 0) 0 else newline + 1
    }

    private fun lineEndOf(pos: Int): Int {
        val newline = value.text.indexOf('\n', pos)
        return if (newline < 0) value.text.length else newline
    }

    private data class InlineDescriptor(val regex: Regex, val prefixLen: Int, val suffixLen: Int)

    private companion object {
        val BOLD = InlineDescriptor(Regex("""\*\*(?:(?!\*\*).)+\*\*"""), 2, 2)
        val ITALIC = InlineDescriptor(Regex("""(?<![*\\])\*(?!\*)[^*\n]+\*(?!\*)"""), 1, 1)
        val STRIKE = InlineDescriptor(Regex("""~~(?:(?!~~).)+~~"""), 2, 2)
        val UNDERLINE = InlineDescriptor(Regex("""<u>.+?</u>""", RegexOption.IGNORE_CASE), 3, 4)

        val HEADING_LINE = Regex("""^#{1,6}\s.*""")
        val BULLET_LINE = Regex("""^[-*+]\s.*""")
        val NUMBERED_LINE = Regex("""^\d+\.\s.*""")
        val ANY_BLOCK_PREFIX = Regex("""^(#{1,6}\s|[-*+]\s|\d+\.\s)""")

        // Just the leading marker (+ its trailing space) of a list item, used for Enter continuation.
        val BULLET_MARKER = Regex("""^[-*+]\s+""")
        val NUMBERED_MARKER = Regex("""^(\d+)\.\s+""")
    }
}

/**
 * The editor surface for a [MarkdownEditorState]: a [BasicTextField] over the raw Markdown, styled
 * live by [markdownVisualTransformation], with Ctrl/Cmd+B/I/U shortcuts. Long content scrolls
 * vertically inside the given bounds.
 */
@Composable
fun MarkdownRichTextEditor(
    state: MarkdownEditorState,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val accentColor = MaterialTheme.colorScheme.primary
    val transformation = remember(baseColor, markerColor, accentColor) {
        markdownVisualTransformation(markerColor = markerColor, accentColor = accentColor)
    }
    val textStyle = LocalTextStyle.current.merge(
        LocalTextStyle.current.copy(color = baseColor, fontSize = 15.sp, lineHeight = 22.sp)
    )

    // Tell the app a text field is focused so the editor-screen keyboard shortcuts (space =
    // play/pause, arrows = seek, Delete/Backspace = remove clip) stand down while the user types —
    // otherwise those keys are swallowed globally instead of editing the document. The dispose
    // guard releases the count if the editor is torn down (document closed) while still focused.
    var wasFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (wasFocused) TextInputFocusTracker.onFocusChanged(true, false) }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
    ) {
        BasicTextField(
            value = state.value,
            onValueChange = { state.value = it },
            modifier = Modifier
                .fillMaxWidth()
                // Use hasFocus (not isFocused): the inner BasicTextField's focus target sits
                // below the verticalScroll focus-group node this modifier observes, so isFocused
                // stays false while the field is being typed in. Keying off hasFocus makes the
                // focus tracker register the editor — otherwise the editor-screen arrow-key
                // shortcuts keep seeking the timeline instead of moving the caret.
                .onFocusChanged { focus ->
                    TextInputFocusTracker.onFocusChanged(wasFocused, focus.hasFocus)
                    wasFocused = focus.hasFocus
                }
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
                // Editing shortcuts, handled on key-down and *consumed* (returning true) so the
                // browser/OS never runs its own default for them — e.g. Ctrl+B toggling the
                // bookmarks bar, Ctrl+U opening "view source", or the letter leaking in as text.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val shortcut = event.isCtrlPressed || event.isMetaPressed
                    when {
                        shortcut && event.key == Key.B -> { state.toggleBold(); true }
                        shortcut && event.key == Key.I -> { state.toggleItalic(); true }
                        shortcut && event.key == Key.U -> { state.toggleUnderline(); true }
                        // Enter continues the current list (or leaves it on an empty item); when the
                        // caret isn't on a list line we return false so a normal newline is inserted.
                        !shortcut && !event.isAltPressed && !event.isShiftPressed &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) ->
                            state.continueList()
                        else -> false
                    }
                },
            textStyle = textStyle,
            cursorBrush = SolidColor(accentColor),
            visualTransformation = transformation,
            decorationBox = { inner ->
                Box {
                    if (state.value.text.isEmpty()) {
                        Text(placeholder, color = markerColor, fontSize = 15.sp)
                    }
                    inner()
                }
            }
        )
    }
}

/**
 * A length-preserving [VisualTransformation] that renders Markdown source as styled text: inline
 * `**bold**` / `*italic*` / `~~strike~~` / `<u>underline</u>` / `` `code` `` and line-level
 * `# heading` / `- bullet` / `1. numbered`. The syntax markers themselves stay in the text (so the
 * character count is unchanged and offsets map 1:1) but are dimmed with [markerColor]; list markers
 * are tinted with [accentColor].
 */
fun markdownVisualTransformation(markerColor: Color, accentColor: Color): VisualTransformation =
    VisualTransformation { text ->
        TransformedText(
            buildMarkdownAnnotated(text.text, markerColor, accentColor),
            OffsetMapping.Identity
        )
    }

private fun buildMarkdownAnnotated(
    text: String,
    markerColor: Color,
    accentColor: Color
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (text.isEmpty()) return@buildAnnotatedString

    // ---- Block-level styling, one line at a time.
    var lineStart = 0
    for (line in text.split("\n")) {
        val lineEnd = lineStart + line.length
        val heading = Regex("""^(#{1,6})\s""").find(line)
        val bullet = Regex("""^[-*+]\s""").find(line)
        val numbered = Regex("""^\d+\.\s""").find(line)
        when {
            heading != null -> {
                addStyle(
                    SpanStyle(fontSize = headingFontSize(heading.groupValues[1].length), fontWeight = FontWeight.Bold),
                    lineStart, lineEnd
                )
                addStyle(SpanStyle(color = markerColor), lineStart, lineStart + heading.value.length)
            }
            bullet != null -> addStyle(
                SpanStyle(color = accentColor, fontWeight = FontWeight.Bold),
                lineStart, lineStart + bullet.value.length
            )
            numbered != null -> addStyle(
                SpanStyle(color = accentColor, fontWeight = FontWeight.Bold),
                lineStart, lineStart + numbered.value.length
            )
        }
        lineStart = lineEnd + 1 // skip the '\n'
    }

    // ---- Inline styling over the whole text. Bold is handled before italic so `**` never gets
    // mistaken for a single-star italic run.
    fun style(regex: Regex, prefixLen: Int, suffixLen: Int, span: SpanStyle) {
        for (match in regex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            val innerStart = start + prefixLen
            val innerEnd = end - suffixLen
            if (innerEnd > innerStart) addStyle(span, innerStart, innerEnd)
            addStyle(SpanStyle(color = markerColor), start, innerStart)
            addStyle(SpanStyle(color = markerColor), innerEnd, end)
        }
    }

    style(Regex("""\*\*(?:(?!\*\*).)+\*\*"""), 2, 2, SpanStyle(fontWeight = FontWeight.Bold))
    style(
        Regex("""(?<![*\\])\*(?!\*)[^*\n]+\*(?!\*)"""), 1, 1,
        SpanStyle(fontStyle = FontStyle.Italic)
    )
    style(Regex("""~~(?:(?!~~).)+~~"""), 2, 2, SpanStyle(textDecoration = TextDecoration.LineThrough))
    style(
        Regex("""<u>.+?</u>""", RegexOption.IGNORE_CASE), 3, 4,
        SpanStyle(textDecoration = TextDecoration.Underline)
    )
    style(Regex("""`[^`\n]+`"""), 1, 1, SpanStyle(fontFamily = FontFamily.Monospace))
}
