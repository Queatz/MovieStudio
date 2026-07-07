package app.moviestudio

// Android fallbacks: no WebSocket push (clients poll), no preview audio pool, no frame capture
// and no browser downloads.

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? = null

actual fun updateAudioPlayback(items: List<AudioPlayItem>, playing: Boolean) {
    // No-op on Android: preview audio playback is a browser feature.
}

actual fun preloadTimelineMedia(items: List<PreloadMediaItem>) {
    // No-op on Android: media preloading warms a browser <video>/<audio>/<img> pool.
}

actual suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean = false

actual fun triggerDownload(url: String, fileName: String) {
    // No-op on Android.
}

actual fun downloadTextFile(content: String, fileName: String) {
    // No-op on Android.
}

actual fun requestVideoFullscreen() {
    // No-op on Android: the preview video element is a browser feature.
}

actual fun setPreviewObjectPosition(xPercent: Double, yPercent: Double) {
    // No-op on Android: the preview video element is a browser feature.
}

actual fun setPreviewOverlayVisible(visible: Boolean) {
    // No-op on Android: the preview video element is a browser feature.
}

actual fun playSequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double,
    sampleUrl: String?
) {
    // No-op on Android: sequencer note playback is a browser (WebAudio) feature.
}

// Realtime speech dictation is a browser (Web Speech API) feature.
actual fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean = false

actual fun stopRealtimeSpeechInput() {
    // No-op on Android.
}

// Waveform decoding is a browser (WebAudio) feature; Android callers use a synthetic fallback.
actual suspend fun loadAudioWaveform(url: String, buckets: Int): FloatArray? = null

// No browser default action to suppress on Android.
actual fun installMarkdownShortcutGuard(isActive: () -> Boolean): KeyGuardHandle =
    object : KeyGuardHandle {
        override fun dispose() {}
    }
