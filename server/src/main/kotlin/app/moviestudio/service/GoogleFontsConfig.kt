package app.moviestudio.service

import app.moviestudio.config.Env
import org.slf4j.LoggerFactory

/**
 * Configuration holder for the Google Fonts Developer API integration (the font catalog behind
 * the text/caption font picker).
 *
 * Expected `.env` entries:
 * - GOOGLE_FONTS_API_KEY          Developer API key (free, see
 *                                 https://developers.google.com/fonts/docs/developer_api).
 * - GOOGLE_FONTS_API_URL          Optional endpoint override (default
 *                                 https://www.googleapis.com/webfonts/v1/webfonts).
 * - GOOGLE_FONTS_CATALOG_TTL_MS   How long the cached catalog stays fresh before it is re-fetched
 *                                 (default 7 days).
 *
 * Without a key the picker degrades gracefully to the app-bundled fonts (and any previously
 * cached catalog copy keeps working).
 */
object GoogleFontsConfig {
    private val logger = LoggerFactory.getLogger(GoogleFontsConfig::class.java)

    val apiKey: String = Env.get("GOOGLE_FONTS_API_KEY", "")
    val apiUrl: String = Env.get("GOOGLE_FONTS_API_URL", "https://www.googleapis.com/webfonts/v1/webfonts")

    /** The cached catalog is considered stale and re-fetched after this age (default 7 days). */
    val catalogTtlMs: Long = Env.get("GOOGLE_FONTS_CATALOG_TTL_MS", "").toLongOrNull()
        ?: 7L * 24 * 60 * 60 * 1000

    /** True when a real API key has been supplied. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    fun logStatus() {
        if (isConfigured) {
            logger.info("Google Fonts configured; catalog cache TTL {} ms.", catalogTtlMs)
        } else {
            logger.warn(
                "Google Fonts not configured; the font picker only offers the bundled fonts. " +
                    "Set GOOGLE_FONTS_API_KEY to enable the full catalog."
            )
        }
    }
}
