package app.moviestudio

import app.moviestudio.routing.GenerateMediaRequest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Covers the [GenerateMediaRequest.sourceAssetId] field on the data level: it decodes from the
 * API request when present and defaults to null for requests (and older clients) that omit it,
 * so the "which asset is this generation from" spinner infrastructure never breaks decoding.
 */
class GenerateSourceAssetTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun apiRequestCarriesSourceAssetId() {
        val body =
            """{"movieId":"m1","setup":{"kind":"video","prompt":"A cat"},"sourceAssetId":"asset-7"}"""
        val request = json.decodeFromString(GenerateMediaRequest.serializer(), body)
        assertEquals("asset-7", request.sourceAssetId)
    }

    @Test
    fun requestsWithoutSourceAssetIdDefaultToNull() {
        val body = """{"movieId":"m1","setup":{"kind":"video","prompt":"A cat"}}"""
        val request = json.decodeFromString(GenerateMediaRequest.serializer(), body)
        assertNull(request.sourceAssetId)
        assertNull(request.assetId)
    }
}
