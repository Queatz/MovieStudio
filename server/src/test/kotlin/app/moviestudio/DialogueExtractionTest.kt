package app.moviestudio

import app.moviestudio.service.extractQuotedDialogue
import app.moviestudio.service.isDrivingAudioMediaRejection
import app.moviestudio.service.sanitizeExtractedDialogue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for prompt dialogue extraction used when synthesizing character driving audio
 * for video generation. No network / Arango required.
 */
class DialogueExtractionTest {

    @Test
    fun extractQuotedDialogueJoinsStraightQuotedSegments() {
        val prompt = """Captain Mira turns and says "We jump at dawn." Then softer: "Stay with me.""""
        assertEquals("We jump at dawn. Stay with me.", extractQuotedDialogue(prompt))
    }

    @Test
    fun extractQuotedDialogueSupportsCurlyQuotes() {
        val prompt = "She whispers “Hold the line” across the bridge."
        assertEquals("Hold the line", extractQuotedDialogue(prompt))
    }

    @Test
    fun extractQuotedDialogueReturnsEmptyWhenNoQuotes() {
        assertEquals("", extractQuotedDialogue("A wide shot of the starship leaving dock."))
        assertEquals("", extractQuotedDialogue(""))
        assertEquals("", extractQuotedDialogue("   "))
    }

    @Test
    fun sanitizeExtractedDialogueStripsWrappingQuotesAndRejectsNone() {
        assertEquals("We jump at dawn.", sanitizeExtractedDialogue("\"We jump at dawn.\""))
        assertEquals("Hold the line", sanitizeExtractedDialogue("“Hold the line”"))
        assertEquals("", sanitizeExtractedDialogue("none"))
        assertEquals("", sanitizeExtractedDialogue("N/A"))
        assertEquals("", sanitizeExtractedDialogue("no dialogue"))
        assertEquals("", sanitizeExtractedDialogue("   "))
    }

    @Test
    fun isDrivingAudioMediaRejectionDetectsMediaTypeErrors() {
        assertTrue(
            isDrivingAudioMediaRejection(
                IllegalStateException(
                    "Input should be 'reference_image', 'reference_video' or 'first_frame': input.media.2.type"
                )
            )
        )
        assertTrue(
            isDrivingAudioMediaRejection(
                IllegalStateException("invalid media type driving_audio for R2V")
            )
        )
        assertFalse(
            isDrivingAudioMediaRejection(
                IllegalStateException("Model Studio task timed out after 600000ms")
            )
        )
        assertFalse(isDrivingAudioMediaRejection(IllegalStateException("")))
    }
}
