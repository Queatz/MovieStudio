package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A file the user dragged from outside the app (the OS file manager, the browser, another app...)
 * onto a drop target. The bytes have not been uploaded yet — call [uploadDroppedFile] to send them
 * to object storage, or [readDroppedFileText] for text placeholders.
 *
 * The concrete payload is platform-specific (a [java.io.File] on desktop, a browser `File` handle
 * on the web), but every platform exposes the original file [name] so the studio can infer the
 * asset type from its extension.
 */
expect class DroppedFile {
    /** The original file name including its extension. */
    val name: String
}

/**
 * Returns a [Modifier] that turns the composable it is applied to into a drop target for files
 * dragged from outside the app.
 *
 * While files hover over the target [onDragOver] is invoked with `true`, and with `false` once
 * they leave the target or are dropped — so the UI can reveal a "drop here" affordance. Dropped
 * files are delivered to [onDropped] as soon as the user releases them.
 *
 * On platforms that cannot receive external file drops this returns [Modifier] unchanged and never
 * invokes the callbacks.
 */
@Composable
expect fun rememberFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onDropped: (List<DroppedFile>) -> Unit,
): Modifier

/**
 * Uploads a [file] previously delivered by [rememberFileDropTarget] to object storage (via a
 * pre-signed URL obtained from the server) and returns its uploaded metadata, or null on failure
 * or on a platform without upload support.
 *
 * [onProgress] is invoked with the upload completion fraction (0f..1f) as bytes are sent.
 */
expect suspend fun uploadDroppedFile(
    file: DroppedFile,
    onProgress: (Float) -> Unit = {},
): UploadedDeviceFile?

/** Reads a dropped text file's UTF-8 contents, or null if it cannot be read. */
expect suspend fun readDroppedFileText(file: DroppedFile): String?

/**
 * Best-effort mapping from a dropped file's [fileName] to the studio [AssetType] it should become,
 * based on its extension. Text files map to [AssetType.TEXT] (turned into a placeholder voice
 * asset by the caller). Returns null for extensions the studio does not understand.
 */
fun assetTypeForFile(fileName: String): AssetType? {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff" ->
            AssetType.IMAGE
        "mp4", "mov", "webm", "mkv", "avi", "m4v", "wmv", "flv", "mpg", "mpeg", "3gp" ->
            AssetType.VIDEO
        "mp3", "wav", "m4a", "ogg", "oga", "aac", "flac", "opus", "weba", "aiff", "wma" ->
            AssetType.AUDIO
        "txt", "text", "md", "markdown", "rtf", "srt", "vtt" ->
            AssetType.TEXT
        else -> null
    }
}
