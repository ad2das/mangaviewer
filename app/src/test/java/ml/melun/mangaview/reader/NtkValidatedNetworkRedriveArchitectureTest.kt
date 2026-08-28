package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkValidatedNetworkRedriveArchitectureTest {
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val registry = File(
        "src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt",
    ).readText()

    @Test
    fun activityOwnsOneValidatedEdgeAndRetiresItWithTheExactViewer() {
        val register = functionBody(
            activity,
            "private fun registerStrictNtkValidatedNetworkRedriveObserver(",
        )
        val reconcile = functionBody(
            activity,
            "private fun reconcileStrictNtkValidatedNetworkState(",
        )
        val redrive = functionBody(
            activity,
            "private fun runStrictNtkValidatedNetworkRedrive(",
        )
        val destroy = functionBody(activity, "override fun onDestroy()")
        val testRetire = functionBody(activity, "fun testPrepareForNextLaunch()")
        val unregister = functionBody(
            activity,
            "private fun unregisterStrictNtkValidatedNetworkRedriveObserver()",
        )
        val pause = functionBody(
            activity,
            "private fun pauseStrictNtkValidatedNetworkRedrive(",
        )
        val resume = functionBody(
            activity,
            "private fun resumeStrictNtkValidatedNetworkRedrive()",
        )

        assertTrue(register.contains("registerDefaultNetworkCallback(callback)"))
        assertTrue(register.contains("postStrictNtkNetworkReconcile()"))
        assertTrue(register.contains("strictNtkNetworkUnvalidatedEvidence.set(true)"))
        assertTrue(activity.contains("NetworkCapabilities.NET_CAPABILITY_INTERNET"))
        assertTrue(activity.contains("NetworkCapabilities.NET_CAPABILITY_VALIDATED"))
        assertTrue(reconcile.contains("strictNtkNetworkRedriveGate.observe(validated, now)"))
        assertTrue(reconcile.contains("strictNtkNetworkUnvalidatedEvidence.getAndSet(false)"))
        assertTrue(redrive.contains("strictActivityOwner.get() === owner"))
        assertTrue(redrive.contains("ViewerTelemetry.isActiveViewer(owner.viewerGeneration, launchPath)"))
        assertTrue(redrive.contains("strictNtkPendingSessionPath"))
        assertTrue(redrive.contains("strictNtkManifestSubscription == null"))
        assertTrue(redrive.contains("hasCurrentStrictNtkRecoverableSourceTerminal(launchPath)"))
        assertTrue(redrive.contains("startStrictReaderSessionWhenExactReady("))
        assertTrue(redrive.contains("clearViewImmediately = false"))
        assertTrue(redrive.contains("isActiveNetworkValidated(manager)"))
        assertTrue(redrive.contains("CurrentValidatedRedriveResult.SOURCE_SETTLING"))
        assertTrue(redrive.contains("scheduleStrictNtkValidatedNetworkRedrive("))
        assertTrue(redrive.contains("CurrentValidatedRedriveResult.ATTEMPT_FAILED"))
        assertTrue(redrive.contains("ticket.hardDeadlineAtMs"))
        assertTrue(redrive.contains("retireCurrentFlightForValidatedReplacement("))
        val initialAdjacent = redrive.indexOf(
            "redriveCurrentForwardAdjacentExactManifestAfterValidated(ticket.epoch)",
        )
        val bodyAdjacent = redrive.indexOf(
            "redriveCurrentForwardAdjacentExactRecoveryAfterValidated(ticket.epoch)",
        )
        val launchAuthority = redrive.indexOf("currentAuthoritativeManifest(launchPath)")
        assertTrue(initialAdjacent >= 0 && bodyAdjacent > initialAdjacent)
        assertTrue(launchAuthority > bodyAdjacent)
        assertTrue(destroy.indexOf("unregisterStrictNtkValidatedNetworkRedriveObserver()") >= 0)
        assertTrue(testRetire.indexOf("unregisterStrictNtkValidatedNetworkRedriveObserver()") >= 0)
        assertTrue(unregister.contains("synchronized(strictNtkNetworkObserverLock)"))
        assertTrue(unregister.contains("adjacentExactDiscoveryRetryTokens.clear()"))
        assertTrue(pause.contains("strictNtkNetworkPausedAtMs = SystemClock.elapsedRealtime()"))
        assertTrue(resume.contains("resumeAfterPause("))

        val sessionWait = functionBody(
            activity,
            "private fun startStrictReaderSessionWhenExactReady(",
        )
        val sessionStart = functionBody(activity, "private fun startReaderSession(")
        assertFalse(sessionWait.contains("unregisterStrictNtkValidatedNetworkRedriveObserver()"))
        assertFalse(sessionStart.contains("unregisterStrictNtkValidatedNetworkRedriveObserver()"))

        val sourceTerminal = functionBody(
            activity,
            "private fun postStrictNtkRecoverableSourceTerminal(",
        )
        assertTrue(sourceTerminal.contains("if (!retryableTransport) return"))
        assertTrue(sourceTerminal.contains("sessionGeneration != activeReaderSessionGeneration.get()"))
        assertTrue(sourceTerminal.contains("seal?.matchesEpisodePath(path) != true"))
    }

    @Test
    fun currentOnlyAdmissionRechecksGenerationInsideThePathLockAndNeverReusesALiveLease() {
        val currentStart = functionBody(
            coordinator,
            "fun startCurrentColdRollingAfterValidated(",
        )
        val internal = functionBody(coordinator, "private fun startInternal(")
        val pathLockAt = internal.indexOf("synchronized(flightLifecycleLock(path))")
        val lockedTail = internal.substring(pathLockAt)
        val freshBegin = functionBody(
            registry,
            "fun beginFreshColdRollingDiscoveryAfterValidated(",
        )
        val beginInternal = functionBody(registry, "private fun beginDiscoveryInternal(")

        assertTrue(currentStart.contains("expectedViewerGeneration"))
        assertTrue(currentStart.contains("CurrentValidatedRedriveResult.SOURCE_SETTLING"))
        assertTrue(currentStart.contains("freshAdmissionAttempted.get()"))
        assertTrue(currentStart.contains("hasCurrentDiscoveryEntry(path)"))
        assertTrue(pathLockAt >= 0)
        assertTrue(lockedTail.contains("ViewerTelemetry.isActiveViewer("))
        assertTrue(lockedTail.contains("expectedCurrentViewerGeneration"))
        assertTrue(lockedTail.contains("requireFreshValidatedGeneration"))
        assertTrue(lockedTail.contains("beginFreshColdRollingDiscoveryAfterValidated("))
        val flightPublish = lockedTail.indexOf("flights[path] = admitted")
        val postPublishOwnerCheck = lockedTail.indexOf(
            "if (!ViewerTelemetry.isActiveViewer(viewerGeneration, ownerPath))",
            flightPublish,
        )
        assertTrue(flightPublish >= 0 && postPublishOwnerCheck > flightPublish)
        assertTrue(freshBegin.contains("requireFreshGeneration = true"))
        assertTrue(beginInternal.contains("if (requireFreshGeneration) return@synchronized null"))
        assertFalse(currentStart.contains("startStrictNtkDiscovery("))
        assertTrue(currentStart.contains("retireCompletedTerminalCurrentFlightForValidatedReplacement("))

        val completedTerminal = functionBody(
            coordinator,
            "private fun retireCompletedTerminalCurrentFlightForValidatedReplacement(",
        )
        assertTrue(completedTerminal.contains("flight.completed.get()"))
        assertTrue(completedTerminal.contains("snapshot != null"))
        assertTrue(completedTerminal.contains("NtkSourceState.TERMINAL_CLOSING"))
        assertTrue(completedTerminal.contains("detachFlightForForegroundLeaveLocked(flight)"))
        assertTrue(completedTerminal.contains("completeDetachedFlightForegroundLeave(retired)"))
    }

    @Test
    fun routeRecoveryCarriesEveryFailedViewerOwnerGenerationForward() {
        val recovery = functionBody(coordinator, "private fun recoverStrictRouteAndRestart(")

        assertTrue(recovery.contains("expectedCurrentViewerGeneration = failedFlight.viewerGeneration"))
        assertTrue(recovery.contains("expectedCurrentOwnerEpisodePath = failedFlight.viewerOwnerEpisodePath"))
        assertFalse(recovery.contains("failedFlight.viewerOwnerEpisodePath == failedFlight.episodePath"))
    }

    @Test
    fun completionIsQualifiedByTicketEpochPathAndViewerGeneration() {
        val complete = functionBody(
            activity,
            "private fun completeStrictNtkValidatedNetworkRedrive(",
        )

        assertTrue(complete.contains("strictNtkNetworkTicketEpoch != ticket.epoch"))
        assertTrue(complete.contains("strictNtkNetworkTicketPath != normalizedExpectedPath"))
        assertTrue(complete.contains("strictNtkNetworkTicketGeneration != expectedViewerGeneration"))
        assertTrue(complete.contains("pendingTicket()?.epoch != ticket.epoch"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
