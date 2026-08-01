package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentTransitionCardPolicyTest {
    @Test
    fun directWifiForwardGeneratedEpisodeIsPixelContinuous() {
        assertFalse(
            NtkAdjacentTransitionCardPolicy.shouldInsert(
                direction = 1,
                directWifiStrictAdjacent = true,
                ntkGeneratedEpisode = true,
            )
        )
    }

    @Test
    fun mobileSniAndNonGeneratedPathsKeepTransitionCard() {
        assertTrue(
            NtkAdjacentTransitionCardPolicy.shouldInsert(
                direction = 1,
                directWifiStrictAdjacent = false,
                ntkGeneratedEpisode = true,
            )
        )
        assertTrue(
            NtkAdjacentTransitionCardPolicy.shouldInsert(
                direction = 1,
                directWifiStrictAdjacent = true,
                ntkGeneratedEpisode = false,
            )
        )
    }

    @Test
    fun previousEpisodeDirectionAlwaysKeepsTransitionCard() {
        assertTrue(
            NtkAdjacentTransitionCardPolicy.shouldInsert(
                direction = -1,
                directWifiStrictAdjacent = true,
                ntkGeneratedEpisode = true,
            )
        )
    }
}
