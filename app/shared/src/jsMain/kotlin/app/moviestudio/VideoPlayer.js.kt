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
    revealRadiusFraction: Float
) {
    LaunchedEffect(Unit) {
        val setupCallback = js("""
            function(onTimeUpdate) {
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
                video.ontimeupdate = function() {
                    onTimeUpdate(video.currentTime);
                };
            }
        """)
        setupCallback(onTimeUpdate)
    }

    // Update video state (src / play / pause / seek) when they change. This must NOT hide the
    // element on change: `playhead` advances every tick during playback, so hiding it here (as a
    // DisposableEffect onDispose keyed on playhead did) blanked the <video> after the first frame.
    LaunchedEffect(url, isPlaying, playhead) {
        val updateState = js("""
            function(url, isPlaying, playhead) {
                const video = document.getElementById('compose-video-preview');
                if (video) {
                    if (video.src !== url && url) {
                        video.src = url;
                        video.load();
                    }
                    if (isPlaying) {
                        if (video.paused) video.play().catch(function(e) {});
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
        updateState(url, isPlaying, playhead.toDouble())
    }

    // Drive the transition-in (cross-fade + slide + circular reveal) onto the shared <video>
    // overlay. Kept separate from bounds so it re-applies every tick as the progress advances.
    LaunchedEffect(alpha, offsetXFraction, offsetYFraction, revealRadiusFraction) {
        val updateTransition = js("""
            function(opacity, dx, dy, reveal) {
                const video = document.getElementById('compose-video-preview');
                if (video) {
                    video.dataset.trOp = opacity;
                    video.dataset.trDx = dx;
                    video.dataset.trDy = dy;
                    video.dataset.trReveal = reveal;
                    const x = parseFloat(video.dataset.baseX || '0');
                    const y = parseFloat(video.dataset.baseY || '0');
                    const w = parseFloat(video.dataset.baseW || '0');
                    const h = parseFloat(video.dataset.baseH || '0');
                    video.style.left = (x + dx * w) + 'px';
                    video.style.top = (y + dy * h) + 'px';
                    video.style.opacity = opacity;
                    // Circular reveal (CIRCLE transition), matching the FFmpeg geq mask.
                    const cp = reveal >= 1 ? 'none' : ('circle(' + (reveal * Math.hypot(w / 2, h / 2)) + 'px at 50% 50%)');
                    video.style.clipPath = cp;
                    video.style.webkitClipPath = cp;
                }
            }
        """)
        updateTransition(
            alpha.toDouble(),
            offsetXFraction.toDouble(),
            offsetYFraction.toDouble(),
            revealRadiusFraction.toDouble()
        )
    }

    // Hide the shared <video> only when this player actually leaves the composition (no video clip
    // under the playhead anymore) — not on every state change.
    DisposableEffect(Unit) {
        onDispose {
            val hideVideo = js("""
                function() {
                    const video = document.getElementById('compose-video-preview');
                    if (video) {
                        video.style.display = 'none';
                        video.pause();
                    }
                }
            """)
            hideVideo()
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
                val updateBounds = js("""
                    function(x, y, w, h) {
                        const video = document.getElementById('compose-video-preview');
                        if (video) {
                            // Remember the un-transformed stage bounds so the transition (opacity +
                            // slide offset + circular reveal) can be re-applied over them
                            // independently of layout.
                            video.dataset.baseX = x;
                            video.dataset.baseY = y;
                            video.dataset.baseW = w;
                            video.dataset.baseH = h;
                            const dx = parseFloat(video.dataset.trDx || '0');
                            const dy = parseFloat(video.dataset.trDy || '0');
                            const op = video.dataset.trOp || '1';
                            const rev = parseFloat(video.dataset.trReveal || '1');
                            video.style.left = (x + dx * w) + 'px';
                            video.style.top = (y + dy * h) + 'px';
                            video.style.width = w + 'px';
                            video.style.height = h + 'px';
                            video.style.opacity = op;
                            const cp = rev >= 1 ? 'none' : ('circle(' + (rev * Math.hypot(w / 2, h / 2)) + 'px at 50% 50%)');
                            video.style.clipPath = cp;
                            video.style.webkitClipPath = cp;
                            video.style.display = 'block';
                        }
                    }
                """)
                updateBounds(
                    (windowOffset.x / density).toDouble(),
                    (windowOffset.y / density).toDouble(),
                    (width / density).toDouble(),
                    (height / density).toDouble()
                )
            }
    )
}
