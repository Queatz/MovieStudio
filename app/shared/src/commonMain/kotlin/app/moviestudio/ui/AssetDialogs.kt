package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AudioPlayItem
import app.moviestudio.WordTiming
import app.moviestudio.updateAudioPlayback
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Full asset details: description editing, one-click generate/regenerate from the description,
 * a restorable version history, voice transcript tooling, sound clipping and library actions.
 */
@Composable
fun AssetDetailsDialog(
    viewModel: AppViewModel,
    asset: Asset,
    onDismiss: () -> Unit,
    onEditSequence: (Asset) -> Unit
) {
    var description by remember(asset.id) { mutableStateOf(asset.description ?: "") }
    var showTweak by remember { mutableStateOf(false) }
    var showClipAudio by remember { mutableStateOf(false) }
    var showTimings by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var transcriptDraft by remember(asset.id, asset.transcript) { mutableStateOf(asset.transcript ?: "") }
    val isAudioAsset = asset.type == AssetType.AUDIO || asset.type == AssetType.MUSIC || asset.type == AssetType.VOICE

    val hasSequence = asset.type == AssetType.MUSIC &&
        asset.generationConfig?.contains("\"notes\"") == true

    StudioDialog(
        title = "${assetGlyph(asset.type)} ${asset.type.name.lowercase().replaceFirstChar { it.uppercase() }} asset",
        onDismiss = onDismiss,
        width = 600.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatDuration(asset.durationSeconds)} • ${if (asset.isDescriptionOnly) "description only" else "media ready"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (asset.tags.isNotEmpty()) {
                Text(
                    asset.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Description",
            minLines = 2,
            maxLines = 4
        )
        if (description != (asset.description ?: "")) {
            Spacer(Modifier.height(6.dp))
            PillButton("Save description", compact = true) {
                viewModel.updateAsset(asset.copy(description = description, aiPrompt = description))
            }
        }

        SectionLabel("Actions")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillButton(
                if (asset.isDescriptionOnly) "✨ Generate media" else "🔄 Regenerate",
                compact = true,
                enabled = !(asset.description ?: asset.aiPrompt).isNullOrBlank()
            ) {
                viewModel.generateAssetMedia(asset)
                onDismiss()
            }
            if (asset.type == AssetType.VIDEO || asset.type == AssetType.IMAGE) {
                GhostPillButton("✏ Edit", compact = true) { showTweak = true }
            }
            GhostPillButton("➕ Add to timeline", compact = true) {
                viewModel.addAssetToTimeline(asset)
                onDismiss()
            }
            if (asset.type == AssetType.VIDEO && !asset.isDescriptionOnly) {
                GhostPillButton("🎧 Extract audio", compact = true) {
                    viewModel.extractAudioFromAsset(asset)
                    onDismiss()
                }
            }
            if ((asset.type == AssetType.AUDIO || asset.type == AssetType.MUSIC) && !asset.isDescriptionOnly) {
                GhostPillButton("✂️ Clip sound", compact = true) { showClipAudio = true }
            }
            if (hasSequence) {
                GhostPillButton("🎹 Edit sequence", compact = true) { onEditSequence(asset) }
            }
            GhostPillButton("🗑 Delete", compact = true) { showDeleteConfirm = true }
        }

        // -------------------------------------------------------- audio playback with scrubber
        if (isAudioAsset && !asset.isDescriptionOnly && asset.ossUrl.isNotBlank()) {
            SectionLabel("Listen")
            AssetAudioPlayer(asset)
        }

        // ------------------------------------------------------------------ voice: transcript
        if (asset.type == AssetType.VOICE) {
            SectionLabel("Transcript")
            StudioTextField(
                value = transcriptDraft,
                onValueChange = { transcriptDraft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Transcript text...",
                minLines = 3,
                maxLines = 7
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    "💾 Save & re-time with AI",
                    compact = true,
                    enabled = transcriptDraft.isNotBlank() && transcriptDraft != (asset.transcript ?: "")
                ) {
                    viewModel.saveTranscript(asset, transcriptDraft.trim())
                }
                GhostPillButton("🎙 Auto-transcribe", compact = true, enabled = !asset.isDescriptionOnly) {
                    viewModel.regenerateTranscript(asset)
                }
                GhostPillButton("🕒 Word timings (${asset.wordTimings.size})", compact = true,
                    enabled = asset.wordTimings.isNotEmpty()) { showTimings = true }
            }
        }

        // ------------------------------------------------------------------- version history
        if (asset.history.isNotEmpty()) {
            SectionLabel("History (${asset.history.size} previous version${if (asset.history.size == 1) "" else "s"})")
            asset.history.forEachIndexed { index, version ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("v${asset.history.size - index}", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            version.prompt ?: version.ossUrl.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatDuration(version.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GhostPillButton("↩ Restore", compact = true) {
                        viewModel.restoreAssetVersion(asset, index)
                    }
                }
            }
        }
    }

    if (showTweak) {
        GenerateMediaDialog(viewModel, initialAsset = asset) { showTweak = false }
    }
    if (showClipAudio) {
        ClipAudioDialog(viewModel, asset) { showClipAudio = false }
    }
    if (showTimings) {
        WordTimingEditorDialog(viewModel, asset) { showTimings = false }
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete asset?",
            message = "\"${(asset.description ?: asset.aiPrompt ?: "This asset").take(60)}\" will be " +
                "permanently removed from the library.",
            confirmLabel = "Delete asset",
            onConfirm = {
                viewModel.deleteAsset(asset)
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

/**
 * In-dialog player for audio-carrying assets (sound, music, voice): play/pause, a scrubber and a
 * timecode readout. Playback goes through the platform audio pool (browser targets), honoring
 * the asset's source offset for clipped sounds.
 */
@Composable
private fun AssetAudioPlayer(asset: Asset) {
    var playing by remember(asset.id) { mutableStateOf(false) }
    var position by remember(asset.id) { mutableStateOf(0f) }
    val duration = asset.durationSeconds.toFloat().coerceAtLeast(0.5f)

    // The scrubber is the master clock: it advances while playing and the audio pool chases it.
    LaunchedEffect(playing) {
        while (playing) {
            delay(100)
            val next = position + 0.1f
            if (next >= duration) {
                position = duration
                playing = false
            } else {
                position = next
            }
        }
    }
    LaunchedEffect(playing, position) {
        updateAudioPlayback(
            listOf(
                AudioPlayItem(
                    key = "asset-preview-${asset.id}",
                    url = asset.ossUrl,
                    positionSeconds = asset.sourceOffsetSeconds + position.toDouble(),
                    volume = 1.0
                )
            ),
            playing
        )
    }
    // Closing the dialog stops the preview sound.
    DisposableEffect(asset.id) {
        onDispose { updateAudioPlayback(emptyList(), false) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(
            if (playing) "⏸" else "▶",
            contentDescription = "Play / pause",
            size = 34.dp,
            background = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary
        ) {
            if (!playing && position >= duration - 0.05f) position = 0f
            playing = !playing
        }
        Spacer(Modifier.width(10.dp))
        Slider(
            value = position.coerceIn(0f, duration),
            onValueChange = { position = it },
            valueRange = 0f..duration,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${formatDuration(position.toDouble())} / ${formatDuration(duration.toDouble())}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Clips a window out of a sound asset into a new sound-effect asset. Playback and rendering
 * honor the stored source offset, so no re-encoding is needed.
 */
@Composable
fun ClipAudioDialog(viewModel: AppViewModel, asset: Asset, onDismiss: () -> Unit) {
    val total = asset.durationSeconds.toFloat().coerceAtLeast(0.5f)
    var range by remember { mutableStateOf(0f..total) }
    var name by remember { mutableStateOf("") }

    StudioDialog(title = "Clip sound", onDismiss = onDismiss, width = 500.dp) {
        Text(
            "Choose the window to keep. It becomes a new asset in the sound-effects library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(range.start.toDouble()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = 0f..total,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Text(
                formatDuration(range.endInclusive.toDouble()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            "Length: ${formatDuration((range.endInclusive - range.start).toDouble())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Sound effect name",
            placeholder = "Door slam, tail only...",
            singleLine = true
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("✂️ Save clip", enabled = range.endInclusive - range.start >= 0.2f) {
                viewModel.clipAudioAsset(
                    asset,
                    range.start.toDouble(),
                    range.endInclusive.toDouble(),
                    name.trim()
                )
                onDismiss()
            }
        }
    }
}

/**
 * Visual word-timing editor: the words are laid out on a strip proportional to the media
 * duration; select a word and drag its start/end sliders to fine-tune the alignment.
 */
@Composable
fun WordTimingEditorDialog(viewModel: AppViewModel, asset: Asset, onDismiss: () -> Unit) {
    var timings by remember(asset.id) { mutableStateOf(asset.wordTimings) }
    var selectedIndex by remember { mutableStateOf(0) }
    val duration = asset.durationSeconds.toFloat().coerceAtLeast(0.5f)

    StudioDialog(title = "Word timings", onDismiss = onDismiss, width = 620.dp) {
        Text(
            "Select a word, then drag its start/end. Neighboring words never overlap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        // Visual strip: each word block positioned/sized by its timing.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                timings.forEachIndexed { index, word ->
                    val widthWeight = ((word.end - word.start).toFloat() / duration).coerceAtLeast(0.015f)
                    val gapBefore = if (index == 0) (word.start.toFloat() / duration) else 0f
                    if (gapBefore > 0.001f) Spacer(Modifier.weight(gapBefore))
                    Box(
                        modifier = Modifier
                            .weight(widthWeight)
                            .height(56.dp)
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(6.dp)) // clip BEFORE clickable
                            .background(if (index == selectedIndex) Color(0xFF8F7BFF) else Color(0xFF37324A))
                            .clickable { selectedIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            word.word,
                            color = Color.White,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val tail = 1f - (timings.lastOrNull()?.end?.toFloat() ?: 0f) / duration
                if (tail > 0.001f) Spacer(Modifier.weight(tail.coerceAtLeast(0.001f)))
            }
        }
        Spacer(Modifier.height(12.dp))

        val selected = timings.getOrNull(selectedIndex)
        if (selected != null) {
            val prevEnd = timings.getOrNull(selectedIndex - 1)?.end?.toFloat() ?: 0f
            val nextStart = timings.getOrNull(selectedIndex + 1)?.start?.toFloat() ?: duration
            Text(
                "“${selected.word}”",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            LabeledSlider(
                label = "Starts at",
                value = selected.start.toFloat(),
                valueRange = prevEnd..(selected.end.toFloat() - 0.02f).coerceAtLeast(prevEnd),
                valueText = "${((selected.start * 100).roundToInt() / 100.0)}s",
                onValueChange = { newStart ->
                    timings = timings.mapIndexed { i, w ->
                        if (i == selectedIndex) w.copy(start = newStart.toDouble()) else w
                    }
                }
            )
            LabeledSlider(
                label = "Ends at",
                value = selected.end.toFloat(),
                valueRange = (selected.start.toFloat() + 0.02f).coerceAtMost(nextStart)..nextStart,
                valueText = "${((selected.end * 100).roundToInt() / 100.0)}s",
                onValueChange = { newEnd ->
                    timings = timings.mapIndexed { i, w ->
                        if (i == selectedIndex) w.copy(end = newEnd.toDouble()) else w
                    }
                }
            )
        }

        DialogActions {
            GhostPillButton("Even spread", compact = true) {
                timings = app.moviestudio.buildWordTimings(
                    timings.joinToString(" ") { it.word },
                    asset.durationSeconds
                )
            }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save timings") {
                viewModel.saveWordTimings(asset, timings)
                onDismiss()
            }
        }
    }
}
