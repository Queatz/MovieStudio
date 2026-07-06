package app.moviestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AudioPlayItem
import app.moviestudio.WordTiming
import app.moviestudio.loadAudioWaveform
import app.moviestudio.totalCostUsd
import app.moviestudio.totalTokens
import app.moviestudio.triggerDownload
import app.moviestudio.updateAudioPlayback
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full asset details: description editing, generate/regenerate via the full per-type generation
 * dialog, a restorable version history, voice transcript tooling, sound clipping and library
 * actions.
 */
@Composable
fun AssetDetailsDialog(
    viewModel: AppViewModel,
    asset: Asset,
    onDismiss: () -> Unit,
    onEditSequence: (Asset) -> Unit
) {
    var description by remember(asset.id) { mutableStateOf(asset.description ?: "") }
    var showGenerate by remember { mutableStateOf(false) }
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

        // A preview of the media itself, rendered according to the asset's type.
        AssetMediaPreview(asset)

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
            // Opens the full generation dialog for the asset's type (same as creating anew),
            // pre-filled from the asset's stored setup.
            PillButton(
                if (asset.isDescriptionOnly) "✨ Generate media..." else "🔄 Regenerate...",
                compact = true
            ) { showGenerate = true }
            if (asset.type == AssetType.VIDEO || asset.type == AssetType.IMAGE) {
                GhostPillButton("✏ Edit", compact = true) { showTweak = true }
            }
            GhostPillButton("➕ Add to timeline", compact = true) {
                viewModel.addAssetToTimeline(asset)
                onDismiss()
            }
            GhostPillButton(
                "⬇ Download",
                compact = true,
                enabled = !asset.isDescriptionOnly && asset.ossUrl.isNotBlank()
            ) {
                triggerDownload(asset.ossUrl, downloadFileNameFor(asset))
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

        // ------------------------------------------------------------------- AI cost ledger
        // Every AI call connected to this asset, with its running total in tokens and USD.
        AssetCostLedger(asset)

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
                    "💾 Save & re-time",
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
            AccordionSection(
                title = "History",
                description = "${asset.history.size} previous version${if (asset.history.size == 1) "" else "s"}"
            ) {
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
                    // Type icon: versions can differ from the asset's current type (regenerations
                    // may convert image <-> video); older versions without a recorded type show
                    // the asset's current type.
                    Text(assetGlyph(version.type ?: asset.type), fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
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
    }

    if (showGenerate) {
        // The full per-type generation dialog, pre-filled from the asset's stored setup —
        // regenerating pushes the previous media onto the asset's restorable history.
        when (asset.type) {
            AssetType.MUSIC -> GenerateMusicDialog(viewModel, initialAsset = asset) { showGenerate = false }
            AssetType.AUDIO -> SoundEffectDialog(viewModel, initialAsset = asset) { showGenerate = false }
            AssetType.VOICE -> TtsDialog(viewModel, initialAsset = asset) { showGenerate = false }
            else -> GenerateMediaDialog(viewModel, initialAsset = asset) { showGenerate = false }
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
 * Builds a friendly file name for downloading [asset]'s media: the asset's own description (or
 * prompt) sanitized into a safe file name, falling back to its type, with the extension taken
 * from its media URL when present (or a sensible per-type default otherwise).
 */
private fun downloadFileNameFor(asset: Asset): String {
    val typeName = asset.type.name.lowercase()
    val urlExtension = asset.ossUrl.substringAfterLast('.', "")
        .substringBefore('?')
        .takeIf { it.isNotBlank() && it.length in 1..5 }
    val extension = urlExtension ?: when (asset.type) {
        AssetType.VIDEO -> "mp4"
        AssetType.IMAGE -> "png"
        AssetType.AUDIO, AssetType.MUSIC, AssetType.VOICE -> "mp3"
        AssetType.TEXT -> "txt"
    }
    val baseName = (asset.description ?: asset.aiPrompt)
        ?.take(40)
        ?.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ' ') c else ' ' }
        ?.joinToString("")
        ?.trim()
        ?.replace(Regex("\\s+"), "-")
        ?.takeIf { it.isNotBlank() }
        ?: typeName
    return "$baseName.$extension"
}

/**
 * A preview of the asset's media, shown at the top of [AssetDetailsDialog] and rendered according
 * to the asset's type: images as a still, videos as an inline (tap-to-play) player. Audio types
 * have their own dedicated player section further down the dialog, and description-only assets
 * have no media to preview yet, so those are skipped here.
 */
@Composable
private fun AssetMediaPreview(asset: Asset) {
    if (asset.isDescriptionOnly || asset.ossUrl.isBlank()) return
    when (asset.type) {
        AssetType.IMAGE -> {
            AsyncImage(
                model = asset.ossUrl,
                contentDescription = asset.description ?: asset.aiPrompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(10.dp))
        }
        AssetType.VIDEO -> {
            VideoPreview(asset.ossUrl, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        // Audio (sound / music / voice) has its dedicated "Listen" player; text has no media.
        else -> {}
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
    var clipStart by remember(asset.id) { mutableStateOf(0f) }
    var clipEnd by remember(asset.id) { mutableStateOf(total) }
    var name by remember { mutableStateOf("") }

    val canPlay = !asset.isDescriptionOnly && asset.ossUrl.isNotBlank()
    var playing by remember(asset.id) { mutableStateOf(false) }
    var position by remember(asset.id) { mutableStateOf(0f) }

    // Decoded waveform peaks (null → a synthetic placeholder is drawn instead).
    var waveform by remember(asset.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(asset.id, asset.ossUrl, canPlay) {
        waveform = if (canPlay) loadAudioWaveform(asset.ossUrl, WAVEFORM_BUCKETS) else null
    }

    // While playing, loop the playhead within the selected clip window so the user previews exactly
    // what will be saved.
    LaunchedEffect(playing, clipStart, clipEnd) {
        if (position < clipStart || position > clipEnd) position = clipStart
        while (playing) {
            delay(50)
            val next = position + 0.05f
            position = if (next >= clipEnd) clipStart else next
        }
    }
    LaunchedEffect(playing, position) {
        if (canPlay) {
            updateAudioPlayback(
                listOf(
                    AudioPlayItem(
                        key = "clipaudio-${asset.id}",
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

    StudioDialog(title = "Clip sound", onDismiss = onDismiss, width = 560.dp) {
        Text(
            "Drag the white handles on the waveform to choose the window to keep. It becomes a new " +
                "asset in the sound-effects library. Play to preview just the selected window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // Waveform with draggable clip-window handles: everything outside the window is dimmed.
        val minClipGap = 0.1f
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp)) // clip BEFORE pointerInput: rounded hover/press
                .background(Color(0xFF17151C))
                .pointerInput(total, clipStart, clipEnd) {
                    detectTapGestures { offset ->
                        position = (offset.x / size.width * total).coerceIn(clipStart, clipEnd)
                    }
                }
        ) {
            val density = LocalDensity.current
            val stripWidthPx = with(density) { maxWidth.toPx() }
            val pxPerSecond = stripWidthPx / total
            val handleWidthPx = with(density) { 14.dp.toPx() }

            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val maxBar = h * 0.42f
                val startX = (clipStart / total * w).coerceIn(0f, w)
                val endX = (clipEnd / total * w).coerceIn(0f, w)

                // Dim the regions outside the selected window.
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, 0f), size = Size(startX, h))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(endX, 0f), size = Size((w - endX).coerceAtLeast(0f), h))
                // Tint the selected window.
                drawRect(
                    Color(0xFF8F7BFF).copy(alpha = 0.15f),
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(1.5f), h)
                )

                // Waveform bars: brighter inside the window, filled up to the playhead.
                val barStride = 3f
                val barWidth = 2f
                val count = (w / barStride).toInt().coerceAtLeast(1)
                for (i in 0 until count) {
                    val x = i * barStride
                    val t = i.toFloat() / count * total
                    val bh = (waveformBarHeight(waveform, i, count) * maxBar).coerceAtLeast(1f)
                    val inWindow = t in clipStart..clipEnd
                    val color = when {
                        inWindow && t <= position -> Color(0xFFC9BCFF)
                        inWindow -> Color(0xFF8F7BFF)
                        else -> Color(0xFF3A3550)
                    }
                    drawRect(color, topLeft = Offset(x, mid - bh), size = Size(barWidth, bh * 2f))
                }

                // Window boundary lines.
                drawLine(Color.White, Offset(startX, 0f), Offset(startX, h), strokeWidth = 2f)
                drawLine(Color.White, Offset(endX, 0f), Offset(endX, h), strokeWidth = 2f)

                // Playhead.
                val px = (position / total * w).coerceIn(0f, w)
                drawLine(Color(0xFFFF5A6E), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
            }

            // Left handle → drags the clip start (never past the end).
            val startX = clipStart * pxPerSecond
            Box(
                modifier = Modifier
                    .offset { IntOffset((startX - handleWidthPx / 2f).roundToInt(), 0) }
                    .width(with(density) { handleWidthPx.toDp() })
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(4.dp)) // clip BEFORE pointerInput
                    .background(Color.White)
                    .pointerInput(pxPerSecond, clipEnd) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaS = dragAmount / pxPerSecond
                            clipStart = (clipStart + deltaS).coerceIn(0f, clipEnd - minClipGap)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮", color = Color(0xFF17151C), fontSize = 12.sp)
            }

            // Right handle → drags the clip end (never past the start).
            val endX = clipEnd * pxPerSecond
            Box(
                modifier = Modifier
                    .offset { IntOffset((endX - handleWidthPx / 2f).roundToInt(), 0) }
                    .width(with(density) { handleWidthPx.toDp() })
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(4.dp)) // clip BEFORE pointerInput
                    .background(Color.White)
                    .pointerInput(pxPerSecond, clipStart) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaS = dragAmount / pxPerSecond
                            clipEnd = (clipEnd + deltaS).coerceIn(clipStart + minClipGap, total)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮", color = Color(0xFF17151C), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Transport (play/pause the window) + start/end/length readouts.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                if (playing) "⏸" else "▶",
                contentDescription = "Play / pause",
                size = 34.dp,
                enabled = canPlay,
                background = MaterialTheme.colorScheme.primary,
                tint = MaterialTheme.colorScheme.onPrimary
            ) {
                if (!playing && (position < clipStart || position >= clipEnd - 0.05f)) position = clipStart
                playing = !playing
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${formatDuration(clipStart.toDouble())} → ${formatDuration(clipEnd.toDouble())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Length: ${formatDuration((clipEnd - clipStart).toDouble())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
            PillButton("✂️ Save clip", enabled = clipEnd - clipStart >= 0.2f) {
                viewModel.clipAudioAsset(
                    asset,
                    clipStart.toDouble(),
                    clipEnd.toDouble(),
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

    // The word currently under the playhead while playing (for the "follow along" highlight).
    val activeIndex = if (playing) wordIndexAt(position) else -1

    // Space toggles play/pause; the dialog grabs focus so the shortcut works immediately.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    StudioDialog(title = "Word timings", onDismiss = onDismiss, width = 640.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar && canPlay) {
                        if (!playing && position >= duration - 0.05f) position = 0f
                        playing = !playing
                        true
                    } else {
                        false
                    }
                }
        ) {
        Text(
            "Play or scrub the waveform (press Space to play/pause). The word playing is " +
                "highlighted. Pick a word and drag its start/end sliders, or grab the white handles " +
                "on the word blocks below to resize it. Neighboring words never overlap.",
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

        // Word blocks with labels + drag handles, wrapped into lines of up to 8 words each so
        // long transcripts stay readable. Each line uses its own local time scale (spanning only
        // that line's words) so a line never gets squeezed into unreadably thin blocks, while the
        // left/right handles still resize the selected word by the correct number of seconds.
        val minWordGap = 0.02f
        val wordsPerLine = 8
        val lineGroups = timings.indices.chunked(wordsPerLine)
        val lineHeight = 48.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeight * lineGroups.size.coerceAtLeast(1))
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
        ) {
            val density = LocalDensity.current
            val stripWidthPx = with(density) { maxWidth.toPx() }
            val lineHeightPx = with(density) { lineHeight.toPx() }
            val handleWidthPx = with(density) { 12.dp.toPx() }
            val minWordWidthPx = with(density) { 2.dp.toPx() }

            // A line's pixel scale is local to that line's own time span, so a line whose words
            // are packed close together in time still fills the row width readably.
            fun linePxPerSecond(indices: List<Int>): Float {
                val lineStart = timings[indices.first()].start.toFloat()
                val lineEnd = timings[indices.last()].end.toFloat()
                return stripWidthPx / (lineEnd - lineStart).coerceAtLeast(0.01f)
            }

            lineGroups.forEachIndexed { lineIndex, indices ->
                val lineStart = timings[indices.first()].start.toFloat()
                val linePxPerSecond = linePxPerSecond(indices)
                val lineOffsetY = (lineIndex * lineHeightPx).roundToInt()

                indices.forEach { index ->
                    val word = timings[index]
                    val startX = (word.start.toFloat() - lineStart) * linePxPerSecond
                    val endX = (word.end.toFloat() - lineStart) * linePxPerSecond
                    val wordWidthPx = (endX - startX).coerceAtLeast(minWordWidthPx)
                    val blockColor = when {
                        index == activeIndex -> Color(0xFFC9BCFF) // currently playing
                        index == selectedIndex -> Color(0xFF8F7BFF) // selected for editing
                        else -> Color(0xFF37324A)
                    }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(startX.roundToInt(), lineOffsetY) }
                            .width(with(density) { wordWidthPx.toDp() })
                            .height(lineHeight)
                            .padding(vertical = 8.dp, horizontal = 1.dp)
                            .clip(RoundedCornerShape(6.dp)) // clip BEFORE clickable
                            .background(blockColor)
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
                }
            }

            // Left/right drag handles for the selected word: grab and slide to resize it here.
            val sel = timings.getOrNull(selectedIndex)
            if (sel != null) {
                val prevEnd = timings.getOrNull(selectedIndex - 1)?.end?.toFloat() ?: 0f
                val nextStart = timings.getOrNull(selectedIndex + 1)?.start?.toFloat() ?: duration
                val selLineIndices = lineGroups[selectedIndex / wordsPerLine]
                val selLineStart = timings[selLineIndices.first()].start.toFloat()
                val selLinePxPerSecond = linePxPerSecond(selLineIndices)
                val selLineOffsetY = ((selectedIndex / wordsPerLine) * lineHeightPx).roundToInt()
                val selStartX = (sel.start.toFloat() - selLineStart) * selLinePxPerSecond
                val selEndX = (sel.end.toFloat() - selLineStart) * selLinePxPerSecond

                // Left handle → drags the word start (never past the previous word).
                Box(
                    modifier = Modifier
                        .offset { IntOffset((selStartX - handleWidthPx / 2f).roundToInt(), selLineOffsetY) }
                        .width(with(density) { handleWidthPx.toDp() })
                        .height(lineHeight)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp)) // clip BEFORE pointerInput
                        .background(Color.White)
                        .pointerInput(selectedIndex, selLinePxPerSecond, prevEnd) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaS = dragAmount / selLinePxPerSecond
                                timings = timings.mapIndexed { i, w ->
                                    if (i == selectedIndex) {
                                        val maxStart = (w.end.toFloat() - minWordGap).coerceAtLeast(prevEnd)
                                        val newStart = (w.start.toFloat() + deltaS).coerceIn(prevEnd, maxStart)
                                        w.copy(start = newStart.toDouble())
                                    } else {
                                        w
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⋮", color = Color(0xFF17151C), fontSize = 11.sp)
                }

                // Right handle → drags the word end (never past the next word).
                Box(
                    modifier = Modifier
                        .offset { IntOffset((selEndX - handleWidthPx / 2f).roundToInt(), selLineOffsetY) }
                        .width(with(density) { handleWidthPx.toDp() })
                        .height(lineHeight)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp)) // clip BEFORE pointerInput
                        .background(Color.White)
                        .pointerInput(selectedIndex, selLinePxPerSecond, nextStart) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaS = dragAmount / selLinePxPerSecond
                                timings = timings.mapIndexed { i, w ->
                                    if (i == selectedIndex) {
                                        val minEnd = (w.start.toFloat() + minWordGap).coerceAtMost(nextStart)
                                        val newEnd = (w.end.toFloat() + deltaS).coerceIn(minEnd, nextStart)
                                        w.copy(end = newEnd.toDouble())
                                    } else {
                                        w
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⋮", color = Color(0xFF17151C), fontSize = 11.sp)
                }
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

/**
 * The asset's AI-cost ledger: a running total (in tokens and USD) across every AI call connected
 * to the asset, followed by one row per call showing its details, tokens used, per-token price and
 * resulting USD cost. Renders nothing when no AI calls have been recorded yet.
 */
@Composable
private fun AssetCostLedger(asset: Asset) {
    if (asset.ledger.isEmpty()) return
    val callCount = asset.ledger.size
    val totalTokens = asset.ledger.totalTokens()
    val totalCost = asset.ledger.totalCostUsd()

    AccordionSection(
        title = "Cost",
        description = "$callCount call${if (callCount == 1) "" else "s"} · " +
            "${formatTokens(totalTokens)} tokens · ${formatUsd(totalCost)}"
    ) {
    // Running total across every AI call connected to this asset.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Total",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${formatTokens(totalTokens)} tokens",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            formatUsd(totalCost),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }

    // One row per AI call: its details, tokens used, per-token price and USD cost.
    asset.ledger.forEach { entry ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatTokens(entry.tokens)} tokens · ${formatUsdPerToken(entry.costPerToken)}/token",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                formatUsd(entry.costUsd),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    }
}

/** Formats a token count with thousands separators (e.g. 12345 -> "12,345"). Multiplatform-safe. */
private fun formatTokens(tokens: Long): String {
    val digits = abs(tokens).toString()
    val grouped = buildString {
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % 3 == 0) append(',')
            append(c)
        }
    }
    return if (tokens < 0) "-$grouped" else grouped
}

/** Formats a USD amount, keeping sub-cent precision so tiny token costs stay visible. */
private fun formatUsd(amount: Double): String = "$" + formatMoney(amount, minDecimals = 2, maxDecimals = 6)

/** Formats a per-token USD price, which is tiny, keeping its significant fractional digits. */
private fun formatUsdPerToken(amount: Double): String = "$" + formatMoney(amount, minDecimals = 2, maxDecimals = 9)

/**
 * Formats a non-negative money [amount] with between [minDecimals] and [maxDecimals] fraction
 * digits (trailing zeros beyond [minDecimals] trimmed). Built with integer math so it works on
 * every Kotlin Multiplatform target (no `java.util.Formatter`).
 */
private fun formatMoney(amount: Double, minDecimals: Int, maxDecimals: Int): String {
    var factor = 1.0
    repeat(maxDecimals) { factor *= 10.0 }
    val scaled = kotlin.math.round(abs(amount) * factor).toLong()
    val digits = scaled.toString().padStart(maxDecimals + 1, '0')
    val intPart = digits.substring(0, digits.length - maxDecimals)
    var fracPart = digits.substring(digits.length - maxDecimals)
    while (fracPart.length > minDecimals && fracPart.endsWith("0")) {
        fracPart = fracPart.dropLast(1)
    }
    val sign = if (amount < 0) "-" else ""
    return "$sign$intPart.$fracPart"
}
