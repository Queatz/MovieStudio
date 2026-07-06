package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// No browser, no WebGL canvas: the preview-method toggle is not offered on Android.
actual fun isWebGLPreviewSupported(): Boolean = false

@Composable
actual fun WebGLPreviewSurface(
    layers: List<WebGLPreviewLayer>,
    isPlaying: Boolean,
    modifier: Modifier
) {
    // Unreachable in practice (the toggle is hidden when unsupported); graceful placeholder.
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "WebGL preview is only available in the browser",
            color = Color.White
        )
    }
}
