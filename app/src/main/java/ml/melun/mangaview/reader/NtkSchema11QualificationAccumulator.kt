package ml.melun.mangaview.reader

data class NtkSchema11QualificationSnapshot(
    val base: NtkSchema10QualificationSnapshot,
    val postSubmitSuccessfulCount: Long,
    val postSubmitLatchedProofCount: Long,
    val postSubmitTerminalLostProofCount: Long,
    val postSubmitLogicalUnlatchedNow: Long,
    val reason20Count: Int,
    val duplicateFrameIdCount: Int,
    val proofAheadCount: Int,
    val unlatchedOverflowCount: Int,
    val postApplyCutInvalidCount: Int,
    val maxLogicalUnlatched: Long,
    val applyBeforePriorCommitConsumedCount: Long,
    val maxCommitProofPending: Long,
    val rendererPriorLatchOrderingInvalidFrames: Int,
    val surfaceLatchWatermarkInvalidFrames: Int,
    val successorLatchGateFrames: Int,
    val priorLatchDeferredJoinCount: Long,
    val priorOnCompletePendingAtSuccessorApplyFrames: Int
) {
    val lifetimeEvidenceFrames get() = base.lifetimeEvidenceFrames
    val interactionEvidenceFrames get() = base.interactionEvidenceFrames
    val moveFrames get() = base.moveFrames
    val terminalFrames get() = base.terminalFrames
    val coveredTerminalInputCount get() = base.coveredTerminalInputCount
    val continuousLatchInterval get() = base.continuousLatchInterval
    val successorApplyBeforePriorCommitPairs
        get() = base.successorApplyBeforePriorCommitPairs
    val commitProofPendingMax get() = base.commitProofPendingMax
    val frameworkHeldRefMax get() = base.frameworkHeldRefMax
    val evidenceOverflow get() = base.evidenceOverflow
    val interactionEvidenceOverflow get() = base.interactionEvidenceOverflow
    val retainedInteractionEvidenceFrames
        get() = base.retainedInteractionEvidenceFrames
    val droppedInteractionEvidenceFrames
        get() = base.droppedInteractionEvidenceFrames
}

