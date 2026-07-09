package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.moviestudio.AppViewModel
import app.moviestudio.MovieStatus
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.RenderRecord
import app.moviestudio.SUPPORTED_ASPECT_RATIOS
import app.moviestudio.VideoPlayer
import app.moviestudio.displayName
import app.moviestudio.requestVideoFullscreen
import app.moviestudio.triggerDownload
import app.moviestudio.updateAudioPlayback

/**
 * The movie editor: header (title, status, aspect, jobs, render), preview + library row, clip
 * inspector and the timeline. Hosts the render dialogs and the background-generations panel.
 */
@Composable
fun EditorScreen(viewModel: AppViewModel) {
    var showJobsPanel by remember { mutableStateOf(false) }
    var showRenders by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    // Leaving the movie editor (back to the dashboard) must stop every track that could still be
    // sounding. Pausing the viewModel stops the playback ticker and the shared <video> element
    // (VideoPlayer already hides/pauses itself on disposal), but the browser audio pool backing
    // music/voice/other-audio clips is a persistent, platform-level resource that is only
    // reconciled from PreviewPanel's LaunchedEffect — which simply gets cancelled, without a final
    // "stop everything" call, when this whole screen is torn down. Explicitly emptying it here
    // ensures nothing keeps playing behind the closed editor.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.pause()
            updateAudioPlayback(emptyList(), false)
        }
    }

    // Fullscreen playback: the stage fills the window, every other control disappears.
    if (viewModel.isFullscreenPlayback) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PreviewPanel(viewModel, Modifier.fillMaxSize(), fullscreen = true)
            // Discreet exit affordance (ESC also leaves fullscreen).
            RoundIconButton(
                "\uD83E\uDC90",
                contentDescription = "Exit fullscreen (Esc)",
                size = 34.dp,
                background = Color.Black.copy(alpha = 0.45f),
                tint = Color.White.copy(alpha = 0.8f)
            ) { viewModel.exitFullscreenPlayback() }
        }
        return
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                viewModel = viewModel,
                onShowJobs = { showJobsPanel = true },
                onShowRenders = {
                    viewModel.refreshRenders()
                    showRenders = true
                }
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .padding(horizontal = 12.dp)
            ) {
                // Left rail: the timeline notes panel with the movie documents panel docked
                // right under it. Collapsed panels wrap to their icon rail (so the 📄 button
                // sits directly below the 📝 button), while an expanded panel takes the
                // remaining height — documents get (nearly) the full height when notes are
                // closed, and the two split it when both are open.
                Column(Modifier.fillMaxHeight()) {
                    TimelineNotesPanel(
                        viewModel,
                        if (viewModel.notesPanelExpanded) Modifier.weight(1f) else Modifier
                    )
                    Spacer(Modifier.height(12.dp))
                    DocumentsPanel(
                        viewModel,
                        if (viewModel.documentsPanelExpanded) Modifier.weight(1f) else Modifier
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (viewModel.documentsPanelExpanded) {
                        // Documents mode: the preview area becomes the document editor (or the
                        // documents empty state until one is selected).
                        DocumentEditorPanel(viewModel, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        PreviewPanel(viewModel, Modifier.weight(1f).fillMaxWidth())
                        val selection = viewModel.findClip(viewModel.selectedClipId)
                        if (selection != null) {
                            Spacer(Modifier.height(8.dp))
                            ClipInspector(viewModel, selection.first, selection.second)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                LibraryPanel(viewModel, Modifier.width(330.dp).fillMaxHeight())
            }

            Spacer(Modifier.height(10.dp))
            TimelinePanel(
                viewModel,
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        // Floating ghost following the pointer while a library card is dragged to the timeline.
        val dragged = LibraryDragState.draggedAsset
        if (dragged != null) {
            val pointer = LibraryDragState.pointerPosition - rootOrigin
            Box(
                Modifier
                    .offset { IntOffset((pointer.x + 14f).roundToInt(), (pointer.y + 10f).roundToInt()) }
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "${assetGlyph(dragged.type)} " + (dragged.description ?: dragged.aiPrompt ?: "media").take(26),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (viewModel.renderJobId != null) {
        RenderProgressDialog(viewModel)
    }
    if (showRenders) {
        RendersHistoryDialog(viewModel) { showRenders = false }
    }
    if (showJobsPanel) {
        BackgroundJobsDialog(viewModel) { showJobsPanel = false }
    }
}

@Composable
private fun EditorTopBar(
    viewModel: AppViewModel,
    onShowJobs: () -> Unit,
    onShowRenders: () -> Unit
) {
    val movie = viewModel.currentMovie ?: return
    var editingTitle by remember(movie.id) { mutableStateOf(false) }
    var titleDraft by remember(movie.id) { mutableStateOf(movie.title) }
    var editingDescription by remember(movie.id) { mutableStateOf(false) }
    var descriptionDraft by remember(movie.id) { mutableStateOf(movie.description) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton("←", contentDescription = "Back to movies", size = 34.dp) { viewModel.goToDashboard() }
        Spacer(Modifier.width(8.dp))

        Column {
            if (editingTitle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudioTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        modifier = Modifier.width(280.dp),
                        singleLine = true,
                        autoFocus = true,
                        onDismiss = { editingTitle = false },
                        onSubmit = {
                            viewModel.updateMovie(movie.copy(title = titleDraft.trim()))
                            editingTitle = false
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    PillButton("Save", compact = true, enabled = titleDraft.isNotBlank()) {
                        viewModel.updateMovie(movie.copy(title = titleDraft.trim()))
                        editingTitle = false
                    }
                }
            } else {
                Text(
                    movie.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { titleDraft = movie.title; editingTitle = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (editingTitle && editingDescription) {
                Spacer(Modifier.width(6.dp))
            }

            // The movie's description, shown and editable right under the title. Click to edit
            // inline (multi-line); a blank description shows a subtle prompt to add one.
            if (editingDescription) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudioTextField(
                        value = descriptionDraft,
                        onValueChange = { descriptionDraft = it },
                        modifier = Modifier.width(280.dp),
                        placeholder = "Description",
                        minLines = 2,
                        autoFocus = true,
                        onDismiss = { editingDescription = false },
                        onSubmit = {
                            viewModel.updateMovie(movie.copy(description = descriptionDraft.trim()))
                            editingDescription = false
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    PillButton("Save", compact = true) {
                        viewModel.updateMovie(movie.copy(description = descriptionDraft.trim()))
                        editingDescription = false
                    }
                }
            } else {
                Text(
                    movie.description.ifBlank { "Add a description" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (movie.description.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { descriptionDraft = movie.description; editingDescription = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        MovieStatusSelector(viewModel)
        Spacer(Modifier.width(8.dp))
        AspectRatioSelector(viewModel)

        Spacer(Modifier.weight(1f))

        // Background generations indicator (running count, plus failed jobs awaiting attention).
        val runningCount = viewModel.runningJobs.size
        val failedCount = viewModel.failedJobs.size
        val jobsLabel = when {
            runningCount > 0 -> "⚙️ Generating ($runningCount)"
            failedCount > 0 -> "⚠️ Failed ($failedCount)"
            else -> "⚙️ Generations"
        }
        GhostPillButton(jobsLabel, compact = true) { onShowJobs() }
        Spacer(Modifier.width(8.dp))
        GhostPillButton("🎞️ Renders (${viewModel.renders.size})", compact = true) { onShowRenders() }
        Spacer(Modifier.width(8.dp))
        // Rendering an empty timeline is pointless — the button stays disabled until media lands.
        PillButton("🚀 Render", compact = true, enabled = viewModel.timelineHasClips) { viewModel.startRender() }
    }
}

/** Movie status selector: DRAFT and beyond — the user can move the movie between statuses. */
@Composable
private fun MovieStatusSelector(viewModel: AppViewModel) {
    val movie = viewModel.currentMovie ?: return
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(50)) // clip BEFORE clickable so hover is pill-shaped
                .clickable { expanded = true }
        ) {
            StatusBadge(movie.status)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MovieStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.displayName()) },
                    onClick = {
                        expanded = false
                        viewModel.updateMovie(movie.copy(status = status))
                    }
                )
            }
        }
    }
}

@Composable
private fun AspectRatioSelector(viewModel: AppViewModel) {
    val movie = viewModel.currentMovie ?: return
    var expanded by remember { mutableStateOf(false) }
    Box {
        GhostPillButton(movie.aspectRatio, compact = true) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SUPPORTED_ASPECT_RATIOS.forEach { ratio ->
                DropdownMenuItem(
                    text = { Text(ratio) },
                    onClick = {
                        expanded = false
                        viewModel.updateMovie(movie.copy(aspectRatio = ratio))
                    }
                )
            }
        }
    }
}

/** Modal dialog tracking the live progress of the current render job. */
@Composable
private fun RenderProgressDialog(viewModel: AppViewModel) {
    val jobId = viewModel.renderJobId ?: return
    val event = viewModel.jobProgress[jobId]
    val done = event?.status == JobStatus.COMPLETED
    val failed = event?.status == JobStatus.FAILED

    StudioDialog(
        title = if (done) "Render complete 🎉" else "Rendering movie...",
        onDismiss = { viewModel.clearRenderJob() },
        width = 480.dp
    ) {
        if (!done && !failed) {
            val progress = (event?.progress ?: 0) / 100f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.height(10.dp))
            Text(
                event?.message ?: "Queued on the server...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (failed) {
            Text(
                event?.message ?: "The render failed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            val url = event?.resultUrl
            Text(
                "Your movie was rendered and uploaded to cloud storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (url != null) {
                val fileName = downloadFileNameForRender(viewModel.currentMovie?.title ?: "movie", url)
                Spacer(Modifier.height(12.dp))
                RenderReplayPlayer(url, fileName, videoHeight = 230.dp)
                Spacer(Modifier.height(12.dp))
                DialogActions {
                    PillButton("Done") { viewModel.clearRenderJob() }
                }
            }
        }
    }
}

/**
 * Builds a friendly file name for downloading a rendered movie: the movie's own title sanitized
 * into a safe file name, with the extension taken from the render's URL when present (or "mp4"
 * as a sensible default otherwise).
 */
private fun downloadFileNameForRender(movieTitle: String, url: String): String {
    val urlExtension = url.substringAfterLast('.', "")
        .substringBefore('?')
        .takeIf { it.isNotBlank() && it.length in 1..5 }
    val extension = urlExtension ?: "mp4"
    val baseName = movieTitle
        .take(60)
        .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ' ') c else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")
        .ifBlank { "movie-render" }
    return "$baseName.$extension"
}

/**
 * Small self-contained player used to replay a finished render inside a dialog: the movie frame
 * with its play/pause, fullscreen and download controls laid out in a row *below* the frame (not
 * overlaid on the movie). Playback is deferred until the media has actually loaded ([onReady]), so
 * the movie never tries to start before its first frame is ready.
 */
@Composable
private fun RenderReplayPlayer(url: String, fileName: String, videoHeight: Dp) {
    // Gate playback on load: start paused and only begin once the movie is ready, so we never
    // attempt to play an empty/unloaded <video> element (which shows a black flash / stutter).
    var ready by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableStateOf(0f) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(videoHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            VideoPlayer(
                url = url,
                isPlaying = playing,
                playhead = position,
                onTimeUpdate = { position = it },
                onReady = {
                    // The movie is loaded — start it now (and enable the transport controls).
                    ready = true
                    playing = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(10.dp))
        // Transport, fullscreen and download controls, sitting below the movie frame.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoundIconButton(
                if (playing) "⏸" else "▶",
                contentDescription = "Play / pause",
                enabled = ready,
                size = 38.dp,
                background = MaterialTheme.colorScheme.surfaceVariant,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            ) { playing = !playing }
            Spacer(Modifier.weight(1f))
            RoundIconButton(
                "⛶",
                contentDescription = "Fullscreen",
                size = 34.dp,
                background = MaterialTheme.colorScheme.surfaceVariant,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            ) { requestVideoFullscreen() }
            RoundIconButton(
                "⬇",
                contentDescription = "Download movie",
                size = 34.dp,
                background = MaterialTheme.colorScheme.surfaceVariant,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            ) { triggerDownload(url, fileName) }
        }
    }
}

/** Every past render of this movie: replay or download any of them at any time. */
@Composable
private fun RendersHistoryDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var replayUrl by remember { mutableStateOf<String?>(null) }
    val movieTitle = viewModel.currentMovie?.title ?: "movie"

    StudioDialog(title = "Renders", onDismiss = onDismiss, width = 560.dp, scrollable = false) {
        if (viewModel.renders.isEmpty()) {
            Text(
                "No renders yet. Click Render to produce the movie.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val current = replayUrl
            if (current != null) {
                RenderReplayPlayer(
                    current,
                    downloadFileNameForRender(movieTitle, current),
                    videoHeight = 250.dp
                )
                Spacer(Modifier.height(10.dp))
                GhostPillButton("← All renders", compact = true) { replayUrl = null }
            } else {
                LazyColumn(Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(viewModel.renders, key = { it.id }) { render ->
                        RenderRow(
                            render = render,
                            onReplay = { replayUrl = render.url },
                            onDownload = { triggerDownload(render.url, downloadFileNameForRender(movieTitle, render.url)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderRow(render: RenderRecord, onReplay: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)) // clip BEFORE clickable: rounded hover highlight
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onReplay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🎬", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Render • ${formatDuration(render.durationSeconds)} • ${render.aspectRatio}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                render.url.substringAfterLast('/').take(46),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        GhostPillButton("▶ Play", compact = true) { onReplay() }
        Spacer(Modifier.width(6.dp))
        GhostPillButton("⬇", compact = true) { onDownload() }
    }
}

/**
 * All background generations: running jobs with live progress, plus failed jobs which stay
 * listed (with their failure reason) until the user retries or dismisses them.
 */
@Composable
private fun BackgroundJobsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    StudioDialog(title = "Background generations", onDismiss = onDismiss, width = 540.dp, scrollable = false) {
        if (viewModel.activeJobs.isEmpty()) {
            Text(
                "Nothing is being generated right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.activeJobs, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        progress = viewModel.jobProgress[job.id]?.progress,
                        message = viewModel.jobProgress[job.id]?.message ?: job.error,
                        onRetry = { viewModel.retryJob(job) },
                        onDismissJob = { viewModel.dismissJob(job) }
                    )
                }
            }
        }
        DialogActions {
            GhostPillButton("Refresh", compact = true) { viewModel.refreshActiveJobs() }
            ActionSpacer()
            PillButton("Close", compact = true) { onDismiss() }
        }
    }
}

@Composable
private fun JobRow(
    job: Job,
    progress: Int?,
    message: String?,
    onRetry: () -> Unit,
    onDismissJob: () -> Unit
) {
    val failed = job.status == JobStatus.FAILED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val glyph = when (job.type) {
                JobType.SKELETON -> "🦴"
                JobType.FFMPEG_RENDER -> "🎞️"
                JobType.AI_GEN -> "✨"
            }
            Text(glyph, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                job.label ?: "${job.type.name.lowercase().replaceFirstChar { it.uppercase() }} job",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                job.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        if (!failed) {
            LinearProgressIndicator(
                progress = { ((progress ?: 5) / 100f).coerceIn(0.02f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
        }
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("🔁 Retry", compact = true) { onRetry() }
                GhostPillButton("Dismiss", compact = true) { onDismissJob() }
            }
        }
    }
}
