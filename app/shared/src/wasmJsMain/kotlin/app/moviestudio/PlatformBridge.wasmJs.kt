package app.moviestudio

import kotlinx.coroutines.await
import kotlin.js.Promise

// ------------------------------------------------------------------------- job events WebSocket

// Opens a self-healing job-events socket: whenever it closes/errors (server restart, network
// blip, sleep/wake) it automatically reconnects after a short delay so there is ALWAYS a live
// channel. On every (re)connect the server replays the recent terminal events, so completions
// that happened while the socket was down are still delivered. Returns a handle whose `closed`
// flag stops reconnection when the caller explicitly closes it. Returns null when the browser
// has no WebSocket support (callers then fall back to polling).
@JsFun("""
(url, onMessage) => {
    if (typeof WebSocket === 'undefined') { return null; }
    const handle = { ws: null, closed: false, timer: null };
    const scheduleReconnect = () => {
        if (handle.closed || handle.timer) { return; }
        handle.timer = setTimeout(() => { handle.timer = null; connect(); }, 3000);
    };
    const connect = () => {
        if (handle.closed) { return; }
        try {
            const ws = new WebSocket(url);
            handle.ws = ws;
            ws.onmessage = (event) => {
                try { onMessage(String(event.data)); } catch (e) {}
            };
            ws.onclose = () => { scheduleReconnect(); };
            ws.onerror = () => { try { ws.close(); } catch (e) {} };
        } catch (e) {
            scheduleReconnect();
        }
    };
    connect();
    return handle;
}
""")
private external fun jsOpenWebSocket(url: String, onMessage: (String) -> Unit): JsAny?

