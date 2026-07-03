package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font

/**
 * Keep the default Compose Web text stack on Wasm. It can resolve platform/browser color emoji,
 * while forcing text through the bundled resource fonts prevents that fallback path.
 */
@Composable
internal actual fun emojiFallbackFonts(): List<Font> = emptyList()

@Composable
internal actual fun useBundledAppFonts(): Boolean = false
