package app.moviestudio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot

/**
 * A centered circular clip used by the `CIRCLE` transition's live preview. [fraction] is the
 * [TransitionVisual.revealRadiusFraction]: the circle's radius as a fraction of the distance from
 * the center to a corner, so `fraction == 1f` covers the whole component and `0f` shows nothing.
 *
 * This is the Compose counterpart to the FFmpeg `geq` alpha mask in `FFmpegService` — both grow the
 * same circle over the transition window so the preview matches the export.
 */
class CircleRevealShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val f = fraction.coerceIn(0f, 1f)
        val maxRadius = hypot(size.width / 2f, size.height / 2f)
        val path = Path().apply {
            addOval(Rect(Offset(size.width / 2f, size.height / 2f), maxRadius * f))
        }
        return Outline.Generic(path)
    }
}
