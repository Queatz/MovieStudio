package app.moviestudio.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.MovieDocument
import app.moviestudio.updateAudioPlayback
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.delay

/** A document with its nesting depth, as laid out in the flattened tree list. */
private data class DocumentRow(val document: MovieDocument, val depth: Int)

/** Where a dragged document would land relative to the row it hovers over. */
private enum class DropZone { BEFORE, INTO, AFTER }

/**
 * Flattens the documents tree into a depth-first list of rows: children (sorted by sortIndex)
 * directly follow their parent with an increased depth. Documents whose parent is missing (or
 * that sit deeper than the nesting cap) still show up at the top level, so nothing ever
 * disappears from the panel.
 */
private fun flattenDocumentTree(documents: List<MovieDocument>): List<DocumentRow> {
    val byParent = documents.groupBy { it.parentId }
    val result = mutableListOf<DocumentRow>()
    fun addLevel(parentId: String?, depth: Int) {
        (byParent[parentId] ?: emptyList())
            .sortedWith(compareBy({ it.sortIndex }, { it.createdAt }))
            .forEach { document ->
                result.add(DocumentRow(document, depth))
                if (depth < 8) addLevel(document.id, depth + 1)
            }
    }
    addLevel(null, 0)
    val listed = result.map { it.document.id }.toSet()
    documents.filter { it.id !in listed }
        .sortedWith(compareBy({ it.sortIndex }, { it.createdAt }))
        .forEach { result.add(DocumentRow(it, 0)) }
    return result
}

/** A short plain-text excerpt of rich [html] content, for history rows and previews. */
private fun htmlExcerpt(html: String, maxLength: Int = 90): String {
    val text = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    return if (text.length <= maxLength) text else text.take(maxLength) + "…"
}

/**
 * The expandable movie documents panel, docked under the timeline notes panel: every long-form
 * document of the movie (full script, research, character bios...) as a nested tree. Documents
 * are created with the ＋ button, nest under each other and are re-arranged by dragging a row
 * onto another (drop on the middle nests it inside; near the top/bottom edge places it before/
 * after). While the panel is open, the preview area shows the document editor.
 */
