package app.moviestudio.job

import app.moviestudio.Job
import app.moviestudio.JobProgressEvent
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.database.JobRepository
import app.moviestudio.routing.JobWebSocketManager
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.FFmpegService
import app.moviestudio.service.SkeletonService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Polls the job queue and executes jobs asynchronously on the server. Every job type — AI media
 * generation, movie skeleton planning and final FFmpeg renders — reports progress through
 * [JobWebSocketManager] so clients can follow along live and react on completion.
 *
 * The worker is demand-driven rather than always-on: [init] hands it the application scope once on
 * startup, and [start] (re)launches the polling loop only when a job is actually started or a
 * recovered job is re-enqueued. The loop stops itself as soon as it finds no more PENDING jobs, so
 * the server does not keep polling the database while the queue is idle.
 */
object JobQueueWorker {
    private val logger = LoggerFactory.getLogger(JobQueueWorker::class.java)

    // Guards [workerJob]/[scope] so a job enqueued right as the loop decides to stop can never be
    // dropped: the loop only stops after re-checking for pending jobs under this same lock, while
    // [start] flips the loop back on (also under the lock) for anything enqueued in the meantime.
    private val lock = Any()
    private var workerJob: kotlinx.coroutines.Job? = null
    private var scope: CoroutineScope? = null

    /** Remembers the application [scope] so [start] can (re)launch the polling loop on demand. */
    fun init(scope: CoroutineScope) {
        synchronized(lock) { this.scope = scope }
    }

    /**
     * (Re)launches the polling loop if it isn't already running. Called whenever a new job is
     * started or a recovered job is re-enqueued. A [scope] may be supplied (and is remembered);
     * otherwise the scope handed to [init] is used.
     */
    fun start(scope: CoroutineScope? = null) {
        synchronized(lock) {
            val effectiveScope = scope ?: this.scope
            if (effectiveScope == null) {
                logger.warn("JobQueueWorker.start() called before init(); no scope available - ignoring.")
                return
            }
            this.scope = effectiveScope
            if (workerJob?.isActive == true) return
            workerJob = effectiveScope.launch(Dispatchers.IO) {
                logger.info("Starting background job queue worker loop.")
                while (isActive) {
                    try {
                        val pendingJobs = JobRepository.pollPendingJobs()
                        if (pendingJobs.isEmpty()) {
                            // Nothing left to do. Stop the loop, but only after re-checking under
                            // the lock so a job enqueued right now (which also calls start()) is
                            // either seen here or restarts the loop - it can't slip through.
                            val stopped = synchronized(lock) {
                                if (JobRepository.pollPendingJobs().isEmpty()) {
                                    workerJob = null
                                    true
                                } else {
                                    false
                                }
                            }
                            if (stopped) {
                                logger.info("No pending jobs found; stopping background job queue worker loop.")
                                return@launch
                            }
                        } else {
                            for (job in pendingJobs) {
                                if (!isActive) break

                                // Atomically / CAS lock the job to RUNNING
                                val lockedJob = JobRepository.lockJobToRunning(job.id)
                                if (lockedJob != null) {
                                    logger.info("Successfully locked job ${job.id} to RUNNING. Delegating...")
                                    // Run on the application scope, not as a child of the polling
                                    // loop, so stopping the loop when the queue drains never cancels
                                    // an in-flight job.
                                    effectiveScope.launch {
                                        executeJob(lockedJob)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error in job queue worker loop: ${e.message}", e)
                    }
                    delay(2000) // Poll every 2 seconds
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

    private suspend fun broadcast(job: Job, status: JobStatus, progress: Int, message: String?, resultUrl: String? = null) {
        JobWebSocketManager.broadcast(
            JobProgressEvent(
                jobId = job.id,
                movieId = job.movieId,
                status = status,
                progress = progress,
                message = message,
                resultUrl = resultUrl,
                jobType = job.type
            )
        )
    }

    private suspend fun executeJob(job: Job) {
        try {
            broadcast(job, JobStatus.RUNNING, 5, "Job is now running")

            val onProgress: suspend (Int, String) -> Unit = { progress, msg ->
                broadcast(job, JobStatus.RUNNING, progress, msg)
            }

            when (job.type) {
                JobType.AI_GEN -> {
                    AIGenerationService.executeAiGenerationJob(job, onProgress)
                    val resultUrl = JobRepository.getById(job.id)?.resultUrl
                    broadcast(job, JobStatus.COMPLETED, 100, "AI generation completed successfully", resultUrl)
                }
                JobType.SKELETON -> {
                    SkeletonService.executeSkeletonJob(job, onProgress)
                    broadcast(job, JobStatus.COMPLETED, 100, "Skeleton generation completed")
                }
                // FFmpegService broadcasts its own COMPLETED event (with the upload URL).
                JobType.FFMPEG_RENDER -> FFmpegService.executeRenderJob(job, onProgress)
            }
        } catch (e: Exception) {
            logger.error("Failed to execute job ${job.id}: ${e.message}", e)
            // Keep the failed job (with its reason) so the user can retry or dismiss it from the
            // background-generations panel instead of it silently vanishing.
            val failedJob = job.copy(status = JobStatus.FAILED, error = e.message ?: "Unknown error")
            JobRepository.update(failedJob)
            broadcast(job, JobStatus.FAILED, 100, "Job failed: ${e.message}")
        }
    }
}
