package app.moviestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AudioPlayItem
import app.moviestudio.WordTiming
import app.moviestudio.loadAudioWaveform
import app.moviestudio.updateAudioPlayback
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
            if (asset.type == AssetType.IMAGE && !asset.isDescriptionOnly) {
                GhostPillButton("🖼 Set as cover", compact = true) {
                    viewModel.setMovieCover(asset.ossUrl)
                    onDismiss()
                }
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

/** How many amplitude buckets we decode the audio into for the waveform display. */
private const val WAVEFORM_BUCKETS = 400

/**
 * Visual word-timing editor built for precise caption alignment. A waveform of the audio (decoded
 * on web targets, a synthetic placeholder elsewhere) is drawn on the same time scale as the word
 * blocks, so each word lines up visually with the sound it belongs to. Play the clip, scrub the
 * waveform to hear any moment, then pick a word and drag its start/end to line the captions up
 * perfectly. Neighboring words never overlap.
 */
@Composable
fun WordTimingEditorDialog(viewModel: AppViewModel, asset: Asset, onDismiss: () -> Unit) {
    var timings by remember(asset.id) { mutableStateOf(asset.wordTimings) }
    var selectedIndex by remember { mutableStateOf(0) }
    val duration = asset.durationSeconds.toFloat().coerceAtLeast(0.5f)

    val canPlay = !asset.isDescriptionOnly && asset.ossUrl.isNotBlank()
    var playing by remember(asset.id) { mutableStateOf(false) }
    var position by remember(asset.id) { mutableStateOf(0f) }

    // Decoded waveform peaks (null → a synthetic placeholder is drawn instead).
    var waveform by remember(asset.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(asset.id, asset.ossUrl, canPlay) {
        waveform = if (canPlay) loadAudioWaveform(asset.ossUrl, WAVEFORM_BUCKETS) else null
    }

    // The scrubber position is the master clock while playing; the audio pool chases it.
    LaunchedEffect(playing) {
        while (playing) {
            delay(50)
            val next = position + 0.05f
            if (next >= duration) {
                position = duration
                playing = false
            } else {
                position = next
            }
        }
    }
    LaunchedEffect(playing, position) {
        if (canPlay) {
            updateAudioPlayback(
                listOf(
                    AudioPlayItem(
                        key = "wordtiming-${asset.id}",
                        url = asset.ossUrl,
                        positionSeconds = asset.sourceOffsetSeconds + position.toDouble(),
                        volume = 1.0
                    )
                ),
                playing
            )
        }
    }
    // Closing the dialog stops the preview sound.
    DisposableEffect(asset.id) {
        onDispose { updateAudioPlayback(emptyList(), false) }
    }

    fun wordIndexAt(t: Float): Int =
        timings.indexOfFirst { t >= it.start.toFloat() && t <= it.end.toFloat() }

    StudioDialog(title = "Word timings", onDismiss = onDismiss, width = 640.dp) {
        Text(
            "Play or scrub the waveform to find a moment, then pick a word and drag its start/end " +
                "so the captions line up. Neighboring words never overlap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        // Waveform aligned with the word boundaries; tap/drag anywhere to scrub (and tapping a
        // word's span selects it).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp)) // clip BEFORE pointerInput: rounded hover/press
                .background(Color(0xFF17151C))
                .pointerInput(duration, timings) {
                    detectTapGestures { offset ->
                        val t = (offset.x / size.width * duration).coerceIn(0f, duration)
                        position = t
                        val hit = wordIndexAt(t)
                        if (hit >= 0) selectedIndex = hit
                    }
                }
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            position = (offset.x / size.width * duration).coerceIn(0f, duration)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            position = (change.position.x / size.width * duration).coerceIn(0f, duration)
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val maxBar = h * 0.42f

                // Highlight the selected word's span behind the bars.
                val sel = timings.getOrNull(selectedIndex)
                if (sel != null) {
                    val x0 = (sel.start.toFloat() / duration * w).coerceIn(0f, w)
                    val x1 = (sel.end.toFloat() / duration * w).coerceIn(0f, w)
                    drawRect(
                        Color(0xFF8F7BFF).copy(alpha = 0.18f),
                        topLeft = Offset(x0, 0f),
                        size = Size((x1 - x0).coerceAtLeast(1.5f), h)
                    )
                }

                // Waveform bars: brighter within the selected word, filled up to the playhead.
                val barStride = 3f
                val barWidth = 2f
                val count = (w / barStride).toInt().coerceAtLeast(1)
                for (i in 0 until count) {
                    val x = i * barStride
                    val t = i.toFloat() / count * duration
                    val bh = (waveformBarHeight(waveform, i, count) * maxBar).coerceAtLeast(1f)
                    val inSelected = sel != null && t >= sel.start.toFloat() && t <= sel.end.toFloat()
                    val color = when {
                        inSelected -> Color(0xFFC9BCFF)
                        t <= position -> Color(0xFF8F7BFF)
                        else -> Color(0xFF4A4560)
                    }
                    drawRect(color, topLeft = Offset(x, mid - bh), size = Size(barWidth, bh * 2f))
                }

                // Thin divider lines at every word start/end.
                timings.forEach { word ->
                    val xs = word.start.toFloat() / duration * w
                    val xe = word.end.toFloat() / duration * w
                    drawLine(Color(0xFF2C2838), Offset(xs, 0f), Offset(xs, h), strokeWidth = 1f)
                    drawLine(Color(0xFF2C2838), Offset(xe, 0f), Offset(xe, h), strokeWidth = 1f)
                }

                // Playhead.
                val px = (position / duration * w).coerceIn(0f, w)
                drawLine(Color(0xFFFF5A6E), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Transport: play/pause + timecode readout.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                if (playing) "⏸" else "▶",
                contentDescription = "Play / pause",
                size = 34.dp,
                enabled = canPlay,
                background = MaterialTheme.colorScheme.primary,
                tint = MaterialTheme.colorScheme.onPrimary
            ) {
                if (!playing && position >= duration - 0.05f) position = 0f
                playing = !playing
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${formatDuration(position.toDouble())} / ${formatDuration(duration.toDouble())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))

        // Word blocks with labels, laid out on the same time scale as the waveform above.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                var prevEnd = 0f
                timings.forEachIndexed { index, word ->
                    val gapBefore = ((word.start.toFloat() - prevEnd) / duration).coerceAtLeast(0f)
                    if (gapBefore > 0.001f) Spacer(Modifier.weight(gapBefore))
                    val widthWeight = ((word.end - word.start).toFloat() / duration).coerceAtLeast(0.015f)
                    Box(
                        modifier = Modifier
                            .weight(widthWeight)
                            .height(32.dp)
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(6.dp)) // clip BEFORE clickable
                            .background(if (index == selectedIndex) Color(0xFF8F7BFF) else Color(0xFF37324A))
                            .clickable {
                                selectedIndex = index
                                position = word.start.toFloat()
                            },
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
                    prevEnd = word.end.toFloat()
                }
                val tail = (1f - prevEnd / duration).coerceAtLeast(0.001f)
                if (tail > 0.001f) Spacer(Modifier.weight(tail))
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

/**
 * A waveform bar's height fraction (0f..1f): a real decoded peak when [peaks] is available,
 * otherwise a smooth synthetic placeholder so the strip still reads as a waveform on platforms
 * without audio decoding.
 */
private fun waveformBarHeight(peaks: FloatArray?, index: Int, count: Int): Float {
    if (peaks != null && peaks.isNotEmpty()) {
        val idx = (index.toLong() * peaks.size / count).toInt().coerceIn(0, peaks.size - 1)
        return peaks[idx].coerceIn(0f, 1f)
    }
    val t = index.toFloat() / count
    val envelope = 0.35f + 0.4f * abs(sin(t * 6.3f + 0.6f))
    val detail = abs(sin(t * 41f))
    return (0.12f + envelope * detail).coerceIn(0.04f, 1f)
}
