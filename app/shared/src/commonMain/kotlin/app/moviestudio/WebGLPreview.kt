package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One visual layer of the WebGL preview stage, bottom-to-top. Mirrors what the default preview
 * renderer draws for the same clip: the media at [url], center-cropped ("cover") into the stage
 * with the clip's 0-100 crop offsets, and transformed by the transition-in state evaluated at the
 * playhead ([alpha] cross-fade, [translateXFraction]/[translateYFraction] slide as fractions of
 * the stage size, [revealRadiusFraction] centered circular reveal — 1 = fully revealed, and the
 * textured [pixelateFraction]/[noiseFraction]/[voronoiFraction] amounts the shader applies).
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
    val offsetYPercent: Double,
    /**
     * Cover-crop zoom (1.0 = today's cover). Combined with [offsetXPercent]/[offsetYPercent]
     * via [framingWindow] at draw time, once the texture's aspect is known.
     */
    val zoom: Double = 1.0,
    /**
     * Playback gain (0 = silent, 1 = 100%) for video layers, the clip's volume envelope evaluated
     * at the playhead. Only the top-most video is audible, matching the default renderer; the
     * platform clamps it to the media element's 0..1 range.
     */
    val volume: Double = 1.0,
    /** Mosaic amount (0 = crisp, 1 = maximally blocky), from [TransitionVisual.pixelateFraction]. */
    val pixelateFraction: Float = 0f,
    /** Grain/dissolve amount (0 = clean, 1 = fully speckled), from [TransitionVisual.noiseFraction]. */
    val noiseFraction: Float = 0f,
    /** Voronoi-cell amount (0 = crisp, 1 = coarse cells), from [TransitionVisual.voronoiFraction]. */
    val voronoiFraction: Float = 0f,
    /**
     * Centered, aspect-matched elliptical reveal with a soft (feathered) edge — the "vignette" iris,
     * from [TransitionVisual.vignetteRevealFraction] (1 = fully revealed / no mask, 0 = nothing).
     */
    val vignetteRevealFraction: Float = 1f
)

/** [WebGLPreviewLayer.kind] of a still image (texture uploaded once, cached by URL). */
const val WEBGL_LAYER_IMAGE = "image"

/** [WebGLPreviewLayer.kind] of a video (texture re-uploaded every frame from a hidden element). */
const val WEBGL_LAYER_VIDEO = "video"

/**
 * Number of CSV floats per [WebGLPreviewLayer] tick. The js and wasm parsers must stay in lockstep
 * with this stride — a mismatch drops the frame.
 */
internal const val WEBGL_LAYER_FRAME_STRIDE = 13

/**
 * Serializes the fast-changing per-frame values as a flat CSV (13 numbers per layer, in stack
 * order: position, alpha, dx, dy, reveal, offsetX, offsetY, zoom, volume, pixelate, noise, voronoi,
 * vignette) — cheaper to build and parse than JSON and allocation-free on the JS side.
 */
internal fun webglLayerFrameCsv(layers: List<WebGLPreviewLayer>): String = buildString {
    layers.forEachIndexed { index, layer ->
        if (index > 0) append(',')
        append(layer.positionSeconds).append(',')
        append(layer.alpha).append(',')
        append(layer.translateXFraction).append(',')
        append(layer.translateYFraction).append(',')
        append(layer.revealRadiusFraction).append(',')
        append(layer.offsetXPercent).append(',')
        append(layer.offsetYPercent).append(',')
        append(layer.zoom).append(',')
        append(layer.volume).append(',')
        append(layer.pixelateFraction).append(',')
        append(layer.noiseFraction).append(',')
        append(layer.voronoiFraction).append(',')
        append(layer.vignetteRevealFraction)
    }
}

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
