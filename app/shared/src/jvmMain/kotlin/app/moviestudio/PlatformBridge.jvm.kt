package app.moviestudio

// Desktop (JVM) fallbacks: no WebSocket push (clients poll), no preview audio pool, no frame
// capture and no browser downloads.

actual fun connectJobEvents(wsUrl: String, onMessage: (String) -> Unit): JobEventsConnection? = null

actual fun updateAudioPlayback(items: List<AudioPlayItem>, playing: Boolean) {
    // No-op on desktop: preview audio playback is a browser feature.
}

actual suspend fun captureVideoFrameAndUpload(uploadUrl: String): Boolean = false

actual fun triggerDownload(url: String, fileName: String) {
    // No-op on desktop.
}

actual fun requestVideoFullscreen() {
    // No-op on desktop: the preview video element is a browser feature.
}

actual fun setPreviewObjectPosition(xPercent: Double, yPercent: Double) {
    // No-op on desktop: the preview video element is a browser feature.
}

actual fun playSequencerTone(
    waveform: String,
    frequencyHz: Double,
    durationSeconds: Double,
    volume: Double,
    sampleUrl: String?
) {
    // No-op on desktop: sequencer note playback is a browser (WebAudio) feature.
}

// Realtime speech dictation is a browser (Web Speech API) feature.
actual fun startRealtimeSpeechInput(onResult: (String) -> Unit): Boolean = false

actual fun stopRealtimeSpeechInput() {
    // No-op on desktop.
}

// Waveform decoding is a browser (WebAudio) feature; desktop callers use a synthetic fallback.
actual suspend fun loadAudioWaveform(url: String, buckets: Int): FloatArray? = null

// No browser default action to suppress on desktop.
actual fun installMarkdownShortcutGuard(isActive: () -> Boolean): KeyGuardHandle =
    object : KeyGuardHandle {
        override fun dispose() {}
    }