@Composable
fun DocumentsPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val expanded = viewModel.documentsPanelExpanded
    val width by animateDpAsState(if (expanded) 280.dp else 44.dp)

    var deleteTarget by remember { mutableStateOf<MovieDocument?>(null) }

    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (expanded) 10.dp else 6.dp)
    ) {
        if (!expanded) {
            CollapsedDocumentsRail(viewModel)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📄 Documents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                GhostPillButton("＋", compact = true) { viewModel.addDocument() }
                Spacer(Modifier.width(4.dp))
                RoundIconButton("⏴", contentDescription = "Close the documents panel", size = 28.dp) {
                    viewModel.documentsPanelExpanded = false
                }
            }
            Spacer(Modifier.height(8.dp))

            if (viewModel.documents.isEmpty() && viewModel.documentsError != null) {
                // Documents failed to load: error + retry instead of the empty state.
                ErrorRetryBox(
                    message = viewModel.documentsError ?: "Failed to load documents",
                    modifier = Modifier.fillMaxWidth(),
                    onRetry = { viewModel.refreshDocuments() }
                )
            } else if (viewModel.documents.isEmpty()) {
                Text(
                    "No documents yet. Keep the full script and other long-form writing here — " +
                        "it stays with the movie without being part of the final cut.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                DocumentTree(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f),
                    onDelete = { deleteTarget = it }
                )
            }
        }
    }

    deleteTarget?.let { document ->
        val childCount = viewModel.documentSubtreeIds(document.id).size - 1
        ConfirmDialog(
            title = "Delete document?",
            message = "\"${document.title.take(60)}\"" +
                (if (childCount > 0) " and its $childCount nested document(s)" else "") +
                " will be permanently removed from the movie.",
            confirmLabel = "Delete document",
            onConfirm = {
                viewModel.deleteDocument(document.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** The collapsed panel: a slim rail with the expand toggle and the document count. */
@Composable
private fun CollapsedDocumentsRail(viewModel: AppViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        RoundIconButton("📄", contentDescription = "Show movie documents", size = 32.dp) {
            viewModel.documentsPanelExpanded = true
        }
        if (viewModel.documents.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text(
                    "${viewModel.documents.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * The nested documents list with drag-and-drop re-arranging. Rows report their root-space bounds
 * so the drop target under the pointer is resolved live: hovering a row's middle nests the
 * dragged document inside it, hovering near the top/bottom edge places it before/after.
 */
@Composable
private fun DocumentTree(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onDelete: (MovieDocument) -> Unit
) {
    val rows = flattenDocumentTree(viewModel.documents)

    var draggedId by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableStateOf(0f) }
    val rowBounds = remember { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }

    // The dragged document can't land inside its own subtree.
    val forbidden = draggedId?.let { viewModel.documentSubtreeIds(it) } ?: emptySet()

    // Resolve the row + zone currently under the pointer.
    val dropTarget: Pair<String, DropZone>? = draggedId?.let {
        rows.firstNotNullOfOrNull { row ->
            val bounds = rowBounds[row.document.id] ?: return@firstNotNullOfOrNull null
            if (row.document.id in forbidden || pointerY !in bounds) return@firstNotNullOfOrNull null
            val heightPx = bounds.endInclusive - bounds.start
            val zone = when {
                pointerY < bounds.start + heightPx * 0.25f -> DropZone.BEFORE
                pointerY > bounds.endInclusive - heightPx * 0.25f -> DropZone.AFTER
                else -> DropZone.INTO
            }
            row.document.id to zone
        }
    }

    fun completeDrop() {
        val dragged = draggedId
        val target = dropTarget
        draggedId = null
        if (dragged == null || target == null || target.first == dragged) return
        val (targetId, zone) = target
        val targetDoc = viewModel.documents.firstOrNull { it.id == targetId } ?: return
        when (zone) {
            // Nest inside the target, after its existing children.
            DropZone.INTO -> {
                val childCount = viewModel.documents.count { it.parentId == targetId && it.id != dragged }
                viewModel.moveDocument(dragged, targetId, childCount)
            }
            // Become the target's sibling, right before/after it.
            else -> {
                val siblings = viewModel.documents
                    .filter { it.parentId == targetDoc.parentId && it.id != dragged }
                    .sortedWith(compareBy({ it.sortIndex }, { it.createdAt }))
                val targetIndex = siblings.indexOfFirst { it.id == targetId }
                if (targetIndex < 0) return
                val index = if (zone == DropZone.BEFORE) targetIndex else targetIndex + 1
                viewModel.moveDocument(dragged, targetDoc.parentId, index)
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        rows.forEach { row ->
            key(row.document.id) {
                DocumentTreeRow(
                    row = row,
                    selected = row.document.id == viewModel.selectedDocumentId,
                    dragging = row.document.id == draggedId,
                    dropZone = dropTarget?.takeIf { it.first == row.document.id }?.second,
                    onBoundsChange = { rowBounds[row.document.id] = it },
                    onClick = { viewModel.selectedDocumentId = row.document.id },
                    onAddChild = { viewModel.addDocument(parentId = row.document.id) },
                    onDelete = { onDelete(row.document) },
                    onDragStart = { startY ->
                        draggedId = row.document.id
                        pointerY = startY
                    },
                    onDragTo = { y -> pointerY = y },
                    onDragEnd = { completeDrop() },
                    onDragCancel = { draggedId = null }
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** One document row: indent per depth, title, add-child and delete actions, drag handle. */
@Composable
private fun DocumentTreeRow(
    row: DocumentRow,
    selected: Boolean,
    dragging: Boolean,
    dropZone: DropZone?,
    onBoundsChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onClick: () -> Unit,
    onAddChild: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: (Float) -> Unit,
    onDragTo: (Float) -> Unit,
    onDragCancel: () -> Unit,
    onDragEnd: () -> Unit
) {
    var originY by remember { mutableStateOf(0f) }
    // pointerInput(id) below never restarts (the id is stable), so the gesture would keep the
    // callbacks captured on first composition — including a completeDrop that only ever sees a
    // null drop target. rememberUpdatedState keeps the latest callbacks visible to the gesture.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragTo by rememberUpdatedState(onDragTo)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val accent = MaterialTheme.colorScheme.primary
    val background = when {
        dropZone == DropZone.INTO -> accent.copy(alpha = 0.28f)
        selected -> accent.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 14).dp)
            .onGloballyPositioned {
                originY = it.positionInRoot().y
                onBoundsChange(originY..(originY + it.size.height))
            }
            .alpha(if (dragging) 0.45f else 1f)
            .clip(RoundedCornerShape(10.dp)) // clip BEFORE clickable: rounded hover highlight
            .background(background)
            // Insert-line indicator when the drop lands before/after this row (as a sibling).
            .drawBehind {
                if (dropZone == DropZone.BEFORE) {
                    drawLine(accent, Offset(0f, 1.5f), Offset(size.width, 1.5f), strokeWidth = 3f)
                }
                if (dropZone == DropZone.AFTER) {
                    drawLine(
                        accent,
                        Offset(0f, size.height - 1.5f),
                        Offset(size.width, size.height - 1.5f),
                        strokeWidth = 3f
                    )
                }
            }
            .clickable { onClick() }
            // Drag the row onto another document to re-arrange / nest it.
            .pointerInput(row.document.id) {
                detectDragGestures(
                    onDragStart = { offset -> currentOnDragStart(originY + offset.y) },
                    onDrag = { change, _ ->
                        change.consume()
                        currentOnDragTo(originY + change.position.y)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragCancel() }
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (row.depth == 0) "📄" else "↳", fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            row.document.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RoundIconButton("＋", contentDescription = "Add a nested document", size = 22.dp) { onAddChild() }
        RoundIconButton("🗑", contentDescription = "Delete document", size = 22.dp) { onDelete() }
    }
}

/**
 * The center-stage documents area shown in place of the preview panel while the documents panel
 * is open: the empty state when nothing is selected, otherwise the rich-text editor for the
 * selected document (auto-saving, with a restorable history).
 */
@Composable
fun DocumentEditorPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    // The preview (and its audio-pool sync) is hidden while documents are open — stop playback.
    LaunchedEffect(Unit) {
        viewModel.pause()
        updateAudioPlayback(emptyList(), false)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        val document = viewModel.documents.firstOrNull { it.id == viewModel.selectedDocumentId }
        if (document == null) {
            DocumentsEmptyState(viewModel)
        } else {
            DocumentEditor(viewModel, document)
        }
    }
}

/** Empty state for the documents area: no document is selected (or none exists yet). */
@Composable
private fun DocumentsEmptyState(viewModel: AppViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📄", fontSize = 34.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (viewModel.documents.isEmpty()) "No documents yet" else "No document selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Keep the full script, research and other long-form writing with the movie.\n" +
                    "Documents auto-save, keep a restorable history and can nest under each other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
            Spacer(Modifier.height(14.dp))
            PillButton("＋ New document") { viewModel.addDocument() }
        }
    }
}

/**
 * The rich-text editor for one document: an editable title, a formatting toolbar and the
 * rich editor itself. Content auto-saves about a second after typing pauses (and once more when
 * the editor closes); the server checkpoints replaced content into the document's history.
 */
@Composable
private fun ColumnScope.DocumentEditor(viewModel: AppViewModel, document: MovieDocument) {
    val state = remember(document.id) { RichTextState().apply { setHtml(document.content) } }
    var editingTitle by remember(document.id) { mutableStateOf(false) }
    var titleDraft by remember(document.id) { mutableStateOf(document.title) }
    var showHistory by remember(document.id) { mutableStateOf(false) }

    fun freshDocument(): MovieDocument =
        viewModel.documents.firstOrNull { it.id == document.id } ?: document

    // Debounced auto-save: persist the content when it settled for a moment.
    LaunchedEffect(document.id) {
        var lastSaved = document.content
        while (true) {
            delay(1000)
            val html = state.toHtml()
            if (html != lastSaved) {
                lastSaved = html
                viewModel.updateDocument(freshDocument().copy(content = html))
            }
        }
    }
    // Save any trailing edits when the editor closes or switches documents.
    DisposableEffect(document.id) {
        onDispose {
            val html = state.toHtml()
            val fresh = viewModel.documents.firstOrNull { it.id == document.id }
            if (fresh != null && html != fresh.content) {
                viewModel.updateDocument(fresh.copy(content = html))
            }
        }
    }

    // ------------------------------------------------------------------ header: title + actions
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (editingTitle) {
            StudioTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                autoFocus = true,
                onDismiss = { editingTitle = false },
                onSubmit = {
                    if (titleDraft.isNotBlank()) {
                        viewModel.updateDocument(freshDocument().copy(title = titleDraft.trim()))
                    }
                    editingTitle = false
                }
            )
            Spacer(Modifier.width(6.dp))
            PillButton("Save", compact = true, enabled = titleDraft.isNotBlank()) {
                viewModel.updateDocument(freshDocument().copy(title = titleDraft.trim()))
                editingTitle = false
            }
        } else {
            Text(
                document.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { titleDraft = document.title; editingTitle = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        GhostPillButton("🕒 History (${document.history.size})", compact = true,
            enabled = document.history.isNotEmpty()) { showHistory = true }
        Spacer(Modifier.width(6.dp))
        RoundIconButton("✕", contentDescription = "Close the document", size = 28.dp) {
            viewModel.selectedDocumentId = null
        }
    }
    Spacer(Modifier.height(8.dp))

    // ---------------------------------------------------------------------- formatting toolbar
    // Shared by the toolbar buttons and the Ctrl/Cmd+B/I/U shortcuts on the editor.
    fun toggleBold() = state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
    fun toggleItalic() = state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
    fun toggleUnderline() = state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FormatToggle("B", active = state.currentSpanStyle.fontWeight == FontWeight.Bold) { toggleBold() }
        FormatToggle("I", active = state.currentSpanStyle.fontStyle == FontStyle.Italic) { toggleItalic() }
        FormatToggle(
            "U",
            active = state.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true
        ) {
            toggleUnderline()
        }
        FormatToggle(
            "S̶",
            active = state.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true
        ) {
            state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        FormatToggle("H", active = state.currentSpanStyle.fontSize == 24.sp) {
            state.toggleSpanStyle(SpanStyle(fontSize = 24.sp))
        }
        FormatToggle("• List", active = state.isUnorderedList) { state.toggleUnorderedList() }
        FormatToggle("1. List", active = state.isOrderedList) { state.toggleOrderedList() }
    }
    Spacer(Modifier.height(8.dp))

    // ------------------------------------------------------------------------------- the editor
    RichTextEditor(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            // Standard formatting shortcuts: Ctrl+B/I/U (Cmd on macOS) toggle the style of the
            // selection, or of what gets typed next when the cursor is collapsed.
            .onPreviewKeyEvent { event ->
                val shortcut = event.isCtrlPressed || event.isMetaPressed
                if (event.type != KeyEventType.KeyDown || !shortcut) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.B -> { toggleBold(); true }
                    Key.I -> { toggleItalic(); true }
                    Key.U -> { toggleUnderline(); true }
                    else -> false
                }
            },
        placeholder = {
            Text(
                "Write the script, notes, research…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )

    if (showHistory) {
        DocumentHistoryDialog(
            document = document,
            onRestore = { version ->
                state.setHtml(version.content)
                viewModel.restoreDocumentVersion(freshDocument(), version)
                showHistory = false
            },
            onDismiss = { showHistory = false }
        )
    }
}

/** A compact toolbar toggle button for one formatting action. */
@Composable
private fun FormatToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // The button must not steal focus from the rich editor on click: losing focus drops
            // the text selection, which made the formatting actions appear to do nothing.
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(8.dp)) // clip BEFORE clickable: rounded hover highlight
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The document's saved versions, newest first: auto-save checkpoints the replaced content, so any
 * earlier state of the text can be brought back (restoring checkpoints the current content too).
 */
@Composable
private fun DocumentHistoryDialog(
    document: MovieDocument,
    onRestore: (app.moviestudio.DocumentVersion) -> Unit,
    onDismiss: () -> Unit
) {
    StudioDialog(title = "Document history", onDismiss = onDismiss, width = 520.dp, scrollable = false) {
        Text(
            "Previous versions of \"${document.title.take(40)}\", newest first. Restoring keeps " +
                "the current text in the history, so nothing is lost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(document.history.size) { index ->
                val version = document.history[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "v${document.history.size - index}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        htmlExcerpt(version.content).ifBlank { "(empty)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    GhostPillButton("↩ Restore", compact = true) { onRestore(version) }
                }
            }
        }
        DialogActions {
            ActionSpacer()
            PillButton("Close", compact = true) { onDismiss() }
        }
    }
}
