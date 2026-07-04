package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.CAPTION_FONT_FAMILIES
import app.moviestudio.CaptionConfig
import app.moviestudio.Clip
import app.moviestudio.Track
import app.moviestudio.TrackType
import app.moviestudio.TransitionSpec
import app.moviestudio.TransitionType
import app.moviestudio.displayName
import app.moviestudio.parseEffectsConfig
import kotlin.math.roundToInt

/**
 * Inspector for the selected timeline clip: transition-in over overlapping media (with a live
 * percentage-of-clip control), captions (voice clips), volume (audio clips) and clip actions.
 */
@Composable
fun ClipInspector(viewModel: AppViewModel, clip: Clip, track: Track) {
    val asset = viewModel.assetById(clip.assetId)
    val effects = parseEffectsConfig(clip.effectsConfig)
    val clipLength = (clip.trimOut - clip.trimIn).coerceAtLeast(0.25f)
    var showCaptionEditor by remember { mutableStateOf(false) }

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
            Row {
                GhostPillButton("🗑 Remove", compact = true) { viewModel.deleteClip(clip.id) }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Transition editor (visual tracks): blends this clip in over whatever plays beneath it.
        if (track.type == TrackType.VIDEO) {
            Column(Modifier.width(300.dp)) {
                Text(
                    "Transition in (over underlying media)",
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

        // Volume (audio-carrying tracks).
        if (track.type == TrackType.MUSIC || track.type == TrackType.VOICE) {
            Column(Modifier.width(170.dp)) {
                LabeledSlider(
                    label = "Volume",
                    value = effects.volume.toFloat(),
                    valueRange = 0f..2f,
                    valueText = "${(effects.volume * 100).roundToInt()}%",
                    onValueChange = { viewModel.updateClipEffects(clip, effects.copy(volume = it.toDouble())) }
                )
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
            valueText = "${config.fontSizeSp}sp",
            onValueChange = { config = config.copy(fontSizeSp = it.roundToInt()) }
        )

        SectionLabel("Color")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("#FFFFFF", "#FFE45E", "#7FE0A7", "#7FC6FF", "#FF9EC1", "#FF6B5E").forEach { hex ->
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
}
