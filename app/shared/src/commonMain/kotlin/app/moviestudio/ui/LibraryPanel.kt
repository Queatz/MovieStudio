package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.Character
import app.moviestudio.Scene
import app.moviestudio.VisualStyle
import app.moviestudio.rememberFileDropTarget

/** Library tabs: the global asset library by type, plus saved characters, scenes and styles. */
private sealed interface LibTab {
    data object All : LibTab
    data class OfType(val type: AssetType) : LibTab
    data object Characters : LibTab
    data object Scenes : LibTab
    data object Styles : LibTab
}

private fun tabLabel(tab: LibTab): String = when (tab) {
    // "All" is intentionally emoji-free; every type/library filter carries its identity emoji.
    LibTab.All -> "All"
    is LibTab.OfType -> when (tab.type) {
        AssetType.VIDEO -> "🎬 Video"
        AssetType.IMAGE -> "🖼️ Images"
        AssetType.MUSIC -> "🎵 Music"
        AssetType.AUDIO -> "💥 Sound Effect"
        AssetType.VOICE -> "🎙️ Voice"
        AssetType.TEXT -> "📝 Text"
    }
    LibTab.Characters -> "👤 Characters"
    LibTab.Scenes -> "🏞️ Scenes"
    LibTab.Styles -> "🎨 Styles"
}

/** True when [asset] matches the free-text library [query] (case-insensitive) across its text fields. */
private fun assetMatchesQuery(asset: Asset, query: String): Boolean {
    val q = query.lowercase()
    return (asset.description ?: "").lowercase().contains(q) ||
        (asset.aiPrompt ?: "").lowercase().contains(q) ||
        (asset.transcript ?: "").lowercase().contains(q) ||
        asset.tags.any { it.lowercase().contains(q) }
}

/**
 * True when [asset] would currently be visible in the library asset list given the active [tab],
 * the "This movie" scope ([onlyThisMovie] / [currentMovieId]) and the search [query]. Mirrors the
 * filtering applied when building the list, and is used to decide whether a freshly generated
 * asset warrants scrolling the list to the top.
 */
private fun assetVisibleInLibrary(
    asset: Asset,
    tab: LibTab,
    onlyThisMovie: Boolean,
    currentMovieId: String?,
    query: String
): Boolean {
    val tabMatches = when (tab) {
        LibTab.All -> true
        is LibTab.OfType -> asset.type == tab.type
        // Character/scene/style tabs never show library assets.
        LibTab.Characters, LibTab.Scenes, LibTab.Styles -> false
    }
    if (!tabMatches) return false
    if (onlyThisMovie && asset.movieId != currentMovieId) return false
    return query.isBlank() || assetMatchesQuery(asset, query)
}

/**
 * The right-hand library: every asset in the studio (the library is global — one holistic
 * suite), the saved characters/scenes libraries and the "Add" menu with all creation flows.
 */
