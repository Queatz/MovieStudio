package app.moviestudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android does not receive external file drops in the shared UI layer (uploading from the device
// needs an Activity/ActivityResultLauncher we do not have here), so dropping is a graceful no-op —
// mirroring pickAndUploadDeviceFile. DroppedFile is never instantiated on Android.
actual class DroppedFile internal constructor(actual val name: String)

@Composable
@Suppress("UNUSED_PARAMETER")
actual fun rememberFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onDropped: (List<DroppedFile>) -> Unit,
): Modifier = Modifier

@Suppress("UNUSED_PARAMETER")
actual suspend fun uploadDroppedFile(
    file: DroppedFile,
    onProgress: (Float) -> Unit,
): UploadedDeviceFile? = null

@Suppress("UNUSED_PARAMETER")
actual suspend fun readDroppedFileText(file: DroppedFile): String? = null
