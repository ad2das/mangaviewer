package ml.melun.mangaview.reader

import kotlin.math.ceil
import kotlin.math.max

data class NtkSchema10DurationStats(
    val samples: Int,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val maxNanos: Long
)

data class NtkQualificationRetentionSnapshot(
    val evidenceOverflow: Boolean,
    val interactionEvidenceOverflow: Boolean,
    val identityEvidenceOverflow: Boolean,
    val retainedInteractionEvidenceFrames: Int,
    val droppedInteractionEvidenceFrames: Long
) {
    companion object {
        val EMPTY = NtkQualificationRetentionSnapshot(false, false, false, 0, 0L)
    }
}

data class NtkSchema10QualificationSnapshot(
    val lifetimeEvidenceFrames: Int,
    val interactionEvidenceFrames: Int,
    val invalidFrames: Int,
    val identityOrOrderInvalidFrames: Int,
    val lifetimeStageFrames: Int,
    val interactionStageFrames: Int,
    val moveFrames: Int,
    val terminalFrames: Int,
    val distinctTerminalGestures: Int,
    val duplicateTerminalGestures: Int,
    val acceptedTerminalInputCount: Int,
    val coveredTerminalInputCount: Int,
    val missingTerminalInputCount: Int,
    val duplicateTerminalInputCoverageCount: Int,
    val acceptedTerminalInputOverflow: Boolean,
    val lastAcceptedTerminalInputSequence: Long,
    val lastInteractionInputWatermark: Long,
    val submittedCount: Long,
    val onCommitCount: Long,
    val acquireSignalCount: Long,
    val onCompleteCount: Long,
    val retirementCount: Long,
    val fullJoinCount: Long,
    val releaseBacklogOverlapFrames: Int,
    val maxAppliedOutstanding: Long,
    val applyBeforeAcquireSignalProvenCount: Int,
    val acquireFenceOwnershipInvalidFrames: Int,
    val fixedOpportunityInvalidFrames: Int,
    val refreshPeriodNanos: Long,
    val refreshPeriodInvalidFrames: Int,
    val invalidTimestampOrOrderSamples: Int,
    val continuousPairsOverFourPeriods: Int,
    val consecutivePairsOverTwoPeriods: Int,
    val targetUnretiredMax: Long,
    val preparedProducerMax: Long,
    val commitProofPendingMax: Long,
    val completeProofPendingMax: Long,
    val appliedCallbackRecordMax: Long,
    val previousReleaseDepthMax: Long,
    val frameworkHeldRefMax: Long,
    val freeReusableMin: Long,
    val appOwnedBufferDomainMin: Long,
    val priorLatchGateUsedCount: Long,
    val waitingPriorLatchStatusCount: Long,
    val successorApplyBeforePriorCompleteCount: Long,
    val successorApplyBeforePriorCommitPairs: Int,
    val backendCapacityExhaustedCount: Long,
    val backendCapacityWaitCount: Long,
    val backpressureEnableCount: Long,
    val backpressureDisableCount: Long,
    val backendInvariantFatalCount: Long,
    val priorRetirementChainInvalidFrames: Int,
    val reserveBeforeDrawInvalidFrames: Int,
    val transactionPrepare: NtkSchema10DurationStats,
    val applyCall: NtkSchema10DurationStats,
    val renderToAcquireSignal: NtkSchema10DurationStats,
    val transportReadyToApply: NtkSchema10DurationStats,
    val mutationToApply: NtkSchema10DurationStats,
    val mutationToLatch: NtkSchema10DurationStats,
    val physicalInputToLatch: NtkSchema10DurationStats,
    val continuousApplyInterval: NtkSchema10DurationStats,
    val continuousLatchInterval: NtkSchema10DurationStats,
    /** Bounded-ledger state; overflow also contributes a synthetic [invalidFrames] entry. */
    val retention: NtkQualificationRetentionSnapshot =
        NtkQualificationRetentionSnapshot.EMPTY
) {
    val evidenceOverflow get() = retention.evidenceOverflow
    val interactionEvidenceOverflow get() = retention.interactionEvidenceOverflow
    val identityEvidenceOverflow get() = retention.identityEvidenceOverflow
    val retainedInteractionEvidenceFrames
        get() = if (retention === NtkQualificationRetentionSnapshot.EMPTY) {
            interactionEvidenceFrames
        } else {
            retention.retainedInteractionEvidenceFrames
        }
    val droppedInteractionEvidenceFrames
        get() = retention.droppedInteractionEvidenceFrames
}

