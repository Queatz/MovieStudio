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

// Installs a fully synchronous mic stack: getUserMedia resolves via a synchronous "thenable" so
// the whole fallback setup (AudioContext, capture node, WebSocket) runs inline within the call,
// letting the test drive it deterministically without awaiting real promises.
private fun installFakeMediaStack(): Unit = js(
    """
    (function() {
        var fakeStream = { getTracks: function() { return [{ stop: function() {} }]; } };
        Object.defineProperty(navigator, 'mediaDevices', {
            configurable: true,
            value: {
                getUserMedia: function() {
                    return { then: function(onF) { onF(fakeStream); return { catch: function() {} }; } };
                }
            }
        });
        function FakeAudioContext() {
            this.sampleRate = 48000;
            this.state = 'running';
            this.destination = {};
            this.resume = function() {};
            this.close = function() { this.state = 'closed'; };
            this.createMediaStreamSource = function() { return { connect: function() {}, disconnect: function() {} }; };
            this.createScriptProcessor = function() { return { connect: function() {}, disconnect: function() {}, onaudioprocess: null }; };
        }
        window.__savedAudioContext = window.AudioContext;
        window.__savedWebkitAudioContext = window.webkitAudioContext;
        window.AudioContext = FakeAudioContext;
        window.webkitAudioContext = FakeAudioContext;
    })()
    """
)

private fun removeFakeMediaStack(): Unit = js(
    """
    (function() {
        try { delete navigator.mediaDevices; } catch (e) {}
        window.AudioContext = window.__savedAudioContext;
        window.webkitAudioContext = window.__savedWebkitAudioContext;
        delete window.__savedAudioContext;
        delete window.__savedWebkitAudioContext;
        delete window.__fakeDictationWs;
    })()
    """
)

// A recording WebSocket stand-in. It is swapped in only for the synchronous dictation setup and
// restored right after, so the test runner's own WebSocket connection is never disturbed.
private fun installFakeWebSocket(): Unit = js(
    """
    (function() {
        window.__savedWebSocket = window.WebSocket;
        function FakeWebSocket(url) {
            var self = this;
            window.__fakeDictationWs = self;
            self.url = url;
            self.readyState = 1;
            self.binaryType = '';
            self.sent = [];
            self.onopen = null;
            self.onmessage = null;
            self.send = function(data) { self.sent.push(data); };
            self.close = function() { self.readyState = 3; };
        }
        window.WebSocket = FakeWebSocket;
    })()
    """
)

private fun restoreWebSocket(): Unit = js(
    """
    (function() {
        if (window.__savedWebSocket) { window.WebSocket = window.__savedWebSocket; }
        delete window.__savedWebSocket;
    })()
    """
)

private fun fakeDictationWsUrl(): String = js("String(window.__fakeDictationWs.url)")

private fun fakeDictationWsSentJoined(): String = js("window.__fakeDictationWs.sent.join('|')")

private fun triggerFakeDictationWsOpen(): Unit = js("window.__fakeDictationWs.onopen()")

private fun emitFakeDictationMessage(data: String): Unit = js("window.__fakeDictationWs.onmessage({ data: data })")

private fun installMediaDevicesWithoutGetUserMedia(): Unit = js(
    """
    (function() {
        Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {} });
    })()
    """
)

private fun removeMediaDevicesShadow(): Unit = js(
    """
    (function() { try { delete navigator.mediaDevices; } catch (e) {} })()
    """
)
