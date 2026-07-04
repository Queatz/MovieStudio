package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.await
import kotlin.js.Promise

// Web implementation: listen for HTML5 drag-and-drop of files anywhere on the document. Dropped
// File handles are stashed on window.__msDroppedFiles so they can be uploaded/read by index; the
// PUT mirrors the file picker (pre-signed URL + server-signed Content-Type, persist the downloadUrl).
actual class DroppedFile(internal val index: Int, actual val name: String)

// Installs the document-level file drag-and-drop listeners. Reports drag-over transitions through
// [onDragOver] and, on drop, stores the FileList and hands the file count to [onDrop]. Only reacts
// to drags that actually carry files (dataTransfer.types contains "Files"), so text selections and
// in-app drags never trigger the overlay.
private fun jsInstallFileDrop(onDragOver: (Boolean) -> Unit, onDrop: (Int) -> Unit): Unit = js("""
    (function(onDragOver, onDrop) {
        if (window.__msFileDrop) { return; }
        var depth = 0;
        function carriesFiles(e) {
            var types = e.dataTransfer && e.dataTransfer.types;
            if (!types) return false;
            for (var i = 0; i < types.length; i++) { if (types[i] === 'Files') return true; }
            return false;
        }
        function onEnter(e) {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            depth++;
            onDragOver(true);
        }
        function onOver(e) {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            try { e.dataTransfer.dropEffect = 'copy'; } catch (err) {}
        }
        function onLeave(e) {
            depth--;
            if (depth <= 0) { depth = 0; onDragOver(false); }
        }
        function onDropped(e) {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            depth = 0;
            onDragOver(false);
            var files = e.dataTransfer ? e.dataTransfer.files : null;
            window.__msDroppedFiles = files ? Array.prototype.slice.call(files) : [];
            onDrop(window.__msDroppedFiles.length);
        }
        window.__msFileDrop = { enter: onEnter, over: onOver, leave: onLeave, drop: onDropped };
        window.addEventListener('dragenter', onEnter, false);
        window.addEventListener('dragover', onOver, false);
        window.addEventListener('dragleave', onLeave, false);
        window.addEventListener('drop', onDropped, false);
    })(onDragOver, onDrop)
""")

private fun jsUninstallFileDrop(): Unit = js("""
    (function() {
        var h = window.__msFileDrop;
        if (!h) return;
        window.removeEventListener('dragenter', h.enter, false);
        window.removeEventListener('dragover', h.over, false);
        window.removeEventListener('dragleave', h.leave, false);
        window.removeEventListener('drop', h.drop, false);
        window.__msFileDrop = null;
    })()
""")

private fun jsDroppedFileName(index: Int): String = js("""
    (function(index) {
        var files = window.__msDroppedFiles;
        return (files && files[index] && files[index].name) ? files[index].name : '';
    })(index)
""")

@Suppress("UNUSED_PARAMETER")
private fun jsUploadDroppedFile(baseUrl: String, index: Int, onProgress: (Double) -> Unit): Promise<String?> = js("""
    new Promise(function(resolve) {
        try {
            var files = window.__msDroppedFiles;
            var file = files && files[index];
            if (!file) { resolve(null); return; }
            var objectKey = 'uploads/' + Date.now() + '-' + file.name;
            var downloadUrl = null;
            function readDuration(f) {
                return new Promise(function(res) {
                    try {
                        var isAudio = f.type && f.type.indexOf('audio') === 0;
                        var isVideo = f.type && f.type.indexOf('video') === 0;
                        if (!isAudio && !isVideo) { res(5); return; }
                        var media = document.createElement(isAudio ? 'audio' : 'video');
                        media.preload = 'metadata';
                        var objectUrl = URL.createObjectURL(f);
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
            fetch(baseUrl + '/api/assets/upload-url', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ objectKey: objectKey })
            }).then(function(urlResp) {
                if (!urlResp.ok) { throw new Error('upload-url request failed'); }
                return urlResp.json();
            }).then(function(urlJson) {
                downloadUrl = urlJson.downloadUrl || urlJson.uploadUrl.split('?')[0];
                var contentType = urlJson.contentType || 'application/octet-stream';
                return new Promise(function(res, rej) {
                    var xhr = new XMLHttpRequest();
                    xhr.open('PUT', urlJson.uploadUrl, true);
                    xhr.setRequestHeader('Content-Type', contentType);
                    xhr.upload.onprogress = function(e) {
                        if (e.lengthComputable) { onProgress(e.loaded / e.total); }
                    };
                    xhr.onload = function() {
                        if (xhr.status >= 200 && xhr.status < 300) { onProgress(1); res(); }
                        else { rej(new Error('upload PUT failed: ' + xhr.status)); }
                    };
                    xhr.onerror = function() { rej(new Error('upload PUT network error')); };
                    onProgress(0);
                    xhr.send(file);
                });
            }).then(function() {
                return readDuration(file);
            }).then(function(duration) {
                resolve(JSON.stringify({ fileName: file.name, ossUrl: downloadUrl, durationSeconds: duration }));
            }).catch(function(e) {
                resolve(null);
            });
        } catch (e) { resolve(null); }
    })
""")

private fun jsReadDroppedFileText(index: Int): Promise<String?> = js("""
    new Promise(function(resolve) {
        try {
            var files = window.__msDroppedFiles;
            var file = files && files[index];
            if (!file) { resolve(null); return; }
            var reader = new FileReader();
            reader.onload = function() { resolve(String(reader.result)); };
            reader.onerror = function() { resolve(null); };
            reader.readAsText(file);
        } catch (e) { resolve(null); }
    })
""")

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
                    val files = (0 until count).map { i -> DroppedFile(i, jsDroppedFileName(i)) }
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
    val resultJson = jsUploadDroppedFile(baseUrl, file.index) { onProgress(it.toFloat()) }.await() ?: return null
    return parseUploadedDeviceFile(resultJson)
}

actual suspend fun readDroppedFileText(file: DroppedFile): String? = try {
    jsReadDroppedFileText(file.index).await()
} catch (e: Throwable) {
    null
}
