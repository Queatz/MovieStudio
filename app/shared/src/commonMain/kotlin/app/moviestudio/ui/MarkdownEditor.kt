package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AiChatMessage
import app.moviestudio.NetworkService
import app.moviestudio.installMarkdownShortcutGuard
import app.moviestudio.isFromIme
import app.moviestudio.startRealtimeSpeechInput
import app.moviestudio.stopRealtimeSpeechInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tiny, self-contained rich-text editor for Markdown.
 *
 * We deliberately do NOT depend on a third-party rich-text widget: the underlying [TextFieldValue]
 * simply holds real Markdown source (`**bold**`, `*italic*`, `***bold italic***`,
 * `<u>underline</u>`, `~~strike~~`,
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

    // A `***…***` run is both bold and italic, so it counts as active for either toggle.
    val isBold: Boolean get() = caretInside(BOLD) || caretInside(BOLD_ITALIC)
    val isItalic: Boolean get() = caretInside(ITALIC) || caretInside(BOLD_ITALIC)
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
        val BOLD_ITALIC = InlineDescriptor(Regex("""\*\*\*(?:(?!\*\*\*).)+\*\*\*"""), 3, 3)
        val BOLD = InlineDescriptor(Regex("""(?<!\*)\*\*(?:(?!\*\*).)+\*\*(?!\*)"""), 2, 2)
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
 *
 * [focusRequester] lets a caller (e.g. the formatting toolbar) put the caret back into the field
 * after an action that would otherwise leave it unfocused — toolbar buttons don't take focus
 * themselves (see [FormatToggle]), so without this the field would stay unfocused after a click.
 *
 * Like [StudioTextField], the editor supports hold-to-dictate: long-press the surface (pointer or
 * touch) to start realtime speech-to-text — recognized words stream into the document while the
 * press is held, and dictation stops the moment the press is released. While dictation is live the
 * placeholder switches to "Start speaking...".
 *
 * It also supports an AI chat by default: pressing Alt+Enter opens the reusable [AiPromptDialog],
 * where the user can chat with the AI and, once happy, press "Insert" to append the generated text
 * to the document. [aiGenerate] performs the actual AI call and defaults to the generic
 * [NetworkService.generateText] endpoint; pass `null` to disable the shortcut entirely.
 */
@Composable
fun MarkdownRichTextEditor(
    state: MarkdownEditorState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true,
    aiGenerate: (suspend (messages: List<AiChatMessage>) -> String)? = { NetworkService.generateText(it) },
    aiPromptTitle: String = "✨ AI chat",
    aiPromptDescription: String? =
        "Describe what you want, chat to refine it, then insert the result into the editor."
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
    // Alt+Enter opens the reusable AI prompt dialog (enabled by default; disabled only when the
    // caller passes a null [aiGenerate]).
    var showAiPrompt by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // onPreviewKeyEvent returning true below only stops other Compose handlers from seeing the
        // event — it does NOT stop the browser from running its own default action for Ctrl/Cmd+
        // B/I/U (bold/italic toggle, "view source"), so a native browser-level guard is required
        // too. It only fires while this exact field has focus.
        val shortcutGuard = installMarkdownShortcutGuard { wasFocused }
        onDispose {
            shortcutGuard.dispose()
            // A dictation session left running (e.g. the editor is torn down mid-press) must not
            // keep the microphone open.
            if (dictating) stopRealtimeSpeechInput()
            if (wasFocused) TextInputFocusTracker.onFocusChanged(true, false)
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            // The field only lays out as tall as its text, so the empty area beneath the
            // placeholder isn't part of the text field. Make the whole surface put the caret
            // back into the editor when clicked (no ripple: it should feel like the field).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled
            ) { focusRequester.requestFocus() }
    ) {
        BasicTextField(
            value = state.value,
            onValueChange = { state.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
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
                    // Let IME composition keystrokes (e.g. Vietnamese Telex) flow straight to the
                    // input method: inspecting/consuming them commits the composing region per key
                    // so accents never fold onto their base letter.
                    if (event.isFromIme()) return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val shortcut = event.isCtrlPressed || event.isMetaPressed
                    when {
                        shortcut && event.key == Key.B -> { state.toggleBold(); true }
                        shortcut && event.key == Key.I -> { state.toggleItalic(); true }
                        shortcut && event.key == Key.U -> { state.toggleUnderline(); true }
                        // Alt+Enter opens the AI prompt dialog (chat with the AI, then insert its
                        // result). Enabled by default; skipped only when [aiGenerate] is null.
                        aiGenerate != null && event.isAltPressed && event.key == Key.Enter -> {
                            showAiPrompt = true
                            true
                        }
                        // Enter continues the current list (or leaves it on an empty item); when the
                        // caret isn't on a list line we return false so a normal newline is inserted.
                        !shortcut && !event.isAltPressed && !event.isShiftPressed &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) ->
                            state.continueList()
                        else -> false
                    }
                }
                // Hold-to-dictate. Observed on the Initial pass without consuming anything, so the
                // normal text-editing gestures keep working exactly as before.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // A long press is a press still held after the platform long-press timeout.
                        val releasedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                        if (releasedEarly == null) {
                            // Dictate for as long as the press is held. A failing platform bridge
                            // must never kill this pointer handler (that would disable dictation
                            // for the rest of the editor's lifetime).
                            val base = state.markdown
                            val started = runCatching {
                                startRealtimeSpeechInput { spoken ->
                                    val prefix = if (base.isBlank()) "" else base.trimEnd() + " "
                                    state.setMarkdown(prefix + spoken)
                                }
                            }.getOrDefault(false)
                            dictating = started
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) break
                            }
                            if (started) stopRealtimeSpeechInput()
                            dictating = false
                        }
                    }
                },
            textStyle = textStyle,
            cursorBrush = SolidColor(accentColor),
            visualTransformation = transformation,
            decorationBox = { inner ->
                Box {
                    if (state.value.text.isEmpty()) {
                        // Live dictation: invite the user to talk (visible while the document is
                        // still empty), mirroring [StudioTextField].
                        if (dictating) {
                            Text(
                                "Start speaking...",
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                fontSize = 15.sp
                            )
                        } else {
                            Text(placeholder, color = markerColor, fontSize = 15.sp)
                        }
                    }
                    inner()
                }
            }
        )
    }

    // AI prompt dialog (opened with Alt+Enter): chat with the AI and insert its result into the
    // document. Shown unless the caller disabled the shortcut by passing a null [aiGenerate].
    if (showAiPrompt && aiGenerate != null) {
        AiPromptDialog(
            title = aiPromptTitle,
            description = aiPromptDescription,
            initialPrompt = state.markdown,
            generate = aiGenerate,
            generateLabel = "✨ Generate",
            acceptLabel = "Insert",
            onAccept = { generated ->
                // Append the accepted text to whatever is already there so nothing the user typed
                // is lost, mirroring hold-to-dictate's spacing.
                val base = state.markdown
                val prefix = if (base.isBlank()) "" else base.trimEnd() + " "
                state.setMarkdown(prefix + generated)
                showAiPrompt = false
            },
            onDismiss = { showAiPrompt = false }
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

/**
 * Maps a Markdown heading level (number of leading `#`) to its rendered font size, so `#` reads as
 * a large title and `####` as a small sub-heading rather than every level looking identical. Levels
 * beyond 4 (`#####`, `######`) fall back to the smallest heading size.
 */
private fun headingFontSize(level: Int): TextUnit = when (level) {
    1 -> 24.sp
    2 -> 20.sp
    3 -> 18.sp
    4 -> 16.sp
    5 -> 14.sp
    else -> 12.sp
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

    // ---- Inline styling over the whole text. Bold+italic (`***`) is handled first, then bold
    // before italic, so a `***` run is never mistaken for a `**` bold run or a single-star italic
    // run (and the bold/italic patterns explicitly refuse to match a marker that is part of a `***`).
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

    style(
        Regex("""\*\*\*(?:(?!\*\*\*).)+\*\*\*"""), 3, 3,
        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    )
    style(
        Regex("""(?<!\*)\*\*(?:(?!\*\*).)+\*\*(?!\*)"""), 2, 2,
        SpanStyle(fontWeight = FontWeight.Bold)
    )
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

// ---------------------------------------------------------------------------- printable export

/** Escapes the characters that are unsafe to drop into HTML text/attributes. */
private fun escapeHtml(text: String): String = buildString {
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

/**
 * Renders one line's inline Markdown (`***bold italic***`, `**bold**`, `*italic*`, `~~strike~~`,
 * `<u>underline</u>`, `` `code` ``) into safe HTML. The line is HTML-escaped first (so any stray
 * `<`/`&` in the user's text is inert); the `<u>` underline markers are preserved across escaping
 * via sentinels so they still become real `<u>` tags. Markers are consumed exactly like the live
 * [markdownVisualTransformation] does, keeping the printout faithful to the editor.
 */
private fun markdownInlineToHtml(line: String): String {
    // Preserve the underline markers across HTML-escaping, then escape everything else.
    var s = line
        .replace("<u>", "\u0001").replace("<U>", "\u0001")
        .replace("</u>", "\u0002").replace("</U>", "\u0002")
    s = escapeHtml(s)

    fun wrap(regex: Regex, prefixLen: Int, suffixLen: Int, open: String, close: String) {
        s = regex.replace(s) { match ->
            val value = match.value
            open + value.substring(prefixLen, value.length - suffixLen) + close
        }
    }

    // `***` first so it is never mistaken for a `**`/`*` run (mirrors the editor's ordering).
    wrap(Regex("""\*\*\*(?:(?!\*\*\*).)+\*\*\*"""), 3, 3, "<strong><em>", "</em></strong>")
    wrap(Regex("""(?<!\*)\*\*(?:(?!\*\*).)+\*\*(?!\*)"""), 2, 2, "<strong>", "</strong>")
    wrap(Regex("""(?<![*\\])\*(?!\*)[^*\n]+\*(?!\*)"""), 1, 1, "<em>", "</em>")
    wrap(Regex("""~~(?:(?!~~).)+~~"""), 2, 2, "<del>", "</del>")
    wrap(Regex("""`[^`\n]+`"""), 1, 1, "<code>", "</code>")

    return s.replace("\u0001", "<u>").replace("\u0002", "</u>")
}

/**
 * Converts Markdown [markdown] into a block of HTML (`<h1>`…`<h6>`, `<ul>`/`<ol>` lists,
 * `<p>` paragraphs) suitable for a printable page. Only the subset the editor understands is
 * handled; everything else prints as plain paragraphs.
 */
fun markdownToPrintHtml(markdown: String): String {
    val out = StringBuilder()
    var openList: String? = null // "ul" or "ol", or null when not inside a list
    fun closeList() {
        openList?.let { out.append("</").append(it).append(">\n") }
        openList = null
    }
    for (raw in markdown.split("\n")) {
        val line = raw.trimEnd()
        val heading = Regex("""^(#{1,6})\s+(.*)$""").find(line)
        val bullet = Regex("""^[-*+]\s+(.*)$""").find(line)
        val numbered = Regex("""^\d+\.\s+(.*)$""").find(line)
        when {
            line.isBlank() -> closeList()
            heading != null -> {
                closeList()
                val level = heading.groupValues[1].length
                out.append("<h").append(level).append(">")
                    .append(markdownInlineToHtml(heading.groupValues[2]))
                    .append("</h").append(level).append(">\n")
            }
            bullet != null -> {
                if (openList != "ul") { closeList(); out.append("<ul>\n"); openList = "ul" }
                out.append("<li>").append(markdownInlineToHtml(bullet.groupValues[1])).append("</li>\n")
            }
            numbered != null -> {
                if (openList != "ol") { closeList(); out.append("<ol>\n"); openList = "ol" }
                out.append("<li>").append(markdownInlineToHtml(numbered.groupValues[1])).append("</li>\n")
            }
            else -> {
                closeList()
                out.append("<p>").append(markdownInlineToHtml(line)).append("</p>\n")
            }
        }
    }
    closeList()
    return out.toString()
}

/**
 * Builds a complete, self-contained HTML page for [title] + [markdown] content, ready to open in a
 * blank browser tab and print: the content is rendered from Markdown, laid out centered on an A4
 * sheet, and an on-load script opens the browser's print dialog. Hand the result to
 * [app.moviestudio.printDocument].
 */
fun buildPrintableDocumentHtml(title: String, markdown: String): String {
    val safeTitle = escapeHtml(title.ifBlank { "Document" })
    val body = markdownToPrintHtml(markdown)
    return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeTitle</title>
<style>
  @page { size: A4; margin: 18mm; }
  html { background: #525659; }
  body {
    margin: 0;
    font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    color: #111111;
    line-height: 1.5;
  }
  .page {
    box-sizing: border-box;
    width: 210mm;
    min-height: 297mm;
    margin: 24px auto;
    padding: 18mm;
    background: #ffffff;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.35);
    font-size: 12pt;
  }
  .doc-title { margin: 0 0 16px; font-size: 24pt; }
  h1 { font-size: 22pt; }
  h2 { font-size: 18pt; }
  h3 { font-size: 16pt; }
  h4, h5, h6 { font-size: 13pt; }
  p { margin: 0 0 10px; }
  ul, ol { margin: 0 0 10px; padding-left: 22px; }
  li { margin: 2px 0; }
  code { font-family: "Courier New", monospace; background: #f0f0f0; padding: 0 3px; border-radius: 3px; }
  @media print {
    html { background: #ffffff; }
    .page { width: auto; min-height: 0; margin: 0; padding: 0; box-shadow: none; }
  }
</style>
</head>
<body>
  <div class="page">
    <h1 class="doc-title">$safeTitle</h1>
    $body
  </div>
  <script>window.onload = function () { window.focus(); window.print(); };</script>
</body>
</html>
""".trimIndent()
}
