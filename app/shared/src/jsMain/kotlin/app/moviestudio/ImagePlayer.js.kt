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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
actual fun ImagePlayer(
    url: String,
    offsetXPercent: Double,
    offsetYPercent: Double,
    modifier: Modifier
) {
    // Track the overlay's latest laid-out bounds as state. Compose effects run AFTER
    // onGloballyPositioned, and a still image (unlike a playing video) doesn't re-lay-out every
    // frame, so applying the overlay position directly inside onGloballyPositioned could either race
    // the element's creation or use a stale/early coordinate that never gets corrected — which made
    // the image render outside the playback area. Driving all DOM writes from a LaunchedEffect keyed
    // on the latest bounds guarantees the final, settled coordinates always win.
    var bounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(url, offsetXPercent, offsetYPercent, bounds) {
        val b = bounds ?: return@LaunchedEffect
        val applyImage = js("""
            function(url, x, y, left, top, w, h) {
                let img = document.getElementById('compose-image-preview');
                if (!img) {
                    img = document.createElement('img');
                    img.id = 'compose-image-preview';
                    img.style.position = 'absolute';
                    img.style.zIndex = '1000';
                    img.style.backgroundColor = 'black';
                    // Center-crop fit: fill the (aspect-constrained) viewport and crop the overflow.
                    img.style.objectFit = 'cover';
                    document.body.appendChild(img);
                }
                if (url && img.src !== url) {
                    img.src = url;
                }
                img.style.objectPosition = x + '% ' + y + '%';
                img.style.left = left + 'px';
                img.style.top = top + 'px';
                img.style.width = w + 'px';
                img.style.height = h + 'px';
                img.style.display = 'block';
            }
        """)
        applyImage(
            url,
            offsetXPercent,
            offsetYPercent,
            b.left.toDouble(),
            b.top.toDouble(),
            b.width.toDouble(),
            b.height.toDouble()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val hideImage = js("""
                function() {
                    const img = document.getElementById('compose-image-preview');
                    if (img) { img.style.display = 'none'; }
                }
            """)
            hideImage()
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val windowOffset = coordinates.localToWindow(Offset.Zero)
                bounds = Rect(
                    windowOffset.x,
                    windowOffset.y,
                    windowOffset.x + coordinates.size.width,
                    windowOffset.y + coordinates.size.height
                )
            }
    )
}
