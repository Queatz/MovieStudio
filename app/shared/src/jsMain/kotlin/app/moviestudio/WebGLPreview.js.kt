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
import androidx.compose.runtime.rememberUpdatedState
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

private fun jsHasWebGL(): Boolean = js("typeof WebGLRenderingContext !== 'undefined'")

actual fun isWebGLPreviewSupported(): Boolean = jsHasWebGL()

// Cap on the WebGL readback (and therefore the ImageBitmap) resolution. The preview stage is small,
// so a larger buffer only inflates the per-frame GPU->CPU readpixels + skia raster cost without a
// visible quality gain; the bitmap is scaled up to fill the stage. Aspect is preserved when capping.
// Kept modest on purpose: readback + JS->Wasm copy + skia raster cost all scale with the square of
// this number, and a preview does not need full-retina pixels.
private const val MAX_WEBGL_READBACK_DIM = 1280

private fun readbackSize(size: IntSize): IntSize {
    val maxDim = maxOf(size.width, size.height)
    if (maxDim <= MAX_WEBGL_READBACK_DIM) return size
    val scale = MAX_WEBGL_READBACK_DIM.toFloat() / maxDim
    return IntSize(
        maxOf(1, (size.width * scale).roundToInt()),
        maxOf(1, (size.height * scale).roundToInt())
    )
}

// ~30fps cap for the readback loop (matches the master playback clock; a 60fps readback would only
// produce duplicate frames). Nanoseconds.
private const val MIN_FRAME_INTERVAL_NANOS = 30_000_000L

// After a change, keep producing frames for this long even while paused, so a just-seeked or
// just-decoded video frame is still captured. Nanoseconds.
private const val SETTLE_NANOS = 500_000_000L

