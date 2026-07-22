package app.moviestudio

/**
 * Pure helpers backing timeline editing (multi-selection, drag, track insert — used by the
 * editor's timeline gestures and the view model). Kept free of any Compose/UI state so they can
 * be unit-tested directly.
 */

/** Adds [clipId] to [current] when absent, or removes it when present (shift-click toggle). */
fun toggledSelection(current: Set<String>, clipId: String): Set<String> =
    if (clipId in current) current - clipId else current + clipId

/** Which edge of a clip a resize-drag should act on. */
enum class ResizeEdge { LEFT, RIGHT }

/**
 * The edge a resize-drag starting at [pointerX] should act on, given the clip's on-screen span
 * ([clipStartX]..[clipEndX]) and how close to an edge a tap counts as a grab ([edgeGrab]).
 *
 * When the clip is small enough that the pointer is within [edgeGrab] of *both* edges at once,
 * a plain nearest-edge test can't tell them apart, so we instead prefer whichever edge can still
 * expand the clip: [trimIn] already at 0 means the left edge can't extend any further left (the
 * source media start is already reached), so the right edge is picked instead, and vice versa
 * when [trimOut] already sits at [trimOutCap] (the asset's length limit; <= 0 means uncapped). If
 * both or neither edge can expand, the left edge wins, matching the tie-break used when the clip
 * is wide enough for the two grab zones to not overlap.
 */
fun pickResizeEdge(
    pointerX: Float,
    clipStartX: Float,
    clipEndX: Float,
    edgeGrab: Float,
    trimIn: Float,
    trimOut: Float,
    trimOutCap: Float
): ResizeEdge? {
    val nearLeft = pointerX - clipStartX <= edgeGrab
    val nearRight = clipEndX - pointerX <= edgeGrab
    if (!nearLeft && !nearRight) return null
    if (nearLeft && nearRight) {
        val leftCanExpand = trimIn > 0f
        val rightCanExpand = trimOutCap <= 0f || trimOut < trimOutCap
        return when {
            !leftCanExpand && rightCanExpand -> ResizeEdge.RIGHT
            !rightCanExpand && leftCanExpand -> ResizeEdge.LEFT
            else -> ResizeEdge.LEFT
        }
    }
    return if (nearLeft) ResizeEdge.LEFT else ResizeEdge.RIGHT
}

/** A clip captured at the start of a drag, with the row it sits on and that row's track type. */
data class MovingClip(
    val clip: Clip,
    val trackIndex: Int,
    val trackType: TrackType
)

/**
 * New positions for a group of clips being dragged together. Every clip shifts by [deltaSeconds]
 * (clamped so no clip starts before 0) and by [rowDelta] track rows. A clip re-homes onto its
 * destination row only when that row exists and shares the clip's own track type; otherwise it
 * keeps its original track. Baselines come from the captured [movers] (not the live timeline),
 * so repeatedly applying this mid-drag never compounds the shift.
 */
fun movedClipGroup(
    movers: List<MovingClip>,
    tracks: List<TrackWithClips>,
    deltaSeconds: Float,
    rowDelta: Int
): List<Clip> = movers.map { mover ->
    val destTrack = tracks.getOrNull(mover.trackIndex + rowDelta)?.track
    val trackId = if (destTrack != null && destTrack.type == mover.trackType) destTrack.id else mover.clip.trackId
    mover.clip.copy(
        timelineStart = (mover.clip.timelineStart + deltaSeconds).coerceAtLeast(0f),
        trackId = trackId
    )
}

/**
 * Where a new track of [type] should land in [tracks]: immediately after the last existing track
 * of the same kind. When none of that type exist yet, the new track is appended at the end.
 *
 * Used by the "＋ Track" menu so a second video track sits under the other videos (not under voice).
 */
fun trackInsertIndex(tracks: List<TrackWithClips>, type: TrackType): Int {
    val lastSameType = tracks.indexOfLast { it.track.type == type }
    return if (lastSameType >= 0) lastSameType + 1 else tracks.size
}

/**
 * Contiguous zIndex values for every existing track after inserting a new one at [insertIndex].
 * Tracks at or after the insertion point shift up by one; earlier tracks keep their row index.
 * The new track itself takes [insertIndex] as its zIndex.
 */
fun shiftedZIndexesAfterInsert(trackCount: Int, insertIndex: Int): List<Int> =
    List(trackCount) { index -> if (index >= insertIndex) index + 1 else index }
