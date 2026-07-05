package app.moviestudio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.moviestudio.UploadState
import app.moviestudio.VideoPlayer
import app.moviestudio.startRealtimeSpeechInput
import app.moviestudio.stopRealtimeSpeechInput
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks whether any studio text input currently has keyboard focus. The editor uses this so the
 * space bar only toggles playback while the user is *not* typing.
 */
object TextInputFocusTracker {
    var focusedFields by mutableStateOf(0)
        private set

    val anyFocused: Boolean get() = focusedFields > 0

    fun onFocusChanged(wasFocused: Boolean, isFocused: Boolean) {
        if (!wasFocused && isFocused) focusedFields++
        else if (wasFocused && !isFocused) focusedFields = maxOf(0, focusedFields - 1)
    }
}

/** The corner shape every studio text input uses: large rounded corners (global theme rule). */
val StudioFieldShape = RoundedCornerShape(18.dp)

/**
 * The app-wide text input: large rounded corners and a 50% white-alpha background (global theme
 * rules), plus focus bookkeeping for the space-bar playback shortcut.
 *
 * Every instance also supports hold-to-dictate: long-press the field (pointer or touch) to start
 * realtime speech-to-text — recognized words stream into the field while the press is held, and
 * dictation stops the moment the press is released. While dictation is live the border glows in
 * the error color and the placeholder switches to "Start speaking...". On the web this uses the
 * browser Web Speech API when present and otherwise streams the mic to the server's realtime ASR
 * relay (so Firefox works too); on platforms with no speech support the long-press does nothing.
 */
@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    autoFocus: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onDismiss: () -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }

    // Autofocus the title input so the user can start typing right away.
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focus.requestFocus() }
        }
    }
    var wasFocused by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    // The dictation callback fires from outside the composition, so it must always see the
    // freshest value/callback of this field.
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnSubmit by rememberUpdatedState(onSubmit)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // A dictation session left running (e.g. the field leaves the composition mid-press) must
    // not keep the microphone open.
    DisposableEffect(Unit) {
        onDispose { if (dictating) stopRealtimeSpeechInput() }
    }
    val fieldBackground = MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { state ->
                TextInputFocusTracker.onFocusChanged(wasFocused, state.isFocused)
                wasFocused = state.isFocused
            }
            // Ctrl+Enter submits the field (e.g. sends a chat message) without inserting a
            // newline via the normal Enter key.
            .onPreviewKeyEvent { keyEvent ->
                when (keyEvent.type) {
                    KeyEventType.KeyDown if keyEvent.isCtrlPressed && keyEvent.key == Key.Enter -> {
                        latestOnSubmit()
                        true
                    }
                    KeyEventType.KeyDown if keyEvent.key == Key.Escape -> {
                        latestOnDismiss()
                        true
                    }
                    else -> {
                        false
                    }
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
                        // for the rest of the field's lifetime).
                        val base = latestValue
                        val started = runCatching {
                            startRealtimeSpeechInput { spoken ->
                                val prefix = if (base.isBlank()) "" else base.trimEnd() + " "
                                latestOnValueChange(prefix + spoken)
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
        label = label?.let { { Text(it) } },
        placeholder = when {
            // Live dictation: invite the user to talk (visible while the field is still empty).
            dictating -> {
                { Text("Start speaking...", color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)) }
            }
            placeholder != null -> {
                { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) }
            }
            else -> null
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        textStyle = textStyle,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = StudioFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = fieldBackground,
            unfocusedContainerColor = fieldBackground,
            disabledContainerColor = Color.White.copy(alpha = 0.25f),
            focusedBorderColor = if (dictating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = if (dictating) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        )
    )
}

/**
 * Standard studio dialog chrome: rounded surface, title row with a close affordance and
 * scrollable content.
 */
@Composable
fun StudioDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 560.dp,
    maxHeight: Dp = 640.dp,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = width),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    RoundIconButton("✕", contentDescription = "Close") { onDismiss() }
                }
                Spacer(Modifier.size(12.dp))
                if (scrollable) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                } else {
                    Column(modifier = Modifier.heightIn(max = maxHeight)) {
                        content()
                    }
                }
            }
        }
    }
}

/** Small circular icon button drawn with an emoji/text glyph (clipped before clickable). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundIconButton(
    glyph: String,
    contentDescription: String? = null,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 32.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape) // clip BEFORE clickable so the hover highlight is round
            .background(background)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = if (enabled) tint else tint.copy(alpha = 0.4f), fontSize = (size.value * 0.45).sp)
    }
}

/** Pill-shaped primary action button (clipped before clickable per project guidelines). */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape) // clip BEFORE clickable so hover has rounded corners
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = contentColor,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** Ghost/secondary pill button. */
@Composable
fun GhostPillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    PillButton(
        text = text,
        modifier = modifier,
        enabled = enabled,
        container = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        compact = compact,
        onClick = onClick
    )
}

