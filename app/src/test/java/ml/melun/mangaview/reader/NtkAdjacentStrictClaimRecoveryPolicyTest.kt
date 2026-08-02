package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkAdjacentStrictClaimRecoveryPolicyTest {
    @Test
    fun noStrictOwnershipLeavesTheLegacyLoaderUnchanged() {
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.ALLOW_GENERIC,
            decide(claimPresent = false, claimIdentityLive = false),
        )
    }

    @Test
    fun liveExactClaimContinuesToOwnMissingBody() {
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.HOLD_LIVE_CLAIM,
            decide(claimPresent = true, claimIdentityLive = true),
        )
    }

    @Test
    fun deadExactClaimGetsBoundedSameIdentityRediscovery() {
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.RETIRE_AND_REDISCOVER,
            decide(claimPresent = true, claimIdentityLive = false),
        )
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.TERMINAL_RETIREMENT,
            decide(
                claimPresent = true,
                claimIdentityLive = false,
                retiredGenerationCount =
                    NtkAdjacentStrictClaimRecoveryPolicy.MAX_REDISCOVERY_GENERATIONS,
            ),
        )
    }

    @Test
    fun replacementWaitNeverFallsThroughToGenericLoader() {
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.WAIT_FOR_REPLACEMENT,
            decide(
                claimPresent = false,
                claimIdentityLive = false,
                recoveryActive = true,
                replacementInFlight = true,
            ),
        )
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.WAIT_FOR_REPLACEMENT,
            decide(
                claimPresent = false,
                claimIdentityLive = false,
                recoveryActive = true,
                matchingAuthorityAvailable = true,
            ),
        )
    }

    @Test
    fun missingReplacementRequestIsBoundedThenTerminal() {
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.REQUEST_REDISCOVERY,
            decide(
                claimPresent = false,
                claimIdentityLive = false,
                recoveryActive = true,
                rediscoveryRequestCount = 1,
                elapsedSinceRequestMs = NtkAdjacentStrictClaimRecoveryPolicy.REQUEST_RETRY_MS,
            ),
        )
        assertEquals(
            NtkAdjacentStrictClaimRecoveryPolicy.Decision.TERMINAL_RETIREMENT,
            decide(
                claimPresent = false,
                claimIdentityLive = false,
                recoveryActive = true,
                rediscoveryRequestCount =
                    NtkAdjacentStrictClaimRecoveryPolicy.MAX_REQUESTS_PER_GENERATION,
                elapsedSinceRequestMs = NtkAdjacentStrictClaimRecoveryPolicy.REQUEST_RETRY_MS,
            ),
        )
    }

    private fun decide(
        claimPresent: Boolean,
        claimIdentityLive: Boolean,
        recoveryActive: Boolean = false,
        retiredGenerationCount: Int = 0,
        rediscoveryRequestCount: Int = 0,
        elapsedSinceRequestMs: Long = 0L,
        replacementInFlight: Boolean = false,
        matchingAuthorityAvailable: Boolean = false,
    ) = NtkAdjacentStrictClaimRecoveryPolicy.decide(
        claimPresent = claimPresent,
        claimIdentityLive = claimIdentityLive,
        recoveryActive = recoveryActive,
        retiredGenerationCount = retiredGenerationCount,
        rediscoveryRequestCount = rediscoveryRequestCount,
        elapsedSinceRequestMs = elapsedSinceRequestMs,
        replacementInFlight = replacementInFlight,
        matchingAuthorityAvailable = matchingAuthorityAvailable,
    )
}
