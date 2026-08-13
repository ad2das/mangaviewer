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
    fun productionRollingModeKeepsSixSlotsWorkConservingWithoutProbeBarriers() {
        val gate = NtkAdaptiveManhwaBulkAdmission(
            eligibleBodyCount = 96,
            probeWiderStages = false,
        )
        assertTrue(gate.snapshot().settled)
        assertEquals(6, gate.snapshot().targetLimit)

        val active = acquire(gate, 6)
        active.first().succeeded(500_000L)

        // A completed body immediately admits its replacement instead of leaving the slot idle
        // while a synthetic six-body measurement cohort drains.
        val replacement = acquire(gate, 1).single()
        assertEquals(6, gate.snapshot().activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        active.drop(1).forEach { it.close() }
        replacement.close()
        assertEquals(0, gate.snapshot().activeLeases)
        gate.close()
    }

    @Test
    fun initialC6FillIsWarmupThenFixedSampleRefillsWithoutDrain() {
        val gate = rollingHealthGate()
        val warmup = acquire(gate, 6)
        val samples = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        warmup.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 100L + index,
                    pageIndex = index,
                    startMs = 1_000L,
                    elapsedMs = 2_000L,
                ),
            )
            samples += acquire(gate, 1).single()
            assertEquals(6, gate.snapshot().activeLeases)
            assertEquals(0, gate.snapshot().stageSuccesses)
        }

        val postSample = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        repeat(5) { index ->
            samples[index].succeeded(
                healthProof(
                    operationId = (index + 1).toLong(),
                    pageIndex = index,
                    startMs = 4_000L,
                    elapsedMs = 2_000L,
                ),
            )
            postSample += acquire(gate, 1).single()
            assertEquals(index + 1, gate.snapshot().stageSuccesses)
            assertEquals(6, gate.snapshot().activeLeases)
        }

        val widened = checkNotNull(samples.last().succeeded(
            healthProof(
                operationId = 6L,
                pageIndex = 5,
                startMs = 4_000L,
                elapsedMs = 2_000L,
            ),
        ))
        assertEquals(12, widened.targetLimit)
        assertEquals("healthy_c6_to_c12", widened.transitionReason)
        assertFalse(widened.frozen)
        assertEquals(5, widened.activeLeases)

        val expanded = acquire(gate, 7)
        assertEquals(12, gate.snapshot().activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        gate.close()
        postSample.forEach { it.close() }
        expanded.forEach { it.close() }
    }

    @Test
    fun c12WarmupInterleavesOldC6AndRefillsFixedSamplesWithoutStuck() {
        val gate = rollingHealthGate()
        val initialC6 = acquire(gate, 6)
        val c6Samples = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        initialC6.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 1_000L + index,
                    pageIndex = index,
                    startMs = 1_000L,
                    elapsedMs = 2_000L,
                ),
            )
            c6Samples += acquire(gate, 1).single()
        }

        val oldC6 = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        repeat(5) { index ->
            c6Samples[index].succeeded(
                healthProof(
                    operationId = 1_100L + index,
                    pageIndex = index,
                    startMs = 4_000L,
                    elapsedMs = 2_000L,
                ),
            )
            oldC6 += acquire(gate, 1).single()
        }
        val widened = checkNotNull(c6Samples.last().succeeded(
            healthProof(
                operationId = 1_105L,
                pageIndex = 5,
                startMs = 4_000L,
                elapsedMs = 2_000L,
            ),
        ))
        assertEquals(12, widened.targetLimit)
        assertEquals(5, widened.activeLeases)

        val newC12 = acquire(gate, 7)
        assertEquals(12, gate.snapshot().activeLeases)
        val interleavedWarmup = buildList {
            repeat(5) { index ->
                add(newC12[index])
                add(oldC6[index])
            }
            add(newC12[5])
            add(newC12[6])
        }
        val c12Samples = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        interleavedWarmup.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 2_000L + index,
                    pageIndex = 20 + index,
                    startMs = 7_000L,
                    elapsedMs = 2_000L,
                ),
            )
            c12Samples += acquire(gate, 1).single()
            assertEquals(12, gate.snapshot().activeLeases)
            assertEquals(0, gate.snapshot().stageSuccesses)
            assertFalse(gate.snapshot().frozen)
        }

        c12Samples.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 3_000L + index,
                    pageIndex = 40 + index,
                    startMs = 10_000L,
                    elapsedMs = 1_000L,
                    host = REPLICA_HOSTS[index % REPLICA_HOSTS.size],
                ),
            )
        }
        val promoted = gate.snapshot()
        assertEquals(24, promoted.targetLimit)
        assertEquals("healthy_c12_to_c24", promoted.transitionReason)
        assertFalse(promoted.frozen)
    }

    @Test
    fun twelveFreshFasterC12ProofsOpenC24() {
        val gate = rollingHealthGate()
        completeHealthStage(
            gate,
            count = 6,
            operationBase = 1L,
            startMs = 1_000L,
            elapsedMs = 2_000L,
        )
        assertEquals(12, gate.snapshot().targetLimit)

        completeHealthStage(
            gate,
            count = 12,
            operationBase = 100L,
            startMs = 4_000L,
            elapsedMs = 2_000L,
        )
        val widened = gate.snapshot()
        assertEquals(24, widened.targetLimit)
        assertEquals(12, widened.bestLimit)
        assertEquals("healthy_c12_to_c24", widened.transitionReason)
        assertTrue(widened.settled)
        assertFalse(widened.frozen)
        assertEquals(24, acquire(gate, 24).size)
    }

    @Test
    fun C12WithoutFifteenPercentThroughputGainFreezesBackAtC6() {
        val gate = rollingHealthGate()
        completeHealthStage(
            gate,
            count = 6,
            operationBase = 1L,
            startMs = 1_000L,
            elapsedMs = 500L,
        )
        completeHealthStage(
            gate,
            count = 12,
            operationBase = 100L,
            startMs = 4_000L,
            // Each body remains healthy, but aggregate C12 throughput is below the fast C6 base.
            elapsedMs = 2_000L,
        )

        val frozen = gate.snapshot()
        assertTrue(frozen.frozen)
        assertEquals(6, frozen.targetLimit)
        assertEquals("c12_throughput_not_improved", frozen.transitionReason)
    }

    @Test
    fun fasterWinnerCompletionsCannotReplaceTheFixedSlowC12Sample() {
        val gate = rollingHealthGate()
        completeHealthStage(gate, 6, 1L, 1_000L, 500L)
        assertEquals(12, gate.snapshot().targetLimit)

        val fixedSamples = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        acquire(gate, 12).forEachIndexed { index, warmup ->
            warmup.succeeded(
                healthProof(
                    operationId = 10_000L + index,
                    pageIndex = index,
                    startMs = 2_000L,
                    elapsedMs = 500L,
                ),
            )
            fixedSamples += acquire(gate, 1).single()
        }
        assertEquals(0, gate.snapshot().stageSuccesses)

        val nonSampleHolders = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        repeat(11) { index ->
            fixedSamples[index].succeeded(
                healthProof(
                    operationId = 20_000L + index,
                    pageIndex = 20 + index,
                    startMs = 3_000L,
                    elapsedMs = 1_000L,
                ),
            )
            assertEquals(index + 1, gate.snapshot().stageSuccesses)

            // A later, much faster completion is healthy but was admitted after the fixed twelve.
            // It must not replace the still-running slow sample in either numerator or duration.
            val fastWinner = acquire(gate, 1).single()
            fastWinner.succeeded(
                healthProof(
                    operationId = 30_000L + index,
                    pageIndex = 40 + index,
                    startMs = 4_100L,
                    elapsedMs = 100L,
                ),
            )
            nonSampleHolders += acquire(gate, 1).single()
            assertEquals(index + 1, gate.snapshot().stageSuccesses)
            assertEquals(12, gate.snapshot().activeLeases)
        }

        val rejected = checkNotNull(fixedSamples.last().succeeded(
            healthProof(
                operationId = 20_011L,
                pageIndex = 31,
                startMs = 3_000L,
                elapsedMs = 2_900L,
            ),
        ))
        assertTrue(rejected.frozen)
        assertEquals(6, rejected.targetLimit)
        assertEquals("c12_throughput_not_improved", rejected.transitionReason)
        assertEquals(11, rejected.activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        nonSampleHolders.forEach { it.close() }
    }

    @Test
    fun unhealthyPhysicalEvidenceFreezesAtC6WithoutPerformanceLearning() {
        val cases = listOf(
            healthProof(1L, 0, protocol = "h2") to "non_h1_success",
            healthProof(2L, 1, physicalAttemptOrdinal = 1) to "physical_failover",
            healthProof(3L, 2, usedRangeContinuation = true) to "range_continuation",
            healthProof(
                4L,
                3,
                expectedHost = REPLICA_HOSTS[0],
                responseHost = REPLICA_HOSTS[1],
            ) to "alternate_host_success",
            healthProof(5L, 4, elapsedMs = 3_001L) to "slow_success",
            healthProof(6L, 5, capturedProfileLive = false) to "profile_changed",
            healthProof(7L, 6, ordinaryClassificationLive = false) to
                "ordinary_classification_changed",
            healthProof(8L, 7, exactOrdinaryJpeg = false) to "mixed_exact_format",
        )

        cases.forEach { (proof, expectedReason) ->
            val gate = rollingHealthGate()
            val lease = acquire(gate, 1).single()
            val transition = checkNotNull(lease.succeeded(proof))
            assertTrue(expectedReason, transition.frozen)
            assertEquals(expectedReason, transition.transitionReason)
            assertEquals(6, transition.targetLimit)
        }
    }

    @Test
    fun nonSampleWarmupTransportAndMissingProofFailClosed() {
        val transportGate = rollingHealthGate()
        val transportWarmup = acquire(transportGate, 6)
        val transport = checkNotNull(transportWarmup.first().failed())
        assertTrue(transport.frozen)
        assertEquals("transport_failure", transport.transitionReason)
        assertEquals(5, transport.activeLeases)
        transportWarmup.drop(1).forEach { it.close() }

        val missingGate = rollingHealthGate()
        val missingWarmup = acquire(missingGate, 6)
        val missing = checkNotNull(missingWarmup.first().succeeded(1_000_000L))
        assertTrue(missing.frozen)
        assertEquals("missing_physical_eof_proof", missing.transitionReason)
        missingWarmup.drop(1).forEach { it.close() }
    }

    @Test
    fun slowC24BodyFallsBackToProvenC12WithoutCancellingOtherCalls() {
        val gate = rollingHealthGate()
        completeHealthStage(gate, 6, 1L, 1_000L, 2_000L)
        completeHealthStage(gate, 12, 100L, 4_000L, 2_000L)
        assertEquals(24, gate.snapshot().targetLimit)

        val active = acquire(gate, 24)
        val downshifted = checkNotNull(active.first().succeeded(
            healthProof(1_000L, 30, startMs = 7_000L, elapsedMs = 3_001L),
        ))
        assertTrue(downshifted.frozen)
        assertEquals(12, downshifted.targetLimit)
        assertEquals(23, downshifted.activeLeases)
        assertNull(gate.tryAcquire(0L, TimeUnit.MILLISECONDS))
        active.drop(1).forEach { it.close() }
        assertEquals(0, gate.snapshot().activeLeases)
        assertEquals(12, gate.snapshot().targetLimit)
    }

    @Test
    fun finiteSuffixBelowFortyEightStaysAtC6AndProvisionalProofsCannotPromote() {
        val finite = rollingHealthGate(eligibleBodyCount = 27)
        completeHealthStage(finite, 6, 1L, 1_000L, 1_000L)
        assertEquals(6, finite.snapshot().targetLimit)
        assertTrue(finite.snapshot().settled)

        val provisional = rollingHealthGate(
            eligibleBodyCount = 384,
            finiteBodyCountKnown = false,
        )
        completeHealthStage(provisional, 6, 100L, 1_000L, 1_000L)
        assertEquals(6, provisional.snapshot().targetLimit)
        assertFalse(provisional.snapshot().finiteBodyCountKnown)

        val armed = checkNotNull(provisional.settleForFiniteBodyCount(60))
        assertTrue(armed.finiteBodyCountKnown)
        assertFalse(armed.settled)
        completeHealthStage(provisional, 6, 200L, 3_000L, 1_000L)
        assertEquals(12, provisional.snapshot().targetLimit)
    }

    @Test
    fun provisionalActiveGenerationResetsIntoWarmupWithoutStuckOrScoreLeak() {
        val gate = rollingHealthGate(
            eligibleBodyCount = 384,
            finiteBodyCountKnown = false,
        )
        val provisional = acquire(gate, 6)
        val armed = checkNotNull(gate.settleForFiniteBodyCount(60))
        assertFalse(armed.settled)
        assertEquals(6, armed.activeLeases)

        val current = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        provisional.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 40_000L + index,
                    pageIndex = index,
                    startMs = 1_000L,
                    elapsedMs = 1_000L,
                ),
            )
            current += acquire(gate, 1).single()
            assertEquals(6, gate.snapshot().activeLeases)
            assertEquals(0, gate.snapshot().stageSuccesses)
        }

        // The first current-generation refill only establishes a full C6 warm-up. Its refill is
        // the sixth fixed sample; the other five were admitted by the remaining provisional EOFs.
        current.first().succeeded(
            healthProof(
                operationId = 41_000L,
                pageIndex = 10,
                startMs = 3_000L,
                elapsedMs = 1_000L,
            ),
        )
        val sixthSample = acquire(gate, 1).single()
        val samples = current.drop(1) + sixthSample
        samples.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 42_000L + index,
                    pageIndex = 20 + index,
                    startMs = 5_000L,
                    elapsedMs = 1_000L,
                    host = REPLICA_HOSTS[index % REPLICA_HOSTS.size],
                ),
            )
        }
        assertEquals(12, gate.snapshot().targetLimit)
        assertEquals("healthy_c6_to_c12", gate.snapshot().transitionReason)
    }

    @Test
    fun duplicateProofsAndNeutralAbortCannotUnsafePromote() {
        val duplicateGate = rollingHealthGate()
        val duplicateLeases = acquire(duplicateGate, 6)
        duplicateLeases.forEachIndexed { index, lease ->
            val transition = lease.succeeded(
                healthProof(
                    operationId = 1L,
                    pageIndex = index,
                    host = REPLICA_HOSTS[index % REPLICA_HOSTS.size],
                ),
            )
            if (index == 1) {
                assertEquals("duplicate_physical_proof", transition?.transitionReason)
            }
        }
        assertEquals(6, duplicateGate.snapshot().targetLimit)
        assertEquals(0, duplicateGate.snapshot().stageSuccesses)
        assertTrue(duplicateGate.snapshot().frozen)

        val abortedGate = rollingHealthGate()
        val active = acquire(abortedGate, 6)
        val frozen = checkNotNull(active.first().aborted())
        assertTrue(frozen.frozen)
        assertEquals("ambiguous_abort", frozen.transitionReason)
        active.drop(1).forEach { it.close() }
        assertEquals(6, abortedGate.snapshot().targetLimit)
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

    private fun rollingHealthGate(
        eligibleBodyCount: Int = 96,
        finiteBodyCountKnown: Boolean = true,
    ) = NtkAdaptiveManhwaBulkAdmission(
        eligibleBodyCount = eligibleBodyCount,
        probeWiderStages = false,
        healthGatedRollingRamp = true,
        finiteBodyCountKnownAtConstruction = finiteBodyCountKnown,
    )

    private fun completeHealthStage(
        gate: NtkAdaptiveManhwaBulkAdmission,
        count: Int,
        operationBase: Long,
        startMs: Long,
        elapsedMs: Long,
    ) {
        val samples = mutableListOf<NtkAdaptiveManhwaBulkAdmission.Lease>()
        acquire(gate, count).forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = 1_000_000L + operationBase + index,
                    pageIndex = index,
                    startMs = startMs,
                    elapsedMs = elapsedMs,
                    host = REPLICA_HOSTS[index % REPLICA_HOSTS.size],
                ),
            )
            samples += acquire(gate, 1).single()
        }
        samples.forEachIndexed { index, lease ->
            lease.succeeded(
                healthProof(
                    operationId = operationBase + index,
                    pageIndex = count + index,
                    startMs = startMs + elapsedMs + 1L,
                    elapsedMs = elapsedMs,
                    host = REPLICA_HOSTS[index % REPLICA_HOSTS.size],
                ),
            )
        }
    }

    private fun healthProof(
        operationId: Long,
        pageIndex: Int,
        host: String = REPLICA_HOSTS[Math.floorMod(pageIndex, REPLICA_HOSTS.size)],
        expectedHost: String = host,
        responseHost: String = host,
        startMs: Long = 1_000L,
        elapsedMs: Long = 500L,
        encodedBytes: Long = 1_000_000L,
        protocol: String = "http/1.1",
        physicalAttemptOrdinal: Int = 0,
        usedRangeContinuation: Boolean = false,
        capturedProfileLive: Boolean = true,
        ordinaryClassificationLive: Boolean = true,
        exactOrdinaryJpeg: Boolean = true,
    ) = NtkAdaptiveManhwaBulkAdmission.PhysicalProof(
        operationId = operationId,
        pageIndex = pageIndex,
        encodedBytes = encodedBytes,
        expectedResponseHost = expectedHost,
        capturedProfileLive = capturedProfileLive,
        ordinaryClassificationLive = ordinaryClassificationLive,
        exactOrdinaryJpeg = exactOrdinaryJpeg,
        evidence = ReaderImageCache.NtkStrictPhysicalBodyEvidence(
            protocol = protocol,
            responseHost = responseHost,
            physicalStartedAtNanos = TimeUnit.MILLISECONDS.toNanos(startMs),
            proofReadyAtNanos = TimeUnit.MILLISECONDS.toNanos(startMs + elapsedMs),
            physicalAttemptOrdinal = physicalAttemptOrdinal,
            usedRangeContinuation = usedRangeContinuation,
        ),
    )

    companion object {
        private val REPLICA_HOSTS = NtkClickOwnedManhwaWavePolicy.replicaHosts()
    }
}
