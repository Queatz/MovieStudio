package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

@JsFun("""
(onTimeUpdate, onEnded, onReady) => {
    let video = document.getElementById('compose-video-preview');
    if (!video) {
        video = document.createElement('video');
        video.id = 'compose-video-preview';
        // Load media through a CORS request (set BEFORE any src) so the canvas the "Save frame"
        // feature draws this <video> into stays origin-clean. Without it the cross-origin OSS
        // source taints the canvas and canvas.toBlob() throws a SecurityError, so frame capture
        // always fails. OSS CORS allows this GET.
        video.crossOrigin = 'anonymous';
        // This element carries the currently-playing media: give it top network priority so the
        // hidden preload pool (see PlatformBridge) always yields its bandwidth to live playback.
        try { video.fetchPriority = 'high'; } catch (e) {}
        video.style.position = 'absolute';
        video.style.left = '0';
        video.style.top = '0';
        video.style.width = '100%';
        video.style.height = '100%';
        video.style.backgroundColor = 'black';
        video.style.display = 'block';
        // Fallback until framingSprite layout has videoWidth and wrap size.
        video.style.objectFit = 'cover';
        video.style.objectPosition = 'center';
        video.style.transform = 'none';
        video.controls = false;
        // Purely a decorative overlay (all interaction happens via the Compose transport
        // controls) — let pointer events pass through so clicking the stage doesn't steal
        // native DOM focus away from the Compose canvas (which would break the space-bar
        // play/pause shortcut and other keyboard input app-wide).
        video.style.pointerEvents = 'none';
        video.setAttribute('playsinline', 'true');
        const wrap = document.createElement('div');
        wrap.id = 'compose-video-preview-wrap';
        wrap.style.position = 'absolute';
        wrap.style.zIndex = '1000';
        wrap.style.overflow = 'hidden';
        wrap.style.backgroundColor = 'black';
        wrap.style.pointerEvents = 'none';
        wrap.style.display = 'none';
        wrap.appendChild(video);
        document.body.appendChild(wrap);
    }
    let existingWrap = document.getElementById('compose-video-preview-wrap');
    if (!existingWrap) {
        existingWrap = document.createElement('div');
        existingWrap.id = 'compose-video-preview-wrap';
        existingWrap.style.position = 'absolute';
        existingWrap.style.zIndex = '1000';
        existingWrap.style.overflow = 'hidden';
        existingWrap.style.backgroundColor = 'black';
        existingWrap.style.pointerEvents = 'none';
        existingWrap.style.display = 'none';
        document.body.appendChild(existingWrap);
    }
    if (video.parentNode !== existingWrap) {
        video.style.position = 'absolute';
        video.style.left = '0';
        video.style.top = '0';
        video.style.width = '100%';
        video.style.height = '100%';
        video.style.pointerEvents = 'none';
        existingWrap.appendChild(video);
    }
    video.ontimeupdate = () => {
        onTimeUpdate(video.currentTime);
    };
    video.onended = () => {
        onEnded();
    };
    // Fires once the media has loaded enough to render its first frame, so callers can defer
    // starting playback until the movie is actually ready.
    video.onloadeddata = () => {
        onReady();
    };
    if (window.__msLayoutPreviewVideo) { window.__msLayoutPreviewVideo(); }
}
""")
private external fun jsSetupVideoCallback(onTimeUpdate: (Double) -> Unit, onEnded: () -> Unit, onReady: () -> Unit)

@JsFun("""
(url, isPlaying, playhead) => {
    const video = document.getElementById('compose-video-preview');
    if (video) {
        if (video.src !== url && url) {
            video.src = url;
            video.load();
        }
        if (isPlaying) {
            if (video.paused) video.play().catch(e => {});
        } else {
            if (!video.paused) video.pause();
        }
        const diff = Math.abs(video.currentTime - playhead);
        if (diff > 0.5) {
            video.currentTime = playhead;
        }
    }
}
""")
private external fun jsUpdateVideoState(url: String, isPlaying: Boolean, playhead: Double)

@JsFun("""
(x, y, w, h) => {
    const host = document.getElementById('compose-video-preview-wrap') ||
        document.getElementById('compose-video-preview');
    if (host) {
        // Remember the un-transformed stage bounds so the transition (opacity + slide offset +
        // circular reveal) can be re-applied on top of them independently of layout changes.
        host.dataset.baseX = x;
        host.dataset.baseY = y;
        host.dataset.baseW = w;
        host.dataset.baseH = h;
        const dx = parseFloat(host.dataset.trDx || '0');
        const dy = parseFloat(host.dataset.trDy || '0');
        const op = host.dataset.trOp || '1';
        const rev = parseFloat(host.dataset.trReveal || '1');
        host.style.left = (x + dx * w) + 'px';
        host.style.top = (y + dy * h) + 'px';
        host.style.width = w + 'px';
        host.style.height = h + 'px';
        host.style.opacity = op;
        // Circular reveal (CIRCLE transition): radius as a fraction of the center-to-corner
        // distance, matching the FFmpeg geq mask. rev >= 1 means no mask.
        const cp = rev >= 1 ? 'none' : ('circle(' + (rev * Math.hypot(w / 2, h / 2)) + 'px at 50% 50%)');
        host.style.clipPath = cp;
        host.style.webkitClipPath = cp;
        host.style.display = 'block';
        if (window.__msLayoutPreviewVideo) { window.__msLayoutPreviewVideo(); }
    }
}
""")
private external fun jsUpdateVideoBounds(x: Double, y: Double, w: Double, h: Double)

