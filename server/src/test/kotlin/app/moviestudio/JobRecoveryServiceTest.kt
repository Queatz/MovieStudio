package app.moviestudio

import app.moviestudio.service.AsyncTaskState
import app.moviestudio.service.JobRecoveryService
import app.moviestudio.service.JobRecoveryService.RecoveryAction
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the crash-recovery decision logic that reconciles jobs left RUNNING after a
 * server restart. The decision core is pure (no DB / no network), so the whole resume-vs-clean-up
 * matrix is covered here without a live ArangoDB or Model Studio.
 */
class JobRecoveryServiceTest {

    private fun job(
        type: JobType = JobType.AI_GEN,
        taskId: String? = "task-123",
        status: JobStatus = JobStatus.RUNNING,
    ) = Job(
        id = "job-1",
        movieId = "movie-1",
        type = type,
        status = status,
        payload = "{}",
        resultUrl = null,
        taskId = taskId,
    )

    @Test
    fun aiGenJobWithLiveOrSucceededTaskIsResumed() {
        // A pending/running remote task and one that already succeeded can all be re-attached.
        for (state in listOf(AsyncTaskState.PENDING, AsyncTaskState.RUNNING, AsyncTaskState.SUCCEEDED)) {
            assertEquals(
                RecoveryAction.RESUME,
                JobRecoveryService.recoveryActionFor(job(), state),
                "AI_GEN job tracking a $state task must be resumed",
            )
        }
    }

    @Test
    fun aiGenJobWithFailedOrUnknownTaskIsCleanedUp() {
        // The remote task failed, was canceled, or no longer exists -> cannot be resumed.
        for (state in listOf(AsyncTaskState.FAILED, AsyncTaskState.UNKNOWN, null)) {
            assertEquals(
                RecoveryAction.FAIL,
                JobRecoveryService.recoveryActionFor(job(), state),
                "AI_GEN job whose task is $state must be cleaned up",
            )
        }
    }

    @Test
    fun aiGenJobWithoutTaskIdIsCleanedUp() {
        // No tracked async task (e.g. a synchronous music/tts generation, or one that crashed
        // before submitting) -> nothing to resume, so clean it up even if a state is somehow known.
        assertEquals(RecoveryAction.FAIL, JobRecoveryService.recoveryActionFor(job(taskId = null), AsyncTaskState.RUNNING))
        assertEquals(RecoveryAction.FAIL, JobRecoveryService.recoveryActionFor(job(taskId = ""), AsyncTaskState.RUNNING))
    }

    @Test
    fun nonAiGenerationJobsAreAlwaysCleanedUp() {
        // Renders and skeleton planning are not remote async tasks and can never be resumed,
        // regardless of any (spurious) task state.
        for (type in listOf(JobType.FFMPEG_RENDER, JobType.SKELETON)) {
            assertEquals(
                RecoveryAction.FAIL,
                JobRecoveryService.recoveryActionFor(job(type = type), AsyncTaskState.RUNNING),
                "$type jobs must always be cleaned up on restart",
            )
        }
    }

    @Test
    fun taskIdSurvivesJobSerialization() {
        // The task_id must persist through the DB (JSON) round-trip so a re-enqueued job can be
        // resumed by re-polling the same remote task; older documents without the field decode to null.
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(Job.serializer(), job(taskId = "wan-task-abc"))
        assertEquals("wan-task-abc", json.decodeFromString(Job.serializer(), encoded).taskId)

        val legacy = """{"id":"j","movieId":"m","type":"AI_GEN","status":"RUNNING","payload":"{}","resultUrl":null}"""
        assertEquals(null, json.decodeFromString(Job.serializer(), legacy).taskId)
    }
}
