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
    var movies by mutableStateOf<List<Film>>(emptyList())
        private set
    var currentMovie by mutableStateOf<Film?>(null)
        private set
    var timeline by mutableStateOf<FilmTimeline?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    // --------------------------------------------------------------------------------- library
    var libraryAssets by mutableStateOf<List<Asset>>(emptyList())
        private set
    var libraryFilter by mutableStateOf<AssetType?>(null)
    var characters by mutableStateOf<List<Character>>(emptyList())
        private set
    var scenes by mutableStateOf<List<Scene>>(emptyList())
        private set
    var voiceOptions by mutableStateOf(VoiceOptions())
        private set
    var renders by mutableStateOf<List<RenderRecord>>(emptyList())
        private set

    // -------------------------------------------------------------------------------- playback
    var playhead by mutableStateOf(0f)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var zoomScale by mutableStateOf(20f) // pixels per second on the timeline
    var scrollOffset by mutableStateOf(0f) // timeline horizontal scroll, in seconds
    var selectedClipId by mutableStateOf<String?>(null)

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
            try {
                movies = NetworkService.getMovies()
            } catch (e: Exception) {
                errorMessage = "Failed to load movies: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Creates a movie with three default tracks, then opens it right away. */
    fun createMovie(title: String, aspectRatio: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                val movie = Film(
                    id = generateId(),
                    title = title.ifBlank { "Untitled Movie" },
                    totalDuration = 0.0,
                    status = FilmStatus.DRAFT,
                    createdAt = 0, // stamped by the server
                    aspectRatio = aspectRatio
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

    fun deleteMovie(movie: Film) {
        viewModelScope.launch {
            try {
                NetworkService.deleteMovie(movie.id)
                movies = movies.filter { it.id != movie.id }
            } catch (e: Exception) {
                errorMessage = "Failed to delete movie: ${e.message}"
            }
        }
    }

    fun openMovie(movie: Film) {
        currentMovie = movie
        currentScreen = Screen.EDITOR
        playhead = 0f
        isPlaying = false
        selectedClipId = null
        refreshTimeline()
        refreshLibrary()
        refreshCharactersAndScenes()
        refreshVoices()
        refreshRenders()
        refreshActiveJobs()
    }

    fun goToDashboard() {
        isPlaying = false
        currentScreen = Screen.DASHBOARD
        currentMovie = null
        timeline = null
        selectedClipId = null
        loadMovies()
    }

    /** Persists movie metadata changes (title, status, aspect ratio). */
    fun updateMovie(updated: Film) {
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

    fun refreshTimeline() {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                val loaded = NetworkService.getTimeline(movieId)
                timeline = loaded
                currentMovie = loaded.movie
            } catch (e: Exception) {
                errorMessage = "Failed to load timeline: ${e.message}"
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

    fun seek(seconds: Float) {
        val duration = (timeline?.calculatedDuration() ?: 0.0).toFloat()
        playhead = seconds.coerceIn(0f, if (duration > 0f) duration else seconds.coerceAtLeast(0f))
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

    fun addTrack(type: TrackType) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                val zIndex = (timeline?.tracks?.maxOfOrNull { it.track.zIndex } ?: -1) + 1
                NetworkService.createTrack(movieId, Track(generateId(), movieId, type, zIndex))
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to add track: ${e.message}"
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

    /**
     * Adds [asset] to the first compatible track, at [atSeconds] when given (e.g. a drag-and-drop
     * position on the timeline) or at the current playhead position otherwise.
     */
    fun addAssetToTimeline(asset: Asset, atSeconds: Float? = null) {
        val movieId = currentMovie?.id ?: return
        val tracks = timeline?.tracks ?: return
        val targetTrackType = when (asset.type) {
            AssetType.VIDEO, AssetType.IMAGE, AssetType.TEXT -> TrackType.VIDEO
            AssetType.MUSIC, AssetType.AUDIO -> TrackType.MUSIC
            AssetType.VOICE -> TrackType.VOICE
        }
        viewModelScope.launch {
            try {
                val track = tracks.firstOrNull { it.track.type == targetTrackType }?.track ?: run {
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
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to add to timeline: ${e.message}"
            }
        }
    }

    /**
     * Local-only clip update used while a drag is in progress: keeps the canvas in sync at full
     * frame rate without hitting the server. The drag commit calls [updateClip].
     */
    fun updateClipLocal(clip: Clip) {
        timeline = timeline?.copy(
            tracks = timeline!!.tracks.map { trackWithClips ->
                trackWithClips.copy(clips = trackWithClips.clips.map { if (it.id == clip.id) clip else it })
            }
        )
    }

    /** Persists a moved/resized clip. */
    fun updateClip(clip: Clip) {
        val movieId = currentMovie?.id ?: return
        // Optimistically update local state so dragging feels instant.
        timeline = timeline?.copy(
            tracks = timeline!!.tracks.map { trackWithClips ->
                trackWithClips.copy(clips = trackWithClips.clips.map { if (it.id == clip.id) clip else it })
            }
        )
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

    fun deleteClip(clipId: String) {
        val movieId = currentMovie?.id ?: return
        viewModelScope.launch {
            try {
                NetworkService.deleteClip(movieId, clipId)
                if (selectedClipId == clipId) selectedClipId = null
                refreshTimeline()
            } catch (e: Exception) {
                errorMessage = "Failed to delete clip: ${e.message}"
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

    // =================================================================================== library

    fun refreshLibrary() {
        viewModelScope.launch {
            try {
                // The asset library is global (not scoped to a single movie).
                libraryAssets = NetworkService.getLibraryAssets(null, null)
            } catch (e: Exception) {
                errorMessage = "Failed to load library: ${e.message}"
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
                    movieId = null,
                    tags = listOf("description-only"),
                    aiPrompt = description,
                    description = description
                )
                val saved = NetworkService.createAsset(asset)
                refreshLibrary()
                onDone(saved)
            } catch (e: Exception) {
                errorMessage = "Failed to add asset: ${e.message}"
            }
        }
    }

    /** Opens the device file picker and uploads the chosen media into the global library. */
    fun uploadAsset(type: AssetType, description: String = "") {
        viewModelScope.launch {
            try {
                val uploaded = pickAndUploadDeviceFile(type) ?: return@launch
                val asset = Asset(
                    id = generateId(),
                    type = type,
                    ossUrl = uploaded.ossUrl,
                    durationSeconds = uploaded.durationSeconds,
                    movieId = null,
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
            } catch (e: Exception) {
                errorMessage = "Upload failed: ${e.message}"
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

    /** Queues async generation (or regeneration) of an asset's media from its description. */
    fun generateAssetMedia(asset: Asset) {
        viewModelScope.launch {
            try {
                NetworkService.generateAssetMedia(asset.id)
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start generation: ${e.message}"
            }
        }
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
                onDone(asset)
            } catch (e: Exception) {
                errorMessage = "Failed to render sequence: ${e.message}"
                onDone(null)
            }
        }
    }

    // ================================================================================ generation

    /** Queues an AI media generation described by [setup]; optionally regenerating [assetId]. */
    fun generateMedia(setup: GenerationSetup, assetId: String? = null) {
        viewModelScope.launch {
            try {
                NetworkService.generateMedia(currentMovie?.id, setup, assetId)
                refreshActiveJobs()
            } catch (e: Exception) {
                errorMessage = "Failed to start generation: ${e.message}"
            }
        }
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
                val publicUrl = upload.uploadUrl.substringBefore('?')
                NetworkService.createAsset(
                    Asset(
                        id = generateId(),
                        type = AssetType.IMAGE,
                        ossUrl = publicUrl,
                        durationSeconds = 5.0,
                        movieId = null,
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

    /** Picks an audio sample from the device, then enrolls a new cloned voice with it. */
    fun createVoiceCloneFromDevice(name: String, onDone: (VoiceClone?) -> Unit) {
        viewModelScope.launch {
            try {
                val uploaded = pickAndUploadDeviceFile(AssetType.AUDIO)
                if (uploaded == null) {
                    onDone(null)
                    return@launch
                }
                val clone = NetworkService.createVoiceClone(name, uploaded.ossUrl)
                refreshVoices()
                onDone(clone)
            } catch (e: Exception) {
                errorMessage = "Voice cloning failed: ${e.message}"
                onDone(null)
            }
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
                        if (finished.isNotEmpty() && wsConnection == null) {
                            // Without WS events we still want fresh media after completions.
                            refreshLibrary()
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
                refreshLibrary()
                when (event.jobType) {
                    JobType.SKELETON -> {
                        // The server planned and inserted timeline items: reload the timeline.
                        if (event.movieId == currentMovie?.id) refreshTimeline()
                    }
                    JobType.FFMPEG_RENDER -> {
                        if (event.movieId == currentMovie?.id) {
                            refreshRenders()
                            refreshTimeline()
                        }
                    }
                    else -> {
                        if (event.movieId == currentMovie?.id) refreshTimeline()
                    }
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
