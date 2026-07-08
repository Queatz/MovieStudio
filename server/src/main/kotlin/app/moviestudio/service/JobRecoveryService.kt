package app.moviestudio.service

import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.database.JobRepository
import app.moviestudio.job.JobQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Reconciles background generation jobs left in the RUNNING state by a previous server process.
 *
 * The [JobQueueWorker] drives each RUNNING job from an in-memory coroutine, so after a crash or
 * restart those coroutines are gone and any job still marked RUNNING is orphaned - it would
 * otherwise appear stuck forever in the background-generations panel. On startup this service:
 *  - resumes the jobs that CAN be picked back up: AI generations that were waiting on a remote
 *    Alibaba Model Studio / DashScope async task ([Job.taskId]) which is still pending/running or
 *    has already succeeded. These are simply re-enqueued (set back to PENDING) - their `task_id`
 *    survives the round-trip, so when the worker re-runs them [QwenAIService.runAsyncGenerationTask]
 *    re-attaches to and re-polls the same live task instead of resubmitting; and
 *  - cleans up everything else (renders, skeleton planning, synchronous generations, and async
 *    tasks whose remote task has failed or vanished) by marking it FAILED with a clear reason, so
 *    the user can retry or dismiss it.
 */
object JobRecoveryService {
    private val logger = LoggerFactory.getLogger(JobRecoveryService::class.java)

    /** Reason recorded on jobs that were interrupted by a restart and could not be resumed. */
    const val INTERRUPTED_ERROR = "Interrupted by a server restart and could not be resumed"

    /** What to do with a RUNNING job discovered on startup. */
    enum class RecoveryAction { RESUME, FAIL }

    /**
     * Pure decision (no I/O) used by [recoverInterruptedJobs] and covered by unit tests: a RUNNING
     * job may be [RecoveryAction.RESUME]d only when it is an AI generation ([JobType.AI_GEN]) that
     * was waiting on a remote async task ([Job.taskId]) whose latest [taskState] is still live
     * (PENDING/RUNNING) or already SUCCEEDED. Any other job - a different job type, one with no
     * tracked task, or one whose task has FAILED/vanished ([AsyncTaskState.UNKNOWN]/null) - is
     * cleaned up with [RecoveryAction.FAIL].
     */
    fun recoveryActionFor(job: Job, taskState: AsyncTaskState?): RecoveryAction {
        if (job.type != JobType.AI_GEN) return RecoveryAction.FAIL
        if (job.taskId.isNullOrBlank()) return RecoveryAction.FAIL
        return when (taskState) {
            AsyncTaskState.PENDING, AsyncTaskState.RUNNING, AsyncTaskState.SUCCEEDED -> RecoveryAction.RESUME
            AsyncTaskState.FAILED, AsyncTaskState.UNKNOWN, null -> RecoveryAction.FAIL
        }
    }

    /**
     * Scans all RUNNING jobs and either re-enqueues them (resumable Model Studio async tasks) or
     * marks them FAILED (everything else). [service] is injectable for testing; it defaults to the
     * configured [AIGenerationService].
     */
    suspend fun recoverInterruptedJobs(service: AIGenerationService = AIGenerationService) {
        val running = runCatching { JobRepository.queryRunningJobs() }.getOrElse {
            logger.error("Could not query interrupted (RUNNING) jobs on startup: ${it.message}", it)
            return
        }
        if (running.isEmpty()) {
            logger.info("No interrupted (RUNNING) jobs found on startup.")
            return
        }
        logger.info("Found {} interrupted (RUNNING) job(s) on startup; reconciling...", running.size)

        for (job in running) {
            val taskId = job.taskId
            // Only ask the provider about jobs that were actually tracking an async task.
            val taskState: AsyncTaskState? = if (job.type == JobType.AI_GEN && !taskId.isNullOrBlank()) {
                runCatching { service.probeAsyncTaskStatus(taskId) }
                    .getOrElse {
                        logger.warn("Could not probe task {} for job {}: {}", taskId, job.id, it.message)
                        AsyncTaskState.UNKNOWN
                    }
            } else {
                null
            }

            when (recoveryActionFor(job, taskState)) {
                RecoveryAction.RESUME -> {
                    logger.info("Resuming interrupted job {} - its Model Studio task {} is {}.", job.id, taskId, taskState)
                    // Keep the task_id so the worker re-attaches to the live task instead of resubmitting.
                    runCatching { JobRepository.update(job.copy(status = JobStatus.PENDING)) }
                        .onSuccess {
                            // A recovered job is back in the queue: make sure the worker is running.
                            JobQueueWorker.start()
                        }
                        .onFailure { logger.error("Could not re-enqueue interrupted job ${job.id}: ${it.message}", it) }
                }
                RecoveryAction.FAIL -> {
                    logger.info(
                        "Cleaning up interrupted job {} (type={}, task={}, state={}) - it cannot be resumed.",
                        job.id, job.type, taskId, taskState,
                    )
                    runCatching { JobRepository.update(job.copy(status = JobStatus.FAILED, error = INTERRUPTED_ERROR)) }
                        .onFailure { logger.error("Could not clean up interrupted job ${job.id}: ${it.message}", it) }
                }
            }
        }
    }

    /** Launches [recoverInterruptedJobs] once on the given [scope]; call before the job worker starts. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { recoverInterruptedJobs() }
                .onFailure { logger.error("Interrupted-job recovery failed on startup: ${it.message}", it) }
        }
    }
}
