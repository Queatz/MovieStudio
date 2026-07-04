package app.moviestudio

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.io.File

// Desktop implementation: Compose's cross-platform drag-and-drop target receives the AWT drop and
// exposes the dragged files through the event's awtTransferable (java.io.File list flavor).
actual class DroppedFile(val file: File) {
    actual val name: String get() = file.name
}

@Composable
actual fun rememberFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onDropped: (List<DroppedFile>) -> Unit,
): Modifier {
    if (!enabled) return Modifier
    // Keep the latest callbacks without recreating the (remembered) drop target on every recompose.
    val currentDragOver by rememberUpdatedState(onDragOver)
    val currentDropped by rememberUpdatedState(onDropped)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentDragOver(true)
            override fun onExited(event: DragAndDropEvent) = currentDragOver(false)
            override fun onEnded(event: DragAndDropEvent) = currentDragOver(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentDragOver(false)
                val files = filesFromEvent(event)
                if (files.isEmpty()) return false
                currentDropped(files)
                return true
            }
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> eventHasFiles(event) },
        target = target
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun eventHasFiles(event: DragAndDropEvent): Boolean = try {
    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
} catch (e: Exception) {
    false
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNCHECKED_CAST")
private fun filesFromEvent(event: DragAndDropEvent): List<DroppedFile> = try {
    val transferable = event.awtTransferable
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).map { DroppedFile(it) }
    } else {
        emptyList()
    }
} catch (e: Exception) {
    emptyList()
}

actual suspend fun uploadDroppedFile(
    file: DroppedFile,
    onProgress: (Float) -> Unit,
): UploadedDeviceFile? = try {
    val objectKey = "uploads/${generateId()}-${file.file.name}"
    val upload = NetworkService.requestUploadUrl(objectKey)
    putFile(upload.uploadUrl, file.file, upload.contentType, onProgress)
    UploadedDeviceFile(fileName = file.file.name, ossUrl = upload.downloadUrl, durationSeconds = 5.0)
} catch (e: Exception) {
    null
}

actual suspend fun readDroppedFileText(file: DroppedFile): String? = withContext(Dispatchers.IO) {
    try {
        file.file.readText()
    } catch (e: Exception) {
        null
    }
}