// Lazily creates the WebGL compositor (a DETACHED <canvas> — never added to the DOM, never
// positioned, so it can't cover Compose dialogs) and reconciles the (rarely changing) STRUCTURE of
// the bottom-to-top layer stack with it: which clips exist, their kind and url. Per-layer texture
// sources are a pool of hidden <video> elements (keyed by clip id, CORS-loaded so texImage2D stays
// legal on the cross-origin OSS media) and a session cache of <img> loaders. Videos play/pause with
// the master clock — mirroring the default preview's shared element; only the TOP-most video is
// audible, matching the default method's single-<video> audio. The fast-changing per-frame values
// (position/alpha/transition) are pushed separately by jsWebGLSyncFrame so this DOM-heavy pass does
// NOT run on every playhead tick. The frame is NOT presented here: jsWebGLRenderAndRead draws +
// reads it back so it can be painted inside the Compose scene graph.
private fun jsWebGLSyncStructure(structJson: String, playing: Boolean): Unit = js("""
    (function(structJson, playing) {
        var struct = JSON.parse(structJson);
        var S = window.__msWebGLPreview;
        if (!S) {
            // Detached canvas: it is only a render + readback target. It is never appended to the
            // document, so unlike the old floating overlay it cannot paint over Compose popups.
            var canvas = document.createElement('canvas');
            // No preserveDrawingBuffer: readback happens right after render() in the same frame, so
            // it is not needed, and dropping it frees the browser compositor's fast present paths.
            // Prefer WebGL2 (enables the async PBO readback in jsWebGLRenderAndRead); fall back to 1.
            var gl = canvas.getContext('webgl2', { alpha: false }) ||
                     canvas.getContext('webgl', { alpha: false });
            if (!gl) { return; }
            var isGL2 = !!(window.WebGL2RenderingContext && gl instanceof WebGL2RenderingContext);
            // Unit-quad vertex shader: aPos in [0,1], (0,0) = stage top-left. uTranslate is the
            // slide-in offset as a fraction of the stage size (+X right, +Y down).
            var vsSrc =
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
            var fsSrc =
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
            var compile = function(type, src) {
                var sh = gl.createShader(type);
                gl.shaderSource(sh, src);
                gl.compileShader(sh);
                return sh;
            };
            var prog = gl.createProgram();
            gl.attachShader(prog, compile(gl.VERTEX_SHADER, vsSrc));
            gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fsSrc));
            gl.linkProgram(prog);
            gl.useProgram(prog);
            var buf = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, buf);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
            var aPos = gl.getAttribLocation(prog, 'aPos');
            gl.enableVertexAttribArray(aPos);
            gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
            gl.enable(gl.BLEND);
            gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
            S = window.__msWebGLPreview = {
                canvas: canvas,
                gl: gl,
                isGL2: isGL2,
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
                active: false,
                // Set when a hidden <video> presents a new frame (requestVideoFrameCallback) or an
                // image finishes loading, so the paused render loop knows to draw exactly one frame.
                dirty: true,
                // Double-buffered pixel-pack buffers for the WebGL2 async readback.
                pbo: [null, null],
                pboReady: [false, false],
                pboIndex: 0,
                pboSize: 0
            };
            // Re-uploads a video texture only when the element actually presents a new frame, so a
            // paused/static video is not re-uploaded on every render. When requestVideoFrameCallback
            // is unavailable, S.render falls back to uploading every render.
            S.registerRvfc = function(entry) {
                if (typeof entry.el.requestVideoFrameCallback !== 'function') { return; }
                var cb = function() {
                    entry.hasNewFrame = true;
                    S.dirty = true;
                    entry.rvfcHandle = entry.el.requestVideoFrameCallback(cb);
                };
                entry.rvfcHandle = entry.el.requestVideoFrameCallback(cb);
            };
            S.newTexture = function() {
                var tex = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, tex);
                // Media has arbitrary (non-power-of-two) sizes: WebGL1 then requires
                // CLAMP_TO_EDGE wrapping and non-mipmap filtering.
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                return tex;
            };
            S.render = function() {
                if (canvas.width === 0 || canvas.height === 0) { return; }
                gl.viewport(0, 0, canvas.width, canvas.height);
                gl.clearColor(0, 0, 0, 1);
                gl.clear(gl.COLOR_BUFFER_BIT);
                var stageAspect = canvas.width / canvas.height;
                for (var i = 0; i < S.layers.length; i++) {
                    var layer = S.layers[i];
                    // Fully transparent (e.g. the very start of a fade-in): nothing to upload/draw.
                    if (!(layer.alpha > 0)) { continue; }
                    var srcW = 0;
                    var srcH = 0;
                    if (layer.kind === 'video') {
                        var entry = S.videos[layer.key];
                        // HAVE_CURRENT_DATA (2) means a frame is available for texImage2D.
                        if (!entry || entry.el.readyState < 2 || !entry.el.videoWidth) { continue; }
                        gl.bindTexture(gl.TEXTURE_2D, entry.tex);
                        // Only re-upload when a new frame arrived (requestVideoFrameCallback) or the
                        // texture was never filled; without rVFC, upload on every render.
                        var rvfc = (typeof entry.el.requestVideoFrameCallback === 'function');
                        if (!entry.uploaded || entry.hasNewFrame || !rvfc) {
                            try {
                                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, entry.el);
                            } catch (e) { continue; }
                            entry.uploaded = true;
                            entry.hasNewFrame = false;
                        }
                        srcW = entry.el.videoWidth;
                        srcH = entry.el.videoHeight;
                    } else {
                        var rec = S.images[layer.url];
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
                    var texAspect = srcW / srcH;
                    var uScale = 1;
                    var vScale = 1;
                    var uOff = 0;
                    var vOff = 0;
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
        // Rebuild S.layers from the structure, preserving the per-frame numbers of clips that are
        // still present; jsWebGLSyncFrame overwrites them for the current tick right after this.
        var prev = {};
        for (var i = 0; i < S.layers.length; i++) { prev[S.layers[i].key] = S.layers[i]; }
        var next = [];
        for (var i = 0; i < struct.length; i++) {
            var s = struct[i];
            var old = prev[s.key];
            next.push({
                key: s.key, kind: s.kind, url: s.url,
                position: old ? old.position : 0,
                alpha: old ? old.alpha : 1,
                dx: old ? old.dx : 0,
                dy: old ? old.dy : 0,
                reveal: old ? old.reveal : 1,
                offsetX: old ? old.offsetX : 50,
                offsetY: old ? old.offsetY : 50
            });
        }
        S.layers = next;
        S.dirty = true;
        // Reconcile the hidden <video> texture-source pool with the video layers.
        var wantedVideos = {};
        var topVideoKey = null;
        for (var i = 0; i < struct.length; i++) {
            if (struct[i].kind === 'video') { topVideoKey = struct[i].key; }
        }
        for (var i = 0; i < struct.length; i++) {
            var s = struct[i];
            if (s.kind !== 'video') { continue; }
            wantedVideos[s.key] = true;
            var entry = S.videos[s.key];
            if (!entry) {
                var v = document.createElement('video');
                // CORS request so texImage2D from the cross-origin OSS source is not blocked.
                v.crossOrigin = 'anonymous';
                v.preload = 'auto';
                v.setAttribute('playsinline', 'true');
                v.style.display = 'none'; // texture source only, never shown directly
                // Kept in the DOM (hidden) purely so the browser reliably decodes/plays it; it is
                // never visible, so it does not cover any Compose UI.
                document.body.appendChild(v);
                entry = S.videos[s.key] = { el: v, tex: S.newTexture(), uploaded: false, hasNewFrame: false, rvfcHandle: null };
                S.registerRvfc(entry);
            }
            var el = entry.el;
            if (el.getAttribute('data-src') !== s.url) {
                el.setAttribute('data-src', s.url);
                el.src = s.url;
                el.load();
                entry.uploaded = false;
                entry.hasNewFrame = false;
            }
            // Only the top-most video is audible, like the default method's single <video>.
            el.muted = s.key !== topVideoKey;
            if (playing) {
                if (el.paused) { el.play().catch(function(e) {}); }
            } else {
                if (!el.paused) { el.pause(); }
            }
        }
        for (var key in S.videos) {
            if (!wantedVideos[key]) {
                var e = S.videos[key];
                try { e.el.pause(); } catch (err) {}
                if (e.rvfcHandle != null && typeof e.el.cancelVideoFrameCallback === 'function') {
                    try { e.el.cancelVideoFrameCallback(e.rvfcHandle); } catch (err) {}
                }
                if (e.el.parentNode) { e.el.parentNode.removeChild(e.el); }
                try { S.gl.deleteTexture(e.tex); } catch (err) {}
                delete S.videos[key];
            }
        }
        // Start loading still images once per URL (kept for the session; scrubbing re-uses them).
        for (var i = 0; i < struct.length; i++) {
            if (struct[i].kind === 'image' && !S.images[struct[i].url]) {
                (function(url) {
                    var img = new Image();
                    var rec = { el: img, tex: null, ready: false };
                    S.images[url] = rec;
                    // CORS request so texImage2D from the cross-origin OSS source is not blocked.
                    img.crossOrigin = 'anonymous';
                    img.onload = function() { rec.ready = true; S.dirty = true; }; // uploaded lazily at draw
                    img.src = url;
                })(struct[i].url);
            }
        }
    })(structJson, playing)
""")

