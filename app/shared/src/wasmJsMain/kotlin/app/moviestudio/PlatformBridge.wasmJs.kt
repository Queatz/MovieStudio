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
