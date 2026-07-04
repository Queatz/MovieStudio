package app.moviestudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Installs a scriptable stand-in for the browser's SpeechRecognition so the dictation bridge can
// be exercised end-to-end (start → interim results → stop) inside headless Chromium.
private fun installFakeSpeechRecognition(): Unit = js(
    """
    (function() {
        window.__fakeSpeech = { started: 0, stopped: 0, instance: null };
        function FakeRecognition() {
            var self = this;
            this.continuous = false;
            this.interimResults = false;
            this.onresult = null;
            this.onend = null;
            this.start = function() {
                window.__fakeSpeech.started++;
                window.__fakeSpeech.instance = self;
            };
            this.stop = function() {
                window.__fakeSpeech.stopped++;
                if (self.onend) self.onend();
            };
        }
        window.SpeechRecognition = FakeRecognition;
    })()
    """
)

private fun removeFakeSpeechRecognition(): Unit = js(
    """
    (function() {
        delete window.SpeechRecognition;
        delete window.__fakeSpeech;
    })()
    """
)

// Fires the fake recognition's onresult with two result chunks, mirroring the real API shape
// (results[i][0].transcript) so the accumulation logic in the bridge is what's under test.
// NOTE: no IIFE parameters here — redeclaring names that match the Kotlin parameters makes the
// compiler rename the Kotlin ones without rewriting this js() code (the exact production bug).
private fun emitFakeSpeechResult(first: String, second: String): Unit = js(
    """
    (function() {
        var rec = window.__fakeSpeech.instance;
        if (rec && rec.onresult) {
            rec.onresult({ results: [[{ transcript: first }], [{ transcript: second }]] });
        }
    })()
    """
)

private fun fakeSpeechStartedCount(): Int = js("window.__fakeSpeech.started")

private fun fakeSpeechStoppedCount(): Int = js("window.__fakeSpeech.stopped")

private fun fakeSpeechContinuous(): Boolean = js("window.__fakeSpeech.instance.continuous")

private fun fakeSpeechInterim(): Boolean = js("window.__fakeSpeech.instance.interimResults")

class SpeechInputJsTest {

    @Test
    fun dictationBridgeStreamsResultsWhileActiveAndStopsCleanly() {
        installFakeSpeechRecognition()
        try {
            var latest = ""
            var calls = 0
            val started = startRealtimeSpeechInput { text ->
                latest = text
                calls++
            }
            assertTrue(started, "start must report success when SpeechRecognition exists")
            assertEquals(1, fakeSpeechStartedCount())
            assertTrue(fakeSpeechContinuous(), "recognition must run in continuous mode")
            assertTrue(fakeSpeechInterim(), "recognition must stream interim results")

            emitFakeSpeechResult("hello ", "world")
            assertEquals(1, calls)
            assertEquals("hello world", latest)

            emitFakeSpeechResult("hello ", "there")
            assertEquals(2, calls)
            assertEquals("hello there", latest)

            stopRealtimeSpeechInput()
            assertEquals(1, fakeSpeechStoppedCount())

            // After stop, late results must not reach the (now stale) callback.
            emitFakeSpeechResult("too ", "late")
            assertEquals(2, calls)
        } finally {
            stopRealtimeSpeechInput()
            removeFakeSpeechRecognition()
        }
    }

    @Test
    fun startingASecondDictationStopsThePreviousSession() {
        installFakeSpeechRecognition()
        try {
            assertTrue(startRealtimeSpeechInput { })
            assertTrue(startRealtimeSpeechInput { })
            assertEquals(2, fakeSpeechStartedCount())
            assertEquals(1, fakeSpeechStoppedCount())
            stopRealtimeSpeechInput()
            assertEquals(2, fakeSpeechStoppedCount())
        } finally {
            stopRealtimeSpeechInput()
            removeFakeSpeechRecognition()
        }
    }

    @Test
    fun startReportsFailureWithoutSpeechRecognitionSupport() {
        // Headless Chromium has webkitSpeechRecognition; hide both to model Firefox.
        hideNativeSpeechRecognition()
        try {
            assertFalse(startRealtimeSpeechInput { })
            // Stopping with no active session must be a safe no-op.
            stopRealtimeSpeechInput()
        } finally {
            restoreNativeSpeechRecognition()
        }
    }
}

private fun hideNativeSpeechRecognition(): Unit = js(
    """
    (function() {
        window.__savedSpeechRec = window.SpeechRecognition;
        window.__savedWebkitSpeechRec = window.webkitSpeechRecognition;
        window.SpeechRecognition = undefined;
        window.webkitSpeechRecognition = undefined;
    })()
    """
)

private fun restoreNativeSpeechRecognition(): Unit = js(
    """
    (function() {
        window.SpeechRecognition = window.__savedSpeechRec;
        window.webkitSpeechRecognition = window.__savedWebkitSpeechRec;
        delete window.__savedSpeechRec;
        delete window.__savedWebkitSpeechRec;
    })()
    """
)
