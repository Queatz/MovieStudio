package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
