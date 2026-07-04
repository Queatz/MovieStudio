package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Film
import app.moviestudio.FilmStatus
import app.moviestudio.SUPPORTED_ASPECT_RATIOS
import app.moviestudio.displayName

/** Home screen: the movie list plus the create-movie flow (which auto-opens the new movie). */
@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(viewModel.movies, key = { it.id }) { movie ->
                    MovieCard(
                        movie = movie,
                        onOpen = { viewModel.openMovie(movie) },
                        onDelete = { viewModel.deleteMovie(movie) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateMovieDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, aspect ->
                showCreateDialog = false
                viewModel.createMovie(title, aspect) // auto-opens once created
            }
        )
    }
}

@Composable
private fun MovieCard(movie: Film, onOpen: () -> Unit, onDelete: () -> Unit) {
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
        // Poster strip: always-dark preview look.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color(0xFF17151D)),
            contentAlignment = Alignment.Center
        ) {
            Text("🎞️", fontSize = 40.sp)
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
fun StatusBadge(status: FilmStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        FilmStatus.DRAFT -> Color(0xFF6B6B78) to Color.White
        FilmStatus.IN_PRODUCTION -> Color(0xFF2E7BE9) to Color.White
        FilmStatus.RENDERING -> Color(0xFFF2A33C) to Color(0xFF3A2A00)
        FilmStatus.REVIEW -> Color(0xFF9C6ADE) to Color.White
        FilmStatus.COMPLETED -> Color(0xFF34A853) to Color.White
        FilmStatus.ARCHIVED -> Color(0xFF44414D) to Color(0xFFCBC7D4)
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
private fun CreateMovieDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(SUPPORTED_ASPECT_RATIOS.first()) }
    val titleFocus = remember { FocusRequester() }

    // Autofocus the title input so the user can start typing right away.
    LaunchedEffect(Unit) {
        runCatching { titleFocus.requestFocus() }
    }

    StudioDialog(title = "New Movie", onDismiss = onDismiss, width = 440.dp) {
        StudioTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
            label = "Title",
            placeholder = "My next masterpiece",
            singleLine = true
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
            PillButton("Create Movie", enabled = title.isNotBlank()) { onCreate(title.trim(), aspect) }
        }
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
