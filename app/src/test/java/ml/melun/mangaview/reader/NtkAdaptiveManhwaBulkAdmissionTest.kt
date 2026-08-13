package ml.melun.mangaview.reader

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdaptiveManhwaBulkAdmissionTest {
    private class FakeClock {
        val now = AtomicLong(1L)
        fun advanceMillis(value: Long) {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(value))
        }
    }

    @Test
    fun measuredLaxShapeStartsAtSixAndRejectsEight() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)

        // Same 17,738,403 original bytes measured at C6=16.796 s and C8=18.692 s. C6 is
        // the contemporaneous safe seed; wider stages still require a measured 15% improvement.
        val exactBytes = 17_738_403L
        completeStage(
            gate,
            clock,
            count = 6,
            bytesEach = exactBytes / 6L,
            elapsedMs = 16_796L,
        )
        assertEquals(8, gate.snapshot().targetLimit)
        val eight = acquire(gate, 8)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        clock.advanceMillis(18_692L)
        eight.forEach { it.succeeded(exactBytes / 8L) }

        val settled = gate.snapshot()
        assertTrue(settled.settled)
        assertEquals(6, settled.targetLimit)
        assertEquals(6, settled.bestLimit)
        assertEquals(1, settled.stageIndex)
        assertEquals(0, settled.activeLeases)
        assertEquals(6, acquire(gate, 6).size)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
    }

    @Test
    fun fastCohortCanRecoverTheHistoricalTwentyFourCallCeiling() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        listOf(6, 8, 12, 24).forEachIndexed { index, count ->
            completeStage(
                gate,
                clock,
                count = count,
                bytesEach = 1_000_000L,
                elapsedMs = if (index == 0) 4_000L else 2_000L,
            )
        }
        val snapshot = gate.snapshot()
        assertTrue(snapshot.settled)
        assertEquals(24, snapshot.targetLimit)
        assertEquals(24, snapshot.bestLimit)
    }

    @Test
    fun failureDownshiftsNewAdmissionWithoutCancellingExistingLeases() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        val active = acquire(gate, 6)
        active.first().failed()

        val downshifted = gate.snapshot()
        assertTrue(downshifted.settled)
        assertEquals(4, downshifted.targetLimit)
        assertEquals(5, downshifted.activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        assertNull(active[1].failed())
        assertEquals(4, gate.snapshot().targetLimit)
        active.forEach { it.close() }
        assertEquals(4, acquire(gate, 4).size)
    }

    @Test
    fun failureAfterSettlingAtEightMovesBackToSix() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        completeStage(gate, clock, count = 6, bytesEach = 1_000_000L, elapsedMs = 4_000L)
        completeStage(gate, clock, count = 8, bytesEach = 1_000_000L, elapsedMs = 2_000L)
        completeStage(gate, clock, count = 12, bytesEach = 100_000L, elapsedMs = 2_000L)
        assertTrue(gate.snapshot().settled)
        assertEquals(8, gate.snapshot().targetLimit)

        val active = acquire(gate, 8)
        active.first().failed()
        assertEquals(6, gate.snapshot().targetLimit)
        assertEquals(7, gate.snapshot().activeLeases)
        active.drop(1).forEach { it.close() }
        assertEquals(6, acquire(gate, 6).size)
    }

    @Test
    fun failureAfterSettlingAtTwentyFourMovesBackToTwelve() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        listOf(6, 8, 12, 24).forEachIndexed { index, count ->
            completeStage(
                gate,
                clock,
                count = count,
                bytesEach = 1_000_000L,
                elapsedMs = if (index == 0) 4_000L else 2_000L,
            )
        }
        assertTrue(gate.snapshot().settled)
        assertEquals(24, gate.snapshot().targetLimit)

        val active = acquire(gate, 24)
        active.first().failed()
        assertEquals(12, gate.snapshot().targetLimit)
        assertEquals(23, gate.snapshot().activeLeases)
        active.drop(1).forEach { it.close() }
        assertEquals(12, acquire(gate, 12).size)
    }

    @Test
    fun probingStageDoesNotRefillOrOverlapTheNextLimit() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        val first = acquire(gate, 6)
        first.first().succeeded(500_000L)

        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        assertEquals(6, gate.snapshot().stageAdmissions)
        assertEquals(5, gate.snapshot().activeLeases)

        clock.advanceMillis(2_000L)
        first.drop(1).forEach { it.succeeded(500_000L) }
        assertEquals(8, gate.snapshot().targetLimit)
        assertEquals(0, gate.snapshot().activeLeases)
        assertEquals(8, acquire(gate, 8).size)
    }

    @Test
    fun cancellationDrainsAndRestartsTheProbeWithAFreshGeneration() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        val first = acquire(gate, 6)
        clock.advanceMillis(2_000L)
        first.first().aborted()
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        assertTrue(gate.snapshot().retryAfterDrain)
        assertFalse(gate.snapshot().settled)
        first.drop(1).forEach {
            it.succeeded(500_000L)
        }
        assertFalse(gate.snapshot().retryAfterDrain)
        assertEquals(6, acquire(gate, 6).size)
    }

    @Test
    fun delayedAbortTimeIsNotCarriedIntoTheReplacementCohort() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        val invalid = acquire(gate, 6)
        clock.advanceMillis(2_000L)
        invalid.first().aborted()
        invalid.drop(1).forEach { it.close() }

        completeStage(gate, clock, count = 6, bytesEach = 1_000_000L, elapsedMs = 2_000L)
        assertEquals(8, gate.snapshot().targetLimit)
        completeStage(gate, clock, count = 8, bytesEach = 825_000L, elapsedMs = 2_000L)
        assertTrue(gate.snapshot().settled)
        assertEquals(6, gate.snapshot().targetLimit)
    }

    @Test
    fun closeWithoutOutcomeIsAnAtomicAbortAndRestartsAfterDrain() {
        val clock = FakeClock()
        val gate = NtkAdaptiveManhwaBulkAdmission(clockNanos = clock.now::get)
        val first = acquire(gate, 6)
        clock.advanceMillis(2_000L)
        first.first().close()
        assertTrue(gate.snapshot().retryAfterDrain)
        first.drop(1).forEach { it.succeeded(500_000L) }
        assertEquals(6, gate.snapshot().targetLimit)
        assertFalse(gate.snapshot().retryAfterDrain)
        assertEquals(6, acquire(gate, 6).size)
    }

    @Test
    fun physicalFailureWinsAfterAnotherLeaseAbortedTheSameCohort() {
        val gate = NtkAdaptiveManhwaBulkAdmission()
        val active = acquire(gate, 6)
        active[0].aborted()
        assertTrue(gate.snapshot().retryAfterDrain)

        active[1].failed()
        val failed = gate.snapshot()
        assertTrue(failed.settled)
        assertFalse(failed.retryAfterDrain)
        assertEquals(4, failed.targetLimit)
        assertEquals(4, failed.activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))

        active.drop(2).forEach { it.close() }
        assertEquals(4, acquire(gate, 4).size)
    }

    @Test
    fun finiteChapterUsesMeasuredSixWithoutSpendingItsBodiesOnUpwardProbes() {
        val gate = NtkAdaptiveManhwaBulkAdmission(eligibleBodyCount = 27)
        assertTrue(gate.snapshot().settled)
        assertEquals(6, gate.snapshot().targetLimit)
        val active = acquire(gate, 6)
        active.first().close()
        assertEquals(1, acquire(gate, 1).size)
    }

    @Test
    fun exactFiniteCountCanStopAnUnstartedMaximumBoundProbe() {
        val gate = NtkAdaptiveManhwaBulkAdmission()
        val constrained = checkNotNull(gate.settleForFiniteBodyCount(27))
        assertTrue(constrained.settled)
        assertEquals(6, constrained.targetLimit)

        val busyGate = NtkAdaptiveManhwaBulkAdmission()
        val active = acquire(busyGate, 6)
        assertNull(busyGate.settleForFiniteBodyCount(27))
        assertFalse(busyGate.snapshot().settled)
        active.forEach { it.close() }
    }

    @Test
    fun closeRejectsNewAdmissionAndExistingLeaseReleaseRemainsSafe() {
        val gate = NtkAdaptiveManhwaBulkAdmission()
        val lease = checkNotNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        gate.close()
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        lease.close()
        lease.close()
        assertTrue(gate.snapshot().closed)
        assertEquals(0, gate.snapshot().activeLeases)
        assertFalse(gate.snapshot().targetLimit >
            NtkClickOwnedManhwaWavePolicy.HOST_GPU_CURRENT_RESTORED_BULK_BODY_TRANSFERS)
    }

    private fun completeStage(
        gate: NtkAdaptiveManhwaBulkAdmission,
        clock: FakeClock,
        count: Int,
        bytesEach: Long,
        elapsedMs: Long,
    ) {
        val leases = acquire(gate, count)
        clock.advanceMillis(elapsedMs)
        leases.forEach { lease ->
            lease.succeeded(bytesEach)
        }
    }

    private fun acquire(
        gate: NtkAdaptiveManhwaBulkAdmission,
        count: Int,
    ): List<NtkAdaptiveManhwaBulkAdmission.Lease> =
        List(count) {
            checkNotNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        }
}
