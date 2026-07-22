package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.DbCollection
import app.moviestudio.database.VisualStyleRepository
import app.moviestudio.routing.expandReferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that a selected visual style is appended to the generation prompt after any
 * character/scene/reference additions, in the exact `"\n\nVisual style: ...\n\n"` form.
 */
class VisualStyleExpansionTest {

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection(DbCollection.VISUAL_STYLES.collectionName).truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection(DbCollection.VISUAL_STYLES.collectionName).truncate()
        } catch (_: Exception) {
            // Ignore
        }
    }

    @Test
    fun appendsVisualStyleAfterPrompt() {
        val styleId = UUID.randomUUID().toString()
        VisualStyleRepository.insert(
            VisualStyle(
                id = styleId,
                name = "Pretty Anime",
                style = "semi-realistic cute/beautiful anime with faint outlines",
                movieId = "m1",
                createdAt = System.currentTimeMillis()
            )
        )

        val setup = GenerationSetup(
            kind = "image",
            prompt = "A sunny meadow full of wildflowers",
            styleId = styleId
        )
        val expanded = expandReferences(setup)

        assertTrue(
            expanded.prompt.endsWith("\n\nVisual style: semi-realistic cute/beautiful anime with faint outlines\n\n"),
            "style must be appended after the prompt: ${expanded.prompt}"
        )
        assertTrue(expanded.prompt.startsWith("A sunny meadow full of wildflowers"))
        // The original style id stays so regenerations can restore the selection.
        assertEquals(styleId, expanded.styleId)
    }

    @Test
    fun leavesPromptAloneWhenNoStyleSelected() {
        val setup = GenerationSetup(kind = "video", prompt = "City skyline at dusk")
        val expanded = expandReferences(setup)
        assertEquals("City skyline at dusk", expanded.prompt)
        assertFalse(expanded.prompt.contains("Visual style:"))
    }

    @Test
    fun ignoresMissingStyleIdGracefully() {
        val setup = GenerationSetup(
            kind = "image",
            prompt = "A portrait",
            styleId = "does-not-exist"
        )
        val expanded = expandReferences(setup)
        assertEquals("A portrait", expanded.prompt)
    }
}
