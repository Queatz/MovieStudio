package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.DbCollection
import app.moviestudio.database.JobRepository
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.GenerationCommon
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers asset type conversion during in-place regeneration: editing an IMAGE asset in the
 * generate-media dialog and switching to Video must convert the asset to a VIDEO asset (and vice
 * versa), with the previous media's type recorded on its restorable history entry. An explicit
 * [AiJobPayload.targetType] (used by the description-based /generate route) still pins the type.
 */
class AssetTypeConversionTest {

    private val collections = listOf(DbCollection.ASSETS, DbCollection.CLIPS, DbCollection.JOBS)
    private var dbAvailable = true

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            dbAvailable = false
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        if (!dbAvailable) return
        try {
            collections.forEach { ArangoDatabase.db.collection(it.collectionName).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun insertAsset(type: AssetType, ossUrl: String): Asset {
        val asset = Asset(
            id = UUID.randomUUID().toString(),
            type = type,
            ossUrl = ossUrl,
            durationSeconds = 5.0,
            movieId = null,
            tags = emptyList(),
            aiPrompt = "original prompt",
            createdAt = System.currentTimeMillis()
        )
        AssetRepository.insert(asset)
        return asset
    }

    private fun insertJob(): Job {
        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = "",
            type = JobType.AI_GEN,
            status = JobStatus.RUNNING,
            payload = "",
            resultUrl = null,
            createdAt = System.currentTimeMillis()
        )
        JobRepository.insert(job)
        return job
    }

    @Test
    fun regeneratingAnImageAsVideoConvertsTheAssetType() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val image = insertAsset(AssetType.IMAGE, "https://oss.example/pic.png")
        // The generate-media dialog with the kind switched to "video" sends no targetType.
        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "video", prompt = "animate the picture"),
            assetId = image.id
        )
        GenerationCommon.finalize(insertJob(), payload, "https://oss.example/clip.mp4", 6.0)

        val updated = AssetRepository.getById(image.id)
        assertNotNull(updated)
        assertEquals(AssetType.VIDEO, updated.type)
        assertEquals("https://oss.example/clip.mp4", updated.ossUrl)
        // The replaced media went onto the history with its original type recorded.
        assertEquals(1, updated.history.size)
        assertEquals("https://oss.example/pic.png", updated.history[0].ossUrl)
        assertEquals(AssetType.IMAGE, updated.history[0].type)
    }

    @Test
    fun regeneratingAVideoAsImageConvertsTheAssetType() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val video = insertAsset(AssetType.VIDEO, "https://oss.example/clip.mp4")
        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "image", prompt = "a still of the scene"),
            assetId = video.id
        )
        GenerationCommon.finalize(insertJob(), payload, "https://oss.example/still.png", 5.0)

        val updated = AssetRepository.getById(video.id)
        assertNotNull(updated)
        assertEquals(AssetType.IMAGE, updated.type)
        assertEquals(1, updated.history.size)
        assertEquals(AssetType.VIDEO, updated.history[0].type)
    }

    @Test
    fun explicitTargetTypeStillPinsTheAssetType() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        // The description-based /generate route pins the asset's own type via targetType even
        // when the stored setup kind disagrees.
        val voice = insertAsset(AssetType.VOICE, "https://oss.example/voice.mp3")
        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "tts", prompt = "new narration"),
            assetId = voice.id,
            targetType = AssetType.VOICE.name
        )
        GenerationCommon.finalize(insertJob(), payload, "https://oss.example/voice2.mp3", 4.0)

        val updated = AssetRepository.getById(voice.id)
        assertNotNull(updated)
        assertEquals(AssetType.VOICE, updated.type)
    }
}
