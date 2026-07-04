package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlin.js.Promise
import kotlinx.coroutines.await

// Web implementation: listen for HTML5 drag-and-drop of files anywhere on the document. Dropped
// File handles are stashed on window.__msDroppedFiles so they can be uploaded/read by index; the
// PUT mirrors the file picker (pre-signed URL + server-signed Content-Type, persist the downloadUrl).
actual class DroppedFile(internal val index: Int, actual val name: String)

// Installs document-level file drag-and-drop listeners. Reports drag-over transitions through
// [onDragOver] and, on drop, stores the FileList and hands the file count to [onDrop]. Only reacts
// to drags that actually carry files, so text selections and in-app drags never trigger the overlay.
@JsFun("""
(onDragOver, onDrop) => {
    if (window.__msFileDrop) { return; }
    let depth = 0;
    const carriesFiles = (e) => {
        const types = e.dataTransfer && e.dataTransfer.types;
        if (!types) return false;
        for (let i = 0; i < types.length; i++) { if (types[i] === 'Files') return true; }
        return false;
    };
    const onEnter = (e) => {
        if (!carriesFiles(e)) return;
        e.preventDefault();
        depth++;
        onDragOver(true);
    };
    const onOver = (e) => {
        if (!carriesFiles(e)) return;
        e.preventDefault();
        try { e.dataTransfer.dropEffect = 'copy'; } catch (err) {}
    };
    const onLeave = (e) => {
        depth--;
        if (depth <= 0) { depth = 0; onDragOver(false); }
    };
    const onDropped = (e) => {
        if (!carriesFiles(e)) return;
        e.preventDefault();
        depth = 0;
        onDragOver(false);
        const files = e.dataTransfer ? e.dataTransfer.files : null;
        window.__msDroppedFiles = files ? Array.prototype.slice.call(files) : [];
        onDrop(window.__msDroppedFiles.length);
    };
    window.__msFileDrop = { enter: onEnter, over: onOver, leave: onLeave, drop: onDropped };
    window.addEventListener('dragenter', onEnter, false);
    window.addEventListener('dragover', onOver, false);
    window.addEventListener('dragleave', onLeave, false);
    window.addEventListener('drop', onDropped, false);
}
""")
private external fun jsInstallFileDrop(onDragOver: (Boolean) -> Unit, onDrop: (Int) -> Unit)

@JsFun("""
() => {
    const h = window.__msFileDrop;
    if (!h) return;
    window.removeEventListener('dragenter', h.enter, false);
    window.removeEventListener('dragover', h.over, false);
    window.removeEventListener('dragleave', h.leave, false);
    window.removeEventListener('drop', h.drop, false);
    window.__msFileDrop = null;
}
""")
private external fun jsUninstallFileDrop()

@JsFun("""
(index) => {
    const files = window.__msDroppedFiles;
    return (files && files[index] && files[index].name) ? files[index].name : '';
}
""")
private external fun jsDroppedFileName(index: Int): JsString

@JsFun("""
(baseUrl, index, onProgress) => new Promise((resolve) => {
    try {
        const files = window.__msDroppedFiles;
        const file = files && files[index];
        if (!file) { resolve(null); return; }
        const objectKey = 'uploads/' + Date.now() + '-' + file.name;
        let downloadUrl = null;
        const readDuration = (f) => new Promise((res) => {
            try {
                const isAudio = f.type && f.type.indexOf('audio') === 0;
                const isVideo = f.type && f.type.indexOf('video') === 0;
                if (!isAudio && !isVideo) { res(5); return; }
                const media = document.createElement(isAudio ? 'audio' : 'video');
                media.preload = 'metadata';
                const objectUrl = URL.createObjectURL(f);
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
            return new Promise((res, rej) => {
                const xhr = new XMLHttpRequest();
                xhr.open('PUT', urlJson.uploadUrl, true);
                xhr.setRequestHeader('Content-Type', contentType);
                xhr.upload.onprogress = (e) => {
                    if (e.lengthComputable) { onProgress(e.loaded / e.total); }
                };
                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 300) { onProgress(1); res(); }
                    else { rej(new Error('upload PUT failed: ' + xhr.status)); }
                };
                xhr.onerror = () => rej(new Error('upload PUT network error'));
                onProgress(0);
                xhr.send(file);
            });
        }).then(() => {
            return readDuration(file);
        }).then((duration) => {
            resolve(JSON.stringify({ fileName: file.name, ossUrl: downloadUrl, durationSeconds: duration }));
        }).catch((e) => resolve(null));
    } catch (e) { resolve(null); }
})
""")
private external fun jsUploadDroppedFile(baseUrl: String, index: Int, onProgress: (Double) -> Unit): Promise<JsString?>

@JsFun("""
(index) => new Promise((resolve) => {
    try {
        const files = window.__msDroppedFiles;
        const file = files && files[index];
        if (!file) { resolve(null); return; }
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => resolve(null);
        reader.readAsText(file);
    } catch (e) { resolve(null); }
})
""")
private external fun jsReadDroppedFileText(index: Int): Promise<JsString?>

@Composable
actual fun rememberFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onDropped: (List<DroppedFile>) -> Unit,
): Modifier {
    val currentDragOver by rememberUpdatedState(onDragOver)
    val currentDropped by rememberUpdatedState(onDropped)
    DisposableEffect(enabled) {
        if (enabled) {
            jsInstallFileDrop(
                onDragOver = { over -> currentDragOver(over) },
                onDrop = { count ->
                    val files = (0 until count).map { i -> DroppedFile(i, jsDroppedFileName(i).toString()) }
                    currentDropped(files)
                }
            )
        }
        onDispose { if (enabled) jsUninstallFileDrop() }
    }
    return Modifier
}

actual suspend fun uploadDroppedFile(
    file: DroppedFile,
    onProgress: (Float) -> Unit,
): UploadedDeviceFile? {
    val baseUrl = getBaseUrl().removeSuffix("/")
    val result = jsUploadDroppedFile(baseUrl, file.index) { onProgress(it.toFloat()) }.await() ?: return null
    return parseUploadedDeviceFile(result.toString())
}

actual suspend fun readDroppedFileText(file: DroppedFile): String? = try {
    jsReadDroppedFileText(file.index).await()?.toString()
} catch (e: Throwable) {
    null
}
