package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.moviestudio.AiChatMessage
import app.moviestudio.AiChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reusable "AI generate with review" dialog: shows the prompt that is about to be sent to the
 * AI so the user can inspect and edit it first, then presents the AI's response and lets the user
 * send follow-up messages to refine it, chat-style. When the user is happy, the accept button
 * hands the latest AI response back via [onAccept].
 *
 * The component is intentionally generic — every label/placeholder is customizable and the actual
 * AI call is injected via [generate], so any feature (lyrics, themes, and whatever comes next) can
 * reuse it by plugging in its own endpoint.
 *
 * @param title dialog title, e.g. "AI-generate lyrics".
 * @param initialPrompt the prompt prefilled in the editable field (exactly what would previously
 *   have been sent blindly).
 * @param generate performs the AI call with the full conversation (newest message last) and
 *   returns the AI's text response. Exceptions are caught and surfaced as an inline error.
 * @param onAccept invoked with the latest AI response when the user accepts it. The caller is
 *   responsible for closing the dialog (typically alongside [onDismiss]).
 * @param onDismiss invoked when the user cancels/closes the dialog.
 * @param description optional helper text shown under the title.
 * @param promptLabel / [promptPlaceholder] customize the editable prompt field.
 * @param generateLabel label of the button sending the initial prompt.
 * @param followUpPlaceholder hint of the follow-up input shown once a conversation has started.
 * @param acceptLabel label of the button accepting the latest AI response.
 * @param copiedLabel transient confirmation shown after a chat message is copied to the clipboard.
 * @param width dialog width.
 */
