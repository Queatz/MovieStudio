package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Covers the sound-effect (Alibaba ThinkSound, `thinksound-v1`) DashScope request body shared by
 * the direct text-to-audio and video-driven generation modes.
 */
class QwenAudioRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.parameters(): JsonObject = getValue("parameters").jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun directTextToAudioBodyCarriesPromptAndOmitsVideoUrl() {
        val setup = GenerationSetup(kind = "sfx", prompt = "Thunder rumbling", durationSeconds = 5.0)

        val body = QwenAIService.audioGenRequestBody("thinksound-v1", setup, videoUrl = null)

        assertEquals("thinksound-v1", body.str("model"))
        assertEquals("Thunder rumbling", body.input().str("prompt"))
        // Direct text-to-audio does not score a video, so no video_url rides along.
        assertFalse(body.input().containsKey("video_url"))
        assertEquals(5, body.parameters()["duration"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun videoDrivenBodyCarriesVideoUrl() {
        val setup = GenerationSetup(kind = "sfx", prompt = "Ocean waves", durationSeconds = 8.0)

        val body = QwenAIService.audioGenRequestBody(
            "thinksound-v1",
            setup,
            videoUrl = "https://oss.example.com/source-clip.mp4"
        )

        assertEquals("https://oss.example.com/source-clip.mp4", body.input().str("video_url"))
        assertEquals(8, body.parameters()["duration"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun outOfRangeDurationIsOmittedSoTheModelDecides() {
        val setup = GenerationSetup(kind = "sfx", prompt = "Glass shatter", durationSeconds = 0.0)

        val body = QwenAIService.audioGenRequestBody("thinksound-v1", setup, videoUrl = null)

        assertNull(body.parameters()["duration"])
    }
}
