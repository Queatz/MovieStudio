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

@JsFun("""
(onTimeUpdate) => {
    let video = document.getElementById('compose-video-preview');
    if (!video) {
        video = document.createElement('video');
        video.id = 'compose-video-preview';
        video.style.position = 'absolute';
        video.style.zIndex = '1000';
        video.style.backgroundColor = 'black';
        video.style.display = 'none';
        // Center-crop fit: fill the (aspect-constrained) viewport and crop the overflow.
        video.style.objectFit = 'cover';
        video.style.objectPosition = 'center';
        video.controls = false;
        video.setAttribute('playsinline', 'true');
        document.body.appendChild(video);
    }
    video.ontimeupdate = () => {
        onTimeUpdate(video.currentTime);
    };
}
""")
private external fun jsSetupVideoCallback(onTimeUpdate: (Double) -> Unit)

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
    const video = document.getElementById('compose-video-preview');
    if (video) {
        // Remember the un-transformed stage bounds so the transition (opacity + slide offset) can
        // be re-applied on top of them independently of layout changes.
        video.dataset.baseX = x;
        video.dataset.baseY = y;
        video.dataset.baseW = w;
        video.dataset.baseH = h;
        const dx = parseFloat(video.dataset.trDx || '0');
        const dy = parseFloat(video.dataset.trDy || '0');
        const op = video.dataset.trOp || '1';
        video.style.left = (x + dx * w) + 'px';
        video.style.top = (y + dy * h) + 'px';
        video.style.width = w + 'px';
        video.style.height = h + 'px';
        video.style.opacity = op;
        video.style.display = 'block';
    }
}
""")
private external fun jsUpdateVideoBounds(x: Double, y: Double, w: Double, h: Double)

@JsFun("""
(opacity, dx, dy) => {
    const video = document.getElementById('compose-video-preview');
    if (video) {
        // Transition-in state (cross-fade + slide), re-applied over the last known stage bounds.
        video.dataset.trOp = opacity;
        video.dataset.trDx = dx;
        video.dataset.trDy = dy;
        const x = parseFloat(video.dataset.baseX || '0');
        const y = parseFloat(video.dataset.baseY || '0');
        const w = parseFloat(video.dataset.baseW || '0');
        const h = parseFloat(video.dataset.baseH || '0');
        video.style.left = (x + dx * w) + 'px';
        video.style.top = (y + dy * h) + 'px';
        video.style.opacity = opacity;
    }
}
""")
private external fun jsUpdateVideoTransition(opacity: Double, dx: Double, dy: Double)

@JsFun("""
() => {
    const video = document.getElementById('compose-video-preview');
    if (video) {
        video.style.display = 'none';
        video.pause();
    }
}
""")
private external fun jsHideVideo()

@Composable
actual fun VideoPlayer(
    url: String,
    isPlaying: Boolean,
    playhead: Float,
    onTimeUpdate: (Float) -> Unit,
    modifier: Modifier,
    alpha: Float,
    offsetXFraction: Float,
    offsetYFraction: Float
) {
    LaunchedEffect(Unit) {
        jsSetupVideoCallback { sec ->
            onTimeUpdate(sec.toFloat())
        }
    }

    // Update video state (src / play / pause / seek) when they change. This must NOT hide the
    // element on change: `playhead` advances every tick during playback, so hiding it here (as a
    // DisposableEffect onDispose keyed on playhead did) blanked the <video> after the first frame.
    LaunchedEffect(url, isPlaying, playhead) {
        jsUpdateVideoState(url, isPlaying, playhead.toDouble())
    }

    // Drive the transition-in (cross-fade + slide) onto the shared <video> overlay. Kept separate
    // from bounds so it re-applies every tick as the progress advances.
    LaunchedEffect(alpha, offsetXFraction, offsetYFraction) {
        jsUpdateVideoTransition(alpha.toDouble(), offsetXFraction.toDouble(), offsetYFraction.toDouble())
    }

    // Hide the shared <video> only when this player actually leaves the composition (no video clip
    // under the playhead anymore) — not on every state change.
    DisposableEffect(Unit) {
        onDispose {
            jsHideVideo()
        }
    }

    // Compose Web lays out in physical pixels (CSS px × devicePixelRatio), but the <video> overlay
    // is positioned/sized in CSS pixels. Divide by the density so the element lines up with the
    // aspect-constrained stage instead of overflowing it on high-DPI (retina) displays.
    val density = LocalDensity.current.density
    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val windowOffset = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                val width = coordinates.size.width
                val height = coordinates.size.height
                jsUpdateVideoBounds(
                    (windowOffset.x / density).toDouble(),
                    (windowOffset.y / density).toDouble(),
                    (width / density).toDouble(),
                    (height / density).toDouble()
                )
            }
    )
}
