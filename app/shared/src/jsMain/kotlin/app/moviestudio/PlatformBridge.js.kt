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
private fun jsOpenWebSocket(url: String, onMessage: (String) -> Unit): dynamic = js("""
    (function(url, onMessage) {
        if (typeof WebSocket === 'undefined') { return null; }
        var handle = { ws: null, closed: false, timer: null };
        function scheduleReconnect() {
            if (handle.closed || handle.timer) { return; }
            handle.timer = setTimeout(function() {
                handle.timer = null;
                connect();
            }, 3000);
        }
        function connect() {
            if (handle.closed) { return; }
            try {
                var ws = new WebSocket(url);
                handle.ws = ws;
                ws.onmessage = function(event) {
                    try { onMessage(String(event.data)); } catch (e) {}
                };
                ws.onclose = function() { scheduleReconnect(); };
                ws.onerror = function() { try { ws.close(); } catch (e) {} };
            } catch (e) {
                scheduleReconnect();
            }
        }
        connect();
        return handle;
    })(url, onMessage)
""")

private fun jsCloseWebSocket(handle: dynamic): Unit = js("""
    (function(handle) {
        handle.closed = true;
        if (handle.timer) { clearTimeout(handle.timer); handle.timer = null; }
        try { if (handle.ws) { handle.ws.close(); } } catch (e) {}
    })(handle)
""")

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? {
    val handle = jsOpenWebSocket(wsUrl, onMessage) ?: return null
    return object : JobEventsConnection {
        override fun close() {
            try {
                jsCloseWebSocket(handle)
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

// ------------------------------------------------------------------------------ media preloading

// Maintains a session-lived pool of hidden media elements keyed by URL: <video>/<audio> preloaded
// with preload='auto' (muted, CORS so they decode/draw like the preview does) and decoded <img>
// loaders. Warming these keeps every timeline asset persistently buffered, so the shared preview
// <video>, the <audio> playback pool and Coil image loads all resolve from cache instantly and the
// movie preview no longer stalls when a clip enters the playhead. URLs absent from the latest
// reconcile are released so the pool tracks the current timeline.
private fun jsPreloadMedia(itemsJson: String): Unit = js("""
    (function(itemsJson) {
        var items = JSON.parse(itemsJson);
        if (!window.__msPreloadPool) { window.__msPreloadPool = {}; }
        var pool = window.__msPreloadPool;
        var wanted = {};
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var url = item.url;
            wanted[url] = true;
            if (pool[url]) { continue; }
            var el;
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
        for (var key in pool) {
            if (!wanted[key]) {
                var stale = pool[key];
                try { stale.src = ''; if (stale.load) { stale.load(); } } catch (e) {}
                delete pool[key];
            }
        }
    })(itemsJson)
""")

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

private fun jsCaptureFrameAndUpload(uploadUrl: String): Promise<Boolean> = js("""
    new Promise(function(resolve) {
        try {
            var upload = function(blob) {
                if (!blob) { resolve(false); return; }
                fetch(uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'image/png' } })
                    .then(function(resp) { resolve(resp.ok); })
                    .catch(function(e) { resolve(false); });
            };
            // WebGL preview method: the compositor no longer preserves its drawing buffer, so build
            // the PNG from the last frame we read back (S.readBuf, bottom-to-top RGBA). Paint it into
            // a 2D canvas, then draw that flipped vertically so the saved image is right-side up.
            var S = window.__msWebGLPreview;
            if (S && S.active && S.readBuf && S.canvas && S.canvas.width > 0 &&
                S.readBuf.length === S.canvas.width * S.canvas.height * 4) {
                var w = S.canvas.width, h = S.canvas.height;
                var src = document.createElement('canvas');
                src.width = w; src.height = h;
                var sctx = src.getContext('2d');
                var imgData = sctx.createImageData(w, h);
                imgData.data.set(S.readBuf);
                sctx.putImageData(imgData, 0, 0);
                var out = document.createElement('canvas');
                out.width = w; out.height = h;
                var octx = out.getContext('2d');
                octx.translate(0, h);
                octx.scale(1, -1);
                octx.drawImage(src, 0, 0);
                out.toBlob(upload, 'image/png');
                return;
            }
            var video = document.getElementById('compose-video-preview');
            if (!video || !video.videoWidth) { resolve(false); return; }
            var canvas = document.createElement('canvas');
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            var ctx = canvas.getContext('2d');
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
            canvas.toBlob(upload, 'image/png');
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
        function saveBlob(href, revoke) {
            var a = document.createElement('a');
            a.href = href;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            if (revoke) { setTimeout(function() { URL.revokeObjectURL(href); }, 1000); }
        }
        // Browsers ignore the download attribute (and thus the movie-name file name) when the
        // href points at a cross-origin resource, e.g. our cloud-storage renders. Fetch the file
        // into a same-origin blob URL first so the chosen file name is actually honored, and fall
        // back to a plain link if the fetch is blocked (e.g. missing CORS headers).
        fetch(url)
            .then(function(response) {
                if (!response.ok) { throw new Error('download failed'); }
                return response.blob();
            })
            .then(function(blob) { saveBlob(URL.createObjectURL(blob), true); })
            .catch(function() {
                var a = document.createElement('a');
                a.href = url;
                a.download = fileName;
                a.target = '_blank';
                a.rel = 'noopener';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
            });
    })(url, fileName)
""")

actual fun triggerDownload(url: String, fileName: String) {
    jsTriggerDownload(url, fileName)
}

private fun jsDownloadTextFile(content: String, fileName: String): Unit = js("""
    (function(content, fileName) {
        var blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
    })(content, fileName)
""")

actual fun downloadTextFile(content: String, fileName: String) {
    jsDownloadTextFile(content, fileName)
}

private fun jsPrintDocument(html: String): Unit = js("""
    (function(html) {
        var w = window.open('', '_blank');
        if (!w) { return; }
        w.document.open();
        w.document.write(html);
        w.document.close();
    })(html)
""")

actual fun printDocument(html: String) {
    jsPrintDocument(html)
}

// ------------------------------------------------------------------------------------ fullscreen

private fun jsRequestVideoFullscreen(): Unit = js("""
    (function() {
        var video = document.getElementById('compose-video-preview');
        if (!video) return;
        // Native controls make sense in fullscreen (Compose transport is hidden there); pointer
        // events must be enabled so the user can actually use them.
        video.controls = true;
        video.style.pointerEvents = 'auto';
        var req = video.requestFullscreen || video.webkitRequestFullscreen || video.webkitEnterFullscreen;
        if (req) { try { req.call(video); } catch (e) {} }
        // Restore the decorative-overlay behavior once fullscreen is dismissed.
        var onChange = function() {
            if (!document.fullscreenElement && !document.webkitFullscreenElement) {
                video.controls = false;
                video.style.pointerEvents = 'none';
                document.removeEventListener('fullscreenchange', onChange);
                document.removeEventListener('webkitfullscreenchange', onChange);
            }
        };
        document.addEventListener('fullscreenchange', onChange);
        document.addEventListener('webkitfullscreenchange', onChange);
    })()
""")

actual fun requestVideoFullscreen() {
    jsRequestVideoFullscreen()
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

// -------------------------------------------------------------------- preview overlay visibility

// Toggles the shared <video> overlay's visibility WITHOUT touching `display` (which already
// tracks whether a video clip is under the playhead) — so a StudioDialog can hide it temporarily
// and the overlay is restored to exactly the state it was in once the dialog closes.
private fun jsSetPreviewOverlayVisible(visible: Boolean): Unit = js("""
    (function(visible) {
        var video = document.getElementById('compose-video-preview');
        if (video) { video.style.visibility = visible ? 'visible' : 'hidden'; }
    })(visible)
""")

actual fun setPreviewOverlayVisible(visible: Boolean) {
    jsSetPreviewOverlayVisible(visible)
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

// ---------------------------------------------------------------------- waveform decoding

// Fetches the audio file, decodes it with WebAudio and reduces channel 0 to [buckets] peak
// amplitudes, returned as a comma-separated string. Decoded peaks are cached per url+buckets so
// reopening the editor is instant. Resolves to an empty string on any failure.
private fun jsLoadWaveform(url: String, buckets: Int): Promise<String> = js("""
    new Promise(function(resolve) {
        try {
            if (!window.__msWaveCache) { window.__msWaveCache = {}; }
            var cacheKey = buckets + '@' + url;
            if (window.__msWaveCache[cacheKey]) { resolve(window.__msWaveCache[cacheKey]); return; }
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (!Ctx) { resolve(''); return; }
            if (!window.__msWaveCtx) { window.__msWaveCtx = new Ctx(); }
            var ctx = window.__msWaveCtx;
            fetch(url)
                .then(function(r) { return r.arrayBuffer(); })
                .then(function(data) { return ctx.decodeAudioData(data); })
                .then(function(buffer) {
                    var channel = buffer.getChannelData(0);
                    var n = channel.length;
                    var per = Math.max(1, Math.floor(n / buckets));
                    var peaks = new Array(buckets);
                    var globalPeak = 0.00001;
                    for (var b = 0; b < buckets; b++) {
                        var start = b * per;
                        var end = Math.min(n, start + per);
                        var peak = 0;
                        for (var i = start; i < end; i++) {
                            var v = channel[i]; if (v < 0) v = -v;
                            if (v > peak) peak = v;
                        }
                        peaks[b] = peak;
                        if (peak > globalPeak) globalPeak = peak;
                    }
                    for (var j = 0; j < buckets; j++) { peaks[j] = peaks[j] / globalPeak; }
                    var csv = peaks.join(',');
                    window.__msWaveCache[cacheKey] = csv;
                    resolve(csv);
                })
                .catch(function(e) { resolve(''); });
        } catch (e) { resolve(''); }
    })
""")

actual suspend fun loadAudioWaveform(url: String, buckets: Int): FloatArray? {
    return try {
        val csv = jsLoadWaveform(url, buckets).await()
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
private fun jsInstallMarkdownShortcutGuard(isActive: () -> Boolean): dynamic = js("""
    (function(isActive) {
        var handler = function(e) {
            try {
                if ((e.ctrlKey || e.metaKey) &&
                    (e.code === 'KeyB' || e.code === 'KeyI' || e.code === 'KeyU') && isActive()) {
                    e.preventDefault();
                }
            } catch (err) {}
        };
        document.addEventListener('keydown', handler, true);
        return handler;
    })(isActive)
""")

private fun jsRemoveKeydownListener(handler: dynamic): Unit = js("""
    (function(handler) { document.removeEventListener('keydown', handler, true); })(handler)
""")

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

private fun jsInstallImeTracker(): Unit = js("""
    (function() {
        if (window.__msImeTrackerInstalled) return;
        window.__msImeTrackerInstalled = true;
        window.__msImeComposing = false;
        document.addEventListener('compositionstart', function() { window.__msImeComposing = true; }, true);
        document.addEventListener('compositionend', function() { window.__msImeComposing = false; }, true);
    })()
""")

private fun jsIsImeComposing(): Boolean = js("(!!window.__msImeComposing)")

actual fun androidx.compose.ui.input.key.KeyEvent.isFromIme(): Boolean {
    if (!imeTrackerInstalled) {
        jsInstallImeTracker()
        imeTrackerInstalled = true
    }
    return jsIsImeComposing()
}
