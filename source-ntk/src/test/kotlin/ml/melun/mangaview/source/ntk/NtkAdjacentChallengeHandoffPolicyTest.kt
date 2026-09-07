package ml.melun.mangaview.source.ntk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentChallengeHandoffPolicyTest {
    @Test
    fun `only an in-flight exact adjacent challenge is inherited`() {
        assertTrue(shouldInherit(resolved = false))
        assertFalse(shouldInherit(resolved = true))
        assertFalse(shouldInherit(resolved = false, requestedPath = "/manhwa/1/3"))
        assertFalse(shouldInherit(resolved = false, started = false))
        assertFalse(shouldInherit(resolved = false, completedDelivery = false))
    }

    private fun shouldInherit(
        resolved: Boolean,
        requestedPath: String = "/manhwa/1/2",
        started: Boolean = true,
        completedDelivery: Boolean = true,
    ): Boolean = NtkAdjacentChallengeHandoffPolicy.shouldInherit(
        completedDelivery = completedDelivery,
        challengeStarted = started,
        challengeResolved = resolved,
        challengePath = "/manhwa/1/2",
        requestedPath = requestedPath,
    )
}
