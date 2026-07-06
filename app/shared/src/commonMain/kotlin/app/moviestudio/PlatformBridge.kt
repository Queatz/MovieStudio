package app.moviestudio

/**
 * Platform bridge for capabilities that need direct browser APIs on web targets. Non-web
 * platforms provide graceful no-op fallbacks (the app then relies on polling, etc.).
 */

/** Handle to an open job-events stream; closing it stops the delivery of events. */
interface JobEventsConnection {
    fun close()
}

/**
 * Opens a WebSocket to [wsUrl] delivering raw JSON [app.moviestudio.JobProgressEvent] frames to
 * [onMessage]. Returns null when the platform has no WebSocket support (callers fall back to
 * polling).
 */
expect fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection?

/**
 * One sound that should currently be audible in the movie preview, positioned at
 * [positionSeconds] within its media file.
 */
data class AudioPlayItem(
    val key: String,
    val url: String,
    val positionSeconds: Double,
    val volume: Double
)

/**
 * Reconciles the platform audio-element pool with [items]: sounds not listed stop, listed ones
 * play (when [playing]) at their target position/volume. Powers music/voice/sound playback in
 * the movie preview.
 */
expect fun updateAudioPlayback(items: List<AudioPlayItem>, playing: Boolean)

/**
 * How a [PreloadMediaItem] should be preloaded, so the platform can pick the matching element
 * (`<video>` / `<audio>` / `<img>`) and preload strategy.
 */
enum class PreloadKind { VIDEO, IMAGE, AUDIO }

/**
 * One media file that appears on the current timeline and should be fetched/decoded ahead of time
 * (and kept warm) so the movie preview plays it back instantly — instead of stalling — when the
 * playhead reaches its clip.
 */
data class PreloadMediaItem(
    val url: String,
    val kind: PreloadKind
)

/**
 * Every distinct, media-bearing asset referenced by [timeline]'s clips, each tagged with the
 * [PreloadKind] to preload it as (derived from the asset's [AssetType]). Description-only clips
 * (blank `ossUrl`) are skipped and each URL appears once, in first-seen order. Pure and
 * deterministic, so it is unit-testable and its result can be handed to [preloadTimelineMedia].
 */
fun collectPreloadMedia(timeline: MovieTimeline?, assets: List<Asset>): List<PreloadMediaItem> {
    if (timeline == null) return emptyList()
    val assetsById = assets.associateBy { it.id }
    val seen = HashSet<String>()
    val result = ArrayList<PreloadMediaItem>()
    timeline.tracks.forEach { trackWithClips ->
        trackWithClips.clips.forEach { clip ->
            val asset = assetsById[clip.assetId] ?: return@forEach
            val url = asset.ossUrl
            if (url.isBlank() || !seen.add(url)) return@forEach
            val kind = when (asset.type) {
                AssetType.IMAGE -> PreloadKind.IMAGE
                AssetType.VIDEO -> PreloadKind.VIDEO
                else -> PreloadKind.AUDIO
            }
            result.add(PreloadMediaItem(url, kind))
        }
    }
    return result
}

/**
 * Preloads — and keeps warm for the rest of the session — every media file on the current timeline
 * ([items], typically from [collectPreloadMedia]). On web targets this maintains a pool of hidden,
 * CORS-loaded `<video>` / `<audio>` elements and `<img>` loaders keyed by URL, so that when the
 * playhead reaches a clip its media is already fetched/decoded and preview playback starts
 * instantly. Repeated calls reconcile the pool with [items]; media no longer on the timeline is
 * released. No-op on platforms without a DOM (desktop / Android), where images already stream
 * through Coil.
 */
expect fun preloadTimelineMedia(items: List<PreloadMediaItem>)

/**
 * Captures the current frame of the movie preview's video element as a PNG and PUTs it to
 * [uploadUrl] (a pre-signed OSS URL). Returns true on success, false when there is no frame to
 * capture or the platform cannot capture frames.
 */
expect suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean

/** Asks the platform to download the file at [url] with the suggested [fileName]. */
expect fun triggerDownload(url: String, fileName: String)

