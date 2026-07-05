package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays the movie preview's active video clip.
 *
 * [alpha] (0..1) and [offsetXFraction]/[offsetYFraction] (fraction of the player's own size,
 * +X = right, +Y = down) let the preview drive a transition-in on the video: a cross-fade via
 * [alpha] and a slide via the offsets, mirroring the FFmpeg export. [revealRadiusFraction] is a
 * centered circular reveal mask, as a fraction of the distance from the center to a corner
 * (1 = fully revealed / no mask, 0 = nothing shown) — used by the circle transition. Defaults
 * render the video fully opaque, un-offset and fully revealed.
 *
 * [onEnded] fires once when playback reaches the end of the media, so callers can reset their own
 * `isPlaying`/`playhead` state (e.g. to let a "Play" button restart the clip from the beginning).
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
    offsetYFraction: Float = 0f,
    revealRadiusFraction: Float = 1f,
    onEnded: () -> Unit = {}
)
