package app.moviestudio.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.moviestudio.AppViewModel
import app.moviestudio.Issue
import app.moviestudio.IssueStatus
import app.moviestudio.Movie
import app.moviestudio.MovieStatus
import app.moviestudio.SUPPORTED_ASPECT_RATIOS
import app.moviestudio.Tip
import app.moviestudio.displayName

/** Home screen: the movie list plus the create-movie flow (which auto-opens the new movie). */
@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }
    // null = All; otherwise only movies with that lifecycle status are shown.
    var statusFilter by remember { mutableStateOf<MovieStatus?>(null) }

    // Load tips and issues up-front so the header buttons can show their unread/open counts
    // without the panels having been opened first.
    LaunchedEffect(Unit) {
        viewModel.refreshTips()
        viewModel.refreshIssues()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎬", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Movie Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            // Report an issue: reveals the right-hand issues panel; badge shows open issues.
            HeaderCountIconButton(
                glyph = "⚠️",
                contentDescription = "Report an issue",
                count = viewModel.issues.count { it.isOpen },
                active = viewModel.issuesPanelExpanded
            ) {
                viewModel.issuesPanelExpanded = !viewModel.issuesPanelExpanded
                if (viewModel.issuesPanelExpanded) viewModel.refreshIssues()
            }
            Spacer(Modifier.width(10.dp))
            // Tips: reveals the right-hand tips panel; badge shows unread tips.
            HeaderCountIconButton(
                glyph = "💡",
                contentDescription = "Tips",
                count = viewModel.tips.count { !it.read },
                active = viewModel.tipsPanelExpanded
            ) {
                viewModel.tipsPanelExpanded = !viewModel.tipsPanelExpanded
                if (viewModel.tipsPanelExpanded) viewModel.refreshTips()
            }
            Spacer(Modifier.width(10.dp))
            PillButton("＋ New Movie") { showCreateDialog = true }
        }

        if (viewModel.isLoading && viewModel.movies.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (viewModel.movies.isEmpty() && viewModel.moviesError != null) {
            // The movie list failed to load: error + retry instead of the empty state.
            ErrorRetryBox(
                message = viewModel.moviesError ?: "Failed to load movies",
                modifier = Modifier.fillMaxSize(),
                onRetry = { viewModel.loadMovies() }
            )
        } else if (viewModel.movies.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎥", fontSize = 44.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No movies yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Create your first movie to start producing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    PillButton("＋ New Movie") { showCreateDialog = true }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // Status filter chips (All + each MovieStatus), matching the library tab pills.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filters: List<MovieStatus?> = listOf(null) + MovieStatus.entries
                    filters.forEach { candidate ->
                        val selected = candidate == statusFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50)) // clip BEFORE clickable: pill hover
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { statusFilter = candidate }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                candidate?.displayName() ?: "All",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                val visibleMovies = if (statusFilter == null) {
                    viewModel.movies
                } else {
                    viewModel.movies.filter { it.status == statusFilter }
                }
                if (visibleMovies.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No movies with this status.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val (activeMovies, archivedMovies) = if (statusFilter == null) {
                        visibleMovies.partition { it.status != MovieStatus.ARCHIVED }
                    } else {
                        visibleMovies to emptyList()
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 260.dp),
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(activeMovies, key = { it.id }) { movie ->
                            MovieCard(
                                movie = movie,
                                onOpen = { viewModel.openMovie(movie) },
                                onDelete = { viewModel.deleteMovie(movie) }
                            )
                        }
                        if (archivedMovies.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "archived-header") {
                                Text(
                                    "Archived",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = if (activeMovies.isEmpty()) 0.dp else 8.dp)
                                )
                            }
                            items(archivedMovies, key = { it.id }) { movie ->
                                MovieCard(
                                    movie = movie,
                                    onOpen = { viewModel.openMovie(movie) },
                                    onDelete = { viewModel.deleteMovie(movie) }
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        // Always composed so they can slide in and out with a width animation. Both panels work
        // in tandem: opening one does not close the other, so they can sit side by side.
        IssuesPanel(viewModel)
        TipsPanel(viewModel)
    }

    if (showCreateDialog) {
        CreateMovieDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, aspect, description ->
                showCreateDialog = false
                viewModel.createMovie(title, aspect, description) // auto-opens once created
            }
        )
    }
}