/** Section label used inside dialogs and side panels. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

/**
 * Generic dropdown selector rendered as a rounded field (clipped before clickable) that opens a
 * menu of [options].
 */
@Composable
fun <T> DropdownSelector(
    label: String?,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(StudioFieldShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    display(selected),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(display(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

/** A labelled slider with a trailing value readout. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled
        )
    }
}

/** Row of dialog action buttons aligned to the end. */
@Composable
fun DialogActions(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** Fixed-width spacer used between dialog action buttons. */
@Composable
fun ActionSpacer() {
    Spacer(Modifier.width(10.dp))
}

/**
 * Confirmation dialog shown before destructive actions (deleting movies, assets, voices, ...).
 * The confirm button is error-colored; confirming also dismisses the dialog.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    StudioDialog(title = title, onDismiss = onDismiss, width = 420.dp) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                confirmLabel,
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                onDismiss()
                onConfirm()
            }
        }
    }
}

/**
 * Inline "error + retry" state shown by major components (movie list, library, timeline...)
 * when their data failed to load.
 */
@Composable
fun ErrorRetryBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️", fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(12.dp))
            PillButton("🔁 Retry", compact = true) { onRetry() }
        }
    }
}

/** Small chip used to show a selected reference (character, scene, image) with a remove action. */
@Composable
fun RemovableChip(
    text: String,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        RoundIconButton("✕", size = 22.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer) { onRemove() }
    }
}

/**
 * A rounded image thumbnail (Coil [AsyncImage], center-cropped) used across the dialogs to preview
 * a selected/attached image. When [onRemove] is provided a small ✕ badge overlays the top-right
 * corner so the image can be detached.
 */
@Composable
fun ImageThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onRemove: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Image preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (onRemove != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onRemove() }
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("✕", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/**
 * A lightweight inline video preview: renders the video at a bounded, aspect-correct size with a
 * dedicated play/pause button placed BELOW it. Used by dialogs that need to preview a video asset
 * without the full editor transport controls.
 *
 * The play/pause control is intentionally kept out of the video's own bounds: on web the player is
 * a shared native `<video>` overlay drawn above the Compose canvas, so an overlaid button would be
 * hidden and unclickable. Placing it beneath the frame keeps it visible and tappable everywhere.
 */
@Composable
fun VideoPreview(
    url: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp
) {
    var playing by remember(url) { mutableStateOf(false) }
    var playhead by remember(url) { mutableStateOf(0f) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bounded, aspect-correct frame (16:9), centered within the available width.
        Box(
            modifier = Modifier
                .height(height)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            VideoPlayer(
                url = url,
                isPlaying = playing,
                playhead = playhead,
                onTimeUpdate = { playhead = it },
                modifier = Modifier.fillMaxSize(),
                // Reset to the start on natural end so the button reads "Play" again and the next
                // tap restarts the clip instead of trying to resume from (and re-triggering) the end.
                onEnded = {
                    playing = false
                    playhead = 0f
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        GhostPillButton(if (playing) "⏸ Pause" else "▶ Play", compact = true) { playing = !playing }
    }
}

/**
 * Live upload progress bar shown while a device upload is in flight (file pick, reference image,
 * voice sample or mic recording). Renders the [state]'s label plus a determinate progress bar
 * driven by its completion fraction, animated so it advances smoothly between progress ticks.
 */
@Composable
fun UploadProgressBar(state: UploadState, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = state.fraction.coerceIn(0f, 1f),
        label = "uploadProgress"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "📤 ${state.label}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(animated * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    }
}

/**
 * A transient "toast" pill — a rounded [Surface] with a single line of text — overlaid inside a
 * [Box] (hence the [BoxScope] receiver) so it floats above the content without shifting any layout.
 * Every visual aspect ([alignment], [containerColor]/[contentColor], [shape], [textStyle],
 * [contentPadding]) is customizable so the same component serves confirmations
 * ("Copied to clipboard"), errors, and whatever comes next. Callers own the show/hide timing and
 * can gate rendering with [visible]; outer positioning/margins are supplied via [modifier]
 * (e.g. `Modifier.padding(24.dp)`).
 */
@Composable
fun BoxScope.StudioToast(
    message: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    alignment: Alignment = Alignment.BottomCenter,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    shape: Shape = RoundedCornerShape(50),
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    if (!visible) return
    Surface(
        modifier = Modifier.align(alignment).then(modifier),
        shape = shape,
        color = containerColor,
        tonalElevation = 6.dp
    ) {
        Text(
            message,
            modifier = Modifier.padding(contentPadding),
            color = contentColor,
            style = textStyle
        )
    }
}
