package app.moviestudio

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await

// Fetches the font file and returns its bytes base64-encoded (the simplest reliable way to move
// binary data across the Wasm/JS boundary), mirroring the fetch bridge in HttpFetchClient.wasmJs.
@JsFun("""
async (url) => {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error('HTTP ' + response.status);
    }
    const buffer = await response.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
}
""")
private external fun fetchBase64(url: String): Promise<JsAny>

@JsFun("(jsString) => jsString")
private external fun jsStringToString(jsString: JsAny): String

/** Browser (Wasm): fetch the font bytes and register them as a skiko font. */
actual suspend fun loadRuntimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily? =
    try {
        val base64 = jsStringToString(fetchBase64(url).await())
        val bytes = Base64.decode(base64)
        FontFamily(
            Font(
                identity = "runtime:$url#$weight${if (italic) "i" else ""}",
                data = bytes,
                weight = FontWeight(weight),
                style = if (italic) FontStyle.Italic else FontStyle.Normal
            )
        )
    } catch (e: Throwable) {
        null
    }