@Composable
private fun MovieCard(movie: Movie, onOpen: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)) // clip BEFORE clickable so hover has rounded corners
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        // Poster strip: the cover photo when set, otherwise the always-dark placeholder look.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color(0xFF17151D)),
            contentAlignment = Alignment.Center
        ) {
            val cover = movie.coverImageUrl
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = "${movie.title} cover",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("🎞️", fontSize = 40.sp)
            }
            StatusBadge(
                movie.status,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    movie.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                RoundIconButton("🗑", contentDescription = "Delete movie", size = 28.dp) { showDeleteConfirm = true }
            }
            if (movie.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    movie.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatDuration(movie.totalDuration)} • ${movie.aspectRatio}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete movie?",
            message = "\"${movie.title}\" and its timeline will be permanently deleted. " +
                "Library media is kept.",
            confirmLabel = "Delete movie",
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
fun StatusBadge(status: MovieStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        MovieStatus.DRAFT -> Color(0xFF6B6B78) to Color.White
        MovieStatus.IN_PRODUCTION -> Color(0xFF2E7BE9) to Color.White
        MovieStatus.RENDERING -> Color(0xFFF2A33C) to Color(0xFF3A2A00)
        MovieStatus.REVIEW -> Color(0xFF9C6ADE) to Color.White
        MovieStatus.COMPLETED -> Color(0xFF34A853) to Color.White
        MovieStatus.ARCHIVED -> Color(0xFF44414D) to Color(0xFFCBC7D4)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(status.displayName(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CreateMovieDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(SUPPORTED_ASPECT_RATIOS.first()) }
    var description by remember { mutableStateOf("") }

    StudioDialog(title = "New Movie", onDismiss = onDismiss, width = 440.dp) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Title",
            placeholder = "My next masterpiece",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(12.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Description",
            placeholder = "What's this movie about?",
            minLines = 3
        )
        Spacer(Modifier.height(12.dp))
        DropdownSelector(
            label = "Aspect ratio",
            options = SUPPORTED_ASPECT_RATIOS,
            selected = aspect,
            display = { it },
            onSelect = { aspect = it }
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Create Movie", enabled = title.isNotBlank()) { onCreate(title.trim(), aspect, description.trim()) }
        }
    }
}

/**
 * The dashboard's right-hand tips panel: search, create, and browse studio-wide tips (newest
 * first). Toggled by the header's tips icon; slides in and out with a width animation (matching
 * the editor's timeline notes panel).
 */
@Composable
private fun TipsPanel(viewModel: AppViewModel) {
    val width by animateDpAsState(if (viewModel.tipsPanelExpanded) 340.dp else 0.dp)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .clipToBounds()
    ) {
        // Give the content a fixed width via requiredWidth so it ignores the animating box's
        // (smaller) width constraint instead of squashing to fit it. It keeps a 140.dp floor and
        // animates its width up to the full 340.dp as the box grows, anchored to the right edge so
        // it slides in from the side; the box clips whatever hasn't slid into view yet.
        if (width > 0.dp) {
            TipsPanelContent(
                viewModel,
                Modifier.requiredWidth(width.coerceAtLeast((340 / 1.5f).dp)).fillMaxHeight().align(Alignment.CenterEnd)
            )
        }
    }
}

/** The actual tips panel content (header, search, create, and the tip list). */
@Composable
private fun TipsPanelContent(viewModel: AppViewModel, modifier: Modifier) {
    var showCreateForm by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Tip?>(null) }

    // While a search is active the "New Tip" affordance is hidden so it doesn't distract.
    val searching = viewModel.tipsSearchQuery.isNotBlank()

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Tips",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RoundIconButton("✕", contentDescription = "Close tips", size = 32.dp) {
                viewModel.tipsPanelExpanded = false
            }
        }
        Spacer(Modifier.height(12.dp))

        // Search across tip titles and content.
        StudioTextField(
            value = viewModel.tipsSearchQuery,
            onValueChange = { viewModel.searchTips(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search tips",
            singleLine = true,
            autoFocus = false,
            aiGenerate = null
        )
        Spacer(Modifier.height(12.dp))

        // Create a new tip inline. Hidden while searching.
        if (!searching) {
            if (showCreateForm) {
                TipCreateForm(
                    onCancel = { showCreateForm = false },
                    onCreate = { title, content ->
                        viewModel.createTip(title, content)
                        showCreateForm = false
                    }
                )
            } else {
                PillButton("＋ Tip", modifier = Modifier.fillMaxWidth()) { showCreateForm = true }
            }
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        // All tips, newest first.
        when {
            viewModel.tipsError != null -> {
                ErrorRetryBox(
                    message = viewModel.tipsError ?: "Failed to load tips",
                    modifier = Modifier.fillMaxWidth(),
                    onRetry = { viewModel.refreshTips() }
                )
            }
            viewModel.tips.isEmpty() -> {
                Text(
                    if (viewModel.tipsSearchQuery.isBlank()) "No tips yet. Add your first tip."
                    else "No tips match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lazyItems(viewModel.tips, key = { it.id }) { tip ->
                        TipCard(
                            tip = tip,
                            onToggleRead = { viewModel.toggleTipRead(tip) },
                            onEdit = { editTarget = tip }
                        )
                    }
                }
            }
        }
    }

    // Long-pressing a tip opens the edit dialog (which can also delete the tip).
    editTarget?.let { tip ->
        TipEditDialog(
            tip = tip,
            onSave = { title, content ->
                viewModel.updateTip(tip, title, content)
                editTarget = null
            },
            onDelete = {
                viewModel.deleteTip(tip.id)
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }
}

/** Inline create-a-tip form used inside [TipsPanel]. */
@Composable
private fun TipCreateForm(onCancel: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Title",
            placeholder = "Tip title",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Details",
            placeholder = "What's the tip?",
            minLines = 3
        )
        Spacer(Modifier.height(10.dp))
        Row {
            GhostPillButton("Cancel", modifier = Modifier.weight(1f)) { onCancel() }
            Spacer(Modifier.width(8.dp))
            PillButton("Save", modifier = Modifier.weight(1f), enabled = title.isNotBlank()) {
                onCreate(title.trim(), content.trim())
            }
        }
    }
}

