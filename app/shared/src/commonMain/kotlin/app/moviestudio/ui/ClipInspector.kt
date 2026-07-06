package app.moviestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.AssetType
import app.moviestudio.CAPTION_FONT_FAMILIES
import app.moviestudio.CaptionConfig
import app.moviestudio.Clip
import app.moviestudio.EffectsConfig
import app.moviestudio.MAX_CLIP_VOLUME
import app.moviestudio.SlideDirection
import app.moviestudio.Track
import app.moviestudio.TrackType
import app.moviestudio.TransitionSpec
import app.moviestudio.TransitionType
import app.moviestudio.VolumePoint
import app.moviestudio.clipCarriesAudio
import app.moviestudio.displayName
import app.moviestudio.parseEffectsConfig
import app.moviestudio.volumeAt
import kotlin.math.roundToInt

/**
 * Inspector for the selected timeline clip: transition-in over overlapping media (with a live
 * percentage-of-clip control), captions (voice clips), volume (any audio-carrying clip — the audio
 * tracks plus video clips whose media has sound) and clip actions.
 */
@Composable
fun ClipInspector(viewModel: AppViewModel, clip: Clip, track: Track) {
    val asset = viewModel.assetById(clip.assetId)
    val effects = parseEffectsConfig(clip.effectsConfig)
    val clipLength = (clip.trimOut - clip.trimIn).coerceAtLeast(0.25f)
    var showCaptionEditor by remember { mutableStateOf(false) }
    var showVolumeEditor by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Clip identity
        Column(Modifier.width(190.dp)) {
            Text(
                asset?.description ?: asset?.aiPrompt ?: "Clip",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${track.type.name.lowercase().replaceFirstChar { it.uppercase() }} clip • ${formatDuration(clipLength.toDouble())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Column {
                GhostPillButton("⧉ Duplicate", compact = true) { viewModel.duplicateClip(clip.id) }
                Spacer(Modifier.height(6.dp))
                GhostPillButton("🗑 Remove", compact = true) { viewModel.deleteClip(clip.id) }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Transition editor (visual tracks): blends this clip in over whatever plays beneath it.
        if (track.type == TrackType.VIDEO) {
            Column(Modifier.width(300.dp)) {
                Text(
                    "Transition",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropdownSelector(
                        label = null,
                        options = TransitionType.entries.toList(),
                        selected = effects.transition?.type ?: TransitionType.NONE,
                        display = { it.displayName() },
                        modifier = Modifier.width(150.dp)
                    ) { type ->
                        val spec = if (type == TransitionType.NONE) {
                            null
                        } else {
                            TransitionSpec(type, (effects.transition?.durationSeconds ?: 1.0).coerceAtMost(clipLength.toDouble()))
                        }
                        viewModel.updateClipEffects(clip, effects.copy(transition = spec))
                    }
                    Spacer(Modifier.width(10.dp))
                    val transition = effects.transition
                    if (transition != null && transition.type != TransitionType.NONE) {
                        val percent = ((transition.durationSeconds / clipLength) * 100).roundToInt().coerceIn(1, 100)
                        Column(Modifier.width(140.dp)) {
                            LabeledSlider(
                                label = "Window",
                                value = transition.durationSeconds.toFloat(),
                                valueRange = 0.1f..clipLength,
                                valueText = "${formatSeconds(transition.durationSeconds)}s • $percent%",
                                onValueChange = { newDuration ->
                                    viewModel.updateClipEffects(
                                        clip,
                                        effects.copy(transition = transition.copy(durationSeconds = newDuration.toDouble()))
                                    )
                                }
                            )
                        }
                    }
                }
                // Slide direction: which edge the clip enters from (SLIDE only).
                val transition = effects.transition
                if (transition != null && transition.type == TransitionType.SLIDE) {
                    Spacer(Modifier.height(6.dp))
                    DropdownSelector(
                        label = "Direction",
                        options = SlideDirection.entries.toList(),
                        selected = transition.direction,
                        display = { it.displayName() },
                        modifier = Modifier.width(150.dp)
                    ) { direction ->
                        viewModel.updateClipEffects(
                            clip,
                            effects.copy(transition = transition.copy(direction = direction))
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
        }

        // Crop position (visual tracks): where the media sits inside the center-crop window.
        // 0-100 on each axis, 50/50 = centered; applied in the preview and the final render.
        if (track.type == TrackType.VIDEO) {
            Column(Modifier.width(170.dp)) {
                LabeledSlider(
                    label = "Offset X",
                    value = effects.offsetX.toFloat(),
                    valueRange = 0f..100f,
                    valueText = "${effects.offsetX.roundToInt()}",
                    onValueChange = { viewModel.updateClipEffects(clip, effects.copy(offsetX = it.roundToInt().toDouble())) }
                )
                LabeledSlider(
                    label = "Offset Y",
                    value = effects.offsetY.toFloat(),
                    valueRange = 0f..100f,
                    valueText = "${effects.offsetY.roundToInt()}",
                    onValueChange = { viewModel.updateClipEffects(clip, effects.copy(offsetY = it.roundToInt().toDouble())) }
                )
            }
            Spacer(Modifier.width(16.dp))
        }

        // Volume (audio-carrying clips): every clip on an audio track, plus video clips on the
        // video track whose media carries an audio stream. Flat slider, or the volume-over-time
        // envelope once keyframes exist — with the advanced editor a click away in both cases.
        if (clipCarriesAudio(track.type, asset?.type)) {
            Column(Modifier.width(190.dp)) {
                if (effects.volumeKeyframes.isEmpty()) {
                    LabeledSlider(
                        label = "Volume",
                        value = effects.volume.toFloat(),
                        valueRange = 0f..MAX_CLIP_VOLUME.toFloat(),
                        valueText = "${(effects.volume * 100).roundToInt()}%",
                        onValueChange = { viewModel.updateClipEffects(clip, effects.copy(volume = it.toDouble())) }
                    )
                } else {
                    Text(
                        "Volume",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Envelope • ${effects.volumeKeyframes.size} keyframe(s)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                }
                GhostPillButton("🎚 Volume editor", compact = true) { showVolumeEditor = true }
            }
            Spacer(Modifier.width(16.dp))
        }

        // Captions (voice clips): quick toggle + the advanced editor.
        if (track.type == TrackType.VOICE) {
            Column {
                Text(
                    "Captions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = effects.captions?.enabled == true,
                        onCheckedChange = { enabled ->
                            val captions = (effects.captions ?: CaptionConfig()).copy(enabled = enabled)
                            viewModel.updateClipEffects(clip, effects.copy(captions = captions))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    GhostPillButton("Caption editor", compact = true) { showCaptionEditor = true }
                }
            }
        }
    }

    if (showCaptionEditor) {
        CaptionEditorDialog(
            initial = effects.captions ?: CaptionConfig(enabled = true),
            onDismiss = { showCaptionEditor = false },
            onSave = { config ->
                viewModel.updateClipEffects(clip, effects.copy(captions = config))
                showCaptionEditor = false
            }
        )
    }

    if (showVolumeEditor) {
        VolumeEnvelopeDialog(
            clipLengthSeconds = clipLength,
            initial = effects,
            onDismiss = { showVolumeEditor = false },
            onSave = { keyframes ->
                viewModel.updateClipEffects(clip, effects.copy(volumeKeyframes = keyframes))
                showVolumeEditor = false
            }
        )
    }
}

private fun formatSeconds(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return rounded.toString()
}

/** Advanced caption editor: font chooser (incl. "my fonts"), size, color and placement. */
@Composable
fun CaptionEditorDialog(
    initial: CaptionConfig,
    onDismiss: () -> Unit,
    onSave: (CaptionConfig) -> Unit
) {
    var config by remember { mutableStateOf(initial.copy(enabled = true)) }
    var showCustomColorPicker by remember { mutableStateOf(false) }

    StudioDialog(title = "Caption editor", onDismiss = onDismiss, width = 480.dp) {
        // Live preview strip (always dark, like the movie stage).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF121016)),
            contentAlignment = when (config.position) {
                "top" -> Alignment.TopCenter
                "center" -> Alignment.Center
                else -> Alignment.BottomCenter
            }
        ) {
            Text(
                "Captions look like this",
                color = parseHexColor(config.color),
                fontSize = (config.fontSizeSp * 0.6).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = captionFontFamily(config.fontFamily),
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        DropdownSelector(
            label = "Font",
            options = CAPTION_FONT_FAMILIES,
            selected = config.fontFamily,
            display = { it }
        ) { config = config.copy(fontFamily = it) }
        Spacer(Modifier.height(8.dp))

        LabeledSlider(
            label = "Size",
            value = config.fontSizeSp.toFloat(),
            valueRange = 14f..64f,
            valueText = "${config.fontSizeSp}",
            onValueChange = { config = config.copy(fontSizeSp = it.roundToInt()) }
        )

        SectionLabel("Color")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presetColors = listOf("#FFFFFF", "#FFE45E", "#7FE0A7", "#7FC6FF", "#FF9EC1", "#FF6B5E")
            presetColors.forEach { hex ->
                val selected = config.color.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape) // clip BEFORE clickable: round hover
                        .background(parseHexColor(hex))
                        .clickable { config = config.copy(color = hex) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            // Custom color: last swatch in the row, opens a small hex-entry popup. Once a
            // non-preset color is active, this swatch shows that color instead of the "+" glyph.
            val isCustomColorActive = presetColors.none { it.equals(config.color, ignoreCase = true) }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape) // clip BEFORE clickable: round hover
                    .background(if (isCustomColorActive) parseHexColor(config.color) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showCustomColorPicker = true },
                contentAlignment = Alignment.Center
            ) {
                if (isCustomColorActive) {
                    Text("✓", color = Color.Black, fontWeight = FontWeight.Bold)
                } else {
                    Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
        }

        SectionLabel("Position")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (value, label) ->
                val selected = config.position == value
                if (selected) {
                    PillButton(label, compact = true) { }
                } else {
                    GhostPillButton(label, compact = true) { config = config.copy(position = value) }
                }
            }
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save captions") { onSave(config) }
        }
    }

    if (showCustomColorPicker) {
        CustomColorPickerDialog(
            initialHex = config.color,
            onDismiss = { showCustomColorPicker = false },
            onPick = { hex ->
                config = config.copy(color = hex)
                showCustomColorPicker = false
            }
        )
    }
}

/** A valid `#RRGGBB` or `#AARRGGBB` hex color string. */
private fun isValidHexColor(hex: String): Boolean =
    Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(hex)

/** `[hue (0..360), saturation (0..1), value (0..1)]` for an RGB color (each component 0..1). */
private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}

/**
 * Formats HSVA (`hue` 0..360, `saturation`/`value`/`alpha` 0..1) into a `#RRGGBB` string, or
 * `#AARRGGBB` when the color is not fully opaque.
 */
private fun hsvaToHex(hue: Float, saturation: Float, value: Float, alpha: Float): String {
    val color = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    fun channel(component: Float) = (component * 255f).roundToInt().coerceIn(0, 255)
    fun hex2(x: Int) = x.toString(16).padStart(2, '0').uppercase()
    val r = hex2(channel(color.red))
    val g = hex2(channel(color.green))
    val b = hex2(channel(color.blue))
    val a = channel(alpha)
    return if (a >= 255) "#$r$g$b" else "#${hex2(a)}$r$g$b"
}

/**
 * Custom color picker: HSVA sliders (hue, saturation, value, alpha) driving a live preview, plus
 * a synced hex field so a color can also be typed. Every control stays in step with the others.
 */
@Composable
private fun CustomColorPickerDialog(
    initialHex: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val initialColor = if (isValidHexColor(initialHex)) parseHexColor(initialHex) else Color.White
    val initialHsv = rgbToHsv(initialColor.red, initialColor.green, initialColor.blue)
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var saturation by remember { mutableStateOf(initialHsv[1]) }
    var brightness by remember { mutableStateOf(initialHsv[2]) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }
    var hex by remember { mutableStateOf(hsvaToHex(initialHsv[0], initialHsv[1], initialHsv[2], initialColor.alpha)) }

    // Keep the hex field mirroring the sliders whenever one moves.
    fun syncHexFromHsva() {
        hex = hsvaToHex(hue, saturation, brightness, alpha)
    }

    val valid = isValidHexColor(hex)
    val previewColor = Color.hsv(
        hue.coerceIn(0f, 360f),
        saturation.coerceIn(0f, 1f),
        brightness.coerceIn(0f, 1f),
        alpha.coerceIn(0f, 1f)
    )

    StudioDialog(title = "Custom color", onDismiss = onDismiss, width = 320.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(previewColor)
            )
            Spacer(Modifier.width(12.dp))
            StudioTextField(
                value = hex,
                onValueChange = { input ->
                    hex = input
                    // Typing a full, valid hex drives the sliders back the other way.
                    if (isValidHexColor(input)) {
                        val color = parseHexColor(input)
                        val hsv = rgbToHsv(color.red, color.green, color.blue)
                        hue = hsv[0]
                        saturation = hsv[1]
                        brightness = hsv[2]
                        alpha = color.alpha
                    }
                },
                modifier = Modifier.width(160.dp),
                label = "Hex",
                placeholder = "#RRGGBB",
                singleLine = true,
                autoFocus = false,
                onSubmit = { if (valid) onPick(hex) },
                onDismiss = onDismiss
            )
        }

        Spacer(Modifier.height(12.dp))
        LabeledSlider(
            label = "Hue",
            value = hue,
            valueRange = 0f..360f,
            valueText = "${hue.roundToInt()}°",
            onValueChange = { hue = it; syncHexFromHsva() }
        )
        LabeledSlider(
            label = "Saturation",
            value = saturation * 100f,
            valueRange = 0f..100f,
            valueText = "${(saturation * 100f).roundToInt()}%",
            onValueChange = { saturation = it / 100f; syncHexFromHsva() }
        )
        LabeledSlider(
            label = "Value",
            value = brightness * 100f,
            valueRange = 0f..100f,
            valueText = "${(brightness * 100f).roundToInt()}%",
            onValueChange = { brightness = it / 100f; syncHexFromHsva() }
        )
        LabeledSlider(
            label = "Alpha",
            value = alpha * 100f,
            valueRange = 0f..100f,
            valueText = "${(alpha * 100f).roundToInt()}%",
            onValueChange = { alpha = it / 100f; syncHexFromHsva() }
        )

        if (!valid) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Enter a color as #RRGGBB or #AARRGGBB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Use color", enabled = valid) { onPick(hex) }
        }
    }
}

/** Maps a canvas position to a volume keyframe (time across the width, gain down the height). */
private fun offsetToVolumePoint(
    offset: Offset,
    widthPx: Int,
    heightPx: Int,
    clipLength: Double
): VolumePoint {
    val time = (offset.x / widthPx * clipLength).coerceIn(0.0, clipLength)
    val volume = ((1f - offset.y / heightPx) * MAX_CLIP_VOLUME).coerceIn(0.0, MAX_CLIP_VOLUME)
    return VolumePoint(time = time, volume = volume)
}

/** The canvas position of a volume keyframe (inverse of [offsetToVolumePoint]). */
private fun volumePointOffset(
    point: VolumePoint,
    widthPx: Int,
    heightPx: Int,
    clipLength: Double
): Offset = Offset(
    (point.time / clipLength * widthPx).toFloat(),
    ((1.0 - point.volume / MAX_CLIP_VOLUME) * heightPx).toFloat()
)

/** The keyframe within [thresholdPx] of the pointer, if any (nearest wins). */
private fun volumePointNear(
    points: List<VolumePoint>,
    offset: Offset,
    widthPx: Int,
    heightPx: Int,
    clipLength: Double,
    thresholdPx: Float
): VolumePoint? = points
    .minByOrNull { (volumePointOffset(it, widthPx, heightPx, clipLength) - offset).getDistance() }
    ?.takeIf { (volumePointOffset(it, widthPx, heightPx, clipLength) - offset).getDistance() <= thresholdPx }

/**
 * Advanced volume editor: the clip's loudness over time as a keyframed envelope drawn across the
 * clip's length. Tap adds a keyframe, dragging moves one (or creates one and drags it),
 * double-tap removes one and "Reset to flat" returns the clip to its single flat volume.
 * The envelope is applied in the preview and in the final render.
 */
@Composable
fun VolumeEnvelopeDialog(
    clipLengthSeconds: Float,
    initial: EffectsConfig,
    onDismiss: () -> Unit,
    onSave: (List<VolumePoint>) -> Unit
) {
    val clipLength = clipLengthSeconds.toDouble().coerceAtLeast(0.1)
    var points by remember { mutableStateOf(initial.volumeKeyframes.sortedBy { it.time }) }
    // The keyframe currently being moved by a drag on the canvas.
    var dragging by remember { mutableStateOf<VolumePoint?>(null) }
    // The envelope previewed on the canvas: the edited keyframes over the clip's flat volume.
    val preview = initial.copy(volumeKeyframes = points)

    StudioDialog(title = "Volume editor", onDismiss = onDismiss, width = 620.dp) {
        Text(
            "Shape the clip's loudness over time. Tap the curve area to add a keyframe, drag a " +
                "keyframe to move it and double-tap one to remove it. 100% plays the sound at " +
                "its natural loudness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
                // Tap: add a keyframe (double-tap an existing one to remove it).
                .pointerInput(clipLength) {
                    detectTapGestures(
                        onTap = { offset ->
                            val existing = volumePointNear(points, offset, size.width, size.height, clipLength, 22.dp.toPx())
                            if (existing == null) {
                                points = (points + offsetToVolumePoint(offset, size.width, size.height, clipLength))
                                    .sortedBy { it.time }
                            }
                        },
                        onDoubleTap = { offset ->
                            volumePointNear(points, offset, size.width, size.height, clipLength, 22.dp.toPx())?.let {
                                points = points - it
                            }
                        }
                    )
                }
                // Drag: move the nearest keyframe, or place a new one and drag it into shape.
                .pointerInput(clipLength) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val anchor = volumePointNear(points, offset, size.width, size.height, clipLength, 26.dp.toPx())
                                ?: offsetToVolumePoint(offset, size.width, size.height, clipLength).also {
                                    points = (points + it).sortedBy { point -> point.time }
                                }
                            dragging = anchor
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val anchor = dragging ?: return@detectDragGestures
                            val moved = offsetToVolumePoint(change.position, size.width, size.height, clipLength)
                            points = points.map { if (it == anchor) moved else it }.sortedBy { it.time }
                            dragging = moved
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Gain grid: lines at 0/50/100/150/200%, the 100% line drawn brighter.
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(
                        color = if (i == 2) Color(0xFF3D3850) else Color(0xFF272232),
                        start = Offset(0f, y),
                        end = Offset(size.width, y)
                    )
                }
                // The envelope, sampled across the clip (flat volume when no keyframes exist).
                val steps = 120
                var previous = Offset(
                    0f,
                    ((1.0 - preview.volumeAt(0.0).coerceIn(0.0, MAX_CLIP_VOLUME) / MAX_CLIP_VOLUME) * size.height).toFloat()
                )
                for (i in 1..steps) {
                    val time = clipLength * i / steps
                    val gain = preview.volumeAt(time).coerceIn(0.0, MAX_CLIP_VOLUME)
                    val next = Offset(
                        size.width * i / steps,
                        ((1.0 - gain / MAX_CLIP_VOLUME) * size.height).toFloat()
                    )
                    drawLine(Color(0xFF8F7BFF), previous, next, strokeWidth = 3f)
                    previous = next
                }
                // Keyframe handles.
                points.forEach { point ->
                    val center = Offset(
                        (point.time / clipLength * size.width).toFloat(),
                        ((1.0 - point.volume.coerceIn(0.0, MAX_CLIP_VOLUME) / MAX_CLIP_VOLUME) * size.height).toFloat()
                    )
                    drawCircle(Color(0xFFFF5A9E), radius = 7f, center = center)
                    drawCircle(Color(0xFF17151C), radius = 3f, center = center)
                }
            }
            // Gain scale labels.
            listOf(
                "200%" to Alignment.TopStart,
                "100%" to Alignment.CenterStart,
                "0%" to Alignment.BottomStart
            ).forEach { (label, alignment) ->
                Text(
                    label,
                    modifier = Modifier.align(alignment).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    color = Color(0xFF6F6884)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(clipLength),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (points.isEmpty()) {
                "No keyframes — the clip plays at its flat ${(initial.volume * 100).roundToInt()}% volume."
            } else {
                "${points.size} keyframe(s). The volume between keyframes fades smoothly."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DialogActions {
            GhostPillButton("Reset to flat", compact = true, enabled = points.isNotEmpty()) { points = emptyList() }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save volume") { onSave(points) }
        }
    }
}