@JsFun("""
(handle) => {
    handle.closed = true;
    if (handle.timer) { clearTimeout(handle.timer); handle.timer = null; }
    try { if (handle.ws) { handle.ws.close(); } } catch (e) {}
}
""")
private external fun jsCloseWebSocket(handle: JsAny)

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? {
    val handle = jsOpenWebSocket(wsUrl, onMessage) ?: return null
    return object : JobEventsConnection {
        override fun close() {
            jsCloseWebSocket(handle)
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

// ------------------------------------------------------------------------------ media preloading

// Maintains a session-lived pool of hidden media elements keyed by URL: <video>/<audio> preloaded
// with preload='auto' (muted, CORS so they decode/draw like the preview does) and decoded <img>
// loaders. Warming these keeps every timeline asset persistently buffered, so the shared preview
// <video>, the <audio> playback pool and Coil image loads all resolve from cache instantly and the
// movie preview no longer stalls when a clip enters the playhead. URLs absent from the latest
// reconcile are released so the pool tracks the current timeline.
@JsFun("""
(itemsJson) => {
    const items = JSON.parse(itemsJson);
    if (!window.__msPreloadPool) { window.__msPreloadPool = {}; }
    const pool = window.__msPreloadPool;
    const wanted = {};
    for (const item of items) {
        const url = item.url;
        wanted[url] = true;
        if (pool[url]) { continue; }
        let el;
        if (item.kind === 'IMAGE') {
            el = new Image();
            el.crossOrigin = 'anonymous';
            el.src = url;
        } else {
            el = document.createElement(item.kind === 'VIDEO' ? 'video' : 'audio');
            el.crossOrigin = 'anonymous';
            el.preload = 'auto';
            el.muted = true;
            if (item.kind === 'VIDEO') { el.setAttribute('playsinline', 'true'); }
            el.src = url;
            try { el.load(); } catch (e) {}
        }
        pool[url] = el;
    }
    for (const key in pool) {
        if (!wanted[key]) {
            const stale = pool[key];
            try { stale.src = ''; if (stale.load) { stale.load(); } } catch (e) {}
            delete pool[key];
        }
    }
}
""")
private external fun jsPreloadMedia(itemsJson: String)

actual fun preloadTimelineMedia(items: List<PreloadMediaItem>) {
    val json = buildString {
        append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append("{\"url\":\"").append(item.url).append("\",")
            append("\"kind\":\"").append(item.kind.name).append("\"}")
        }
        append(']')
    }
    jsPreloadMedia(json)
}

// -------------------------------------------------------------------------------- frame capture

@JsFun("""
(uploadUrl) => new Promise((resolve) => {
    try {
        const upload = (blob) => {
            if (!blob) { resolve(0); return; }
            fetch(uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'image/png' } })
                .then((resp) => resolve(resp.ok ? 1 : 0))
                .catch((e) => resolve(0));
        };
        // WebGL preview method: the compositor no longer preserves its drawing buffer, so build the
        // PNG from the last frame we read back (S.readBuf, bottom-to-top RGBA). Paint it into a 2D
        // canvas, then draw that flipped vertically so the saved image is right-side up.
        const S = window.__msWebGLPreview;
        if (S && S.active && S.readBuf && S.canvas && S.canvas.width > 0 &&
            S.readBuf.length === S.canvas.width * S.canvas.height * 4) {
            const w = S.canvas.width, h = S.canvas.height;
            const src = document.createElement('canvas');
            src.width = w; src.height = h;
            const sctx = src.getContext('2d');
            const imgData = sctx.createImageData(w, h);
            imgData.data.set(S.readBuf);
            sctx.putImageData(imgData, 0, 0);
            const out = document.createElement('canvas');
            out.width = w; out.height = h;
            const octx = out.getContext('2d');
            octx.translate(0, h);
            octx.scale(1, -1);
            octx.drawImage(src, 0, 0);
            out.toBlob(upload, 'image/png');
            return;
        }
        const video = document.getElementById('compose-video-preview');
        if (!video || !video.videoWidth) { resolve(0); return; }
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob(upload, 'image/png');
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
    const saveBlob = (href, revoke) => {
        const a = document.createElement('a');
        a.href = href;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        if (revoke) { setTimeout(() => URL.revokeObjectURL(href), 1000); }
    };
    // Browsers ignore the download attribute (and thus the movie-name file name) when the href
    // points at a cross-origin resource, e.g. our cloud-storage renders. Fetch the file into a
    // same-origin blob URL first so the chosen file name is actually honored, and fall back to a
    // plain link if the fetch is blocked (e.g. missing CORS headers).
    fetch(url)
        .then((response) => {
            if (!response.ok) { throw new Error('download failed'); }
            return response.blob();
        })
        .then((blob) => saveBlob(URL.createObjectURL(blob), true))
        .catch(() => {
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            a.target = '_blank';
            a.rel = 'noopener';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        });
}
""")
private external fun jsTriggerDownload(url: String, fileName: String)

actual fun triggerDownload(url: String, fileName: String) {
    jsTriggerDownload(url, fileName)
}

@JsFun("""
(content, fileName) => {
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}
""")
private external fun jsDownloadTextFile(content: String, fileName: String)

actual fun downloadTextFile(content: String, fileName: String) {
    jsDownloadTextFile(content, fileName)
}

@JsFun("""
(html) => {
    const w = window.open('', '_blank');
    if (!w) { return; }
    w.document.open();
    w.document.write(html);
    w.document.close();
}
""")
private external fun jsPrintDocument(html: String)

actual fun printDocument(html: String) {
    jsPrintDocument(html)
}

// ------------------------------------------------------------------------------------ fullscreen

@JsFun("""
() => {
    const video = document.getElementById('compose-video-preview');
    if (!video) return;
    // Native controls make sense in fullscreen (Compose transport is hidden there); pointer
    // events must be enabled so the user can actually use them.
    video.controls = true;
    video.style.pointerEvents = 'auto';
    const req = video.requestFullscreen || video.webkitRequestFullscreen || video.webkitEnterFullscreen;
    if (req) { try { req.call(video); } catch (e) {} }
    // Restore the decorative-overlay behavior once fullscreen is dismissed.
    const onChange = () => {
        if (!document.fullscreenElement && !document.webkitFullscreenElement) {
            video.controls = false;
            video.style.pointerEvents = 'none';
            document.removeEventListener('fullscreenchange', onChange);
            document.removeEventListener('webkitfullscreenchange', onChange);
        }
    };
    document.addEventListener('fullscreenchange', onChange);
    document.addEventListener('webkitfullscreenchange', onChange);
}
""")
private external fun jsRequestVideoFullscreen()

actual fun requestVideoFullscreen() {
    jsRequestVideoFullscreen()
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

// -------------------------------------------------------------------- preview overlay visibility

// Toggles the shared <video> overlay's visibility WITHOUT touching `display` (which already
// tracks whether a video clip is under the playhead) — so a StudioDialog can hide it temporarily
// and the overlay is restored to exactly the state it was in once the dialog closes.
@JsFun("""
(visible) => {
    const video = document.getElementById('compose-video-preview');
    if (video) { video.style.visibility = visible ? 'visible' : 'hidden'; }
}
""")
private external fun jsSetPreviewOverlayVisible(visible: Boolean)

actual fun setPreviewOverlayVisible(visible: Boolean) {
    jsSetPreviewOverlayVisible(visible)
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

// Server-backed dictation fallback for browsers without the Web Speech API (e.g. Firefox):
// captures the microphone with WebAudio, downsamples to 16 kHz 16-bit PCM and streams it to our
// /api/speech/ws relay, which forwards to Qwen realtime ASR and streams a running transcript back.
// Resolves 1 only when the browser has getUserMedia / WebSocket / AudioContext; mic-permission and
// socket outcomes resolve asynchronously.
@JsFun("""
(wsUrl, onResult) => {
    try {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.WebSocket || !AudioCtx) {
            return 0;
        }
        if (window.__msDictation) { try { window.__msDictation.stop(); } catch (e) {} }
        const targetRate = 16000;
        const state = { stopped: false, ws: null, ctx: null, stream: null, source: null, processor: null };
        window.__msDictation = {
            stop: () => {
                state.stopped = true;
                try { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify({ action: 'stop' })); } catch (e) {}
                try { if (state.processor) { state.processor.onaudioprocess = null; state.processor.disconnect(); } } catch (e) {}
                try { if (state.source) state.source.disconnect(); } catch (e) {}
                try { if (state.stream) state.stream.getTracks().forEach((t) => t.stop()); } catch (e) {}
                try { if (state.ctx && state.ctx.state !== 'closed') state.ctx.close(); } catch (e) {}
                const ws = state.ws;
                if (ws) { setTimeout(() => { try { ws.close(); } catch (e) {} }, 300); }
            }
        };
        navigator.mediaDevices.getUserMedia({ audio: true }).then((stream) => {
            if (state.stopped) { try { stream.getTracks().forEach((t) => t.stop()); } catch (e) {} return; }
            state.stream = stream;
            const ctx = new AudioCtx();
            state.ctx = ctx;
            if (ctx.state === 'suspended') { try { ctx.resume(); } catch (e) {} }
            const source = ctx.createMediaStreamSource(stream);
            state.source = source;
            const processor = ctx.createScriptProcessor(4096, 1, 1);
            state.processor = processor;
            const ws = new WebSocket(wsUrl);
            ws.binaryType = 'arraybuffer';
            state.ws = ws;
            ws.onopen = () => {
                try { ws.send(JSON.stringify({ action: 'start', sampleRate: targetRate, format: 'pcm' })); } catch (e) {}
            };
            ws.onmessage = (event) => {
                try {
                    const msg = JSON.parse(String(event.data));
                    if (msg && msg.type === 'transcript' && typeof msg.text === 'string') {
                        onResult(msg.text);
                    }
                } catch (e) {}
            };
            processor.onaudioprocess = (e) => {
                if (state.stopped || !ws || ws.readyState !== 1) return;
                const input = e.inputBuffer.getChannelData(0);
                const ratio = ctx.sampleRate / targetRate;
                const outLength = Math.max(1, Math.floor(input.length / ratio));
                const pcm = new Int16Array(outLength);
                for (let i = 0; i < outLength; i++) {
                    let sample = input[Math.floor(i * ratio)] || 0;
                    sample = Math.max(-1, Math.min(1, sample));
                    pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
                }
                try { ws.send(pcm.buffer); } catch (e) {}
            };
            source.connect(processor);
            processor.connect(ctx.destination);
        }).catch((e) => {
            try { if (window.__msDictation) window.__msDictation.stop(); } catch (e2) {}
            window.__msDictation = null;
        });
        return 1;
    } catch (e) {
        return 0;
    }
}
""")
private external fun jsStartServerDictation(wsUrl: String, onResult: (String) -> Unit): Int

@JsFun("""
() => {
    try {
        const d = window.__msDictation;
        window.__msDictation = null;
        if (d) d.stop();
    } catch (e) {}
}
""")
private external fun jsStopServerDictation()

actual fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean {
    // Prefer the browser's native Web Speech API; fall back to the server relay (Firefox etc.).
    if (jsStartSpeechRecognition(onResult) == 1) return true
    return jsStartServerDictation(NetworkService.speechWsUrl(), onResult) == 1
}

actual fun stopRealtimeSpeechInput() {
    jsStopSpeechRecognition()
    jsStopServerDictation()
}

// ---------------------------------------------------------------------- waveform decoding

// Fetches the audio file, decodes it with WebAudio and reduces channel 0 to [buckets] peak
// amplitudes, returned as a comma-separated string. Decoded peaks are cached per url+buckets so
// reopening the editor is instant. Resolves to an empty string on any failure.
@JsFun("""
(url, buckets) => new Promise((resolve) => {
    try {
        if (!window.__msWaveCache) { window.__msWaveCache = {}; }
        const cacheKey = buckets + '@' + url;
        if (window.__msWaveCache[cacheKey]) { resolve(window.__msWaveCache[cacheKey]); return; }
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) { resolve(''); return; }
        if (!window.__msWaveCtx) { window.__msWaveCtx = new Ctx(); }
        const ctx = window.__msWaveCtx;
        fetch(url)
            .then((r) => r.arrayBuffer())
            .then((data) => ctx.decodeAudioData(data))
            .then((buffer) => {
                const channel = buffer.getChannelData(0);
                const n = channel.length;
                const per = Math.max(1, Math.floor(n / buckets));
                const peaks = new Array(buckets);
                let globalPeak = 0.00001;
                for (let b = 0; b < buckets; b++) {
                    const start = b * per;
                    const end = Math.min(n, start + per);
                    let peak = 0;
                    for (let i = start; i < end; i++) {
                        let v = channel[i]; if (v < 0) v = -v;
                        if (v > peak) peak = v;
                    }
                    peaks[b] = peak;
                    if (peak > globalPeak) globalPeak = peak;
                }
                for (let j = 0; j < buckets; j++) { peaks[j] = peaks[j] / globalPeak; }
                const csv = peaks.join(',');
                window.__msWaveCache[cacheKey] = csv;
                resolve(csv);
            })
            .catch((e) => resolve(''));
    } catch (e) { resolve(''); }
})
""")
private external fun jsLoadWaveform(url: String, buckets: Int): Promise<JsString>

actual suspend fun loadAudioWaveform(url: String, buckets: Int): FloatArray? {
    return try {
        val csv = jsLoadWaveform(url, buckets).await<JsString>().toString()
        if (csv.isBlank()) return null
        val peaks = csv.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
        peaks.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }
}

// --------------------------------------------------------------------- key default suppression

// A capture-phase document listener stops the browser's own bold/italic/"view source" action for
// Ctrl/Cmd+B/I/U while `isActive()` (typically "this field currently has focus") is true. Compose's
// onPreviewKeyEvent returning true only short-circuits other Compose handlers — it never reaches
// the DOM event, so the browser default still runs unless we call preventDefault() here ourselves.
@JsFun("""
(isActive) => {
    const handler = (e) => {
        try {
            if ((e.ctrlKey || e.metaKey) &&
                (e.code === 'KeyB' || e.code === 'KeyI' || e.code === 'KeyU') && isActive()) {
                e.preventDefault();
            }
        } catch (err) {}
    };
    document.addEventListener('keydown', handler, true);
    return handler;
}
""")
private external fun jsInstallMarkdownShortcutGuard(isActive: () -> Boolean): JsAny

@JsFun("""
(handler) => { document.removeEventListener('keydown', handler, true); }
""")
private external fun jsRemoveKeydownListener(handler: JsAny)

actual fun installMarkdownShortcutGuard(isActive: () -> Boolean): KeyGuardHandle {
    val handler = jsInstallMarkdownShortcutGuard(isActive)
    return object : KeyGuardHandle {
        override fun dispose() {
            jsRemoveKeydownListener(handler)
        }
    }
}

// Compose surfaces the web key event as a skiko wrapper, not the raw DOM `KeyboardEvent`, so its
// `isComposing` flag isn't reachable from Kotlin. Instead track IME composition globally: a
// capture-phase `compositionstart`/`compositionend` pair on the document flips a flag that stays
// true for the whole composition (e.g. a Vietnamese Telex sequence). Installed once, lazily.
private var imeTrackerInstalled = false

@JsFun("""
() => {
    if (window.__msImeTrackerInstalled) return;
    window.__msImeTrackerInstalled = true;
    window.__msImeComposing = false;
    document.addEventListener('compositionstart', () => { window.__msImeComposing = true; }, true);
    document.addEventListener('compositionend', () => { window.__msImeComposing = false; }, true);
}
""")
private external fun jsInstallImeTracker()

@JsFun("() => (!!window.__msImeComposing)")
private external fun jsIsImeComposing(): Boolean

actual fun androidx.compose.ui.input.key.KeyEvent.isFromIme(): Boolean {
    if (!imeTrackerInstalled) {
        jsInstallImeTracker()
        imeTrackerInstalled = true
    }
    return jsIsImeComposing()
}
