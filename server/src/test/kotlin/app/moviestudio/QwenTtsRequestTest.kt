package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwenTtsRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun presetVoiceSendsTextAndVoiceWithoutInstruct() {
        val setup = GenerationSetup(kind = "tts", prompt = "Hello there")

        val body = QwenAIService.buildTtsRequestBody(setup, "Cherry", "qwen3-tts-flash", instructions = "")
        val input = body.input()

        assertEquals("qwen3-tts-flash", body.str("model"))
        assertEquals("Hello there", input.str("text"))
        assertEquals("Cherry", input.str("voice"))
        assertNull(input["instruct"])
    }

    @Test
    fun presetVoiceCarriesInstructionsAsInstruct() {
        val setup = GenerationSetup(kind = "tts", prompt = "Great news!")

        val input = QwenAIService
            .buildTtsRequestBody(setup, "Cherry", "qwen3-tts-instruct-flash", instructions = "excited")
            .input()

        assertEquals("excited", input.str("instruct"))
    }

    @Test
    fun clonedVoiceTargetsCosyVoiceWithEnrolledVoiceId() {
        val setup = GenerationSetup(kind = "tts", prompt = "This is my cloned voice")

        val body = QwenAIService.buildClonedVoiceTtsRequestBody(setup, "cosyvoice-myclone-abc123", "cosyvoice-v3.5-plus")
        val input = body.input()

        assertEquals("cosyvoice-v3.5-plus", body.str("model"))
        assertEquals("This is my cloned voice", input.str("text"))
        assertEquals("cosyvoice-myclone-abc123", input.str("voice"))
    }

    @Test
    fun clonedVoiceOmitsInstructBecauseCosyVoiceDoesNotSupportIt() {
        // Even when the setup carries voice instructions, the cloned-voice body must not send an
        // `instruct` input: CosyVoice rejects it and it is a qwen-tts-only steering field.
        val setup = GenerationSetup(kind = "tts", prompt = "Line", instructions = "sad")

        val input = QwenAIService
            .buildClonedVoiceTtsRequestBody(setup, "cosyvoice-myclone-abc123", "cosyvoice-v3.5-plus")
            .input()

        assertNull(input["instruct"])
    }

    @Test
    fun voiceDesignEnrollsFromDescriptionNotAudioUrl() {
        val body = QwenAIService.buildVoiceDesignRequestBody(
            prefix = "seacaptain",
            description = "A warm, gravelly older man with a slow storyteller's cadence."
        )
        val input = body.input()

        // Voice Design enrolls against CosyVoice via the natural-language `text` input, and unlike
        // voice cloning does NOT carry a reference audio `url`.
        assertEquals("create_voice", input.str("action"))
        assertEquals("cosyvoice-v3.5-plus", input.str("target_model"))
        assertEquals("seacaptain", input.str("prefix"))
        assertEquals("A warm, gravelly older man with a slow storyteller's cadence.", input.str("text"))
        assertNull(input["url"])
    }

    @Test
    fun defaultVoiceCatalogExposesSupportedVoicesWithLanguages() {
        // The default Voice Library exposes the standard, multilingual Qwen3-TTS voices, each with
        // its spoken languages — not just a handful of names.
        assertEquals(QWEN_VOICE_CATALOG.map { it.id }, QWEN_VOICE_PRESETS)
        assertTrue(QWEN_VOICE_CATALOG.size >= 5)
        val cherry = QWEN_VOICE_CATALOG.first { it.id == "Cherry" }
        assertTrue(cherry.languages.contains("English"))
        assertTrue(QWEN_VOICE_CATALOG.all { it.languages.isNotEmpty() })
    }

    @Test
    fun defaultVoiceCatalogOmitsUnsupportedDialectVoices() {
        // The region-specific Chinese-dialect voices are rejected by the hosted qwen3-tts endpoint
        // with HTTP 400 "Voice '<name>' is not supported", so they must not be offered.
        val unsupported = setOf("Kiki", "Rocky", "Dylan", "Jada", "Sunny", "Li", "Marcus", "Roy", "Peter", "Eric")
        assertTrue(QWEN_VOICE_CATALOG.none { it.id in unsupported })
    }
}
