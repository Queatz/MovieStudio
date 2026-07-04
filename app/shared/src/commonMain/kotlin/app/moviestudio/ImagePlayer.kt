package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a still image ([AssetType.IMAGE]) in the movie preview.
 *
 * This exists separately from [VideoPlayer] because image assets placed on the video track must be
 * loaded through an image element, not a video element. Loading a still image through the video
 * player made the browser fetch it as video (Sec-Fetch-Dest video), which OSS rejected because the
 * object is an image, so the frame never rendered.
 *
 * [offsetXPercent]/[offsetYPercent] drive the center-crop position (50 = center) to match the
 * clip's [app.moviestudio.EffectsConfig.offsetX]/[app.moviestudio.EffectsConfig.offsetY].
 */
@Composable
expect fun ImagePlayer(
    url: String,
    offsetXPercent: Double = 50.0,
    offsetYPercent: Double = 50.0,
    modifier: Modifier = Modifier
)
