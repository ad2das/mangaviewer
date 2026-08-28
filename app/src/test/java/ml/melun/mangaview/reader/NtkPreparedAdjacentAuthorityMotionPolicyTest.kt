package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPreparedAdjacentAuthorityMotionPolicyTest {
    @Test
    fun `direct wifi manhwa document may parse before predecessor body completion`() {
        assertTrue(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayParseBeforePredecessorComplete(
                directWifiAdjacentBodyGate = true,
                episodePath = "/manhwa/2/next",
            ),
        )
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayParseBeforePredecessorComplete(
                directWifiAdjacentBodyGate = false,
                episodePath = "/manhwa/2/next",
            ),
        )
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayParseBeforePredecessorComplete(
                directWifiAdjacentBodyGate = true,
                episodePath = "/webtoon/2/next",
            ),
        )
    }

    @Test
    fun `prepared direct wifi manhwa document may parse while predecessor keeps scrolling`() {
        assertTrue(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayParseDocumentDuringMotion(
                directWifiAdjacentBodyGate = true,
                predecessorReady = true,
                episodePath = "/manhwa/2/next",
            ),
        )
    }

    @Test
    fun `prepared direct wifi manhwa authority may commit while predecessor keeps scrolling`() {
        assertTrue(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayCommitDuringMotion(
                directWifiAdjacentBodyGate = true,
                predecessorReady = true,
                episodePath = "/manhwa/2/next",
            ),
        )
    }

    @Test
    fun `authority cannot bypass the predecessor body gate`() {
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayCommitDuringMotion(
                directWifiAdjacentBodyGate = true,
                predecessorReady = false,
                episodePath = "/manhwa/2/next",
            ),
        )
    }

    @Test
    fun `webtoon and non direct adjacent profiles retain motion idle deferral`() {
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayParseDocumentDuringMotion(
                directWifiAdjacentBodyGate = true,
                predecessorReady = true,
                episodePath = "/webtoon/2/next",
            ),
        )
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayCommitDuringMotion(
                directWifiAdjacentBodyGate = true,
                predecessorReady = true,
                episodePath = "/webtoon/2/next",
            ),
        )
        assertFalse(
            NtkPreparedAdjacentAuthorityMotionPolicy.mayCommitDuringMotion(
                directWifiAdjacentBodyGate = false,
                predecessorReady = true,
                episodePath = "/manhwa/2/next",
            ),
        )
    }
}
