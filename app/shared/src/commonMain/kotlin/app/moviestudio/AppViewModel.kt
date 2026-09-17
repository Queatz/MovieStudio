package app.moviestudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

enum class Screen {
    DASHBOARD,
    EDITOR
}

/** Audio-playback pool key used for Voice Library voice previews ("sample" buttons). */
private const val VOICE_SAMPLE_KEY = "voice-library-sample"

/**
 * Live progress of a device upload in flight.
 *
 * @property label a short human-readable description of what is being uploaded.
 * @property fraction the completion ratio, from 0f (just started) to 1f (finished).
 */
data class UploadState(
    val label: String,
    val fraction: Float
)

/**
 * Signals the library panel that a background generation just produced [asset]. The panel scrolls
 * to the top when [asset] matches its current filters. The incrementing [id] makes every
 * completion a distinct signal, so back-to-back generations each re-trigger the scroll even when
 * the asset object is otherwise equal.
 */
data class GeneratedAssetSignal(
    val asset: Asset,
    val id: Long
)

/**
 * Central state holder for the studio: movie list, the open movie's timeline, the global asset
 * library, saved characters/scenes/voices, render history and live background-job tracking
 * (WebSocket push with polling fallback).
 */
class AppViewModel : ViewModel() {
    private val eventJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------------------ navigation
    var currentScreen by mutableStateOf(Screen.DASHBOARD)
        private set

    private val _movies = mutableStateOf<List<Movie>>(emptyList())

    /** The dashboard's movie list, with archived movies always sorted to the end. */
    var movies: List<Movie>
        get() = _movies.value
        private set(value) {
            _movies.value = value.sortedBy { it.status == MovieStatus.ARCHIVED }
        }

    var currentMovie by mutableStateOf<Movie?>(null)
        private set
    var timeline by mutableStateOf<MovieTimeline?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    // Per-component load failures, so major surfaces can show an inline "error + retry" state
    // instead of only a transient toast.
    var moviesError by mutableStateOf<String?>(null)
        private set
    var libraryError by mutableStateOf<String?>(null)
        private set
    var timelineError by mutableStateOf<String?>(null)
        private set
    var notesError by mutableStateOf<String?>(null)
        private set
    var documentsError by mutableStateOf<String?>(null)
        private set

    // --------------------------------------------------------------------------------- library
    var libraryAssets by mutableStateOf<List<Asset>>(emptyList())
        private set

    /**
     * The most recent asset produced by a finished background generation, wrapped with a
     * monotonically increasing id. The library panel observes this to scroll to the top whenever
     * the fresh asset matches the filters currently applied there.
     */
    var generatedAssetSignal by mutableStateOf<GeneratedAssetSignal?>(null)
        private set
    private var generatedAssetSeq = 0L

    var libraryFilter by mutableStateOf<AssetType?>(null)
    var characters by mutableStateOf<List<Character>>(emptyList())
        private set
    var scenes by mutableStateOf<List<Scene>>(emptyList())
        private set
    var visualStyles by mutableStateOf<List<VisualStyle>>(emptyList())
        private set
    var voiceOptions by mutableStateOf(VoiceOptions())
        private set

    /** The voice whose preview is currently loading/playing in the Voice Library (null = none). */
    var samplingVoiceId by mutableStateOf<String?>(null)
        private set
    var renders by mutableStateOf<List<RenderRecord>>(emptyList())
        private set

    // ------------------------------------------------------------------------------------ tips
    /** Studio-wide tips (reusable workflow advice), newest first. */
    var tips by mutableStateOf<List<Tip>>(emptyList())
        private set

    /** True while the dashboard tips side panel is open. */
    var tipsPanelExpanded by mutableStateOf(false)

    /** The current tips search query (empty = show all). */
    var tipsSearchQuery by mutableStateOf("")
        private set

    var tipsError by mutableStateOf<String?>(null)
        private set

    // ---------------------------------------------------------------------------------- issues
    /** Reported issues (studio-wide), open first then newest. */
    var issues by mutableStateOf<List<Issue>>(emptyList())
        private set

    /** True while the dashboard issues side panel is open. */
    var issuesPanelExpanded by mutableStateOf(false)

    /** The current issues search query (empty = show all). */
    var issuesSearchQuery by mutableStateOf("")
        private set

    var issuesError by mutableStateOf<String?>(null)
        private set

    // -------------------------------------------------------------------------- timeline notes
    /** Text-only notes pinned to timeline positions (the plot builder), sorted by time. */
    var timelineNotes by mutableStateOf<List<TimelineNote>>(emptyList())
        private set

    /** True while the expandable notes side panel is open. */
    var notesPanelExpanded by mutableStateOf(false)

    private val _selectedNoteId = mutableStateOf<String?>(null)

    /** The note highlighted in the panel (also set by clicking a marker on the timeline). */
    var selectedNoteId: String?
        get() = _selectedNoteId.value
        set(value) {
            _selectedNoteId.value = value
            // Notes and timeline clips are mutually exclusive selections: selecting a note clears
            // any selected clips so a following Delete/Backspace acts on the note, not a clip.
            if (value != null) selectedClipIds = emptySet()
        }

    /**
     * Set from the global key handler when Delete/Backspace is pressed with a note selected. The
     * notes panel observes it to raise the note delete-confirmation dialog, then resets it.
     */
    var noteDeletionRequested by mutableStateOf(false)

    // ------------------------------------------------------------------------------- documents
    /** Rich-text documents of the open movie (script, research...), forming a tree. */
    var documents by mutableStateOf<List<MovieDocument>>(emptyList())
        private set

    /** True while the movie documents panel is open (the preview area becomes the editor). */
    var documentsPanelExpanded by mutableStateOf(false)

    /** The document open in the editor, or null for the documents empty state. */
    var selectedDocumentId by mutableStateOf<String?>(null)

    /** A just-created document whose editor should open with its title input shown and focused. */
    var newlyCreatedDocumentId by mutableStateOf<String?>(null)

    // -------------------------------------------------------------------------------- playback
    var playhead by mutableStateOf(0f)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    private val _zoomScale = mutableStateOf(Movie.DEFAULT_TIMELINE_ZOOM)

    /**
     * Timeline zoom (pixels per second). Changing it schedules a debounced save to the open movie
     * so the zoom is restored the next time the movie is opened (see [scheduleTimelineViewPersist]).
     */
    var zoomScale: Float
        get() = _zoomScale.value
        set(value) {
            if (_zoomScale.value != value) {
                _zoomScale.value = value
                scheduleTimelineViewPersist()
            }
        }

    private val _scrollOffset = mutableStateOf(Movie.DEFAULT_TIMELINE_OFFSET)

    /**
     * Timeline horizontal scroll (in seconds). Changing it schedules a debounced save to the open
     * movie so the offset is restored the next time the movie is opened (see
     * [scheduleTimelineViewPersist]).
     */
    var scrollOffset: Float
        get() = _scrollOffset.value
        set(value) {
            if (_scrollOffset.value != value) {
                _scrollOffset.value = value
                scheduleTimelineViewPersist()
            }
        }

    // Debounces persisting the timeline scroll/zoom so dragging a slider (or the playhead
    // auto-follow while playing) doesn't spam the server with movie updates.
    private var timelineViewPersistJob: CoroutineJob? = null

    private val _selectedClipIds = mutableStateOf<Set<String>>(emptySet())

    /**
     * Every selected timeline clip (multi-select). Moving and deleting act on the whole set;
     * shift-clicking a clip toggles it in and out (see [toggleClipSelection]).
     */
    var selectedClipIds: Set<String>
        get() = _selectedClipIds.value
        private set(value) {
            _selectedClipIds.value = value
            // Notes and timeline clips are mutually exclusive selections: selecting a clip clears
            // any selected note so a following Delete/Backspace acts on the clip, not the note.
            if (value.isNotEmpty()) selectedNoteId = null
        }

    /**
     * The single selected clip, or null when zero or several clips are selected. Reading it keys
     * the clip inspector, so the inspector naturally disappears once more than one clip is
     * selected. Assigning replaces the whole selection with just that clip (or clears it when
     * null), which keeps every existing single-select call site working.
     */
    var selectedClipId: String?
        get() = selectedClipIds.singleOrNull()
        set(value) { selectedClipIds = setOfNotNull(value) }

    /** Adds or removes [clipId] from the multi-selection (shift-click on the timeline). */
    fun toggleClipSelection(clipId: String) {
        selectedClipIds = toggledSelection(selectedClipIds, clipId)
    }

    /** True while the movie plays in the distraction-free fullscreen mode (ESC exits). */
    var isFullscreenPlayback by mutableStateOf(false)
        private set

    /**
     * True while the preview stage renders through the WebGL compositor instead of the default
     * (DOM `<video>` + Compose) method. Toggled from the transport controls; only offered on
     * platforms where [isWebGLPreviewSupported] is true.
     */
    var previewUseWebGL by mutableStateOf(false)