@Composable
fun AiPromptDialog(
    title: String,
    initialPrompt: String,
    generate: suspend (messages: List<AiChatMessage>) -> String,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    promptLabel: String = "Prompt",
    promptPlaceholder: String? = null,
    generateLabel: String = "✨ Generate",
    followUpPlaceholder: String = "Ask for changes: shorter, happier, more dramatic...",
    acceptLabel: String = "✅ Use result",
    copiedLabel: String = "Copied to clipboard",
    width: Dp = 560.dp
) {
    var prompt by remember { mutableStateOf(initialPrompt) }
    var followUp by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<AiChatMessage>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Bumped every time a message is copied so the confirmation toast (re)appears and its
    // auto-dismiss timer restarts, even if a previous toast is still fading out.
    var copyEventId by remember { mutableStateOf(0) }
    var copyToastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(copyEventId) {
        if (copyEventId == 0) return@LaunchedEffect
        copyToastVisible = true
        delay(1500)
        copyToastVisible = false
    }

    val latestResponse = messages.lastOrNull { it.role == AiChatRole.ASSISTANT }?.content
    val conversationStarted = messages.isNotEmpty()

    // Sends [text] as the next user message. On failure the message is rolled back and restored
    // into its input field so nothing the user typed is lost.
    fun send(text: String, restoreOnFailure: (String) -> Unit) {
        val content = text.trim()
        if (content.isBlank() || loading) return
        error = null
        loading = true
        val history = messages + AiChatMessage(role = AiChatRole.USER, content = content)
        messages = history
        scope.launch {
            try {
                val reply = generate(history)
                messages = history + AiChatMessage(role = AiChatRole.ASSISTANT, content = reply)
            } catch (e: Exception) {
                messages = history.dropLast(1)
                restoreOnFailure(content)
                error = e.message ?: "AI generation failed"
            } finally {
                loading = false
            }
        }
    }

    StudioDialog(title = title, onDismiss = onDismiss, width = width, scrollable = false) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (!conversationStarted) {
                    // Review stage: the exact prompt that will be sent, fully editable.
                    StudioTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = promptLabel,
                        placeholder = promptPlaceholder,
                        minLines = 2,
                        maxLines = 6,
                        autoFocus = true,
                        enabled = !loading,
                        onSubmit = { send(prompt) { restored -> prompt = restored } },
                        onDismiss = onDismiss,
                        // This field lives *inside* the AI dialog; disable its own Alt+Enter assist
                        // so it can't recursively open another AI prompt dialog.
                        aiGenerate = null
                    )
                } else {
                    // Conversation stage: the transcript so far, newest message last. Bounded to a max
                    // height and independently scrollable so the follow-up input and dialog buttons stay
                    // visible at the bottom even in long conversations.
                    val transcriptScroll = rememberScrollState()
                    // Keep the newest message in view as the conversation grows.
                    LaunchedEffect(transcriptScroll) {
                        snapshotFlow { transcriptScroll.maxValue }
                            .collect { max -> transcriptScroll.animateScrollTo(max) }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(transcriptScroll)
                    ) {
                        messages.forEach { message ->
                            AiChatBubble(message, onCopy = { copyEventId++ })
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "I'm thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                error?.let {
                    Text(
                        "⚠️ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (conversationStarted && !loading) {
                    // Follow-up input: refine the latest response without starting over.
                    StudioTextField(
                        value = followUp,
                        onValueChange = { followUp = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Follow-up",
                        placeholder = followUpPlaceholder,
                        minLines = 1,
                        maxLines = 4,
                        autoFocus = true,
                        onSubmit = {
                            send(followUp) { restored -> followUp = restored }
                            followUp = ""
                        },
                        onDismiss = onDismiss,
                        // Inside the AI dialog already: no nested Alt+Enter assist.
                        aiGenerate = null,
                        trailingIcon = {
                            RoundIconButton(
                                "➤",
                                contentDescription = "Send follow-up",
                                enabled = followUp.isNotBlank(),
                                size = 28.dp
                            ) {
                                send(followUp) { restored -> followUp = restored }
                                followUp = ""
                            }
                        }
                    )
                }

                DialogActions {
                    GhostPillButton("Cancel") { onDismiss() }
                    if (conversationStarted) {
                        ActionSpacer()
                        // Back to the editable prompt (prefilled with the first message as sent).
                        GhostPillButton("↺ Start over", enabled = !loading) {
                            prompt = messages.firstOrNull { it.role == AiChatRole.USER }?.content ?: prompt
                            messages = emptyList()
                            followUp = ""
                            error = null
                        }
                    }
                    ActionSpacer()
                    if (!conversationStarted) {
                        PillButton(generateLabel, enabled = prompt.isNotBlank() && !loading) {
                            send(prompt) { restored -> prompt = restored }
                        }
                    } else {
                        PillButton(acceptLabel, enabled = latestResponse != null && !loading) {
                            latestResponse?.let(onAccept)
                        }
                    }
                }
            }

            // Transient confirmation, overlaid at the bottom so it doesn't shift any layout.
            StudioToast(
                message = copiedLabel,
                visible = copyToastVisible,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * One transcript entry: user messages on the right, AI responses on the left. Hovering the bubble
 * reveals a copy button in its top-right corner, and clicking anywhere on the bubble copies the
 * whole message to the clipboard (invoking [onCopy] so the caller can surface a confirmation).
 */
@Composable
private fun AiChatBubble(message: AiChatMessage, onCopy: () -> Unit) {
    val fromUser = message.role == AiChatRole.USER
    val clipboard = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (fromUser) 12.dp else 2.dp,
        bottomEnd = if (fromUser) 2.dp else 12.dp
    )
    fun copy() {
        clipboard.setText(AnnotatedString(message.content))
        onCopy()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        // Track hover on the wrapper so moving onto the overlaid copy button keeps it visible.
        Box(modifier = Modifier.hoverable(interactionSource)) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .clip(bubbleShape) // clip BEFORE clickable so the highlight has rounded corners
                    .clickable(onClickLabel = "Copy message") { copy() }
                    .background(
                        if (fromUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    if (fromUser) "You" else "✨ AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                SelectionContainer {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fromUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            // Copy affordance, revealed on hover in the bubble's top-right corner.
            if (hovered) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    RoundIconButton(
                        "📋",
                        contentDescription = "Copy message",
                        background = MaterialTheme.colorScheme.surface,
                        size = 26.dp,
                        onClick = { copy() }
                    )
                }
            }
        }
    }
}
