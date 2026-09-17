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

    @Test
    fun collapseAsrWordSpacingRemovesLetterSpacingAndOuterSpaces() {
        assertEquals("Xin", QwenAIService.collapseAsrWordSpacing("X i n"))
        assertEquals("chào", QwenAIService.collapseAsrWordSpacing("c h à o"))
        assertEquals("Hello", QwenAIService.collapseAsrWordSpacing("Hello "))
        assertEquals("World", QwenAIService.collapseAsrWordSpacing(" World"))
        // Real word boundaries must be kept ("this is" is two words in one token).
        assertEquals("this is", QwenAIService.collapseAsrWordSpacing("this is"))
    }

    @Test
    fun parseAsrResponseStripsLetterSpacingInsideVietnameseWords() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "Xin chào các bạn")
                put("sentence", buildJsonObject {
                    put("text", "Xin chào các bạn")
                    put("words", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "X i n")
                            put("begin_time", 0)
                            put("end_time", 300)
                        })
                        add(buildJsonObject {
                            put("text", "c h à o")
                            put("begin_time", 300)
                            put("end_time", 700)
                        })
                        add(buildJsonObject {
                            put("text", "c á c")
                            put("begin_time", 700)
                            put("end_time", 1000)
                        })
                        add(buildJsonObject {
                            put("text", "b ạ n")
                            put("begin_time", 1000)
                            put("end_time", 1400)
                        })
                    })
                })
            })
        }

        val (text, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        assertEquals("Xin chào các bạn", text)
        assertEquals(listOf("Xin", "chào", "các", "bạn"), timings.map { it.word })
        assertEquals(0.0, timings[0].start, 0.0001)
        assertEquals(0.3, timings[0].end, 0.0001)
        assertEquals(1.4, timings.last().end, 0.0001)
    }

    @Test
    fun parseAsrResponseMergesCharacterLevelVietnameseTokens() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "Xin chào")
                put("sentence", buildJsonObject {
                    put("text", "Xin chào")
                    put("words", buildJsonArray {
                        add(buildJsonObject { put("text", "X"); put("begin_time", 0); put("end_time", 80) })
                        add(buildJsonObject { put("text", "i"); put("begin_time", 80); put("end_time", 140) })
                        add(buildJsonObject { put("text", "n"); put("begin_time", 140); put("end_time", 220) })
                        add(buildJsonObject { put("text", " "); put("begin_time", 220); put("end_time", 260) })
                        add(buildJsonObject { put("text", "c"); put("begin_time", 260); put("end_time", 320) })
                        add(buildJsonObject { put("text", "h"); put("begin_time", 320); put("end_time", 380) })
                        add(buildJsonObject { put("text", "à"); put("begin_time", 380); put("end_time", 460) })
                        add(buildJsonObject { put("text", "o"); put("begin_time", 460); put("end_time", 540) })
                    })
                })
            })
        }

        val (text, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        assertEquals("Xin chào", text)
        assertEquals(2, timings.size)
        assertEquals("Xin", timings[0].word)
        assertEquals("chào", timings[1].word)
        assertEquals(0.0, timings[0].start, 0.0001)
        assertEquals(0.22, timings[0].end, 0.0001)
        assertEquals(0.26, timings[1].start, 0.0001)
        assertEquals(0.54, timings[1].end, 0.0001)
    }

    @Test
    fun parseAsrResponseUsesTranscriptWordsWhenQwenPutsSpacesInTokens() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "Hello World, this is Alibaba Speech Lab.")
                put("sentence", buildJsonObject {
                    put("text", "Hello World, this is Alibaba Speech Lab.")
                    put("words", buildJsonArray {
                        add(buildJsonObject { put("text", "Hello "); put("begin_time", 0); put("end_time", 280) })
                        add(buildJsonObject { put("text", " World"); put("begin_time", 280); put("end_time", 520) })
                        add(buildJsonObject { put("text", "this is"); put("begin_time", 520); put("end_time", 900) })
                        add(buildJsonObject { put("text", "Alibaba"); put("begin_time", 900); put("end_time", 1300) })
                        add(buildJsonObject { put("text", "Speech"); put("begin_time", 1300); put("end_time", 1600) })
                        add(buildJsonObject { put("text", "Lab"); put("begin_time", 1600); put("end_time", 1900) })
                    })
                })
            })
        }

        val (text, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        assertEquals("Hello World, this is Alibaba Speech Lab.", text)
        assertEquals(
            listOf("Hello", "World,", "this", "is", "Alibaba", "Speech", "Lab."),
            timings.map { it.word },
        )
        timings.forEach { word ->
            assertFalse(word.word.startsWith(" "), "leading space in '${word.word}'")
            assertFalse(word.word.endsWith(" "), "trailing space in '${word.word}'")
        }
    }

    @Test
    fun parseAsrResponseKeepsChineseCharacterTokens() {
        val response = buildJsonObject {
            put("output", buildJsonObject {
                put("text", "你好世界")
                put("sentence", buildJsonObject {
                    put("text", "你好世界")
                    put("words", buildJsonArray {
                        add(buildJsonObject { put("text", "你"); put("begin_time", 0); put("end_time", 200) })
                        add(buildJsonObject { put("text", "好"); put("begin_time", 200); put("end_time", 400) })
                        add(buildJsonObject { put("text", "世"); put("begin_time", 400); put("end_time", 600) })
                        add(buildJsonObject { put("text", "界"); put("begin_time", 600); put("end_time", 800) })
                    })
                })
            })
        }

        val (_, timings) = QwenAIService.parseAsrResponse(response, durationSeconds = 5.0)
        assertEquals(listOf("你", "好", "世", "界"), timings.map { it.word })
    }
}
