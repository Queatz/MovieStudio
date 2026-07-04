package app.moviestudio

import kotlinx.coroutines.await
import kotlin.js.Promise

// ------------------------------------------------------------------------- job events WebSocket

private fun jsOpenWebSocket(url: String, onMessage: (String) -> Unit): dynamic = js("""
    (function(url, onMessage) {
        try {
            var ws = new WebSocket(url);
            ws.onmessage = function(event) {
                try { onMessage(String(event.data)); } catch (e) {}
            };
            return ws;
        } catch (e) {
            return null;
        }
    })(url, onMessage)
""")

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? {
    val ws = jsOpenWebSocket(wsUrl, onMessage) ?: return null
    return object : JobEventsConnection {
        override fun close() {
            try {
                ws.close()
            } catch (e: Throwable) {
                // already closed
            }
        }
    }
}

// ------------------------------------------------------------------------------- audio playback

// Maintains a pool of <audio> elements keyed by clip id. Elements are created lazily, resynced
// when they drift more than 0.35s and removed when their clip leaves the playhead window.
private fun jsUpdateAudioPool(itemsJson: String, playing: Boolean): Unit = js("""
    (function(itemsJson, playing) {
        var items = JSON.parse(itemsJson);
        if (!window.__msAudioPool) { window.__msAudioPool = {}; }
        var pool = window.__msAudioPool;
        var wanted = {};
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            wanted[item.key] = true;
            var audio = pool[item.key];
            if (!audio) {
                audio = new Audio();
                audio.preload = 'auto';
                pool[item.key] = audio;
            }
            if (audio.getAttribute('data-src') !== item.url) {
                audio.setAttribute('data-src', item.url);
                audio.src = item.url;
            }
            audio.volume = Math.max(0, Math.min(1, item.volume));
            var drift = Math.abs(audio.currentTime - item.positionSeconds);
            if (drift > 0.35) {
                try { audio.currentTime = item.positionSeconds; } catch (e) {}
            }
            if (playing) {
                if (audio.paused) { audio.play().catch(function(e) {}); }
            } else {
                if (!audio.paused) { audio.pause(); }
            }
        }
        for (var key in pool) {
            if (!wanted[key]) {
                try { pool[key].pause(); } catch (e) {}
                delete pool[key];
            }
        }
    })(itemsJson, playing)
""")

actual fun updateAudioPlayback(items: List<AudioPlayItem>, playing: Boolean) {
    val json = buildString {
        append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append("{\"key\":\"").append(item.key).append("\",")
            append("\"url\":\"").append(item.url).append("\",")
            append("\"positionSeconds\":").append(item.positionSeconds).append(',')
            append("\"volume\":").append(item.volume).append('}')
        }
        append(']')
    }
    jsUpdateAudioPool(json, playing)
}

// -------------------------------------------------------------------------------- frame capture

private fun jsCaptureFrameAndUpload(uploadUrl: String): Promise<Boolean> = js("""
    new Promise(function(resolve) {
        try {
            var video = document.getElementById('compose-video-preview');
            if (!video || !video.videoWidth) { resolve(false); return; }
            var canvas = document.createElement('canvas');
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            var ctx = canvas.getContext('2d');
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
            canvas.toBlob(function(blob) {
                if (!blob) { resolve(false); return; }
                fetch(uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'image/png' } })
                    .then(function(resp) { resolve(resp.ok); })
                    .catch(function(e) { resolve(false); });
            }, 'image/png');
        } catch (e) {
            resolve(false);
        }
    })
""")

actual suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean {
    return try {
        jsCaptureFrameAndUpload(uploadUrl).await()
    } catch (e: Throwable) {
        false
    }
}

// ------------------------------------------------------------------------------------ downloads

private fun jsTriggerDownload(url: String, fileName: String): Unit = js("""
    (function(url, fileName) {
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.target = '_blank';
        a.rel = 'noopener';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    })(url, fileName)
""")

actual fun triggerDownload(url: String, fileName: String) {
    jsTriggerDownload(url, fileName)
}

// ------------------------------------------------------------------ preview object-position

private fun jsSetPreviewObjectPosition(x: Double, y: Double): Unit = js("""
    (function(x, y) {
        var video = document.getElementById('compose-video-preview');
        if (video) { video.style.objectPosition = x + '% ' + y + '%'; }
    })(x, y)
""")

