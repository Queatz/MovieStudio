package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
actual fun VideoPlayer(
    url: String,
    isPlaying: Boolean,
    playhead: Float,
    onTimeUpdate: (Float) -> Unit,
    modifier: Modifier
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

    DisposableEffect(url, isPlaying, playhead) {
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
                            video.style.left = x + 'px';
                            video.style.top = y + 'px';
                            video.style.width = w + 'px';
                            video.style.height = h + 'px';
                            video.style.display = 'block';
                        }
                    }
                """)
                updateBounds(windowOffset.x.toDouble(), windowOffset.y.toDouble(), width.toDouble(), height.toDouble())
            }
    )
}
