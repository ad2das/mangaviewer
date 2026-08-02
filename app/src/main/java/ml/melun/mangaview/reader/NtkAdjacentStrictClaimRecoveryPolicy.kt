package ml.melun.mangaview.reader

/**
 * Fail-closed recovery policy for a continuously appended exact-source claim.
 *
 * A dead claimed port must not masquerade as active ownership forever, but losing that port also
 * does not authorize the generic image loader. Recovery may only replace the same exact episode
 * identity, a bounded number of times. An exhausted identity is explicitly terminal.
 */
internal object NtkAdjacentStrictClaimRecoveryPolicy {
    const val MAX_REDISCOVERY_GENERATIONS = 2
    const val MAX_REQUESTS_PER_GENERATION = 3
    const val REQUEST_RETRY_MS = 1_000L

    enum class Decision {
        ALLOW_GENERIC,
        HOLD_LIVE_CLAIM,
        RETIRE_AND_REDISCOVER,
        WAIT_FOR_REPLACEMENT,
        REQUEST_REDISCOVERY,
        TERMINAL_RETIREMENT,
    }

    fun decide(
        claimPresent: Boolean,
        claimIdentityLive: Boolean,
        recoveryActive: Boolean,
        retiredGenerationCount: Int,
        rediscoveryRequestCount: Int,
        elapsedSinceRequestMs: Long,
        replacementInFlight: Boolean,
        matchingAuthorityAvailable: Boolean,
    ): Decision {
        require(retiredGenerationCount >= 0)
        require(rediscoveryRequestCount >= 0)
        require(elapsedSinceRequestMs >= 0L)

        if (claimPresent) {
            if (claimIdentityLive) return Decision.HOLD_LIVE_CLAIM
            return if (retiredGenerationCount < MAX_REDISCOVERY_GENERATIONS) {
                Decision.RETIRE_AND_REDISCOVER
            } else {
                Decision.TERMINAL_RETIREMENT
            }
        }
        if (!recoveryActive) return Decision.ALLOW_GENERIC
        if (replacementInFlight || matchingAuthorityAvailable ||
            elapsedSinceRequestMs < REQUEST_RETRY_MS
        ) {
            return Decision.WAIT_FOR_REPLACEMENT
        }
        return if (rediscoveryRequestCount < MAX_REQUESTS_PER_GENERATION) {
            Decision.REQUEST_REDISCOVERY
        } else {
            Decision.TERMINAL_RETIREMENT
        }
    }
}
