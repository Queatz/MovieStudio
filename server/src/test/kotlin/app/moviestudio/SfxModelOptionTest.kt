package app.moviestudio

import app.moviestudio.routing.GenerateMediaRequest
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.GenerationCommon
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Covers the sound-effect model option ([GenerationSetup.sfxModel]) end to end on the data
 * level: API request decoding, job payload round-trips and backward compatibility with
 * generation configs stored before the option existed.
 */
class SfxModelOptionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigsWithoutSfxModelDefaultToWan() {
        val decoded = json.decodeFromString(
            GenerationSetup.serializer(),
            """{"kind":"sfx","prompt":"Door slam"}"""
        )
        assertEquals("wan", decoded.sfxModel)
    }

    @Test
    fun apiRequestCarriesSfxModel() {
        val body = """{"movieId":"m1","setup":{"kind":"sfx","prompt":"Thunder","sfxModel":"fun-audiogen"}}"""
        val request = json.decodeFromString(GenerateMediaRequest.serializer(), body)
        assertEquals("fun-audiogen", request.setup.sfxModel)
    }

    @Test
    fun jobPayloadRoundTripsEverySupportedSfxModel() {
        for (model in SUPPORTED_SFX_MODELS) {
            val payload = AiJobPayload(setup = GenerationSetup(kind = "sfx", prompt = "Rain", sfxModel = model))
            val encoded = json.encodeToString(AiJobPayload.serializer(), payload)
            val parsed = GenerationCommon.parsePayload(encoded)
            assertEquals(model, parsed.setup.sfxModel)
        }
    }

    @Test
    fun supportedSfxModelsIncludeFunAudioGenOptions() {
        assertTrue("fun-audiogen" in SUPPORTED_SFX_MODELS)
        assertTrue("fun-audiogen-vd" in SUPPORTED_SFX_MODELS)
    }
}
