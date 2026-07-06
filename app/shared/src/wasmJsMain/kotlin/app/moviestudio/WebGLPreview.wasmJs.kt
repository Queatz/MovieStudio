package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

@JsFun("() => (typeof WebGLRenderingContext !== 'undefined')")
private external fun jsHasWebGL(): Boolean

actual fun isWebGLPreviewSupported(): Boolean = jsHasWebGL()

// Reconciles the WebGL compositor with the current bottom-to-top layer stack. Lazily creates the
// shared <canvas id="compose-webgl-preview"> overlay, its GL context/shader program and the
// per-layer texture sources: a pool of hidden <video> elements (keyed by clip id, CORS-loaded so
// texImage2D stays legal on the cross-origin OSS media) and a session cache of <img> loaders.
// Videos are re-seeked when they drift more than 0.5s and play/pause with the master clock —
// mirroring the default preview's shared element; only the TOP-most video is audible, matching
// the default method's single-<video> audio. A requestAnimationFrame loop redraws every frame
// (video textures are re-uploaded per frame); with no layers the canvas hides and the loop stops
// so Compose-drawn content behind it (description cards, empty state) shows through.
@JsFun("""
(layersJson, playing) => {
    const layers = JSON.parse(layersJson);
    let S = window.__msWebGLPreview;
    if (!S) {
        const canvas = document.createElement('canvas');
        canvas.id = 'compose-webgl-preview';
        canvas.style.position = 'absolute';
        canvas.style.zIndex = '1000';
        canvas.style.backgroundColor = 'black';
        canvas.style.display = 'none';
        // Decorative overlay like the shared <video>: pointer events pass through so the
        // Compose canvas keeps native focus (space-bar shortcut etc).
        canvas.style.pointerEvents = 'none';
        document.body.appendChild(canvas);
        // preserveDrawingBuffer keeps the last rendered frame readable so the "Save frame"
        // feature can capture the canvas with toBlob(). alpha:false = opaque black stage.
        const gl = canvas.getContext('webgl', { alpha: false, preserveDrawingBuffer: true });
        if (!gl) { document.body.removeChild(canvas); return; }
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
            raf: 0
        };
        // The stage bounds may have been reported before the first layer sync created us.
        const pb = window.__msWebGLPendingBounds;
        if (pb) {
            window.__msWebGLPendingBounds = null;
            canvas.style.left = pb.x + 'px';
            canvas.style.top = pb.y + 'px';
            canvas.style.width = pb.w + 'px';
            canvas.style.height = pb.h + 'px';
            const dpr = window.devicePixelRatio || 1;
            canvas.width = Math.max(1, Math.round(pb.w * dpr));
            canvas.height = Math.max(1, Math.round(pb.h * dpr));
        }
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
        S.loop = () => {
            S.render();
            S.raf = requestAnimationFrame(S.loop);
        };
    }
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
    if (layers.length > 0) {
        S.canvas.style.display = 'block';
        if (!S.raf) { S.raf = requestAnimationFrame(S.loop); }
    } else {
        S.canvas.style.display = 'none';
        if (S.raf) { cancelAnimationFrame(S.raf); S.raf = 0; }
    }
}
""")
private external fun jsWebGLSyncLayers(layersJson: String, playing: Boolean)

// Places the canvas overlay over the stage (CSS pixels) and sizes its backing store at
// devicePixelRatio for a crisp render. Bounds reported before the first layer sync are parked in
// window.__msWebGLPendingBounds and applied when the compositor is created.
@JsFun("""
(x, y, w, h) => {
    const S = window.__msWebGLPreview;
    if (!S) {
        window.__msWebGLPendingBounds = { x: x, y: y, w: w, h: h };
        return;
    }
    const canvas = S.canvas;
    canvas.style.left = x + 'px';
    canvas.style.top = y + 'px';
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
    const dpr = window.devicePixelRatio || 1;
    const bw = Math.max(1, Math.round(w * dpr));
    const bh = Math.max(1, Math.round(h * dpr));
    if (canvas.width !== bw) { canvas.width = bw; }
    if (canvas.height !== bh) { canvas.height = bh; }
}
""")
private external fun jsWebGLSetBounds(x: Double, y: Double, w: Double, h: Double)

// Hides the WebGL canvas, stops the render loop and tears down the video texture pool. Called
// when the surface leaves the composition (preview method switched back to Default, movie
// closed...). The GL context, program and image cache are kept for a cheap re-entry.
@JsFun("""
() => {
    const S = window.__msWebGLPreview;
    if (!S) { return; }
    S.canvas.style.display = 'none';
    if (S.raf) { cancelAnimationFrame(S.raf); S.raf = 0; }
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
    // Re-sync the compositor whenever the stack changes (every playhead tick while playing).
    LaunchedEffect(layers, isPlaying) {
        jsWebGLSyncLayers(layersToJson(layers), isPlaying)
    }

    // Hide the canvas and stop the loop when the WebGL stage leaves the composition.
    DisposableEffect(Unit) {
        onDispose { jsWebGLHide() }
    }

    // Same DPI correction as VideoPlayer: Compose Web reports physical pixels, the CSS overlay is
    // positioned in logical pixels, so divide by the density.
    val density = LocalDensity.current.density
    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val windowOffset = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                jsWebGLSetBounds(
                    (windowOffset.x / density).toDouble(),
                    (windowOffset.y / density).toDouble(),
                    (coordinates.size.width / density).toDouble(),
                    (coordinates.size.height / density).toDouble()
                )
            }
    )
}
