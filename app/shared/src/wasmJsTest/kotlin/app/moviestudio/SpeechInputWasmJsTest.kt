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
    fun fallsBackToServerRelayWhenWebSpeechUnavailable() {
        // Model Firefox: no Web Speech API, but the mic + WebSocket + AudioContext all exist.
        hideNativeSpeechRecognition()
        installFakeMediaStack()
        // The fake WebSocket is only needed during the (synchronous) mic-permission callback; it
        // is restored immediately afterwards so it can never interfere with the test runner.
        installFakeWebSocket()
        try {
            var latest = ""
            var calls = 0
            val started = startRealtimeSpeechInput { text ->
                latest = text
                calls++
            }
            restoreWebSocket()

            assertTrue(started, "must fall back to the server relay when Web Speech is unavailable")
            assertTrue(fakeDictationWsUrl().endsWith("/api/speech/ws"), "must open the speech relay socket")

            // On open the browser announces the audio format it will stream.
            triggerFakeDictationWsOpen()
            assertTrue(
                fakeDictationWsSentJoined().contains("\"action\":\"start\""),
                "must send the start/format handshake on open"
            )

            // Running transcripts pushed by the server stream straight into the field.
            emitFakeDictationMessage("""{"type":"transcript","text":"hello world"}""")
            assertEquals(1, calls)
            assertEquals("hello world", latest)

            emitFakeDictationMessage("""{"type":"transcript","text":"hello world again"}""")
            assertEquals(2, calls)
            assertEquals("hello world again", latest)

            stopRealtimeSpeechInput()
            assertTrue(
                fakeDictationWsSentJoined().contains("\"action\":\"stop\""),
                "releasing the press must tell the relay to stop"
            )
        } finally {
            stopRealtimeSpeechInput()
            restoreWebSocket()
            removeFakeMediaStack()
            restoreNativeSpeechRecognition()
        }
    }

    @Test
    fun startReportsFailureWhenNoSpeechSupportAtAll() {
        // Neither the Web Speech API nor microphone capture is available: dictation stays off.
        hideNativeSpeechRecognition()
        installMediaDevicesWithoutGetUserMedia()
        try {
            assertFalse(startRealtimeSpeechInput { })
            // Stopping with no active session must be a safe no-op.
            stopRealtimeSpeechInput()
        } finally {
            removeMediaDevicesShadow()
            restoreNativeSpeechRecognition()
        }
    }
}

// Installs a fully synchronous mic stack: getUserMedia resolves via a synchronous "thenable" so
// the whole fallback setup (AudioContext, capture node, WebSocket) runs inline within the call,
// letting the test drive it deterministically without awaiting real promises.
@JsFun(
    """
() => {
    const fakeStream = { getTracks: () => [{ stop: () => {} }] };
    Object.defineProperty(navigator, 'mediaDevices', {
        configurable: true,
        value: {
            getUserMedia: () => ({ then: (onF) => { onF(fakeStream); return { catch: () => {} }; } })
        }
    });
    function FakeAudioContext() {
        this.sampleRate = 48000;
        this.state = 'running';
        this.destination = {};
        this.resume = () => {};
        this.close = () => { this.state = 'closed'; };
        this.createMediaStreamSource = () => ({ connect: () => {}, disconnect: () => {} });
        this.createScriptProcessor = () => ({ connect: () => {}, disconnect: () => {}, onaudioprocess: null });
    }
    window.__savedAudioContext = window.AudioContext;
    window.__savedWebkitAudioContext = window.webkitAudioContext;
    window.AudioContext = FakeAudioContext;
    window.webkitAudioContext = FakeAudioContext;
}
"""
)
private external fun installFakeMediaStack()

@JsFun(
    """
() => {
    try { delete navigator.mediaDevices; } catch (e) {}
    window.AudioContext = window.__savedAudioContext;
    window.webkitAudioContext = window.__savedWebkitAudioContext;
    delete window.__savedAudioContext;
    delete window.__savedWebkitAudioContext;
    delete window.__fakeDictationWs;
}
"""
)
private external fun removeFakeMediaStack()

// A recording WebSocket stand-in. It is swapped in only for the synchronous dictation setup and
// restored right after, so the test runner's own WebSocket connection is never disturbed.
@JsFun(
    """
() => {
    window.__savedWebSocket = window.WebSocket;
    function FakeWebSocket(url) {
        const self = this;
        window.__fakeDictationWs = self;
        self.url = url;
        self.readyState = 1;
        self.binaryType = '';
        self.sent = [];
        self.onopen = null;
        self.onmessage = null;
        self.send = (data) => { self.sent.push(data); };
        self.close = () => { self.readyState = 3; };
    }
    window.WebSocket = FakeWebSocket;
}
"""
)
private external fun installFakeWebSocket()

@JsFun(
    """
() => {
    if (window.__savedWebSocket) { window.WebSocket = window.__savedWebSocket; }
    delete window.__savedWebSocket;
}
"""
)
private external fun restoreWebSocket()

@JsFun("() => String(window.__fakeDictationWs.url)")
private external fun fakeDictationWsUrl(): String

@JsFun("() => window.__fakeDictationWs.sent.join('|')")
private external fun fakeDictationWsSentJoined(): String

@JsFun("() => { window.__fakeDictationWs.onopen(); }")
private external fun triggerFakeDictationWsOpen()

@JsFun("(data) => { window.__fakeDictationWs.onmessage({ data: data }); }")
private external fun emitFakeDictationMessage(data: String)

@JsFun("() => { Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {} }); }")
private external fun installMediaDevicesWithoutGetUserMedia()

@JsFun("() => { try { delete navigator.mediaDevices; } catch (e) {} }")
private external fun removeMediaDevicesShadow()
