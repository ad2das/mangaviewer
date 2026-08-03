package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkCompletedForwardEpisodePolicyTest {
    @Test
    fun rejectsUnknownShortIncompleteAndNonCanonicalEpisodes() {
        assertFalse(NtkCompletedForwardEpisodePolicy.isComplete(0, emptyList(), emptyList()))
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 2),
                listOf(true, true, true),
            )
        )
        assertTrue(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 2, 3, 3),
                List(5) { true },
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 2, 1, 3),
                List(4) { true },
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 3, 4),
                List(4) { true },
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 2, 3),
                listOf(true, true, false, true),
            )
        )
    }

    @Test
    fun acceptsOnlyTheExactCanonicalFullyDrawableEpisode() {
        assertTrue(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 2, 3),
                List(4) { true },
            )
        )
        assertTrue(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 1, 2, 3),
                List(5) { true },
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                4,
                listOf(0, 1, 1, 2, 3),
                listOf(true, true, false, true, true),
            )
        )
    }

    @Test
    fun resumedEpisodeRequiresOnlyCanonicalSourcesAtAndAfterTheSavedFloorToBeDrawable() {
        val canonical = (0 until 8).toList()
        assertTrue(
            NtkCompletedForwardEpisodePolicy.isComplete(
                authoritativeCount = 8,
                sourceIndexes = canonical,
                drawableReady = listOf(false, false, false, false, false, true, true, true),
                requiredFirstSource = 5,
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                authoritativeCount = 8,
                sourceIndexes = canonical,
                drawableReady = listOf(false, false, false, false, false, true, false, true),
                requiredFirstSource = 5,
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                authoritativeCount = 8,
                sourceIndexes = listOf(0, 1, 2, 3, 5, 6, 7),
                drawableReady = List(7) { true },
                requiredFirstSource = 5,
            )
        )
        assertFalse(
            NtkCompletedForwardEpisodePolicy.isComplete(
                authoritativeCount = 8,
                sourceIndexes = canonical,
                drawableReady = List(8) { true },
                requiredFirstSource = 8,
            )
        )
    }
}
