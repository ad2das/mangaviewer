package ml.melun.mangaview.reader

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Session-local admission gate for the offscreen part of a resumed manhwa.
 *
 * The gate starts conservatively, measures byte-weighted wall throughput, and probes each larger
 * limit once. A probe must materially beat the best completed stage or the session settles at the
 * previous best. Lowering the target never cancels a physical request that already owns a lease.
 */
internal class NtkAdaptiveManhwaBulkAdmission(
    limits: IntArray = NtkClickOwnedManhwaWavePolicy.hostGpuCurrentBulkTransferLadder(),
    eligibleBodyCount: Int = Int.MAX_VALUE,
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
    )

    inner class Lease internal constructor(
        private val stageGeneration: Long,
    ) : Closeable {
        private val completed = AtomicBoolean(false)

        fun succeeded(encodedBytes: Long): Snapshot? {
            require(encodedBytes > 0L)
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(stageGeneration, LeaseOutcome.SUCCESS, encodedBytes)
        }

        fun failed(): Snapshot? {
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(stageGeneration, LeaseOutcome.TRANSPORT_FAILURE, 0L)
        }

        /**
         * Retires an admission that never produced a comparable transport result. Cancellation,
         * an exact-candidate miss, or a Wi-Fi profile transition must not teach the controller
         * that the current concurrency is slow. The probing cohort may replace this slot.
         */
        fun aborted(): Snapshot? {
            if (!completed.compareAndSet(false, true)) return null
            return completeLease(stageGeneration, LeaseOutcome.ABORTED, 0L)
        }

        override fun close() {
            if (!completed.compareAndSet(false, true)) return
            completeLease(stageGeneration, LeaseOutcome.ABORTED, 0L)
        }
    }

    private enum class LeaseOutcome {
        SUCCESS,
        TRANSPORT_FAILURE,
        ABORTED,
    }

    private val ladder = limits.copyOf()
    private val lock = ReentrantLock(true)
    private val admissionChanged = lock.newCondition()
    private var closed = false
    private var activeLeases = 0
    private var stageIndex = 0
    private var stageGeneration = 1L
    private var targetLimit: Int
    private var bestLimit: Int
    private var bestBytesPerSecond = 0.0
    private var stageStartedAtNanos = 0L
    private var stageCompletedAtNanos = 0L
    private var stageAdmissions = 0
    private var stageSuccesses = 0
    private var stageBytes = 0L
    private var retryAfterDrain = false
    private var retryDrainGeneration = 0L
    private var settled: Boolean

    init {
        require(ladder.isNotEmpty())
        require(ladder.all { it > 0 })
        require(ladder.toList() == ladder.distinct().sorted())
        require(eligibleBodyCount >= 0)
        require(minimumImprovementRatio in 0.0..1.0)
        stageIndex = ladder.indexOf(
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_INITIAL_TRANSFERS,
        )
        require(stageIndex >= 0)
        targetLimit = ladder[stageIndex]
        bestLimit = targetLimit
        settled = eligibleBodyCount <
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES
    }

    fun tryAcquire(timeout: Long, unit: TimeUnit): Lease? {
        require(timeout >= 0L)
        var remainingNanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (!closed && (retryAfterDrain || if (settled) {
                    activeLeases >= targetLimit
                } else {
                    activeLeases >= targetLimit || stageAdmissions >= targetLimit
                }
            )) {
                if (remainingNanos <= 0L) return null
                remainingNanos = admissionChanged.awaitNanos(remainingNanos)
            }
            if (closed) return null
            activeLeases++
            if (!settled) stageAdmissions++
            if (stageStartedAtNanos == 0L) {
                stageStartedAtNanos = clockNanos().coerceAtLeast(1L)
            }
            return Lease(stageGeneration)
        } finally {
            lock.unlock()
        }
    }

    fun snapshot(): Snapshot = lock.withLock { snapshotLocked() }

    /**
     * Maximum-bound manhwa plans learn their exact page count shortly before bulk release. Avoid
     * spending a finite suffix on an upward benchmark when fewer bodies remain than can benefit.
     * This never changes or cancels an admission that has already started.
     */
    fun settleForFiniteBodyCount(eligibleBodyCount: Int): Snapshot? = lock.withLock {
        require(eligibleBodyCount >= 0)
        if (eligibleBodyCount >=
                NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_UPWARD_PROBE_MIN_BODIES ||
            closed || settled || activeLeases > 0 || stageAdmissions > 0
        ) return@withLock null
        stageIndex = ladder.indexOf(
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_BULK_INITIAL_TRANSFERS,
        )
        targetLimit = ladder[stageIndex]
        bestLimit = targetLimit
        settled = true
        stageGeneration++
        resetStageLocked()
        admissionChanged.signalAll()
        snapshotLocked()
    }

    /**
     * Records the physical outcome and releases its slot under one lock transition. A failed body
     * therefore lowers the target before a waiter can refill the old wider limit. This is also the
     * only release path for an adaptive lease; [Lease.close] is an explicit non-comparable abort.
     */
    private fun completeLease(
        leaseStageGeneration: Long,
        outcome: LeaseOutcome,
        encodedBytes: Long,
    ): Snapshot? = lock.withLock {
        check(activeLeases > 0) { "Adaptive manhwa bulk lease underflow" }
        activeLeases--

        var changed: Snapshot? = null
        when (outcome) {
            LeaseOutcome.TRANSPORT_FAILURE -> {
                // A real socket/DNS/timeout failure wins over an earlier non-comparable abort from
                // the same cohort. Existing Calls keep running; only future admission is lowered.
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
                    // A probing stage is one exact, non-overlapping cohort. A freed C6 slot cannot
                    // refill, and C8 cannot begin until all C6 bodies have terminal outcomes.
                    stageSuccesses++
                    stageBytes += encodedBytes
                    stageCompletedAtNanos =
                        clockNanos().coerceAtLeast(stageStartedAtNanos + 1L)
                }
            }
            LeaseOutcome.ABORTED -> {
                if (!closed && !settled && !retryAfterDrain &&
                    leaseStageGeneration == stageGeneration
                ) {
                    // A non-comparable outcome invalidates the entire clock window. Existing Calls
                    // drain naturally; the same limit receives a clean generation afterwards.
                    retryAfterDrain = true
                    retryDrainGeneration = stageGeneration
                    stageGeneration++
                    changed = snapshotLocked()
                }
            }
        }

        changed = maybeCompleteStageLocked() ?: changed
        finishRetryDrainLocked()
        admissionChanged.signalAll()
        changed
    }

    private fun settleAfterTransportFailureLocked(): Snapshot {
        // A failure is evidence against the physical concurrency that just failed, including a
        // previously successful wider stage. Move exactly one measured rung down (C24 -> C12,
        // C8 -> C6, C6 -> C4) without cancelling Calls that already own the wider-stage lease.
        // Selecting bestLimit here would be a no-op after a wide stage had already settled.
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

    private fun maybeCompleteStageLocked(): Snapshot? {
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
        stageAdmissions = 0
        stageSuccesses = 0
        stageBytes = 0L
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
    )

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            admissionChanged.signalAll()
        }
    }
}