actual fun setPreviewObjectPosition(xPercent: Double, yPercent: Double) {
    jsSetPreviewObjectPosition(xPercent, yPercent)
}

// ---------------------------------------------------------------------- sequencer playback

// WebAudio note playback for the sequencer dialog: oscillator waveforms, or a decoded (and
// cached) sample buffer pitch-shifted via playbackRate — mirroring the server synthesizer.
private fun jsPlaySequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double,
    sampleUrl: String?
): Unit = js("""
    (function(waveform, frequencyHz, durationSeconds, volume, sampleUrl) {
        try {
            if (!window.__msSequencerCtx) {
                window.__msSequencerCtx = new (window.AudioContext || window.webkitAudioContext)();
                window.__msSequencerSamples = {};
            }
            var ctx = window.__msSequencerCtx;
            if (ctx.state === 'suspended') { ctx.resume(); }
            var gain = ctx.createGain();
            gain.gain.setValueAtTime(Math.max(0.001, volume), ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + durationSeconds);
            gain.connect(ctx.destination);
            if (waveform === 'sample' && sampleUrl) {
                var cache = window.__msSequencerSamples;
                var play = function(buffer) {
                    var src = ctx.createBufferSource();
                    src.buffer = buffer;
                    src.playbackRate.value = frequencyHz / 261.6256;
                    src.connect(gain);
                    src.start();
                    src.stop(ctx.currentTime + durationSeconds);
                };
                if (cache[sampleUrl]) { play(cache[sampleUrl]); return; }
                fetch(sampleUrl)
                    .then(function(r) { return r.arrayBuffer(); })
                    .then(function(data) { return ctx.decodeAudioData(data); })
                    .then(function(buffer) { cache[sampleUrl] = buffer; play(buffer); })
                    .catch(function(e) {});
                return;
            }
            var osc = ctx.createOscillator();
            osc.type = waveform === 'saw' ? 'sawtooth'
                : (waveform === 'square' || waveform === 'triangle') ? waveform : 'sine';
            osc.frequency.value = frequencyHz;
            osc.connect(gain);
            osc.start();
            osc.stop(ctx.currentTime + durationSeconds);
        } catch (e) {}
    })(waveform, frequencyHz, durationSeconds, volume, sampleUrl)
""")

actual fun playSequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double,
    sampleUrl: String?
) {
    jsPlaySequencerTone(waveform, frequencyHz, durationSeconds, volume, sampleUrl)
}

// ---------------------------------------------------------------------- realtime dictation

// Web Speech API dictation: a single recognition session accumulates final + interim results and
// reports the combined text after every event. Returns false when the API is unavailable.
// NOTE: the IIFE must NOT redeclare a parameter named like the Kotlin parameter — shadowing makes
// the compiler rename the Kotlin parameter (onResult -> onResult_0) without rewriting the js()
// code, which produced a ReferenceError at runtime and silently broke dictation.
private fun jsStartSpeechRecognition(onResult: (String) -> Unit): Boolean = js("""
    (function() {
        try {
            var Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (!Recognition) { return false; }
            if (window.__msSpeechRec) { try { window.__msSpeechRec.stop(); } catch (e) {} }
            var rec = new Recognition();
            rec.continuous = true;
            rec.interimResults = true;
            rec.onresult = function(event) {
                var text = '';
                for (var i = 0; i < event.results.length; i++) {
                    text += event.results[i][0].transcript;
                }
                try { onResult(text); } catch (e) {}
            };
            rec.onend = function() { if (window.__msSpeechRec === rec) { window.__msSpeechRec = null; } };
            window.__msSpeechRec = rec;
            rec.start();
            return true;
        } catch (e) {
            return false;
        }
    })()
""")

private fun jsStopSpeechRecognition(): Unit = js("""
    (function() {
        try {
            var rec = window.__msSpeechRec;
            if (!rec) return;
            window.__msSpeechRec = null;
            rec.onresult = function() {};
            rec.stop();
        } catch (e) {}
    })()
""")