// Pushes the fast-changing per-frame values (7 numbers per layer, in stack order: position, alpha,
// dx, dy, reveal, offsetX, offsetY) as a flat CSV — parsed with a cheap split, no JSON.parse and no
// per-tick object allocation. The structure must already be in place (jsWebGLSyncStructure); if the
// count does not match yet, the tick is skipped and the next one applies. Video layers re-seek here
// when they drift more than 0.5s from their target position.
private fun jsWebGLSyncFrame(csv: String): Unit = js("""
    (function(csv) {
        var S = window.__msWebGLPreview;
        if (!S || !S.layers || S.layers.length === 0) { return; }
        var parts = csv.length ? csv.split(',') : [];
        var n = S.layers.length;
        if (parts.length !== n * 7) { return; }
        for (var i = 0; i < n; i++) {
            var b = i * 7;
            var layer = S.layers[i];
            layer.position = parseFloat(parts[b]);
            layer.alpha = parseFloat(parts[b + 1]);
            layer.dx = parseFloat(parts[b + 2]);
            layer.dy = parseFloat(parts[b + 3]);
            layer.reveal = parseFloat(parts[b + 4]);
            layer.offsetX = parseFloat(parts[b + 5]);
            layer.offsetY = parseFloat(parts[b + 6]);
            if (layer.kind === 'video') {
                var entry = S.videos[layer.key];
                if (entry && entry.el && Math.abs(entry.el.currentTime - layer.position) > 0.5) {
                    try { entry.el.currentTime = layer.position; } catch (e) {}
                }
            }
        }
    })(csv)
""")

// True (once) when a hidden <video> has presented a new frame or an image finished loading since
// the last poll, so the paused render loop can draw exactly one fresh frame instead of spinning.
private fun jsWebGLTakeDirty(): Boolean = js("""
    (function() {
        var S = window.__msWebGLPreview;
        if (!S) { return false; }
        var d = S.dirty === true;
        S.dirty = false;
        return d;
    })()
""")

