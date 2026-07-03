package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moviestudio.ui.DashboardScreen
import app.moviestudio.ui.EditorScreen
import app.moviestudio.ui.TextInputFocusTracker
import kotlinx.coroutines.delay

/**
 * Movie Studio root. Hosts the dashboard (movie list) and the editor, applies the studio theme
 * (auto light/dark) and owns two global behaviors:
 * - the space-bar play/pause shortcut (active only while no text input is focused), and
 * - the transient error toast.
 */
@Composable
fun App() {
    AppTheme {
        val viewModel: AppViewModel = viewModel { AppViewModel() }
        val rootFocus = remember { FocusRequester() }

        // Keep keyboard focus on the root whenever no text field holds it, so the space-bar
        // shortcut always works after the user finishes typing.
        LaunchedEffect(TextInputFocusTracker.focusedFields, viewModel.currentScreen) {
            if (!TextInputFocusTracker.anyFocused) {
                runCatching { rootFocus.requestFocus() }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Space toggles playback (including restart at the end) while not typing.
                    if (event.key == Key.Spacebar &&
                        event.type == KeyEventType.KeyDown &&
                        !TextInputFocusTracker.anyFocused &&
                        viewModel.currentScreen == Screen.EDITOR
                    ) {
                        viewModel.togglePlayback()
                        true
                    } else {
                        false
                    }
                }
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (viewModel.currentScreen) {
                Screen.DASHBOARD -> DashboardScreen(viewModel)
                Screen.EDITOR -> EditorScreen(viewModel)
            }

            ErrorToast(viewModel)
        }
    }
}

/** Transient bottom-center toast surfacing [AppViewModel.errorMessage]; auto-dismisses. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ErrorToast(viewModel: AppViewModel) {
    val message = viewModel.errorMessage ?: return
    LaunchedEffect(message) {
        delay(5000)
        if (viewModel.errorMessage == message) viewModel.errorMessage = null
    }
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(24.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 6.dp
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