/**
 * A single tip in the tips panel list. Tapping toggles read/unread; long-pressing opens the edit
 * dialog. Read tips are visually dimmed and show a check mark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TipCard(tip: Tip, onToggleRead: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)) // clip BEFORE clickable so hover has rounded corners
            .background(
                if (tip.read) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .combinedClickable(onClick = onToggleRead, onLongClick = onEdit)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💡", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                tip.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (tip.read) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (tip.read) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "✓ Read",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (tip.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                tip.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Edit dialog opened by long-pressing a tip: change its title/content, or delete it outright.
 */
@Composable
private fun TipEditDialog(
    tip: Tip,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(tip.title) }
    var content by remember { mutableStateOf(tip.content) }
    StudioDialog(title = "Edit Tip", onDismiss = onDismiss, width = 440.dp) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Title",
            placeholder = "Tip title",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(12.dp))
        StudioTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Details",
            placeholder = "What's the tip?",
            minLines = 3
        )
        DialogActions {
            PillButton(
                "🗑 Delete",
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) { onDelete() }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save", enabled = title.isNotBlank()) { onSave(title.trim(), content.trim()) }
        }
    }
}

/**
 * A round header icon button with a small count badge in its top-right corner. The badge is only
 * drawn when [count] is greater than zero (so a settled state shows just the icon).
 */
@Composable
private fun HeaderCountIconButton(
    glyph: String,
    contentDescription: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit
) {
    Box {
        RoundIconButton(
            glyph,
            contentDescription = contentDescription,
            background = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            size = 40.dp,
            onClick = onClick
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = count.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * The dashboard's right-hand issues panel: search, report, and browse reported issues (open first,
 * then newest). Toggled by the header's ⚠️ icon; slides in and out with a width animation, so it
 * can sit alongside the tips panel.
 */
@Composable
private fun IssuesPanel(viewModel: AppViewModel) {
    val width by animateDpAsState(if (viewModel.issuesPanelExpanded) 340.dp else 0.dp)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .clipToBounds()
    ) {
        // Mirrors TipsPanel: a fixed-width content anchored to the right edge that the animating
        // box clips as it slides in.
        if (width > 0.dp) {
            IssuesPanelContent(
                viewModel,
                Modifier.requiredWidth(width.coerceAtLeast((340 / 1.5f).dp)).fillMaxHeight().align(Alignment.CenterEnd)
            )
        }
    }
}

/** The actual issues panel content (header, search, report, and the issue list). */
@Composable
private fun IssuesPanelContent(viewModel: AppViewModel, modifier: Modifier) {
    var showCreateForm by remember { mutableStateOf(false) }
    var detailTarget by remember { mutableStateOf<Issue?>(null) }

    // While a search is active the "Report an issue" affordance is hidden so it doesn't distract.
    val searching = viewModel.issuesSearchQuery.isNotBlank()

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Issues",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RoundIconButton("✕", contentDescription = "Close issues", size = 32.dp) {
                viewModel.issuesPanelExpanded = false
            }
        }
        Spacer(Modifier.height(12.dp))

        // Search across issue titles and descriptions.
        StudioTextField(
            value = viewModel.issuesSearchQuery,
            onValueChange = { viewModel.searchIssues(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search issues",
            singleLine = true,
            autoFocus = false,
            aiGenerate = null
        )
        Spacer(Modifier.height(12.dp))

        // Report a new issue inline. Hidden while searching.
        if (!searching) {
            if (showCreateForm) {
                IssueCreateForm(
                    onCancel = { showCreateForm = false },
                    onCreate = { title, description ->
                        viewModel.createIssue(title, description)
                        showCreateForm = false
                    }
                )
            } else {
                PillButton("＋ Report an issue", modifier = Modifier.fillMaxWidth()) { showCreateForm = true }
            }
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        // All issues, open first then newest.
        when {
            viewModel.issuesError != null -> {
                ErrorRetryBox(
                    message = viewModel.issuesError ?: "Failed to load issues",
                    modifier = Modifier.fillMaxWidth(),
                    onRetry = { viewModel.refreshIssues() }
                )
            }
            viewModel.issues.isEmpty() -> {
                Text(
                    if (viewModel.issuesSearchQuery.isBlank()) "No issues reported. Report your first issue."
                    else "No issues match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lazyItems(viewModel.issues, key = { it.id }) { issue ->
                        IssueCard(issue = issue, onOpen = { detailTarget = issue })
                    }
                }
            }
        }
    }

    // Tapping an issue opens its detail dialog (edit details, change status, open/close, delete).
    detailTarget?.let { issue ->
        IssueDetailDialog(
            issue = issue,
            onSave = { title, description, status, isOpen ->
                viewModel.updateIssue(issue, title, description, status, isOpen)
                detailTarget = null
            },
            onDelete = {
                viewModel.deleteIssue(issue.id)
                detailTarget = null
            },
            onDismiss = { detailTarget = null }
        )
    }
}

