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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.Character
import app.moviestudio.GenerationSetup
import app.moviestudio.NetworkService
import app.moviestudio.Scene
import app.moviestudio.VisualStyle
import app.moviestudio.generateId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay

/** Page size for the reference-image picker's paged library fetch. */
private const val REFERENCE_IMAGE_PAGE_SIZE = 20

/**
 * Movie scope for the library API: the open movie when "This movie" is on, all movies when off,
 * and the global (no-movie) bucket when the filter is on but nothing is open.
 */
private fun referencePickerMovieId(onlyThisMovie: Boolean, currentMovieId: String?): String? = when {
    !onlyThisMovie -> null
    currentMovieId != null -> currentMovieId
    else -> "global"
}

/**
 * Reference-image picker shared by the character and scene editors: choose up to [max] images
 * from the global image library, upload new ones, generate them with AI from [aiPrompt] (the
 * subject's description), or repose an attached image via image-to-image editing.
 *
 * [canGenerateAi] gates "Generate with AI" until the character/scene has a name or description.
 * [generationSourceId] tags jobs started from this picker so both AI buttons can show a spinner
 * while those generations are still in flight (same pattern as asset details).
 *
 * Images are loaded from `/api/library` with server-side description search and paging; the
 * thumbnail row infinite-scrolls as the user reaches the end.
 */
