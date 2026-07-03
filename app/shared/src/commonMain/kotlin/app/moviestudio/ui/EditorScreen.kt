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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.moviestudio.FilmStatus
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.RenderRecord
import app.moviestudio.SUPPORTED_ASPECT_RATIOS
import app.moviestudio.VideoPlayer
import app.moviestudio.displayName
import app.moviestudio.triggerDownload

/**
 * The movie editor: header (title, status, aspect, jobs, render), preview + library row, clip
 * inspector and the timeline. Hosts the render dialogs and the background-generations panel.
 */
@Composable
fun EditorScreen(viewModel: AppViewModel) {
    var showJobsPanel by remember { mutableStateOf(false) }
    var showRenders by remember { mutableStateOf(false) }

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
                .padding(horizontal = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                PreviewPanel(viewModel, Modifier.weight(1f).fillMaxWidth())
                val selection = viewModel.findClip(viewModel.selectedClipId)
                if (selection != null) {
                    Spacer(Modifier.height(8.dp))
                    ClipInspector(viewModel, selection.first, selection.second)
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton("←", contentDescription = "Back to movies", size = 34.dp) { viewModel.goToDashboard() }
        Spacer(Modifier.width(8.dp))

        if (editingTitle) {
            StudioTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                modifier = Modifier.width(280.dp),
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            PillButton("Save", compact = true, enabled = titleDraft.isNotBlank()) {
                viewModel.updateMovie(movie.copy(title = titleDraft.trim()))
                editingTitle = false
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

        Spacer(Modifier.width(10.dp))
        MovieStatusSelector(viewModel)
        Spacer(Modifier.width(8.dp))
        AspectRatioSelector(viewModel)

        Spacer(Modifier.weight(1f))

        // Background generations indicator.
        val activeCount = viewModel.activeJobs.size
        GhostPillButton(
            if (activeCount > 0) "⚙️ Generating ($activeCount)" else "⚙️ Generations",
            compact = true
        ) { onShowJobs() }
        Spacer(Modifier.width(8.dp))
        GhostPillButton("🎞️ Renders (${viewModel.renders.size})", compact = true) { onShowRenders() }
        Spacer(Modifier.width(8.dp))
        PillButton("🚀 Render", compact = true) { viewModel.startRender() }
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
            FilmStatus.entries.forEach { status ->
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
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    RenderReplayPlayer(url)
                }
                Spacer(Modifier.height(12.dp))
                DialogActions {
                    GhostPillButton("⬇ Download") { triggerDownload(url, "movie-render.mp4") }
                    ActionSpacer()
                    PillButton("Done") { viewModel.clearRenderJob() }
                }
            }
        }
    }
}

/** Small self-contained player used to replay a finished render inside a dialog. */
@Composable
private fun RenderReplayPlayer(url: String) {
    var playing by remember(url) { mutableStateOf(true) }
    var position by remember(url) { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        VideoPlayer(
            url = url,
            isPlaying = playing,
            playhead = position,
            onTimeUpdate = { position = it },
            modifier = Modifier.fillMaxSize()
        )
        RoundIconButton(
            if (playing) "⏸" else "▶",
            size = 38.dp,
            background = Color.Black.copy(alpha = 0.55f),
            tint = Color.White
        ) { playing = !playing }
    }
}

/** Every past render of this movie: replay or download any of them at any time. */
@Composable
private fun RendersHistoryDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var replayUrl by remember { mutableStateOf<String?>(null) }

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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    RenderReplayPlayer(current)
                }
                Spacer(Modifier.height(10.dp))
                GhostPillButton("← All renders", compact = true) { replayUrl = null }
            } else {
                LazyColumn(Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(viewModel.renders, key = { it.id }) { render ->
                        RenderRow(
                            render = render,
                            onReplay = { replayUrl = render.url },
                            onDownload = { triggerDownload(render.url, "movie-render-${render.id.take(6)}.mp4") }
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

/** All background generations (skeletons and every asset type) currently in flight. */
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
                    JobRow(job, viewModel.jobProgress[job.id]?.progress, viewModel.jobProgress[job.id]?.message)
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
private fun JobRow(job: Job, progress: Int?, message: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ((progress ?: 5) / 100f).coerceIn(0.02f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
