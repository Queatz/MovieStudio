package app.moviestudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import js.buffer.ArrayBuffer
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

@JsFun("() => (typeof WebGLRenderingContext !== 'undefined')")
private external fun jsHasWebGL(): Boolean

actual fun isWebGLPreviewSupported(): Boolean = jsHasWebGL()

// Cap on the WebGL readback (and therefore the ImageBitmap) resolution. The preview stage is small,
// so a larger buffer only inflates the per-frame GPU->CPU readpixels + skia raster cost without a
// visible quality gain; the bitmap is scaled up to fill the stage. Aspect is preserved when capping.
private const val MAX_WEBGL_READBACK_DIM = 1600

private fun readbackSize(size: IntSize): IntSize {
    val maxDim = maxOf(size.width, size.height)
    if (maxDim <= MAX_WEBGL_READBACK_DIM) return size
    val scale = MAX_WEBGL_READBACK_DIM.toFloat() / maxDim
    return IntSize(
        maxOf(1, (size.width * scale).roundToInt()),
        maxOf(1, (size.height * scale).roundToInt())
    )
}

// Lazily creates the WebGL compositor (a DETACHED <canvas> — never added to the DOM, never
// positioned, so it can't cover Compose dialogs) and reconciles it with the bottom-to-top layer
// stack. Per-layer texture sources are a pool of hidden <video> elements (keyed by clip id,
// CORS-loaded so texImage2D stays legal on the cross-origin OSS media) and a session cache of
// <img> loaders. Videos are re-seeked when they drift more than 0.5s and play/pause with the
// master clock — mirroring the default preview's shared element; only the TOP-most video is
// audible, matching the default method's single-<video> audio. The frame is NOT presented here:
// jsWebGLRenderAndRead draws + reads it back so it can be painted inside the Compose scene graph.
@JsFun("""
(layersJson, playing) => {
    const layers = JSON.parse(layersJson);
    let S = window.__msWebGLPreview;
    if (!S) {
        // Detached canvas: it is only a render + readback target. It is never appended to the
        // document, so unlike the old floating overlay it cannot paint over Compose popups.
        const canvas = document.createElement('canvas');
        // preserveDrawingBuffer keeps the last rendered frame readable so "Save frame" can
        // capture it with toBlob(). alpha:false = opaque black stage.
        const gl = canvas.getContext('webgl', { alpha: false, preserveDrawingBuffer: true });
        if (!gl) { return; }
        // Unit-quad vertex shader: aPos in [0,1], (0,0) = stage top-left. uTranslate is the
        // slide-in offset as a fraction of the stage size (+X right, +Y down).
        const vsSrc =
            'attribute vec2 aPos;' +
            'uniform vec2 uTranslate;' +
            'varying vec2 vPos;' +
            'void main() {' +
            '  vPos = aPos;' +
            '  float x = (aPos.x + uTranslate.x) * 2.0 - 1.0;' +
            '  float y = 1.0 - (aPos.y + uTranslate.y) * 2.0;' +
            '  gl_Position = vec4(x, y, 0.0, 1.0);' +
            '}';
        // Fragment shader: cover-crop UV window (uUvScale/uUvOffset), cross-fade (uAlpha) and
        // the centered circular reveal (uReveal) evaluated in PIXEL space (the stage is not
        // square) as a fraction of the center-to-corner distance — matching the DOM clip-path
        // circle and the FFmpeg geq mask.
        const fsSrc =
            'precision mediump float;' +
            'varying vec2 vPos;' +
            'uniform sampler2D uTex;' +
            'uniform vec2 uUvScale;' +
            'uniform vec2 uUvOffset;' +
            'uniform vec2 uSize;' +
            'uniform float uAlpha;' +
            'uniform float uReveal;' +
            'void main() {' +
            '  vec4 c = texture2D(uTex, vPos * uUvScale + uUvOffset);' +
            '  float a = uAlpha;' +
            '  if (uReveal < 1.0) {' +
            '    float d = length((vPos - vec2(0.5, 0.5)) * uSize) / length(uSize * 0.5);' +
            '    if (d > uReveal) { a = 0.0; }' +
            '  }' +
            '  gl_FragColor = vec4(c.rgb, a);' +
            '}';
        const compile = (type, src) => {
            const sh = gl.createShader(type);
            gl.shaderSource(sh, src);
            gl.compileShader(sh);
            return sh;
        };
        const prog = gl.createProgram();
        gl.attachShader(prog, compile(gl.VERTEX_SHADER, vsSrc));
        gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fsSrc));
        gl.linkProgram(prog);
        gl.useProgram(prog);
        const buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
        const aPos = gl.getAttribLocation(prog, 'aPos');
        gl.enableVertexAttribArray(aPos);
        gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
        S = window.__msWebGLPreview = {
            canvas: canvas,
            gl: gl,
            uTranslate: gl.getUniformLocation(prog, 'uTranslate'),
            uUvScale: gl.getUniformLocation(prog, 'uUvScale'),
            uUvOffset: gl.getUniformLocation(prog, 'uUvOffset'),
            uSize: gl.getUniformLocation(prog, 'uSize'),
            uAlpha: gl.getUniformLocation(prog, 'uAlpha'),
            uReveal: gl.getUniformLocation(prog, 'uReveal'),
            layers: [],
            videos: {},
            images: {},
            readBuf: null,
            active: false
        };
        S.newTexture = () => {
            const tex = gl.createTexture();
            gl.bindTexture(gl.TEXTURE_2D, tex);
            // Media has arbitrary (non-power-of-two) sizes: WebGL1 then requires
            // CLAMP_TO_EDGE wrapping and non-mipmap filtering.
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
            return tex;
        };
        S.render = () => {
            if (canvas.width === 0 || canvas.height === 0) { return; }
            gl.viewport(0, 0, canvas.width, canvas.height);
            gl.clearColor(0, 0, 0, 1);
            gl.clear(gl.COLOR_BUFFER_BIT);
            const stageAspect = canvas.width / canvas.height;
            for (let i = 0; i < S.layers.length; i++) {
                const layer = S.layers[i];
                let srcW = 0;
                let srcH = 0;
                if (layer.kind === 'video') {
                    const entry = S.videos[layer.key];
                    // HAVE_CURRENT_DATA (2) means a frame is available for texImage2D.
                    if (!entry || entry.el.readyState < 2 || !entry.el.videoWidth) { continue; }
                    gl.bindTexture(gl.TEXTURE_2D, entry.tex);
                    try {
                        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, entry.el);
                    } catch (e) { continue; }
                    srcW = entry.el.videoWidth;
                    srcH = entry.el.videoHeight;
                } else {
                    const rec = S.images[layer.url];
                    if (!rec || !rec.ready) { continue; }
                    if (!rec.tex) {
                        rec.tex = S.newTexture();
                        gl.bindTexture(gl.TEXTURE_2D, rec.tex);
                        try {
                            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, rec.el);
                        } catch (e) { rec.tex = null; continue; }
                    } else {
                        gl.bindTexture(gl.TEXTURE_2D, rec.tex);
                    }
                    srcW = rec.el.naturalWidth;
                    srcH = rec.el.naturalHeight;
                }
                if (!srcW || !srcH) { continue; }
                // Cover-fit: crop the overflowing axis; the 0-100 offset slides the visible
                // window across the overflow ((iw-ow) * offset/100 — FFmpeg's crop window).
                const texAspect = srcW / srcH;
                let uScale = 1;
                let vScale = 1;
                let uOff = 0;
                let vOff = 0;
                if (texAspect > stageAspect) {
                    uScale = stageAspect / texAspect;
                    uOff = (1 - uScale) * (layer.offsetX / 100);
                } else {
                    vScale = texAspect / stageAspect;
                    vOff = (1 - vScale) * (layer.offsetY / 100);
                }
                gl.uniform2f(S.uTranslate, layer.dx, layer.dy);
                gl.uniform2f(S.uUvScale, uScale, vScale);
                gl.uniform2f(S.uUvOffset, uOff, vOff);
                gl.uniform2f(S.uSize, canvas.width, canvas.height);
                gl.uniform1f(S.uAlpha, layer.alpha);
                gl.uniform1f(S.uReveal, layer.reveal);
                gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
            }
        };
    }
    S.active = true;
    S.layers = layers;
    // Reconcile the hidden <video> texture-source pool with the video layers.
    const wantedVideos = {};
    let topVideoKey = null;
    for (let i = 0; i < layers.length; i++) {
        if (layers[i].kind === 'video') { topVideoKey = layers[i].key; }
    }
    for (let i = 0; i < layers.length; i++) {
        const layer = layers[i];
        if (layer.kind !== 'video') { continue; }
        wantedVideos[layer.key] = true;
        let entry = S.videos[layer.key];
        if (!entry) {
            const v = document.createElement('video');
            // CORS request so texImage2D from the cross-origin OSS source is not blocked.
            v.crossOrigin = 'anonymous';
            v.preload = 'auto';
            v.setAttribute('playsinline', 'true');
            v.style.display = 'none'; // texture source only, never shown directly
            // Kept in the DOM (hidden) purely so the browser reliably decodes/plays it; it is
            // never visible, so it does not cover any Compose UI.
            document.body.appendChild(v);
            entry = S.videos[layer.key] = { el: v, tex: S.newTexture() };
        }
        const el = entry.el;
        if (el.getAttribute('data-src') !== layer.url) {
            el.setAttribute('data-src', layer.url);
            el.src = layer.url;
            el.load();
        }
        // Only the top-most video is audible, like the default method's single <video>.
        el.muted = layer.key !== topVideoKey;
        if (Math.abs(el.currentTime - layer.position) > 0.5) {
            try { el.currentTime = layer.position; } catch (e) {}
        }
        if (playing) {
            if (el.paused) { el.play().catch((e) => {}); }
        } else {
            if (!el.paused) { el.pause(); }
        }
    }
    for (const key in S.videos) {
        if (!wantedVideos[key]) {
            try { S.videos[key].el.pause(); } catch (e) {}
            if (S.videos[key].el.parentNode) { S.videos[key].el.parentNode.removeChild(S.videos[key].el); }
            try { S.gl.deleteTexture(S.videos[key].tex); } catch (e) {}
            delete S.videos[key];
        }
    }
    // Start loading still images once per URL (kept for the session; scrubbing re-uses them).
    for (let i = 0; i < layers.length; i++) {
        if (layers[i].kind === 'image' && !S.images[layers[i].url]) {
            const img = new Image();
            const rec = { el: img, tex: null, ready: false };
            S.images[layers[i].url] = rec;
            // CORS request so texImage2D from the cross-origin OSS source is not blocked.
            img.crossOrigin = 'anonymous';
            img.onload = () => { rec.ready = true; }; // texture uploaded lazily at draw
            img.src = layers[i].url;
        }
    }
}
""")
private external fun jsWebGLSyncLayers(layersJson: String, playing: Boolean)

