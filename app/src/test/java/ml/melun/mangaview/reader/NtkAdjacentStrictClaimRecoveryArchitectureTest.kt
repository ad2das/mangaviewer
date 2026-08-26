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
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
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
    fun oldGenerationCleanupOwnsThePathUntilValidatedRearmIsPublished() {
        val recovery = functionSlice(
            readerSession,
            "private fun holdOrRecoverAdjacentStrictSource(",
            "private fun retireAdjacentStrictSourceGeneration(",
        )
        val ensure = functionSlice(
            readerSession,
            "private fun ensureAdjacentStrictSourceClaim(",
            "private fun releaseAdjacentStrictClaimAfterPredecessorComplete(",
        )
        val validated = functionSlice(
            readerSession,
            "fun redriveCurrentForwardAdjacentExactRecoveryAfterValidated(",
            "private fun reportAdjacentStrictRecoveryTerminal(",
        )
        val report = functionSlice(
            readerSession,
            "private fun reportAdjacentStrictRecoveryTerminal(",
            "private fun remainingAdjacentRunwayBoundaryLocked(",
        )
        val firstRetirementFlag = recovery.indexOf("state.retirementInProgress = true")
        val oldCleanup = recovery.indexOf("retireAdjacentStrictSourceGeneration(")
        val ownerRelease = recovery.indexOf("state.retirementInProgress = false")
        val validatedPublish = recovery.indexOf("ensureAdjacentStrictRecoveryManifestSubscription()")

        assertTrue(recovery.contains("if (recovery?.retirementInProgress == true) return true"))
        assertTrue(ensure.contains("if (recovery?.retirementInProgress == true) return false"))
        assertTrue(firstRetirementFlag >= 0)
        assertTrue(oldCleanup > firstRetirementFlag)
        assertTrue(ownerRelease > oldCleanup)
        assertTrue(validatedPublish > ownerRelease)
        assertTrue(validated.contains("!state.retirementInProgress"))
        assertTrue(
            validated.indexOf("lastObservedAdjacentValidatedNetworkEpoch = validatedEpoch") <
                validated.indexOf("!state.retirementInProgress"),
        )
        assertTrue(report.contains("!state.exhausted || !state.networkRearmableTerminal"))
        assertTrue(report.contains("adjacentStrictPredecessorPaths.remove(path)"))
        assertTrue(
            report.indexOf("adjacentStrictPredecessorPaths.remove(path)") <
                report.indexOf("if (!shouldReport) return"),
        )
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
    fun validatedFlightWindowIsBoundToTheExactInitialOrBodyRecoveryOwner() {
        val initial = functionSlice(
            readerSession,
            "fun redriveCurrentForwardAdjacentExactManifestAfterValidated(",
            "fun redriveCurrentForwardAdjacentExactRecoveryAfterValidated(",
        )
        val body = functionSlice(
            readerSession,
            "fun redriveCurrentForwardAdjacentExactRecoveryAfterValidated(",
            "fun adjacentValidatedFlightRecoveryWindow(",
        )
        val window = functionSlice(
            readerSession,
            "fun adjacentValidatedFlightRecoveryWindow(",
            "private fun reportAdjacentStrictRecoveryTerminal(",
        )

        assertTrue(initial.contains("live.lastValidatedRedriveAtMs = now"))
        assertTrue(initial.contains("lastValidatedRedriveAtMs = now"))
        assertTrue(body.contains("state.awaitingReplacement"))
        assertTrue(body.contains("state.lastValidatedRedriveEpoch = validatedEpoch"))
        assertTrue(body.contains("state.lastValidatedRedriveAtMs = now"))
        val predecessorTerminal = body.indexOf(
            "predecessorMatches(entry) && terminalEligible(entry.value)",
        )
        val predecessorActive = body.indexOf(
            "predecessorMatches(entry) && activeEligible(entry.value)",
        )
        val targetTerminal = body.indexOf(
            "targetMatches(entry) && terminalEligible(entry.value)",
        )
        val targetActive = body.indexOf(
            "targetMatches(entry) && activeEligible(entry.value)",
        )
        assertTrue(predecessorTerminal >= 0)
        assertTrue(predecessorActive > predecessorTerminal)
        assertTrue(targetTerminal > predecessorActive)
        assertTrue(targetActive > targetTerminal)
        assertTrue(window.contains("forwardAdjacentCompletionTargetClaims[predecessorPath]"))
        assertTrue(window.contains("adjacentStrictRecoveryStates[targetPath]"))
        assertTrue(window.contains("!claim.structureCommitted"))
        assertTrue(window.contains("!state.retirementInProgress && !state.exhausted"))
        assertTrue(window.contains("state.predecessorEpisodePath.equals(predecessorPath"))
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
        assertTrue(retirement.contains("detachFlightForForegroundLeaveLocked(owned)"))
        assertTrue(retirement.contains("completeDetachedFlightForegroundLeave(flight)"))
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
        assertTrue(retirement.contains("detachFlightForForegroundLeaveLocked(owned)"))
        assertTrue(retirement.contains("completeDetachedFlightForegroundLeave(flight)"))
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
    fun bodyResidentControlProtectsExactTargetBeforePublishingDiscovery() {
        val activation = functionSlice(
            readerSession,
            "private fun activateForwardAdjacentCompletionTargetClaim(",
            "private fun startForwardAdjacentExactDiscoveryAtCompletion(",
        )
        val allow = activation.indexOf("ReaderImageCache.allowAdjacentNtkForegroundViewerPath(")
        val control = activation.indexOf("if (controlOnly)", allow)
        val launch = activation.indexOf("listener.onAdjacentExactManifestRequired(", control)
        assertTrue(allow >= 0)
        assertTrue(control > allow)
        assertTrue(launch > control)
        assertTrue(activation.contains("\"body_resident_exact_control\""))
    }

    @Test
    fun centralExactOwnerReplacesAnEvidenceCancelledFlightBeforeJoiningItAgain() {
        val watchdog = functionSlice(
            activity,
            "private fun postAdjacentExactManifestForGeneration(",
            "private fun isCurrentNtkReader(",
        )
        val observeFlight = watchdog.indexOf(
            "NtkStrictEpisodeDiscoveryCoordinator.isInFlight(capturedTargetPath)",
        )
        val retire = watchdog.indexOf("retireCancelledAdjacentTargetForReplacement(")
        val recompute = watchdog.indexOf(
            "isInFlight(capturedTargetPath)",
            retire,
        )
        val join = watchdog.indexOf("if (inFlight &&", recompute)
        assertTrue(observeFlight >= 0)
        assertTrue(retire > observeFlight)
        assertTrue(recompute > retire)
        assertTrue(join > recompute)
        assertTrue(watchdog.contains("capturedViewerGeneration"))
        assertTrue(watchdog.contains("\"adjacent_exact_manifest_watchdog\""))
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
        val recoveryProof = functionSlice(
            readerSession,
            "fun isAdjacentStrictReplacementDiscoveryCurrent(",
            "private fun nextUnloadedAdjacentEpisode(",
        )
        assertTrue(recoveryProof.contains("!state.retirementInProgress"))
    }

    private fun functionSlice(source: String, startToken: String, endToken: String): String {
        val start = source.indexOf(startToken)
        require(start >= 0) { "Missing $startToken" }
        val end = source.indexOf(endToken, start + startToken.length)
        require(end > start) { "Missing $endToken" }
        return source.substring(start, end)
    }
}
