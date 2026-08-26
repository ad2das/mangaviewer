package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkForegroundNetworkLeaveBarrierArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()

    @Test
    fun oneLockedHelperOwnsEveryFlightMapDetach() {
        val detach = functionBody(
            coordinator,
            "private fun detachFlightForForegroundLeaveLocked(",
        )
        val complete = functionBody(
            coordinator,
            "private fun completeDetachedFlightForegroundLeave(",
        )
        val removals = Regex("flights\\.remove\\(").findAll(coordinator).toList()

        assertEquals(1, removals.size)
        assertTrue(detach.contains("Thread.holdsLock(flightLifecycleLock(path))"))
        assertTrue(detach.contains("if (flights[path] !== flight) return false"))
        assertTrue(detach.contains("foregroundNetworkLeaveBarriers.putIfAbsent(path, flight)"))
        assertTrue(detach.contains("flights.remove(path, flight)"))
        assertTrue(detach.contains("foregroundNetworkLeaveBarriers.remove(path, flight)"))
        val leave = complete.indexOf("leaveForegroundNetworkIfEntered(flight)")
        val lock = complete.indexOf("synchronized(flightLifecycleLock(flight.episodePath))")
        val compareRemove = complete.indexOf(
            "foregroundNetworkLeaveBarriers.remove(flight.episodePath, flight)",
        )
        assertTrue(leave >= 0 && lock > leave && compareRemove > lock)
    }

    @Test
    fun exactlyOnceLeaveJoinsAnAlreadyClaimedClientLeave() {
        val leave = functionBody(coordinator, "private fun leaveForegroundNetworkIfEntered(")
        val await = functionBody(
            coordinator,
            "private fun awaitForegroundNetworkLeaveUninterruptibly(",
        )

        assertTrue(leave.contains("NtkForegroundNetworkLeavePolicy.action("))
        assertTrue(leave.contains("foregroundNetworkEntered.compareAndSet(true, false)"))
        assertTrue(leave.contains("foregroundNetworkLeaveStarted.compareAndSet(false, true)"))
        assertTrue(leave.contains("client.leaveNtkStrictForegroundNetwork("))
        assertTrue(leave.contains("foregroundNetworkLeaveCompleted.complete(Unit)"))
        assertTrue(leave.contains("awaitForegroundNetworkLeaveUninterruptibly(flight)"))
        assertTrue(await.contains("foregroundNetworkLeaveCompleted.get()"))
        assertTrue(await.contains("Thread.currentThread().interrupt()"))
    }

    @Test
    fun everyForegroundCapableDetachPathUsesTheCommonBarrier() {
        val expectedFunctions = listOf(
            "private fun startInternal(",
            "fun retireCurrentFlightForValidatedReplacement(",
            "fun retireObservedAdjacentFlightForValidatedReplacement(",
            "private fun retireCompletedTerminalCurrentFlightForValidatedReplacement(",
            "private fun retireCompletedUnusableAdjacentFlightForReplacement(",
            "fun retireViewerOwnership(",
            "fun retireAdjacentTargetForReplacement(",
            "fun retireCancelledAdjacentTargetForReplacement(",
            "fun retireConsumedTargetOwnership(",
            "private fun runFlight(",
            "private fun recoverStrictRouteAndRestart(",
        )
        expectedFunctions.forEach { signature ->
            val body = functionBody(coordinator, signature)
            assertTrue(
                "$signature must detach through the shared foreground-leave barrier",
                body.contains("detachFlightForForegroundLeaveLocked("),
            )
        }

        val start = functionBody(coordinator, "private fun startInternal(")
        assertTrue(start.contains("foregroundNetworkLeaveBarriers[path] != null"))
        assertTrue(start.contains("completeDetachedFlightForegroundLeave(admitted)"))
        assertFalse(start.contains("flights.remove("))
    }

    @Test
    fun completedAdjacentWithNoUsableRegistryIsRetiredBeforeAdmission() {
        val start = functionBody(coordinator, "private fun startInternal(")
        val completed = functionBody(
            coordinator,
            "private fun retireCompletedUnusableAdjacentFlightForReplacement(",
        )
        val recovery = start.indexOf(
            "retireCompletedUnusableAdjacentFlightForReplacement(",
        )
        val admission = start.indexOf("val adjacentAdmissionLock")

        assertTrue(recovery >= 0 && admission > recovery)
        assertTrue(completed.contains("synchronized(flightLifecycleLock(targetPath))"))
        assertTrue(completed.contains("flight.adjacentPredecessorEpisodePath != predecessorPath"))
        assertTrue(completed.contains("flight.viewerGeneration != expectedViewerGeneration"))
        assertTrue(completed.contains("flight.viewerOwnerEpisodePath != expectedOwnerEpisodePath"))
        assertTrue(completed.contains("flight.adjacentPredecessorGate"))
        assertTrue(completed.contains("flight.completed.get()"))
        assertTrue(completed.contains("currentAuthoritativeManifest(targetPath)"))
        assertTrue(completed.contains("currentSnapshot(targetPath)"))
        assertTrue(completed.contains("NtkCompletedAdjacentRegistryPolicy.isUnusable("))
        assertTrue(completed.contains("claimNetworkOwnershipRetirement(flight)"))
        assertTrue(completed.contains("detachFlightForForegroundLeaveLocked(flight)"))
        assertTrue(completed.contains("completeDetachedFlightForegroundLeave(retired)"))
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