// Sizes the compositor to (w,h) device pixels, renders the current layer stack and reads the frame
// back as RGBA bytes so Compose can paint it inside its own scene graph (Option 1 — the preview is
// a true Compose citizen, so dialogs/popups layer over it automatically). readPixels is bottom-up,
// so the caller draws the resulting bitmap vertically flipped.
//
// On WebGL2 the readback is asynchronous and double-buffered through pixel-pack buffers (PBOs): the
// freshly rendered frame is copied into one PBO without stalling the GL pipeline, and the PBO
// written on the PREVIOUS call (whose transfer has already finished) is pulled into readBuf — so
// there is no GPU->CPU stall. This costs one frame of latency, invisible for a preview. The very
// first call (and the first after a resize) has no previous frame and returns null. On WebGL1, or
// if anything about the PBO path fails, it falls back to a plain synchronous readPixels. Returns
// null when the compositor is not ready (no GL / not synced) or the async frame is not available yet.
private fun jsWebGLRenderAndRead(w: Int, h: Int): Int8Array<ArrayBuffer>? = js("""
    (function(w, h) {
        var S = window.__msWebGLPreview;
        if (!S || !S.gl) { return null; }
        var gl = S.gl;
        var canvas = S.canvas;
        var sizeChanged = false;
        if (canvas.width !== w) { canvas.width = w; sizeChanged = true; }
        if (canvas.height !== h) { canvas.height = h; sizeChanged = true; }
        var needed = w * h * 4;
        if (!S.readBuf || S.readBuf.length !== needed) { S.readBuf = new Uint8Array(needed); sizeChanged = true; }
        S.render();
        if (S.isGL2) {
            try {
                var PP = gl.PIXEL_PACK_BUFFER;
                if (sizeChanged || S.pboSize !== needed) {
                    if (S.pbo[0]) { try { gl.deleteBuffer(S.pbo[0]); } catch (e) {} }
                    if (S.pbo[1]) { try { gl.deleteBuffer(S.pbo[1]); } catch (e) {} }
                    S.pbo[0] = gl.createBuffer();
                    S.pbo[1] = gl.createBuffer();
                    gl.bindBuffer(PP, S.pbo[0]); gl.bufferData(PP, needed, gl.STREAM_READ);
                    gl.bindBuffer(PP, S.pbo[1]); gl.bufferData(PP, needed, gl.STREAM_READ);
                    gl.bindBuffer(PP, null);
                    S.pboReady[0] = false; S.pboReady[1] = false; S.pboIndex = 0; S.pboSize = needed;
                }
                var i = S.pboIndex;
                var j = 1 - i;
                // Kick off the async transfer of the just-rendered frame into pbo[i].
                gl.bindBuffer(PP, S.pbo[i]);
                gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, 0);
                S.pboReady[i] = true;
                // Pull the previous frame (its transfer is already done) out of pbo[j]; no stall.
                var result = null;
                if (S.pboReady[j]) {
                    gl.bindBuffer(PP, S.pbo[j]);
                    gl.getBufferSubData(PP, 0, S.readBuf);
                    result = new Int8Array(S.readBuf.buffer, 0, needed);
                }
                gl.bindBuffer(PP, null);
                S.pboIndex = j;
                return result;
            } catch (e) {
                // Any PBO hiccup: disable the async path and fall back to synchronous readback.
                S.isGL2 = false;
                try { gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null); } catch (e2) {}
            }
        }
        gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, S.readBuf);
        return new Int8Array(S.readBuf.buffer, 0, needed);
    })(w, h)
""")

// Stops the compositor when the WebGL stage leaves the composition (preview method switched back
// to Default, movie closed...). Tears down the video texture pool and clears the active flag; the
// GL context, program and image cache are kept for a cheap re-entry.
private fun jsWebGLHide(): Unit = js("""
    (function() {
        var S = window.__msWebGLPreview;
        if (!S) { return; }
        S.active = false;
        for (var key in S.videos) {
            var e = S.videos[key];
            try { e.el.pause(); } catch (err) {}
            if (e.rvfcHandle != null && typeof e.el.cancelVideoFrameCallback === 'function') {
                try { e.el.cancelVideoFrameCallback(e.rvfcHandle); } catch (err) {}
            }
            if (e.el.parentNode) { e.el.parentNode.removeChild(e.el); }
            try { S.gl.deleteTexture(e.tex); } catch (err) {}
            delete S.videos[key];
        }
        S.layers = [];
        S.dirty = true;
    })()
""")

