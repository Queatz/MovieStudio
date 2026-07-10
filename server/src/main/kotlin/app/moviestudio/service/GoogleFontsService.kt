package app.moviestudio.service

import app.moviestudio.FontCatalogEntry
import app.moviestudio.FontPref
import app.moviestudio.FontPrefsResponse
import app.moviestudio.FontSearchResponse
import app.moviestudio.StudioFont
import app.moviestudio.database.FontCatalogDocument
import app.moviestudio.database.FontCatalogRepository
import app.moviestudio.database.FontPrefRepository
import app.moviestudio.database.FontRepository
import app.moviestudio.storage.OssService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Google Fonts integration behind the text/caption font picker.
 *
 * The full catalog (`webfonts?key=...&sort=popularity`) is fetched once and cached in ArangoDB
 * ([FontCatalogRepository]); it is only re-fetched when older than
 * [GoogleFontsConfig.catalogTtlMs] (7 days by default), so server restarts never re-hit the API
 * needlessly. All Google Fonts are open-source (OFL/Apache), so when a user picks a font variant
 * its `.ttf` is downloaded once, re-hosted on our own OSS bucket and persisted ([FontRepository])
 * — the same file is then reused across all movies by both the live preview and the FFmpeg
 * export, and is never downloaded from Google again.
 *
 * Pin and recently-used state is stored per family in [FontPrefRepository].
 */
object GoogleFontsService {
    private val logger = LoggerFactory.getLogger(GoogleFontsService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val CATALOG_DOC_ID = "google"
    private const val RECENT_LIMIT = 10

    /** One parsed catalog family, including the per-variant download URLs (server-side only). */
    data class GoogleFont(
        val family: String,
        val category: String,
        val variants: List<String>,
        val subsets: List<String>,
        /** Variant name ("regular", "700italic", ...) → direct `.ttf` download URL. */
        val files: Map<String, String>,
        /** Small "menu" subset of the font (family-name glyphs only), used for picker previews. */
        val menu: String
    )

    private data class CatalogState(val fetchedAt: Long, val fonts: List<GoogleFont>)

    @Volatile
    private var catalogState: CatalogState? = null
    private val catalogMutex = Mutex()

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 120_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
    }