@Composable
fun LibraryPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf<LibTab>(LibTab.All) }

    // Dialog state
    var detailAsset by remember { mutableStateOf<Asset?>(null) }
    var showGenerateMedia by remember { mutableStateOf(false) }
    var showGenerateMusic by remember { mutableStateOf(false) }
    var sequencerAsset by remember { mutableStateOf<Asset?>(null) }
    var showSequencer by remember { mutableStateOf(false) }
    var showSfx by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showRecordVoice by remember { mutableStateOf(false) }
    var showRecordSoundEffect by remember { mutableStateOf(false) }
    var showRecordMusic by remember { mutableStateOf(false) }
    var showDescribe by remember { mutableStateOf(false) }
    var showNewText by remember { mutableStateOf(false) }
    var characterEditor by remember { mutableStateOf<Character?>(null) }
    var showNewCharacter by remember { mutableStateOf(false) }
    var sceneEditor by remember { mutableStateOf<Scene?>(null) }
    var showNewScene by remember { mutableStateOf(false) }
    var styleEditor by remember { mutableStateOf<VisualStyle?>(null) }
    var showNewStyle by remember { mutableStateOf(false) }

    // Text search: a magnifier reveals a filter field that narrows the visible media (and the
    // characters/scenes libraries) by name, description, prompt or tags.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Pre-checked "This movie" filter: narrows the media to assets created for the open movie.
    var onlyThisMovie by remember { mutableStateOf(true) }

    // Dropping files from the OS (file manager, browser...) onto the panel uploads them straight
    // into the global library; the type of each asset is inferred from the file's extension.
    var dragOver by remember { mutableStateOf(false) }
    val dropTarget = rememberFileDropTarget(
        enabled = true,
        onDragOver = { dragOver = it },
        onDropped = { files -> viewModel.uploadDroppedFiles(files) }
    )
    val dropAccent = MaterialTheme.colorScheme.primary

    // Scroll the asset list to the top whenever a background generation finishes and the freshly
    // generated asset matches the filters currently applied here, so the new media is revealed.
    val assetListState = rememberLazyListState()
    LaunchedEffect(viewModel.generatedAssetSignal?.id) {
        val signal = viewModel.generatedAssetSignal ?: return@LaunchedEffect
        val matches = assetVisibleInLibrary(
            asset = signal.asset,
            tab = tab,
            onlyThisMovie = onlyThisMovie,
            currentMovieId = viewModel.currentMovie?.id,
            query = searchQuery.trim()
        )
        if (matches) assetListState.animateScrollToItem(0)
    }

    Box(modifier = modifier.then(dropTarget)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Library",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RoundIconButton(
                "🔍",
                contentDescription = "Search media",
                size = 34.dp,
                background = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tint = if (searchOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            }
            Spacer(Modifier.width(6.dp))
            AddMenuButton(
                onGenerateMedia = { showGenerateMedia = true },
                onGenerateMusic = { showGenerateMusic = true },
                onSequencer = { sequencerAsset = null; showSequencer = true },
                onSoundEffect = { showSfx = true },
                onTts = { showTts = true },
                onRecordVoice = { showRecordVoice = true },
                onRecordSoundEffect = { showRecordSoundEffect = true },
                onRecordMusic = { showRecordMusic = true },
                onDescribe = { showDescribe = true },
                onNewText = { showNewText = true },
                onUpload = { type -> viewModel.uploadAsset(type) },
                onNewCharacter = { showNewCharacter = true },
                onNewScene = { showNewScene = true },
                onNewVisualStyle = { showNewStyle = true }
            )
        }
        Spacer(Modifier.height(8.dp))

        // Reveal-able search field to filter the media by text.
        if (searchOpen) {
            StudioTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Search media",
                singleLine = true,
                autoFocus = true,
                leadingIcon = { Text("🔍", fontSize = 14.sp) }
            )
            Spacer(Modifier.height(8.dp))
        }

        // Tab chips
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // "This movie" toggle: assets remember which movie they were created for.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50)) // clip BEFORE clickable: pill hover
                    .background(
                        if (onlyThisMovie) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onlyThisMovie = !onlyThisMovie }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    if (onlyThisMovie) "✓ This movie" else "This movie",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onlyThisMovie) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val tabs = listOf(
                LibTab.All,
                LibTab.OfType(AssetType.VIDEO),
                LibTab.OfType(AssetType.IMAGE),
                LibTab.OfType(AssetType.MUSIC),
                LibTab.OfType(AssetType.AUDIO),
                LibTab.OfType(AssetType.VOICE),
                LibTab.OfType(AssetType.TEXT),
                LibTab.Characters,
                LibTab.Scenes,
                LibTab.Styles
            )
            tabs.forEach { candidate ->
                val selected = candidate == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50)) // clip BEFORE clickable: pill hover
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { tab = candidate }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        tabLabel(candidate),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Live upload progress bar for any device upload started from the "＋ Add" menu.
        viewModel.uploadState?.let { upload ->
            UploadProgressBar(upload)
            Spacer(Modifier.height(8.dp))
        }

        val query = searchQuery.trim()
        when (val current = tab) {
            LibTab.Characters -> CharacterList(viewModel, query, onlyThisMovie) { characterEditor = it }
            LibTab.Scenes -> SceneList(viewModel, query, onlyThisMovie) { sceneEditor = it }
            LibTab.Styles -> StyleList(viewModel, query, onlyThisMovie) { styleEditor = it }
            LibTab.All, is LibTab.OfType -> {
                val typed = when (current) {
                    is LibTab.OfType -> viewModel.libraryAssets.filter { it.type == current.type }
                    else -> viewModel.libraryAssets
                }
                val scoped = if (onlyThisMovie) {
                    typed.filter { it.movieId == viewModel.currentMovie?.id }
                } else {
                    typed
                }
                val assets = if (query.isBlank()) scoped else scoped.filter { assetMatchesQuery(it, query) }
                if (viewModel.libraryAssets.isEmpty() && viewModel.libraryError != null) {
                    // The library failed to load: error + retry instead of an empty list.
                    ErrorRetryBox(
                        message = viewModel.libraryError ?: "Failed to load the library",
                        modifier = Modifier.fillMaxSize(),
                        onRetry = { viewModel.refreshLibrary() }
                    )
                } else if (assets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                query.isNotBlank() -> "No media matches “$query”."
                                onlyThisMovie && typed.isNotEmpty() ->
                                    "No media created for this movie yet.\nUncheck “This movie” to browse the whole library."
                                else -> "Nothing here yet.\nUse ＋ to add or drop files here to create or upload media."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = assetListState,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(assets, key = { it.id }) { asset ->
                            AssetCard(
                                asset = asset,
                                onOpen = { detailAsset = asset },
                                onAddToTimeline = { viewModel.addAssetToTimeline(asset) },
                                onDropOnTimeline = { target ->
                                    viewModel.addAssetToTimeline(asset, target.seconds, target.trackId)
                                }
                            )
                        }
                    }
                }
            }
        }
        }

        // Dotted outline revealed while files hover over the library, inviting the drop.
        if (dragOver) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(dropAccent.copy(alpha = 0.08f))
                    .drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawRoundRect(
                            color = dropAccent,
                            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(
                                width = strokeWidth,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                            )
                        )
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Drop files to upload",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = dropAccent
                )
            }
        }
    }

    // ---------------------------------------------------------------------------- dialogs
    detailAsset?.let { asset ->
        // Always render the freshest copy of the asset from the library.
        val fresh = viewModel.libraryAssets.firstOrNull { it.id == asset.id } ?: asset
        AssetDetailsDialog(
            viewModel = viewModel,
            asset = fresh,
            onDismiss = { detailAsset = null },
            onEditSequence = { seqAsset ->
                detailAsset = null
                sequencerAsset = seqAsset
                showSequencer = true
            }
        )
    }
    if (showGenerateMedia) {
        GenerateMediaDialog(viewModel, initialAsset = null) { showGenerateMedia = false }
    }
    if (showGenerateMusic) {
        GenerateMusicDialog(viewModel) { showGenerateMusic = false }
    }
    if (showSequencer) {
        SequencerDialog(viewModel, sequencerAsset) { showSequencer = false }
    }
    if (showSfx) {
        SoundEffectDialog(viewModel) { showSfx = false }
    }
    if (showTts) {
        TtsDialog(viewModel) { showTts = false }
    }
    if (showRecordVoice) {
        RecordVoiceDialog(viewModel) { showRecordVoice = false }
    }
    if (showRecordSoundEffect) {
        RecordSoundEffectDialog(viewModel) { showRecordSoundEffect = false }
    }
    if (showRecordMusic) {
        RecordMusicDialog(viewModel) { showRecordMusic = false }
    }
    if (showDescribe) {
        DescribeAssetDialog(viewModel) { showDescribe = false }
    }
    if (showNewText) {
        NewTextAssetDialog(viewModel) { showNewText = false }
    }
    if (showNewCharacter) {
        CharacterEditorDialog(viewModel, existing = null) { showNewCharacter = false }
    }
    characterEditor?.let { character ->
        CharacterEditorDialog(viewModel, existing = character) { characterEditor = null }
    }
    if (showNewScene) {
        SceneEditorDialog(viewModel, existing = null) { showNewScene = false }
    }
    sceneEditor?.let { scene ->
        SceneEditorDialog(viewModel, existing = scene) { sceneEditor = null }
    }
    if (showNewStyle) {
        VisualStyleEditorDialog(viewModel, existing = null) { showNewStyle = false }
    }
    styleEditor?.let { style ->
        VisualStyleEditorDialog(viewModel, existing = style) { styleEditor = null }
    }
}

