package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkHostGpuEmulatorCurrentWebtoonC8HealthStateTest {
    private fun evidence(
        protocol: String = "h2",
        host: String = NtkWebtoonReplicaHeaderPolicy.WIFI_DIRECT_H2_PREFERRED_HOST,
        elapsedMs: Long = 500L,
    ) = ReaderImageCache.NtkStrictPhysicalBodyEvidence(
        protocol = protocol,
        responseHost = host,
        physicalStartedAtNanos = 1_000_000_000L,
        proofReadyAtNanos = 1_000_000_000L + elapsedMs * 1_000_000L,
        physicalAttemptOrdinal = 0,
        usedRangeContinuation = false,
    )

    @Test
    fun requiresSixFreshFastDistinctPreferredH2Cohorts() {
        val state = NtkHostGpuEmulatorCurrentWebtoonC8HealthState()
        repeat(5) { index ->
            assertNull(state.recordSuccess(
                operationId = (index + 1).toLong(),
                pageIndex = index + 2,
                attemptOrdinal = 1,
                cohortKey = "cohort-$index",
                evidence = evidence(),
            ))
        }
        assertFalse(state.qualified)
        val transition = checkNotNull(state.recordSuccess(
            operationId = 6L,
            pageIndex = 7,
            attemptOrdinal = 1,
            cohortKey = "cohort-5",
            evidence = evidence(),
        ))
        assertEquals(6, transition.oldTarget)
        assertEquals(8, transition.newTarget)
        assertEquals("balanced_physical_eof", transition.reason)
        assertEquals(6, transition.distinctCohortCount)
        assertTrue(state.qualified)
        assertFalse(state.frozen)
    }

    @Test
    fun oneHealthyCohortAndDuplicateOperationCannotQualify() {
        val state = NtkHostGpuEmulatorCurrentWebtoonC8HealthState()
        repeat(12) { index ->
            state.recordSuccess(
                operationId = (index + 1).toLong(),
                pageIndex = index + 2,
                attemptOrdinal = 1,
                cohortKey = "one-cohort",
                evidence = evidence(),
            )
        }
        assertNull(state.recordSuccess(12L, 13, 1, "another", evidence()))
        assertFalse(state.qualified)
        assertFalse(state.frozen)
    }

    @Test
    fun retrySlowAlternateAndNonH2EvidenceFreezeAtSix() {
        val cases = listOf(
            Triple(2, evidence(), "retry_success"),
            Triple(1, evidence(elapsedMs = 3_001L), "slow_success"),
            Triple(1, evidence(host = "shaomoi.net"), "alternate_host_success"),
            Triple(1, evidence(protocol = "http/1.1"), "non_h2_success"),
            Triple(1, evidence().copy(physicalAttemptOrdinal = 1), "physical_failover"),
            Triple(1, evidence().copy(usedRangeContinuation = true), "range_continuation"),
        )
        cases.forEachIndexed { index, (attempt, proof, reason) ->
            val state = NtkHostGpuEmulatorCurrentWebtoonC8HealthState()
            val transition = checkNotNull(state.recordSuccess(
                operationId = (index + 1).toLong(),
                pageIndex = index + 2,
                attemptOrdinal = attempt,
                cohortKey = "cohort-$index",
                evidence = proof,
            ))
            assertEquals(reason, transition.reason)
            assertEquals(6, transition.newTarget)
            assertTrue(state.frozen)
            assertFalse(state.qualified)
        }
    }

    @Test
    fun physicalFailureAfterQualificationPermanentlyDropsToSix() {
        val state = NtkHostGpuEmulatorCurrentWebtoonC8HealthState()
        repeat(6) { index ->
            state.recordSuccess(
                operationId = (index + 1).toLong(),
                pageIndex = index + 2,
                attemptOrdinal = 1,
                cohortKey = "cohort-$index",
                evidence = evidence(),
            )
        }
        assertTrue(state.qualified)
        val failure = checkNotNull(state.recordFailure(10L, 20, 1))
        assertEquals(8, failure.oldTarget)
        assertEquals(6, failure.newTarget)
        assertEquals("physical_failure", failure.reason)
        assertTrue(state.frozen)
        repeat(20) { index ->
            assertNull(state.recordSuccess(
                operationId = 100L + index,
                pageIndex = 30 + index,
                attemptOrdinal = 1,
                cohortKey = "later-$index",
                evidence = evidence(),
            ))
        }
        assertFalse(state.qualified)
    }

    @Test
    fun fragmentedTlsRecoveryNeedsThreeDistinctExactEofProofs() {
        val state = NtkHostGpuEmulatorFragmentedTlsRecoveryHealthState()
        val recovered = evidence().copy(
            transport = NtkHostGpuEmulatorFragmentedTlsRecoveryHealthState.TRANSPORT,
        )

        assertFalse(state.recordSuccess(1L, recovered))
        assertFalse(state.recordSuccess(1L, recovered))
        assertFalse(state.recordSuccess(2L, recovered.copy(usedRangeContinuation = true)))
        assertTrue(state.recordSuccess(3L, recovered))
        assertTrue(state.qualified)
        assertFalse(state.recordSuccess(4L, recovered))
    }

    @Test
    fun ordinaryTransportCannotQualifyFragmentedTlsRecoveryLanes() {
        val state = NtkHostGpuEmulatorFragmentedTlsRecoveryHealthState(requiredSuccesses = 1)
        assertFalse(state.recordSuccess(1L, evidence()))
        assertFalse(state.qualified)
    }

    @Test
    fun currentQuicNeedsThreeExactH3ProofsAndDegradesOnFallback() {
        val state = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState()
        val h3 = evidence(protocol = "h3").copy(
            transport = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState.TRANSPORT,
        )
        // Opening pages are already transport-exact EOF/SHA evidence. They remain behind the
        // separate contiguous p0..p5 viewport fence, but must not force a redundant second proof
        // wave after that fence opens.
        assertFalse(state.recordSuccess(0, 1L, h3))
        assertFalse(state.recordSuccess(1, 2L, h3))
        assertTrue(state.recordSuccess(2, 3L, h3))
        assertTrue(state.qualified)
        assertFalse(state.wellProven)

        assertFalse(state.recordSuccess(3, 4L, h3))
        assertFalse(state.recordSuccess(4, 5L, h3))
        assertTrue(state.recordSuccess(5, 6L, h3))
        assertTrue(state.wellProven)

        assertFalse(state.recordSuccess(6, 7L, evidence()))
        assertTrue(state.qualified)
        assertFalse(state.wellProven)
        assertFalse(state.frozen)
    }

    @Test
    fun currentQuicPhysicalFailureDropsToBaseUntilFreshExactH3Window() {
        val state = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState()
        val h3 = evidence(protocol = "h3").copy(
            transport = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState.TRANSPORT,
        )
        assertTrue(state.recordFailure(0))
        assertTrue(state.frozen)
        assertFalse(state.qualified)
        assertFalse(state.wellProven)
        assertFalse(state.recordSuccess(1, 1L, h3))
        assertFalse(state.recordSuccess(2, 2L, h3))
        assertTrue(state.recordSuccess(3, 3L, h3))
        assertFalse(state.frozen)
        assertTrue(state.qualified)
        assertFalse(state.wellProven)
    }

    @Test
    fun currentQuicFallbackBeforeQualificationRestartsProofWithoutPermanentFreeze() {
        val state = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState()
        val h3 = evidence(protocol = "h3").copy(
            transport = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState.TRANSPORT,
        )
        assertFalse(state.recordSuccess(0, 1L, evidence()))
        assertFalse(state.frozen)
        assertFalse(state.qualified)
        assertFalse(state.recordSuccess(1, 2L, h3))
        assertFalse(state.recordSuccess(2, 3L, h3))
        assertTrue(state.recordSuccess(3, 4L, h3))
        assertTrue(state.qualified)
    }

    @Test
    fun qualifiedQuicRangeContinuationDegradesAndRequiresFreshProofWindow() {
        val state = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState()
        val h3 = evidence(protocol = "h3").copy(
            transport = NtkHostGpuEmulatorCurrentWebtoonQuicHealthState.TRANSPORT,
        )
        repeat(6) { index ->
            state.recordSuccess(index, index.toLong() + 1L, h3)
        }
        assertTrue(state.qualified)
        assertTrue(state.wellProven)

        assertFalse(state.recordSuccess(6, 7L, h3.copy(usedRangeContinuation = true)))
        assertTrue(state.qualified)
        assertFalse(state.wellProven)
        assertFalse(state.frozen)

        repeat(5) { index ->
            assertFalse(state.recordSuccess(7 + index, 8L + index, h3))
        }
        assertFalse(state.wellProven)
        assertTrue(state.recordSuccess(12, 13L, h3))
        assertTrue(state.wellProven)
        assertFalse(state.frozen)
    }
}
