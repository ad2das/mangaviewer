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
}
