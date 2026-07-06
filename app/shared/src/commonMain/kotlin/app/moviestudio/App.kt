package app.moviestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import app.moviestudio.ui.DashboardScreen
import app.moviestudio.ui.EditorScreen
import app.moviestudio.ui.KeyModifierState
import app.moviestudio.ui.StudioToast
import app.moviestudio.ui.TextInputFocusTracker
import kotlinx.coroutines.delay

/**
 * Movie Studio root. Hosts the dashboard (movie list) and the editor, applies the studio theme
 * (auto light/dark) and owns the global behaviors:
 * - the space-bar play/pause shortcut (active only while no text input is focused),
 * - arrow-key playhead stepping (1s, or 1min with Ctrl) and ESC leaving fullscreen playback,
 * - Delete/Backspace removing the selected timeline clip,
 * - Ctrl-state tracking for snap-dragging, and
 * - the transient error toast.
 */
@Composable
fun App() {
    AppTheme {
        // Coil loads network images (AsyncImage) on every platform through a Ktor engine, which is
        // auto-detected from the per-target ktor-client dependency. Registered once here so the
        // whole app (preview panel, thumbnails, ...) can render URLs with plain Compose components.
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory()) }
                .build()
        }
        val viewModel: AppViewModel = viewModel { AppViewModel() }
        val rootFocus = remember { FocusRequester() }
        // Whether the root (or anything inside its subtree) currently holds keyboard focus.
        // Clicking a non-text surface — the timeline canvas or the preview stage — clears focus
        // off the root without touching any text field, which used to silently kill the space-bar
        // shortcut. Tracking this lets us re-grab focus the instant it is lost to nothing.
        var rootHasFocus by remember { mutableStateOf(false) }

        // A structural signature of the timeline that only changes when tracks or clips are added
        // or removed (never merely moved/resized — those keep the counts constant). Adding a
        // track/clip goes through a dropdown menu or a drag-and-drop that steals keyboard focus
        // from the root; keying the focus effect on this signature re-acquires focus right after
        // such a change so the Delete/Space/arrow shortcuts (and the Ctrl/Alt snap modifiers, which
        // are fed from the root key handler) keep working without needing a page reload.
        val timelineStructure by remember {
            derivedStateOf {
                val t = viewModel.timeline
                (t?.tracks?.size ?: 0) to (t?.tracks?.sumOf { it.clips.size } ?: 0)
            }
        }

        // Keep keyboard focus on the root whenever no text field holds it, so the space-bar
        // shortcut (and the other global shortcuts) always work after the user finishes typing or
        // after an interaction — such as adding a timeline item — momentarily stole focus.
        LaunchedEffect(
            TextInputFocusTracker.focusedFields,
            viewModel.currentScreen,
            timelineStructure,
            rootHasFocus
        ) {
            // Re-acquire focus whenever no text field is being edited AND the root subtree does
            // not already hold focus — covering both the initial focus grab and focus lost to a
            // click on the timeline canvas / preview stage. Running from a LaunchedEffect means
            // the focus state has settled, so a click that focuses a text field (anyFocused==true)
            // is left alone rather than having focus yanked back to the root.
            if (!TextInputFocusTracker.anyFocused && !rootHasFocus) {
                runCatching { rootFocus.requestFocus() }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { rootHasFocus = it.hasFocus }
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Pointer gestures (e.g. Ctrl-snap drags) read the live Ctrl state from here;
                    // holding Alt disables clip-to-clip snapping.
                    KeyModifierState.ctrlDown = event.isCtrlPressed
                    KeyModifierState.altDown = event.isAltPressed
                    KeyModifierState.shiftDown = event.isShiftPressed
                    val editingText = TextInputFocusTracker.anyFocused
                    val inEditor = viewModel.currentScreen == Screen.EDITOR
                    when {
                        // Space toggles playback (including restart at the end) while not typing.
                        event.key == Key.Spacebar && event.type == KeyEventType.KeyDown &&
                            !editingText && inEditor -> {
                            viewModel.togglePlayback()
                            true
                        }
                        // Arrow keys step the playhead by a second — or a minute with Ctrl held.
                        (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                            event.type == KeyEventType.KeyDown && !editingText && inEditor -> {
                            val step = if (event.isCtrlPressed) 60f else 1f
                            viewModel.seekBy(if (event.key == Key.DirectionLeft) -step else step)
                            true
                        }
                        // Delete/Backspace removes every selected timeline clip while not typing.
                        (event.key == Key.Delete || event.key == Key.Backspace) &&
                            event.type == KeyEventType.KeyDown && !editingText && inEditor &&
                            viewModel.selectedClipIds.isNotEmpty() -> {
                            viewModel.deleteSelectedClips()
                            true
                        }
                        // ESC leaves fullscreen playback.
                        event.key == Key.Escape && event.type == KeyEventType.KeyDown &&
                            viewModel.isFullscreenPlayback -> {
                            viewModel.exitFullscreenPlayback()
                            true
                        }
                        else -> false
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
    StudioToast(
        message = message,
        modifier = Modifier.padding(24.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    )
}
