package app.moviestudio

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Android: download to a temp file (Android fonts must come from a file/resource). */
actual suspend fun loadRuntimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily? =
    withContext(Dispatchers.IO) {
        try {
            val file = File.createTempFile("moviestudio_font_", ".ttf")
            file.deleteOnExit()
            URI(url).toURL().openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            FontFamily(
                Font(
                    file = file,
                    weight = FontWeight(weight),
                    style = if (italic) FontStyle.Italic else FontStyle.Normal
                )
            )
        } catch (e: Exception) {
            null
        }
    }