/** Bounded NTK11 ledger retaining the V10 retirement+latch JOIN semantics. */
internal class NtkSchema11QualificationAccumulator(
    interactionCapacity: Int = NtkSchema10QualificationAccumulator.DEFAULT_INTERACTION_CAPACITY,
    identityCapacity: Int = NtkSchema10QualificationAccumulator.DEFAULT_IDENTITY_CAPACITY
) {
    private val prefix = NtkSchema10QualificationAccumulator(
        interactionCapacity,
        identityCapacity
    )
    private var lastCapsuleSequence = 0L
    private var interactionWindowActive = false
    private var interactionBaselineCapsuleSequence = Long.MAX_VALUE
    private var lastPostSubmitSuccessfulCount = 0L
    private var lastPostSubmitLatchedProofCount = 0L
    private var lastPostSubmitTerminalLostProofCount = 0L
    private var lastPostSubmitLogicalUnlatchedNow = 0L
    private var reason20Count = 0L
    private var duplicateFrameIdCount = 0L
    private var proofAheadCount = 0L
    private var unlatchedOverflowCount = 0L
    private var postApplyCutInvalidCount = 0L
    private var maxLogicalUnlatched = 0L
    private var applyBeforePriorCommitConsumedCount = 0L
    private var maxCommitProofPending = 0L
    private var rendererPriorLatchOrderingInvalidFrames = 0L
    private var surfaceLatchWatermarkInvalidFrames = 0L
    private var successorLatchGateFrames = 0L
    private var priorLatchDeferredJoinCount = 0L
    private var priorOnCompletePendingAtSuccessorApplyFrames = 0L

    @Synchronized
    fun accept(frame: NtkStripRenderEngine.FrameSnapshot) {
        val prefixViolation = prefix.accept(frame)
        val values = frame.schema11Values
        if (values.size != NtkSchema11FrameValidator.FIELD_COUNT) {
            // Schema11 has no general invalid-frame counter of its own.  Reuse the already-fatal
            // conservation branch so a malformed appendix can never accidentally qualify.
            ++reason20Count
            return
        }

        lastCapsuleSequence = values[8]
        lastPostSubmitSuccessfulCount = values[282]
        lastPostSubmitLatchedProofCount = values[283]
        lastPostSubmitTerminalLostProofCount = values[284]
        lastPostSubmitLogicalUnlatchedNow = values[285]
        if (values[287] != 0L) ++reason20Count
        when (values[287]) {
            1L -> ++duplicateFrameIdCount
            2L -> ++proofAheadCount
            3L -> ++unlatchedOverflowCount
            4L -> ++postApplyCutInvalidCount
        }
        maxLogicalUnlatched = maxOf(
            maxLogicalUnlatched,
            maxOf(values[286], values[302])
        )
        applyBeforePriorCommitConsumedCount = maxOf(
            applyBeforePriorCommitConsumedCount,
            values[307]
        )
        maxCommitProofPending = maxOf(maxCommitProofPending, values[305])
        if (values[288] != 1L) ++rendererPriorLatchOrderingInvalidFrames
        if (hasSurfaceLatchWatermarkViolation(values, prefixViolation)) {
            ++surfaceLatchWatermarkInvalidFrames
        }
        priorLatchDeferredJoinCount += values[291]
        if (interactionWindowActive && values[8] > interactionBaselineCapsuleSequence) {
            if (values[289] == 1L) ++successorLatchGateFrames
            if (values[310] == 1L) {
                ++priorOnCompletePendingAtSuccessorApplyFrames
            }
        }
    }

    @Synchronized
    fun beginInteractionWindow() {
        prefix.beginInteractionWindow()
        interactionBaselineCapsuleSequence = lastCapsuleSequence
        successorLatchGateFrames = 0L
        priorOnCompletePendingAtSuccessorApplyFrames = 0L
        interactionWindowActive = true
    }

    @Synchronized
    fun snapshot(
        acceptedTerminalInputSequences: LongArray,
        acceptedTerminalInputCount: Int,
        acceptedTerminalInputOverflow: Boolean
    ): NtkSchema11QualificationSnapshot {
        val base = prefix.snapshot(
            acceptedTerminalInputSequences,
            acceptedTerminalInputCount,
            acceptedTerminalInputOverflow
        )
        return NtkSchema11QualificationSnapshot(
            base = base,
            postSubmitSuccessfulCount = lastPostSubmitSuccessfulCount,
            postSubmitLatchedProofCount = lastPostSubmitLatchedProofCount,
            postSubmitTerminalLostProofCount = lastPostSubmitTerminalLostProofCount,
            postSubmitLogicalUnlatchedNow = lastPostSubmitLogicalUnlatchedNow,
            reason20Count = saturatedInt(reason20Count),
            duplicateFrameIdCount = saturatedInt(duplicateFrameIdCount),
            proofAheadCount = saturatedInt(proofAheadCount),
            unlatchedOverflowCount = saturatedInt(unlatchedOverflowCount),
            postApplyCutInvalidCount = saturatedInt(postApplyCutInvalidCount),
            maxLogicalUnlatched = maxLogicalUnlatched,
            applyBeforePriorCommitConsumedCount =
                applyBeforePriorCommitConsumedCount,
            maxCommitProofPending = maxCommitProofPending,
            rendererPriorLatchOrderingInvalidFrames =
                saturatedInt(rendererPriorLatchOrderingInvalidFrames),
            surfaceLatchWatermarkInvalidFrames =
                saturatedInt(surfaceLatchWatermarkInvalidFrames),
            successorLatchGateFrames = saturatedInt(successorLatchGateFrames),
            priorLatchDeferredJoinCount = priorLatchDeferredJoinCount,
            priorOnCompletePendingAtSuccessorApplyFrames =
                saturatedInt(priorOnCompletePendingAtSuccessorApplyFrames)
        )
    }

    /**
     * Equivalent to `NtkSchema11FrameValidator.violation(values)?.startsWith("surface-")` after
     * the already-validated V10 prefix, without allocating a 282-long prefix on every frame.
     */
    private fun hasSurfaceLatchWatermarkViolation(
        v: LongArray,
        prefixViolation: String?
    ): Boolean {
        if (v[83] != 11L || prefixViolation != null) return false
        val successful = v[282]
        val latched = v[283]
        val lost = v[284]
        val rendererUnlatched = v[285]
        if (successful <= 0L || latched < 0L || lost != 0L ||
            rendererUnlatched != 1L ||
            successful != latched + lost + rendererUnlatched ||
            v[286] != 1L || v[287] != 0L || v[288] != 1L
        ) return false

        val hasPrior = v[238] == 1L
        if (!hasPrior) {
            if (v[289] != 0L || v[290] != 0L || v[291] != 0L ||
                v[292] != 0L || v[293] != 0L || v[294] != 0L ||
                v[295] != 0L ||
                v[296] != v[137] || v[296] <= 0L ||
                v[297] != 0L || v[298] != 0L
            ) return false
        } else if (v[289] != 1L || v[290] != 1L || v[291] !in 0L..1L ||
            v[292] != 2L || v[296] != v[137] ||
            v[256] > v[296] || v[296] > v[88] || v[88] > v[34] ||
            v[293] <= 0L || v[294] <= 0L || v[295] < v[294] ||
            v[293] != v[267] || v[294] != v[268] ||
            v[295] != v[269] || v[295] > v[34] ||
            v[297] != v[296] - v[295] || v[298] != 0L
        ) {
            return false
        }

        if (v[299] != v[273] || v[299] <= 0L ||
            v[301] != 1L || v[302] != 1L ||
            v[303] != 1L || v[304] != 1L ||
            v[305] != 1L || v[306] !in 1L..8L ||
            v[307] != 0L || v[308] < 0L || v[310] !in 0L..1L
        ) return true
        return if (!hasPrior) {
            v[300] != 0L || v[309] != 0L || v[310] != 0L
        } else {
            v[300] != v[240] || v[300] <= 0L ||
                v[309] != v[34] - v[295] || v[309] < 0L
        }
    }

    private fun saturatedInt(value: Long): Int =
        value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
