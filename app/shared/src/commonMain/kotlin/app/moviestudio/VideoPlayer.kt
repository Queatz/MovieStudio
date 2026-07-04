package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays the movie preview's active video clip.
 *
 * [alpha] (0..1) and [offsetXFraction]/[offsetYFraction] (fraction of the player's own size,
 * +X = right, +Y = down) let the preview drive a transition-in on the video: a cross-fade via
 * [alpha] and a slide via the offsets, mirroring the FFmpeg export. Defaults render the video
 * fully opaque and un-offset.
 */
@Composable
expect fun VideoPlayer(
    url: String,
    isPlaying: Boolean,
    playhead: Float,
    onTimeUpdate: (Float) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    offsetXFraction: Float = 0f,
    offsetYFraction: Float = 0f
)
