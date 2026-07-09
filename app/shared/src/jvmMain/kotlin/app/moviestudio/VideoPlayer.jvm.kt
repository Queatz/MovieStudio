package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

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
    // No real media element on the JVM placeholder — report ready immediately so callers that gate
    // playback on load don't stay stuck waiting.
    LaunchedEffect(url) { onReady() }
    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                translationX = offsetXFraction * size.width
                translationY = offsetYFraction * size.height
            }
            .then(
                if (revealRadiusFraction < 1f) Modifier.clip(CircleRevealShape(revealRadiusFraction))
                else Modifier
            )
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Video Preview (Desktop/JVM)\nURL: $url\nPlaying: $isPlaying\nTime: ${"%.2f".format(playhead)}s",
            color = Color.White
        )
    }
}
