package app.moviestudio

import app.moviestudio.routing.MAX_R2V_REFERENCE_IMAGES
import app.moviestudio.routing.ReferenceSubject
import app.moviestudio.routing.balanceReferenceImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the reference-image budget balancing that feeds WAN 2.7 R2V. The critical
 * behaviour is that when the user selects more subjects (extra reference photos + characters +
 * scenes) than WAN can accept, at least one photo from every selection still survives instead of a
 * whole subject being silently dropped, and that each surviving photo is bound by index to its
 * subject's name in the prompt.
 */
class ReferenceBalancingTest {

    /** A character/scene-style subject: its images plus an "N shows X" style prompt sentence. */
    private fun subject(name: String, images: List<String>) =
        ReferenceSubject(images = images) { indices ->
            if (indices.isEmpty()) " Featuring \"$name\"." else " Reference image(s) $indices show \"$name\"."
        }

    /** The extra-reference-photos group carries no name, so it renders no prompt sentence. */
    private fun extras(images: List<String>) = ReferenceSubject(images = images) { "" }

    @Test
    fun guaranteesAtLeastOnePhotoPerSubjectWhenOversubscribed() {
        // Five subjects each with several photos, but only four slots: the first pass must give
        // each of the first four subjects exactly one photo before anyone gets a second.
        val subjects = listOf(
            subject("A", listOf("a1", "a2", "a3")),
            subject("B", listOf("b1", "b2", "b3")),
            subject("C", listOf("c1", "c2")),
            subject("D", listOf("d1", "d2")),
            subject("E", listOf("e1")),
        )

        val (images, _) = balanceReferenceImages(subjects, budget = MAX_R2V_REFERENCE_IMAGES)

        assertEquals(MAX_R2V_REFERENCE_IMAGES, images.size, "must fill exactly the budget")
        assertEquals(listOf("a1", "b1", "c1", "d1"), images, "each surviving subject contributes its first photo, none twice")
    }

    @Test
    fun balancesExtrasCharactersAndScenesEachGettingOne() {
        // The exact scenario from the issue: extra reference photos + characters + a scene, more
        // photos than the budget. Every selection must be represented.
        val subjects = listOf(
            extras(listOf("x1", "x2")),
            subject("Alice", listOf("al1", "al2")),
            subject("Bob", listOf("bo1")),
            subject("Park", listOf("pa1", "pa2")),
        )

        val (images, _) = balanceReferenceImages(subjects, budget = MAX_R2V_REFERENCE_IMAGES)

        assertEquals(listOf("x1", "al1", "bo1", "pa1"), images)
        assertTrue("x1" in images, "extra reference photos must keep a slot")
        assertTrue("al1" in images && "bo1" in images, "each character must keep a slot")
        assertTrue("pa1" in images, "the scene must keep a slot")
    }

    @Test
    fun fillsRemainingBudgetWithSecondPhotosRoundRobin() {
        // Budget larger than the number of subjects: after everyone has one, the spare slots go
        // round-robin to second photos.
        val subjects = listOf(
            subject("A", listOf("a1", "a2", "a3")),
            subject("B", listOf("b1", "b2")),
        )

        val (images, _) = balanceReferenceImages(subjects, budget = 4)

        // Pass 1: a1, b1. Pass 2: a2, b2.
        assertEquals(listOf("a1", "a2", "b1", "b2"), images, "surviving images stay grouped by subject")
    }

    @Test
    fun deduplicatesSharedPhotosAcrossSubjects() {
        val subjects = listOf(
            subject("A", listOf("shared", "a2")),
            subject("B", listOf("shared", "b2")),
        )

        val (images, _) = balanceReferenceImages(subjects, budget = 4)

        assertEquals(images.distinct(), images, "no URL may appear twice")
        assertTrue("shared" in images)
        assertEquals(3, images.size, "the shared photo occupies a single slot")
    }

    @Test
    fun promptIndicesLineUpWithFinalImageOrder() {
        val subjects = listOf(
            subject("Alice", listOf("al1", "al2")),
            subject("Bob", listOf("bo1")),
        )

        val (images, prompt) = balanceReferenceImages(subjects, budget = 4)

        // Alice keeps indices 1-2 (al1, al2) and Bob index 3 (bo1) after contiguous grouping.
        assertEquals(listOf("al1", "al2", "bo1"), images)
        assertTrue(prompt.contains("[1, 2] show \"Alice\"."), "Alice's prompt must reference her images by their final indices: $prompt")
        assertTrue(prompt.contains("[3] show \"Bob\"."), "Bob's prompt must reference his image by its final index: $prompt")
    }

    @Test
    fun subjectWithoutSurvivingPhotoIsStillNamed() {
        // Two subjects, budget of one: the second gets no photo but must still be described so its
        // textual context is not lost.
        val subjects = listOf(
            subject("A", listOf("a1")),
            subject("B", listOf("b1")),
        )

        val (images, prompt) = balanceReferenceImages(subjects, budget = 1)

        assertEquals(listOf("a1"), images)
        assertTrue(prompt.contains("Featuring \"B\"."), "the dropped subject must still be named in the prompt: $prompt")
    }
}