/**
 * Serializes only the STRUCTURE of the layer stack (identity/kind/url) for the JS bridge. This
 * changes rarely, so the DOM-heavy video/image pool reconciliation keys on it instead of running on
 * every playhead tick.
 */
private fun structuralJson(layers: List<WebGLPreviewLayer>): String = buildString {
    append('[')
    layers.forEachIndexed { index, layer ->
        if (index > 0) append(',')
        append("{\"key\":\"").append(layer.key).append("\",")
        append("\"kind\":\"").append(layer.kind).append("\",")
        append("\"url\":\"").append(layer.url).append("\"}")
    }
    append(']')
}

/**
 * Serializes the fast-changing per-frame values as a flat CSV (7 numbers per layer, in stack order:
 * position, alpha, dx, dy, reveal, offsetX, offsetY) — cheaper to build and parse than JSON and
 * allocation-free on the JS side.
 */
private fun frameCsv(layers: List<WebGLPreviewLayer>): String = buildString {
    layers.forEachIndexed { index, layer ->
        if (index > 0) append(',')
        append(layer.positionSeconds).append(',')
        append(layer.alpha).append(',')
        append(layer.translateXFraction).append(',')
        append(layer.translateYFraction).append(',')
        append(layer.revealRadiusFraction).append(',')
        append(layer.offsetXPercent).append(',')
        append(layer.offsetYPercent)
    }
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

    // A frame is only produced when something actually changed: this counter is bumped on any change
    // that should refresh the paused preview (scrub, transition, resize, play/pause).
    var generation by remember { mutableStateOf(0) }
    // Read live inside the (Unit-keyed) frame loop, which would otherwise capture a stale value.
    val playing = rememberUpdatedState(isPlaying)

    // Structure (which clips/urls exist) changes rarely; keep the DOM-heavy pool reconciliation off
    // the per-tick path by keying it on the structural signature (and play state) only.
    val structJson = remember(layers) { structuralJson(layers) }
    LaunchedEffect(structJson, isPlaying) {
        hasLayers = layers.isNotEmpty()
        jsWebGLSyncStructure(structJson, isPlaying)
    }

    // Per-frame values change every playhead tick: push them as a compact CSV (no pool walk) and
    // bump the render generation, so the loop also refreshes the paused preview after a scrub,
    // transition, resize or play/pause.
    LaunchedEffect(layers, sizePx, isPlaying) {
        jsWebGLSyncFrame(frameCsv(layers))
        generation++
    }

    // Stop the compositor and release the video pool when the WebGL stage leaves the composition.
    DisposableEffect(Unit) {
        onDispose {
            bitmap = null
            jsWebGLHide()
        }
    }

    // Frame loop, driven by the Compose clock. Instead of rendering + reading back on every vsync,
    // it only produces a frame when there is a reason to: during playback, within a short settling
    // window after a change (so a just-seeked / just-decoded video frame is captured even while
    // paused), or when a hidden <video> reports a new frame. Readback is capped to ~30fps to match
    // the master clock. Runs only while this surface is composed (cancelled on dispose).
    LaunchedEffect(Unit) {
        var lastGeneration = -1
        var settleUntilNanos = 0L
        var lastRenderNanos = 0L
        var info: ImageInfo? = null
        while (true) {
            val nowNanos = withFrameNanos { it }
            val size = sizePx
            if (!hasLayers || size.width <= 0 || size.height <= 0) {
                if (bitmap != null) bitmap = null
                continue
            }
            if (generation != lastGeneration) {
                lastGeneration = generation
                settleUntilNanos = nowNanos + SETTLE_NANOS
            }
            // Consume any pending "new video frame / image loaded" signal every vsync.
            val newContentFrame = jsWebGLTakeDirty()
            val wantFrame = playing.value || nowNanos < settleUntilNanos || newContentFrame
            if (!wantFrame) continue
            // Cap to ~30fps: the master clock ticks at ~30fps, so 60fps readbacks are duplicates.
            if (nowNanos - lastRenderNanos < MIN_FRAME_INTERVAL_NANOS) continue
            lastRenderNanos = nowNanos
            val rb = readbackSize(size)
            val arr = jsWebGLRenderAndRead(rb.width, rb.height) ?: continue
            val bytes = arr.toByteArray()
            if (bytes.size != rb.width * rb.height * 4) continue
            if (info == null || info.width != rb.width || info.height != rb.height) {
                info = ImageInfo(rb.width, rb.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
            }
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