/** Inline report-an-issue form used inside [IssuesPanel]. */
@Composable
private fun IssueCreateForm(onCancel: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Title",
            placeholder = "What's going on?",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Details",
            placeholder = "Describe the issue",
            minLines = 3
        )
        Spacer(Modifier.height(10.dp))
        Row {
            GhostPillButton("Cancel", modifier = Modifier.weight(1f)) { onCancel() }
            Spacer(Modifier.width(8.dp))
            PillButton("Report", modifier = Modifier.weight(1f), enabled = title.isNotBlank()) {
                onCreate(title.trim(), description.trim())
            }
        }
    }
}

/**
 * A single issue in the issues panel list. Tapping opens the detail dialog. Its status and
 * open/closed state are shown as chips; closed issues are visually dimmed.
 */
@Composable
private fun IssueCard(issue: Issue, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)) // clip BEFORE clickable so hover has rounded corners
            .background(
                if (issue.isOpen) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                issue.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (issue.isOpen) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenClosedChip(issue.isOpen)
            Spacer(Modifier.width(6.dp))
            IssueStatusChip(issue.status)
        }
        if (issue.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                issue.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Small colored pill showing an issue's workflow status. */
@Composable
private fun IssueStatusChip(status: IssueStatus) {
    val (bg, fg) = when (status) {
        IssueStatus.NEW -> Color(0xFF2E7BE9) to Color.White
        IssueStatus.IN_PROGRESS -> Color(0xFFF2A33C) to Color(0xFF3A2A00)
        IssueStatus.RESOLVED -> Color(0xFF34A853) to Color.White
        IssueStatus.WONT_FIX -> Color(0xFF6B6B78) to Color.White
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(status.displayName(), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small pill showing whether an issue is open or closed. */
@Composable
private fun OpenClosedChip(isOpen: Boolean) {
    val bg = if (isOpen) Color(0xFF34A853) else Color(0xFF44414D)
    val fg = if (isOpen) Color.White else Color(0xFFCBC7D4)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(if (isOpen) "Open" else "Closed", color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Detail dialog opened by tapping an issue: edit its title/description, change the workflow status,
 * open/close it, or delete it outright.
 */
@Composable
private fun IssueDetailDialog(
    issue: Issue,
    onSave: (String, String, IssueStatus, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(issue.title) }
    var description by remember { mutableStateOf(issue.description) }
    var status by remember { mutableStateOf(issue.status) }
    var isOpen by remember { mutableStateOf(issue.isOpen) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    StudioDialog(title = "Issue", onDismiss = onDismiss, width = 480.dp) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Title",
            placeholder = "What's going on?",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(12.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Details",
            placeholder = "Describe the issue",
            minLines = 4
        )
        Spacer(Modifier.height(12.dp))
        DropdownSelector(
            label = "Status",
            options = IssueStatus.entries,
            selected = status,
            display = { it.displayName() },
            onSelect = { status = it }
        )
        Spacer(Modifier.height(12.dp))
        // Open/close the issue. The current state is echoed next to the toggle button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "State",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                OpenClosedChip(isOpen)
            }
            if (isOpen) {
                GhostPillButton("Close issue") { isOpen = false }
            } else {
                PillButton("Reopen issue") { isOpen = true }
            }
        }
        DialogActions {
            PillButton(
                "🗑 Delete",
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) { showDeleteConfirm = true }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save", enabled = title.isNotBlank()) {
                onSave(title.trim(), description.trim(), status, isOpen)
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete issue?",
            message = "\"${issue.title}\" will be permanently deleted.",
            confirmLabel = "Delete issue",
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

/** Formats seconds as m:ss (or h:mm:ss for long movies). */
fun formatDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
