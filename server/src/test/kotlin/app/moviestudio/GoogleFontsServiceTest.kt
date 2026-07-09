package app.moviestudio

import app.moviestudio.service.GoogleFontsConfig
import app.moviestudio.service.GoogleFontsService
import kotlin.test.*

class GoogleFontsServiceTest {

    // A trimmed-down Developer API response: two families, one with an http:// file URL.
    private val samplePayload = """
        {
          "kind": "webfonts#webfontList",
          "items": [
            {
              "family": "Roboto",
              "variants": ["100", "100italic", "regular", "italic", "700", "700italic"],
              "subsets": ["cyrillic", "latin", "latin-ext"],
              "version": "v32",
              "lastModified": "2024-09-04",
              "files": {
                "regular": "http://fonts.gstatic.com/s/roboto/v32/regular.ttf",
                "700": "https://fonts.gstatic.com/s/roboto/v32/700.ttf"
              },
              "category": "sans-serif",
              "kind": "webfonts#webfont",
              "menu": "http://fonts.gstatic.com/s/roboto/v32/menu.ttf"
            },
            {
              "family": "Lobster",
              "variants": ["regular"],
              "subsets": ["latin", "vietnamese"],
              "files": {
                "regular": "https://fonts.gstatic.com/s/lobster/v30/regular.ttf"
              },
              "category": "display",
              "kind": "webfonts#webfont",
              "menu": "https://fonts.gstatic.com/s/lobster/v30/menu.ttf"
            }
          ]
        }
    """.trimIndent()

    private val fonts = GoogleFontsService.parseCatalog(samplePayload)

    // ------------------------------------------------------------------------ catalog parsing

    @Test
    fun parseCatalogReadsFamiliesInApiOrder() {
        assertEquals(listOf("Roboto", "Lobster"), fonts.map { it.family })
    }

    @Test
    fun parseCatalogReadsVariantsSubsetsAndCategory() {
        val roboto = fonts.first()
        assertEquals(listOf("100", "100italic", "regular", "italic", "700", "700italic"), roboto.variants)
        assertEquals(listOf("cyrillic", "latin", "latin-ext"), roboto.subsets)
        assertEquals("sans-serif", roboto.category)
    }

    @Test
    fun parseCatalogUpgradesFileAndMenuUrlsToHttps() {
        val roboto = fonts.first()
        assertEquals("https://fonts.gstatic.com/s/roboto/v32/regular.ttf", roboto.files["regular"])
        assertEquals("https://fonts.gstatic.com/s/roboto/v32/700.ttf", roboto.files["700"])
        assertEquals("https://fonts.gstatic.com/s/roboto/v32/menu.ttf", roboto.menu)
    }

    @Test
    fun parseCatalogHandlesGarbageAndEmptyPayloads() {
        assertTrue(GoogleFontsService.parseCatalog("not json").isEmpty())
        assertTrue(GoogleFontsService.parseCatalog("{}").isEmpty())
    }

    // ------------------------------------------------------------------------ 7-day staleness

    @Test
    fun catalogIsFreshWithinTheTtlAndStaleAfterIt() {
        val now = 1_000_000_000_000L
        val ttl = GoogleFontsConfig.catalogTtlMs
        assertFalse(GoogleFontsService.isStale(now - ttl + 1, now))
        assertTrue(GoogleFontsService.isStale(now - ttl - 1, now))
    }

    @Test
    fun defaultCatalogTtlIsSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, GoogleFontsConfig.catalogTtlMs)
    }

    // ------------------------------------------------------------------------ search & filters

    @Test
    fun searchMatchesFamilyNameCaseInsensitively() {
        val response = GoogleFontsService.buildSearchResponse(fonts, "lob", null, null, 10)
        assertEquals(listOf("Lobster"), response.fonts.map { it.family })
        assertEquals(1, response.totalMatches)
    }

    @Test
    fun searchFiltersBySubsetAndCategory() {
        val bySubset = GoogleFontsService.buildSearchResponse(fonts, "", "cyrillic", null, 10)
        assertEquals(listOf("Roboto"), bySubset.fonts.map { it.family })

        val byCategory = GoogleFontsService.buildSearchResponse(fonts, "", null, "display", 10)
        assertEquals(listOf("Lobster"), byCategory.fonts.map { it.family })
    }

    @Test
    fun searchCapsResultsAtLimitButReportsTotalMatches() {
        val response = GoogleFontsService.buildSearchResponse(fonts, "", null, null, 1)
        assertEquals(1, response.fonts.size)
        assertEquals(2, response.totalMatches)
    }

    @Test
    fun searchExposesFilterOptionListsAndAvailability() {
        val response = GoogleFontsService.buildSearchResponse(fonts, "", null, null, 10)
        assertEquals(listOf("cyrillic", "latin", "latin-ext", "vietnamese"), response.subsets)
        assertEquals(listOf("display", "sans-serif"), response.categories)
        assertTrue(response.catalogAvailable)

        val empty = GoogleFontsService.buildSearchResponse(emptyList(), "", null, null, 10)
        assertFalse(empty.catalogAvailable)
    }

    @Test
    fun searchEntriesUseTheMenuFileAsPreviewUrl() {
        val response = GoogleFontsService.buildSearchResponse(fonts, "Roboto", null, null, 10)
        assertEquals("https://fonts.gstatic.com/s/roboto/v32/menu.ttf", response.fonts.single().previewUrl)
    }

    // ------------------------------------------------------------------------ variant helpers

    @Test
    fun variantNamesRoundTripThroughWeightAndItalic() {
        assertEquals(400, fontVariantWeight("regular"))
        assertEquals(400, fontVariantWeight("italic"))
        assertEquals(700, fontVariantWeight("700italic"))
        assertEquals(100, fontVariantWeight("100"))

        assertFalse(fontVariantItalic("regular"))
        assertTrue(fontVariantItalic("italic"))
        assertTrue(fontVariantItalic("500italic"))
        assertFalse(fontVariantItalic("500"))

        assertEquals("regular", fontVariantName(400, italic = false))
        assertEquals("italic", fontVariantName(400, italic = true))
        assertEquals("700", fontVariantName(700, italic = false))
        assertEquals("700italic", fontVariantName(700, italic = true))
    }

    // ------------------------------------------------------------------------ ids / slugs

    @Test
    fun fontKeysAreStableSlugsSafeForArangoAndOss() {
        assertEquals("open-sans-700italic", GoogleFontsService.fontKey("Open Sans 700italic"))
        assertEquals("noto-sans-jp", GoogleFontsService.fontKey("Noto Sans JP"))
        assertEquals("lobster", GoogleFontsService.fontKey("  Lobster  "))
    }
}
