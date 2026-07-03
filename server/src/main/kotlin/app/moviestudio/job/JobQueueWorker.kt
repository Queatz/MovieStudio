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
 */
object JobQueueWorker {
    private val logger = LoggerFactory.getLogger(JobQueueWorker::class.java)
    private var workerJob: kotlinx.coroutines.Job? = null

    fun start(scope: CoroutineScope) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch(Dispatchers.IO) {
            logger.info("Starting background job queue worker loop.")
            while (isActive) {
                try {
                    val pendingJobs = JobRepository.pollPendingJobs()
                    for (job in pendingJobs) {
                        if (!isActive) break

                        // Atomically / CAS lock the job to RUNNING
                        val lockedJob = JobRepository.lockJobToRunning(job.id)
                        if (lockedJob != null) {
                            logger.info("Successfully locked job ${job.id} to RUNNING. Delegating...")
                            launch {
                                executeJob(lockedJob)
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

    fun stop() {
        workerJob?.cancel()
        workerJob = null
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
            val failedJob = job.copy(status = JobStatus.FAILED)
            JobRepository.update(failedJob)
            broadcast(job, JobStatus.FAILED, 100, "Job failed: ${e.message}")
        }
    }
}
