package app.moviestudio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A media file the user picked from their device that has already been uploaded to object storage.
 *
 * @property fileName the original file name, used as a human-readable description fallback.
 * @property ossUrl the public URL of the uploaded object.
 * @property durationSeconds the media duration in seconds (best-effort; falls back to a default).
 */
data class UploadedDeviceFile(
    val fileName: String,
    val ossUrl: String,
    val durationSeconds: Double
)

/**
 * Opens the platform's native file picker for the given media [type], uploads the selected file to
 * object storage (via a pre-signed URL obtained from the server) and returns the uploaded file's
 * public URL together with its metadata.
 *
 * Returns null when the user cancels the picker or when the current platform does not support
 * picking/uploading files from the device.
 *
 * [onProgress] is invoked with the upload completion fraction (0f..1f) as bytes are sent, so the
 * UI can show a live progress bar. Platforms that cannot measure progress simply never call it.
 */
expect suspend fun pickAndUploadDeviceFile(
    type: AssetType,
    onProgress: (Float) -> Unit = {}
): UploadedDeviceFile?

/**
 * Starts recording audio from the device microphone (asking for permission if needed).
 * Returns true when recording actually started, false when the platform has no microphone
 * support or the user denied access.
 */
expect suspend fun startMicRecording(): Boolean

/**
 * Stops the in-progress microphone recording, uploads it to object storage (via a pre-signed
 * URL obtained from the server) and returns the uploaded file. Returns null when nothing was
 * being recorded or the upload failed.
 *
 * [onProgress] is invoked with the upload completion fraction (0f..1f) as bytes are sent.
 */
expect suspend fun stopMicRecordingAndUpload(
    onProgress: (Float) -> Unit = {}
): UploadedDeviceFile?

/** Cancels the in-progress microphone recording, discarding any captured audio. */
expect fun cancelMicRecording()

/**
 * The `accept` filter (MIME family) suggested for the given media [type] when opening a file
 * picker. Falls back to accepting any file type.
 */
fun acceptFilterFor(type: AssetType): String = when (type) {
    AssetType.VIDEO -> "video/*"
    AssetType.AUDIO, AssetType.MUSIC, AssetType.VOICE -> "audio/*"
    AssetType.IMAGE -> "image/*"
    AssetType.TEXT -> "text/*"
}

/**
 * Parses the `{ "fileName", "ossUrl", "durationSeconds" }` JSON produced by the web upload
 * implementations back into an [UploadedDeviceFile]. Returns null on malformed input.
 */
internal fun parseUploadedDeviceFile(jsonText: String): UploadedDeviceFile? {
    return try {
        val obj = Json.parseToJsonElement(jsonText).jsonObject
        val fileName = obj["fileName"]?.jsonPrimitive?.content ?: return null
        val ossUrl = obj["ossUrl"]?.jsonPrimitive?.content ?: return null
        val duration = obj["durationSeconds"]?.jsonPrimitive?.doubleOrNull ?: 5.0
        UploadedDeviceFile(fileName = fileName, ossUrl = ossUrl, durationSeconds = duration)
    } catch (e: Exception) {
        null
    }
}
