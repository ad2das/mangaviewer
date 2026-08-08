package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiStrictAdjacentOrderPolicyTest {
    @Test
    fun blankSourceNameDecreasingIdExactLiveClaimIsAllowedOnlyOnDirectWifi() {
        assertFalse(
            shouldSkip(
                directWifi = true,
                claimTarget = TARGET_PATH,
                claimPredecessor = CURRENT_PATH,
                claimLive = true,
                targetId = 126431L,
            )
        )
        assertTrue(
            shouldSkip(
                directWifi = false,
                claimTarget = TARGET_PATH,
                claimPredecessor = CURRENT_PATH,
                claimLive = true,
                targetId = 126431L,
            )
        )
    }

    @Test
    fun decreasingIdWithoutClaimKeepsExistingRejection() {
        assertTrue(
            shouldSkip(
                directWifi = true,
                claimTarget = null,
                claimPredecessor = null,
                claimLive = false,
                targetId = 126431L,
            )
        )
    }

    @Test
    fun decreasingIdWithWrongPredecessorKeepsExistingRejection() {
        assertTrue(
            shouldSkip(
                directWifi = true,
                claimTarget = TARGET_PATH,
                claimPredecessor = "/manhwa/12006/126430",
                claimLive = true,
                targetId = 126431L,
            )
        )
        assertTrue(
            shouldSkip(
                directWifi = true,
                claimTarget = "/manhwa/12006/126430",
                claimPredecessor = CURRENT_PATH,
                claimLive = true,
                targetId = 126431L,
            )
        )
    }

    @Test
    fun normalIncreasingIdKeepsExistingAllowanceWithoutClaim() {
        assertFalse(
            shouldSkip(
                directWifi = true,
                claimTarget = null,
                claimPredecessor = null,
                claimLive = false,
                targetId = 126433L,
            )
        )
    }

    private fun shouldSkip(
        directWifi: Boolean,
        claimTarget: String?,
        claimPredecessor: String?,
        claimLive: Boolean,
        targetId: Long,
    ): Boolean = NtkDirectWifiStrictAdjacentOrderPolicy.shouldSkipNumericIdFallback(
        directWifiStrictAdjacentTransport = directWifi,
        // The cold Continue anchor deliberately has a blank display name; ordering is proven by
        // the exact predecessor-bound transport claim, not by title parsing.
        currentTailEpisodePath = CURRENT_PATH,
        targetEpisodePath = TARGET_PATH,
        exactClaimTargetEpisodePath = claimTarget,
        exactClaimPredecessorEpisodePath = claimPredecessor,
        exactClaimLive = claimLive,
        maxExistingEpisodeId = 126432L,
        targetEpisodeId = targetId,
    )

    private companion object {
        const val CURRENT_PATH = "/manhwa/12006/126432"
        const val TARGET_PATH = "/manhwa/12006/126431"
    }
}
