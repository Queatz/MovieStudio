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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AudioPlayItem
import app.moviestudio.CaptionConfig
import app.moviestudio.CircleRevealShape
import app.moviestudio.Clip
import app.moviestudio.NO_TRANSITION
import app.moviestudio.TrackType
import app.moviestudio.TransitionVisual
import app.moviestudio.VideoPlayer
import app.moviestudio.WEBGL_LAYER_IMAGE
import app.moviestudio.WEBGL_LAYER_VIDEO
import app.moviestudio.WebGLPreviewLayer
import app.moviestudio.WebGLPreviewSurface
import app.moviestudio.isWebGLPreviewSupported
import app.moviestudio.aspectRatioToFloat
import app.moviestudio.calculatedDuration
import app.moviestudio.collectPreloadMedia
import app.moviestudio.parseEffectsConfig
import app.moviestudio.preloadTimelineMedia
import app.moviestudio.progressAt
import app.moviestudio.visualAt
import app.moviestudio.setPreviewObjectPosition
import app.moviestudio.shared.resources.Res
import app.moviestudio.shared.resources.asap
import app.moviestudio.shared.resources.yuyu
import app.moviestudio.updateAudioPlayback
import app.moviestudio.volumeAt
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.Font

/** A clip together with its resolved asset and track type, active under the playhead. */
private data class ActiveClip(val clip: Clip, val asset: Asset, val trackType: TrackType, val zIndex: Int)

/**
 * The movie preview area (always dark, regardless of theme). It composites EVERY visual clip under
 * the playhead onto one aspect-constrained stage, stacked by track zIndex: still images (plain
 * Compose [AsyncImage], center-cropped), the active video and description-only text cards. It also
 * overlays captions for voice clips and keeps the browser audio pool in sync with everything
 * audible under the playhead. See `docs/PreviewPanel.md` for the full compositing model.
 *
 * With [fullscreen] set, all chrome (rounded corners, padding, transport controls) is hidden —
 * only the stage remains, for the distraction-free fullscreen playback mode (ESC exits).
 */
