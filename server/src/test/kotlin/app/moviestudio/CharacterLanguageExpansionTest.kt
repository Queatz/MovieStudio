package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.database.CharacterRepository
import app.moviestudio.database.DbCollection
import app.moviestudio.routing.expandReferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that a character's main language is injected into video generation prompts (and only
 * those) as `"<name> speaks in <language> unless otherwise specified."`.
 */
class CharacterLanguageExpansionTest {

    @Before
    fun setUp() {
        try {
            ArangoDatabase.init()
            ArangoDatabase.db.collection(DbCollection.CHARACTERS.collectionName).truncate()
        } catch (e: Exception) {
            println("Skipping DB setup because ArangoDB is not available: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        try {
            ArangoDatabase.db.collection(DbCollection.CHARACTERS.collectionName).truncate()
        } catch (_: Exception) {
            // Ignore
        }
    }

    @Test
    fun appendsMainLanguageToVideoPrompt() {
        val characterId = UUID.randomUUID().toString()
        CharacterRepository.insert(
            Character(
                id = characterId,
                name = "Mira",
                description = "A captain",
                mainLanguage = "Mandarin Chinese",
                createdAt = System.currentTimeMillis()
            )
        )

        val expanded = expandReferences(
            GenerationSetup(
                kind = "video",
                prompt = "Mira greets the crew on the bridge",
                characterIds = listOf(characterId)
            )
        )

        assertTrue(
            expanded.prompt.contains("Mira speaks in Mandarin Chinese unless otherwise specified."),
            "video prompt must include the character language note: ${expanded.prompt}"
        )
        assertTrue(expanded.prompt.contains("Featuring character \"Mira\""), expanded.prompt)
    }

    @Test
    fun doesNotAppendLanguageForImageGeneration() {
        val characterId = UUID.randomUUID().toString()
        CharacterRepository.insert(
            Character(
                id = characterId,
                name = "Mira",
                description = "A captain",
                mainLanguage = "French",
                createdAt = System.currentTimeMillis()
            )
        )

        val expanded = expandReferences(
            GenerationSetup(
                kind = "image",
                prompt = "Portrait of Mira",
                characterIds = listOf(characterId)
            )
        )

        assertFalse(
            expanded.prompt.contains("speaks in"),
            "image prompts must not inject spoken-language notes: ${expanded.prompt}"
        )
    }

    @Test
    fun skipsBlankMainLanguage() {
        val characterId = UUID.randomUUID().toString()
        CharacterRepository.insert(
            Character(
                id = characterId,
                name = "Mira",
                description = "A captain",
                mainLanguage = "   ",
                createdAt = System.currentTimeMillis()
            )
        )

        val expanded = expandReferences(
            GenerationSetup(
                kind = "video",
                prompt = "Mira waves",
                characterIds = listOf(characterId)
            )
        )

        assertFalse(
            expanded.prompt.contains("speaks in"),
            "blank main language must not inject a note: ${expanded.prompt}"
        )
    }
}
