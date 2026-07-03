package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AudioPlayItem
import app.moviestudio.CaptionConfig
import app.moviestudio.Clip
import app.moviestudio.TrackType
import app.moviestudio.VideoPlayer
import app.moviestudio.aspectRatioToFloat
import app.moviestudio.calculatedDuration
import app.moviestudio.parseEffectsConfig
import app.moviestudio.shared.resources.Res
import app.moviestudio.shared.resources.asap
import app.moviestudio.shared.resources.yuyu
import app.moviestudio.updateAudioPlayback
import org.jetbrains.compose.resources.Font

/** A clip together with its resolved asset and track type, active under the playhead. */
private data class ActiveClip(val clip: Clip, val asset: Asset, val trackType: TrackType, val zIndex: Int)

/**
 * The movie preview area (always dark, regardless of theme): plays the active video clip,
 * renders description-only items as large centered white text, overlays captions for voice clips
 * and keeps the browser audio pool in sync with everything audible under the playhead.
 */
@Composable
fun PreviewPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val timeline = viewModel.timeline
    val movie = viewModel.currentMovie
    val playhead = viewModel.playhead

    // Resolve everything currently under the playhead.
    val activeClips = remember(timeline, playhead, viewModel.libraryAssets) {
        val result = mutableListOf<ActiveClip>()
        timeline?.tracks?.forEach { trackWithClips ->
            trackWithClips.clips.forEach { clip ->
                val length = clip.trimOut - clip.trimIn
                if (playhead >= clip.timelineStart && playhead < clip.timelineStart + length) {
                    val asset = viewModel.assetById(clip.assetId)
                    if (asset != null) {
                        result.add(ActiveClip(clip, asset, trackWithClips.track.type, trackWithClips.track.zIndex))
                    }
                }
            }
        }
        result
    }

    val videoActive = activeClips
        .filter { it.trackType == TrackType.VIDEO && it.asset.ossUrl.isNotBlank() && !it.asset.isDescriptionOnly }
        .maxByOrNull { it.zIndex }
    val textActive = activeClips
        .filter { it.trackType == TrackType.VIDEO && it.asset.isDescriptionOnly }
        .maxByOrNull { it.zIndex }
    val captionClips = activeClips.filter { it.trackType == TrackType.VOICE }

    // Keep the audio-element pool in sync (music, voice, sound effects under the playhead).
    val audioItems = activeClips
        .filter { it.trackType != TrackType.VIDEO && it.asset.ossUrl.isNotBlank() }
        .map { active ->
            val effects = parseEffectsConfig(active.clip.effectsConfig)
            AudioPlayItem(
                key = active.clip.id,
                url = active.asset.ossUrl,
                positionSeconds = active.asset.sourceOffsetSeconds + active.clip.trimIn +
                    (playhead - active.clip.timelineStart).toDouble(),
                volume = effects.volume
            )
        }
    LaunchedEffect(audioItems, viewModel.isPlaying) {
        updateAudioPlayback(audioItems, viewModel.isPlaying)
    }

    Column(modifier = modifier) {
        // Stage: aspect-constrained, always dark.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF121016)),
            contentAlignment = Alignment.Center
        ) {
            val ratio = aspectRatioToFloat(movie?.aspectRatio ?: "16:9")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(ratio)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoActive != null) {
                        val mediaTime = videoActive.asset.sourceOffsetSeconds.toFloat() +
                            videoActive.clip.trimIn + (playhead - videoActive.clip.timelineStart)
                        VideoPlayer(
                            url = videoActive.asset.ossUrl,
                            isPlaying = viewModel.isPlaying,
                            playhead = mediaTime,
                            onTimeUpdate = { /* the ticker is the master clock */ },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Description-only items render as large, centered white text.
                    if (textActive != null) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                textActive.asset.description ?: textActive.asset.aiPrompt ?: "Untitled scene",
                                color = Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 34.sp
                            )
                        }
                    }

                    if (videoActive == null && textActive == null) {
                        EmptyStageContent(viewModel)
                    }

                    // Captions for active voice clips.
                    captionClips.forEach { active ->
                        val captions = parseEffectsConfig(active.clip.effectsConfig).captions
                        if (captions != null && captions.enabled) {
                            CaptionOverlay(active, captions, playhead)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        TransportControls(viewModel)
    }
}

