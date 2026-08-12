package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkAdjacentStrictClaimRecoveryArchitectureTest {
    private val readerSession = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()

    @Test
    fun terminalAdjacentClaimIsRetiredBeforeSameIdentityRediscovery() {
        val recovery = functionSlice(
            readerSession,
            "private fun holdOrRecoverAdjacentStrictSource(",
            "private fun retireAdjacentStrictSourceGeneration(",
        )
        assertTrue(recovery.contains("NtkAdjacentStrictClaimRecoveryPolicy.decide("))
        assertTrue(recovery.contains("expectedManifestDigest = failedClaim.manifestDigest"))
        assertTrue(recovery.contains("adjacentStrictSourceClaims.remove(path, failedClaim)"))
        assertTrue(recovery.contains("retireAdjacentStrictSourceGeneration("))
        assertTrue(recovery.contains("listener.onAdjacentExactManifestRequired(target, rediscoveryPredecessor)"))
        assertFalse(recovery.contains("ReaderImageCache.getOrFetchFile"))
        assertFalse(recovery.contains("DIRECTION_PREVIOUS"))
    }

    @Test
    fun replacementKeepsExactDigestAndRejectsTerminalRegistryAuthority() {
        val authority = functionSlice(
            readerSession,
            "private fun exactViewerApiAdjacentAuthority(",
            "private fun waitForExactViewerApiAdjacentUrls(",
        )
        assertTrue(authority.contains("snapshot.generation != authority.seal.revision"))
        assertTrue(authority.contains("snapshot.manifestDigest != authority.seal.digestSha256"))
        assertTrue(authority.contains("snapshot.state.ordinal >= NtkSourceState.TERMINAL_CLOSING.ordinal"))
        assertTrue(authority.contains("recovery.expectedManifestDigest == authority.seal.digestSha256"))
    }

    @Test
    fun coordinatorReplacementIsAdjacentAndGenerationScoped() {
        val retirement = functionSlice(
            coordinator,
            "fun retireAdjacentTargetForReplacement(",
            "fun retireCancelledAdjacentTargetForReplacement(",
        )
        assertTrue(retirement.contains("owned.viewerGeneration != viewerGeneration"))
        assertTrue(retirement.contains("owned.lease.generation.value != discoveryGeneration"))
        assertTrue(retirement.contains("owned.viewerOwnerEpisodePath.equals(key, ignoreCase = true)"))
        assertTrue(retirement.contains("retireDiscoveryForReplacement("))
        assertTrue(retirement.contains("flights.remove(key, owned)"))
        assertFalse(retirement.contains("completedAdjacentPredecessors.remove"))
    }

    @Test
    fun cancelledDiscoveryReplacementIsEvidenceBoundAndAdjacentScoped() {
        val retirement = functionSlice(
            coordinator,
            "fun retireCancelledAdjacentTargetForReplacement(",
            "fun retireConsumedTargetOwnership(",
        )
        assertTrue(retirement.contains("owned.viewerGeneration != viewerGeneration"))
        assertTrue(retirement.contains("owned.viewerOwnerEpisodePath.equals(key, ignoreCase = true)"))
        assertTrue(retirement.contains("owned.completed.get()"))
        assertTrue(retirement.contains("currentAuthoritativeManifest(key) != null"))
        assertTrue(
            retirement.contains(
                "wasNtkEpisodeWorkCancelledSince(key, owned.startedAtMs)",
            ),
        )
        assertTrue(retirement.contains("retireDiscoveryForReplacement("))
        assertTrue(retirement.contains("flights.remove(key, owned)"))
        assertTrue(retirement.contains("leaveNtkStrictForegroundNetwork(key, viewerGeneration)"))
    }

    @Test
    fun exactManifestWaitProtectsTargetBeforeLaunchAndRetriesCancelledFlight() {
        val wait = functionSlice(
            readerSession,
            "private fun waitForExactViewerApiAdjacentUrls(",
            "private fun fetchGeneratedNtkAppendUrlsWithEarlyHandoff(",
        )
        val allow = wait.indexOf("ReaderImageCache.allowAdjacentNtkForegroundViewerPath(")
        val launch = wait.indexOf("listener.onAdjacentExactManifestRequired(")
        assertTrue(allow >= 0)
        assertTrue(launch > allow)
        assertTrue(wait.contains("retireCancelledAdjacentTargetForReplacement("))
        assertTrue(wait.contains("requestExactDiscovery(\"adjacent_exact_manifest_cancelled_retry\")"))
        assertTrue(wait.contains("if (!replacedCancelledFlight) holdOrRecoverAdjacentStrictSource(target)"))
    }

    @Test
    fun remainingRunwayCannotFallThroughWhileStrictReplacementIsPending() {
        val fetch = functionSlice(
            readerSession,
            "private fun startRemainingAdjacentRunwayFileFetches(",
            "private fun remainingAdjacentRunwayFileFetchLimit(",
        )
        assertTrue(fetch.contains("if (holdOrRecoverAdjacentStrictSource(target)) return true"))
        assertTrue(
            fetch.indexOf("if (holdOrRecoverAdjacentStrictSource(target)) return true") <
                fetch.indexOf("ReaderImageCache.getOrFetchFileForeground("),
        )
    }

    private fun functionSlice(source: String, startToken: String, endToken: String): String {
        val start = source.indexOf(startToken)
        require(start >= 0) { "Missing $startToken" }
        val end = source.indexOf(endToken, start + startToken.length)
        require(end > start) { "Missing $endToken" }
        return source.substring(start, end)
    }
}