    /** Warms the catalog on server start (fetching it only when the cached copy is stale/missing). */
    fun init(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { catalog() }
                .onFailure { logger.warn("Could not warm the Google Fonts catalog: {}", it.message) }
        }
    }

    /** True when [fetchedAt] is older than the configured catalog TTL (7 days by default). */
    internal fun isStale(fetchedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - fetchedAt > GoogleFontsConfig.catalogTtlMs

    /**
     * The Google Fonts catalog, resolved memory → DB cache → Developer API. A fresh cached copy is
     * served as-is; a stale/missing one triggers a re-fetch (persisted back to the DB with the new
     * timestamp). When the API is unavailable or unconfigured, any stale copy keeps being served,
     * and an empty list is the last resort (the picker then offers only the bundled fonts).
     */
    suspend fun catalog(): List<GoogleFont> {
        val now = System.currentTimeMillis()
        catalogState?.let { if (!isStale(it.fetchedAt, now)) return it.fonts }
        return catalogMutex.withLock {
            catalogState?.let { if (!isStale(it.fetchedAt, now)) return@withLock it.fonts }
            val cachedDoc = runCatching { FontCatalogRepository.getById(CATALOG_DOC_ID) }.getOrNull()
            if (cachedDoc != null && !isStale(cachedDoc.fetchedAt, now)) {
                val fonts = parseCatalog(cachedDoc.payload)
                catalogState = CatalogState(cachedDoc.fetchedAt, fonts)
                logger.info("Google Fonts catalog loaded from cache: {} families.", fonts.size)
                return@withLock fonts
            }
            if (GoogleFontsConfig.isConfigured) {
                try {
                    val payload = fetchCatalogPayload()
                    val fonts = parseCatalog(payload)
                    if (fonts.isNotEmpty()) {
                        runCatching {
                            FontCatalogRepository.upsert(
                                FontCatalogDocument(
                                    id = CATALOG_DOC_ID,
                                    fetchedAt = now,
                                    payload = payload,
                                    createdAt = cachedDoc?.createdAt ?: now
                                )
                            )
                        }.onFailure { logger.warn("Could not persist the Google Fonts catalog cache: {}", it.message) }
                        catalogState = CatalogState(now, fonts)
                        logger.info("Google Fonts catalog refreshed from the API: {} families.", fonts.size)
                        return@withLock fonts
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to refresh the Google Fonts catalog: {}", e.message)
                }
            }
            // Fall back to the stale cached copy (still perfectly usable) or nothing at all.
            val fallback = cachedDoc?.let { parseCatalog(it.payload) } ?: emptyList()
            if (fallback.isNotEmpty() && cachedDoc != null) {
                catalogState = CatalogState(cachedDoc.fetchedAt, fallback)
                logger.info("Serving stale Google Fonts catalog cache: {} families.", fallback.size)
            }
            fallback
        }
    }

    private suspend fun fetchCatalogPayload(): String {
        val url = "${GoogleFontsConfig.apiUrl}?key=${GoogleFontsConfig.apiKey}&sort=popularity"
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Google Fonts API returned ${response.status}")
        }
        return response.bodyAsText()
    }

    /** Parses the raw Developer API response, preserving its (popularity) order. */
    internal fun parseCatalog(payload: String): List<GoogleFont> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element.jsonObject
            val family = obj["family"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GoogleFont(
                family = family,
                category = obj["category"]?.jsonPrimitive?.contentOrNull ?: "",
                variants = obj["variants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                subsets = obj["subsets"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                files = obj["files"]?.jsonObject
                    ?.mapValues { (_, value) -> httpsUrl(value.jsonPrimitive.content) }
                    ?: emptyMap(),
                menu = httpsUrl(obj["menu"]?.jsonPrimitive?.contentOrNull ?: "")
            )
        }
    }

    /** Google's file URLs are sometimes plain `http://`; always store and serve `https://`. */
    internal fun httpsUrl(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    /**
     * Catalog families matching [query] (case-insensitive substring of the family name), the
     * [subset] language and the [category], in popularity order, capped at [limit]. The full
     * subset/category option lists are always included so the picker can build its filters.
     */
    suspend fun search(query: String, subset: String?, category: String?, limit: Int): FontSearchResponse =
        buildSearchResponse(catalog(), query, subset, category, limit)

    /** The pure filtering behind [search], separated for testability. */
    internal fun buildSearchResponse(
        fonts: List<GoogleFont>,
        query: String,
        subset: String?,
        category: String?,
        limit: Int
    ): FontSearchResponse {
        val q = query.trim()
        val matches = fonts.filter { font ->
            (q.isEmpty() || font.family.contains(q, ignoreCase = true)) &&
                (subset.isNullOrBlank() || font.subsets.contains(subset)) &&
                (category.isNullOrBlank() || font.category.equals(category, ignoreCase = true))
        }
        return FontSearchResponse(
            fonts = matches.take(limit.coerceAtLeast(1)).map { it.toCatalogEntry() },
            subsets = fonts.flatMap { it.subsets }.distinct().sorted(),
            categories = fonts.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
            totalMatches = matches.size,
            catalogAvailable = fonts.isNotEmpty()
        )
    }

    private fun GoogleFont.toCatalogEntry() = FontCatalogEntry(
        family = family,
        category = category,
        variants = variants,
        subsets = subsets,
        previewUrl = menu
    )

    /** The user's pinned (A-Z) and recently used (newest first) families, resolved to catalog entries. */
    suspend fun prefs(): FontPrefsResponse {
        val prefs = runCatching { FontPrefRepository.listAll() }.getOrElse { emptyList() }
        val byFamily = catalog().associateBy { it.family.lowercase() }
        fun resolve(family: String): FontCatalogEntry? = byFamily[family.lowercase()]?.toCatalogEntry()
        return FontPrefsResponse(
            pinned = prefs.filter { it.pinned }
                .sortedBy { it.family.lowercase() }
                .mapNotNull { resolve(it.family) },
            recent = prefs.filter { it.lastUsedAt > 0 }
                .sortedByDescending { it.lastUsedAt }
                .take(RECENT_LIMIT)
                .mapNotNull { resolve(it.family) }
        )
    }

    /** Pins or unpins [family] in the picker, returning the updated pinned/recent lists. */
    suspend fun setPinned(family: String, pinned: Boolean): FontPrefsResponse {
        upsertPref(family) { it.copy(pinned = pinned) }
        return prefs()
    }

    private fun upsertPref(family: String, mutate: (FontPref) -> FontPref) {
        val now = System.currentTimeMillis()
        val id = fontKey(family)
        val base = FontPrefRepository.getById(id) ?: FontPref(id = id, family = family, createdAt = now)
        FontPrefRepository.upsert(mutate(base))
    }

    /**
     * The persisted [StudioFont] for [family]+[variant]. The `.ttf` is downloaded from Google and
     * re-hosted on OSS only the very first time the variant is ever requested; afterwards the
     * stored record is returned directly, so fonts are shared across all movies. Also touches the
     * family's last-used time so it surfaces under "Recently used".
     */
    suspend fun ensureFont(family: String, variant: String): StudioFont {
        upsertPref(family) { it.copy(lastUsedAt = System.currentTimeMillis()) }
        val id = fontKey("$family $variant")
        FontRepository.getById(id)?.let { return it }

        val font = catalog().firstOrNull { it.family.equals(family, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown font family: $family")
        val fileUrl = font.files[variant]
            ?: font.files["regular"]
            ?: font.files.values.firstOrNull()
            ?: throw IllegalArgumentException("Font family '$family' has no downloadable files")
        val extension = fileUrl.substringAfterLast('.', "ttf").substringBefore('?').ifBlank { "ttf" }

        logger.info("Downloading font '{}' variant '{}' from {}", family, variant, fileUrl)
        val tempFile = MediaUtil.downloadToTemp(fileUrl, ".$extension")
        try {
            val url = OssService.uploadFile("fonts/$id.$extension", tempFile)
            val record = StudioFont(
                id = id,
                family = font.family,
                variant = variant,
                url = url,
                createdAt = System.currentTimeMillis()
            )
            return FontRepository.upsert(record)
        } finally {
            tempFile.delete()
        }
    }

    /** A stable slug of a family (+variant) name, safe as an Arango `_key` and an OSS object key. */
    internal fun fontKey(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
