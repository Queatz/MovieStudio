package app.moviestudio.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.TimelineNote

/** The blue shared by the note markers on the timeline and the notes panel accents. */
val NoteBlue = Color(0xFF4DA3FF)

/**
 * The expandable left side panel of the editor: every timeline note (the text-only plot
 * builder), sorted by time. Notes are added at the playhead and can be edited, re-pinned and
 * deleted from here; clicking one seeks to its blue marker on the timeline.
 */
@Composable
fun TimelineNotesPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val expanded = viewModel.notesPanelExpanded
    val width by animateDpAsState(if (expanded) 280.dp else 44.dp)

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TimelineNote?>(null) }
    var deleteTarget by remember { mutableStateOf<TimelineNote?>(null) }

    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (expanded) 10.dp else 6.dp)
    ) {
        if (!expanded) {
            CollapsedNotesRail(viewModel)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📝 Notes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                GhostPillButton("＋ Note", compact = true) { showAdd = true }
                Spacer(Modifier.width(4.dp))
                RoundIconButton("⏴", contentDescription = "Collapse the notes panel", size = 28.dp) {
                    viewModel.notesPanelExpanded = false
                }
            }
            Spacer(Modifier.height(8.dp))

            if (viewModel.timelineNotes.isEmpty()) {
                Text(
                    "No notes yet. Pin text-only notes to the timeline to plot your movie — " +
                        "they show up there as blue markers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Reveal the highlighted note when a marker is clicked on the timeline.
                val listState = rememberLazyListState()
                LaunchedEffect(viewModel.selectedNoteId) {
                    val index = viewModel.timelineNotes.indexOfFirst { it.id == viewModel.selectedNoteId }
                    if (index >= 0) listState.animateScrollToItem(index)
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(viewModel.timelineNotes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            selected = note.id == viewModel.selectedNoteId,
                            onClick = { viewModel.focusNote(note) },
                            onRepin = { viewModel.updateNote(note.copy(atSeconds = viewModel.playhead.toDouble())) },
                            onEdit = { editTarget = note },
                            onDelete = { deleteTarget = note }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        NoteEditorDialog(
            title = "Add note",
            initialText = "",
            atSeconds = viewModel.playhead.toDouble(),
            confirmLabel = "Add note",
            onSave = { text ->
                viewModel.addNote(text)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    editTarget?.let { note ->
        NoteEditorDialog(
            title = "Edit note",
            initialText = note.text,
            atSeconds = note.atSeconds,
            confirmLabel = "Save",
            onSave = { text ->
                viewModel.updateNote(note.copy(text = text))
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    deleteTarget?.let { note ->
        ConfirmDialog(
            title = "Delete note?",
            message = "\"${note.text.take(60)}\" and its marker will be removed from the timeline.",
            confirmLabel = "Delete note",
            onConfirm = {
                viewModel.deleteNote(note.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** The collapsed panel: a slim rail with the expand toggle and the note count. */
@Composable
private fun CollapsedNotesRail(viewModel: AppViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        RoundIconButton("📝", contentDescription = "Show timeline notes", size = 32.dp) {
            viewModel.notesPanelExpanded = true
        }
        if (viewModel.timelineNotes.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(NoteBlue.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text(
                    "${viewModel.timelineNotes.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NoteBlue
                )
            }
        }
    }
}

/** One note in the panel: its time, its text and the re-pin / edit / delete actions. */
@Composable
private fun NoteRow(
    note: TimelineNote,
    selected: Boolean,
    onClick: () -> Unit,
    onRepin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)) // clip BEFORE clickable: rounded hover highlight
            .background(
                if (selected) NoteBlue.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(NoteBlue))
            Spacer(Modifier.width(6.dp))
            Text(
                formatDuration(note.atSeconds),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NoteBlue,
                modifier = Modifier.weight(1f)
            )
            RoundIconButton("📍", contentDescription = "Move the marker to the playhead", size = 22.dp) { onRepin() }
            RoundIconButton("✏️", contentDescription = "Edit note", size = 22.dp) { onEdit() }
            RoundIconButton("🗑", contentDescription = "Delete note", size = 22.dp) { onDelete() }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            note.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Add/edit dialog for a note: the text is the note — notes stay text-only. */
@Composable
private fun NoteEditorDialog(
    title: String,
    initialText: String,
    atSeconds: Double,
    confirmLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    StudioDialog(title = title, onDismiss = onDismiss, width = 420.dp) {
        Text(
            "📍 Pinned at ${formatDuration(atSeconds)}",
            style = MaterialTheme.typography.labelMedium,
            color = NoteBlue,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Note",
            placeholder = "What happens at this moment of the movie...",
            minLines = 3,
            autoFocus = true,
            onSubmit = {
                onSave(text.trim())
            }
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(confirmLabel, enabled = text.isNotBlank()) {
                onSave(text.trim())
            }
        }
    }
}

