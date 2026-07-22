package ml.melun.mangaview.reader

/** Strict cold physical-frame verdict for the retirement+latch JOIN pipeline. */
object NtkSchema11SurfaceControlQualificationContract {
    @JvmStatic
    fun violations(
        evidence: NtkSchema11QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long
    ): List<String> {
        if (evidence == null) return listOf("snapshot-absent")
        val result = ArrayList<String>()
        result += NtkSchema10SurfaceControlQualificationContract
            .twoPeriodViolations(
            evidence.base,
            expectedPhysicalGestures,
            expectedLastPhysicalInputSequence
        )
        if (evidence.reason20Count != 0 ||
            evidence.duplicateFrameIdCount != 0 ||
            evidence.proofAheadCount != 0 ||
            evidence.unlatchedOverflowCount != 0 ||
            evidence.postApplyCutInvalidCount != 0
        ) result += "post-submit-conservation-branch"
        if (evidence.postSubmitSuccessfulCount <= 0L ||
            evidence.postSubmitLatchedProofCount < 0L ||
            evidence.postSubmitTerminalLostProofCount != 0L ||
            evidence.postSubmitLogicalUnlatchedNow != 1L ||
            evidence.postSubmitSuccessfulCount !=
                evidence.postSubmitLatchedProofCount +
                evidence.postSubmitTerminalLostProofCount +
                evidence.postSubmitLogicalUnlatchedNow ||
            evidence.maxLogicalUnlatched != 1L
        ) result += "renderer-logical-unlatched"
        if (evidence.applyBeforePriorCommitConsumedCount != 0L ||
            evidence.maxCommitProofPending != 1L ||
            evidence.rendererPriorLatchOrderingInvalidFrames != 0 ||
            evidence.surfaceLatchWatermarkInvalidFrames != 0
        ) result += "physical-latch-backstop"
        if (evidence.base.submittedCount <= 1L ||
            evidence.successorLatchGateFrames !=
                evidence.base.submittedCount.toInt() - 1 ||
            evidence.priorLatchDeferredJoinCount < 0L
        ) result += "successor-latch-gate-invalid"
        if (evidence.priorOnCompletePendingAtSuccessorApplyFrames <= 0) {
            result += "prior-complete-overlap-not-exercised"
        }
        return result.distinct()
    }

    @JvmStatic
    fun violation(
        evidence: NtkSchema11QualificationSnapshot?,
        expectedPhysicalGestures: Int,
        expectedLastPhysicalInputSequence: Long
    ): String? = violations(
        evidence,
        expectedPhysicalGestures,
        expectedLastPhysicalInputSequence
    ).takeIf { it.isNotEmpty() }?.joinToString(";")
}
