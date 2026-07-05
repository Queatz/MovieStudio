package app.moviestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.Clip
import app.moviestudio.MovieTimeline
import app.moviestudio.TimelineNote
import app.moviestudio.Track
import app.moviestudio.TrackType
import app.moviestudio.TransitionType
import app.moviestudio.calculatedDuration
import app.moviestudio.parseEffectsConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val RULER_HEIGHT = 26f
private const val TRACK_HEIGHT = 52f
private const val TRACK_GAP = 6f
private const val EDGE_GRAB = 10f

// Snapping: while dragging/resizing a clip, if its start or end comes within this many seconds of
// another clip's start or end, it snaps onto that edge.
private const val SNAP_THRESHOLD_SECONDS = 1f

// Note markers: blue pills with the note's text, sitting in the lower band of the ruler.
private const val NOTE_PILL_TOP = 13f
private const val NOTE_PILL_HEIGHT = 13f
private const val NOTE_PILL_MAX_WIDTH = 170f
private val noteTextStyle = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)

/** The single-line label shown inside a note's blue marker pill. */
private fun noteLabel(note: TimelineNote): String = note.text.substringBefore('\n').take(24)

/** Width of a note's marker pill: its measured label plus padding, capped. */
private fun notePillWidth(textMeasurer: androidx.compose.ui.text.TextMeasurer, label: String): Float {
    val measured = textMeasurer.measure(label, style = noteTextStyle, maxLines = 1)
    return min(measured.size.width + 10f, NOTE_PILL_MAX_WIDTH)
}

/** What a drag that started on the timeline is currently doing. */
private sealed interface DragSession {
    data object Seek : DragSession
    class MoveClip(
        val clip: Clip,
        val sourceTrackType: TrackType,
        val duration: Float,
        val snapEdges: FloatArray,
        var newStart: Float,
        var snappedStart: Float,
        var newTrackId: String
    ) : DragSession
    class ResizeLeft(
        val clip: Clip,
        val maxTrim: Float,
        val snapEdges: FloatArray,
        var newStart: Float,
        var newTrimIn: Float,
        var snappedStart: Float,
        var snappedTrimIn: Float
    ) : DragSession
    class ResizeRight(
        val clip: Clip,
        val maxTrimOut: Float,
        val snapEdges: FloatArray,
        var newTrimOut: Float,
        var snappedTrimOut: Float
    ) : DragSession
}

/**
 * All snap-candidate times on the timeline: every other clip's start and end (excluding the clip
 * being dragged). Collected once when a drag starts and reused for every pointer move, so we never
 * re-walk the whole timeline mid-gesture. The returned array is sorted so [nearestSnap] can binary
 * search it.
 */
private fun collectSnapEdges(timeline: MovieTimeline?, excludeClipId: String): FloatArray {
    if (timeline == null) return FloatArray(0)
    val edges = ArrayList<Float>()
    timeline.tracks.forEach { trackWithClips ->
        trackWithClips.clips.forEach { clip ->
            if (clip.id != excludeClipId) {
                edges.add(clip.timelineStart)
                edges.add(clip.timelineStart + (clip.trimOut - clip.trimIn))
            }
        }
    }
    return edges.toFloatArray().also { it.sort() }
}

/**
 * The candidate edge closest to [value], or null if none is within [SNAP_THRESHOLD_SECONDS].
 * [edges] must be sorted ascending; the nearest edge is found with a binary search so this stays
 * cheap even for long timelines.
 */
private fun nearestSnap(edges: FloatArray, value: Float): Float? {
    if (edges.isEmpty()) return null
    var lo = 0
    var hi = edges.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (edges[mid] < value) lo = mid + 1 else hi = mid
    }
    var best: Float? = null
    var bestDist = SNAP_THRESHOLD_SECONDS
    if (lo < edges.size) {
        val d = abs(edges[lo] - value)
        if (d <= bestDist) { bestDist = d; best = edges[lo] }
    }
    if (lo - 1 >= 0) {
        val d = abs(edges[lo - 1] - value)
        if (d <= bestDist) { bestDist = d; best = edges[lo - 1] }
    }
    return best
}

/**
 * Snapped start for a clip being moved: snap whichever of its start or end edge is nearest to
 * another clip's edge (within [SNAP_THRESHOLD_SECONDS]); otherwise keep [start] unchanged.
 */
