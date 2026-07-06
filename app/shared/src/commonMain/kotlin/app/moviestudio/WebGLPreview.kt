package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One visual layer of the WebGL preview stage, bottom-to-top. Mirrors what the default preview
 * renderer draws for the same clip: the media at [url], center-cropped ("cover") into the stage
 * with the clip's 0-100 crop offsets, and transformed by the transition-in state evaluated at the
 * playhead ([alpha] cross-fade, [translateXFraction]/[translateYFraction] slide as fractions of
 * the stage size, [revealRadiusFraction] centered circular reveal — 1 = fully revealed).
 */
data class WebGLPreviewLayer(
    /** Stable identity of the layer (the clip id); keys the platform's video texture pool. */
    val key: String,
    /** Either [WEBGL_LAYER_IMAGE] or [WEBGL_LAYER_VIDEO]. */
    val kind: String,
    val url: String,
    /** Media time in seconds for video layers (sourceOffset + trimIn + clip-local playhead). */
    val positionSeconds: Double,
    val alpha: Float,
    val translateXFraction: Float,
    val translateYFraction: Float,
    val revealRadiusFraction: Float,
    /** 0-100 center-crop offsets (50 = center), same as [EffectsConfig.offsetX]/[EffectsConfig.offsetY]. */
    val offsetXPercent: Double,
    val offsetYPercent: Double
)

/** [WebGLPreviewLayer.kind] of a still image (texture uploaded once, cached by URL). */
const val WEBGL_LAYER_IMAGE = "image"

/** [WebGLPreviewLayer.kind] of a video (texture re-uploaded every frame from a hidden element). */
const val WEBGL_LAYER_VIDEO = "video"

/**
 * True when this platform can render the WebGL preview (web targets with WebGL available).
 * The preview-method toggle is only offered when this returns true.
 */
expect fun isWebGLPreviewSupported(): Boolean

/**
 * The WebGL-powered preview stage: composites all [layers] (bottom-to-top) on the GPU and paints
 * the result INSIDE the Compose scene graph (the frame is read back from the GPU and drawn as an
 * `ImageBitmap`), so Compose dialogs, cards and captions layer over it automatically. Unlike the
 * default DOM `<video>` path this draws every layer — several videos included — strictly in z
 * order, with the same cover-crop and transition math. Video layers play/pause with [isPlaying] and
 * re-seek to their [WebGLPreviewLayer.positionSeconds] when they drift, slaved to the master
 * playback clock exactly like the default renderer.
 *
 * With no [layers] the surface paints nothing so Compose-drawn content behind it (the stage's black
 * background, the empty state) stays visible. Non-web platforms render a placeholder.
 */
@Composable
expect fun WebGLPreviewSurface(
    layers: List<WebGLPreviewLayer>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
)
