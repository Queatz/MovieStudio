package app.moviestudio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.moviestudio.AppViewModel
import app.moviestudio.AssetType
import app.moviestudio.Character
import app.moviestudio.Scene
import app.moviestudio.generateId

/**
 * Reference-image picker shared by the character and scene editors: choose up to [max] images
 * from the global image library (or upload new ones first).
 */
@Composable
private fun ReferenceImagePicker(
    viewModel: AppViewModel,
    selected: List<String>,
    max: Int,
    onChange: (List<String>) -> Unit
) {
    val imageAssets = viewModel.libraryAssets.filter { it.type == AssetType.IMAGE && it.ossUrl.isNotBlank() }

    SectionLabel("Reference images (${selected.size}/$max)")
    if (selected.isNotEmpty()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            selected.forEach { url ->
                val label = imageAssets.firstOrNull { it.ossUrl == url }?.description?.take(18)
                    ?: url.substringAfterLast('/').take(18)
                RemovableChip("🖼 $label") { onChange(selected - url) }
            }
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        imageAssets.filter { it.ossUrl !in selected }.take(14).forEach { image ->
            GhostPillButton(
                "🖼 " + (image.description ?: "image").take(16),
                compact = true,
                enabled = selected.size < max
            ) { onChange((selected + image.ossUrl).take(max)) }
        }
        GhostPillButton("📤 Upload image", compact = true) { viewModel.uploadAsset(AssetType.IMAGE) }
    }
    if (imageAssets.isEmpty()) {
        Text(
            "Upload or generate images first — then attach up to $max of them as references.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        ReferenceImagePicker(viewModel, referenceImages, Character.MAX_REFERENCE_IMAGES) { referenceImages = it }

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
        ReferenceImagePicker(viewModel, referenceImages, Scene.MAX_REFERENCE_IMAGES) { referenceImages = it }

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
                        createdAt = existing?.createdAt ?: 0
                    ),
                    isNew = existing == null
                )
                onDismiss()
            }
        }
    }
}
