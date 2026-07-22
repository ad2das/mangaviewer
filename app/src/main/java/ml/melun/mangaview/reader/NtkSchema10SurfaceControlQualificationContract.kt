package ml.melun.mangaview.reader

/** Strict physical-frame verdict for asynchronous NTK10 SurfaceControl evidence. */
object NtkSchema10SurfaceControlQualificationContract {
    @JvmStatic
    fun violations(
        evidence: NtkSchema10QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long
    ): List<String> = violations(
        evidence,
        expectedPhysicalGestures,
        expectedLastPhysicalInputSequence,
        false
    )

    internal fun twoPeriodViolations(
        evidence: NtkSchema10QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long
    ): List<String> = violations(
        evidence,
        expectedPhysicalGestures,
        expectedLastPhysicalInputSequence,
        true
    )

    private fun violations(
        evidence: NtkSchema10QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long,
        twoPeriodTimingProfile: Boolean
    ): List<String> {
        if (evidence == null) return listOf("snapshot-absent")
        if (expectedPhysicalGestures <= 0 ||
            expectedLastPhysicalInputSequence <= 0L
        ) return listOf("expected-physical-input-invalid")
        val result = ArrayList<String>(12)
        val t = evidence.refreshPeriodNanos
        if (evidence.lifetimeEvidenceFrames <= 0 ||
            evidence.interactionEvidenceFrames <= 0
        ) result += "schema10-frame-evidence-absent"
        if (evidence.invalidFrames != 0 ||
            evidence.identityOrOrderInvalidFrames != 0 ||
            evidence.priorRetirementChainInvalidFrames != 0 ||
            evidence.reserveBeforeDrawInvalidFrames != 0
        ) result += "schema10-direct-evidence-invalid"
        if (evidence.lifetimeStageFrames != 1 ||
            evidence.interactionStageFrames != 0 ||
            evidence.acceptedTerminalInputOverflow ||
            evidence.acceptedTerminalInputCount != expectedPhysicalGestures ||
            evidence.coveredTerminalInputCount != expectedPhysicalGestures ||
            evidence.missingTerminalInputCount != 0 ||
            evidence.duplicateTerminalInputCoverageCount != 0 ||
            evidence.lastAcceptedTerminalInputSequence !=
                expectedLastPhysicalInputSequence ||
            evidence.lastInteractionInputWatermark !=
                expectedLastPhysicalInputSequence
        ) result += "physical-input-frame-coverage-invalid"
        if (evidence.submittedCount != evidence.lifetimeEvidenceFrames.toLong() ||
            evidence.onCommitCount != evidence.submittedCount ||
            evidence.acquireSignalCount != evidence.submittedCount ||
            evidence.onCompleteCount != evidence.submittedCount ||
            evidence.retirementCount != evidence.submittedCount ||
            evidence.fullJoinCount != evidence.submittedCount ||
            evidence.maxAppliedOutstanding !in 1L..8L ||
            evidence.releaseBacklogOverlapFrames <= 0 ||
            evidence.applyBeforeAcquireSignalProvenCount !in
                0..evidence.interactionEvidenceFrames ||
            evidence.acquireFenceOwnershipInvalidFrames != 0
        ) result += "surfacecontrol-event-conservation-invalid"
        if (evidence.targetUnretiredMax != 1L ||
            evidence.preparedProducerMax != 1L ||
            evidence.commitProofPendingMax != 1L ||
            evidence.completeProofPendingMax !in 1L..8L ||
            evidence.appliedCallbackRecordMax !in 1L..8L ||
            evidence.previousReleaseDepthMax !in 1L..7L ||
            evidence.frameworkHeldRefMax !in 1L..7L ||
            evidence.freeReusableMin < 1L ||
            evidence.appOwnedBufferDomainMin < 1L
        ) result += "bounded-ledger-conservation-invalid"
        val expectedPriorLatchGates = evidence.submittedCount - 1L
        val latchPolicyInvalid = expectedPriorLatchGates < 1L ||
            evidence.priorLatchGateUsedCount != expectedPriorLatchGates ||
            evidence.waitingPriorLatchStatusCount !in
                0L..evidence.priorLatchGateUsedCount
        if (latchPolicyInvalid ||
            evidence.backendCapacityExhaustedCount != 0L ||
            evidence.backendCapacityWaitCount != 0L ||
            evidence.backpressureDisableCount != 0L ||
            evidence.backendInvariantFatalCount != 0L ||
            evidence.backpressureEnableCount != 1L
        ) result += "backend-policy-invalid"
        if (evidence.successorApplyBeforePriorCommitPairs != 0) {
            result += "prior-latch-gate-bypassed"
        }
        if (evidence.successorApplyBeforePriorCompleteCount <= 0L) {
            result += "prior-complete-overlap-not-exercised"
        }
        if (t <= 0L || evidence.refreshPeriodInvalidFrames != 0) {
            result += "schema10-refresh-period-invalid"
            return result
        }
        if (evidence.invalidTimestampOrOrderSamples != 0) {
            result += "schema10-timestamp-order-invalid"
        }
        val interactionFrames = evidence.interactionEvidenceFrames
        if (!complete(evidence.transactionPrepare, interactionFrames) ||
            !complete(evidence.applyCall, interactionFrames) ||
            evidence.fixedOpportunityInvalidFrames != 0
        ) result += "fixed-opportunity-invalid"
        if (!complete(evidence.renderToAcquireSignal, interactionFrames) ||
            evidence.renderToAcquireSignal.p95Nanos > (9L * t) / 4L ||
            evidence.renderToAcquireSignal.p99Nanos > 3L * t ||
            evidence.renderToAcquireSignal.maxNanos > 4L * t ||
            !complete(evidence.transportReadyToApply, interactionFrames) ||
            evidence.transportReadyToApply.p95Nanos > t ||
            evidence.transportReadyToApply.p99Nanos > 2L * t ||
            evidence.transportReadyToApply.maxNanos > 2L * t
        ) result += "renderer-preparation-overlap-jank"
        // Both profiles retain the exact retirement+latch JOIN.  The cold
        // two-period cold qualification merely uses the user's nominal 2T
        // cadence envelope; it does not remove or weaken admission authority.
        val mutationToApplyP95Limit =
            (if (twoPeriodTimingProfile) 4L else 3L) * t
        val mutationToApplyP99Limit =
            (if (twoPeriodTimingProfile) 5L else 4L) * t
        val mutationToApplyMaxLimit =
            (if (twoPeriodTimingProfile) 6L else 5L) * t
        val mutationToLatchP95Limit =
            (if (twoPeriodTimingProfile) 5L else 4L) * t
        val mutationToLatchP99Limit =
            (if (twoPeriodTimingProfile) 6L else 5L) * t
        val mutationToLatchMaxLimit =
            (if (twoPeriodTimingProfile) 7L else 6L) * t
        val physicalInputToLatchP95Limit =
            (if (twoPeriodTimingProfile) 6L else 5L) * t
        val physicalInputToLatchP99Limit =
            (if (twoPeriodTimingProfile) 7L else 6L) * t
        val physicalInputToLatchMaxLimit =
            (if (twoPeriodTimingProfile) 8L else 7L) * t
        if (!complete(evidence.mutationToApply, interactionFrames) ||
            evidence.mutationToApply.p95Nanos > mutationToApplyP95Limit ||
            evidence.mutationToApply.p99Nanos > mutationToApplyP99Limit ||
            evidence.mutationToApply.maxNanos > mutationToApplyMaxLimit ||
            !complete(evidence.mutationToLatch, interactionFrames) ||
            evidence.mutationToLatch.p95Nanos > mutationToLatchP95Limit ||
            evidence.mutationToLatch.p99Nanos > mutationToLatchP99Limit ||
            evidence.mutationToLatch.maxNanos > mutationToLatchMaxLimit ||
            !complete(evidence.physicalInputToLatch, interactionFrames) ||
            evidence.physicalInputToLatch.p95Nanos >
                physicalInputToLatchP95Limit ||
            evidence.physicalInputToLatch.p99Nanos >
                physicalInputToLatchP99Limit ||
            evidence.physicalInputToLatch.maxNanos >
                physicalInputToLatchMaxLimit
        ) result += "input-to-display-jank"
        val cadenceP95Limit = if (twoPeriodTimingProfile) {
            (9L * t) / 4L
        } else {
            2L * t
        }
        if (evidence.continuousApplyInterval.samples <= 0 ||
            evidence.continuousApplyInterval.samples !=
                evidence.continuousLatchInterval.samples ||
            cadenceInvalid(
                evidence.continuousApplyInterval,
                cadenceP95Limit,
                3L * t,
                4L * t
            ) ||
            cadenceInvalid(
                evidence.continuousLatchInterval,
                cadenceP95Limit,
                3L * t,
                4L * t
            ) ||
            evidence.continuousPairsOverFourPeriods != 0 ||
            (!twoPeriodTimingProfile &&
                evidence.consecutivePairsOverTwoPeriods != 0)
        ) result += "continuous-demand-jank"
        return result
    }

    @JvmStatic
    fun violation(
        evidence: NtkSchema10QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long
    ): String? = violations(
        evidence, expectedPhysicalGestures, expectedLastPhysicalInputSequence
    ).takeIf { it.isNotEmpty() }?.joinToString(";")

    private fun complete(
        stats: NtkSchema10DurationStats,
        expected: Int
    ): Boolean = expected > 0 && stats.samples == expected

    private fun cadenceInvalid(
        stats: NtkSchema10DurationStats,
        p95Limit: Long,
        p99Limit: Long,
        maxLimit: Long
    ): Boolean = stats.p95Nanos > p95Limit ||
        stats.p99Nanos > p99Limit || stats.maxNanos > maxLimit
}
