package app.moviestudio

import kotlin.js.Promise
import kotlinx.coroutines.await

// Opens a hidden <input type=file>, uploads the selected file to OSS via a pre-signed URL and
// resolves with a small JSON payload ({ fileName, ossUrl, durationSeconds }), or null if the user
// cancels. The public URL is the pre-signed URL with its query string (the signature) stripped.
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
        let uploadUrl = null;
        fetch(baseUrl + '/api/assets/upload-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ objectKey: objectKey })
        }).then((urlResp) => {
            if (!urlResp.ok) { throw new Error('upload-url request failed'); }
            return urlResp.json();
        }).then((urlJson) => {
            uploadUrl = urlJson.uploadUrl;
            return fetch(uploadUrl, { method: 'PUT', body: file });
        }).then(() => readDuration(file)).then((duration) => {
            const publicUrl = uploadUrl.split('?')[0];
            finish(JSON.stringify({ fileName: file.name, ossUrl: publicUrl, durationSeconds: duration }));
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
