package app.moviestudio

import app.moviestudio.service.QwenAIService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Covers the Qwen-Audio-3.0-ASR-Flash request body and response parsing used to transcribe
 * extracted voice audio (never the clip description) with word timings.
 */
class QwenAsrRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun asrBodySendsAudioUrlAndNoDescriptionText() {
        val audioUrl = "https://oss.example.com/voice-abc.mp3"
        val body = QwenAIService.buildAsrRequestBody("qwen-audio-3.0-asr-flash", audioUrl)

        assertEquals("qwen-audio-3.0-asr-flash", body.str("model"))
        val content = body.input()
            .getValue("messages").jsonArray.single().jsonObject
            .getValue("content").jsonArray.single().jsonObject
        assertEquals("input_audio", content.str("type"))
        assertEquals(audioUrl, content.getValue("input_audio").jsonObject.str("data"))
        assertFalse(body.toString().contains("Voice from"))
        assertNull(content["text"])
        assertEquals("mp3", body.getValue("parameters").jsonObject.str("format"))
    }

    @Test
    fun asrBodyOmitsFormatWhenExtensionIsUnknown() {
        val body = QwenAIService.buildAsrRequestBody(
            "qwen-audio-3.0-asr-flash",
            "https://oss.example.com/audio-without-extension",
        )
        assertNull(body["parameters"])
    }

    @Test
    fun parseAsrResponseReadsWordTimingsInMilliseconds() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "Hello world")
                put("sentence", buildJsonObject {
                    put("text", "Hello world")
                    put("begin_time", 0)
                    put("end_time", 1000)
                    put("words", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "Hello")
                            put("begin_time", 0)
                            put("end_time", 400)
                        })
                        add(buildJsonObject {
                            put("text", "world")
                            put("begin_time", 400)
                            put("end_time", 1000)
                        })
                    })
                })
            })
        }

        val (text, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        assertEquals("Hello world", text)
        assertEquals(2, timings.size)
        assertEquals("Hello", timings[0].word)
        assertEquals(0.0, timings[0].start, 0.0001)
        assertEquals(0.4, timings[0].end, 0.0001)
        assertEquals("world", timings[1].word)
        assertEquals(0.4, timings[1].start, 0.0001)
        assertEquals(1.0, timings[1].end, 0.0001)
    }

    @Test
    fun parseAsrResponseDoesNotUseADescriptionFallback() {
        val response = buildJsonObject {
            put("output", buildJsonObject {})
        }
        assertFailsWith<IllegalStateException> {
            QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        }
    }

    @Test
    fun parseAsrResponseSpreadsWordsWhenTimestampsAreMissing() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "Hello there friend")
            })
        }
        val (text, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 6.0)
        assertEquals("Hello there friend", text)
        assertEquals(3, timings.size)
        assertEquals("Hello", timings[0].word)
        assertEquals(0.0, timings[0].start, 0.0001)
        assertEquals(2.0, timings[0].end, 0.0001)
        assertEquals(6.0, timings.last().end, 0.0001)
    }

    @Test
    fun millisecondTimestampsConvertToSeconds() {
        assertEquals(0.76, QwenAIService.parseAsrTimestampSeconds("760", durationSeconds = 5.0)!!, 0.0001)
        assertEquals(4.2, QwenAIService.parseAsrTimestampSeconds("4.2", durationSeconds = 5.0)!!, 0.0001)
        assertNull(QwenAIService.parseAsrTimestampSeconds(null, durationSeconds = 5.0))
    }

    @Test
    fun asrAudioFormatReadsExtensionIgnoringQueryString() {
        assertEquals("mp3", QwenAIService.asrAudioFormat("https://oss.example/voice.mp3?Expires=1"))
        assertEquals("wav", QwenAIService.asrAudioFormat("clip.wav"))
        assertNull(QwenAIService.asrAudioFormat("https://oss.example/voice"))
    }
}