    // ------------------------------------------------------------------------------------ jobs
    /** Active (PENDING/RUNNING) plus FAILED background jobs across the studio, newest first. */
    var activeJobs by mutableStateOf<List<Job>>(emptyList())
        private set

    /** Jobs currently in flight (PENDING/RUNNING). */
    val runningJobs: List<Job>
        get() = activeJobs.filter { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }

    /** Failed jobs kept in the panel until the user retries or dismisses them. */
    val failedJobs: List<Job>
        get() = activeJobs.filter { it.status == JobStatus.FAILED }

    /** Latest live progress event per job id. */
    var jobProgress by mutableStateOf<Map<String, JobProgressEvent>>(emptyMap())
        private set

    /** The render job currently shown in the render dialog (if any). */
    var renderJobId by mutableStateOf<String?>(null)
        private set

    /**
     * Live state of the device upload currently in flight (file pick, reference image, voice
     * sample or mic recording), or null when nothing is uploading. Drives the upload progress
     * bars shown across the studio. [fraction] is the completion ratio (0f..1f).
     */
    var uploadState by mutableStateOf<UploadState?>(null)
        private set

    private var wsConnection: JobEventsConnection? = null
    private var playbackTicker: CoroutineJob? = null
    private var jobPoller: CoroutineJob? = null

    init {
        loadMovies()
        connectJobEventsSocket()
        startJobPolling()
        refreshActiveJobs()
    }

    // ==================================================================================== movies

