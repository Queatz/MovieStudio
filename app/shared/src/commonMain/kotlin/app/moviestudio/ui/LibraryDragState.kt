package app.moviestudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.moviestudio.Asset

/**
 * Shared state for dragging a library asset onto the timeline. The library panel starts the drag
 * and moves the pointer, the timeline canvas registers its bounds and a position→seconds
 * resolver, and the editor screen draws the floating drag ghost. All coordinates are in root
 * (composition) space.
 */
object LibraryDragState {
    /** The asset currently being dragged out of the library, or null when no drag is active. */
    var draggedAsset by mutableStateOf<Asset?>(null)

    /** Current pointer position in root coordinates (drives the drag ghost). */
    var pointerPosition by mutableStateOf(Offset.Zero)

    /** The timeline canvas bounds in root coordinates (registered by the timeline). */
    var timelineBounds by mutableStateOf<Rect?>(null)

    /**
     * Converts a root-coordinate position into timeline seconds, or null when the position is
     * outside the timeline canvas. Registered by the timeline while it is on screen.
     */
    var resolveDropSeconds: ((Offset) -> Float?)? = null

    /** True while an active drag hovers over the timeline canvas (drop-target highlight). */
    val isOverTimeline: Boolean
        get() = draggedAsset != null && timelineBounds?.contains(pointerPosition) == true

    /** Ends the drag (whether dropped or cancelled). */
    fun clear() {
        draggedAsset = null
    }
}
