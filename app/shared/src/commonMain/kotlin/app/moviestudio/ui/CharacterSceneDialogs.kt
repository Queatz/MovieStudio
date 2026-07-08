package app.moviestudio.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.moviestudio.AppViewModel
import app.moviestudio.AssetType
import app.moviestudio.Character
import app.moviestudio.GenerationSetup
import app.moviestudio.Scene
import app.moviestudio.generateId

/**
 * Reference-image picker shared by the character and scene editors: choose up to [max] images
 * from the global image library, upload new ones, generate them with AI from [aiPrompt] (the
 * subject's description), or repose an attached image via image-to-image editing.
 */
@Composable
private fun ReferenceImagePicker(
    viewModel: AppViewModel,
    selected: List<String>,
    max: Int,
    aiPrompt: String,
    onChange: (List<String>) -> Unit
) {
    val imageAssets = viewModel.libraryAssets.filter { it.type == AssetType.IMAGE && it.ossUrl.isNotBlank() }
    var showRepose by remember { mutableStateOf(false) }

    SectionLabel("Reference images (${selected.size}/$max)")
    if (selected.isNotEmpty()) {
        // Preview each attached reference image so the user sees exactly what is selected.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            selected.forEach { url ->
                ImageThumbnail(url, size = 76.dp) { onChange(selected - url) }
            }
        }
    }
    // Library images available to attach, shown as clickable thumbnail previews.
    val available = imageAssets.filter { it.ossUrl !in selected }.take(14)
    if (available.isNotEmpty()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            available.forEach { image ->
                val canAdd = selected.size < max
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = canAdd) { onChange((selected + image.ossUrl).take(max)) }
                ) {
                    ImageThumbnail(image.ossUrl, size = 60.dp)
                }
            }
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GhostPillButton("📤 Upload image", compact = true) { viewModel.uploadAsset(AssetType.IMAGE) }
        GhostPillButton("✨ Generate with AI", compact = true, enabled = aiPrompt.isNotBlank()) {
            viewModel.generateMedia(GenerationSetup(kind = "image", prompt = aiPrompt))
        }
        GhostPillButton("🎭 Repose with AI", compact = true, enabled = selected.isNotEmpty()) {
            showRepose = true
        }
    }
    // Live progress while a reference image uploads from the device.
    viewModel.uploadState?.let { upload ->
        Spacer(Modifier.height(6.dp))
        UploadProgressBar(upload)
    }
    Text(
        "AI images generate in the background and appear in the list above when ready.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (imageAssets.isEmpty()) {
        Text(
            "Upload or generate images first — then attach up to $max of them as references.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showRepose) {
        ReposeImageDialog(viewModel, baseOptions = selected) { showRepose = false }
    }
}

/**
 * Image-to-image reposing: pick one of the attached reference images and describe the new pose,
 * angle or expression — the image-edit model repaints the same subject into a fresh library
 * image that can then be attached as another reference.
 */
@Composable
private fun ReposeImageDialog(
    viewModel: AppViewModel,
    baseOptions: List<String>,
    onDismiss: () -> Unit
) {
    var baseUrl by remember { mutableStateOf(baseOptions.firstOrNull()) }
    var prompt by remember { mutableStateOf("") }

    StudioDialog(title = "Repose with AI", onDismiss = onDismiss, width = 500.dp) {
        Text(
            "Pick a base image and describe the new pose, camera angle or expression. The " +
                "image-edit model repaints the same subject into a new reference image.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SectionLabel("Base image")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            baseOptions.forEach { url ->
                val isSelected = baseUrl == url
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { baseUrl = url }
                        .padding(2.dp)
                ) {
                    ImageThumbnail(url, size = 64.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = "New pose / variation",
            placeholder = "Same character, three-quarter view, arms crossed, confident smile...",
            minLines = 2,
            maxLines = 4
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("✨ Generate", enabled = baseUrl != null && prompt.isNotBlank()) {
                viewModel.generateMedia(GenerationSetup(kind = "image", prompt = prompt.trim(), imageUrl = baseUrl))
                onDismiss()
            }
        }
    }
}

/** Create/edit a saved character: name, text description and up to 3 reference images. */
@Composable
fun CharacterEditorDialog(viewModel: AppViewModel, existing: Character?, onDismiss: () -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var referenceImages by remember(existing?.id) { mutableStateOf(existing?.referenceImages ?: emptyList()) }

    StudioDialog(
        title = if (existing == null) "New character" else "Edit character",
        onDismiss = onDismiss,
        width = 540.dp
    ) {
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            placeholder = "Captain Mira Vale",
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Description",
            placeholder = "A weathered starship captain in a crimson coat, silver hair, kind eyes...",
            minLines = 3,
            maxLines = 6
        )
        Spacer(Modifier.height(4.dp))
        ReferenceImagePicker(
            viewModel,
            referenceImages,
            Character.MAX_REFERENCE_IMAGES,
            aiPrompt = "Character reference portrait of ${name.ifBlank { "the character" }}: $description"
        ) { referenceImages = it }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save character", enabled = name.isNotBlank()) {
                viewModel.saveCharacter(
                    Character(
                        id = existing?.id ?: generateId(),
                        name = name.trim(),
                        description = description.trim(),
                        referenceImages = referenceImages.take(Character.MAX_REFERENCE_IMAGES),
                        movieId = existing?.movieId ?: viewModel.currentMovie?.id,
                        createdAt = existing?.createdAt ?: 0
                    ),
                    isNew = existing == null
                )
                onDismiss()
            }
        }
    }
}

/** Create/edit a saved scene: name, text description and up to 3 reference images. */
@Composable
fun SceneEditorDialog(viewModel: AppViewModel, existing: Scene?, onDismiss: () -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var referenceImages by remember(existing?.id) { mutableStateOf(existing?.referenceImages ?: emptyList()) }

    StudioDialog(
        title = if (existing == null) "New scene" else "Edit scene",
        onDismiss = onDismiss,
        width = 540.dp
    ) {
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            placeholder = "Neon Harbor at night",
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Description",
            placeholder = "Rain-slick docks, holographic billboards reflecting in the water...",
            minLines = 3,
            maxLines = 6
        )
        Spacer(Modifier.height(4.dp))
        ReferenceImagePicker(
            viewModel,
            referenceImages,
            Scene.MAX_REFERENCE_IMAGES,
            aiPrompt = "Establishing shot of the scene ${name.ifBlank { "" }}: $description".trim()
        ) { referenceImages = it }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save scene", enabled = name.isNotBlank()) {
                viewModel.saveScene(
                    Scene(
                        id = existing?.id ?: generateId(),
                        name = name.trim(),
                        description = description.trim(),
                        referenceImages = referenceImages.take(Scene.MAX_REFERENCE_IMAGES),
                        movieId = existing?.movieId ?: viewModel.currentMovie?.id,
                        createdAt = existing?.createdAt ?: 0
                    ),
                    isNew = existing == null
                )
                onDismiss()
            }
        }
    }
}