@Composable
fun PreviewPanel(viewModel: AppViewModel, modifier: Modifier = Modifier, fullscreen: Boolean = false) {
    val timeline = viewModel.timeline
    val movie = viewModel.currentMovie
    val playhead = viewModel.playhead

    // Preload (and keep persistently buffered) every media file on the timeline — video, image and
    // audio — so playback and scrubbing across clips stay smooth: by the time the playhead reaches a
    // clip its media is already fetched/decoded rather than loaded on demand. Recomputed only when
    // the set of timeline media changes, so it never runs on a plain playhead tick.
    val preloadItems = remember(timeline, viewModel.libraryAssets) {
        collectPreloadMedia(timeline, viewModel.libraryAssets)
    }
    LaunchedEffect(preloadItems) {
        preloadTimelineMedia(preloadItems)
    }

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

    // Everything visual lives on VIDEO-type tracks: still images, video clips and description-only
    // text cards. Sort ascending by zIndex so a higher clip (an overlay track) draws on top.
    val visualClips = activeClips
        .filter { it.trackType == TrackType.VIDEO }
        .sortedBy { it.zIndex }
    // The web preview shares a single <video> element, so only one video clip can play at a time —
    // the top-most one wins; images and text on other tracks still render around it.
    val activeVideo = visualClips
        .lastOrNull { !it.asset.isDescriptionOnly && it.asset.type != AssetType.IMAGE }
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
                // The volume-over-time envelope (when present) is evaluated at the playhead.
                volume = effects.volumeAt((playhead - active.clip.timelineStart).toDouble())
            )
        }
    LaunchedEffect(audioItems, viewModel.isPlaying) {
        updateAudioPlayback(audioItems, viewModel.isPlaying)
    }

    // Keep the shared <video> element's center-crop position in sync with the active video clip's
    // 0-100 offsets (50 = center). Still images position themselves via Compose alignment (below).
    val activeVideoEffects = activeVideo?.let { parseEffectsConfig(it.clip.effectsConfig) }
    LaunchedEffect(activeVideo?.clip?.id, activeVideoEffects?.offsetX, activeVideoEffects?.offsetY) {
        setPreviewObjectPosition(activeVideoEffects?.offsetX ?: 50.0, activeVideoEffects?.offsetY ?: 50.0)
    }

    Column(modifier = modifier) {
        // Stage: aspect-constrained, always dark. Fullscreen drops the rounded chrome entirely.
        Box(
            modifier = if (fullscreen) {
                Modifier.weight(1f).fillMaxWidth().background(Color.Black)
            } else {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF121016))
            },
            contentAlignment = Alignment.Center
        ) {
            val ratio = aspectRatioToFloat(movie?.aspectRatio ?: "16:9")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (fullscreen) 0.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(ratio)
                        .clipToBounds() // keep sliding-in clips inside the movie frame (like FFmpeg)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // Render EVERY visual clip under the playhead, lowest zIndex first so overlay
                    // tracks (higher zIndex) stack on top: still images, the active video and
                    // description-only text cards all live together here.
                    if (viewModel.previewUseWebGL && isWebGLPreviewSupported()) {
                        // WebGL method: every media layer is composited on the GPU and drawn back
                        // INTO the Compose scene graph (bottom of the stage). Description cards are
                        // text and stay Compose-rendered on top of it, so — unlike the old floating
                        // overlay — dialogs, cards and captions all layer over the preview correctly.
                        WebGLStage(visualClips, playhead, viewModel.isPlaying)
                        visualClips.forEach { active ->
                            if (active.asset.isDescriptionOnly) DescriptionCard(active.asset)
                        }
                    } else {
                        visualClips.forEach { active ->
                            // Transition-in over whatever plays beneath this clip, evaluated at the
                            // playhead from the SAME shared spec the FFmpeg export uses, so the preview
                            // matches the render (fade / slide, honoring slide direction).
                            val transitionVisual = active.transitionVisual(playhead)
                            when {
                                // A clip with no media yet is a description card: large centered text.
                                active.asset.isDescriptionOnly -> DescriptionCard(active.asset)
                                // Still images: plain Compose AsyncImage, center-cropped + offset.
                                active.asset.type == AssetType.IMAGE -> ClipImage(active, transitionVisual)
                                // The single video that owns the shared <video> element.
                                active === activeVideo -> {
                                    val clipLocal = (playhead - active.clip.timelineStart).toDouble()
                                    val mediaTime = active.asset.sourceOffsetSeconds.toFloat() +
                                        active.clip.trimIn + (playhead - active.clip.timelineStart)
                                    VideoPlayer(
                                        url = active.asset.ossUrl,
                                        isPlaying = viewModel.isPlaying,
                                        playhead = mediaTime,
                                        onTimeUpdate = { /* the ticker is the master clock */ },
                                        modifier = Modifier.fillMaxSize(),
                                        alpha = transitionVisual.alpha,
                                        offsetXFraction = transitionVisual.translateXFraction,
                                        offsetYFraction = transitionVisual.translateYFraction,
                                        revealRadiusFraction = transitionVisual.revealRadiusFraction,
                                        // The volume-over-time envelope (when present) evaluated at the playhead.
                                        volume = parseEffectsConfig(active.clip.effectsConfig).volumeAt(clipLocal).toFloat()
                                    )
                                }
                                // Any further simultaneous videos can't share the one <video> element.
                            }
                        }
                    }

                    if (visualClips.isEmpty()) {
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

        if (!fullscreen) {
            Spacer(Modifier.height(10.dp))
            TransportControls(viewModel)
        }
    }
}

/**
 * The WebGL rendering path of the stage: turns every media-bearing visual clip under the playhead
 * into a [WebGLPreviewLayer] — transition-in state and 0-100 crop offsets evaluated exactly like
 * the default renderer — and hands the bottom-to-top stack to the platform's [WebGLPreviewSurface]
 * compositor. Unlike the default DOM path, several simultaneous videos render fine here, layered
 * strictly by zIndex.
 */
@Composable
private fun WebGLStage(visualClips: List<ActiveClip>, playhead: Float, isPlaying: Boolean) {
    val layers = visualClips
        .filter { !it.asset.isDescriptionOnly && it.asset.ossUrl.isNotBlank() }
        .map { active ->
            val transitionVisual = active.transitionVisual(playhead)
            val effects = parseEffectsConfig(active.clip.effectsConfig)
            WebGLPreviewLayer(
                key = active.clip.id,
                kind = if (active.asset.type == AssetType.IMAGE) WEBGL_LAYER_IMAGE else WEBGL_LAYER_VIDEO,
                url = active.asset.ossUrl,
                positionSeconds = active.asset.sourceOffsetSeconds + active.clip.trimIn +
                    (playhead - active.clip.timelineStart).toDouble(),
                alpha = transitionVisual.alpha,
                translateXFraction = transitionVisual.translateXFraction,
                translateYFraction = transitionVisual.translateYFraction,
                revealRadiusFraction = transitionVisual.revealRadiusFraction,
                offsetXPercent = effects.offsetX,
                offsetYPercent = effects.offsetY,
                // The volume-over-time envelope (when present) evaluated at the playhead.
                volume = effects.volumeAt((playhead - active.clip.timelineStart).toDouble())
            )
        }
    WebGLPreviewSurface(layers, isPlaying, Modifier.fillMaxSize())
}

