package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkSchema10SurfaceControlQualificationContractTest {
    @Test
    fun acceptsStrictSchema10PhysicalStreamAtAllLimits() {
        assertNull(
            NtkSchema10SurfaceControlQualificationContract.violation(
                valid(), GESTURES, GESTURES.toLong()
            )
        )
    }

    @Test
    fun acceptsBothExactAcquireFenceCompletionBranches() {
        assertNull(
            NtkSchema10SurfaceControlQualificationContract.violation(
                valid().copy(applyBeforeAcquireSignalProvenCount = 0),
                GESTURES,
                GESTURES.toLong()
            )
        )
        assertNull(
            NtkSchema10SurfaceControlQualificationContract.violation(
                valid().copy(
                    applyBeforeAcquireSignalProvenCount =
                        INTERACTION_FRAMES
                ),
                GESTURES,
                GESTURES.toLong()
            )
        )
        assertViolation(
            "surfacecontrol-event-conservation-invalid",
            valid().copy(applyBeforeAcquireSignalProvenCount = -1)
        )
        assertViolation(
            "surfacecontrol-event-conservation-invalid",
            valid().copy(
                applyBeforeAcquireSignalProvenCount =
                    INTERACTION_FRAMES + 1
            )
        )
        assertViolation(
            "surfacecontrol-event-conservation-invalid",
            valid().copy(acquireSignalCount = FRAMES - 1L)
        )
        assertViolation(
            "surfacecontrol-event-conservation-invalid",
            valid().copy(acquireFenceOwnershipInvalidFrames = 1)
        )
    }

    @Test
    fun rejectsLatchGateBypassCapacityWaitAndMissingCompleteOverlapProof() {
        assertViolation(
            "backend-policy-invalid",
            valid().copy(priorLatchGateUsedCount = INTERACTION_FRAMES - 1L)
        )
        assertViolation(
            "backend-policy-invalid",
            valid().copy(
                waitingPriorLatchStatusCount = INTERACTION_FRAMES + 1L
            )
        )
        assertViolation(
            "backend-policy-invalid",
            valid().copy(backendCapacityWaitCount = 1L)
        )
        assertViolation(
            "backend-policy-invalid",
            valid().copy(backpressureEnableCount = 0L)
        )
        assertViolation(
            "backend-policy-invalid",
            valid().copy(backpressureEnableCount = 2L)
        )
        assertViolation(
            "prior-complete-overlap-not-exercised",
            valid().copy(
                successorApplyBeforePriorCompleteCount = 0L
            )
        )
        assertViolation(
            "prior-latch-gate-bypassed",
            valid().copy(successorApplyBeforePriorCommitPairs = 1)
        )
    }

    @Test
    fun rejectsProofAndReserveBeforeDrawFailuresDirectly() {
        assertViolation(
            "schema10-direct-evidence-invalid",
            valid().copy(priorRetirementChainInvalidFrames = 1)
        )
        assertViolation(
            "schema10-direct-evidence-invalid",
            valid().copy(reserveBeforeDrawInvalidFrames = 1)
        )
    }

    @Test
    fun originalSchema10TimingLimitsRemainExact() {
        assertViolation(
            "input-to-display-jank",
            valid().copy(
                mutationToApply = stats(
                    INTERACTION_FRAMES, 3L * T + 1L, 4L * T, 5L * T
                )
            )
        )
        assertViolation(
            "continuous-demand-jank",
            valid().copy(
                continuousApplyInterval = stats(
                    INTERACTION_FRAMES - 1, 2L * T + 1L, 3L * T, 4L * T
                )
            )
        )
        assertViolation(
            "continuous-demand-jank",
            valid().copy(consecutivePairsOverTwoPeriods = 1)
        )
    }

    private fun assertViolation(
        expected: String,
        snapshot: NtkSchema10QualificationSnapshot
    ) = assertEquals(
        expected,
        NtkSchema10SurfaceControlQualificationContract.violation(
            snapshot, GESTURES, GESTURES.toLong()
        )
    )

    internal fun valid() = NtkSchema10QualificationSnapshot(
        lifetimeEvidenceFrames = FRAMES,
        interactionEvidenceFrames = INTERACTION_FRAMES,
        invalidFrames = 0,
        identityOrOrderInvalidFrames = 0,
        lifetimeStageFrames = 1,
        interactionStageFrames = 0,
        moveFrames = 0,
        terminalFrames = GESTURES,
        distinctTerminalGestures = GESTURES,
        duplicateTerminalGestures = 0,
        acceptedTerminalInputCount = GESTURES,
        coveredTerminalInputCount = GESTURES,
        missingTerminalInputCount = 0,
        duplicateTerminalInputCoverageCount = 0,
        acceptedTerminalInputOverflow = false,
        lastAcceptedTerminalInputSequence = GESTURES.toLong(),
        lastInteractionInputWatermark = GESTURES.toLong(),
        submittedCount = FRAMES.toLong(),
        onCommitCount = FRAMES.toLong(),
        acquireSignalCount = FRAMES.toLong(),
        onCompleteCount = FRAMES.toLong(),
        retirementCount = FRAMES.toLong(),
        fullJoinCount = FRAMES.toLong(),
        releaseBacklogOverlapFrames = 1,
        maxAppliedOutstanding = 7L,
        applyBeforeAcquireSignalProvenCount = 0,
        acquireFenceOwnershipInvalidFrames = 0,
        fixedOpportunityInvalidFrames = 0,
        refreshPeriodNanos = T,
        refreshPeriodInvalidFrames = 0,
        invalidTimestampOrOrderSamples = 0,
        continuousPairsOverFourPeriods = 0,
        consecutivePairsOverTwoPeriods = 0,
        targetUnretiredMax = 1L,
        preparedProducerMax = 1L,
        commitProofPendingMax = 1L,
        completeProofPendingMax = 2L,
        appliedCallbackRecordMax = 7L,
        previousReleaseDepthMax = 6L,
        frameworkHeldRefMax = 7L,
        freeReusableMin = 1L,
        appOwnedBufferDomainMin = 1L,
        priorLatchGateUsedCount = INTERACTION_FRAMES.toLong(),
        waitingPriorLatchStatusCount = 1L,
        successorApplyBeforePriorCompleteCount = 1L,
        successorApplyBeforePriorCommitPairs = 0,
        backendCapacityExhaustedCount = 0L,
        backendCapacityWaitCount = 0L,
        backpressureEnableCount = 1L,
        backpressureDisableCount = 0L,
        backendInvariantFatalCount = 0L,
        priorRetirementChainInvalidFrames = 0,
        reserveBeforeDrawInvalidFrames = 0,
        transactionPrepare = stats(INTERACTION_FRAMES, 2_000_000L),
        applyCall = stats(INTERACTION_FRAMES, 2_000_000L),
        renderToAcquireSignal = stats(
            INTERACTION_FRAMES, 9L * T / 4L, 3L * T, 4L * T
        ),
        transportReadyToApply = stats(
            INTERACTION_FRAMES, T, 2L * T, 2L * T
        ),
        mutationToApply = stats(
            INTERACTION_FRAMES, 3L * T, 4L * T, 5L * T
        ),
        mutationToLatch = stats(
            INTERACTION_FRAMES, 4L * T, 5L * T, 6L * T
        ),
        physicalInputToLatch = stats(
            INTERACTION_FRAMES, 5L * T, 6L * T, 7L * T
        ),
        continuousApplyInterval = stats(
            INTERACTION_FRAMES - 1, 2L * T, 3L * T, 4L * T
        ),
        continuousLatchInterval = stats(
            INTERACTION_FRAMES - 1, 2L * T, 3L * T, 4L * T
        )
    )

    private fun stats(
        samples: Int,
        p95: Long,
        p99: Long = p95,
        max: Long = p99
    ) = NtkSchema10DurationStats(samples, p95, p99, max)

    private companion object {
        const val GESTURES = 59
        const val FRAMES = 60
        const val INTERACTION_FRAMES = 59
        const val T = 11_111_111L
    }
}
