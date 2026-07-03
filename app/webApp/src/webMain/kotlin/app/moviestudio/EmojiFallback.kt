package app.moviestudio

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.moviestudio.shared.resources.Res

@Composable
fun WithEmojiFallback(content: @Composable () -> Unit) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    var fontsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val bytes = Res.readBytes("font/noto_color_emoji.ttf")
            val emojiFamily = FontFamily(Font(identity = "NotoColorEmoji", data = bytes))
            fontFamilyResolver.preload(emojiFamily)
        }
        fontsLoaded = true
    }

    if (fontsLoaded) {
        content()
    } else {
        // Show nothing while loading, or a blank box
    }
}