/**
 * The transition-in transform for this clip at [playhead], derived from the clip's shared
 * [app.moviestudio.TransitionSpec] the same way the FFmpeg export is — so still images and video
 * fade / slide identically in the preview and the render. Returns [NO_TRANSITION] once the playhead
 * is past the transition window (or when there is no transition).
 */
private fun ActiveClip.transitionVisual(playhead: Float): TransitionVisual {
    val transition = parseEffectsConfig(clip.effectsConfig).transition ?: return NO_TRANSITION
    val clipLocal = (playhead - clip.timelineStart).toDouble()
    val clipDuration = (clip.trimOut - clip.trimIn).toDouble()
    return transition.visualAt(transition.progressAt(clipLocal, clipDuration))
}

/**
 * A still-image clip, drawn with a plain Compose [AsyncImage]. [ContentScale.Crop] fills the
 * (aspect-constrained) stage and crops the overflow; the clip's 0-100 crop offsets (50 = center)
 * map to a [BiasAlignment] so the visible window can be nudged, matching the FFmpeg render. The
 * [transitionVisual] fades / slides the image in over whatever plays beneath it.
 */
@Composable
private fun ClipImage(active: ActiveClip, transitionVisual: TransitionVisual) {
    val effects = parseEffectsConfig(active.clip.effectsConfig)
    AsyncImage(
        model = active.asset.ossUrl,
        contentDescription = active.asset.description ?: active.asset.aiPrompt,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = transitionVisual.alpha
                translationX = transitionVisual.translateXFraction * size.width
                translationY = transitionVisual.translateYFraction * size.height
            }
            // Circular reveal (CIRCLE transition): clip to the growing circle, matching FFmpeg.
            .then(
                if (transitionVisual.revealRadiusFraction < 1f)
                    Modifier.clip(CircleRevealShape(transitionVisual.revealRadiusFraction))
                else Modifier
            ),
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(
            horizontalBias = ((effects.offsetX - 50.0) / 50.0).toFloat().coerceIn(-1f, 1f),
            verticalBias = ((effects.offsetY - 50.0) / 50.0).toFloat().coerceIn(-1f, 1f)
        )
    )
}

/** A description-only clip (no media yet) rendered as large, centered white text. */
@Composable
private fun DescriptionCard(asset: Asset) {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            asset.description ?: asset.aiPrompt ?: "Untitled scene",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp
        )
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
                placeholder = "A joyful hot-air balloon festival at sunrise...",
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
        RoundIconButton(
            if (viewModel.isPlaying) "⏸" else "▶",
            contentDescription = "Play / pause (Space)",
            size = 42.dp,
            background = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary,
            onLongClick = {
                viewModel.seek(0f)
                viewModel.togglePlayback()
            }
        ) { viewModel.togglePlayback() }
        Spacer(Modifier.width(12.dp))
        Text(
            "${formatDuration(viewModel.playhead.toDouble())} / ${formatDuration(duration)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        GhostPillButton("📸 Save frame", compact = true) { showSaveFrame = true }
        Spacer(Modifier.width(8.dp))
        // One-click fullscreen playback: all editor UI hides, ESC exits.
        GhostPillButton("⛶ Fullscreen", compact = true) { viewModel.enterFullscreenPlayback() }
        if (isWebGLPreviewSupported()) {
            Spacer(Modifier.width(8.dp))
            RendererDropdown(viewModel)
        }
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

/**
 * Dropdown that toggles the stage between the Default (DOM `<video>` + Compose) and the WebGL
 * compositor preview methods, spelling out the trade-off of each option so the user can pick the
 * one that fits their machine / task: WebGL renders more accurately (matches the FFmpeg export
 * pixel-for-pixel) while Default is more performant (lighter on the GPU).
 */
@Composable
private fun RendererDropdown(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GhostPillButton(
            if (viewModel.previewUseWebGL) "🎛 WebGL" else "🎛 Default",
            compact = true
        ) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RendererOption(
                title = "🎛 WebGL",
                description = "More accurate",
                selected = viewModel.previewUseWebGL,
                onClick = {
                    expanded = false
                    viewModel.previewUseWebGL = true
                }
            )
            RendererOption(
                title = "🎛 Default",
                description = "More performant",
                selected = !viewModel.previewUseWebGL,
                onClick = {
                    expanded = false
                    viewModel.previewUseWebGL = false
                }
            )
        }
    }
}

/** A single entry in the [RendererDropdown], showing the renderer's name and its trade-off. */
@Composable
private fun RendererOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick
    )
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