/** Emoji identity per asset type. */
fun assetGlyph(type: AssetType): String = when (type) {
    AssetType.VIDEO -> "🎬"
    AssetType.AUDIO -> "💥"
    AssetType.MUSIC -> "🎵"
    AssetType.VOICE -> "🎙️"
    AssetType.IMAGE -> "🖼️"
    AssetType.TEXT -> "📝"
}

@Composable
private fun AssetCard(
    asset: Asset,
    onOpen: () -> Unit,
    onAddToTimeline: () -> Unit,
    onDropOnTimeline: (TimelineDropTarget) -> Unit
) {
    // Root-space origin of the card, so drag positions can be mapped for the timeline drop.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(RoundedCornerShape(12.dp)) // clip BEFORE clickable: rounded hover highlight
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onOpen() }
            // Drag the card onto the timeline to place the asset at the drop position.
            .pointerInput(asset.id) {
                detectDragGestures(
                    onDragStart = { offset ->
                        LibraryDragState.draggedAsset = asset
                        LibraryDragState.pointerPosition = origin + offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        LibraryDragState.pointerPosition = origin + change.position
                    },
                    onDragEnd = {
                        val target = LibraryDragState.resolveDropTarget?.invoke(LibraryDragState.pointerPosition)
                        LibraryDragState.clear()
                        if (target != null) onDropOnTimeline(target)
                    },
                    onDragCancel = { LibraryDragState.clear() }
                )
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(assetGlyph(asset.type), fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                asset.description ?: asset.aiPrompt ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(asset.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // A media placeholder shows "Placeholder"; a rendered text element shows "Text".
                val badge = when {
                    asset.isTextElement -> "Text"
                    asset.isPlaceholderAsset -> "Placeholder"
                    else -> null
                }
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    ) {
                        Text(
                            badge,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        RoundIconButton("➕", contentDescription = "Add to timeline", size = 28.dp) { onAddToTimeline() }
    }
}

@Composable
private fun CharacterList(
    viewModel: AppViewModel,
    query: String = "",
    onlyThisMovie: Boolean = false,
    onEdit: (Character) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<Character?>(null) }
    deleteTarget?.let { character ->
        ConfirmDialog(
            title = "Delete character?",
            message = "\"${character.name}\" will be permanently removed from the library.",
            confirmLabel = "Delete character",
            onConfirm = { viewModel.deleteCharacter(character.id) },
            onDismiss = { deleteTarget = null }
        )
    }
    val scoped = if (onlyThisMovie) {
        viewModel.characters.filter { it.movieId == viewModel.currentMovie?.id }
    } else {
        viewModel.characters
    }
    val characters = if (query.isBlank()) scoped
    else scoped.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    if (characters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    query.isNotBlank() -> "No characters match “$query”."
                    onlyThisMovie && viewModel.characters.isNotEmpty() ->
                        "No characters created for this movie yet.\nUncheck “This movie” to browse all characters."
                    else -> "No saved characters.\nUse ＋ Add → New character."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(characters, key = { it.id }) { character ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onEdit(character) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("👤", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        character.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        buildString {
                            append("${character.referenceImages.size} reference image(s)")
                            character.mainLanguage.trim().takeIf { it.isNotEmpty() }?.let {
                                append(" • $it")
                            }
                            character.voiceId.trim().takeIf { it.isNotEmpty() }?.let { id ->
                                append(" • ${voiceDisplayLabel(viewModel.voiceOptions, id)}")
                            }
                            if (character.description.isNotBlank()) {
                                append(" • ${character.description.take(40)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RoundIconButton("🗑", size = 26.dp) { deleteTarget = character }
            }
        }
    }
}

@Composable
private fun SceneList(
    viewModel: AppViewModel,
    query: String = "",
    onlyThisMovie: Boolean = false,
    onEdit: (Scene) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<Scene?>(null) }
    deleteTarget?.let { scene ->
        ConfirmDialog(
            title = "Delete scene?",
            message = "\"${scene.name}\" will be permanently removed from the library.",
            confirmLabel = "Delete scene",
            onConfirm = { viewModel.deleteScene(scene.id) },
            onDismiss = { deleteTarget = null }
        )
    }
    val scoped = if (onlyThisMovie) {
        viewModel.scenes.filter { it.movieId == viewModel.currentMovie?.id }
    } else {
        viewModel.scenes
    }
    val scenes = if (query.isBlank()) scoped
    else scoped.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    if (scenes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    query.isNotBlank() -> "No scenes match “$query”."
                    onlyThisMovie && viewModel.scenes.isNotEmpty() ->
                        "No scenes created for this movie yet.\nUncheck “This movie” to browse all scenes."
                    else -> "No saved scenes.\nUse ＋ Add → New scene."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(scenes, key = { it.id }) { scene ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onEdit(scene) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🏞️", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        scene.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${scene.referenceImages.size} reference image(s) • ${scene.description.take(40)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RoundIconButton("🗑", size = 26.dp) { deleteTarget = scene }
            }
        }
    }
}

@Composable
private fun StyleList(
    viewModel: AppViewModel,
    query: String = "",
    onlyThisMovie: Boolean = false,
    onEdit: (VisualStyle) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<VisualStyle?>(null) }
    deleteTarget?.let { style ->
        ConfirmDialog(
            title = "Delete visual style?",
            message = "\"${style.name}\" will be permanently removed from the library.",
            confirmLabel = "Delete style",
            onConfirm = { viewModel.deleteVisualStyle(style.id) },
            onDismiss = { deleteTarget = null }
        )
    }
    val scoped = if (onlyThisMovie) {
        viewModel.visualStyles.filter { it.movieId == viewModel.currentMovie?.id }
    } else {
        viewModel.visualStyles
    }
    val styles = if (query.isBlank()) scoped
    else scoped.filter {
        it.name.contains(query, ignoreCase = true) || it.style.contains(query, ignoreCase = true)
    }
    if (styles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    query.isNotBlank() -> "No visual styles match “$query”."
                    onlyThisMovie && viewModel.visualStyles.isNotEmpty() ->
                        "No visual styles created for this movie yet.\nUncheck “This movie” to browse all styles."
                    else -> "No saved visual styles.\nUse ＋ Add → Visual style..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(styles, key = { it.id }) { style ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onEdit(style) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎨", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        style.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        style.style.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RoundIconButton("🗑", size = 26.dp) { deleteTarget = style }
            }
        }
    }
}
