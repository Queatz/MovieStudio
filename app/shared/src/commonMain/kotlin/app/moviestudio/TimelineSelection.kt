package app.moviestudio

/**
 * Pure helpers backing timeline multi-selection (used by the editor's timeline gestures and the
 * view model). Kept free of any Compose/UI state so they can be unit-tested directly.
 */

/** Adds [clipId] to [current] when absent, or removes it when present (shift-click toggle). */
fun toggledSelection(current: Set<String>, clipId: String): Set<String> =
    if (clipId in current) current - clipId else current + clipId

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