@Composable
private fun ReferenceImagePicker(
    viewModel: AppViewModel,
    selected: List<String>,
    max: Int,
    aiPrompt: String,
    canGenerateAi: Boolean,
    generationSourceId: String,
    onChange: (List<String>) -> Unit
) {
    var showRepose by remember { mutableStateOf(false) }
    // Pick up jobs already running for this character/scene when the editor opens, and keep the
    // AI buttons spinning for the full PENDING/RUNNING lifetime of any generation they launched.
    LaunchedEffect(generationSourceId) { viewModel.refreshActiveJobs() }
    val generating = viewModel.isGeneratingForAsset(generationSourceId)
    // Pre-checked "This movie" filter: narrows the thumbnails to images created for the open movie.
    val currentMovieId = viewModel.currentMovie?.id
    var onlyThisMovie by remember { mutableStateOf(true) }
    // Description search is applied server-side via the library endpoint's `q` parameter.
    var searchQuery by remember { mutableStateOf("") }
    val movieScope = referencePickerMovieId(onlyThisMovie, currentMovieId)

    // Paged image results for the picker (independent of the full in-memory library list).
    var imageAssets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Reload the first page whenever the movie filter, search text, open movie, or the global
    // library contents change (uploads / finished generations land in libraryAssets).
    LaunchedEffect(onlyThisMovie, searchQuery, currentMovieId, viewModel.libraryAssets) {
        // Debounce typing so each keystroke doesn't hit the database.
        delay(250)
        loading = true
        loadError = null
        try {
            val page = NetworkService.getLibraryAssets(
                movieId = movieScope,
                type = AssetType.IMAGE,
                q = searchQuery.trim().ifBlank { null },
                offset = 0,
                limit = REFERENCE_IMAGE_PAGE_SIZE,
            )
            // Only images with real media can be attached as references.
            imageAssets = page.items.filter { it.ossUrl.isNotBlank() }
            nextOffset = page.offset + page.items.size
            hasMore = page.hasMore
            // Jump back to the start of the row when filters change.
            listState.scrollToItem(0)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load images"
            imageAssets = emptyList()
            nextOffset = 0
            hasMore = false
        } finally {
            loading = false
        }
    }

    // Infinite scroll: when the last few thumbnails come into view, fetch the next page.
    LaunchedEffect(listState, hasMore, loading, loadingMore, nextOffset, movieScope, searchQuery) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            lastVisible >= 0 && total > 0 && lastVisible >= total - 3
        }
            .distinctUntilChanged()
            .filter { nearEnd -> nearEnd && hasMore && !loading && !loadingMore }
            .collect {
                loadingMore = true
                try {
                    val page = NetworkService.getLibraryAssets(
                        movieId = movieScope,
                        type = AssetType.IMAGE,
                        q = searchQuery.trim().ifBlank { null },
                        offset = nextOffset,
                        limit = REFERENCE_IMAGE_PAGE_SIZE,
                    )
                    val fresh = page.items.filter { it.ossUrl.isNotBlank() }
                    val seen = imageAssets.mapTo(HashSet()) { it.id }
                    imageAssets = imageAssets + fresh.filter { it.id !in seen }
                    nextOffset = page.offset + page.items.size
                    hasMore = page.hasMore
                } catch (_: Exception) {
                    // Keep what we have; the user can scroll again to retry.
                } finally {
                    loadingMore = false
                }
            }
    }

    val available = imageAssets.filter { it.ossUrl !in selected }

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

    // Search bar: filters the library by asset description at the database level.
    StudioTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        placeholder = "Search images by description",
        singleLine = true,
        leadingIcon = { Text("🔍", fontSize = 14.sp) },
        // Search fields don't need the AI-chat shortcut.
        aiGenerate = null,
    )

    // "This movie" chip stays put; the thumbnail row infinite-scrolls beside it.
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThisMovieFilterButton(onlyThisMovie) { onlyThisMovie = !onlyThisMovie }
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(available, key = { it.id }) { image ->
                val canAdd = selected.size < max
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = canAdd) { onChange((selected + image.ossUrl).take(max)) }
                ) {
                    ImageThumbnail(image.ossUrl, size = 60.dp)
                }
            }
            if (loading || loadingMore) {
                item(key = "loading") {
                    Box(
                        Modifier.size(60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
    loadError?.let { err ->
        Text(
            err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GhostPillButton("📤 Upload image", compact = true) { viewModel.uploadAsset(AssetType.IMAGE) }
        GhostPillButton(
            "✨ Generate with AI",
            compact = true,
            // Need a name or description so the AI has something real to paint from (the fallback
            // "the character" prompt alone is not enough).
            enabled = canGenerateAi,
            loading = generating
        ) {
            viewModel.generateMedia(
                GenerationSetup(kind = "image", prompt = aiPrompt),
                sourceAssetId = generationSourceId
            )
        }
        GhostPillButton(
            "🎭 Repose with AI",
            compact = true,
            enabled = selected.isNotEmpty(),
            loading = generating
        ) {
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
    if (!loading && available.isEmpty() && loadError == null) {
        Text(
            if (searchQuery.isNotBlank()) {
                "No images match this search."
            } else {
                "Upload or generate images first — then attach up to $max of them as references."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showRepose) {
        ReposeImageDialog(
            viewModel,
            baseOptions = selected,
            generationSourceId = generationSourceId
        ) { showRepose = false }
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
    // Same id the outer picker uses so "Repose with AI" keeps spinning after this dialog closes.
    generationSourceId: String,
    onDismiss: () -> Unit
) {
    var baseUrl by remember { mutableStateOf(baseOptions.firstOrNull()) }
    var prompt by remember { mutableStateOf("") }
    val generating = viewModel.isGeneratingForAsset(generationSourceId)

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
            PillButton(
                "✨ Generate",
                enabled = baseUrl != null && prompt.isNotBlank(),
                loading = generating
            ) {
                viewModel.generateMedia(
                    GenerationSetup(kind = "image", prompt = prompt.trim(), imageUrl = baseUrl),
                    sourceAssetId = generationSourceId
                )
                onDismiss()
            }
        }
    }
}

/**
 * Create/edit a saved character: name, text description, optional main spoken language and up to
 * 3 reference images.
 *
 * When creating ([existing] is null), optional [initialName] / [initialReferenceImages] pre-fill
 * the form — used when spinning a character off an image asset.
 */
@Composable
fun CharacterEditorDialog(
    viewModel: AppViewModel,
    existing: Character?,
    initialName: String = "",
    initialReferenceImages: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: initialName) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var mainLanguage by remember(existing?.id) { mutableStateOf(existing?.mainLanguage ?: "") }
    var referenceImages by remember(existing?.id) {
        mutableStateOf(existing?.referenceImages ?: initialReferenceImages)
    }
    // Stable id for tagging AI image jobs from this editor session (existing character id, or a
    // fresh one for a new character that has not been saved yet).
    val generationSourceId = remember(existing?.id) { existing?.id ?: generateId() }

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
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = mainLanguage,
            onValueChange = { mainLanguage = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Main language",
            placeholder = "English",
            singleLine = true
        )
        Text(
            "When set, video prompts note that this character speaks in this language unless otherwise specified.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        ReferenceImagePicker(
            viewModel,
            referenceImages,
            Character.MAX_REFERENCE_IMAGES,
            aiPrompt = "Character reference portrait of ${name.ifBlank { "the character" }}: $description",
            canGenerateAi = name.isNotBlank() || description.isNotBlank(),
            generationSourceId = generationSourceId
        ) { referenceImages = it }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save character", enabled = name.isNotBlank()) {
                viewModel.saveCharacter(
                    Character(
                        // Reuse the session id so in-flight AI jobs stay linked after save.
                        id = existing?.id ?: generationSourceId,
                        name = name.trim(),
                        description = description.trim(),
                        mainLanguage = mainLanguage.trim(),
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

/**
 * Create/edit a saved scene: name, text description and up to 3 reference images.
 *
 * When creating ([existing] is null), optional [initialName] / [initialReferenceImages] pre-fill
 * the form — used when spinning a scene off an image asset.
 */
@Composable
fun SceneEditorDialog(
    viewModel: AppViewModel,
    existing: Scene?,
    initialName: String = "",
    initialReferenceImages: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: initialName) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var referenceImages by remember(existing?.id) {
        mutableStateOf(existing?.referenceImages ?: initialReferenceImages)
    }
    // Stable id for tagging AI image jobs from this editor session (existing scene id, or a fresh
    // one for a new scene that has not been saved yet).
    val generationSourceId = remember(existing?.id) { existing?.id ?: generateId() }

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
            aiPrompt = "Establishing shot of the scene ${name.ifBlank { "" }}: $description".trim(),
            canGenerateAi = name.isNotBlank() || description.isNotBlank(),
            generationSourceId = generationSourceId
        ) { referenceImages = it }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save scene", enabled = name.isNotBlank()) {
                viewModel.saveScene(
                    Scene(
                        // Reuse the session id so in-flight AI jobs stay linked after save.
                        id = existing?.id ?: generationSourceId,
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

/**
 * Create/edit a saved visual style: a short name plus free-form style text that is appended to
 * image/video generation prompts so independent media keeps a consistent look.
 */
@Composable
fun VisualStyleEditorDialog(
    viewModel: AppViewModel,
    existing: VisualStyle?,
    onDismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var styleText by remember(existing?.id) { mutableStateOf(existing?.style ?: "") }

    StudioDialog(
        title = if (existing == null) "New visual style" else "Edit visual style",
        onDismiss = onDismiss,
        width = 540.dp
    ) {
        Text(
            "Visual styles keep a consistent look across independent image and video generations. " +
                "When applied, the style text is appended to the generation prompt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            placeholder = "Pretty Anime",
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = styleText,
            onValueChange = { styleText = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Style",
            placeholder = "semi-realistic cute/beautiful anime with faint outlines, cute color palette, a little dreamy",
            minLines = 3,
            maxLines = 8,
            showAiButton = true
        )

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                "Save style",
                enabled = name.isNotBlank() && styleText.isNotBlank()
            ) {
                viewModel.saveVisualStyle(
                    VisualStyle(
                        id = existing?.id ?: generateId(),
                        name = name.trim(),
                        style = styleText.trim(),
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