/**
 * Bounded NTK10 evidence accumulator.
 *
 * Lifetime values are reduced online.  Full frame arrays are retained only after an explicit
 * [beginInteractionWindow], because those arrays are required to reproduce exact percentile and
 * terminal-input coverage evidence.  This keeps normal reader use O(1) in both memory and per-frame
 * allocation while preserving the existing qualification semantics for every non-overflow window.
 */
internal class NtkSchema10QualificationAccumulator(
    private val interactionCapacity: Int = DEFAULT_INTERACTION_CAPACITY,
    identityCapacity: Int = DEFAULT_IDENTITY_CAPACITY
) {
    private data class Record(val values: LongArray, val violation: String?) {
        val kind get() = values[74].toInt()
        val gesture get() = values[22]
        val capsuleSequence get() = values[8]
        val frameSequence get() = values[6]
        val inputWatermark get() = values[23]
        val applyEnd get() = values[35]
        val latch get() = values[38]
        val period get() = values[87]
        val visualDemandEpoch get() = values[202]
        val visualMutationSerial get() = values[203]
        val visibleStateChanged get() = values[204] == 1L
    }

    private class BoundedSortedLongSet(capacity: Int) {
        private val values = LongArray(capacity)
        private var size = 0

        /** Exact duplicate detection until the fixed backing array is full. */
        fun add(value: Long): AddResult {
            if (size == 0 || value > values[size - 1]) {
                if (size == values.size) return AddResult.OVERFLOW
                values[size++] = value
                return AddResult.ADDED
            }
            val index = java.util.Arrays.binarySearch(values, 0, size, value)
            if (index >= 0) return AddResult.DUPLICATE
            if (size == values.size) return AddResult.OVERFLOW
            val insertion = -index - 1
            values.copyInto(values, insertion + 1, insertion, size)
            values[insertion] = value
            ++size
            return AddResult.ADDED
        }
    }

    private enum class AddResult { ADDED, DUPLICATE, OVERFLOW }

    private val interactionRecords = ArrayList<Record>(minOf(interactionCapacity, 512))
    private val uniqueIdentitySets = Array(UNIQUE_IDENTITY_INDEXES.size) {
        BoundedSortedLongSet(identityCapacity)
    }
    private val previousValues = LongArray(NtkSchema10FrameValidator.FIELD_COUNT)
    private var hasPreviousValues = false
    private var lastCapsuleSequence = 0L
    private var interactionWindowActive = false
    private var interactionBaselineCapsuleSequence = Long.MAX_VALUE
    private var interactionEvidenceCount = 0L
    private var interactionLastInputWatermark = 0L
    private var droppedInteractionEvidenceCount = 0L
    private var interactionOverflow = false
    private var identityOverflow = false

    private var lifetimeEvidenceCount = 0L
    private var lifetimeInvalidCount = 0L
    private var lifetimeIdentityInvalidCount = 0L
    private var lifetimePriorChainInvalidCount = 0L
    private var lifetimeSuccessorBeforeCommitPairs = 0L
    private var lifetimeStageCount = 0L
    private var lifetimeOnCommitCount = 0L
    private var lifetimeAcquireSignalCount = 0L
    private var lifetimeOnCompleteCount = 0L
    private var lifetimeRetirementCount = 0L
    private var lifetimeFullJoinCount = 0L
    private var lifetimeAcquireOwnershipInvalidCount = 0L
    private var lifetimeReserveBeforeDrawInvalidCount = 0L
    private var firstRefreshPeriodNanos = 0L
    private var lifetimeRefreshInvalidCount = 0L
    private var maxAppliedOutstanding = 0L
    private var targetUnretiredMax = 0L
    private var preparedProducerMax = 0L
    private var commitProofPendingMax = 0L
    private var completeProofPendingMax = 0L
    private var appliedCallbackRecordMax = 0L
    private var previousReleaseDepthMax = 0L
    private var frameworkHeldRefMax = 0L
    private var freeReusableMin = Long.MAX_VALUE
    private var appOwnedBufferDomainMin = Long.MAX_VALUE
    private var priorLatchGateUsedCount = 0L
    private var waitingPriorLatchStatusCount = 0L
    private var successorApplyBeforePriorCompleteCount = 0L
    private var backendCapacityExhaustedCount = 0L
    private var backendCapacityWaitCount = 0L
    private var backpressureEnableCount = 0L
    private var backpressureDisableCount = 0L
    private var backendInvariantFatalCount = 0L

    init {
        require(interactionCapacity > 0)
        require(identityCapacity > 0)
    }

    @Synchronized
    fun accept(frame: NtkStripRenderEngine.FrameSnapshot): String? {
        val values = frame.schema10Values
        val violation = NtkSchema10FrameValidator.violation(values)
        ++lifetimeEvidenceCount
        if (violation == null) ++lifetimeFullJoinCount else ++lifetimeInvalidCount

        if (values.size != NtkSchema10FrameValidator.FIELD_COUNT) {
            // A malformed frame cannot safely participate in any indexed proof.  Count it, retain
            // no unsafe array, and make every later qualification fail closed.
            ++lifetimeIdentityInvalidCount
            if (interactionWindowActive) {
                ++interactionEvidenceCount
                ++droppedInteractionEvidenceCount
                interactionOverflow = true
            }
            return violation
        }

        lastCapsuleSequence = values[8]
        updateLifetime(values)

        if (interactionWindowActive && values[8] > interactionBaselineCapsuleSequence) {
            ++interactionEvidenceCount
            interactionLastInputWatermark = values[23]
            if (interactionRecords.size < interactionCapacity) {
                // The published FrameSnapshot remains mutable for JNI filling.  Keep an immutable
                // copy only inside an explicitly armed qualification window.
                interactionRecords += Record(values.copyOf(), violation)
            } else {
                ++droppedInteractionEvidenceCount
                interactionOverflow = true
            }
        }
        return violation
    }

    @Synchronized
    fun beginInteractionWindow() {
        interactionRecords.clear()
        interactionEvidenceCount = 0L
        interactionLastInputWatermark = 0L
        droppedInteractionEvidenceCount = 0L
        interactionOverflow = false
        interactionBaselineCapsuleSequence = lastCapsuleSequence
        interactionWindowActive = true
    }

    @Synchronized
    fun snapshot(
        acceptedTerminalInputSequences: LongArray,
        acceptedTerminalInputCount: Int,
        acceptedTerminalInputOverflow: Boolean
    ): NtkSchema10QualificationSnapshot {
        val interaction = interactionRecords.toList()

        val acceptedCount = acceptedTerminalInputCount.coerceIn(
            0, acceptedTerminalInputSequences.size
        )
        val accepted = acceptedTerminalInputSequences.copyOf(acceptedCount)
        val coverage = IntArray(acceptedCount)
        var previousWatermark = 0L
        interaction.forEach { record ->
            val watermark = record.inputWatermark
            accepted.forEachIndexed { index, sequence ->
                if (sequence > previousWatermark && sequence <= watermark) {
                    ++coverage[index]
                }
            }
            previousWatermark = max(previousWatermark, watermark)
        }
        val covered = coverage.count { it == 1 }
        val missing = coverage.count { it == 0 }
        val duplicateCoverage = coverage.count { it > 1 }

        val terminalGestureCounts = interaction.filter { it.kind == 2 }
            .groupingBy { it.gesture }.eachCount()
        val inputRecords = interaction.filter { it.kind != 0 }
        var invalidTimestamps = 0
        var overFour = 0
        var consecutiveOverTwo = 0
        var previousOverTwo = false
        val applyIntervals = ArrayList<Long>()
        val latchIntervals = ArrayList<Long>()
        interaction.zipWithNext().forEach { (first, second) ->
            val continuous = first.kind != 0 && second.kind != 0 &&
                second.frameSequence == first.frameSequence + 1L &&
                first.visibleStateChanged && second.visibleStateChanged &&
                first.visualDemandEpoch != 0L &&
                first.visualDemandEpoch == second.visualDemandEpoch &&
                second.visualMutationSerial == first.visualMutationSerial + 1L
            if (!continuous) {
                previousOverTwo = false
                return@forEach
            }
            val applyDelta = second.applyEnd - first.applyEnd
            val latchDelta = second.latch - first.latch
            if (applyDelta <= 0L || latchDelta <= 0L) {
                ++invalidTimestamps
                previousOverTwo = false
                return@forEach
            }
            applyIntervals += applyDelta
            latchIntervals += latchDelta
            if (applyDelta > 4L * second.period ||
                latchDelta > 4L * second.period
            ) ++overFour
            val overTwo = applyDelta > 2L * second.period ||
                latchDelta > 2L * second.period
            if (overTwo && previousOverTwo) ++consecutiveOverTwo
            previousOverTwo = overTwo
        }

        fun durations(block: (LongArray) -> Long?): NtkSchema10DurationStats =
            stats(interaction.mapNotNull { block(it.values) }.also { samples ->
                invalidTimestamps += samples.count { it < 0L }
            }.filter { it >= 0L })

        val transactionPrepare = durations { it[164] - it[163] }
        val applyCall = durations { it[166] }
        val renderToSignal = durations { it[194] - it[93] }
        val transportReadyToApply = durations {
            it[34] - max(it[96], max(it[138], it[161]))
        }
        val mutationToApply = stats(inputRecords.map { it.values[35] - it.values[31] })
        val mutationToLatch = stats(inputRecords.map { it.values[38] - it.values[31] })
        val physicalToLatch = stats(inputRecords.map { it.values[38] - it.values[25] })
        val opportunityInvalid = interaction.count {
            val v = it.values
            // Field 91 is the exclusive deadline for starting the fixed submission,
            // not its completion cutoff.  Since the SurfaceControl migration field 35
            // is transactionApplyEndNanos; compare the final decision/start (161) to
            // the start deadline and keep the independent end-to-cutoff proof in 169.
            v[168] < 0L || v[169] < 0L || v[161] >= v[91] ||
                v[102] != 1L || v[103] != 1L || v[170] != 1L ||
                v[171] != 1L || v[85] != 0L
        }
        return NtkSchema10QualificationSnapshot(
            lifetimeEvidenceFrames = saturatedInt(lifetimeEvidenceCount),
            interactionEvidenceFrames = saturatedInt(interactionEvidenceCount),
            invalidFrames = saturatedInt(
                lifetimeInvalidCount + if (interactionOverflow || identityOverflow) 1L else 0L
            ),
            identityOrOrderInvalidFrames = saturatedInt(
                lifetimeIdentityInvalidCount + if (identityOverflow) 1L else 0L
            ),
            lifetimeStageFrames = saturatedInt(lifetimeStageCount),
            interactionStageFrames = interaction.count { it.kind == 0 },
            moveFrames = interaction.count { it.kind == 1 },
            terminalFrames = interaction.count { it.kind == 2 },
            distinctTerminalGestures = terminalGestureCounts.size,
            duplicateTerminalGestures = terminalGestureCounts.count { it.value != 1 },
            acceptedTerminalInputCount = acceptedCount,
            coveredTerminalInputCount = covered,
            missingTerminalInputCount = missing,
            duplicateTerminalInputCoverageCount = duplicateCoverage,
            acceptedTerminalInputOverflow = acceptedTerminalInputOverflow,
            lastAcceptedTerminalInputSequence = accepted.lastOrNull() ?: 0L,
            lastInteractionInputWatermark = interactionLastInputWatermark,
            submittedCount = lifetimeEvidenceCount,
            onCommitCount = lifetimeOnCommitCount,
            acquireSignalCount = lifetimeAcquireSignalCount,
            onCompleteCount = lifetimeOnCompleteCount,
            retirementCount = lifetimeRetirementCount,
            fullJoinCount = lifetimeFullJoinCount,
            releaseBacklogOverlapFrames = interaction.count {
                (0 until 8).count { slot -> it.values[180 + slot] == 4L } >= 2
            },
            maxAppliedOutstanding = maxAppliedOutstanding,
            applyBeforeAcquireSignalProvenCount =
                interaction.count { it.values[201] == 1L },
            acquireFenceOwnershipInvalidFrames = saturatedInt(
                lifetimeAcquireOwnershipInvalidCount
            ),
            fixedOpportunityInvalidFrames = opportunityInvalid,
            refreshPeriodNanos = firstRefreshPeriodNanos,
            refreshPeriodInvalidFrames = saturatedInt(lifetimeRefreshInvalidCount),
            invalidTimestampOrOrderSamples = invalidTimestamps,
            continuousPairsOverFourPeriods = overFour,
            consecutivePairsOverTwoPeriods = consecutiveOverTwo,
            targetUnretiredMax = targetUnretiredMax,
            preparedProducerMax = preparedProducerMax,
            commitProofPendingMax = commitProofPendingMax,
            completeProofPendingMax = completeProofPendingMax,
            appliedCallbackRecordMax = appliedCallbackRecordMax,
            previousReleaseDepthMax = previousReleaseDepthMax,
            frameworkHeldRefMax = frameworkHeldRefMax,
            freeReusableMin = if (freeReusableMin == Long.MAX_VALUE) 0L else freeReusableMin,
            appOwnedBufferDomainMin =
                if (appOwnedBufferDomainMin == Long.MAX_VALUE) 0L
                else appOwnedBufferDomainMin,
            priorLatchGateUsedCount = priorLatchGateUsedCount,
            waitingPriorLatchStatusCount = waitingPriorLatchStatusCount,
            successorApplyBeforePriorCompleteCount =
                successorApplyBeforePriorCompleteCount,
            successorApplyBeforePriorCommitPairs =
                saturatedInt(lifetimeSuccessorBeforeCommitPairs),
            backendCapacityExhaustedCount = backendCapacityExhaustedCount,
            backendCapacityWaitCount = backendCapacityWaitCount,
            backpressureEnableCount = backpressureEnableCount,
            backpressureDisableCount = backpressureDisableCount,
            backendInvariantFatalCount = backendInvariantFatalCount,
            priorRetirementChainInvalidFrames =
                saturatedInt(lifetimePriorChainInvalidCount),
            reserveBeforeDrawInvalidFrames =
                saturatedInt(lifetimeReserveBeforeDrawInvalidCount),
            transactionPrepare = transactionPrepare,
            applyCall = applyCall,
            renderToAcquireSignal = renderToSignal,
            transportReadyToApply = transportReadyToApply,
            mutationToApply = mutationToApply,
            mutationToLatch = mutationToLatch,
            physicalInputToLatch = physicalToLatch,
            continuousApplyInterval = stats(applyIntervals),
            continuousLatchInterval = stats(latchIntervals),
            retention = NtkQualificationRetentionSnapshot(
                evidenceOverflow = interactionOverflow || identityOverflow,
                interactionEvidenceOverflow = interactionOverflow,
                identityEvidenceOverflow = identityOverflow,
                retainedInteractionEvidenceFrames = interaction.size,
                droppedInteractionEvidenceFrames = droppedInteractionEvidenceCount
            )
        )
    }

    private fun updateLifetime(v: LongArray) {
        if (v[74] == 0L) ++lifetimeStageCount
        lifetimeOnCommitCount += v[104]
        if (v[195] > 0L) ++lifetimeAcquireSignalCount
        lifetimeOnCompleteCount += v[105]
        lifetimeRetirementCount += v[175]
        if (v[197] != 2L || v[198] != 1L ||
            v[199] != 1L || v[200] != 0L
        ) ++lifetimeAcquireOwnershipInvalidCount
        if (v[281] != 1L || v[277] > v[278] || v[280] <= v[279]) {
            ++lifetimeReserveBeforeDrawInvalidCount
        }

        val period = v[87]
        if (lifetimeEvidenceCount == 1L) firstRefreshPeriodNanos = period
        if (kotlin.math.abs(period - NtkSchema10FrameValidator.NINETY_HZ_PERIOD_NANOS) >
            NtkSchema10FrameValidator.REFRESH_TOLERANCE_NANOS ||
            period != firstRefreshPeriodNanos
        ) ++lifetimeRefreshInvalidCount

        maxAppliedOutstanding = max(maxAppliedOutstanding, v[177])
        targetUnretiredMax = max(targetUnretiredMax, v[230])
        preparedProducerMax = max(preparedProducerMax, v[232])
        commitProofPendingMax = max(commitProofPendingMax, v[212])
        completeProofPendingMax = max(completeProofPendingMax, v[213])
        appliedCallbackRecordMax = max(appliedCallbackRecordMax, v[206])
        previousReleaseDepthMax = max(previousReleaseDepthMax, v[207])
        frameworkHeldRefMax = max(frameworkHeldRefMax, v[215])
        freeReusableMin = minOf(freeReusableMin, v[217])
        appOwnedBufferDomainMin = minOf(appOwnedBufferDomainMin, v[219])
        priorLatchGateUsedCount += v[234]
        waitingPriorLatchStatusCount += v[235]
        successorApplyBeforePriorCompleteCount = max(
            successorApplyBeforePriorCompleteCount, v[225]
        )
        backendCapacityExhaustedCount = max(backendCapacityExhaustedCount, v[222])
        backendCapacityWaitCount = max(backendCapacityWaitCount, v[223])
        backpressureEnableCount = max(backpressureEnableCount, v[220])
        backpressureDisableCount = max(backpressureDisableCount, v[221])
        backendInvariantFatalCount = max(backendInvariantFatalCount, v[224])

        if (!identityOverflow) {
            var duplicate = false
            for (slot in UNIQUE_IDENTITY_INDEXES.indices) {
                val index = UNIQUE_IDENTITY_INDEXES[slot]
                when (uniqueIdentitySets[slot].add(v[index])) {
                    AddResult.ADDED -> Unit
                    AddResult.DUPLICATE -> duplicate = true
                    AddResult.OVERFLOW -> identityOverflow = true
                }
            }
            if (duplicate) ++lifetimeIdentityInvalidCount
        }

        if (hasPreviousValues) {
            val p = previousValues
            if (v[0] != p[0] || v[1] != p[1] || v[2] != p[2] ||
                v[3] != p[3] || v[4] <= p[4] || v[5] <= p[5] ||
                v[6] <= p[6] || v[7] <= p[7] || v[8] <= p[8]
            ) ++lifetimeIdentityInvalidCount
            val retirementChainExact = v[238] == 1L &&
                v[239] == p[40] && v[240] == p[273] &&
                v[241] == p[0] && v[242] == p[3] &&
                v[243] == p[1] && v[244] == p[2] &&
                v[245] == p[4] && v[246] == p[5] &&
                v[247] == p[6] && v[248] == p[7] &&
                v[249] == p[8] && v[250] == p[97] &&
                v[251] == p[98] && v[252] == p[99] &&
                v[253] == p[100] && v[254] == p[101] &&
                v[256] <= v[88] && v[88] <= v[34]
            val latchSidecarExact = v[233] == 1L && v[234] == 1L &&
                v[235] in 0L..1L && v[236] == 2L &&
                v[237] == 0L && v[267] == p[39] &&
                v[268] == p[38] && v[269] == p[107] &&
                v[270] == p[106] && v[271] == p[104] &&
                v[276] == 1L && v[34] >= p[107]
            if (!retirementChainExact || !latchSidecarExact) {
                ++lifetimePriorChainInvalidCount
            }
            if (v[34] < p[107]) ++lifetimeSuccessorBeforeCommitPairs
        } else if (v[238] != 0L || v[233] != 0L || v[234] != 0L ||
            v[235] != 0L || v[236] != 0L || v[237] != 0L ||
            v[272] != 0L || v[276] != 0L
        ) {
            ++lifetimePriorChainInvalidCount
        }
        v.copyInto(previousValues)
        hasPreviousValues = true
    }

    private fun stats(values: List<Long>): NtkSchema10DurationStats {
        if (values.isEmpty()) return NtkSchema10DurationStats(0, 0L, 0L, 0L)
        val sorted = values.sorted()
        fun percentile(percent: Int): Long = sorted[
            (ceil(sorted.size * percent / 100.0).toInt() - 1)
                .coerceIn(sorted.indices)
        ]
        return NtkSchema10DurationStats(
            sorted.size, percentile(95), percentile(99), sorted.last()
        )
    }

    private fun saturatedInt(value: Long): Int =
        value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        internal const val DEFAULT_INTERACTION_CAPACITY = 2_048
        internal const val DEFAULT_IDENTITY_CAPACITY = 8_192
        private val UNIQUE_IDENTITY_INDEXES = intArrayOf(
            4, 5, 6, 7, 8, 98, 39, 40, 190, 195, 196, 273
        )
    }
}
