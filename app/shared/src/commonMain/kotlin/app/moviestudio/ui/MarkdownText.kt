package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders [markdown] source as read-only, formatted text. Unlike the document editor's
 * length-preserving [markdownVisualTransformation] (which keeps the raw syntax markers visible so
 * offsets map 1:1), this strips the markers and applies the actual styles — the right thing for
 * showing an AI reply in a chat bubble.
 *
 * It understands the same Markdown flavor the rest of the app produces/consumes: `#`..`######`
 * headings, `-`/`*`/`+` bullets, `1.` numbered lists, fenced ``` code blocks, and inline
 * `**bold**` / `*italic*` / `***bold italic***` / `~~strike~~` / `<u>underline</u>` / `` `code` ``
 * plus `[label](url)` links. Anything that doesn't parse is shown verbatim, so plain text is
 * always safe.
 *
 * Each block renders as its own [Text] inside a [Column], so headings and lists can differ in size
 * and lists can hang-indent. The whole thing is drawn read-only; wrap it in a `SelectionContainer`
 * at the call site if the text needs to be selectable.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        val lines = markdown.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                // Fenced code block: everything up to the closing fence is shown verbatim.
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume the closing fence
                Text(
                    text = code.toString(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(codeBackground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = style.copy(fontFamily = FontFamily.Monospace),
                    color = color
                )
                continue
            }

            val heading = HEADING_REGEX.find(line)
            val bullet = BULLET_REGEX.find(line)
            val numbered = NUMBERED_REGEX.find(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                heading != null -> Text(
                    text = parseInlineMarkdown(line.substring(heading.value.length), codeBackground, linkColor),
                    style = style.copy(
                        fontSize = headingFontSize(heading.groupValues[1].length),
                        fontWeight = FontWeight.Bold
                    ),
                    color = color
                )
                bullet != null -> MarkdownListItem(
                    marker = "•",
                    content = line.substring(bullet.value.length),
                    style = style,
                    color = color,
                    codeBackground = codeBackground,
                    linkColor = linkColor
                )
                numbered != null -> MarkdownListItem(
                    marker = numbered.value.trim(),
                    content = line.substring(numbered.value.length),
                    style = style,
                    color = color,
                    codeBackground = codeBackground,
                    linkColor = linkColor
                )
                else -> Text(
                    text = parseInlineMarkdown(line, codeBackground, linkColor),
                    style = style,
                    color = color
                )
            }
            i++
        }
    }
}

/** One list row: the [marker] ("•" or "1.") kept on its own so wrapped lines hang-indent. */
@Composable
private fun MarkdownListItem(
    marker: String,
    content: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    linkColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$marker ", style = style, color = color)
        Text(
            text = parseInlineMarkdown(content, codeBackground, linkColor),
            modifier = Modifier.weight(1f),
            style = style,
            color = color
        )
    }
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+""")
private val BULLET_REGEX = Regex("""^\s*[-*+]\s+""")
private val NUMBERED_REGEX = Regex("""^\s*\d+\.\s+""")
private val LINK_REGEX = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

/** Maps a heading level (number of leading `#`) to a rendered font size, biggest for `#`. */
private fun headingFontSize(level: Int): TextUnit = when (level) {
    1 -> 22.sp
    2 -> 19.sp
    3 -> 17.sp
    4 -> 15.sp
    else -> 14.sp
}

/**
 * Parses the inline Markdown in [text] into a styled [AnnotatedString] with the syntax markers
 * removed. Emphasis nests (e.g. bold inside a link label), inline `` `code` `` is taken verbatim,
 * and any unterminated marker is emitted literally so the output can never lose characters.
 */
internal fun parseInlineMarkdown(
    text: String,
    codeBackground: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified
): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(text, codeBackground, linkColor)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color
) {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            // Inline code is opaque: no nested styling, marker stripped.
            c == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append(c); i++
                }
            }
            // `***bold italic***` must be tried before `**` and `*`.
            text.startsWith("***", i) -> {
                val close = text.indexOf("***", i + 3)
                if (close > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(text.substring(i + 3, close), codeBackground, linkColor)
                    }
                    i = close + 3
                } else {
                    append(c); i++
                }
            }
            text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(text.substring(i + 2, close), codeBackground, linkColor)
                    }
                    i = close + 2
                } else {
                    append(c); i++
                }
            }
            text.startsWith("~~", i) -> {
                val close = text.indexOf("~~", i + 2)
                if (close > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInlineMarkdown(text.substring(i + 2, close), codeBackground, linkColor)
                    }
                    i = close + 2
                } else {
                    append(c); i++
                }
            }
            text.startsWith("<u>", i, ignoreCase = true) -> {
                val close = text.indexOf("</u>", i + 3, ignoreCase = true)
                if (close > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        appendInlineMarkdown(text.substring(i + 3, close), codeBackground, linkColor)
                    }
                    i = close + 4
                } else {
                    append(c); i++
                }
            }
            // Single-star italic: needs a non-space right after the marker and a closing star.
            c == '*' && i + 1 < n && !text[i + 1].isWhitespace() -> {
                val close = text.indexOf('*', i + 1)
                if (close > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(text.substring(i + 1, close), codeBackground, linkColor)
                    }
                    i = close + 1
                } else {
                    append(c); i++
                }
            }
            c == '[' -> {
                val match = LINK_REGEX.matchAt(text, i)
                if (match != null) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        appendInlineMarkdown(match.groupValues[1], codeBackground, linkColor)
                    }
                    i += match.value.length
                } else {
                    append(c); i++
                }
            }
            else -> {
                append(c); i++
            }
        }
    }
}