private fun snapMovedStart(edges: FloatArray, start: Float, duration: Float): Float {
    val startSnap = nearestSnap(edges, start)
    val endSnap = nearestSnap(edges, start + duration)
    val startDist = if (startSnap != null) abs(startSnap - start) else Float.MAX_VALUE
    val endDist = if (endSnap != null) abs(endSnap - (start + duration)) else Float.MAX_VALUE
    return when {
        startSnap != null && startDist <= endDist -> max(0f, startSnap)
        endSnap != null -> max(0f, endSnap - duration)
        else -> start
    }
}

/** The track row index at the given canvas [y] position (may be out of the tracks' range). */
private fun trackIndexAt(y: Float): Int = ((y - RULER_HEIGHT - TRACK_GAP) / (TRACK_HEIGHT + TRACK_GAP)).toInt()

/**
 * The timeline editor (always dark, regardless of theme): AI generate bar, track headers,
 * a canvas with the ruler / clips / playhead, and the scroll + zoom controls.
 *
 * Gesture notes: the pointer handlers are installed with `pointerInput(Unit)` and read all
 * changing state through [rememberUpdatedState], so fast mouse moves and mid-drag state updates
 * never restart (cancel) an in-progress drag.
 */
@Composable
fun TimelinePanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val timeline = viewModel.timeline

    // Double-clicking a clip opens its asset in the same details dialog the library uses.
    var detailAsset by remember { mutableStateOf<Asset?>(null) }
    var sequencerAsset by remember { mutableStateOf<Asset?>(null) }
    var showSequencer by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF17151C))
            .padding(10.dp)
    ) {
        SkeletonGenerateBar(viewModel)
        Spacer(Modifier.height(8.dp))

        if (timeline == null && viewModel.timelineError != null) {
            // The timeline failed to load: error + retry instead of an empty editor.
            ErrorRetryBox(
                message = viewModel.timelineError ?: "Failed to load the timeline",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRetry = { viewModel.refreshTimeline() }
            )
        } else {
            // Vertical scroll so every track stays reachable when they overflow the panel
            // height. Headers and the canvas share one scroll state so they move together.
            val trackScroll = rememberScrollState()
            val density = LocalDensity.current
            val trackCount = timeline?.tracks?.size ?: 0
            val contentHeightDp = with(density) {
                (RULER_HEIGHT + TRACK_GAP + trackCount * (TRACK_HEIGHT + TRACK_GAP)).toDp()
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Grow with the tracks, but never shrink below the visible viewport so a few
                // tracks still fill the editor as before.
                val rowHeightDp = maxOf(contentHeightDp, maxHeight)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(trackScroll)
                        .height(rowHeightDp)
                ) {
                    TrackHeaderColumn(viewModel)
                    Spacer(Modifier.width(6.dp))
                    TimelineCanvas(
                        viewModel,
                        onOpenAsset = { detailAsset = it },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Scroll + zoom controls side by side, preceded by the split action.
        val duration = max((timeline?.calculatedDuration() ?: 0.0).toFloat(), 10f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Split the selected clip into two at the current playhead position.
            GhostPillButton(
                "✂ Split",
                compact = true,
                enabled = viewModel.canSplitSelectedClip()
            ) { viewModel.splitSelectedClip() }
            Spacer(Modifier.width(10.dp))
            Text("↔", color = Color(0xFF8D89A0), fontSize = 13.sp)
            Slider(
                value = viewModel.scrollOffset,
                onValueChange = { viewModel.scrollOffset = it },
                valueRange = 0f..max(duration - 2f, 0.1f),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text("🔍", color = Color(0xFF8D89A0), fontSize = 13.sp)
            Slider(
                value = viewModel.zoomScale,
                onValueChange = { viewModel.zoomScale = it.coerceIn(4f, 120f) },
                valueRange = 4f..120f,
                modifier = Modifier.width(170.dp).padding(horizontal = 8.dp)
            )
        }
    }

    // Double-clicking a clip opens the asset details dialog (same as the library panel).
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
    if (showSequencer) {
        SequencerDialog(viewModel, sequencerAsset) { showSequencer = false }
    }
}

/** Prompt bar above the timeline: generates skeleton items at the current playhead position. */
@Composable
private fun SkeletonGenerateBar(viewModel: AppViewModel) {
    var prompt by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StudioTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.weight(1f),
            placeholder = "" +
                    "Describe a scene or a whole movie starting at ${formatDuration(viewModel.playhead.toDouble())}...",
            singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        PillButton("✨ Generate", compact = true, enabled = prompt.isNotBlank()) {
            viewModel.generateSkeleton(prompt.trim())
            prompt = ""
        }
        Spacer(Modifier.width(8.dp))
        AddTrackMenu(viewModel)
    }
}

/**
 * The old "Add track" button is now a typed menu: it lets the user pick which kind of track to
 * add (video / music / voice) instead of blindly appending a video track.
 */
@Composable
private fun AddTrackMenu(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GhostPillButton("＋ Track", compact = true) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                TrackType.VIDEO to "🎬 Video track",
                TrackType.MUSIC to "🎵 Music track",
                TrackType.VOICE to "🎙️ Voice track"
            ).forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        viewModel.addTrack(type)
                    }
                )
            }
        }
    }
}

