package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkValidatedAdjacentFlightRetirementArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun observationIsExactAndExcludesEveryNormalGateWait() {
        val observation = functionBody(
            coordinator,
            "fun adjacentValidatedFlightObservation(",
        )
        val candidateStart = coordinator.indexOf(
            "private fun validatedAdjacentRetirementCandidate(",
        )
        val candidateEnd = coordinator.indexOf(
            "private fun retireCompletedTerminalCurrentFlightForValidatedReplacement(",
            candidateStart,
        )
        val candidate = coordinator.substring(candidateStart, candidateEnd)

        assertTrue(observation.contains("targetPath: String?"))
        assertTrue(observation.contains("predecessorPath: String?"))
        assertTrue(observation.contains("expectedViewerGeneration: Long"))
        assertTrue(observation.contains("expectedOwnerEpisodePath: String?"))
        assertTrue(observation.contains("validatedEpoch: Long"))
        assertTrue(observation.contains("synchronized(flightLifecycleLock(target))"))
        assertTrue(observation.contains("synchronized(flight)"))
        assertTrue(observation.contains("validatedAdjacentRetirementEpochs[retirementKey]"))
        assertTrue(observation.contains("NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs"))
        assertTrue(observation.contains("candidate.discoveryGeneration"))
        assertTrue(observation.contains("candidate.startedAtMs"))
        assertTrue(observation.contains("eligibleSinceMs"))
        assertTrue(observation.contains("SystemClock.elapsedRealtime()"))

        assertTrue(candidateStart >= 0 && candidateEnd > candidateStart)
        assertTrue(candidate.contains("flight.episodePath == target"))
        assertTrue(candidate.contains("flight.adjacentPredecessorEpisodePath == predecessor"))
        assertTrue(candidate.contains("flight.viewerGeneration == expectedViewerGeneration"))
        assertTrue(candidate.contains("flight.viewerOwnerEpisodePath == expectedOwner"))
        assertTrue(candidate.contains("flight.adjacentPredecessorGate"))
        assertTrue(candidate.contains("flight.foregroundNetworkEntered.get()"))
        assertTrue(candidate.contains("flight.adjacentControlReady.isDone"))
        assertTrue(candidate.contains("flight.adjacentPredecessorComplete.isDone"))
        assertTrue(candidate.contains("currentAuthoritativeManifest(target)"))
        assertTrue(candidate.contains("ViewerTelemetry.isActiveViewer("))
    }

    @Test
    fun retirementRevalidatesTheObservationAndCommitsOneExactEpoch() {
        val retire = functionBody(
            coordinator,
            "fun retireObservedAdjacentFlightForValidatedReplacement(",
        )

        assertTrue(retire.contains("synchronized(flightLifecycleLock(target))"))
        assertTrue(retire.contains("target != observation.targetEpisodePath"))
        assertTrue(retire.contains("predecessor != observation.predecessorEpisodePath"))
        assertTrue(retire.contains("owner != observation.viewerOwnerEpisodePath"))
        assertTrue(retire.contains("flight.lease.generation.value != observation.discoveryGeneration"))
        assertTrue(retire.contains("flight.startedAtMs != observation.startedAtMs"))
        assertTrue(retire.contains("validatedAdjacentRetirementCandidate("))
        assertTrue(retire.contains("NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs"))
        assertTrue(retire.contains("candidate.phase != observation.phase"))
        assertTrue(
            retire.contains(
                "currentPhysicalPhaseEnteredAtMs != observation.physicalPhaseEnteredAtMs",
            ),
        )
        assertTrue(retire.contains("currentEligibleSinceMs != observation.eligibleSinceMs"))
        assertTrue(retire.contains("claimNetworkOwnershipRetirement(flight)"))
        assertTrue(retire.contains("NtkValidatedAdjacentFlightPhase.GATE_RELEASED"))
        assertTrue(retire.contains("NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD"))
        assertTrue(retire.contains("flight.retirement.retire(target, observation.viewerGeneration)"))
        val epochCommit = retire.indexOf(
            "validatedAdjacentRetirementEpochs[retirementKey] = observation.validatedEpoch",
        )
        val sourceRetire = retire.indexOf("retireDiscoveryForReplacement(")
        val detach = retire.indexOf("detachFlightForForegroundLeaveLocked(flight)")
        val completeLeave = retire.indexOf("completeDetachedFlightForegroundLeave(retired)")
        assertTrue(epochCommit >= 0)
        assertTrue(sourceRetire > epochCommit)
        assertTrue(detach > sourceRetire)
        assertTrue(completeLeave > detach)
        val targetLockAt = retire.indexOf("synchronized(flightLifecycleLock(target))")
        val targetLock = bracedBlockAt(retire, targetLockAt)
        assertFalse(targetLock.contains("completeDetachedFlightForegroundLeave"))
        val startInternal = functionBody(coordinator, "private fun startInternal(")
        assertTrue(startInternal.contains("foregroundNetworkLeaveBarriers[path] != null"))

        val keyStart = coordinator.indexOf("private data class ValidatedAdjacentRetirementKey(")
        val keyEnd = coordinator.indexOf(")", keyStart)
        val key = coordinator.substring(keyStart, keyEnd)
        assertTrue(key.contains("viewerGeneration"))
        assertTrue(key.contains("viewerOwnerPath"))
        assertTrue(key.contains("predecessorPath"))
        assertTrue(key.contains("targetPath"))
        assertFalse(key.contains("discoveryGeneration"))
    }

    @Test
    fun physicalEligibilityTimestampsArePublishedOnlyAtRealPhaseTransitions() {
        val enter = functionBody(coordinator, "private fun enterForegroundNetworkIfNeeded(")
        val bodyGate = functionBody(coordinator, "private fun releaseAdjacentBodyGate(")
        val publishReady = functionBody(
            coordinator,
            "private fun publishAdjacentPredecessorReady(",
        )
        val controlGate = functionBody(coordinator, "private fun releaseAdjacentControlGate(")
        val runFlight = functionBody(coordinator, "private fun runFlight(")

        val networkEnter = enter.indexOf("client.enterNtkStrictForegroundNetwork(")
        val networkAt = enter.indexOf("foregroundNetworkEnteredAtMs.set(")
        val networkPhase = enter.indexOf("NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED")
        assertTrue(networkEnter >= 0 && networkAt > networkEnter && networkPhase > networkAt)
        assertTrue(bodyGate.contains("publishAdjacentPredecessorReady(flight)"))
        assertTrue(publishReady.contains("adjacentPredecessorReadyAtMs.compareAndSet("))
        assertTrue(publishReady.contains("NtkValidatedAdjacentFlightPhase.GATE_RELEASED"))
        assertFalse(controlGate.contains("publishAdjacentPredecessorReady"))
        assertFalse(controlGate.contains("adjacentPredecessorReadyAtMs"))

        val routeBranch = runFlight.indexOf("// The resolver is intentionally demand-driven")
        val routeTail = runFlight.substring(routeBranch)
        val leave = routeTail.indexOf("leaveForegroundNetworkIfEntered(flight)")
        val routeAt = routeTail.indexOf("routeRecoverySlotHeldAtMs.set(")
        val routePhase = routeTail.indexOf(
            "NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD",
        )
        assertTrue(routeBranch >= 0)
        assertTrue(leave >= 0 && routeAt > leave && routePhase > routeAt)
    }

    @Test
    fun activityRetiresOnlyTheSessionBoundValidatedWindowAndRestartsNextTurn() {
        val post = functionBody(activity, "private fun postAdjacentExactManifestForGeneration(")
        val sessionWindow = post.indexOf("adjacentValidatedFlightRecoveryWindow(")
        val observation = post.indexOf("adjacentValidatedFlightObservation(")
        val foregroundDeadline = post.indexOf("window.observedAtMs")
        val retirement = post.indexOf("retireObservedAdjacentFlightForValidatedReplacement(")
        val repost = post.indexOf("retainedForNextTurn = statusHandler.postDelayed(", retirement)
        val replacementStart = post.indexOf("if (predecessorReady && !inFlight)")

        assertTrue(sessionWindow >= 0)
        assertTrue(observation > sessionWindow)
        assertTrue(foregroundDeadline > observation)
        assertTrue(retirement > foregroundDeadline)
        assertTrue(repost > retirement)
        assertTrue(replacementStart > repost)
        assertTrue(post.contains("NTK_ADJACENT_VALIDATED_FLIGHT_STALL_MS"))
        assertTrue(post.contains("validatedWindow != null && predecessorDrawableReady"))
    }

    @Test
    fun homeParksTheExactOwnerAndShiftsItsForegroundDeadline() {
        val pause = functionBody(activity, "private fun pauseStrictNtkValidatedNetworkRedrive(")
        val resume = functionBody(activity, "private fun resumeStrictNtkValidatedNetworkRedrive(")
        val reconcile = functionBody(activity, "private fun reconcileStrictNtkValidatedNetworkState(")
        val run = functionBody(activity, "private fun runStrictNtkValidatedNetworkRedrive(")
        val post = functionBody(activity, "private fun postAdjacentExactManifestForGeneration(")
        val repost = functionBody(activity, "private fun repostAdjacentExactDiscoveryRetryRunnables(")
        val shift = functionBody(session, "fun resumeAdjacentValidatedFlightRecoveryWindows(")
        val shiftPhysical = functionBody(
            coordinator,
            "fun shiftActiveAdjacentPhysicalEligibilityAfterPause(",
        )

        assertTrue(pause.contains("adjacentExactDiscoveryRetryRunnables.values.forEach"))
        assertTrue(pause.contains("statusHandler::removeCallbacks"))
        assertTrue(post.contains("if (!readerHostResumed)"))
        assertTrue(post.contains("retainedForNextTurn = true"))
        val physicalShiftCall = resume.indexOf(
            "shiftActiveAdjacentPhysicalEligibilityAfterPause(",
        )
        val sessionShiftCall = resume.indexOf("resumeAdjacentValidatedFlightRecoveryWindows(")
        assertTrue(physicalShiftCall >= 0 && sessionShiftCall > physicalShiftCall)
        assertTrue(resume.contains("resumeAdjacentValidatedFlightRecoveryWindows("))
        assertTrue(resume.contains("repostAdjacentExactDiscoveryRetryRunnables()"))
        assertTrue(repost.contains("statusHandler.removeCallbacks(runnable)"))
        assertTrue(repost.contains("statusHandler.post(runnable)"))
        assertTrue(reconcile.contains("pauseStrictNtkValidatedNetworkRedrive(hostPause = false)"))
        val unvalidatedRun = run.substring(
            run.indexOf("if (!isActiveNetworkValidated(manager))"),
            run.indexOf("val owner = strictActivityOwnerRecord"),
        )
        assertTrue(unvalidatedRun.contains("pauseStrictNtkValidatedNetworkRedrive(hostPause = false)"))
        val ticketBranch = reconcile.substring(reconcile.indexOf("if (ticket != null)"))
        assertTrue(ticketBranch.contains("repostAdjacentExactDiscoveryRetryRunnables()"))
        assertTrue(shift.contains("observedAtMs <= pausedAtMs -> observedAtMs + pausedDurationMs"))
        assertTrue(shift.contains("else -> resumedAtMs"))
        assertTrue(shiftPhysical.contains("ViewerTelemetry.isActiveViewer("))
        assertTrue(shiftPhysical.contains("flight.viewerGeneration != expectedViewerGeneration"))
        assertTrue(shiftPhysical.contains("flight.viewerOwnerEpisodePath != owner"))
        assertTrue(shiftPhysical.contains("flight.adjacentPredecessorGate"))
        assertTrue(shiftPhysical.contains("synchronized(flightLifecycleLock(path))"))
        assertTrue(shiftPhysical.contains("synchronized(flight)"))
        assertTrue(shiftPhysical.contains("flight.foregroundNetworkEnteredAtMs"))
        assertTrue(shiftPhysical.contains("flight.adjacentPredecessorReadyAtMs"))
        assertTrue(shiftPhysical.contains("flight.routeRecoverySlotHeldAtMs"))
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

    private fun bracedBlockAt(source: String, start: Int): String {
        check(start >= 0)
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("Unclosed block")
    }
}
