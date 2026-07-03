package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font

/**
 * Android already ships a native, always-color-correct emoji font that its font-fallback
 * machinery consults automatically for code points missing from the app's font (Yuyu), so
 * no bundled emoji font needs to be registered here.
 */
@Composable
internal actual fun emojiFallbackFonts(): List<Font> = emptyList()

@Composable
internal actual fun useBundledAppFonts(): Boolean = true
