package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkValidatedAdjacentFlightRetirementPolicyTest {
    @Test
    fun normalControlGateWaitIsNeverObservable() {
        val waiting = networkCandidate().copy(
            foregroundNetworkEntered = false,
            foregroundNetworkEnteredAtMs = 0L,
            phase = NtkValidatedAdjacentFlightPhase.GATE_WAIT,
            controlReady = false,
            predecessorReady = false,
            predecessorReadyAtMs = 0L,
        )

        assertNull(NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(waiting))
    }

    @Test
    fun normalPredecessorGateWaitIsNeverObservableEvenAfterForegroundEnter() {
        val waiting = networkCandidate().copy(
            predecessorReady = false,
            predecessorReadyAtMs = 0L,
        )

        assertNull(NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(waiting))
    }

    @Test
    fun enteredNetworkFlightUsesTheLatestPhysicalOrReadingOrderBoundary() {
        assertEquals(
            500L,
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(networkCandidate()),
        )
    }

    @Test
    fun fullyReleasedGateIsObservableBeforeTheWorkerEntersNetwork() {
        val released = networkCandidate().copy(
            foregroundNetworkEntered = false,
            foregroundNetworkEnteredAtMs = 0L,
            phase = NtkValidatedAdjacentFlightPhase.GATE_RELEASED,
        )

        assertEquals(
            500L,
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(released),
        )
    }

    @Test
    fun routeRecoveryReservationIsAnExplicitSecondPhysicalPhase() {
        val held = networkCandidate().copy(
            foregroundNetworkEntered = false,
            phase = NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD,
            routeRecoverySlotHeldAtMs = 700L,
            networkOwnershipRetiring = true,
        )

        assertEquals(
            700L,
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(held),
        )
    }

    @Test
    fun physicalPhaseStateMustBeInternallyConsistent() {
        val network = networkCandidate()
        val released = network.copy(
            foregroundNetworkEntered = false,
            foregroundNetworkEnteredAtMs = 0L,
            phase = NtkValidatedAdjacentFlightPhase.GATE_RELEASED,
        )
        val route = network.copy(
            foregroundNetworkEntered = false,
            phase = NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD,
            routeRecoverySlotHeldAtMs = 700L,
            networkOwnershipRetiring = true,
        )
        val malformed = listOf(
            network.copy(foregroundNetworkEntered = false),
            network.copy(foregroundNetworkEnteredAtMs = 0L),
            network.copy(networkOwnershipRetiring = true),
            network.copy(phase = NtkValidatedAdjacentFlightPhase.GATE_WAIT),
            released.copy(foregroundNetworkEntered = true),
            released.copy(networkOwnershipRetiring = true),
            route.copy(foregroundNetworkEntered = true),
            route.copy(routeRecoverySlotHeldAtMs = 0L),
            route.copy(networkOwnershipRetiring = false),
        )

        malformed.forEach { candidate ->
            assertNull(NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(candidate))
        }
    }

    @Test
    fun bothPhysicalPhasesStillRequireEveryReadingOrderGate() {
        val network = networkCandidate()
        val released = network.copy(
            foregroundNetworkEntered = false,
            foregroundNetworkEnteredAtMs = 0L,
            phase = NtkValidatedAdjacentFlightPhase.GATE_RELEASED,
        )
        val route = network.copy(
            foregroundNetworkEntered = false,
            phase = NtkValidatedAdjacentFlightPhase.ROUTE_RECOVERY_SLOT_HELD,
            routeRecoverySlotHeldAtMs = 700L,
            networkOwnershipRetiring = true,
        )

        listOf(released, network, route).forEach { candidate ->
            assertNull(
                NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(
                    candidate.copy(controlReady = false),
                ),
            )
            assertNull(
                NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(
                    candidate.copy(predecessorReady = false),
                ),
            )
        }
    }

    @Test
    fun oneExactIdentityCanRetireOnlyOncePerValidatedEpoch() {
        val candidate = networkCandidate()

        assertNull(
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(
                candidate.copy(lastRetiredValidatedEpoch = candidate.validatedEpoch),
            ),
        )
        assertNull(
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(
                candidate.copy(lastRetiredValidatedEpoch = candidate.validatedEpoch + 1L),
            ),
        )
        assertEquals(
            500L,
            NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(
                candidate.copy(
                    validatedEpoch = candidate.validatedEpoch + 1L,
                    lastRetiredValidatedEpoch = candidate.validatedEpoch,
                ),
            ),
        )
    }

    @Test
    fun staleTerminalOrAuthoritativeCandidatesFailClosed() {
        val candidate = networkCandidate()
        val malformed = listOf(
            candidate.copy(exactAdjacentIdentity = false),
            candidate.copy(discoveryGeneration = 0L),
            candidate.copy(completed = true),
            candidate.copy(retired = true),
            candidate.copy(authorityReady = true),
            candidate.copy(activeViewer = false),
            candidate.copy(validatedEpoch = 0L),
        )

        malformed.forEach { state ->
            assertNull(NtkValidatedAdjacentFlightRetirementPolicy.eligibleSinceMs(state))
        }
    }

    private fun networkCandidate() = NtkValidatedAdjacentFlightRetirementCandidate(
        exactAdjacentIdentity = true,
        discoveryGeneration = 17L,
        startedAtMs = 100L,
        foregroundNetworkEntered = true,
        foregroundNetworkEnteredAtMs = 300L,
        phase = NtkValidatedAdjacentFlightPhase.NETWORK_ENTERED,
        routeRecoverySlotHeldAtMs = 0L,
        controlReady = true,
        predecessorReady = true,
        predecessorReadyAtMs = 500L,
        completed = false,
        retired = false,
        networkOwnershipRetiring = false,
        authorityReady = false,
        activeViewer = true,
        validatedEpoch = 7L,
        lastRetiredValidatedEpoch = 6L,
    )
}
