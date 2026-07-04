package app.moviestudio

import kotlinx.coroutines.await
import kotlin.js.Promise

// Opens a hidden <input type=file>, uploads the selected file to OSS via a pre-signed URL and
// resolves with a small JSON payload ({ fileName, ossUrl, durationSeconds }), or null if the user
// cancels. The public URL is the pre-signed URL with its query string (the signature) stripped.
@Suppress("UNUSED_PARAMETER")
private fun jsPickAndUpload(baseUrl: String, accept: String): Promise<String?> = js("""
    new Promise(function(resolve) {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = accept;
        input.style.display = 'none';
        var settled = false;
        function finish(value) {
            if (settled) return;
            settled = true;
            try { document.body.removeChild(input); } catch (e) {}
            resolve(value);
        }
        function readDuration(file) {
            return new Promise(function(res) {
                try {
                    var isAudio = file.type && file.type.indexOf('audio') === 0;
                    var media = document.createElement(isAudio ? 'audio' : 'video');
                    media.preload = 'metadata';
                    var objectUrl = URL.createObjectURL(file);
                    var done = false;
                    function give(d) {
                        if (done) return;
                        done = true;
                        try { URL.revokeObjectURL(objectUrl); } catch (e) {}
                        res(isFinite(d) && d > 0 ? d : 5);
                    }
                    media.onloadedmetadata = function() { give(media.duration); };
                    media.onerror = function() { give(5); };
                    media.src = objectUrl;
                    setTimeout(function() { give(5); }, 3000);
                } catch (e) { res(5); }
            });
        }
        input.onchange = function() {
            var file = input.files && input.files[0];
            if (!file) { finish(null); return; }
            var objectKey = 'uploads/' + Date.now() + '-' + file.name;
            var uploadUrl = null;
            fetch(baseUrl + '/api/assets/upload-url', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ objectKey: objectKey })
            }).then(function(urlResp) {
                if (!urlResp.ok) { throw new Error('upload-url request failed'); }
                return urlResp.json();
            }).then(function(urlJson) {
                uploadUrl = urlJson.uploadUrl;
                return fetch(uploadUrl, { method: 'PUT', body: file });
            }).then(function() {
                return readDuration(file);
            }).then(function(duration) {
                var publicUrl = uploadUrl.split('?')[0];
                finish(JSON.stringify({ fileName: file.name, ossUrl: publicUrl, durationSeconds: duration }));
            }).catch(function(e) {
                finish(null);
            });
        };
        input.oncancel = function() { finish(null); };
        document.body.appendChild(input);
        input.click();
    })
""")

actual suspend fun pickAndUploadDeviceFile(type: AssetType): UploadedDeviceFile? {
    val baseUrl = getBaseUrl().removeSuffix("/")
    val accept = acceptFilterFor(type)
    val resultJson = jsPickAndUpload(baseUrl, accept).await() ?: return null
    return parseUploadedDeviceFile(resultJson)
}

// ------------------------------------------------------------------------------ mic recording

// Starts a MediaRecorder over the microphone stream; captured chunks accumulate on a window
// state object until stop/cancel. Resolves true only when recording actually started.
private fun jsStartMicRecording(): Promise<Boolean> = js("""
    new Promise(function(resolve) {
        try {
            if (window.__msMicRecorder) { resolve(true); return; }
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.MediaRecorder) {
                resolve(false);
                return;
            }
            navigator.mediaDevices.getUserMedia({ audio: true }).then(function(stream) {
                var recorder = new MediaRecorder(stream);
                var chunks = [];
                recorder.ondataavailable = function(e) { if (e.data && e.data.size > 0) chunks.push(e.data); };
                recorder.start(250);
                window.__msMicRecorder = { recorder: recorder, stream: stream, chunks: chunks, startedAt: Date.now() };
                resolve(true);
            }).catch(function(e) { resolve(false); });
        } catch (e) { resolve(false); }
    })
""")

// Stops the recorder, uploads the blob to OSS via a pre-signed URL and resolves with the same
// JSON payload as the file picker ({ fileName, ossUrl, durationSeconds }), or null on failure.
@Suppress("UNUSED_PARAMETER")
private fun jsStopMicRecordingAndUpload(baseUrl: String): Promise<String?> = js("""
    new Promise(function(resolve) {
        try {
            var state = window.__msMicRecorder;
            if (!state) { resolve(null); return; }
            window.__msMicRecorder = null;
            var recorder = state.recorder;
            recorder.onstop = function() {
                try { state.stream.getTracks().forEach(function(t) { t.stop(); }); } catch (e) {}
                var mime = recorder.mimeType || 'audio/webm';
                var ext = mime.indexOf('ogg') >= 0 ? 'ogg' : (mime.indexOf('mp4') >= 0 ? 'm4a' : 'webm');
                var blob = new Blob(state.chunks, { type: mime });
                if (!blob.size) { resolve(null); return; }
                var durationSeconds = Math.max(0.5, (Date.now() - state.startedAt) / 1000);
                var fileName = 'voice-recording-' + Date.now() + '.' + ext;
                var objectKey = 'uploads/' + Date.now() + '-' + fileName;
                var uploadUrl = null;
                fetch(baseUrl + '/api/assets/upload-url', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ objectKey: objectKey })
                }).then(function(urlResp) {
                    if (!urlResp.ok) { throw new Error('upload-url request failed'); }
                    return urlResp.json();
                }).then(function(urlJson) {
                    uploadUrl = urlJson.uploadUrl;
                    return fetch(uploadUrl, { method: 'PUT', body: blob });
                }).then(function() {
                    var publicUrl = uploadUrl.split('?')[0];
                    resolve(JSON.stringify({ fileName: fileName, ossUrl: publicUrl, durationSeconds: durationSeconds }));
                }).catch(function(e) { resolve(null); });
            };
            recorder.stop();
        } catch (e) { resolve(null); }
    })
""")

private fun jsCancelMicRecording(): Unit = js("""
    (function() {
        try {
            var state = window.__msMicRecorder;
            if (!state) return;
            window.__msMicRecorder = null;
            state.recorder.onstop = function() {};
            state.recorder.stop();
            state.stream.getTracks().forEach(function(t) { t.stop(); });
        } catch (e) {}
    })()
""")

actual suspend fun startMicRecording(): Boolean = try {
    jsStartMicRecording().await()
} catch (e: Throwable) {
    false
}

actual suspend fun stopMicRecordingAndUpload(): UploadedDeviceFile? {
    val baseUrl = getBaseUrl().removeSuffix("/")
    val resultJson = try {
        jsStopMicRecordingAndUpload(baseUrl).await()
    } catch (e: Throwable) {
        null
    } ?: return null
    return parseUploadedDeviceFile(resultJson)
}

actual fun cancelMicRecording() {
    jsCancelMicRecording()
}
