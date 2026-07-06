package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class QwenMusicRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun femaleVocalGenderIsSentAsGenderInput() {
        val setup = GenerationSetup(kind = "music", theme = "Upbeat travel vlog", gender = "female")

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertEquals("Upbeat travel vlog", input.str("prompt"))
        assertEquals("female", input.str("gender"))
        assertEquals(false, input["instrumental"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun maleVocalGenderIsSentAsGenderInput() {
        val setup = GenerationSetup(kind = "music", theme = "Driving rock anthem", gender = "male")

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertEquals("male", input.str("gender"))
    }

    @Test
    fun blankGenderIsOmittedSoTheModelDecides() {
        val setup = GenerationSetup(kind = "music", theme = "Ambient underscore")

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertNull(input["gender"])
    }

    @Test
    fun instrumentalDropsGenderAndLyrics() {
        val setup = GenerationSetup(
            kind = "music",
            theme = "Cinematic instrumental",
            lyric = "these lyrics should be dropped",
            gender = "female",
            instrumental = true
        )

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertNull(input["gender"])
        assertNull(input["lyrics"])
        assertEquals(true, input["instrumental"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun themeFallsBackToPromptWhenBlank() {
        val setup = GenerationSetup(kind = "music", prompt = "Jazzy lo-fi beat", gender = "male")

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertEquals("Jazzy lo-fi beat", input.str("prompt"))
        assertEquals("male", input.str("gender"))
        assertFalse(input.containsKey("lyrics"))
    }
}
