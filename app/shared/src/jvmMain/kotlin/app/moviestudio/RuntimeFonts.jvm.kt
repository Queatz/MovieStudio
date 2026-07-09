package app.moviestudio

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Desktop: download the raw bytes and register them as a skiko font. */
actual suspend fun loadRuntimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            FontFamily(
                Font(
                    identity = "runtime:$url#$weight${if (italic) "i" else ""}",
                    data = bytes,
                    weight = FontWeight(weight),
                    style = if (italic) FontStyle.Italic else FontStyle.Normal
                )
            )
        } catch (e: Exception) {
            null
        }
    }
