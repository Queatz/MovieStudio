package app.moviestudio

import app.moviestudio.service.SkeletonService
import app.moviestudio.service.SkeletonService.MAX_SKELETON_ITEMS
import app.moviestudio.service.SkeletonService.MIN_SKELETON_ITEMS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [SkeletonService.inferItemCount], the heuristic that decides how many timeline items to
 * request from the planner based on how complex/detailed the request is, always within
 * [MIN_SKELETON_ITEMS, MAX_SKELETON_ITEMS].
 */
class SkeletonServiceTest {

    private fun emptyTimeline(totalDuration: Double = 0.0) = MovieTimeline(
        movie = Movie(
            id = "movie-1",
            title = "Test Movie",
            totalDuration = totalDuration,
            status = MovieStatus.DRAFT,
            createdAt = 0L
        ),
        tracks = emptyList()
    )

    @Test
    fun `blank prompt and empty timeline falls back to the minimum`() {
        val count = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = "", atSeconds = 0.0),
            emptyTimeline()
        )

        assertEquals(MIN_SKELETON_ITEMS, count)
    }

    @Test
    fun `short simple prompt stays close to the minimum`() {
        val count = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = "A sunset over the ocean.", atSeconds = 0.0),
            emptyTimeline()
        )

        assertTrue(count in MIN_SKELETON_ITEMS..MIN_SKELETON_ITEMS + 4, "expected a small count, got $count")
    }

    @Test
    fun `long, multi-beat prompt requests more items`() {
        val prompt = "A hero discovers a hidden map, gathers a crew, sails through a storm, " +
            "finds the ancient temple, fights the guardian, escapes a collapsing cave, " +
            "reunites with a lost friend, and sails home to a hero's welcome, " +
            "while a rival pursues them across three continents and betrays them twice."
        val simpleCount = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = "A sunset.", atSeconds = 0.0),
            emptyTimeline()
        )

        val complexCount = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = prompt, atSeconds = 0.0),
            emptyTimeline()
        )

        assertTrue(complexCount > simpleCount, "a longer, multi-beat prompt should request more items")
    }

    @Test
    fun `longer existing runway pushes the count up`() {
        val shortMovieCount = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = "Continue the story.", atSeconds = 0.0),
            emptyTimeline(totalDuration = 12.0)
        )
        val longMovieCount = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = "Continue the story.", atSeconds = 0.0),
            emptyTimeline(totalDuration = 180.0)
        )

        assertTrue(longMovieCount > shortMovieCount, "a longer movie should request more items to fill it out")
    }

    @Test
    fun `count is always clamped to the 8 to 40 range`() {
        val hugePrompt = (1..2000).joinToString(" ") { "word$it," }
        val count = SkeletonService.inferItemCount(
            SkeletonService.SkeletonPayload(prompt = hugePrompt, atSeconds = 0.0),
            emptyTimeline(totalDuration = 100_000.0)
        )

        assertEquals(MAX_SKELETON_ITEMS, count)
        assertTrue(count in MIN_SKELETON_ITEMS..MAX_SKELETON_ITEMS)
    }
}
