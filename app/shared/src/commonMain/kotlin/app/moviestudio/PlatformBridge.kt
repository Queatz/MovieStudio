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
 * Captures the current frame of the movie preview's video element as a PNG and PUTs it to
 * [uploadUrl] (a pre-signed OSS URL). Returns true on success, false when there is no frame to
 * capture or the platform cannot capture frames.
 */
expect suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean

/** Asks the platform to download the file at [url] with the suggested [fileName]. */
expect fun triggerDownload(url: String, fileName: String)

/**
 * Positions the media inside the movie preview's center-crop window (CSS `object-position`).
 * [xPercent]/[yPercent] are 0-100 where 50/50 is centered — mirroring the clip's
 * [app.moviestudio.EffectsConfig.offsetX]/[app.moviestudio.EffectsConfig.offsetY].
 */
expect fun setPreviewObjectPosition(xPercent: Double, yPercent: Double)

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
