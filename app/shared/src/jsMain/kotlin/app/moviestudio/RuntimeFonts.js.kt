package app.moviestudio

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.fetch.RequestInit

/** Browser (JS): fetch the font bytes and register them as a skiko font. */
actual suspend fun loadRuntimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily? =
    try {
        val options = js("({ method: 'GET' })")
        val response = window.fetch(url, options.unsafeCast<RequestInit>()).await()
        if (!response.ok) {
            null
        } else {
            val buffer = response.arrayBuffer().await()
            val int8 = Int8Array(buffer)
            val bytes = ByteArray(int8.length) { int8[it] }
            FontFamily(
                Font(
                    identity = "runtime:$url#$weight${if (italic) "i" else ""}",
                    data = bytes,
                    weight = FontWeight(weight),
                    style = if (italic) FontStyle.Italic else FontStyle.Normal
                )
            )
        }
    } catch (e: Exception) {
        null
    }