// Sizes the compositor to (w,h) device pixels, renders the current layer stack and reads the frame
// back as RGBA bytes so Compose can paint it inside its own scene graph (Option 1 — the preview is
// a true Compose citizen, so dialogs/popups layer over it automatically). readPixels is bottom-up,
// so the caller draws the resulting bitmap vertically flipped. Returns null when the compositor is
// not ready yet (no GL / not synced).
@JsFun("""
(w, h) => {
    const S = window.__msWebGLPreview;
    if (!S || !S.gl) { return null; }
    const gl = S.gl;
    const canvas = S.canvas;
    if (canvas.width !== w) { canvas.width = w; }
    if (canvas.height !== h) { canvas.height = h; }
    const needed = w * h * 4;
    if (!S.readBuf || S.readBuf.length !== needed) { S.readBuf = new Uint8Array(needed); }
    S.render();
    gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, S.readBuf);
    return new Int8Array(S.readBuf.buffer, 0, needed);
}
""")
private external fun jsWebGLRenderAndRead(w: Int, h: Int): Int8Array<ArrayBuffer>?

// Stops the compositor when the WebGL stage leaves the composition (preview method switched back
// to Default, movie closed...). Tears down the video texture pool and clears the active flag; the
// GL context, program and image cache are kept for a cheap re-entry.
@JsFun("""
() => {
    const S = window.__msWebGLPreview;
    if (!S) { return; }
    S.active = false;
    for (const key in S.videos) {
        try { S.videos[key].el.pause(); } catch (e) {}
        if (S.videos[key].el.parentNode) { S.videos[key].el.parentNode.removeChild(S.videos[key].el); }
        try { S.gl.deleteTexture(S.videos[key].tex); } catch (e) {}
        delete S.videos[key];
    }
    S.layers = [];
}
""")
private external fun jsWebGLHide()

