package app.moviestudio

import app.moviestudio.service.SpeechRelayService
import app.moviestudio.service.SpeechRelayService.DashScopeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure protocol helpers of the realtime dictation relay (see
 * [SpeechRelayService]). These need no live socket, so they run deterministically regardless of
 * whether a Qwen API key is configured in the environment.
 */
class SpeechRelayServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesTaskLifecycleEvents() {
        assertTrue(SpeechRelayService.parseDashScopeEvent("""{"header":{"event":"task-started"}}""") is DashScopeEvent.Started)
        assertTrue(SpeechRelayService.parseDashScopeEvent("""{"header":{"event":"task-finished"}}""") is DashScopeEvent.Finished)

        val failed = SpeechRelayService.parseDashScopeEvent(
            """{"header":{"event":"task-failed","error_message":"boom"}}"""
        )
        assertTrue(failed is DashScopeEvent.Failed)
        assertEquals("boom", failed.message)
    }

    @Test
    fun ignoresMalformedOrUnknownFrames() {
        assertEquals(DashScopeEvent.Ignored, SpeechRelayService.parseDashScopeEvent("not json"))
        assertEquals(DashScopeEvent.Ignored, SpeechRelayService.parseDashScopeEvent("""{"header":{"event":"heartbeat"}}"""))
    }

    @Test
    fun parsesResultGeneratedTranscriptWithEndFlag() {
        val partial = SpeechRelayService.parseDashScopeEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"hello"}}}}"""
        )
        assertTrue(partial is DashScopeEvent.Transcript)
        assertEquals("hello", partial.text)
        assertFalse(partial.ended)

        val endedByFlag = SpeechRelayService.parseDashScopeEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"hello world","sentence_end":true}}}}"""
        )
        assertTrue(endedByFlag is DashScopeEvent.Transcript)
        assertTrue(endedByFlag.ended)

        val endedByTime = SpeechRelayService.parseDashScopeEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"done","end_time":1200}}}}"""
        )
        assertTrue(endedByTime is DashScopeEvent.Transcript)
        assertTrue(endedByTime.ended)
    }

    @Test
    fun runningTranscriptAccumulatesFinalizedSentences() {
        val transcript = SpeechRelayService.RunningTranscript()
        // A sentence grows across interim updates...
        assertEquals("hello", transcript.update("hello", ended = false))
        assertEquals("hello there", transcript.update("hello there", ended = false))
        // ...and once ended it becomes a fixed prefix for the next sentence.
        assertEquals("hello there friend", transcript.update("hello there friend", ended = true))
        assertEquals("hello there friend how", transcript.update("how", ended = false))
        assertEquals("hello there friend how are you", transcript.update("how are you", ended = true))
    }

    @Test
    fun detectsStopFrame() {
        assertTrue(SpeechRelayService.isStopFrame("""{"action":"stop"}"""))
        assertFalse(SpeechRelayService.isStopFrame("""{"action":"start"}"""))
        assertFalse(SpeechRelayService.isStopFrame("garbage"))
    }

    @Test
    fun buildsExpectedRunTaskAndFinishFrames() {
        val run = json.parseToJsonElement(
            SpeechRelayService.runTaskFrame("task-42", SpeechRelayService.StartConfig(16000, "pcm"))
        ).jsonObject
        assertEquals("run-task", run["header"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("task-42", run["header"]!!.jsonObject["task_id"]!!.jsonPrimitive.content)
        val params = run["payload"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("pcm", params["format"]!!.jsonPrimitive.content)
        assertEquals("16000", params["sample_rate"]!!.jsonPrimitive.content)

        val finish = json.parseToJsonElement(SpeechRelayService.finishTaskFrame("task-42")).jsonObject
        assertEquals("finish-task", finish["header"]!!.jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildsBrowserFacingTranscriptAndErrorFrames() {
        val transcript = json.parseToJsonElement(SpeechRelayService.transcriptFrame("hi there")).jsonObject
        assertEquals("transcript", transcript["type"]!!.jsonPrimitive.content)
        assertEquals("hi there", transcript["text"]!!.jsonPrimitive.content)

        val error = json.parseToJsonElement(SpeechRelayService.errorFrame("nope")).jsonObject
        assertEquals("error", error["type"]!!.jsonPrimitive.content)
        assertEquals("nope", error["message"]!!.jsonPrimitive.content)
    }
}
