package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.GenerationCommon
import app.moviestudio.service.QwenConfig
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the per-asset AI-cost ledger: the [QwenConfig] per-token pricing lookup and
 * [GenerationCommon.finalize] recording each AI call's token cost onto the asset (a brand-new
 * ledger on creation, appended to on every regeneration).
 */
class AssetLedgerTest {

    private val collections = listOf("assets", "clips", "jobs")
    private var dbAvailable = true

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            dbAvailable = false
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        if (!dbAvailable) return
        try {
            collections.forEach { ArangoDatabase.db.collection(it).truncate() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun perTokenPricingIsPositiveAndModelSpecific() {
        // Cost per token is a tiny fraction of a cent, but always positive.
        val chatRate = QwenConfig.usdPerToken(QwenConfig.chatModel)
        val ttsRate = QwenConfig.usdPerToken(QwenConfig.ttsModel)
        val defaultRate = QwenConfig.usdPerToken("wan2.7-t2v")
        assertTrue(chatRate > 0.0 && chatRate < 0.01)
        assertTrue(ttsRate > 0.0 && ttsRate < 0.01)
        assertTrue(defaultRate > 0.0)
        // Different model families are priced differently.
        assertTrue(ttsRate != chatRate)
    }

    @Test
    fun finalizeRecordsLedgerOnCreateAndAppendsOnRegenerate() {
        Assume.assumeTrue("ArangoDB not available", dbAvailable)

        val movieId = UUID.randomUUID().toString()
        val job = Job(
            id = UUID.randomUUID().toString(),
            movieId = movieId,
            type = JobType.AI_GEN,
            status = JobStatus.RUNNING,
            payload = "",
            resultUrl = null,
            createdAt = System.currentTimeMillis()
        )
        JobRepository.insert(job)

        val payload = AiJobPayload(
            setup = GenerationSetup(kind = "video", prompt = "a cat surfing"),
            targetType = "VIDEO"
        )
        val genCall = AiLedgerEntry("Generated video (wan2.7-t2v)", "wan2.7-t2v", tokens = 1200, costPerToken = 0.000002)
        val created = GenerationCommon.finalize(
            job, payload, "https://oss.example/v.mp4", 5.0, ledgerEntries = listOf(genCall)
        )

        val savedCreated = AssetRepository.getById(created.id)
        assertNotNull(savedCreated)
        assertEquals(1, savedCreated.ledger.size)
        assertEquals(1200L, savedCreated.ledger.totalTokens())
        assertEquals(1200 * 0.000002, savedCreated.ledger.totalCostUsd(), 1e-9)

        // Regenerating the same asset appends a second entry so the ledger keeps the full history.
        val regenJob = job.copy(id = UUID.randomUUID().toString(), status = JobStatus.RUNNING)
        JobRepository.insert(regenJob)
        val refineCall = AiLedgerEntry("Refined video prompt", "qwen-plus", tokens = 300, costPerToken = 0.0000004)
        val updated = GenerationCommon.finalize(
            regenJob, payload.copy(assetId = created.id), "https://oss.example/v2.mp4", 6.0,
            ledgerEntries = listOf(refineCall)
        )

        val savedUpdated = AssetRepository.getById(updated.id)
        assertNotNull(savedUpdated)
        assertEquals(2, savedUpdated.ledger.size)
        assertEquals(1500L, savedUpdated.ledger.totalTokens())
    }
}
