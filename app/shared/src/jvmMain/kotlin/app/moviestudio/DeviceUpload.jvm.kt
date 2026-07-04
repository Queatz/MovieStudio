package app.moviestudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Desktop implementation: pick a file with the AWT file dialog, request a pre-signed URL from the
// server and PUT the file's bytes to it with the server-signed Content-Type (it is part of the
// upload URL's signature). The persisted read URL is the signed downloadUrl (objects are private).
actual suspend fun pickAndUploadDeviceFile(type: AssetType): UploadedDeviceFile? {
    val file = pickFile() ?: return null
    return try {
        val objectKey = "uploads/${generateId()}-${file.name}"
        val upload = NetworkService.requestUploadUrl(objectKey)
        putFile(upload.uploadUrl, file, upload.contentType)
        UploadedDeviceFile(fileName = file.name, ossUrl = upload.downloadUrl, durationSeconds = 5.0)
    } catch (e: Exception) {
        null
    }
}

// Microphone recording is a browser (MediaRecorder) feature; desktop falls back to no-ops.
actual suspend fun startMicRecording(): Boolean = false

actual suspend fun stopMicRecordingAndUpload(): UploadedDeviceFile? = null

actual fun cancelMicRecording() {
    // No-op on desktop.
}

private suspend fun pickFile(): File? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as java.awt.Frame?, "Select media to upload", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return@withContext null
    val name = dialog.file ?: return@withContext null
    File(dir, name)
}

private suspend fun putFile(uploadUrl: String, file: File, contentType: String) = withContext(Dispatchers.IO) {
    val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"
        doOutput = true
        // Must match the Content-Type the pre-signed URL was signed with, or OSS rejects the
        // request with SignatureDoesNotMatch.
        setRequestProperty("Content-Type", contentType)
    }
    conn.outputStream.use { os -> file.inputStream().use { it.copyTo(os) } }
    val status = conn.responseCode
    conn.disconnect()
    if (status !in 200..299) {
        throw IllegalStateException("Upload PUT failed with HTTP $status")
    }
}
