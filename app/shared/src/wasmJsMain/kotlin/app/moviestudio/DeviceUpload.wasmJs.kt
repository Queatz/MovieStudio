package app.moviestudio

import kotlin.js.Promise
import kotlinx.coroutines.await

// Opens a hidden <input type=file>, uploads the selected file to OSS via a pre-signed URL and
// resolves with a small JSON payload ({ fileName, ossUrl, durationSeconds }), or null if the user
// cancels. The PUT must send exactly the server-signed Content-Type (it is part of the upload
// URL's signature) and the persisted read URL is the signed downloadUrl (objects are private).
@JsFun("""
(baseUrl, accept) => new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.style.display = 'none';
    let settled = false;
    const finish = (value) => {
        if (settled) return;
        settled = true;
        try { document.body.removeChild(input); } catch (e) {}
        resolve(value);
    };
    const readDuration = (file) => new Promise((res) => {
        try {
            const isAudio = file.type && file.type.indexOf('audio') === 0;
            const media = document.createElement(isAudio ? 'audio' : 'video');
            media.preload = 'metadata';
            const objectUrl = URL.createObjectURL(file);
            let done = false;
            const give = (d) => {
                if (done) return;
                done = true;
                try { URL.revokeObjectURL(objectUrl); } catch (e) {}
                res(isFinite(d) && d > 0 ? d : 5);
            };
            media.onloadedmetadata = () => give(media.duration);
            media.onerror = () => give(5);
            media.src = objectUrl;
            setTimeout(() => give(5), 3000);
        } catch (e) { res(5); }
    });
    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) { finish(null); return; }
        const objectKey = 'uploads/' + Date.now() + '-' + file.name;
        let downloadUrl = null;
        fetch(baseUrl + '/api/assets/upload-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ objectKey: objectKey })
        }).then((urlResp) => {
            if (!urlResp.ok) { throw new Error('upload-url request failed'); }
            return urlResp.json();
        }).then((urlJson) => {
            downloadUrl = urlJson.downloadUrl || urlJson.uploadUrl.split('?')[0];
            const contentType = urlJson.contentType || 'application/octet-stream';
            return fetch(urlJson.uploadUrl, {
                method: 'PUT',
                headers: { 'Content-Type': contentType },
                body: file
            });
        }).then((putResp) => {
            if (!putResp.ok) { throw new Error('upload PUT failed: ' + putResp.status); }
            return readDuration(file);
        }).then((duration) => {
            finish(JSON.stringify({ fileName: file.name, ossUrl: downloadUrl, durationSeconds: duration }));
        }).catch((e) => {
            finish(null);
        });
    };
    input.oncancel = () => finish(null);
    document.body.appendChild(input);
    input.click();
})
""")
private external fun jsPickAndUpload(baseUrl: String, accept: String): Promise<JsString?>

actual suspend fun pickAndUploadDeviceFile(type: AssetType): UploadedDeviceFile? {
    val baseUrl = getBaseUrl().removeSuffix("/")
    val accept = acceptFilterFor(type)
    val result = jsPickAndUpload(baseUrl, accept).await() ?: return null
    return parseUploadedDeviceFile(result.toString())
}

// ------------------------------------------------------------------------------ mic recording

// Starts a MediaRecorder over the microphone stream; captured chunks accumulate on a window
// state object until stop/cancel. Resolves 1 only when recording actually started.
@JsFun("""
() => new Promise((resolve) => {
    try {
        if (window.__msMicRecorder) { resolve(1); return; }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.MediaRecorder) {
            resolve(0);
            return;
        }
        navigator.mediaDevices.getUserMedia({ audio: true }).then((stream) => {
            const recorder = new MediaRecorder(stream);
            const chunks = [];
            recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };
            recorder.start(250);
            window.__msMicRecorder = { recorder: recorder, stream: stream, chunks: chunks, startedAt: Date.now() };
            resolve(1);
        }).catch((e) => resolve(0));
    } catch (e) { resolve(0); }
})
""")
private external fun jsStartMicRecording(): Promise<JsNumber>

// Stops the recorder, uploads the blob to OSS via a pre-signed URL and resolves with the same
// JSON payload as the file picker ({ fileName, ossUrl, durationSeconds }), or null on failure.
@JsFun("""
(baseUrl) => new Promise((resolve) => {
    try {
        const state = window.__msMicRecorder;
        if (!state) { resolve(null); return; }
        window.__msMicRecorder = null;
        const recorder = state.recorder;
        recorder.onstop = () => {
            try { state.stream.getTracks().forEach((t) => t.stop()); } catch (e) {}
            const mime = recorder.mimeType || 'audio/webm';
            const ext = mime.indexOf('ogg') >= 0 ? 'ogg' : (mime.indexOf('mp4') >= 0 ? 'm4a' : 'webm');
            const blob = new Blob(state.chunks, { type: mime });
            if (!blob.size) { resolve(null); return; }
            const durationSeconds = Math.max(0.5, (Date.now() - state.startedAt) / 1000);
            const fileName = 'voice-recording-' + Date.now() + '.' + ext;
            const objectKey = 'uploads/' + Date.now() + '-' + fileName;
            let downloadUrl = null;
            fetch(baseUrl + '/api/assets/upload-url', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ objectKey: objectKey })
            }).then((urlResp) => {
                if (!urlResp.ok) { throw new Error('upload-url request failed'); }
                return urlResp.json();
            }).then((urlJson) => {
                downloadUrl = urlJson.downloadUrl || urlJson.uploadUrl.split('?')[0];
                const contentType = urlJson.contentType || 'application/octet-stream';
                return fetch(urlJson.uploadUrl, {
                    method: 'PUT',
                    headers: { 'Content-Type': contentType },
                    body: blob
                });
            }).then((putResp) => {
                if (!putResp.ok) { throw new Error('upload PUT failed: ' + putResp.status); }
                resolve(JSON.stringify({ fileName: fileName, ossUrl: downloadUrl, durationSeconds: durationSeconds }));
            }).catch((e) => resolve(null));
        };
        recorder.stop();
    } catch (e) { resolve(null); }
})
""")
private external fun jsStopMicRecordingAndUpload(baseUrl: String): Promise<JsString?>

@JsFun("""
() => {
    try {
        const state = window.__msMicRecorder;
        if (!state) return;
        window.__msMicRecorder = null;
        state.recorder.onstop = () => {};
        state.recorder.stop();
        state.stream.getTracks().forEach((t) => t.stop());
    } catch (e) {}
}
""")
private external fun jsCancelMicRecording()

actual suspend fun startMicRecording(): Boolean = try {
    jsStartMicRecording().await<JsNumber>().toDouble() > 0.5
} catch (e: Throwable) {
    false
}

actual suspend fun stopMicRecordingAndUpload(): UploadedDeviceFile? {
    val baseUrl = getBaseUrl().removeSuffix("/")
    val result = try {
        jsStopMicRecordingAndUpload(baseUrl).await()
    } catch (e: Throwable) {
        null
    } ?: return null
    return parseUploadedDeviceFile(result.toString())
}

actual fun cancelMicRecording() {
    jsCancelMicRecording()
}
