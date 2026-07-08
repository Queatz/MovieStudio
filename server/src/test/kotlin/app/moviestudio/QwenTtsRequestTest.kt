package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwenTtsRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.payload(): JsonObject = getValue("payload").jsonObject

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

        val body = QwenAIService.buildClonedVoiceTtsRequestBody(
            setup = setup,
            voice = "cosyvoice-myclone-abc123",
            model = "cosyvoice-v3.5-plus"
        )
        val payload = body.payload()
        val input = payload.getValue("input").jsonObject
        val parameters = payload.getValue("parameters").jsonObject

        assertEquals("audio", payload.str("task_group"))
        assertEquals("tts", payload.str("task"))
        assertEquals("SpeechSynthesizer", payload.str("function"))
        assertEquals("cosyvoice-v3.5-plus", payload.str("model"))
        assertTrue(input.isEmpty())
        assertEquals("cosyvoice-myclone-abc123", parameters.str("voice"))
    }

    @Test
    fun clonedVoiceOmitsInstructBecauseCosyVoiceDoesNotSupportIt() {
        // Even when the setup carries voice instructions, the cloned-voice body must not send an
        // `instruct` input: CosyVoice rejects it and it is a qwen-tts-only steering field.
        val setup = GenerationSetup(kind = "tts", prompt = "Line", instructions = "sad")

        val input = QwenAIService
            .buildClonedVoiceTtsRequestBody(setup, "cosyvoice-myclone-abc123", "cosyvoice-v3.5-plus")
            .payload()
            .getValue("input")
            .jsonObject

        assertNull(input["instruct"])
    }

    @Test
    fun clonedVoiceWebSocketContinueTaskCarriesTextOnly() {
        val setup = GenerationSetup(kind = "tts", prompt = "This is my cloned voice")

        val body = QwenAIService.buildCosyVoiceContinueTaskBody(
            setup = setup,
            model = "cosyvoice-v3.5-plus",
            taskId = "task123"
        )
        val input = body.payload().getValue("input").jsonObject

        assertEquals("This is my cloned voice", input.str("text"))
        assertNull(input["voice"])
    }

    @Test
    fun clonedVoiceWebSocketRunTaskCarriesAudioParameters() {
        val setup = GenerationSetup(kind = "tts", prompt = "Hello from CosyVoice")

        val body = QwenAIService.buildClonedVoiceTtsRequestBody(setup, "cosyvoice-custom-voice", "cosyvoice-v3.5-plus")
        val parameters = body.payload().getValue("parameters").jsonObject

        assertEquals("mp3", parameters.str("format"))
        assertEquals("PlainText", parameters.str("text_type"))
        assertEquals("cosyvoice-custom-voice", parameters.str("voice"))
        assertEquals(24000L, parameters.getValue("sample_rate").jsonPrimitive.long)
    }

    @Test
    fun voiceDesignEnrollsFromDescriptionNotAudioUrl() {
        val body = QwenAIService.buildVoiceDesignRequestBody(
            prefix = "seacaptain",
            description = "A warm, gravelly older man with a slow storyteller's cadence.",
            previewText = "Ahoy there, welcome aboard."
        )
        val input = body.input()

        // Voice Design enrolls against CosyVoice via natural language: the provider requires BOTH
        // `voice_prompt` (the description) and `preview_text` (line spoken in the preview clip) -
        // sending only one of them fails with HTTP 400 "provide url, or provide both voice_prompt
        // and preview_text.". Unlike voice cloning, it does NOT carry a reference audio `url`.
        assertEquals("create_voice", input.str("action"))
        assertEquals("cosyvoice-v3.5-plus", input.str("target_model"))
        assertEquals("seacaptain", input.str("prefix"))
        assertEquals("A warm, gravelly older man with a slow storyteller's cadence.", input.str("voice_prompt"))
        assertEquals("Ahoy there, welcome aboard.", input.str("preview_text"))
        assertNull(input["url"])
        assertNull(input["text"])
    }

    @Test
    fun voiceDesignDefaultsPreviewTextWhenNotSupplied() {
        val body = QwenAIService.buildVoiceDesignRequestBody(
            prefix = "narrator",
            description = "A calm, measured documentary narrator."
        )

        assertEquals(VOICE_SAMPLE_TEXT, body.input().str("preview_text"))
    }

    @Test
    fun defaultVoiceCatalogExposesSupportedVoicesWithLanguages() {
        // The default Voice Library exposes the full documented non-real-time Qwen-TTS voice list,
        // each with its spoken languages — not just a handful of names.
        assertEquals(QWEN_VOICE_CATALOG.map { it.id }, QWEN_VOICE_PRESETS)
        assertEquals(35, QWEN_VOICE_CATALOG.size)
        val cherry = QWEN_VOICE_CATALOG.first { it.id == "Cherry" }
        assertTrue(cherry.languages.contains("English"))
        assertTrue(QWEN_VOICE_CATALOG.all { it.languages.isNotEmpty() })
    }

    @Test
    fun defaultVoiceCatalogIncludesAllDocumentedNonRealtimeVoices() {
        val documented = listOf(
            "Cherry", "Serena", "Ethan", "Chelsie", "Momo", "Vivian", "Moon", "Maia",
            "Kai", "Nofish", "Bella", "Jennifer", "Ryan", "Katerina", "Aiden", "Mia",
            "Mochi", "Bellona", "Vincent", "Bunny", "Neil", "Elias", "Arthur", "Nini",
            "Seren", "Pip", "Stella", "Bodega", "Sonrisa", "Alek", "Dolce", "Sohee",
            "Lenn", "Emilien", "Andre"
        )

        assertEquals(documented, QWEN_VOICE_CATALOG.map { it.id })
    }
}
