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
}

/** Lifetime NTK11 ledger retaining the V10 retirement+latch JOIN semantics. */
internal class NtkSchema11QualificationAccumulator {
    private data class Record(
        val values: LongArray,
        val violation: String?
    ) {
        val capsuleSequence get() = values[8]
    }

    private val prefix = NtkSchema10QualificationAccumulator()
    private val records = ArrayList<Record>(512)
    private var interactionBaselineCapsuleSequence = Long.MAX_VALUE

    @Synchronized
    fun accept(frame: NtkStripRenderEngine.FrameSnapshot) {
        prefix.accept(frame)
        val values = frame.schema11Values.copyOf()
        records += Record(values, NtkSchema11FrameValidator.violation(values))
    }

    @Synchronized
    fun beginInteractionWindow() {
        prefix.beginInteractionWindow()
        interactionBaselineCapsuleSequence =
            records.lastOrNull()?.capsuleSequence ?: 0L
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
        val lifetime = records.toList()
        val interaction = lifetime.filter {
            it.capsuleSequence > interactionBaselineCapsuleSequence
        }
        val last = lifetime.lastOrNull()?.values
        return NtkSchema11QualificationSnapshot(
            base = base,
            postSubmitSuccessfulCount = last?.get(282) ?: 0L,
            postSubmitLatchedProofCount = last?.get(283) ?: 0L,
            postSubmitTerminalLostProofCount = last?.get(284) ?: 0L,
            postSubmitLogicalUnlatchedNow = last?.get(285) ?: 0L,
            reason20Count = lifetime.count { it.values[287] != 0L },
            duplicateFrameIdCount =
                lifetime.count { it.values[287] == 1L },
            proofAheadCount = lifetime.count { it.values[287] == 2L },
            unlatchedOverflowCount =
                lifetime.count { it.values[287] == 3L },
            postApplyCutInvalidCount =
                lifetime.count { it.values[287] == 4L },
            maxLogicalUnlatched =
                lifetime.maxOfOrNull {
                    maxOf(it.values[286], it.values[302])
                } ?: 0L,
            applyBeforePriorCommitConsumedCount =
                lifetime.maxOfOrNull { it.values[307] } ?: 0L,
            maxCommitProofPending =
                lifetime.maxOfOrNull { it.values[305] } ?: 0L,
            rendererPriorLatchOrderingInvalidFrames =
                lifetime.count { it.values[288] != 1L },
            surfaceLatchWatermarkInvalidFrames =
                lifetime.count {
                    NtkSchema11FrameValidator.violation(it.values)
                        ?.startsWith("surface-") == true
                },
            successorLatchGateFrames =
                interaction.count { it.values[289] == 1L },
            priorLatchDeferredJoinCount =
                lifetime.sumOf { it.values[291] },
            priorOnCompletePendingAtSuccessorApplyFrames =
                interaction.count { it.values[310] == 1L }
        )
    }
}