/**
 * Empty state shown when nothing sits under the playhead: a prompt input that generates a movie
 * skeleton right here (same behavior as the timeline's Generate bar).
 */
@Composable
private fun EmptyStageContent(viewModel: AppViewModel) {
    var prompt by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎬", fontSize = 34.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing here yet",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Describe what should happen and AI will sketch the timeline for you.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = "A heist gone wrong in a neon city...",
                singleLine = true
            )
            Spacer(Modifier.width(10.dp))
            PillButton("✨ Generate", enabled = prompt.isNotBlank()) {
                viewModel.generateSkeleton(prompt.trim())
                prompt = ""
            }
        }
    }
}

/** Word-timed captions overlay for a voice clip, styled by its [CaptionConfig]. */
@Composable
private fun CaptionOverlay(active: ActiveClip, captions: CaptionConfig, playhead: Float) {
    val assetTime = active.clip.trimIn + (playhead - active.clip.timelineStart)
    val chunk = active.asset.wordTimings
        .chunked(4)
        .firstOrNull { chunk -> assetTime >= chunk.first().start && assetTime <= chunk.last().end }
        ?: return
    val text = chunk.joinToString(" ") { it.word }

    val alignment = when (captions.position) {
        "top" -> Alignment.TopCenter
        "center" -> Alignment.Center
        else -> Alignment.BottomCenter
    }
    Box(Modifier.fillMaxSize().padding(vertical = 26.dp, horizontal = 20.dp), contentAlignment = alignment) {
        Text(
            text,
            color = parseHexColor(captions.color),
            fontSize = captions.fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = captionFontFamily(captions.fontFamily),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/** Maps a caption font-family display name to a usable [FontFamily]. */
@Composable
fun captionFontFamily(name: String): FontFamily = when (name) {
    "Serif" -> FontFamily.Serif
    "Sans serif" -> FontFamily.SansSerif
    "Monospace" -> FontFamily.Monospace
    "Cursive" -> FontFamily.Cursive
    "Asap (my font)" -> FontFamily(Font(Res.font.asap))
    "Yuyu (my font)" -> FontFamily(Font(Res.font.yuyu))
    else -> FontFamily.Default
}

/** Parses `#RRGGBB` (or `#AARRGGBB`) into a Compose color; defaults to white. */
fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return try {
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> Color.White
        }
    } catch (e: Exception) {
        Color.White
    }
}

/** Transport row: play/pause, timecode and the save-frame action. */
@Composable
private fun TransportControls(viewModel: AppViewModel) {
    var showSaveFrame by remember { mutableStateOf(false) }
    val duration = viewModel.timeline?.calculatedDuration() ?: 0.0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        RoundIconButton("⏮", contentDescription = "Back to start", size = 34.dp) { viewModel.seek(0f) }
        Spacer(Modifier.width(6.dp))
        RoundIconButton(
            if (viewModel.isPlaying) "⏸" else "▶",
            contentDescription = "Play / pause (Space)",
            size = 42.dp,
            background = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary
        ) { viewModel.togglePlayback() }
        Spacer(Modifier.width(12.dp))
        Text(
            "${formatDuration(viewModel.playhead.toDouble())} / ${formatDuration(duration)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        GhostPillButton("📸 Save frame", compact = true) { showSaveFrame = true }
    }

    if (showSaveFrame) {
        SaveFrameDialog(
            onDismiss = { showSaveFrame = false },
            onSave = { text ->
                viewModel.saveCurrentFrame(text) { showSaveFrame = false }
            }
        )
    }
}

/** Save-frame dialog: captures the current preview frame into the image library. */
@Composable
private fun SaveFrameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    StudioDialog(title = "Save frame to image library", onDismiss = onDismiss, width = 440.dp) {
        Text(
            "Captures the frame currently visible in the preview and stores it as an image asset.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Text (optional)",
            placeholder = "Describe this frame...",
            singleLine = true
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(if (saving) "Saving..." else "Save frame", enabled = !saving) {
                saving = true
                onSave(text.trim())
            }
        }
    }
}
