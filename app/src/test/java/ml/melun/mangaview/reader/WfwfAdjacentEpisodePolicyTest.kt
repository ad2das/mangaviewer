package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfAdjacentEpisodePolicyTest {
    @Test
    fun serverEpisodeIdsMustMoveWithTheReadingDirection() {
        assertTrue(WfwfAdjacentEpisodePolicy.isDirectionallyConsistent(1199, 1200, 1))
        assertFalse(WfwfAdjacentEpisodePolicy.isDirectionallyConsistent(1200, 1, 1))
        assertTrue(WfwfAdjacentEpisodePolicy.isDirectionallyConsistent(1200, 1199, -1))
        assertFalse(WfwfAdjacentEpisodePolicy.isDirectionallyConsistent(1199, 1200, -1))
        assertFalse(WfwfAdjacentEpisodePolicy.isDirectionallyConsistent(0, 1, 1))
        assertTrue(WfwfAdjacentEpisodePolicy.isImmediateNumericCandidate(1199, 1200, 1))
        assertTrue(WfwfAdjacentEpisodePolicy.isImmediateNumericCandidate(1200, 1199, -1))
        assertFalse(WfwfAdjacentEpisodePolicy.isImmediateNumericCandidate(1199, 1201, 1))
        assertTrue(WfwfAdjacentEpisodePolicy.isImmediateVisibleCandidate("1183", "1184", 1))
        assertTrue(WfwfAdjacentEpisodePolicy.isImmediateVisibleCandidate("2", "1", -1))
        assertFalse(WfwfAdjacentEpisodePolicy.isImmediateVisibleCandidate("2", "11-2", 1))
    }

    @Test
    fun firstEpisodeNavigationLabelsAreRecognizedOnlyForEpisodeOne() {
        assertTrue(WfwfAdjacentEpisodePolicy.isFirstEpisodeShortcut(1, "첫화 보기"))
        assertTrue(WfwfAdjacentEpisodePolicy.isFirstEpisodeShortcut(1, " 첫화부터 정주행 "))
        assertFalse(WfwfAdjacentEpisodePolicy.isFirstEpisodeShortcut(2, "첫화 보기"))
        assertFalse(WfwfAdjacentEpisodePolicy.isFirstEpisodeShortcut(1, "1화"))
    }

    @Test
    fun matchedOrdinaryEpisodeStillHasBoundedNumericRecovery() {
        assertEquals(
            listOf(1200, 1201, 1202),
            WfwfAdjacentEpisodePolicy.syntheticCandidateIds(1199, 1, 3),
        )
    }

    @Test
    fun firstShortcutAndOutOfListEpisodesProbeBoundedServerIds() {
        assertEquals(
            listOf(2, 3, 4),
            WfwfAdjacentEpisodePolicy.syntheticCandidateIds(1, 1, 3),
        )
        assertEquals(
            listOf(3, 4, 5),
            WfwfAdjacentEpisodePolicy.syntheticCandidateIds(2, 1, 3),
        )
        assertEquals(
            listOf(2, 1),
            WfwfAdjacentEpisodePolicy.syntheticCandidateIds(3, -1, 4),
        )
    }
}