/**
 * Requests native fullscreen playback for the movie preview's video element (the same shared
 * `<video>` the [app.moviestudio.VideoPlayer] drives). Used to watch a finished render
 * distraction-free. No-op on platforms without a fullscreen-capable video element.
 */
expect fun requestVideoFullscreen()

/**
 * Positions the media inside the movie preview's center-crop window (CSS `object-position`).
 * [xPercent]/[yPercent] are 0-100 where 50/50 is centered — mirroring the clip's
 * [app.moviestudio.EffectsConfig.offsetX]/[app.moviestudio.EffectsConfig.offsetY].
 */
expect fun setPreviewObjectPosition(xPercent: Double, yPercent: Double)

/**
 * Shows or hides the movie preview's Default-renderer `<video>` overlay (the shared, absolutely
 * positioned element [app.moviestudio.VideoPlayer] drives on web targets), without touching its
 * `display` — which already tracks whether a video clip is under the playhead — so visibility can
 * be restored exactly as it was. The overlay is drawn ABOVE the entire UI, so any
 * [app.moviestudio.ui.StudioDialog] opened while it is showing would otherwise be hidden
 * underneath it; callers must hide it while a dialog is open and show it again once none remain.
 * No-op on platforms without such an overlay (the WebGL renderer and non-web targets render
 * entirely inside the Compose canvas already, so they never need this).
 */
expect fun setPreviewOverlayVisible(visible: Boolean)

/**
 * Plays one short sequencer note at [frequencyHz] for [durationSeconds]. [waveform] is one of
 * "sine" | "square" | "saw" | "triangle", or "sample" — then [sampleUrl] is fetched (and cached)
 * and pitch-shifted by playback rate, matching the server-side synthesizer. No-op on platforms
 * without an audio engine.
 */
expect fun playSequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double = 0.5,
    sampleUrl: String? = null
)

/**
 * Starts realtime speech-to-text dictation. On web targets this prefers the browser Web Speech
 * API and, when that is unavailable (e.g. Firefox has no `SpeechRecognition`), falls back to
 * streaming microphone audio to the server's `/api/speech/ws` relay (Qwen realtime ASR).
 * [onResult] is called with the full text recognized so far in this dictation session — interim
 * results included, so callers can live-update a text field while the user speaks.
 * Returns false when the platform has no speech recognition support at all or the microphone is
 * unavailable (callers then simply don't enter dictation mode). Note that the fallback resolves
 * microphone permission asynchronously, so a true return only means dictation was started.
 */
expect fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean

/** Stops the in-progress realtime speech dictation session (no-op when none is active). */
expect fun stopRealtimeSpeechInput()

/**
 * Decodes the audio file at [url] and reduces it to [buckets] normalized peak amplitudes
 * (each in 0f..1f), suitable for drawing a compact waveform. On web targets this fetches the
 * file and uses the Web Audio API to decode it; platforms without an audio engine return null
 * so callers can fall back to a synthetic placeholder waveform.
 */
expect suspend fun loadAudioWaveform(url: String, buckets: Int): FloatArray?

/** Handle returned by [installMarkdownShortcutGuard]; removes the underlying browser listener. */
interface KeyGuardHandle {
    fun dispose()
}

/**
 * On web targets, installs a capture-phase browser `keydown` listener that calls
 * `preventDefault()` for Ctrl/Cmd+B, Ctrl/Cmd+I and Ctrl/Cmd+U while [isActive] returns true.
 * Compose's `Modifier.onPreviewKeyEvent` returning `true` only stops the event from reaching
 * other Compose handlers — it does NOT stop the browser from running its own default action for
 * these combos (e.g. toggling the bookmarks bar, or "view source"), so the DOM event has to be
 * prevented directly. [isActive] is polled on every keydown so the guard only fires while the
 * markdown editor that installed it actually has focus. No-op (with a no-op handle) on platforms
 * without a browser, where these shortcuts have no default browser action to suppress.
 */
expect fun installMarkdownShortcutGuard(isActive: () -> Boolean): KeyGuardHandle