/** Serializes the layer stack for the JS bridge (same hand-rolled style as the audio pool). */
private fun layersToJson(layers: List<WebGLPreviewLayer>): String = buildString {
    append('[')
    layers.forEachIndexed { index, layer ->
        if (index > 0) append(',')
        append("{\"key\":\"").append(layer.key).append("\",")
        append("\"kind\":\"").append(layer.kind).append("\",")
        append("\"url\":\"").append(layer.url).append("\",")
        append("\"position\":").append(layer.positionSeconds).append(',')
        append("\"alpha\":").append(layer.alpha).append(',')
        append("\"dx\":").append(layer.translateXFraction).append(',')
        append("\"dy\":").append(layer.translateYFraction).append(',')
        append("\"reveal\":").append(layer.revealRadiusFraction).append(',')
        append("\"offsetX\":").append(layer.offsetXPercent).append(',')
        append("\"offsetY\":").append(layer.offsetYPercent).append('}')
    }
    append(']')
}

@Composable
actual fun WebGLPreviewSurface(
    layers: List<WebGLPreviewLayer>,
    isPlaying: Boolean,
    modifier: Modifier
) {
    // The last frame read back from the GPU, painted inside the Compose scene graph below.
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    // Stage size in physical pixels (Compose Web lays out in CSS px * devicePixelRatio).
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Whether there is anything to composite; when false the surface paints nothing so the stage's
    // black background and any Compose-drawn description cards behind it stay visible.
    var hasLayers by remember { mutableStateOf(false) }

    // Re-sync the compositor whenever the stack changes (every playhead tick while playing).
    LaunchedEffect(layers, isPlaying) {
        hasLayers = layers.isNotEmpty()
        jsWebGLSyncLayers(layersToJson(layers), isPlaying)
    }

    // Stop the compositor and release the video pool when the WebGL stage leaves the composition.
    DisposableEffect(Unit) {
        onDispose {
            bitmap = null
            jsWebGLHide()
        }
    }

    // Frame loop, driven by the Compose clock: render on the GPU, read the pixels back and turn
    // them into an ImageBitmap. Runs only while this surface is composed (the coroutine is
    // cancelled on dispose).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val size = sizePx
            if (!hasLayers || size.width <= 0 || size.height <= 0) {
                if (bitmap != null) bitmap = null
                continue
            }
            val rb = readbackSize(size)
            val arr = jsWebGLRenderAndRead(rb.width, rb.height) ?: continue
            val bytes = arr.toByteArray()
            if (bytes.size != rb.width * rb.height * 4) continue
            val info = ImageInfo(rb.width, rb.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
            bitmap = Image.makeRaster(info, bytes, rb.width * 4).toComposeImageBitmap()
        }
    }

    Box(modifier = modifier.onSizeChanged { sizePx = it }) {
        val bmp = bitmap
        if (bmp != null && hasLayers) {
            Canvas(Modifier.fillMaxSize()) {
                // readPixels returns rows bottom-to-top, so mirror vertically to present the frame
                // right-side up. The bitmap (possibly downscaled) is stretched to fill the stage;
                // its aspect matches the stage, so there is no distortion.
                scale(scaleX = 1f, scaleY = -1f) {
                    drawImage(
                        image = bmp,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    )
                }
            }
        }
    }
}
