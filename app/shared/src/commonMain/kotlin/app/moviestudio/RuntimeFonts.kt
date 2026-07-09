package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads the font file at [url] (an OSS-hosted Google Fonts variant, or the catalog's small
 * "menu" preview subset) and builds a Compose [FontFamily] from it at runtime. The [weight] and
 * [italic] flags are baked into the created Font so text styled with the same weight/style
 * resolves to this exact file. Returns null when the download or font construction fails.
 */
expect suspend fun loadRuntimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily?

// Session-wide cache of runtime-loaded font families, keyed by url+weight+italic, so each font
// file is fetched at most once. Failed loads are cached (as null) to avoid endless refetch loops.
private val runtimeFontCache = mutableMapOf<String, FontFamily?>()
private val runtimeFontMutex = Mutex()

/** [loadRuntimeFontFamily] with the session cache in front of it. */
suspend fun runtimeFontFamily(url: String, weight: Int, italic: Boolean): FontFamily? {
    if (url.isBlank()) return null
    val key = "$url|$weight|$italic"
    runtimeFontMutex.withLock {
        if (runtimeFontCache.containsKey(key)) return runtimeFontCache[key]
    }
    val family = try {
        loadRuntimeFontFamily(url, weight, italic)
    } catch (e: Exception) {
        null
    }
    runtimeFontMutex.withLock { runtimeFontCache[key] = family }
    return family
}

/**
 * The runtime-loaded [FontFamily] for [url] once it is available (cached across the session), or
 * null while it is still loading / when [url] is blank or the download failed — callers should
 * fall back to a bundled font in the meantime.
 */
@Composable
fun rememberRuntimeFontFamily(url: String, weight: Int = 400, italic: Boolean = false): FontFamily? =
    produceState<FontFamily?>(initialValue = null, url, weight, italic) {
        value = runtimeFontFamily(url, weight, italic)
    }.value
