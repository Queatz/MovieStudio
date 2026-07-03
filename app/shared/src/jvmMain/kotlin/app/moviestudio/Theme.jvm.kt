package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import app.moviestudio.shared.resources.Res
import app.moviestudio.shared.resources.noto_color_emoji
import org.jetbrains.compose.resources.Font

/**
 * Desktop (JVM) renders through Skia and has no guaranteed system color-emoji font, so the
 * bundled Noto Color Emoji (COLRv1) font is registered explicitly as a fallback.
 */
@Composable
internal actual fun emojiFallbackFonts(): List<Font> = listOf(Font(Res.font.noto_color_emoji))

@Composable
internal actual fun useBundledAppFonts(): Boolean = true
