package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.DbCollection
import app.moviestudio.database.JobRepository
import app.moviestudio.database.MovieRepository
import app.moviestudio.database.PendingRenderUploadRepository
import app.moviestudio.database.RenderRepository
import app.moviestudio.service.FFmpegService
import app.moviestudio.service.RenderUploadRetryService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.UUID
import kotlin.test.*

/**
 * Covers the "persist locally and retry the upload" behaviour: when a rendered movie could not be
 * uploaded to OSS, a [PendingRenderUpload] record + local file are kept, and a later retry sweep
 * (see [RenderUploadRetryService]) finishes the render — inserting the [RenderRecord], marking the
 * movie/job COMPLETED and removing the local file + pending record.
 *
 * In the test environment OSS is unconfigured, so [app.moviestudio.storage.OssService.uploadFile]
 * returns a mock URL instead of failing, which lets us exercise the successful-retry path.
 */
class RenderUploadRetryTest {

    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection(DbCollection.MOVIES.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.JOBS.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.RENDERS.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.PENDING_RENDER_UPLOADS.collectionName).truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection(DbCollection.MOVIES.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.JOBS.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.RENDERS.collectionName).truncate()
            ArangoDatabase.db.collection(DbCollection.PENDING_RENDER_UPLOADS.collectionName).truncate()
        } catch (e: Exception) {
            // Ignore
        }
        tempFiles.forEach { runCatching { it.delete() } }
    }

    /** A durable local file standing in for a persisted render awaiting upload. */
    private fun localRenderFile(): File {
        val file = File.createTempFile("pending_render_", ".mp4").apply { writeText("RENDERED MOVIE BYTES") }
        tempFiles.add(file)
        return file
    }

    @Test
    fun retrySweepUploadsPendingRenderAndFinalizesIt() = runBlocking {
        val movieId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()

        // The movie is still RENDERING and the render job still RUNNING while the upload is pending.
        MovieRepository.insert(
            Movie(
                id = movieId,
                title = "Pending Upload Movie",
                totalDuration = 12.0,
                status = MovieStatus.RENDERING,
                aspectRatio = "16:9",
                createdAt = System.currentTimeMillis()
            )
        )
        JobRepository.insert(
            Job(
                id = jobId,
                movieId = movieId,
                type = JobType.FFMPEG_RENDER,
                status = JobStatus.RUNNING,
                payload = "{}",
                resultUrl = null,
                createdAt = System.currentTimeMillis()
            )
        )

        val file = localRenderFile()
        PendingRenderUploadRepository.insert(
            PendingRenderUpload(
                id = UUID.randomUUID().toString(),
                jobId = jobId,
                movieId = movieId,
                objectKey = "renders/$movieId/${UUID.randomUUID()}.mp4",
                localFilePath = file.absolutePath,
                durationSeconds = 12.0,
                aspectRatio = "16:9",
                createdAt = System.currentTimeMillis()
            )
        )

        val resolved = RenderUploadRetryService.retryPendingUploadsOnce()
        assertEquals(1, resolved, "the single pending upload should be resolved")

        // The render is now recorded and the movie/job are finished.
        val renders = RenderRepository.queryByMovieId(movieId)
        assertEquals(1, renders.size, "a RenderRecord should be created once the upload succeeds")
        assertTrue(renders.first().url.isNotBlank(), "the render should carry its uploaded URL")

        assertEquals(MovieStatus.COMPLETED, MovieRepository.getById(movieId)?.status)

        val job = JobRepository.getById(jobId)
        assertNotNull(job)
        assertEquals(JobStatus.COMPLETED, job.status)
        assertEquals(renders.first().url, job.resultUrl)

        // The durable local copy and the pending record are cleaned up once uploaded.
        assertFalse(file.exists(), "the local render file should be deleted after a successful upload")
        assertTrue(PendingRenderUploadRepository.queryByMovieId(movieId).isEmpty(), "the pending record should be removed")
    }

    @Test
    fun retryDropsPendingRecordWhoseLocalFileIsMissing() = runBlocking {
        val movieId = UUID.randomUUID().toString()
        val pending = PendingRenderUpload(
            id = UUID.randomUUID().toString(),
            jobId = UUID.randomUUID().toString(),
            movieId = movieId,
            objectKey = "renders/$movieId/${UUID.randomUUID()}.mp4",
            localFilePath = File(System.getProperty("java.io.tmpdir"), "does_not_exist_${UUID.randomUUID()}.mp4").absolutePath,
            durationSeconds = 5.0,
            aspectRatio = "16:9",
            createdAt = System.currentTimeMillis()
        )
        PendingRenderUploadRepository.insert(pending)

        // A pending record with no file can never upload, so it is dropped rather than retried forever.
        assertTrue(FFmpegService.retryPendingUpload(pending))
        assertNull(PendingRenderUploadRepository.getById(pending.id))
        assertTrue(RenderRepository.queryByMovieId(movieId).isEmpty(), "no render should be recorded when the file is gone")
    }
}
