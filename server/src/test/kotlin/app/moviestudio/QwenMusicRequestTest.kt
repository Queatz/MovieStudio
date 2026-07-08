package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.Json
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
    fun instrumentalDropsGenderAndSendsInstrumentalLyricsMarker() {
        val setup = GenerationSetup(
            kind = "music",
            theme = "Cinematic instrumental",
            lyric = "these lyrics should be dropped",
            gender = "female",
            instrumental = true
        )

        val input = QwenAIService.buildMusicRequestBody(setup, "fun-music-v1").input()

        assertNull(input["gender"])
        // Fun-Music ignores input.instrumental and writes/sings its own lyrics when `lyrics` is
        // omitted, so instrumental tracks must explicitly send the "[instrumental]" structure tag.
        assertEquals("[instrumental]", input.str("lyrics"))
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

    @Test
    fun lyricsAreExtractedFromExtraInfoWithSectionMarkersPreserved() {
        // Mirrors the real Fun-Music response shape (output.extra_info.lyrics).
        val response = Json.parseToJsonElement(
            """
            {
              "output": {
                "audio": {"url": "http://example.com/track.mp3"},
                "extra_info": {"channels": 2, "lyrics": "[intro]\n\n[verse]\nWe rise together into the light\n\n[outro]\n", "sample_rate": 48000},
                "finish_reason": "stop"
              },
              "usage": {"duration": 166, "input_tokens": 181}
            }
            """.trimIndent()
        ).jsonObject

        val lyrics = QwenAIService.extractMusicLyrics(response)

        assertEquals("[intro]\n\n[verse]\nWe rise together into the light\n\n[outro]", lyrics)
    }

    @Test
    fun instrumentalResponseWithoutLyricsYieldsNull() {
        val response = Json.parseToJsonElement(
            """
            {
              "output": {
                "audio": {"url": "http://example.com/track.mp3"},
                "extra_info": {"channels": 2, "sample_rate": 48000},
                "finish_reason": "stop"
              }
            }
            """.trimIndent()
        ).jsonObject

        assertNull(QwenAIService.extractMusicLyrics(response))
    }

    @Test
    fun blankLyricsYieldNull() {
        val response = Json.parseToJsonElement(
            """{"output": {"extra_info": {"lyrics": "   \n  "}}}"""
        ).jsonObject

        assertNull(QwenAIService.extractMusicLyrics(response))
    }
}