// Server-backed dictation fallback for browsers without the Web Speech API (e.g. Firefox):
// captures the microphone with WebAudio, downsamples to 16 kHz 16-bit PCM and streams it to our
// /api/speech/ws relay, which forwards to Qwen realtime ASR and streams a running transcript back.
// Returns false only when the browser lacks getUserMedia / WebSocket / AudioContext (so the caller
// knows dictation is truly unavailable); mic-permission and socket outcomes resolve asynchronously.
// NOTE: no IIFE parameters may shadow the Kotlin parameters (wsUrl/onResult) — that shadowing makes
// the compiler rename the Kotlin ones without rewriting this js() code (a ReferenceError at runtime).
private fun jsStartServerDictation(wsUrl: String, onResult: (String) -> Unit): Boolean = js("""
    (function() {
        try {
            var AudioCtx = window.AudioContext || window.webkitAudioContext;
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.WebSocket || !AudioCtx) {
                return false;
            }
            if (window.__msDictation) { try { window.__msDictation.stop(); } catch (e) {} }
            var targetRate = 16000;
            var state = { stopped: false, ws: null, ctx: null, stream: null, source: null, processor: null };
            window.__msDictation = {
                stop: function() {
                    state.stopped = true;
                    try { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify({ action: 'stop' })); } catch (e) {}
                    try { if (state.processor) { state.processor.onaudioprocess = null; state.processor.disconnect(); } } catch (e) {}
                    try { if (state.source) state.source.disconnect(); } catch (e) {}
                    try { if (state.stream) state.stream.getTracks().forEach(function(t) { t.stop(); }); } catch (e) {}
                    try { if (state.ctx && state.ctx.state !== 'closed') state.ctx.close(); } catch (e) {}
                    var ws = state.ws;
                    if (ws) { setTimeout(function() { try { ws.close(); } catch (e) {} }, 300); }
                }
            };
            navigator.mediaDevices.getUserMedia({ audio: true }).then(function(stream) {
                if (state.stopped) { try { stream.getTracks().forEach(function(t) { t.stop(); }); } catch (e) {} return; }
                state.stream = stream;
                var ctx = new AudioCtx();
                state.ctx = ctx;
                if (ctx.state === 'suspended') { try { ctx.resume(); } catch (e) {} }
                var source = ctx.createMediaStreamSource(stream);
                state.source = source;
                var processor = ctx.createScriptProcessor(4096, 1, 1);
                state.processor = processor;
                var ws = new WebSocket(wsUrl);
                ws.binaryType = 'arraybuffer';
                state.ws = ws;
                ws.onopen = function() {
                    try { ws.send(JSON.stringify({ action: 'start', sampleRate: targetRate, format: 'pcm' })); } catch (e) {}
                };
                ws.onmessage = function(event) {
                    try {
                        var msg = JSON.parse(String(event.data));
                        if (msg && msg.type === 'transcript' && typeof msg.text === 'string') {
                            onResult(msg.text);
                        }
                    } catch (e) {}
                };
                processor.onaudioprocess = function(e) {
                    if (state.stopped || !ws || ws.readyState !== 1) return;
                    var input = e.inputBuffer.getChannelData(0);
                    var ratio = ctx.sampleRate / targetRate;
                    var outLength = Math.max(1, Math.floor(input.length / ratio));
                    var pcm = new Int16Array(outLength);
                    for (var i = 0; i < outLength; i++) {
                        var sample = input[Math.floor(i * ratio)] || 0;
                        sample = Math.max(-1, Math.min(1, sample));
                        pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
                    }
                    try { ws.send(pcm.buffer); } catch (e) {}
                };
                source.connect(processor);
                processor.connect(ctx.destination);
            }).catch(function(e) {
                try { if (window.__msDictation) window.__msDictation.stop(); } catch (e2) {}
                window.__msDictation = null;
            });
            return true;
        } catch (e) {
            return false;
        }
    })()
""")

private fun jsStopServerDictation(): Unit = js("""
    (function() {
        try {
            var d = window.__msDictation;
            window.__msDictation = null;
            if (d) d.stop();
        } catch (e) {}
    })()
""")

actual fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean {
    // Prefer the browser's native Web Speech API; fall back to the server relay (Firefox etc.).
    if (jsStartSpeechRecognition(onResult)) return true
    return jsStartServerDictation(NetworkService.speechWsUrl(), onResult)
}

actual fun stopRealtimeSpeechInput() {
    jsStopSpeechRecognition()
    jsStopServerDictation()
}