    fun loadMovies() {
        viewModelScope.launch {
            isLoading = true
            moviesError = null
            try {
                movies = NetworkService.getMovies()
            } catch (e: Exception) {
                moviesError = "Failed to load movies: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Creates a movie with three default tracks, then opens it right away. */
    fun createMovie(title: String, aspectRatio: String, description: String = "") {
        viewModelScope.launch {
            isLoading = true
            try {
                val movie = Movie(
                    id = generateId(),
                    title = title.ifBlank { "Untitled Movie" },
                    totalDuration = 0.0,
                    status = MovieStatus.DRAFT,
                    createdAt = 0, // stamped by the server
                    aspectRatio = aspectRatio,
                    description = description
                )
                val saved = NetworkService.createMovie(movie)
                for ((index, type) in listOf(TrackType.VIDEO, TrackType.MUSIC, TrackType.VOICE).withIndex()) {
                    NetworkService.createTrack(
                        saved.id,
                        Track(id = generateId(), movieId = saved.id, type = type, zIndex = index)
                    )
                }
                movies = listOf(saved) + movies
                openMovie(saved) // auto-open the new movie
            } catch (e: Exception) {
                errorMessage = "Failed to create movie: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteMovie(movie: Movie) {
        viewModelScope.launch {
            try {
                NetworkService.deleteMovie(movie.id)
                movies = movies.filter { it.id != movie.id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete movie: ${e.message}"
            }
        }
    }

    fun openMovie(movie: Movie) {
        currentMovie = movie
        currentScreen = Screen.EDITOR
        playhead = 0f
        isPlaying = false
        // Restore the movie's last timeline view (scroll offset + zoom). Assigned to the backing
        // state directly and any pending save is cancelled, so reloading never re-persists them.
        timelineViewPersistJob?.cancel()
        timelineViewPersistJob = null
        _scrollOffset.value = movie.lastTimelineOffset
        _zoomScale.value = movie.lastTimelineZoom
        selectedClipId = null
        selectedNoteId = null
        timelineNotes = emptyList()
        documentsPanelExpanded = false
        selectedDocumentId = null
        documents = emptyList()
        refreshTimeline()
        refreshNotes()
        refreshDocuments()
        refreshLibrary()
        refreshCharactersAndScenes()
        refreshVisualStyles()
        refreshVoices()
        refreshRenders()
        refreshActiveJobs()
    }

    fun goToDashboard() {
        isPlaying = false
        // Flush any pending (debounced) timeline view save before the movie is cleared, so a
        // last-moment scroll/zoom change made just before leaving isn't lost.
        flushTimelineViewPersist()
        currentScreen = Screen.DASHBOARD
        currentMovie = null
        timeline = null
        selectedClipId = null
        selectedNoteId = null
        selectedDocumentId = null
        documentsPanelExpanded = false
        loadMovies()
    }

    /** Persists movie metadata changes (title, status, aspect ratio). */
    fun updateMovie(updated: Movie) {
        viewModelScope.launch {
            try {
                val saved = NetworkService.updateMovie(updated)
                currentMovie = saved
                movies = movies.map { if (it.id == saved.id) saved else it }
                timeline = timeline?.copy(movie = saved)
            } catch (e: Exception) {
                errorMessage = "Failed to update movie: ${e.message}"
            }
        }
    }

    /**
     * Persists the current timeline view (scroll offset + zoom) onto the open movie, debounced so
     * dragging a slider — or the playhead auto-follow while playing — doesn't spam the server. The
     * movie is only saved when the values actually differ from what it already stores.
     */
    private fun scheduleTimelineViewPersist() {
        val movieId = currentMovie?.id ?: return
        timelineViewPersistJob?.cancel()
        timelineViewPersistJob = viewModelScope.launch {
            delay(700)
            val movie = currentMovie ?: return@launch
            if (movie.id != movieId) return@launch
            if (movie.lastTimelineOffset != scrollOffset || movie.lastTimelineZoom != zoomScale) {
                updateMovie(movie.copy(lastTimelineOffset = scrollOffset, lastTimelineZoom = zoomScale))
            }
        }
    }

    /**
     * Persists a pending timeline view change immediately, cancelling the debounce. Used when the
     * movie is about to be closed so a scroll/zoom change made right before leaving still saves.
     */
    private fun flushTimelineViewPersist() {
        timelineViewPersistJob?.cancel()
        timelineViewPersistJob = null
        val movie = currentMovie ?: return
        if (movie.lastTimelineOffset != scrollOffset || movie.lastTimelineZoom != zoomScale) {
            updateMovie(movie.copy(lastTimelineOffset = scrollOffset, lastTimelineZoom = zoomScale))
        }
    }

    /** Sets [url] (an image asset's URL) as the current movie's cover photo and persists it. */
    fun setMovieCover(url: String) {
        val movie = currentMovie ?: return
        updateMovie(movie.copy(coverImageUrl = url))
    }

    fun refreshTimeline() {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                val loaded = NetworkService.getTimeline(movieId)
                timeline = loaded
                currentMovie = loaded.movie
                timelineError = null
            } catch (e: Exception) {
                timelineError = "Failed to load timeline: ${e.message}"
            }
        }
    }

    // ================================================================================== playback

    fun togglePlayback() {
        if (isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val duration = timeline?.calculatedDuration() ?: 0.0
        if (duration <= 0.0) return
        // Restart from the beginning when playback already reached the end.
        if (playhead >= duration - 0.05f) {
            playhead = 0f
        }
        isPlaying = true
        startPlaybackTicker()
    }

    fun pause() {
        isPlaying = false
        playbackTicker?.cancel()
        playbackTicker = null
    }

    /**
     * Moves the playhead to [seconds]. By default the playhead is clamped to the timeline's
     * calculated end, but callers that let the user scrub freely (clicking or dragging on the
     * ruler) pass [allowPastEnd] = true so the playhead can be parked past the last item.
     */
    fun seek(seconds: Float, allowPastEnd: Boolean = false) {
        val duration = (timeline?.calculatedDuration() ?: 0.0).toFloat()
        val upperBound = when {
            allowPastEnd -> seconds.coerceAtLeast(0f)
            duration > 0f -> duration
            else -> seconds.coerceAtLeast(0f)
        }
        playhead = seconds.coerceIn(0f, upperBound)
    }

    /** Moves the playhead by [deltaSeconds] (arrow-key stepping and wheel scrubbing). */
    fun seekBy(deltaSeconds: Float) {
        seek(playhead + deltaSeconds)
    }

    /** One-click fullscreen playback: hides all editor chrome and plays from the playhead. */
    fun enterFullscreenPlayback() {
        isFullscreenPlayback = true
        if (!isPlaying) play()
    }

    /** Leaves fullscreen playback (ESC or the close affordance). */
    fun exitFullscreenPlayback() {
        isFullscreenPlayback = false
    }

    /**
     * Advances the playhead at ~30fps while playing. The playhead is the master clock: the video
     * element and the audio pool both chase it, so playback keeps moving across description-only
     * segments and gaps.
     */
    private fun startPlaybackTicker() {
        playbackTicker?.cancel()
        playbackTicker = viewModelScope.launch {
            val timeSource = TimeSource.Monotonic
            var last = timeSource.markNow()
            while (isActive && isPlaying) {
                delay(33)
                val now = timeSource.markNow()
                val deltaSeconds = (now - last).inWholeMilliseconds / 1000f
                last = now
                val duration = (timeline?.calculatedDuration() ?: 0.0).toFloat()
                val next = playhead + deltaSeconds
                if (duration > 0f && next >= duration) {
                    playhead = duration
                    pause()
                } else {
                    playhead = next
                }
            }
        }
    }

    // =================================================================================== timeline

    /**
     * Adds a track of [type], inserted after the last existing track of the same kind (so a new
     * video track lands under the other videos, not under music/voice). When none of that type
     * exist yet, the track is appended at the end. Tracks below the insertion point have their
     * zIndex shifted up so order stays contiguous.
     */
    fun addTrack(type: TrackType) {
        val movieId = currentMovie?.id ?: return
        val current = timeline?.tracks ?: emptyList()
        viewModelScope.launch {
            try {
                val insertIndex = trackInsertIndex(current, type)
                NetworkService.createTrack(
                    movieId,
                    Track(generateId(), movieId, type, zIndex = insertIndex)
                )
                // Make room: bump zIndex on every track that sits at or after the insertion point.
                val shifted = shiftedZIndexesAfterInsert(current.size, insertIndex)
                current.forEachIndexed { index, twc ->
                    val newZ = shifted[index]
                    if (twc.track.zIndex != newZ) {
                        NetworkService.updateTrack(movieId, twc.track.copy(zIndex = newZ))
                    }
                }
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to add track: ${e.message}"
            }
        }
    }

    /** Renames a track (blank name falls back to the type's default label in the UI). */
    fun renameTrack(track: Track, name: String) {
        val movieId = currentMovie?.id ?: return
        val updated = track.copy(name = name.trim().ifBlank { null })
        // Optimistic local update so the header renames instantly.
        timeline = timeline?.copy(
            tracks = timeline!!.tracks.map { t -> if (t.track.id == track.id) t.copy(track = updated) else t }
        )
        viewModelScope.launch {
            try {
                NetworkService.updateTrack(movieId, updated)
            } catch (e: Exception) {
                errorMessage = "Failed to rename track: ${e.message}"
                refreshTimeline()
            }
        }
    }

    /**
     * Moves the track at [fromIndex] to [toIndex] (header drag-and-drop) and persists the new
     * zIndex order for every track.
     */
    fun reorderTrack(fromIndex: Int, toIndex: Int) {
        val movieId = currentMovie?.id ?: return
        val current = timeline?.tracks ?: return
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val reordered = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        val renumbered = reordered.mapIndexed { index, t -> t.copy(track = t.track.copy(zIndex = index)) }
        timeline = timeline?.copy(tracks = renumbered)
        viewModelScope.launch {
            try {
                renumbered.forEachIndexed { index, t ->
                    if (current.getOrNull(index)?.track?.id != t.track.id ||
                        current.getOrNull(index)?.track?.zIndex != t.track.zIndex
                    ) {
                        NetworkService.updateTrack(movieId, t.track)
                    }
                }
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to reorder tracks: ${e.message}"
                refreshTimeline()
            }
        }
    }

    fun deleteTrack(trackId: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                NetworkService.deleteTrack(movieId, trackId)
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to delete track: ${e.message}"
            }
        }
    }

    /** The track type an asset of [type] lands on when placed on the timeline. */
    fun compatibleTrackType(type: AssetType): TrackType = when (type) {
        AssetType.VIDEO, AssetType.IMAGE, AssetType.TEXT -> TrackType.VIDEO
        AssetType.MUSIC, AssetType.AUDIO -> TrackType.MUSIC
        AssetType.VOICE -> TrackType.VOICE
    }

    /**
     * Adds [asset] to a compatible track, at [atSeconds] when given (e.g. a drag-and-drop
     * position on the timeline) or at the current playhead position otherwise. When
     * [targetTrackId] names a compatible track (a drop aimed at a specific row), the clip lands
     * on that exact track — so media can be dropped on any track of the matching type.
     */
    fun addAssetToTimeline(asset: Asset, atSeconds: Float? = null, targetTrackId: String? = null) {
        val movieId = currentMovie?.id ?: return
        val tracks = timeline?.tracks ?: return
        val targetTrackType = compatibleTrackType(asset.type)
        viewModelScope.launch {
            try {
                val targeted = targetTrackId
                    ?.let { id -> tracks.firstOrNull { it.track.id == id && it.track.type == targetTrackType } }
                    ?.track
                val track = targeted ?: tracks.firstOrNull { it.track.type == targetTrackType }?.track ?: run {
                    val zIndex = (tracks.maxOfOrNull { it.track.zIndex } ?: -1) + 1
                    NetworkService.createTrack(movieId, Track(generateId(), movieId, targetTrackType, zIndex))
                }
                val duration = if (asset.durationSeconds > 0) asset.durationSeconds else 5.0
                val clip = Clip(
                    id = generateId(),
                    trackId = track.id,
                    assetId = asset.id,
                    timelineStart = (atSeconds ?: playhead).coerceAtLeast(0f),
                    trimIn = 0f,
                    trimOut = duration.toFloat(),
                    effectsConfig = "{}"
                )
                NetworkService.createClip(movieId, clip)
                // Auto-select the freshly added clip so its properties are immediately editable.
                selectedClipId = clip.id
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to add to timeline: ${e.message}"
            }
        }
    }

    // A one-shot timeline placement armed by the timeline's "＋ Add" menu (a blank-track click):
    // the next asset created by a creation flow is dropped onto the timeline at this position
    // instead of only landing in the library. Covers both synchronous flows (text, placeholder,
    // uploads, recordings, sequencer) and asynchronous generation, which resolves later once the
    // freshly generated asset appears.
    private var pendingPlacementSeconds: Float? = null
    private var pendingPlacementTrackId: String? = null

    /**
     * Arms a one-shot timeline placement at [seconds] on the track identified by [trackId] (a
     * blank-track click in the timeline's add menu). The next asset produced by a creation flow is
     * added to the timeline there; see [consumePendingPlacement].
     */
    fun armTimelinePlacement(seconds: Float, trackId: String?) {
        pendingPlacementSeconds = seconds
        pendingPlacementTrackId = trackId
    }

    /** Disarms a pending timeline placement (e.g. the add menu was dismissed without a choice). */
    fun cancelTimelinePlacement() {
        pendingPlacementSeconds = null
        pendingPlacementTrackId = null
    }

    /**
     * If a timeline placement is armed (see [armTimelinePlacement]), drops the freshly created
     * [asset] onto the timeline at that position and disarms it; otherwise does nothing.
     */
    private fun consumePendingPlacement(asset: Asset) {
        val seconds = pendingPlacementSeconds ?: return
        val trackId = pendingPlacementTrackId
        pendingPlacementSeconds = null
        pendingPlacementTrackId = null
        addAssetToTimeline(asset, seconds, trackId)
    }

    /**
     * Applies [clip] to the local timeline state, re-homing it when its trackId changed (clips
     * can be dragged vertically onto another track of the same type).
     */
    private fun applyClipLocally(clip: Clip) {
        timeline = timeline?.copy(
            tracks = timeline!!.tracks.map { trackWithClips ->
                val without = trackWithClips.clips.filter { it.id != clip.id }
                if (trackWithClips.track.id == clip.trackId) {
                    trackWithClips.copy(clips = without + clip)
                } else {
                    trackWithClips.copy(clips = without)
                }
            }
        )
    }

    /**
     * Applies a batch of moved clips to the local timeline in a single pass: each clip is removed
     * from wherever it currently sits and re-added to the track named by its (possibly changed)
     * trackId. Used for multi-clip drags so every selected clip re-homes correctly at once.
     */
    private fun applyClipsLocally(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val movedIds = clips.mapTo(HashSet()) { it.id }
        timeline = timeline?.copy(
            tracks = timeline!!.tracks.map { trackWithClips ->
                val remaining = trackWithClips.clips.filter { it.id !in movedIds }
                val addedHere = clips.filter { it.trackId == trackWithClips.track.id }
                trackWithClips.copy(clips = remaining + addedHere)
            }
        )
    }

    /**
     * Local-only clip update used while a drag is in progress: keeps the canvas in sync at full
     * frame rate without hitting the server. The drag commit calls [updateClip].
     */
    fun updateClipLocal(clip: Clip) {
        applyClipLocally(clip)
    }

    /** Local-only batch update used while a multi-clip drag is in progress (see [updateClips]). */
    fun updateClipsLocal(clips: List<Clip>) {
        applyClipsLocally(clips)
    }

    /** Persists a moved/resized clip. */
    fun updateClip(clip: Clip) {
        val movieId = currentMovie?.id ?: return
        // Optimistically update local state so dragging feels instant.
        applyClipLocally(clip)
        viewModelScope.launch {
            try {
                NetworkService.updateClip(movieId, clip)
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to update clip: ${e.message}"
                refreshTimeline()
            }
        }
    }

    /** Persists a batch of moved clips (the commit of a multi-clip drag). No-op when empty. */
    fun updateClips(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val movieId = currentMovie?.id ?: return
        // Optimistically update local state so dragging feels instant.
        applyClipsLocally(clips)
        viewModelScope.launch {
            try {
                clips.forEach { NetworkService.updateClip(movieId, it) }
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to update clip: ${e.message}"
                refreshTimeline()
            }
        }
    }

    fun deleteClip(clipId: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                NetworkService.deleteClip(movieId, clipId)
                selectedClipIds = selectedClipIds - clipId
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to delete clip: ${e.message}"
            }
        }
    }

    /** Deletes every selected clip in one go (multi-select delete). No-op when nothing selected. */
    fun deleteSelectedClips() {
        val ids = selectedClipIds.toList()
        if (ids.isEmpty()) return
        val movieId = currentMovie?.id ?: return
        selectedClipIds = emptySet()
        viewModelScope.launch {
            try {
                ids.forEach { NetworkService.deleteClip(movieId, it) }
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to delete clip: ${e.message}"
                refreshTimeline()
            }
        }
    }

    /**
     * Duplicates [clipId]: creates an identical clip (same asset, trim and effects) on the same
     * track, placed immediately after the original so the copy is visible and doesn't hide it.
     * The new clip becomes the selected one.
     */
    fun duplicateClip(clipId: String) {
        val movieId = currentMovie?.id ?: return
        val original = findClip(clipId)?.first ?: return
        val length = original.trimOut - original.trimIn
        val copy = original.copy(
            id = generateId(),
            timelineStart = original.timelineStart + length
        )
        // Optimistically update local state so the duplicate appears instantly.
        applyClipLocally(copy)
        viewModelScope.launch {
            try {
                NetworkService.createClip(movieId, copy)
                selectedClipId = copy.id
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to duplicate clip: ${e.message}"
                refreshTimeline()
            }
        }
    }

    /** The smallest piece (in seconds) a split may leave on either side of the playhead. */
    private val minSplitSliver = 0.05f

    /**
     * Whether the currently selected clip can be split at the current playhead: a clip must be
     * selected and the playhead must fall strictly inside it (leaving a sliver on each side).
     */
    fun canSplitSelectedClip(): Boolean {
        val clip = findClip(selectedClipId)?.first ?: return false
        val offset = playhead - clip.timelineStart
        val length = clip.trimOut - clip.trimIn
        return offset > minSplitSliver && offset < length - minSplitSliver
    }

    /**
     * Splits the selected clip into two at the current playhead: the existing clip is trimmed to
     * end at the playhead, and a new clip covers the remainder. No-op when the playhead is not
     * strictly inside the selected clip (see [canSplitSelectedClip]).
     */
    fun splitSelectedClip() {
        val movieId = currentMovie?.id ?: return
        val clip = findClip(selectedClipId)?.first ?: return
        val offset = playhead - clip.timelineStart
        val length = clip.trimOut - clip.trimIn
        if (offset <= minSplitSliver || offset >= length - minSplitSliver) return

        val left = clip.copy(trimOut = clip.trimIn + offset)
        val right = clip.copy(
            id = generateId(),
            timelineStart = clip.timelineStart + offset,
            trimIn = clip.trimIn + offset
        )
        // Optimistically update local state so the split appears instantly.
        applyClipLocally(left)
        applyClipLocally(right)
        viewModelScope.launch {
            try {
                NetworkService.updateClip(movieId, left)
                NetworkService.createClip(movieId, right)
                selectedClipId = right.id
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to split clip: ${e.message}"
                refreshTimeline()
            }
        }
    }

    /** Updates a clip's parsed effects configuration (transition, captions, volume). */
    fun updateClipEffects(clip: Clip, effects: EffectsConfig) {
        updateClip(clip.copy(effectsConfig = encodeEffectsConfig(effects)))
    }

    fun findClip(clipId: String?): Pair<Clip, Track>? {
        if (clipId == null) return null
        val currentTimeline = timeline ?: return null
        for (trackWithClips in currentTimeline.tracks) {
            val clip = trackWithClips.clips.firstOrNull { it.id == clipId }
            if (clip != null) return clip to trackWithClips.track
        }
        return null
    }

    fun assetById(assetId: String): Asset? = libraryAssets.firstOrNull { it.id == assetId }

    // ============================================================================ timeline notes

    /**
     * Updates the notes list and mirrors it into [timeline] so the timeline length calculation
     * ([calculatedDuration]) counts notes pinned past the last clip, live in the editor.
     */
    private fun applyNotes(notes: List<TimelineNote>) {
        timelineNotes = notes
        timeline = timeline?.copy(notes = notes)
    }

    fun refreshNotes() {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                applyNotes(NetworkService.getNotes(movieId))
                notesError = null
            } catch (e: Exception) {
                notesError = "Failed to load notes: ${e.message}"
            }
        }
    }

    /** Adds a text-only note pinned at [atSeconds] (defaults to the current playhead). */
    fun addNote(text: String, atSeconds: Float = playhead) {
        val movieId = currentMovie?.id ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val note = TimelineNote(
                    id = generateId(),
                    movieId = movieId,
                    atSeconds = atSeconds.toDouble().coerceAtLeast(0.0),
                    text = trimmed
                )
                val saved = NetworkService.createNote(movieId, note)
                applyNotes((timelineNotes + saved).sortedBy { it.atSeconds })
                selectedNoteId = saved.id
            } catch (e: Exception) {
                errorMessage = "Failed to add note: ${e.message}"
            }
        }
    }

    /** Persists an edited note (text and/or marker position). */
    fun updateNote(note: TimelineNote) {
        val movieId = currentMovie?.id ?: return
        if (note.text.isBlank()) return
        // Optimistic local update so edits and re-pins feel instant.
        applyNotes(timelineNotes.map { if (it.id == note.id) note else it }.sortedBy { it.atSeconds })
        viewModelScope.launch {
            try {
                NetworkService.updateNote(movieId, note)
            } catch (e: Exception) {
                errorMessage = "Failed to update note: ${e.message}"
                refreshNotes()
            }
        }
    }

    fun deleteNote(noteId: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                NetworkService.deleteNote(movieId, noteId)
                if (selectedNoteId == noteId) selectedNoteId = null
                applyNotes(timelineNotes.filter { it.id != noteId })
            } catch (e: Exception) {
                errorMessage = "Failed to delete note: ${e.message}"
            }
        }
    }

    /** Focuses [note]: highlights it, reveals the notes panel and seeks to its marker. */
    fun focusNote(note: TimelineNote) {
        selectedNoteId = note.id
        notesPanelExpanded = true
        seek(note.atSeconds.toFloat())
    }

    // ================================================================================= documents

    fun refreshDocuments() {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                documents = NetworkService.getDocuments(movieId)
                documentsError = null
            } catch (e: Exception) {
                documentsError = "Failed to load documents: ${e.message}"
            }
        }
    }

    /** Creates a new document (optionally nested under [parentId]) and opens it in the editor. */
    fun addDocument(parentId: String? = null, title: String = "Untitled document") {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                val siblings = documents.filter { it.parentId == parentId }
                val document = MovieDocument(
                    id = generateId(),
                    movieId = movieId,
                    title = title,
                    parentId = parentId,
                    sortIndex = (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1
                )
                val saved = NetworkService.createDocument(movieId, document)
                documents = documents + saved
                selectedDocumentId = saved.id
                newlyCreatedDocumentId = saved.id
            } catch (e: Exception) {
                errorMessage = "Failed to create document: ${e.message}"
            }
        }
    }

    /**
     * Persists a document (rename, content auto-save or move). Optimistic so typing feels
     * instant; the server maintains the restorable content history on every content change.
     */
    fun updateDocument(document: MovieDocument) {
        val movieId = currentMovie?.id ?: return
        if (document.title.isBlank()) return
        documents = documents.map { if (it.id == document.id) document else it }
        viewModelScope.launch {
            try {
                val saved = NetworkService.updateDocument(movieId, document)
                // Adopt the server copy (it carries the history checkpoints) unless the user
                // typed further while the save was in flight.
                documents = documents.map {
                    if (it.id == saved.id && it.content == document.content && it.title == document.title) saved
                    else it
                }
            } catch (e: Exception) {
                errorMessage = "Failed to save document: ${e.message}"
                refreshDocuments()
            }
        }
    }

    /** Deletes [documentId] together with its whole subtree of child documents. */
    fun deleteDocument(documentId: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                val removed = documentSubtreeIds(documentId)
                NetworkService.deleteDocument(movieId, documentId)
                documents = documents.filter { it.id !in removed }
                if (selectedDocumentId in removed) selectedDocumentId = null
            } catch (e: Exception) {
                errorMessage = "Failed to delete document: ${e.message}"
            }
        }
    }

    /** [documentId] plus every descendant document id (children, grandchildren...). */
    fun documentSubtreeIds(documentId: String): Set<String> {
        val ids = mutableSetOf(documentId)
        var changed = true
        while (changed) {
            changed = false
            documents.forEach { doc ->
                val parent = doc.parentId
                if (parent != null && parent in ids && ids.add(doc.id)) changed = true
            }
        }
        return ids
    }

    /**
     * Moves a document in the tree (drag and drop): re-parents it under [newParentId] and places
     * it among those siblings at [targetIndex]. Dropping a document into its own subtree is
     * ignored (it would orphan the branch). Every sibling whose position changed is persisted.
     */
    fun moveDocument(documentId: String, newParentId: String?, targetIndex: Int) {
        val movieId = currentMovie?.id ?: return
        val moved = documents.firstOrNull { it.id == documentId } ?: return
        if (newParentId != null && newParentId in documentSubtreeIds(documentId)) return

        val siblings = documents
            .filter { it.parentId == newParentId && it.id != documentId }
            .sortedBy { it.sortIndex }
            .toMutableList()
        siblings.add(targetIndex.coerceIn(0, siblings.size), moved.copy(parentId = newParentId))

        val renumbered = siblings.mapIndexed { index, doc -> doc.copy(sortIndex = index) }
        val changed = renumbered.filter { doc ->
            val before = documents.firstOrNull { it.id == doc.id }
            before == null || before.parentId != doc.parentId || before.sortIndex != doc.sortIndex
        }
        if (changed.isEmpty()) return
        // Optimistic local re-order so the drop lands instantly.
        documents = documents.map { doc -> renumbered.firstOrNull { it.id == doc.id } ?: doc }
        viewModelScope.launch {
            try {
                changed.forEach { NetworkService.updateDocument(movieId, it) }
            } catch (e: Exception) {
                errorMessage = "Failed to move document: ${e.message}"
                refreshDocuments()
            }
        }
    }

    /**
     * Restores a previous version of the document's content from its history. The content it
     * replaces is checkpointed into the history server-side, so nothing is lost.
     */
    fun restoreDocumentVersion(document: MovieDocument, version: DocumentVersion) {
        updateDocument(document.copy(content = version.content))
    }

    // =================================================================================== library

    fun refreshLibrary() {
        viewModelScope.launch { reloadLibraryAssets() }
    }

    /** Reloads the global asset library into [libraryAssets] (suspending), tracking any error. */
    private suspend fun reloadLibraryAssets() {
        try {
            // The asset library is global (not scoped to a single movie). No limit = full page.
            libraryAssets = NetworkService.getLibraryAssets().items
            libraryError = null
        } catch (e: Exception) {
            libraryError = "Failed to load library: ${e.message}"
        }
    }

    /**
     * Reloads the library after a background generation finished and, when a genuinely new asset
     * appeared, publishes it through [generatedAssetSignal] so the library panel can scroll to the
     * top (when the asset matches the filters currently applied there).
     */
    private suspend fun refreshLibraryDetectingNew() {
        val before = libraryAssets.map { it.id }.toSet()
        reloadLibraryAssets()
        val newAsset = libraryAssets.filter { it.id !in before }.maxByOrNull { it.createdAt }
        if (newAsset != null) {
            generatedAssetSeq += 1
            generatedAssetSignal = GeneratedAssetSignal(newAsset, generatedAssetSeq)
            // A generation kicked off from the timeline's add menu is dropped where it was armed.
            consumePendingPlacement(newAsset)
        }
    }

    /**
     * Runs an upload [block] while publishing its progress to [uploadState]: shows the bar at 0%
     * as soon as the upload starts, forwards every progress tick and always clears the state when
     * the block finishes (success, cancellation or error).
     */
    private suspend fun <T> withUploadProgress(
        label: String,
        block: suspend (onProgress: (Float) -> Unit) -> T
    ): T {
        uploadState = UploadState(label, 0f)
        return try {
            block { fraction -> uploadState = UploadState(label, fraction.coerceIn(0f, 1f)) }
        } finally {
            uploadState = null
        }
    }

    /**
     * Picks an image from the device, uploads it into the image library and hands back its URL —
     * used by the generate dialog to attach custom reference images.
     */
    fun uploadReferenceImage(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val uploaded = withUploadProgress("Uploading reference image") { onProgress ->
                    pickAndUploadDeviceFile(AssetType.IMAGE, onProgress)
                }
                if (uploaded == null) {
                    onDone(null)
                    return@launch
                }
                NetworkService.createAsset(
                    Asset(
                        id = generateId(),
                        type = AssetType.IMAGE,
                        ossUrl = uploaded.ossUrl,
                        durationSeconds = 5.0,
                        movieId = currentMovie?.id,
                        tags = listOf("uploaded", "reference"),
                        aiPrompt = null,
                        description = uploaded.fileName
                    )
                )
                refreshLibrary()
                onDone(uploaded.ossUrl)
            } catch (e: Exception) {
                errorMessage = "Reference upload failed: ${e.message}"
                onDone(null)
            }
        }
    }

    /**
     * Adds a first-class TEXT element (no media, rendered as styled text — not a placeholder) to
     * the global library. The [text] is stored as both the description and prompt.
     */
    fun addTextAsset(text: String, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = Asset(
                    id = generateId(),
                    type = AssetType.TEXT,
                    ossUrl = "",
                    durationSeconds = 5.0,
                    movieId = currentMovie?.id,
                    tags = listOf("text"),
                    aiPrompt = text,
                    description = text,
                    // A first-class text element that renders, rather than a media placeholder.
                    isPlaceholder = false
                )
                val saved = NetworkService.createAsset(asset)
                refreshLibrary()
                consumePendingPlacement(saved)
                onDone(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to add text: ${e.message}"
            }
        }
    }

    /** Adds a description-only asset (no media yet) to the global library. */
    fun addAssetByDescription(type: AssetType, description: String, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = Asset(
                    id = generateId(),
                    type = type,
                    ossUrl = "",
                    durationSeconds = 5.0,
                    movieId = currentMovie?.id,
                    tags = listOf("description-only"),
                    aiPrompt = description,
                    description = description
                )
                val saved = NetworkService.createAsset(asset)
                refreshLibrary()
                consumePendingPlacement(saved)
                onDone(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to add asset: ${e.message}"
            }
        }
    }

    /**
     * Uploads files the user dropped onto the library (from the OS file manager, the browser...),
     * creating a library asset for each one. The asset type is inferred from the file extension via
     * [assetTypeForFile]: images/videos/audio are uploaded to storage, while text files become a
     * placeholder voice asset carrying their text. Unsupported files are skipped. Runs the drops
     * sequentially so the shared [uploadState] progress bar reflects one upload at a time.
     */
    fun uploadDroppedFiles(files: List<DroppedFile>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            var created = false
            for (file in files) {
                try {
                    when (val type = assetTypeForFile(file.name)) {
                        // Text drops become a placeholder voice asset carrying the file's text.
                        AssetType.TEXT -> {
                            val text = readDroppedFileText(file)?.trim()
                            if (!text.isNullOrBlank()) {
                                NetworkService.createAsset(
                                    Asset(
                                        id = generateId(),
                                        type = AssetType.TEXT,
                                        ossUrl = "",
                                        durationSeconds = 5.0,
                                        movieId = currentMovie?.id,
                                        tags = listOf("description-only"),
                                        aiPrompt = text,
                                        description = text
                                    )
                                )
                                created = true
                            }
                        }
                        // Media drops upload their bytes and create an asset of the inferred type.
                        null -> Unit // Unsupported file type: skip.
                        else -> {
                            val uploaded = withUploadProgress("Uploading ${file.name}") { onProgress ->
                                uploadDroppedFile(file, onProgress)
                            } ?: continue
                            val asset = Asset(
                                id = generateId(),
                                type = type,
                                ossUrl = uploaded.ossUrl,
                                durationSeconds = uploaded.durationSeconds,
                                movieId = currentMovie?.id,
                                tags = listOf("uploaded"),
                                aiPrompt = null,
                                description = uploaded.fileName
                            )
                            var saved = NetworkService.createAsset(asset)
                            // Voice media auto-generates a transcript with word timings.
                            if (type == AssetType.VOICE) {
                                saved = NetworkService.generateTranscript(saved.id)
                            }
                            created = true
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = "Upload failed: ${e.message}"
                }
            }
            if (created) refreshLibrary()
        }
    }

    /** Opens the device file picker and uploads the chosen media into the global library. */
    fun uploadAsset(type: AssetType, description: String = "") {
        viewModelScope.launch {
            try {
                val uploaded = withUploadProgress("Uploading ${type.name.lowercase()}") { onProgress ->
                    pickAndUploadDeviceFile(type, onProgress)
                } ?: return@launch
                val asset = Asset(
                    id = generateId(),
                    type = type,
                    ossUrl = uploaded.ossUrl,
                    durationSeconds = uploaded.durationSeconds,
                    movieId = currentMovie?.id,
                    tags = listOf("uploaded"),
                    aiPrompt = null,
                    description = description.ifBlank { uploaded.fileName }
                )
                var saved = NetworkService.createAsset(asset)
                // Voice media auto-generates a transcript with word timings.
                if (type == AssetType.VOICE) {
                    saved = NetworkService.generateTranscript(saved.id)
                }
                refreshLibrary()
                consumePendingPlacement(saved)
            } catch (e: Exception) {
                errorMessage = "Upload failed: ${e.message}"
            }
        }
    }

    /**
     * Saves a finished microphone recording as a VOICE asset in the global library (with the
     * usual auto-generated transcript), used by the "Record voice..." flow.
     */
    fun createVoiceRecordingAsset(name: String, recording: UploadedDeviceFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = Asset(
                    id = generateId(),
                    type = AssetType.VOICE,
                    ossUrl = recording.ossUrl,
                    durationSeconds = recording.durationSeconds,
                    movieId = currentMovie?.id,
                    tags = listOf("recorded"),
                    aiPrompt = null,
                    description = name.ifBlank { "Voice recording" }
                )
                val saved = NetworkService.createAsset(asset)
                // Voice media auto-generates a transcript with word timings.
                NetworkService.generateTranscript(saved.id)
                refreshLibrary()
                consumePendingPlacement(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to save recording: ${e.message}"
            } finally {
                onDone()
            }
        }
    }

    /**
     * Saves a finished microphone recording as a sound-effect (AUDIO) asset in the global
     * library, used by the "Record sound effect..." flow.
     */
    fun createSoundEffectRecordingAsset(name: String, recording: UploadedDeviceFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = Asset(
                    id = generateId(),
                    type = AssetType.AUDIO,
                    ossUrl = recording.ossUrl,
                    durationSeconds = recording.durationSeconds,
                    movieId = currentMovie?.id,
                    tags = listOf("recorded"),
                    aiPrompt = null,
                    description = name.ifBlank { "Sound effect recording" }
                )
                val saved = NetworkService.createAsset(asset)
                refreshLibrary()
                consumePendingPlacement(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to save recording: ${e.message}"
            } finally {
                onDone()
            }
        }
    }

    /**
     * Saves a finished microphone recording as a MUSIC asset in the global library, used by the
     * "Record music..." flow.
     */
    fun createMusicRecordingAsset(name: String, recording: UploadedDeviceFile, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = Asset(
                    id = generateId(),
                    type = AssetType.MUSIC,
                    ossUrl = recording.ossUrl,
                    durationSeconds = recording.durationSeconds,
                    movieId = currentMovie?.id,
                    tags = listOf("recorded"),
                    aiPrompt = null,
                    description = name.ifBlank { "Music recording" }
                )
                val saved = NetworkService.createAsset(asset)
                refreshLibrary()
                consumePendingPlacement(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to save recording: ${e.message}"
            } finally {
                onDone()
            }
        }
    }

    fun updateAsset(asset: Asset, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val saved = NetworkService.updateAsset(asset)
                libraryAssets = libraryAssets.map { if (it.id == saved.id) saved else it }
                onDone(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to update asset: ${e.message}"
            }
        }
    }

    fun deleteAsset(asset: Asset) {
        viewModelScope.launch {
            try {
                NetworkService.deleteAsset(asset.id)
                refreshLibrary()
            } catch (e: Exception) {
                errorMessage = "Failed to delete asset: ${e.message}"
            }
        }
    }

    /**
     * Disassociates [asset] from the movie it was created for, leaving it in the global library.
     * The asset itself (and any timeline clips that reference it) are kept.
     */
    fun removeAssetFromMovie(asset: Asset) {
        if (asset.movieId == null) return
        updateAsset(asset.copy(movieId = null))
    }

    /**
     * Associates [asset] with the currently open movie. Assets can only belong to one movie, so
     * any previous [Asset.movieId] is replaced. No-op when there is no open movie or the asset is
     * already tied to it.
     */
    fun addAssetToMovie(asset: Asset) {
        val movieId = currentMovie?.id ?: return
        if (asset.movieId == movieId) return
        updateAsset(asset.copy(movieId = movieId))
    }

    /** Restores a previous version of the asset's media from its history. */
    fun restoreAssetVersion(asset: Asset, versionIndex: Int, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val restored = NetworkService.restoreAssetVersion(asset.id, versionIndex)
                libraryAssets = libraryAssets.map { if (it.id == restored.id) restored else it }
                onDone(restored)
            } catch (e: Exception) {
                errorMessage = "Failed to restore version: ${e.message}"
            }
        }
    }

    /** Extracts the audio track of a video asset into the sound-effects library (async). */
    fun extractAudioFromAsset(asset: Asset) {
        viewModelScope.launch {
            try {
                NetworkService.extractAudio(asset.id)
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start audio extraction: ${e.message}"
            }
        }
    }

    /**
     * Extracts the audio of a video clip as a voiceover library item (async generation). The
     * server places a muted, captions-on voice clip on the timeline at the original clip.
     */
    fun extractVoiceFromClip(clip: Clip) {
        viewModelScope.launch {
            try {
                NetworkService.extractVoice(clip.assetId, clip.id)
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start voice extraction: ${e.message}"
            }
        }
    }

    /** Saves a clipped window of a sound asset as a new sound effect. */
    fun clipAudioAsset(asset: Asset, startSeconds: Double, endSeconds: Double, name: String) {
        viewModelScope.launch {
            try {
                NetworkService.clipAudio(asset.id, startSeconds, endSeconds, name)
                refreshLibrary()
            } catch (e: Exception) {
                errorMessage = "Failed to clip audio: ${e.message}"
            }
        }
    }

    /** Renders a sequencer pattern server-side into a MUSIC asset (editable later). */
    fun createSequenceAsset(sequence: MusicSequence, onDone: (Asset?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val asset = NetworkService.createMusicSequence(currentMovie?.id, sequence)
                refreshLibrary()
                consumePendingPlacement(asset)
                onDone(asset)
            } catch (e: Exception) {
                errorMessage = "Failed to render sequence: ${e.message}"
                onDone(null)
            }
        }
    }

    // ================================================================================ generation

    /**
     * Queues an AI media generation described by [setup]; optionally regenerating [assetId] in
     * place. [sourceAssetId] records the library asset this generation was launched from (the
     * asset whose details dialog opened the generate/regenerate dialog), so the UI can show a
     * "generating" spinner on that asset while its generations are in flight; it defaults to
     * [assetId] and is carried on the job even when the result becomes a brand-new asset.
     */
    fun generateMedia(setup: GenerationSetup, assetId: String? = null, sourceAssetId: String? = assetId) {
        viewModelScope.launch {
            try {
                NetworkService.generateMedia(currentMovie?.id, setup, assetId, sourceAssetId)
                refreshActiveJobs()
                rememberLastGenerationSettings(setup)
            } catch (e: Exception) {
                errorMessage = "Failed to start generation: ${e.message}"
            }
        }
    }

    /**
     * True while at least one background generation launched from the asset with [assetId] is
     * still in flight (PENDING/RUNNING). Backed by [runningJobs] (WebSocket push + polling), so it
     * reflects jobs started earlier — even before this session — as well as ones started while an
     * asset dialog is open, and clears once they all finish. Reading it in a composable makes the
     * caller recompose as jobs come and go.
     */
    fun isGeneratingForAsset(assetId: String): Boolean =
        runningJobs.any { it.sourceAssetId == assetId }

    /**
     * Remembers generation defaults on the current movie so dialogs pre-select them next time:
     * image/video resolution (and image model), the TTS voice, and the visual style. Per-movie,
     * since different movies commonly target different resolutions, cast different voices and use
     * different looks.
     */
    private fun rememberLastGenerationSettings(setup: GenerationSetup) {
        val movie = currentMovie ?: return
        var next = movie
        when (setup.kind) {
            "image" -> {
                if (next.lastImageResolution != setup.resolution || next.lastImageModel != setup.model) {
                    next = next.copy(lastImageResolution = setup.resolution, lastImageModel = setup.model)
                }
                // Remember the chosen visual style (including clearing it when the user picks None).
                val styleId = setup.styleId?.takeIf { it.isNotBlank() }
                if (next.lastStyleId != styleId) next = next.copy(lastStyleId = styleId)
            }
            "video" -> {
                if (next.lastVideoResolution != setup.resolution) {
                    next = next.copy(lastVideoResolution = setup.resolution)
                }
                val styleId = setup.styleId?.takeIf { it.isNotBlank() }
                if (next.lastStyleId != styleId) next = next.copy(lastStyleId = styleId)
            }
            "tts" -> {
                val voice = setup.voice.trim()
                if (voice.isNotEmpty() && next.lastVoice != voice) next = next.copy(lastVoice = voice)
            }
        }
        if (next != movie) updateMovie(next)
    }

    /** Queues movie-skeleton generation at the current playhead. */
    fun generateSkeleton(prompt: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                NetworkService.generateSkeleton(movieId, prompt, playhead.toDouble())
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start skeleton generation: ${e.message}"
            }
        }
    }

    /** Captures the current preview frame, uploads it and saves it to the image library. */
    fun saveCurrentFrame(text: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val objectKey = "frames/frame-${generateId()}.png"
                val upload = NetworkService.requestUploadUrl(objectKey)
                val ok = captureVideoFrameAndUpload(upload.uploadUrl)
                if (!ok) {
                    errorMessage = "No video frame available to capture"
                    onDone(false)
                    return@launch
                }
                NetworkService.createAsset(
                    Asset(
                        id = generateId(),
                        type = AssetType.IMAGE,
                        ossUrl = upload.downloadUrl,
                        durationSeconds = 5.0,
                        movieId = currentMovie?.id,
                        tags = listOf("frame-capture"),
                        aiPrompt = null,
                        description = text.ifBlank { "Captured frame" }
                    )
                )
                refreshLibrary()
                onDone(true)
            } catch (e: Exception) {
                errorMessage = "Failed to save frame: ${e.message}"
                onDone(false)
            }
        }
    }

    // ===================================================================== characters and scenes

    fun refreshCharactersAndScenes() {
        viewModelScope.launch {
            try {
                characters = NetworkService.getCharacters()
                scenes = NetworkService.getScenes()
            } catch (e: Exception) {
                errorMessage = "Failed to load characters/scenes: ${e.message}"
            }
        }
    }

    fun saveCharacter(character: Character, isNew: Boolean) {
        viewModelScope.launch {
            try {
                NetworkService.saveCharacter(character, isNew)
                characters = NetworkService.getCharacters()
            } catch (e: Exception) {
                errorMessage = "Failed to save character: ${e.message}"
            }
        }
    }

    fun deleteCharacter(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteCharacter(id)
                characters = characters.filter { it.id != id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete character: ${e.message}"
            }
        }
    }

    // ===================================================================== visual styles

    fun refreshVisualStyles() {
        viewModelScope.launch {
            try {
                visualStyles = NetworkService.getVisualStyles()
            } catch (e: Exception) {
                errorMessage = "Failed to load visual styles: ${e.message}"
            }
        }
    }

    fun saveVisualStyle(style: VisualStyle, isNew: Boolean) {
        viewModelScope.launch {
            try {
                NetworkService.saveVisualStyle(style, isNew)
                visualStyles = NetworkService.getVisualStyles()
            } catch (e: Exception) {
                errorMessage = "Failed to save visual style: ${e.message}"
            }
        }
    }

    fun deleteVisualStyle(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteVisualStyle(id)
                visualStyles = visualStyles.filter { it.id != id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete visual style: ${e.message}"
            }
        }
    }

    fun saveScene(scene: Scene, isNew: Boolean) {
        viewModelScope.launch {
            try {
                NetworkService.saveScene(scene, isNew)
                scenes = NetworkService.getScenes()
            } catch (e: Exception) {
                errorMessage = "Failed to save scene: ${e.message}"
            }
        }
    }

    fun deleteScene(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteScene(id)
                scenes = scenes.filter { it.id != id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete scene: ${e.message}"
            }
        }
    }

    // ====================================================================================== tips

    /** Loads tips matching the current [tipsSearchQuery] (newest first). */
    fun refreshTips() {
        viewModelScope.launch {
            tipsError = null
            try {
                tips = NetworkService.getTips(tipsSearchQuery)
            } catch (e: Exception) {
                tipsError = "Failed to load tips: ${e.message}"
            }
        }
    }

    /** Updates the tips search query and reloads the matching tips. */
    fun searchTips(query: String) {
        tipsSearchQuery = query
        refreshTips()
    }

    /** Creates a new tip, then reloads the (unfiltered) list so it appears at the top. */
    fun createTip(title: String, content: String) {
        viewModelScope.launch {
            try {
                NetworkService.createTip(
                    Tip(id = generateId(), title = title.trim(), content = content.trim())
                )
                tipsSearchQuery = ""
                tips = NetworkService.getTips()
            } catch (e: Exception) {
                errorMessage = "Failed to create tip: ${e.message}"
            }
        }
    }

    /** Flips a tip between read and unread, updating it in place so its position doesn't change. */
    fun toggleTipRead(tip: Tip) {
        val updated = tip.copy(read = !tip.read)
        tips = tips.map { if (it.id == tip.id) updated else it }
        viewModelScope.launch {
            try {
                NetworkService.updateTip(updated)
            } catch (e: Exception) {
                // Revert the optimistic change on failure.
                tips = tips.map { if (it.id == tip.id) tip else it }
                errorMessage = "Failed to update tip: ${e.message}"
            }
        }
    }

    /** Saves edits to a tip's title/content, updating it in place. */
    fun updateTip(tip: Tip, title: String, content: String) {
        val updated = tip.copy(title = title.trim(), content = content.trim())
        viewModelScope.launch {
            try {
                val saved = NetworkService.updateTip(updated)
                tips = tips.map { if (it.id == saved.id) saved else it }
            } catch (e: Exception) {
                errorMessage = "Failed to update tip: ${e.message}"
            }
        }
    }

    /** Deletes a tip and drops it from the list. */
    fun deleteTip(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteTip(id)
                tips = tips.filter { it.id != id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete tip: ${e.message}"
            }
        }
    }

    // ==================================================================================== issues

    /** Loads issues matching the current [issuesSearchQuery] (open first, then newest). */
    fun refreshIssues() {
        viewModelScope.launch {
            issuesError = null
            try {
                issues = NetworkService.getIssues(issuesSearchQuery)
            } catch (e: Exception) {
                issuesError = "Failed to load issues: ${e.message}"
            }
        }
    }

    /** Updates the issues search query and reloads the matching issues. */
    fun searchIssues(query: String) {
        issuesSearchQuery = query
        refreshIssues()
    }

    /** Reports a new (open) issue, then reloads the (unfiltered) list so it appears at the top. */
    fun createIssue(title: String, description: String) {
        viewModelScope.launch {
            try {
                NetworkService.createIssue(
                    Issue(id = generateId(), title = title.trim(), description = description.trim())
                )
                issuesSearchQuery = ""
                issues = NetworkService.getIssues()
            } catch (e: Exception) {
                errorMessage = "Failed to report issue: ${e.message}"
            }
        }
    }

    /**
     * Saves edits to an issue made in the detail dialog (title/description, workflow status and
     * open/closed state). Reloads the list afterwards so its ordering reflects the new open state.
     */
    fun updateIssue(issue: Issue, title: String, description: String, status: IssueStatus, isOpen: Boolean) {
        val updated = issue.copy(
            title = title.trim(),
            description = description.trim(),
            status = status,
            isOpen = isOpen
        )
        viewModelScope.launch {
            try {
                NetworkService.updateIssue(updated)
                issues = NetworkService.getIssues(issuesSearchQuery)
            } catch (e: Exception) {
                errorMessage = "Failed to update issue: ${e.message}"
            }
        }
    }

    /** Flips an issue between open and closed, reloading the list so its ordering updates. */
    fun toggleIssueOpen(issue: Issue) {
        val updated = issue.copy(isOpen = !issue.isOpen)
        viewModelScope.launch {
            try {
                NetworkService.updateIssue(updated)
                issues = NetworkService.getIssues(issuesSearchQuery)
            } catch (e: Exception) {
                errorMessage = "Failed to update issue: ${e.message}"
            }
        }
    }

    /** Deletes an issue and drops it from the list. */
    fun deleteIssue(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteIssue(id)
                issues = issues.filter { it.id != id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete issue: ${e.message}"
            }
        }
    }

    // ===================================================================================== voice

    fun refreshVoices() {
        viewModelScope.launch {
            try {
                voiceOptions = NetworkService.getVoiceOptions()
            } catch (e: Exception) {
                errorMessage = "Failed to load voices: ${e.message}"
            }
        }
    }

    /**
     * Picks an audio sample from the device and uploads it, publishing the upload progress to
     * [uploadState] so the voice-cloning dialog can show a progress bar. Hands the uploaded sample
     * (or null on failure/cancellation) back to [onDone] — cloning itself happens separately via
     * [createVoiceCloneFromAudioUrl] once the user names the voice, mirroring the mic-recording flow.
     */
    fun uploadVoiceSample(onDone: (UploadedDeviceFile?) -> Unit) {
        viewModelScope.launch {
            val uploaded = try {
                withUploadProgress("Uploading voice sample") { onProgress ->
                    pickAndUploadDeviceFile(AssetType.AUDIO, onProgress)
                }
            } catch (e: Exception) {
                errorMessage = "Voice sample upload failed: ${e.message}"
                null
            }
            onDone(uploaded)
        }
    }

    /**
     * Stops the in-progress microphone recording and uploads it, publishing the upload progress to
     * [uploadState] so the record dialog can show a progress bar. Hands the uploaded sample (or
     * null on failure) back to [onDone].
     */
    fun stopRecordingAndUpload(onDone: (UploadedDeviceFile?) -> Unit) {
        viewModelScope.launch {
            val uploaded = try {
                withUploadProgress("Uploading recording") { onProgress ->
                    stopMicRecordingAndUpload(onProgress)
                }
            } catch (e: Exception) {
                errorMessage = "Recording upload failed: ${e.message}"
                null
            }
            onDone(uploaded)
        }
    }

    /** Enrolls a new cloned voice from an already-uploaded audio sample (e.g. a mic recording). */
    fun createVoiceCloneFromAudioUrl(name: String, audioUrl: String, onDone: (VoiceClone?) -> Unit) {
        viewModelScope.launch {
            try {
                val clone = NetworkService.createVoiceClone(name, audioUrl)
                refreshVoices()
                onDone(clone)
            } catch (e: Exception) {
                errorMessage = "Voice cloning failed: ${e.message}"
                onDone(null)
            }
        }
    }

    fun deleteVoiceClone(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteVoiceClone(id)
                refreshVoices()
            } catch (e: Exception) {
                errorMessage = "Failed to delete voice: ${e.message}"
            }
        }
    }

    /** Designs a new voice from a natural-language description (CosyVoice Voice Design). */
    fun createVoiceDesign(name: String, description: String, onDone: (VoiceDesign?) -> Unit) {
        viewModelScope.launch {
            try {
                val design = NetworkService.createVoiceDesign(name, description)
                refreshVoices()
                onDone(design)
            } catch (e: Exception) {
                errorMessage = "Voice design failed: ${e.message}"
                onDone(null)
            }
        }
    }

    fun deleteVoiceDesign(id: String) {
        viewModelScope.launch {
            try {
                NetworkService.deleteVoiceDesign(id)
                refreshVoices()
            } catch (e: Exception) {
                errorMessage = "Failed to delete voice: ${e.message}"
            }
        }
    }

    /**
     * Plays a short spoken preview of [voiceId] (samples the voice). Fetches a freshly synthesized
     * clip from the server and plays it through the shared audio-playback pool. A blank preview
     * URL (e.g. when AI is not configured) surfaces a friendly message instead.
     */
    fun sampleVoice(voiceId: String, text: String = "") {
        stopVoiceSample()
        samplingVoiceId = voiceId
        viewModelScope.launch {
            try {
                val url = NetworkService.sampleVoice(voiceId, text)
                // A newer sample may have been requested (or playback stopped) while we waited.
                if (samplingVoiceId != voiceId) return@launch
                if (url.isBlank()) {
                    samplingVoiceId = null
                    errorMessage = "Voice preview is unavailable (AI is not configured)."
                    return@launch
                }
                updateAudioPlayback(listOf(AudioPlayItem(VOICE_SAMPLE_KEY, url, 0.0, 1.0)), playing = true)
            } catch (e: Exception) {
                if (samplingVoiceId == voiceId) samplingVoiceId = null
                errorMessage = "Voice preview failed: ${e.message}"
            }
        }
    }

    /** Stops any in-progress voice preview playback. */
    fun stopVoiceSample() {
        if (samplingVoiceId == null) return
        samplingVoiceId = null
        updateAudioPlayback(emptyList(), playing = false)
    }

    /**
     * Saves an edited transcript, then re-runs it through Qwen so the word offsets are refreshed
     * for the new text.
     */
    fun saveTranscript(asset: Asset, newText: String, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val locallyTimed = asset.copy(
                    transcript = newText,
                    wordTimings = buildWordTimings(newText, asset.durationSeconds)
                )
                val saved = NetworkService.updateAsset(locallyTimed)
                // Re-run through Qwen for recognizer-grade word offsets on the edited text.
                val retimed = try {
                    NetworkService.generateTranscript(saved.id)
                } catch (e: Exception) {
                    saved
                }
                libraryAssets = libraryAssets.map { if (it.id == retimed.id) retimed else it }
                onDone(retimed)
            } catch (e: Exception) {
                errorMessage = "Failed to save transcript: ${e.message}"
            }
        }
    }

    /** Persists manually adjusted word timings from the visual editor. */
    fun saveWordTimings(asset: Asset, timings: List<WordTiming>, onDone: (Asset) -> Unit = {}) {
        updateAsset(asset.copy(wordTimings = timings), onDone)
    }

    /** Re-runs Qwen speech recognition on the asset's audio to rebuild transcript + timings. */
    fun regenerateTranscript(asset: Asset, onDone: (Asset) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val updated = NetworkService.generateTranscript(asset.id)
                libraryAssets = libraryAssets.map { if (it.id == updated.id) updated else it }
                onDone(updated)
            } catch (e: Exception) {
                errorMessage = "Transcription failed: ${e.message}"
            }
        }
    }

    // ==================================================================================== render

    /** True when at least one clip sits on the timeline (renders are pointless otherwise). */
    val timelineHasClips: Boolean
        get() = timeline?.tracks?.any { it.clips.isNotEmpty() } == true

    /** Starts a final render job; the render dialog tracks [renderJobId]'s progress. */
    fun startRender() {
        val movieId = currentMovie?.id ?: return
        if (!timelineHasClips) {
            errorMessage = "The timeline is empty — add media before rendering"
            return
        }
        viewModelScope.launch {
            try {
                val job = NetworkService.startRender(movieId)
                renderJobId = job.id
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start render: ${e.message}"
            }
        }
    }

    fun clearRenderJob() {
        renderJobId = null
    }

    fun refreshRenders() {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                renders = NetworkService.getRenders(movieId)
            } catch (e: Exception) {
                errorMessage = "Failed to load renders: ${e.message}"
            }
        }
    }

    // ====================================================================================== jobs

    fun refreshActiveJobs() {
        viewModelScope.launch {
            try {
                // Failed jobs stay listed so the user can retry or dismiss them.
                activeJobs = NetworkService.getJobs(movieId = null, activeOnly = true, includeFailed = true)
            } catch (e: Exception) {
                // Non-fatal: the jobs panel just stays stale.
            }
        }
    }

    /** Re-queues a failed background job on the server. */
    fun retryJob(job: Job) {
        viewModelScope.launch {
            try {
                NetworkService.retryJob(job.id)
                jobProgress = jobProgress - job.id
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to retry job: ${e.message}"
            }
        }
    }

    /** Dismisses (removes) a failed background job from the panel. */
    fun dismissJob(job: Job) {
        viewModelScope.launch {
            try {
                NetworkService.dismissJob(job.id)
                jobProgress = jobProgress - job.id
                activeJobs = activeJobs.filter { it.id != job.id }
            } catch (e: Exception) {
                errorMessage = "Failed to dismiss job: ${e.message}"
            }
        }
    }

    /** Live event stream over WebSocket (web targets); polling covers the rest. */
    private fun connectJobEventsSocket() {
        // A single global subscription: the library is global, so any completion may matter.
        val base = NetworkService.jobEventsWsUrl("").substringBefore("?")
        wsConnection = connectJobEvents(base) { raw ->
            try {
                val event = eventJson.decodeFromString(JobProgressEvent.serializer(), raw)
                onJobEvent(event)
            } catch (e: Exception) {
                // Ignore malformed frames.
            }
        }
    }

    /** Polling fallback (and WS safety net): refreshes active jobs while any are running. */
    private fun startJobPolling() {
        jobPoller?.cancel()
        jobPoller = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (runningJobs.isNotEmpty()) {
                    val before = activeJobs.map { it.id }.toSet()
                    try {
                        val now = NetworkService.getJobs(movieId = null, activeOnly = true, includeFailed = true)
                        activeJobs = now
                        val finished = before - now.map { it.id }.toSet()
                        if (finished.isNotEmpty()) {
                            // Genuine WS safety net: a job left the active set, so it completed
                            // (or failed). Refresh regardless of whether a live WS channel exists —
                            // on web the socket is the ONLY event source, so if a completion frame
                            // is ever missed (reconnect gap, dropped frame) the freshly generated
                            // media / skeleton timeline items would otherwise never appear. Both
                            // refreshes are idempotent, so double-firing alongside a live WS event
                            // is harmless.
                            refreshLibraryDetectingNew()
                            refreshTimeline()
                        }
                    } catch (e: Exception) {
                        // Keep polling.
                    }
                }
            }
        }
    }

    private fun onJobEvent(event: JobProgressEvent) {
        jobProgress = jobProgress + (event.jobId to event)
        when (event.status) {
            JobStatus.COMPLETED -> {
                refreshActiveJobs()
                // Always refresh the library on completion; surface any freshly generated asset
                // so the panel can scroll to it when it matches the active filters.
                viewModelScope.launch { refreshLibraryDetectingNew() }
                // Always reload the open movie's timeline on ANY completed background generation
                // (skeleton planning, AI media (re)generation, final render). These refreshes are
                // internally scoped to the currently open movie (refreshTimeline/refreshRenders
                // no-op when nothing is open) and are idempotent, so we deliberately do NOT gate
                // them on `event.movieId == currentMovie?.id`: that equality check silently
                // suppressed the reload whenever the event's movieId was blank or didn't match
                // exactly, leaving freshly generated media/clips off the timeline until a manual
                // refresh. Reloading unconditionally is safe and guarantees completions populate.
                refreshTimeline()
                if (event.jobType == JobType.FFMPEG_RENDER) {
                    refreshRenders()
                }
            }
            JobStatus.FAILED -> {
                refreshActiveJobs()
                errorMessage = event.message ?: "A background job failed"
            }
            else -> {
                // RUNNING progress: state already stored in jobProgress.
            }
        }
    }

    override fun onCleared() {
        wsConnection?.close()
        playbackTicker?.cancel()
        jobPoller?.cancel()
        super.onCleared()
    }
}
