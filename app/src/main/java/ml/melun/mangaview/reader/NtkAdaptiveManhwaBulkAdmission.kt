package ml.melun.mangaview.reader

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Session-local admission gate for the offscreen part of a resumed manhwa.
 *
 * The legacy mode retains its non-overlapping benchmark cohorts for non-production tests. The
 * current host-GPU/direct-Wi-Fi path uses [healthGatedRollingRamp]: every freed slot is refilled
 * immediately, while fresh physical EOF/SHA proofs can widen C6 -> C12 -> C24. Lowering a target
 * never cancels a Call that already owns a lease.
 */
internal class NtkAdaptiveManhwaBulkAdmission(
    limits: IntArray = NtkClickOwnedManhwaWavePolicy.hostGpuCurrentBulkTransferLadder(),
    eligibleBodyCount: Int = Int.MAX_VALUE,
    private val probeWiderStages: Boolean = true,
    private val healthGatedRollingRamp: Boolean = false,
    private val finiteBodyCountKnownAtConstruction: Boolean = true,
    private val minimumImprovementRatio: Double =
        NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_MINIMUM_IMPROVEMENT_RATIO,
    private val clockNanos: () -> Long = System::nanoTime,
) : Closeable {
    data class Snapshot(
        val targetLimit: Int,
        val bestLimit: Int,
        val activeLeases: Int,
        val stageIndex: Int,
        val stageAdmissions: Int,
        val stageSuccesses: Int,
        val stageBytes: Long,
        val retryAfterDrain: Boolean,
        val settled: Boolean,
        val closed: Boolean,
        val healthGatedRollingRamp: Boolean,
        val frozen: Boolean,
        val transitionReason: String,
        val healthyReplicaHostCount: Int,
        val eligibleBodyCount: Int,
        val finiteBodyCountKnown: Boolean,
    )

    /**
     * Ephemeral transport evidence. It may tune admission only; it is not body identity authority.
     */
    data class PhysicalProof(
        val operationId: Long,
        val pageIndex: Int,
        val encodedBytes: Long,
        val expectedResponseHost: String,
        val capturedProfileLive: Boolean,
        val ordinaryClassificationLive: Boolean,
        val exactOrdinaryJpeg: Boolean,
        val evidence: ReaderImageCache.NtkStrictPhysicalBodyEvidence,
    ) {
        init {
            require(operationId > 0L)
            require(pageIndex >= 0)
            require(encodedBytes > 0L)
            require(expectedResponseHost.isNotBlank())
        }
    }

    inner class Lease internal constructor(
        private val stageGeneration: Long,
        private val rollingSample: Boolean,
    ) : Closeable {
        private val completed = AtomicBoolean(false)

        /** Legacy, proof-free completion. Rolling health mode treats it as ambiguous and freezes. */
        fun succeeded(encodedBytes: Long): Snapshot? {
            require(encodedBytes > 0L)
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.SUCCESS,
                encodedBytes,
                physicalProof = null,
                disqualificationReason = null,
            )
        }

        fun succeeded(physicalProof: PhysicalProof): Snapshot? {
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.SUCCESS,
                physicalProof.encodedBytes,
                physicalProof,
                disqualificationReason = null,
            )
        }

        fun failed(): Snapshot? {
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.TRANSPORT_FAILURE,
                0L,
                physicalProof = null,
                disqualificationReason = null,
            )
        }

        /**
         * Retires a cancellation or owner close without assigning it a throughput value. In the
         * rolling ramp this permanently stops further widening, because a missing proof must not
         * be silently replaced by a later fast body.
         */
        fun aborted(): Snapshot? {
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.ABORTED,
                0L,
                physicalProof = null,
                disqualificationReason = null,
            )
        }

        /** Classification/profile ambiguity is fail-closed at the established C6 baseline. */
        fun disqualified(reason: String): Snapshot? {
            require(reason.isNotBlank())
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.DISQUALIFIED,
                0L,
                physicalProof = null,
                disqualificationReason = reason,
            )
        }

        override fun close() {
            if (!completed.compareAndSet(false, true)) return
            completeLease(
                stageGeneration,
                rollingSample,
                LeaseOutcome.ABORTED,
                0L,
                physicalProof = null,
                disqualificationReason = null,
            )
        }
    }

    private enum class LeaseOutcome {
        SUCCESS,
        TRANSPORT_FAILURE,
        ABORTED,
        DISQUALIFIED,
    }

    private data class UnhealthyProof(
        val reason: String,
        val forceBaseLimit: Boolean,
    )

    private val ladder = limits.copyOf()
    private val lock = ReentrantLock(true)
    private val admissionChanged = lock.newCondition()
    private val seenPhysicalOperationIds = HashSet<Long>()
    private val healthyReplicaHosts = HashSet<String>()
    private var closed = false
    private var activeLeases = 0
    private var stageIndex = 0
    private var stageGeneration = 1L
    private var targetLimit: Int
    private var bestLimit: Int
    private var bestBytesPerSecond = 0.0
    private var stageStartedAtNanos = 0L
    private var stageCompletedAtNanos = 0L
    private var stageDurationNanos = 0L
    private var stageAdmissions = 0
    private var stageSampleAdmissions = 0
    private var stageWarmupFilled = false
    private var stageSuccesses = 0
    private var stageBytes = 0L
    private var retryAfterDrain = false
    private var retryDrainGeneration = 0L
    private var settled: Boolean
    private var frozen = false
    private var transitionReason = "initial_c6"
    private var finiteEligibleBodyCount = eligibleBodyCount
    private var finiteBodyCountKnown = finiteBodyCountKnownAtConstruction

    init {
        require(ladder.isNotEmpty())
        require(ladder.all { it > 0 })
        require(ladder.toList() == ladder.distinct().sorted())
        require(eligibleBodyCount >= 0)
        require(minimumImprovementRatio in 0.0..1.0)
        stageIndex = ladder.indexOf(BASE_HEALTH_LIMIT)
        require(stageIndex >= 0)
        if (healthGatedRollingRamp) {
            require(ladder.contains(MIDDLE_HEALTH_LIMIT))
            require(ladder.contains(MAXIMUM_HEALTH_LIMIT))
        }
        targetLimit = ladder[stageIndex]
        bestLimit = targetLimit
        settled = if (healthGatedRollingRamp) {
            !finiteBodyCountKnown || eligibleBodyCount <
                NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES
        } else {
            !probeWiderStages || eligibleBodyCount <
                NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES
        }
        if (healthGatedRollingRamp && settled) {
            transitionReason = if (finiteBodyCountKnown) {
                "finite_suffix_below_probe_minimum"
            } else {
                "awaiting_exact_finite_count"
            }
        }
    }

    fun tryAcquire(timeout: Long, unit: TimeUnit): Lease? {
        require(timeout >= 0L)
        var remainingNanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (!closed && if (healthGatedRollingRamp) {
                    activeLeases >= targetLimit
                } else {
                    retryAfterDrain || if (settled) {
                        activeLeases >= targetLimit
                    } else {
                        activeLeases >= targetLimit || stageAdmissions >= targetLimit
                    }
                }
            ) {
                if (remainingNanos <= 0L) return null
                remainingNanos = admissionChanged.awaitNanos(remainingNanos)
            }
            if (closed) return null
            activeLeases++
            if (healthGatedRollingRamp || !settled) stageAdmissions++
            val rollingSample = if (healthGatedRollingRamp) {
                when {
                    closed || settled || frozen || !finiteBodyCountKnown ||
                        finiteEligibleBodyCount <
                            NtkClickOwnedManhwaWavePolicy
                                .HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES -> false
                    targetLimit in BASE_HEALTH_LIMIT..MIDDLE_HEALTH_LIMIT &&
                        !stageWarmupFilled -> {
                        // The admission that first fills C6/C12 is itself warm-up. Only later
                        // admissions observe the entire target populated at admission time.
                        if (activeLeases >= targetLimit) stageWarmupFilled = true
                        false
                    }
                    targetLimit == BASE_HEALTH_LIMIT -> {
                        val sample = stageSampleAdmissions < BASE_HEALTH_PROOFS
                        if (sample) stageSampleAdmissions++
                        sample
                    }
                    targetLimit == MIDDLE_HEALTH_LIMIT -> {
                        val sample = stageSampleAdmissions < MIDDLE_HEALTH_PROOFS
                        if (sample) stageSampleAdmissions++
                        sample
                    }
                    else -> false
                }
            } else {
                false
            }
            if (!healthGatedRollingRamp && stageStartedAtNanos == 0L) {
                stageStartedAtNanos = clockNanos().coerceAtLeast(1L)
            }
            return Lease(stageGeneration, rollingSample)
        } finally {
            lock.unlock()
        }
    }

    fun snapshot(): Snapshot = lock.withLock { snapshotLocked() }

    /**
     * Maximum-bound plans publish the exact suffix before the viewport fence releases bulk work.
     * Rolling mode records every finite count, including counts large enough to ramp; a suffix
     * below 48 remains fixed at C6. Legacy mode retains its prior unstarted-small-wave behavior.
     */
    fun settleForFiniteBodyCount(eligibleBodyCount: Int): Snapshot? = lock.withLock {
        require(eligibleBodyCount >= 0)
        if (!healthGatedRollingRamp) {
            if (eligibleBodyCount >=
                    NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES ||
                closed || settled || activeLeases > 0 || stageAdmissions > 0
            ) return@withLock null
            stageIndex = ladder.indexOf(BASE_HEALTH_LIMIT)
            targetLimit = ladder[stageIndex]
            bestLimit = targetLimit
            settled = true
            stageGeneration++
            resetStageLocked()
            admissionChanged.signalAll()
            return@withLock snapshotLocked()
        }

        if (closed) return@withLock null
        if (finiteBodyCountKnown && finiteEligibleBodyCount == eligibleBodyCount) {
            return@withLock null
        }
        finiteEligibleBodyCount = eligibleBodyCount
        finiteBodyCountKnown = true
        if (!frozen) {
            if (eligibleBodyCount <
                NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES
            ) {
                targetLimit = BASE_HEALTH_LIMIT
                bestLimit = BASE_HEALTH_LIMIT
                stageIndex = ladder.indexOf(BASE_HEALTH_LIMIT)
                settled = true
                transitionReason = "finite_suffix_below_probe_minimum"
            } else {
                // Any provisional maximum-bound evidence is deliberately discarded here.
                targetLimit = BASE_HEALTH_LIMIT
                bestLimit = BASE_HEALTH_LIMIT
                stageIndex = ladder.indexOf(BASE_HEALTH_LIMIT)
                bestBytesPerSecond = 0.0
                settled = false
                transitionReason = "finite_suffix_health_ramp_armed"
            }
            stageGeneration++
            resetStageLocked()
        }
        admissionChanged.signalAll()
        snapshotLocked()
    }

    /** Freezes a profile/classification transition even when no adaptive lease was acquired yet. */
    fun freeze(reason: String): Snapshot? = lock.withLock {
        require(reason.isNotBlank())
        if (!healthGatedRollingRamp || closed || frozen) return@withLock null
        val changed = freezeHealthLocked(reason, forceBaseLimit = true)
        admissionChanged.signalAll()
        changed
    }

    /** Outcome+release is atomic, so a waiter can never refill an obsolete wider target. */
    private fun completeLease(
        leaseStageGeneration: Long,
        rollingSample: Boolean,
        outcome: LeaseOutcome,
        encodedBytes: Long,
        physicalProof: PhysicalProof?,
        disqualificationReason: String?,
    ): Snapshot? = lock.withLock {
        check(activeLeases > 0) { "Adaptive manhwa bulk lease underflow" }
        activeLeases--

        val changed = if (healthGatedRollingRamp) {
            completeRollingHealthLeaseLocked(
                leaseStageGeneration,
                rollingSample,
                outcome,
                encodedBytes,
                physicalProof,
                disqualificationReason,
            )
        } else {
            completeLegacyLeaseLocked(leaseStageGeneration, outcome, encodedBytes)
        }
        admissionChanged.signalAll()
        changed
    }

    private fun completeRollingHealthLeaseLocked(
        leaseStageGeneration: Long,
        rollingSample: Boolean,
        outcome: LeaseOutcome,
        encodedBytes: Long,
        physicalProof: PhysicalProof?,
        disqualificationReason: String?,
    ): Snapshot? {
        if (closed) return null
        return when (outcome) {
            LeaseOutcome.TRANSPORT_FAILURE ->
                freezeHealthLocked("transport_failure", forceBaseLimit = false)
            LeaseOutcome.ABORTED ->
                freezeHealthLocked("ambiguous_abort", forceBaseLimit = false)
            LeaseOutcome.DISQUALIFIED ->
                freezeHealthLocked(
                    disqualificationReason ?: "classification_ambiguity",
                    forceBaseLimit = true,
                )
            LeaseOutcome.SUCCESS -> {
                val proof = physicalProof ?: return freezeHealthLocked(
                    "missing_physical_eof_proof",
                    forceBaseLimit = true,
                )
                check(encodedBytes == proof.encodedBytes)
                val unhealthy = unhealthyPhysicalProof(proof)
                if (unhealthy != null) {
                    return freezeHealthLocked(unhealthy.reason, unhealthy.forceBaseLimit)
                }
                if (!seenPhysicalOperationIds.add(proof.operationId)) {
                    return freezeHealthLocked("duplicate_physical_proof", true)
                }
                if (!rollingSample) return null
                if (frozen || !finiteBodyCountKnown || finiteEligibleBodyCount <
                    NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES
                ) return null
                if (leaseStageGeneration != stageGeneration) return null

                stageSuccesses++
                stageBytes += proof.encodedBytes
                stageDurationNanos += maxOf(
                    1L,
                    proof.evidence.proofReadyAtNanos -
                        proof.evidence.physicalStartedAtNanos,
                )
                healthyReplicaHosts += proof.expectedResponseHost.lowercase(Locale.ROOT)
                maybeAdvanceRollingHealthStageLocked()
            }
        }
    }

    private fun unhealthyPhysicalProof(proof: PhysicalProof): UnhealthyProof? {
        val expectedHost = proof.expectedResponseHost.lowercase(Locale.ROOT)
        val responseHost = proof.evidence.responseHost.lowercase(Locale.ROOT)
        val elapsedNanos = proof.evidence.proofReadyAtNanos -
            proof.evidence.physicalStartedAtNanos
        return when {
            !proof.capturedProfileLive -> UnhealthyProof("profile_changed", true)
            !proof.ordinaryClassificationLive ->
                UnhealthyProof("ordinary_classification_changed", true)
            !proof.exactOrdinaryJpeg -> UnhealthyProof("mixed_exact_format", true)
            proof.evidence.physicalAttemptOrdinal != 0 ->
                UnhealthyProof("physical_failover", true)
            proof.evidence.usedRangeContinuation ->
                UnhealthyProof("range_continuation", true)
            !proof.evidence.protocol.equals(REQUIRED_PROTOCOL, ignoreCase = true) ->
                UnhealthyProof("non_h1_success", true)
            !NtkClickOwnedManhwaWavePolicy.isReplicaHost(expectedHost) ->
                UnhealthyProof("unplanned_replica_host", true)
            responseHost != expectedHost -> UnhealthyProof("alternate_host_success", true)
            elapsedNanos > TimeUnit.MILLISECONDS.toNanos(FAST_PROOF_LIMIT_MS) ->
                UnhealthyProof("slow_success", false)
            else -> null
        }
    }

    private fun maybeAdvanceRollingHealthStageLocked(): Snapshot? {
        val requiredProofs = when (targetLimit) {
            BASE_HEALTH_LIMIT -> BASE_HEALTH_PROOFS
            MIDDLE_HEALTH_LIMIT -> MIDDLE_HEALTH_PROOFS
            else -> return null
        }
        if (stageSuccesses < requiredProofs ||
            healthyReplicaHosts.size < REQUIRED_REPLICA_HOSTS
        ) return null

        val bytesPerSecond = targetLimit.toDouble() * stageBytes.toDouble() *
            1_000_000_000.0 / stageDurationNanos.coerceAtLeast(1L).toDouble()
        if (targetLimit == BASE_HEALTH_LIMIT) {
            bestBytesPerSecond = bytesPerSecond
            bestLimit = BASE_HEALTH_LIMIT
            targetLimit = MIDDLE_HEALTH_LIMIT
            stageIndex = ladder.indexOf(targetLimit)
            settled = false
            transitionReason = "healthy_c6_to_c12"
            stageGeneration++
            resetStageLocked()
            admissionChanged.signalAll()
            return snapshotLocked()
        }

        val materiallyImproved = bestBytesPerSecond > 0.0 &&
            bytesPerSecond >= bestBytesPerSecond * (1.0 + minimumImprovementRatio)
        if (!materiallyImproved) {
            return freezeHealthLocked(
                "c12_throughput_not_improved",
                forceBaseLimit = false,
            )
        }
        bestBytesPerSecond = bytesPerSecond
        bestLimit = MIDDLE_HEALTH_LIMIT
        targetLimit = MAXIMUM_HEALTH_LIMIT
        stageIndex = ladder.indexOf(targetLimit)
        settled = true
        transitionReason = "healthy_c12_to_c24"
        stageGeneration++
        resetStageLocked()
        admissionChanged.signalAll()
        return snapshotLocked()
    }

    private fun freezeHealthLocked(reason: String, forceBaseLimit: Boolean): Snapshot {
        if (!frozen) {
            val fallbackLimit = if (forceBaseLimit) BASE_HEALTH_LIMIT else bestLimit
            targetLimit = fallbackLimit.coerceAtMost(targetLimit)
            if (forceBaseLimit) bestLimit = BASE_HEALTH_LIMIT
            stageIndex = ladder.indexOf(targetLimit)
            settled = true
            frozen = true
            transitionReason = reason
            stageGeneration++
            resetStageLocked()
        }
        return snapshotLocked()
    }

    private fun completeLegacyLeaseLocked(
        leaseStageGeneration: Long,
        outcome: LeaseOutcome,
        encodedBytes: Long,
    ): Snapshot? {
        var changed: Snapshot? = null
        when (outcome) {
            LeaseOutcome.TRANSPORT_FAILURE -> {
                val belongsToCurrentProbe = !retryAfterDrain &&
                    leaseStageGeneration == stageGeneration
                val belongsToDrainingProbe = retryAfterDrain &&
                    leaseStageGeneration == retryDrainGeneration
                if (!closed && (belongsToCurrentProbe || belongsToDrainingProbe)) {
                    changed = settleAfterTransportFailureLocked()
                }
            }
            LeaseOutcome.SUCCESS -> {
                if (!closed && !settled && !retryAfterDrain &&
                    leaseStageGeneration == stageGeneration
                ) {
                    stageSuccesses++
                    stageBytes += encodedBytes
                    stageCompletedAtNanos =
                        clockNanos().coerceAtLeast(stageStartedAtNanos + 1L)
                }
            }
            LeaseOutcome.ABORTED,
            LeaseOutcome.DISQUALIFIED -> {
                if (!closed && !settled && !retryAfterDrain &&
                    leaseStageGeneration == stageGeneration
                ) {
                    retryAfterDrain = true
                    retryDrainGeneration = stageGeneration
                    stageGeneration++
                    changed = snapshotLocked()
                }
            }
        }

        changed = maybeCompleteLegacyStageLocked() ?: changed
        finishRetryDrainLocked()
        return changed
    }

    private fun settleAfterTransportFailureLocked(): Snapshot {
        val failedStageIndex = ladder.indexOf(targetLimit)
        check(failedStageIndex >= 0)
        stageIndex = (failedStageIndex - 1).coerceAtLeast(0)
        targetLimit = ladder[stageIndex]
        bestLimit = targetLimit
        settled = true
        retryAfterDrain = false
        retryDrainGeneration = 0L
        stageGeneration++
        resetStageLocked()
        return snapshotLocked()
    }

    private fun finishRetryDrainLocked() {
        if (!retryAfterDrain || activeLeases > 0) return
        retryAfterDrain = false
        retryDrainGeneration = 0L
        resetStageLocked()
        admissionChanged.signalAll()
    }

    private fun maybeCompleteLegacyStageLocked(): Snapshot? {
        if (closed || settled ||
            stageAdmissions < targetLimit ||
            stageSuccesses < targetLimit ||
            activeLeases > 0
        ) return null

        val elapsedNanos = (stageCompletedAtNanos - stageStartedAtNanos).coerceAtLeast(1L)
        val bytesPerSecond = stageBytes.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()
        val materiallyImproved = bestBytesPerSecond == 0.0 ||
            bytesPerSecond >= bestBytesPerSecond * (1.0 + minimumImprovementRatio)
        if (materiallyImproved) {
            bestBytesPerSecond = bytesPerSecond
            bestLimit = targetLimit
            if (stageIndex + 1 < ladder.size) {
                stageIndex++
                targetLimit = ladder[stageIndex]
                stageGeneration++
                resetStageLocked()
            } else {
                settled = true
            }
        } else {
            targetLimit = bestLimit
            stageIndex = ladder.indexOf(bestLimit)
            settled = true
            stageGeneration++
            resetStageLocked()
        }
        admissionChanged.signalAll()
        return snapshotLocked()
    }

    private fun resetStageLocked() {
        stageStartedAtNanos = 0L
        stageCompletedAtNanos = 0L
        stageDurationNanos = 0L
        stageAdmissions = 0
        stageSampleAdmissions = 0
        stageWarmupFilled = false
        stageSuccesses = 0
        stageBytes = 0L
        healthyReplicaHosts.clear()
    }

    private fun snapshotLocked(): Snapshot = Snapshot(
        targetLimit = targetLimit,
        bestLimit = bestLimit,
        activeLeases = activeLeases,
        stageIndex = stageIndex,
        stageAdmissions = stageAdmissions,
        stageSuccesses = stageSuccesses,
        stageBytes = stageBytes,
        retryAfterDrain = retryAfterDrain,
        settled = settled,
        closed = closed,
        healthGatedRollingRamp = healthGatedRollingRamp,
        frozen = frozen,
        transitionReason = transitionReason,
        healthyReplicaHostCount = healthyReplicaHosts.size,
        eligibleBodyCount = finiteEligibleBodyCount,
        finiteBodyCountKnown = finiteBodyCountKnown,
    )

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            admissionChanged.signalAll()
        }
    }

    companion object {
        const val BASE_HEALTH_LIMIT = 6
        const val MIDDLE_HEALTH_LIMIT = 12
        const val MAXIMUM_HEALTH_LIMIT = 24
        const val BASE_HEALTH_PROOFS = 6
        const val MIDDLE_HEALTH_PROOFS = 12
        const val REQUIRED_REPLICA_HOSTS = 3
        const val FAST_PROOF_LIMIT_MS = 3_000L
        const val REQUIRED_PROTOCOL = "http/1.1"
    }
}
