package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkSchema11SurfaceControlQualificationContractTest {
    @Test
    fun acceptsExactTwoPeriodLatchConjunctionStream() {
        assertNull(
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid(), 59, 59L
            )
        )
    }

    @Test
    fun acceptsNominalTwoPeriodTargetRetirementTimingAtExactLimits() {
        assertNull(
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid(), 59, 59L
            )
        )
    }

    @Test
    fun rejectsEveryTargetRetirementInputLatencyLimitAtPlusOneNanosecond() {
        val base = valid().base
        val overLimit = listOf(
            base.copy(
                mutationToApply = base.mutationToApply.copy(
                    p95Nanos = 4L * T + 1L
                )
            ),
            base.copy(
                mutationToApply = base.mutationToApply.copy(
                    p99Nanos = 5L * T + 1L
                )
            ),
            base.copy(
                mutationToApply = base.mutationToApply.copy(
                    maxNanos = 6L * T + 1L
                )
            ),
            base.copy(
                mutationToLatch = base.mutationToLatch.copy(
                    p95Nanos = 5L * T + 1L
                )
            ),
            base.copy(
                mutationToLatch = base.mutationToLatch.copy(
                    p99Nanos = 6L * T + 1L
                )
            ),
            base.copy(
                mutationToLatch = base.mutationToLatch.copy(
                    maxNanos = 7L * T + 1L
                )
            ),
            base.copy(
                physicalInputToLatch = base.physicalInputToLatch.copy(
                    p95Nanos = 6L * T + 1L
                )
            ),
            base.copy(
                physicalInputToLatch = base.physicalInputToLatch.copy(
                    p99Nanos = 7L * T + 1L
                )
            ),
            base.copy(
                physicalInputToLatch = base.physicalInputToLatch.copy(
                    maxNanos = 8L * T + 1L
                )
            )
        )
        overLimit.forEach { snapshot ->
            assertEquals(
                "input-to-display-jank",
                NtkSchema11SurfaceControlQualificationContract.violation(
                    valid().copy(base = snapshot), 59, 59L
                )
            )
        }
    }

    @Test
    fun rejectsEveryTargetRetirementCadenceLimitAtPlusOneNanosecond() {
        val base = valid().base
        val cadenceP95Limit = 9L * T / 4L
        val overLimit = listOf(
            base.copy(
                continuousApplyInterval = base.continuousApplyInterval.copy(
                    p95Nanos = cadenceP95Limit + 1L
                )
            ),
            base.copy(
                continuousApplyInterval = base.continuousApplyInterval.copy(
                    p99Nanos = 3L * T + 1L
                )
            ),
            base.copy(
                continuousApplyInterval = base.continuousApplyInterval.copy(
                    maxNanos = 4L * T + 1L
                )
            ),
            base.copy(
                continuousLatchInterval = base.continuousLatchInterval.copy(
                    p95Nanos = cadenceP95Limit + 1L
                )
            ),
            base.copy(
                continuousLatchInterval = base.continuousLatchInterval.copy(
                    p99Nanos = 3L * T + 1L
                )
            ),
            base.copy(
                continuousLatchInterval = base.continuousLatchInterval.copy(
                    maxNanos = 4L * T + 1L
                )
            )
        )
        overLimit.forEach { snapshot ->
            assertEquals(
                "continuous-demand-jank",
                NtkSchema11SurfaceControlQualificationContract.violation(
                    valid().copy(base = snapshot), 59, 59L
                )
            )
        }
        assertEquals(
            "continuous-demand-jank",
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid().copy(
                    base = base.copy(continuousPairsOverFourPeriods = 1)
                ),
                59,
                59L
            )
        )
    }

    @Test
    fun rejectsReason20AndAnyMissingLatchGate() {
        assertEquals(
            "post-submit-conservation-branch",
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid().copy(reason20Count = 1), 59, 59L
            )
        )
        assertEquals(
            "successor-latch-gate-invalid",
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid().copy(successorLatchGateFrames = 0),
                59,
                59L
            )
        )
        assertEquals(
            "physical-latch-backstop",
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid().copy(applyBeforePriorCommitConsumedCount = 1L),
                59,
                59L
            )
        )
        assertEquals(
            "renderer-logical-unlatched",
            NtkSchema11SurfaceControlQualificationContract.violation(
                valid().copy(postSubmitLogicalUnlatchedNow = 2L),
                59,
                59L
            )
        )
    }

    private fun valid() = NtkSchema11QualificationSnapshot(
        base = NtkSchema10SurfaceControlQualificationContractTest().valid().copy(
            releaseBacklogOverlapFrames = 1,
            applyBeforeAcquireSignalProvenCount = 0,
            commitProofPendingMax = 1L,
            priorLatchGateUsedCount = 59L,
            waitingPriorLatchStatusCount = 0L,
            successorApplyBeforePriorCompleteCount = 1L,
            consecutivePairsOverTwoPeriods = 20,
            mutationToApply = NtkSchema10DurationStats(
                59, 4L * T, 5L * T, 6L * T
            ),
            mutationToLatch = NtkSchema10DurationStats(
                59, 5L * T, 6L * T, 7L * T
            ),
            physicalInputToLatch = NtkSchema10DurationStats(
                59, 6L * T, 7L * T, 8L * T
            ),
            continuousApplyInterval = NtkSchema10DurationStats(
                58, 9L * T / 4L, 3L * T, 4L * T
            ),
            continuousLatchInterval = NtkSchema10DurationStats(
                58, 9L * T / 4L, 3L * T, 4L * T
            )
        ),
        postSubmitSuccessfulCount = 60L,
        postSubmitLatchedProofCount = 59L,
        postSubmitTerminalLostProofCount = 0L,
        postSubmitLogicalUnlatchedNow = 1L,
        reason20Count = 0,
        duplicateFrameIdCount = 0,
        proofAheadCount = 0,
        unlatchedOverflowCount = 0,
        postApplyCutInvalidCount = 0,
        maxLogicalUnlatched = 1L,
        applyBeforePriorCommitConsumedCount = 0L,
        maxCommitProofPending = 1L,
        rendererPriorLatchOrderingInvalidFrames = 0,
        surfaceLatchWatermarkInvalidFrames = 0,
        successorLatchGateFrames = 59,
        priorLatchDeferredJoinCount = 0L,
        priorOnCompletePendingAtSuccessorApplyFrames = 1
    )

    private companion object {
        const val T = 11_111_111L
    }
}
