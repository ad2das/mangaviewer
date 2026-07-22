package ml.melun.mangaview.reader

import kotlin.math.ceil
import kotlin.math.max

data class NtkSchema10DurationStats(
    val samples: Int,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val maxNanos: Long
)

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
    val continuousLatchInterval: NtkSchema10DurationStats
)

/** Lifetime NTK10 ledger with immutable physical-interaction baselines. */
internal class NtkSchema10QualificationAccumulator {
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

    private val records = ArrayList<Record>(512)
    private var interactionBaselineCapsuleSequence = Long.MAX_VALUE

    @Synchronized
    fun accept(frame: NtkStripRenderEngine.FrameSnapshot) {
        val values = frame.schema10Values.copyOf()
        val violation = NtkSchema10FrameValidator.violation(values)
        records += Record(values, violation)
    }

    @Synchronized
    fun beginInteractionWindow() {
        interactionBaselineCapsuleSequence =
            records.lastOrNull()?.capsuleSequence ?: 0L
    }

    @Synchronized
    fun snapshot(
        acceptedTerminalInputSequences: LongArray,
        acceptedTerminalInputCount: Int,
        acceptedTerminalInputOverflow: Boolean
    ): NtkSchema10QualificationSnapshot {
        val lifetime = records.toList()
        val interaction = lifetime.filter {
            it.capsuleSequence > interactionBaselineCapsuleSequence
        }
        val periods = lifetime.map { it.period }
        val period = periods.firstOrNull() ?: 0L
        val refreshInvalid = periods.count {
            kotlin.math.abs(it - NtkSchema10FrameValidator.NINETY_HZ_PERIOD_NANOS) >
                NtkSchema10FrameValidator.REFRESH_TOLERANCE_NANOS || it != period
        }

        var identityInvalid = 0
        var priorChainInvalid = 0
        var successorBeforeCommitPairs = 0
        val uniqueIndexes = intArrayOf(
            4, 5, 6, 7, 8, 98, 39, 40, 190, 195, 196, 273
        )
        val seen = uniqueIndexes.associateWith { HashSet<Long>() }
        var previous: LongArray? = null
        lifetime.forEach { record ->
            val v = record.values
            if (uniqueIndexes.any { !seen.getValue(it).add(v[it]) }) {
                ++identityInvalid
            }
            previous?.let { p ->
                if (v[0] != p[0] || v[1] != p[1] || v[2] != p[2] ||
                    v[3] != p[3] || v[4] <= p[4] || v[5] <= p[5] ||
                    v[6] <= p[6] || v[7] <= p[7] || v[8] <= p[8]
                ) ++identityInvalid
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
                val exactPrior = retirementChainExact && latchSidecarExact
                if (!exactPrior) ++priorChainInvalid
                if (v[34] < p[107]) {
                    ++successorBeforeCommitPairs
                }
            } ?: run {
                if (v[238] != 0L || v[233] != 0L || v[234] != 0L ||
                    v[235] != 0L || v[236] != 0L || v[237] != 0L ||
                    v[272] != 0L || v[276] != 0L
                ) ++priorChainInvalid
            }
            previous = v
        }

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
        val acquireOwnershipInvalid = lifetime.count {
            val v = it.values
            v[197] != 2L || v[198] != 1L ||
                v[199] != 1L || v[200] != 0L
        }
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
            lifetimeEvidenceFrames = lifetime.size,
            interactionEvidenceFrames = interaction.size,
            invalidFrames = lifetime.count { it.violation != null },
            identityOrOrderInvalidFrames = identityInvalid,
            lifetimeStageFrames = lifetime.count { it.kind == 0 },
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
            lastInteractionInputWatermark =
                interaction.lastOrNull()?.inputWatermark ?: 0L,
            submittedCount = lifetime.size.toLong(),
            onCommitCount = lifetime.sumOf { it.values[104] },
            acquireSignalCount = lifetime.count { it.values[195] > 0L }.toLong(),
            onCompleteCount = lifetime.sumOf { it.values[105] },
            retirementCount = lifetime.sumOf { it.values[175] },
            fullJoinCount = lifetime.count { it.violation == null }.toLong(),
            releaseBacklogOverlapFrames = interaction.count {
                (0 until 8).count { slot -> it.values[180 + slot] == 4L } >= 2
            },
            maxAppliedOutstanding = lifetime.maxOfOrNull { it.values[177] } ?: 0L,
            applyBeforeAcquireSignalProvenCount =
                interaction.count { it.values[201] == 1L },
            acquireFenceOwnershipInvalidFrames = acquireOwnershipInvalid,
            fixedOpportunityInvalidFrames = opportunityInvalid,
            refreshPeriodNanos = period,
            refreshPeriodInvalidFrames = refreshInvalid,
            invalidTimestampOrOrderSamples = invalidTimestamps,
            continuousPairsOverFourPeriods = overFour,
            consecutivePairsOverTwoPeriods = consecutiveOverTwo,
            targetUnretiredMax = lifetime.maxOfOrNull { it.values[230] } ?: 0L,
            preparedProducerMax = lifetime.maxOfOrNull { it.values[232] } ?: 0L,
            commitProofPendingMax = lifetime.maxOfOrNull { it.values[212] } ?: 0L,
            completeProofPendingMax = lifetime.maxOfOrNull { it.values[213] } ?: 0L,
            appliedCallbackRecordMax =
                lifetime.maxOfOrNull { it.values[206] } ?: 0L,
            previousReleaseDepthMax =
                lifetime.maxOfOrNull { it.values[207] } ?: 0L,
            frameworkHeldRefMax = lifetime.maxOfOrNull { it.values[215] } ?: 0L,
            freeReusableMin = lifetime.minOfOrNull { it.values[217] } ?: 0L,
            appOwnedBufferDomainMin =
                lifetime.minOfOrNull { it.values[219] } ?: 0L,
            priorLatchGateUsedCount = lifetime.sumOf { it.values[234] },
            waitingPriorLatchStatusCount = lifetime.sumOf { it.values[235] },
            successorApplyBeforePriorCompleteCount =
                lifetime.maxOfOrNull { it.values[225] } ?: 0L,
            successorApplyBeforePriorCommitPairs = successorBeforeCommitPairs,
            backendCapacityExhaustedCount =
                lifetime.maxOfOrNull { it.values[222] } ?: 0L,
            backendCapacityWaitCount =
                lifetime.maxOfOrNull { it.values[223] } ?: 0L,
            backpressureEnableCount =
                lifetime.maxOfOrNull { it.values[220] } ?: 0L,
            backpressureDisableCount =
                lifetime.maxOfOrNull { it.values[221] } ?: 0L,
            backendInvariantFatalCount =
                lifetime.maxOfOrNull { it.values[224] } ?: 0L,
            priorRetirementChainInvalidFrames = priorChainInvalid,
            reserveBeforeDrawInvalidFrames = lifetime.count {
                it.values[281] != 1L || it.values[277] > it.values[278] ||
                    it.values[280] <= it.values[279]
            },
            transactionPrepare = transactionPrepare,
            applyCall = applyCall,
            renderToAcquireSignal = renderToSignal,
            transportReadyToApply = transportReadyToApply,
            mutationToApply = mutationToApply,
            mutationToLatch = mutationToLatch,
            physicalInputToLatch = physicalToLatch,
            continuousApplyInterval = stats(applyIntervals),
            continuousLatchInterval = stats(latchIntervals)
        )
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
}
