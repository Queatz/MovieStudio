package app.moviestudio

import kotlinx.coroutines.await
import kotlin.js.Promise

// ------------------------------------------------------------------------- job events WebSocket

@JsFun("""
(url, onMessage) => {
    try {
        const ws = new WebSocket(url);
        ws.onmessage = (event) => {
            try { onMessage(String(event.data)); } catch (e) {}
        };
        return ws;
    } catch (e) {
        return null;
    }
}
""")
private external fun jsOpenWebSocket(url: String, onMessage: (String) -> Unit): JsAny?

@JsFun("""
(ws) => {
    try { ws.close(); } catch (e) {}
}
""")
private external fun jsCloseWebSocket(ws: JsAny)

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? {
    val ws = jsOpenWebSocket(wsUrl, onMessage) ?: return null
    return object : JobEventsConnection {
        override fun close() {
            jsCloseWebSocket(ws)
        }
    }
}

// ------------------------------------------------------------------------------- audio playback

@JsFun("""
(itemsJson, playing) => {
    const items = JSON.parse(itemsJson);
    if (!window.__msAudioPool) { window.__msAudioPool = {}; }
    const pool = window.__msAudioPool;
    const wanted = {};
    for (const item of items) {
        wanted[item.key] = true;
        let audio = pool[item.key];
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
        const drift = Math.abs(audio.currentTime - item.positionSeconds);
        if (drift > 0.35) {
            try { audio.currentTime = item.positionSeconds; } catch (e) {}
        }
        if (playing) {
            if (audio.paused) { audio.play().catch((e) => {}); }
        } else {
            if (!audio.paused) { audio.pause(); }
        }
    }
    for (const key in pool) {
        if (!wanted[key]) {
            try { pool[key].pause(); } catch (e) {}
            delete pool[key];
        }
    }
}
""")
private external fun jsUpdateAudioPool(itemsJson: String, playing: Boolean)

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

@JsFun("""
(uploadUrl) => new Promise((resolve) => {
    try {
        const video = document.getElementById('compose-video-preview');
        if (!video || !video.videoWidth) { resolve(0); return; }
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((blob) => {
            if (!blob) { resolve(0); return; }
            fetch(uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'image/png' } })
                .then((resp) => resolve(resp.ok ? 1 : 0))
                .catch((e) => resolve(0));
        }, 'image/png');
    } catch (e) {
        resolve(0);
    }
})
""")
private external fun jsCaptureFrameAndUpload(uploadUrl: String): Promise<JsNumber>

actual suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean {
    return try {
        jsCaptureFrameAndUpload(uploadUrl).await<JsNumber>().toDouble() > 0.5
    } catch (e: Throwable) {
        false
    }
}

// ------------------------------------------------------------------------------------ downloads

@JsFun("""
(url, fileName) => {
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.target = '_blank';
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}
""")
private external fun jsTriggerDownload(url: String, fileName: String)

actual fun triggerDownload(url: String, fileName: String) {
    jsTriggerDownload(url, fileName)
}

// ------------------------------------------------------------------ preview object-position

@JsFun("""
(x, y) => {
    const video = document.getElementById('compose-video-preview');
    if (video) { video.style.objectPosition = x + '% ' + y + '%'; }
}
""")
private external fun jsSetPreviewObjectPosition(x: Double, y: Double)

actual fun setPreviewObjectPosition(xPercent: Double, yPercent: Double) {
    jsSetPreviewObjectPosition(xPercent, yPercent)
}

// ---------------------------------------------------------------------- sequencer playback

// WebAudio note playback for the sequencer dialog: oscillator waveforms, or a decoded (and
// cached) sample buffer pitch-shifted via playbackRate — mirroring the server synthesizer.
@JsFun("""
(waveform, frequencyHz, durationSeconds, volume, sampleUrl) => {
    try {
        if (!window.__msSequencerCtx) {
            window.__msSequencerCtx = new (window.AudioContext || window.webkitAudioContext)();
            window.__msSequencerSamples = {};
        }
        const ctx = window.__msSequencerCtx;
        if (ctx.state === 'suspended') { ctx.resume(); }
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(Math.max(0.001, volume), ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + durationSeconds);
        gain.connect(ctx.destination);
        if (waveform === 'sample' && sampleUrl) {
            const cache = window.__msSequencerSamples;
            const play = (buffer) => {
                const src = ctx.createBufferSource();
                src.buffer = buffer;
                src.playbackRate.value = frequencyHz / 261.6256;
                src.connect(gain);
                src.start();
                src.stop(ctx.currentTime + durationSeconds);
            };
            if (cache[sampleUrl]) { play(cache[sampleUrl]); return; }
            fetch(sampleUrl)
                .then((r) => r.arrayBuffer())
                .then((data) => ctx.decodeAudioData(data))
                .then((buffer) => { cache[sampleUrl] = buffer; play(buffer); })
                .catch((e) => {});
            return;
        }
        const osc = ctx.createOscillator();
        osc.type = waveform === 'saw' ? 'sawtooth'
            : (waveform === 'square' || waveform === 'triangle') ? waveform : 'sine';
        osc.frequency.value = frequencyHz;
        osc.connect(gain);
        osc.start();
        osc.stop(ctx.currentTime + durationSeconds);
    } catch (e) {}
}
""")
private external fun jsPlaySequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double,
    sampleUrl: String?
)

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
// reports the combined text after every event. Resolves 1 only when recognition actually started.
@JsFun("""
(onResult) => {
    try {
        const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!Recognition) { return 0; }
        if (window.__msSpeechRec) { try { window.__msSpeechRec.stop(); } catch (e) {} }
        const rec = new Recognition();
        rec.continuous = true;
        rec.interimResults = true;
        rec.onresult = (event) => {
            let text = '';
            for (let i = 0; i < event.results.length; i++) {
                text += event.results[i][0].transcript;
            }
            try { onResult(text); } catch (e) {}
        };
        rec.onend = () => { if (window.__msSpeechRec === rec) { window.__msSpeechRec = null; } };
        window.__msSpeechRec = rec;
        rec.start();
        return 1;
    } catch (e) {
        return 0;
    }
}
""")
private external fun jsStartSpeechRecognition(onResult: (String) -> Unit): Int

@JsFun("""
() => {
    try {
        const rec = window.__msSpeechRec;
        if (!rec) return;
        window.__msSpeechRec = null;
        rec.onresult = () => {};
        rec.stop();
    } catch (e) {}
}
""")
private external fun jsStopSpeechRecognition()

actual fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean =
    jsStartSpeechRecognition(onResult) == 1

actual fun stopRealtimeSpeechInput() {
    jsStopSpeechRecognition()
}
