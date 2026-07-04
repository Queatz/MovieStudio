@file:OptIn(ExperimentalWasmJsInterop::class)

package app.moviestudio

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Installs a scriptable stand-in for the browser's SpeechRecognition so the dictation bridge can
// be exercised end-to-end (start → interim results → stop) inside headless Chromium.
@JsFun(
    """
() => {
    window.__fakeSpeech = { started: 0, stopped: 0, instance: null };
    function FakeRecognition() {
        const self = this;
        this.continuous = false;
        this.interimResults = false;
        this.onresult = null;
        this.onend = null;
        this.start = () => {
            window.__fakeSpeech.started++;
            window.__fakeSpeech.instance = self;
        };
        this.stop = () => {
            window.__fakeSpeech.stopped++;
            if (self.onend) self.onend();
        };
    }
    window.SpeechRecognition = FakeRecognition;
}
"""
)
private external fun installFakeSpeechRecognition()

@JsFun(
    """
() => {
    delete window.SpeechRecognition;
    delete window.__fakeSpeech;
}
"""
)
private external fun removeFakeSpeechRecognition()

// Fires the fake recognition's onresult with two result chunks, mirroring the real API shape
// (results[i][0].transcript) so the accumulation logic in the bridge is what's under test.
@JsFun(
    """
(first, second) => {
    const rec = window.__fakeSpeech.instance;
    if (rec && rec.onresult) {
        rec.onresult({ results: [[{ transcript: first }], [{ transcript: second }]] });
    }
}
"""
)
private external fun emitFakeSpeechResult(first: String, second: String)

@JsFun("() => window.__fakeSpeech.started")
private external fun fakeSpeechStartedCount(): Int

@JsFun("() => window.__fakeSpeech.stopped")
private external fun fakeSpeechStoppedCount(): Int

@JsFun("() => window.__fakeSpeech.instance.continuous")
private external fun fakeSpeechContinuous(): Boolean

@JsFun("() => window.__fakeSpeech.instance.interimResults")
private external fun fakeSpeechInterim(): Boolean

@JsFun(
    """
() => {
    window.__savedSpeechRec = window.SpeechRecognition;
    window.__savedWebkitSpeechRec = window.webkitSpeechRecognition;
    window.SpeechRecognition = undefined;
    window.webkitSpeechRecognition = undefined;
}
"""
)
private external fun hideNativeSpeechRecognition()

@JsFun(
    """
() => {
    window.SpeechRecognition = window.__savedSpeechRec;
    window.webkitSpeechRecognition = window.__savedWebkitSpeechRec;
    delete window.__savedSpeechRec;
    delete window.__savedWebkitSpeechRec;
}
"""
)
private external fun restoreNativeSpeechRecognition()

class SpeechInputWasmJsTest {

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
