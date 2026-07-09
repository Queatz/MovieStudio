package app.moviestudio.service

import app.moviestudio.config.Env
import app.moviestudio.database.PendingRenderUploadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Periodically re-attempts the object-storage (Alibaba OSS) upload of movies that finished
 * rendering but could not be uploaded at the time (see [FFmpegService.persistPendingUpload]).
 *
 * When a render's upload fails, the finished file is copied to a durable directory and a
 * [app.moviestudio.PendingRenderUpload] record is stored. This worker runs a sweep every
 * [retryIntervalMs] that tries to upload each pending render again; on success the render is
 * finalized exactly like a first-try upload ([FFmpegService.finalizeSuccessfulUpload]) and the
 * local file + record are removed, so a transient OSS outage never loses a rendered movie.
 *
 * Like [app.moviestudio.job.JobQueueWorker], the loop is demand-driven rather than always-on:
 * [init] hands it the application scope on startup, [start] (re)launches the loop, and the loop
 * stops itself once no pending uploads remain (so the server does not keep polling while there is
 * nothing to retry). It is re-kicked by [FFmpegService.persistPendingUpload] whenever a new failed
 * upload is recorded, and by [start] on startup to pick up anything left over from a previous run.
 */
object RenderUploadRetryService {
    private val logger = LoggerFactory.getLogger(RenderUploadRetryService::class.java)

    private val lock = Any()
    private var workerJob: kotlinx.coroutines.Job? = null
    private var scope: CoroutineScope? = null

    /** How often (ms) to re-attempt pending render uploads. Overridable via RENDER_UPLOAD_RETRY_INTERVAL_MS. */
    private val retryIntervalMs: Long =
        Env.get("RENDER_UPLOAD_RETRY_INTERVAL_MS", "60000").toLongOrNull()?.coerceAtLeast(1000) ?: 60000L

    /** Remembers the application [scope] so [start] can (re)launch the retry loop on demand. */
    fun init(scope: CoroutineScope) {
        synchronized(lock) { this.scope = scope }
    }

    /**
     * (Re)launches the retry loop if it isn't already running. Called on startup (to pick up
     * pending uploads left by a previous process) and whenever a new failed upload is recorded. A
     * [scope] may be supplied (and is remembered); otherwise the scope handed to [init] is used.
     */
    fun start(scope: CoroutineScope? = null) {
        synchronized(lock) {
            val effectiveScope = scope ?: this.scope
            if (effectiveScope == null) {
                logger.warn("RenderUploadRetryService.start() called before init(); no scope available - ignoring.")
                return
            }
            this.scope = effectiveScope
            if (workerJob?.isActive == true) return
            workerJob = effectiveScope.launch(Dispatchers.IO) {
                logger.info("Starting render-upload retry worker loop (interval {}ms).", retryIntervalMs)
                while (isActive) {
                    val pending = withContext(Dispatchers.IO) {
                        runCatching { PendingRenderUploadRepository.listAll() }.getOrElse {
                            logger.error("Could not query pending render uploads: ${it.message}", it)
                            emptyList()
                        }
                    }
                    if (pending.isEmpty()) {
                        // Nothing left to retry. Stop the loop, but re-check under the lock so a
                        // failure recorded right now (which also calls start()) either is seen here
                        // or restarts the loop - it can't slip through.
                        val stopped = synchronized(lock) {
                            if (PendingRenderUploadRepository.listAll().isEmpty()) {
                                workerJob = null
                                true
                            } else {
                                false
                            }
                        }
                        if (stopped) {
                            logger.info("No pending render uploads remain; stopping retry worker loop.")
                            return@launch
                        }
                    } else {
                        logger.info("Retrying {} pending render upload(s).", pending.size)
                        for (record in pending) {
                            if (!isActive) break
                            runCatching { FFmpegService.retryPendingUpload(record) }
                                .onFailure { logger.error("Unexpected error retrying pending upload ${record.id}: ${it.message}", it) }
                        }
                        delay(retryIntervalMs)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            workerJob?.cancel()
            workerJob = null
        }
    }

    /**
     * Runs a single retry sweep: attempts to (re)upload every currently pending render and returns
     * how many were resolved (uploaded successfully or dropped because their local file vanished).
     * Self-contained (launches nothing) so it can be driven directly from tests.
     */
    suspend fun retryPendingUploadsOnce(): Int {
        val pending = withContext(Dispatchers.IO) {
            runCatching { PendingRenderUploadRepository.listAll() }.getOrElse {
                logger.error("Could not query pending render uploads: ${it.message}", it)
                emptyList()
            }
        }
        if (pending.isEmpty()) return 0
        logger.info("Retrying {} pending render upload(s).", pending.size)
        var resolved = 0
        for (record in pending) {
            if (FFmpegService.retryPendingUpload(record)) resolved++
        }
        return resolved
    }
}