@JsFun("""
(opacity, dx, dy, reveal) => {
    const host = document.getElementById('compose-video-preview-wrap') ||
        document.getElementById('compose-video-preview');
    if (host) {
        // Transition-in state (cross-fade + slide + circular reveal), re-applied over the last
        // known stage bounds.
        host.dataset.trOp = opacity;
        host.dataset.trDx = dx;
        host.dataset.trDy = dy;
        host.dataset.trReveal = reveal;
        const x = parseFloat(host.dataset.baseX || '0');
        const y = parseFloat(host.dataset.baseY || '0');
        const w = parseFloat(host.dataset.baseW || '0');
        const h = parseFloat(host.dataset.baseH || '0');
        host.style.left = (x + dx * w) + 'px';
        host.style.top = (y + dy * h) + 'px';
        host.style.opacity = opacity;
        const cp = reveal >= 1 ? 'none' : ('circle(' + (reveal * Math.hypot(w / 2, h / 2)) + 'px at 50% 50%)');
        host.style.clipPath = cp;
        host.style.webkitClipPath = cp;
    }
}
""")
private external fun jsUpdateVideoTransition(opacity: Double, dx: Double, dy: Double, reveal: Double)

@JsFun("""
(volume) => {
    const video = document.getElementById('compose-video-preview');
    if (video) {
        // The HTML media element clamps to 0..1, so gains above 100% land fully only in the render.
        video.volume = Math.max(0, Math.min(1, volume));
    }
}
""")
private external fun jsUpdateVideoVolume(volume: Double)

@JsFun("""
() => {
    const wrap = document.getElementById('compose-video-preview-wrap');
    const video = document.getElementById('compose-video-preview');
    if (wrap) { wrap.style.display = 'none'; }
    if (video) {
        video.style.display = wrap ? 'block' : 'none';
        video.pause();
    }
}
""")
private external fun jsHideVideo()

/** The last measured (CSS-pixel) stage bounds of the shared `<video>` overlay. */
private data class VideoBounds(val x: Double, val y: Double, val w: Double, val h: Double)

@Composable
actual fun VideoPlayer(
    url: String,
    isPlaying: Boolean,
    playhead: Float,
    onTimeUpdate: (Float) -> Unit,
    modifier: Modifier,
    alpha: Float,
    offsetXFraction: Float,
    offsetYFraction: Float,
    revealRadiusFraction: Float,
    volume: Float,
    onEnded: () -> Unit,
    onReady: () -> Unit
) {
    LaunchedEffect(Unit) {
        jsSetupVideoCallback(
            { sec -> onTimeUpdate(sec.toFloat()) },
            { onEnded() },
            { onReady() }
        )
    }

    // Update video state (src / play / pause / seek) when they change. This must NOT hide the
    // element on change: `playhead` advances every tick during playback, so hiding it here (as a
    // DisposableEffect onDispose keyed on playhead did) blanked the <video> after the first frame.
    LaunchedEffect(url, isPlaying, playhead) {
        jsUpdateVideoState(url, isPlaying, playhead.toDouble())
    }

    // Drive the transition-in (cross-fade + slide + circular reveal) onto the shared <video>
    // overlay. Kept separate from bounds so it re-applies every tick as the progress advances.
    LaunchedEffect(alpha, offsetXFraction, offsetYFraction, revealRadiusFraction) {
        jsUpdateVideoTransition(
            alpha.toDouble(),
            offsetXFraction.toDouble(),
            offsetYFraction.toDouble(),
            revealRadiusFraction.toDouble()
        )
    }

    // Apply the clip's volume (its envelope evaluated at the playhead) to the shared <video>, so a
    // keyframed volume envelope is honored in the preview like it is for pure-audio clips.
    LaunchedEffect(volume) {
        jsUpdateVideoVolume(volume.toDouble())
    }

    // Hide the shared <video> only when this player actually leaves the composition (no video clip
    // under the playhead anymore) — not on every state change.
    DisposableEffect(Unit) {
        onDispose {
            jsHideVideo()
        }
    }

    // Apply the measured stage bounds (which also flips the element to `display: block`, i.e. makes
    // it visible) to the shared <video>. Driven from Compose state so it runs as its own effect
    // AFTER the setup effect above has created the element. Applying the bounds directly inside
    // `onGloballyPositioned` was the bug behind "the video only appears after a resize": that layout
    // callback fires on the first frame BEFORE the setup effect's coroutine has run, so the very
    // first bounds update hit a not-yet-existing element and was silently dropped — leaving the
    // element `display: none` (audio still played via the state effect) until a window/layout resize
    // re-fired `onGloballyPositioned`. Routing it through state + an effect guarantees the element
    // exists when the bounds are applied, so the frame shows on the first layout.
    var bounds by remember { mutableStateOf<VideoBounds?>(null) }
    LaunchedEffect(bounds) {
        bounds?.let { jsUpdateVideoBounds(it.x, it.y, it.w, it.h) }
    }

    // Compose Web lays out in physical pixels (CSS px × devicePixelRatio), but the <video> overlay
    // is positioned/sized in CSS pixels. Divide by the density so the element lines up with the
    // aspect-constrained stage instead of overflowing it on high-DPI (retina) displays.
    val density = LocalDensity.current.density
    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val windowOffset = coordinates.localToWindow(Offset.Zero)
                val width = coordinates.size.width
                val height = coordinates.size.height
                bounds = VideoBounds(
                    (windowOffset.x / density).toDouble(),
                    (windowOffset.y / density).toDouble(),
                    (width / density).toDouble(),
                    (height / density).toDouble()
                )
            }
    )
}