/** The emoji + default label for a track type (used when the track has no custom name). */
private fun trackGlyphAndLabel(type: TrackType): Pair<String, String> = when (type) {
    TrackType.VIDEO -> "🎬" to "Video"
    TrackType.MUSIC -> "🎵" to "Music"
    TrackType.VOICE -> "🎙️" to "Voice"
    TrackType.EFFECTS -> "✨" to "Effects"
}

/**
 * Left gutter: one header per track. Headers line up exactly with the canvas rows (the canvas
 * draws in raw pixels, so the px constants are converted through the density). Click a header
 * to rename the track, drag it vertically to reorder tracks, and use 🗑 to delete (with a
 * confirmation).
 */
@Composable
private fun TrackHeaderColumn(viewModel: AppViewModel) {
    val tracks = viewModel.timeline?.tracks ?: emptyList()
    // The canvas draws with pixel constants; convert them to dp so headers align pixel-perfectly.
    val density = LocalDensity.current
    val rulerGapDp = with(density) { (RULER_HEIGHT + TRACK_GAP).toDp() }
    val trackHeightDp = with(density) { TRACK_HEIGHT.toDp() }
    val trackGapDp = with(density) { TRACK_GAP.toDp() }

    var renameTarget by remember { mutableStateOf<Track?>(null) }
    var deleteTarget by remember { mutableStateOf<Track?>(null) }
    var draggingTrackId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Column(Modifier.width(92.dp)) {
        Spacer(Modifier.height(rulerGapDp))
        tracks.forEachIndexed { index, trackWithClips ->
            val track = trackWithClips.track
            val (glyph, defaultLabel) = trackGlyphAndLabel(track.type)
            val label = track.name?.takeIf { it.isNotBlank() } ?: defaultLabel
            val isDragging = draggingTrackId == track.id
            Row(
                modifier = Modifier
                    .height(trackHeightDp)
                    .fillMaxWidth()
                    .offset { IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0) }
                    .zIndex(if (isDragging) 1f else 0f)
                    .clip(RoundedCornerShape(8.dp)) // clip BEFORE clickable: rounded hover/press
                    .background(if (isDragging) Color(0xFF322D40) else Color(0xFF221F2A))
                    .clickable { renameTarget = track }
                    // Drag the header vertically to reorder tracks.
                    .pointerInput(track.id, index, tracks.size) {
                        detectDragGestures(
                            onDragStart = {
                                draggingTrackId = track.id
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                            },
                            onDragEnd = {
                                val rowStride = TRACK_HEIGHT + TRACK_GAP
                                val moved = (dragOffsetY / rowStride).roundToInt()
                                val target = (index + moved).coerceIn(0, tracks.lastIndex)
                                draggingTrackId = null
                                dragOffsetY = 0f
                                if (target != index) viewModel.reorderTrack(index, target)
                            },
                            onDragCancel = {
                                draggingTrackId = null
                                dragOffsetY = 0f
                            }
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(glyph, fontSize = 13.sp)
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    color = Color(0xFFB9B4C7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                RoundIconButton("🗑", size = 20.dp, tint = Color(0xFF6E687D)) {
                    deleteTarget = track
                }
            }
            Spacer(Modifier.height(trackGapDp))
        }
    }

    renameTarget?.let { track ->
        RenameTrackDialog(
            track = track,
            onRename = { name ->
                viewModel.renameTrack(track, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { track ->
        val (_, defaultLabel) = trackGlyphAndLabel(track.type)
        val label = track.name?.takeIf { it.isNotBlank() } ?: defaultLabel
        ConfirmDialog(
            title = "Delete track?",
            message = "\"$label\" and all clips on it will be removed from the timeline. " +
                "Library media is kept.",
            confirmLabel = "Delete track",
            onConfirm = { viewModel.deleteTrack(track.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** Simple rename dialog for a track (blank resets to the type's default label). */
@Composable
private fun RenameTrackDialog(track: Track, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val (_, defaultLabel) = trackGlyphAndLabel(track.type)
    var name by remember(track.id) { mutableStateOf(track.name ?: "") }
    StudioDialog(title = "Rename track", onDismiss = onDismiss, width = 380.dp) {
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Track name",
            placeholder = defaultLabel,
            singleLine = true
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Save") { onRename(name) }
        }
    }
}

@Composable
private fun TimelineCanvas(
    viewModel: AppViewModel,
    onOpenAsset: (Asset) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // Latest state, readable from inside the long-lived pointerInput(Unit) handlers.
    val timelineState by rememberUpdatedState(viewModel.timeline)
    val zoomState by rememberUpdatedState(viewModel.zoomScale)
    val scrollState by rememberUpdatedState(viewModel.scrollOffset)
    val assetsState by rememberUpdatedState(viewModel.libraryAssets)
    val notesState by rememberUpdatedState(viewModel.timelineNotes)

    var dragSession by remember { mutableStateOf<DragSession?>(null) }

    // Gently follow the playhead while playing.
    var canvasWidth by remember { mutableStateOf(0f) }
    LaunchedEffect(viewModel.playhead, viewModel.isPlaying) {
        if (viewModel.isPlaying && canvasWidth > 0f) {
            val visibleSeconds = canvasWidth / viewModel.zoomScale
            val playhead = viewModel.playhead
            if (playhead > viewModel.scrollOffset + visibleSeconds * 0.85f || playhead < viewModel.scrollOffset) {
                viewModel.scrollOffset = max(0f, playhead - visibleSeconds * 0.15f)
            }
        }
    }

    fun timeAt(x: Float): Float = scrollState + x / zoomState

    // Register this canvas as the library drag-and-drop target: bounds for hit testing plus a
    // root-position → drop-target resolver used when a library card is dropped here.
    DisposableEffect(Unit) {
        onDispose {
            LibraryDragState.timelineBounds = null
            LibraryDragState.resolveDropTarget = null
        }
    }

    fun clipHit(offset: Offset): Pair<Clip, Int>? {
        val currentTimeline = timelineState ?: return null
        val trackIndex = ((offset.y - RULER_HEIGHT - TRACK_GAP) / (TRACK_HEIGHT + TRACK_GAP)).toInt()
        if (trackIndex < 0 || trackIndex >= currentTimeline.tracks.size) return null
        val time = timeAt(offset.x)
        val clip = currentTimeline.tracks[trackIndex].clips.lastOrNull { clip ->
            time >= clip.timelineStart && time <= clip.timelineStart + (clip.trimOut - clip.trimIn)
        } ?: return null
        return clip to trackIndex
    }

    // The note whose marker pill sits under the pointer (topmost pill wins).
    fun noteHit(offset: Offset): TimelineNote? {
        if (offset.y < NOTE_PILL_TOP || offset.y > RULER_HEIGHT) return null
        return notesState.lastOrNull { note ->
            val x = (note.atSeconds.toFloat() - scrollState) * zoomState
            offset.x >= x && offset.x <= x + notePillWidth(textMeasurer, noteLabel(note))
        }
    }

    Canvas(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                LibraryDragState.timelineBounds = bounds
                LibraryDragState.resolveDropTarget = { position ->
                    if (bounds.contains(position)) {
                        var seconds = max(0f, scrollState + (position.x - bounds.left) / zoomState)
                        // Holding Ctrl while dropping snaps to the nearest whole second.
                        if (KeyModifierState.ctrlDown) seconds = seconds.roundToInt().toFloat()
                        val trackIndex = trackIndexAt(position.y - bounds.top)
                        val trackId = timelineState?.tracks?.getOrNull(trackIndex)?.track?.id
                        TimelineDropTarget(seconds, trackId)
                    } else {
                        null
                    }
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1D1A24))
            // Mouse-wheel: horizontal wheel scrubs the playhead, vertical wheel scrolls tracks.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
                            // Horizontal wheel scrubs the playhead; vertical wheel is left
                            // unconsumed so the timeline's vertical scroll can move through tracks.
                            if (delta.x != 0f) {
                                viewModel.seekBy(delta.x)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // Tap: focus a note marker or seek from the ruler, select/deselect clips below it.
            // Double-tap: open the clip's asset in the details dialog (like the library panel).
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (offset.y > RULER_HEIGHT) {
                            val hit = clipHit(offset)
                            val clip = hit?.first
                            if (clip != null) {
                                viewModel.selectedClipId = clip.id
                                assetsState.firstOrNull { it.id == clip.assetId }?.let(onOpenAsset)
                            }
                        }
                    },
                    onPress = { offset ->
                        if (offset.y <= RULER_HEIGHT) {
                            val note = noteHit(offset)
                            if (note != null) {
                                // Clicking the already-selected note un-selects it and closes the panel.
                                if (viewModel.selectedNoteId == note.id) {
                                    viewModel.selectedNoteId = null
                                    viewModel.notesPanelExpanded = false
                                } else {
                                    viewModel.focusNote(note)
                                }
                            } else {
                                viewModel.seek(timeAt(offset.x), allowPastEnd = true)
                            }
                        } else {
                            val hit = clipHit(offset)
                            viewModel.selectedClipId = hit?.first?.id
                        }
                    }
                )
            }
            // Drag: seek scrubbing, clip move and edge resize. Installed once (pointerInput(Unit))
            // so state changes mid-drag can never cancel the gesture.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragSession = if (offset.y <= RULER_HEIGHT) {
                            viewModel.seek(timeAt(offset.x), allowPastEnd = true)
                            DragSession.Seek
                        } else {
                            val hit = clipHit(offset)
                            if (hit == null) {
                                null
                            } else {
                                val (clip, trackIndex) = hit
                                viewModel.selectedClipId = clip.id
                                val clipStartX = (clip.timelineStart - scrollState) * zoomState
                                val clipEndX = (clip.timelineStart + (clip.trimOut - clip.trimIn) - scrollState) * zoomState
                                val asset = assetsState.firstOrNull { it.id == clip.assetId }
                                val mediaCap = mediaTrimCap(asset)
                                val trackType = timelineState?.tracks?.getOrNull(trackIndex)?.track?.type
                                // Gather the snap targets once up front so mid-drag moves stay cheap.
                                val snapEdges = collectSnapEdges(timelineState, clip.id)
                                when {
                                    offset.x - clipStartX <= EDGE_GRAB ->
                                        DragSession.ResizeLeft(
                                            clip, mediaCap, snapEdges,
                                            clip.timelineStart, clip.trimIn,
                                            clip.timelineStart, clip.trimIn
                                        )
                                    clipEndX - offset.x <= EDGE_GRAB ->
                                        DragSession.ResizeRight(
                                            clip, mediaCap, snapEdges,
                                            clip.trimOut, clip.trimOut
                                        )
                                    else -> DragSession.MoveClip(
                                        clip,
                                        trackType ?: TrackType.VIDEO,
                                        clip.trimOut - clip.trimIn,
                                        snapEdges,
                                        clip.timelineStart,
                                        clip.timelineStart,
                                        clip.trackId
                                    )
                                }
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dt = dragAmount.x / zoomState
                        when (val session = dragSession) {
                            is DragSession.Seek -> viewModel.seek(timeAt(change.position.x), allowPastEnd = true)
                            is DragSession.MoveClip -> {
                                session.newStart = max(0f, session.newStart + dt)
                                // Holding Ctrl snaps the clip's start to the nearest whole second;
                                // holding Alt disables snapping entirely; otherwise snap whichever
                                // edge (start/end) is nearest a clip edge.
                                session.snappedStart = when {
                                    KeyModifierState.ctrlDown -> max(0f, session.newStart.roundToInt().toFloat())
                                    KeyModifierState.altDown -> session.newStart
                                    else -> snapMovedStart(session.snapEdges, session.newStart, session.duration)
                                }
                                // Dragging vertically re-homes the clip onto another track of
                                // the same type.
                                val targetIndex = trackIndexAt(change.position.y)
                                val targetTrack = timelineState?.tracks?.getOrNull(targetIndex)?.track
                                if (targetTrack != null && targetTrack.type == session.sourceTrackType) {
                                    session.newTrackId = targetTrack.id
                                }
                                viewModel.updateClipLocal(
                                    session.clip.copy(
                                        timelineStart = session.snappedStart,
                                        trackId = session.newTrackId
                                    )
                                )
                            }
                            is DragSession.ResizeLeft -> {
                                val minTrim = 0f
                                val maxTrim = session.clip.trimOut - 0.25f
                                val proposedTrim = (session.newTrimIn + dt).coerceIn(minTrim, maxTrim)
                                val delta = proposedTrim - session.newTrimIn
                                session.newTrimIn = proposedTrim
                                session.newStart = max(0f, session.newStart + delta)
                                // Snap the moving left edge to a nearby clip edge (Ctrl or Alt disables it).
                                var start = session.newStart
                                var trimIn = session.newTrimIn
                                if (!KeyModifierState.ctrlDown && !KeyModifierState.altDown) {
                                    val snap = nearestSnap(session.snapEdges, start)
                                    if (snap != null && snap >= 0f) {
                                        val snapTrim = trimIn + (snap - start)
                                        if (snapTrim in minTrim..maxTrim) {
                                            start = snap
                                            trimIn = snapTrim
                                        }
                                    }
                                }
                                session.snappedStart = start
                                session.snappedTrimIn = trimIn
                                viewModel.updateClipLocal(
                                    session.clip.copy(timelineStart = start, trimIn = trimIn)
                                )
                            }
                            is DragSession.ResizeRight -> {
                                session.newTrimOut = (session.newTrimOut + dt)
                                    .coerceAtLeast(session.clip.trimIn + 0.25f)
                                if (session.maxTrimOut > 0f) {
                                    session.newTrimOut = session.newTrimOut.coerceAtMost(session.maxTrimOut)
                                }
                                // Snap the moving right edge to a nearby clip edge (Ctrl or Alt disables it).
                                var trimOut = session.newTrimOut
                                if (!KeyModifierState.ctrlDown && !KeyModifierState.altDown) {
                                    val end = session.clip.timelineStart + (session.newTrimOut - session.clip.trimIn)
                                    val snap = nearestSnap(session.snapEdges, end)
                                    if (snap != null) {
                                        val snapTrimOut = session.clip.trimIn + (snap - session.clip.timelineStart)
                                        val maxOut = if (session.maxTrimOut > 0f) session.maxTrimOut else Float.MAX_VALUE
                                        if (snapTrimOut >= session.clip.trimIn + 0.25f && snapTrimOut <= maxOut) {
                                            trimOut = snapTrimOut
                                        }
                                    }
                                }
                                session.snappedTrimOut = trimOut
                                viewModel.updateClipLocal(session.clip.copy(trimOut = trimOut))
                            }
                            null -> {}
                        }
                    },
                    onDragEnd = {
                        commitDrag(viewModel, dragSession)
                        dragSession = null
                    },
                    onDragCancel = {
                        commitDrag(viewModel, dragSession)
                        dragSession = null
                    }
                )
            }
    ) {
        canvasWidth = size.width
        val currentTimeline = timelineState ?: return@Canvas
        drawRuler(this, textMeasurer, zoomState, scrollState)
        drawTracks(this, textMeasurer, currentTimeline, assetsState, zoomState, scrollState, viewModel.selectedClipId)
        drawNoteMarkers(this, textMeasurer, notesState, zoomState, scrollState, viewModel.selectedNoteId)
        drawPlayhead(this, viewModel.playhead, zoomState, scrollState)

        // Drop-target feedback while a library card is dragged over the timeline.
        if (LibraryDragState.isOverTimeline) {
            drawRoundRect(
                Color(0xFF8F7BFF),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f)
            )
            val dropX = (LibraryDragState.pointerPosition.x - (LibraryDragState.timelineBounds?.left ?: 0f))
                .coerceIn(0f, size.width)
            drawLine(
                Color(0xFF8F7BFF),
                Offset(dropX, 0f),
                Offset(dropX, size.height),
                strokeWidth = 2f
            )
        }
    }
}

/** How far a clip's right edge may be trimmed out, based on its asset's real media length. */
private fun mediaTrimCap(asset: Asset?): Float {
    if (asset == null) return 0f
    // Images, text and description-only assets can be stretched freely.
    val stretchy = asset.isDescriptionOnly ||
        asset.type == app.moviestudio.AssetType.IMAGE ||
        asset.type == app.moviestudio.AssetType.TEXT
    return if (stretchy || asset.durationSeconds <= 0.0) 0f else asset.durationSeconds.toFloat()
}

private fun commitDrag(viewModel: AppViewModel, session: DragSession?) {
    when (session) {
        is DragSession.MoveClip ->
            viewModel.updateClip(
                session.clip.copy(timelineStart = session.snappedStart, trackId = session.newTrackId)
            )
        is DragSession.ResizeLeft ->
            viewModel.updateClip(session.clip.copy(timelineStart = session.snappedStart, trimIn = session.snappedTrimIn))
        is DragSession.ResizeRight ->
            viewModel.updateClip(session.clip.copy(trimOut = session.snappedTrimOut))
        else -> {}
    }
}

private fun drawRuler(
    scope: DrawScope,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    zoom: Float,
    scroll: Float
) = with(scope) {
    drawRect(Color(0xFF262231), size = Size(size.width, RULER_HEIGHT))
    val step = when {
        zoom >= 40f -> 1
        zoom >= 14f -> 5
        zoom >= 7f -> 10
        else -> 30
    }
    val firstTick = (scroll / step).toInt() * step
    var t = firstTick
    while ((t - scroll) * zoom < size.width) {
        val x = (t - scroll) * zoom
        if (x >= 0f) {
            drawLine(Color(0xFF544E66), Offset(x, RULER_HEIGHT - 8f), Offset(x, RULER_HEIGHT))
            val label = formatDuration(t.toDouble())
            drawText(
                textMeasurer,
                label,
                topLeft = Offset(x + 3f, 2f),
                style = TextStyle(color = Color(0xFF8D89A0), fontSize = 9.sp)
            )
        }
        t += step
    }
}

private fun drawTracks(
    scope: DrawScope,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    timeline: MovieTimeline,
    assets: List<Asset>,
    zoom: Float,
    scroll: Float,
    selectedClipId: String?
) = with(scope) {
    timeline.tracks.forEachIndexed { index, trackWithClips ->
        val top = RULER_HEIGHT + TRACK_GAP + index * (TRACK_HEIGHT + TRACK_GAP)
        drawRect(Color(0xFF211E2A), topLeft = Offset(0f, top), size = Size(size.width, TRACK_HEIGHT))

        val baseColor = when (trackWithClips.track.type) {
            TrackType.VIDEO -> Color(0xFF4C7DD0)
            TrackType.MUSIC -> Color(0xFF3FA573)
            TrackType.VOICE -> Color(0xFFC98A3D)
            TrackType.EFFECTS -> Color(0xFF9C6ADE)
        }

        trackWithClips.clips.forEach { clip ->
            val startX = (clip.timelineStart - scroll) * zoom
            val widthPx = (clip.trimOut - clip.trimIn) * zoom
            if (startX + widthPx < 0f || startX > size.width) return@forEach

            val asset = assets.firstOrNull { it.id == clip.assetId }
            val descriptionOnly = asset?.isDescriptionOnly ?: false
            val fill = if (descriptionOnly) baseColor.copy(alpha = 0.45f) else baseColor
            val corner = CornerRadius(7f, 7f)

            drawRoundRect(
                fill,
                topLeft = Offset(startX, top + 4f),
                size = Size(max(widthPx, 3f), TRACK_HEIGHT - 8f),
                cornerRadius = corner
            )
            if (descriptionOnly) {
                drawRoundRect(
                    baseColor,
                    topLeft = Offset(startX, top + 4f),
                    size = Size(max(widthPx, 3f), TRACK_HEIGHT - 8f),
                    cornerRadius = corner,
                    style = Stroke(width = 1.5f)
                )
            }
            if (clip.id == selectedClipId) {
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(startX - 1f, top + 3f),
                    size = Size(max(widthPx, 3f) + 2f, TRACK_HEIGHT - 6f),
                    cornerRadius = corner,
                    style = Stroke(width = 2.5f)
                )
            }

            // Edge handles for the selected clip.
            if (clip.id == selectedClipId && widthPx > 26f) {
                drawRect(Color.White.copy(alpha = 0.75f), Offset(startX + 2f, top + 12f), Size(3f, TRACK_HEIGHT - 24f))
                drawRect(Color.White.copy(alpha = 0.75f), Offset(startX + widthPx - 5f, top + 12f), Size(3f, TRACK_HEIGHT - 24f))
            }

            if (widthPx > 34f) {
                val effects = parseEffectsConfig(clip.effectsConfig)
                val transitionBadge = if (effects.transition != null && effects.transition!!.type != TransitionType.NONE) "⇄ " else ""
                val prefix = if (descriptionOnly) "📝 " else ""
                val label = prefix + transitionBadge + (asset?.description ?: asset?.aiPrompt ?: "Clip")
                drawText(
                    textMeasurer,
                    label,
                    topLeft = Offset(startX + 7f, top + 8f),
                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    size = Size(max(widthPx - 14f, 8f), 16f)
                )
                drawText(
                    textMeasurer,
                    formatDuration((clip.trimOut - clip.trimIn).toDouble()),
                    topLeft = Offset(startX + 7f, top + TRACK_HEIGHT - 22f),
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 9.sp),
                    maxLines = 1,
                    size = Size(max(widthPx - 14f, 8f), 14f)
                )
            }
        }
    }
}

/**
 * Timeline notes as blue markers: a pill with the note's text in the ruler band plus a vertical
 * guide line through the tracks at the note's position. Tapping a pill focuses the note in the
 * notes side panel; the selected note is outlined and its guide line brightened.
 */
private fun drawNoteMarkers(
    scope: DrawScope,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    notes: List<TimelineNote>,
    zoom: Float,
    scroll: Float,
    selectedNoteId: String?
) = with(scope) {
    notes.forEach { note ->
        val x = (note.atSeconds.toFloat() - scroll) * zoom
        if (x < -NOTE_PILL_MAX_WIDTH || x > size.width) return@forEach
        val selected = note.id == selectedNoteId

        // Vertical guide line through the tracks.
        drawLine(
            NoteBlue.copy(alpha = if (selected) 0.85f else 0.4f),
            Offset(x, RULER_HEIGHT),
            Offset(x, size.height),
            strokeWidth = if (selected) 2f else 1.5f
        )

        // The blue marker pill with the note's text.
        val label = noteLabel(note)
        val pillWidth = notePillWidth(textMeasurer, label)
        drawRoundRect(
            if (selected) NoteBlue else NoteBlue.copy(alpha = 0.85f),
            topLeft = Offset(x, NOTE_PILL_TOP),
            size = Size(pillWidth, NOTE_PILL_HEIGHT),
            cornerRadius = CornerRadius(4f, 4f)
        )
        if (selected) {
            drawRoundRect(
                Color.White,
                topLeft = Offset(x - 1f, NOTE_PILL_TOP - 1f),
                size = Size(pillWidth + 2f, NOTE_PILL_HEIGHT + 2f),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(width = 1.5f)
            )
        }
        drawText(
            textMeasurer,
            label,
            topLeft = Offset(x + 5f, NOTE_PILL_TOP + 1f),
            style = noteTextStyle,
            maxLines = 1,
            size = Size(max(pillWidth - 8f, 4f), NOTE_PILL_HEIGHT)
        )
    }
}

private fun drawPlayhead(scope: DrawScope, playhead: Float, zoom: Float, scroll: Float) = with(scope) {
    val x = (playhead - scroll) * zoom
    if (x < 0f || x > size.width) return
    drawLine(Color(0xFFFF5A6E), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    // Grab handle in the ruler.
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x - 6f, 0f)
        lineTo(x + 6f, 0f)
        lineTo(x, 11f)
        close()
    }
    drawPath(path, Color(0xFFFF5A6E))
}
