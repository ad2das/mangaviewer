package ml.melun.mangaview.reader

import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkFrameOrderingTest {
    @Test
    fun frameIdentityAndSubmissionPairsRemainStrictlyOrdered() {
        val ordered = TreeMap<NtkFrameOrderKey, String>()
        ordered[NtkFrameOrderKey(3L, 12L)] = "twelve"
        ordered[NtkFrameOrderKey(3L, 10L)] = "ten"
        assertEquals(listOf(10L, 12L), ordered.keys.map { it.frameSequence })
        assertTrue(isStrictlyNewerNtkFrame(
            NtkFrameOrderKey(3L, 11L), NtkFrameOrderKey(3L, 10L)
        ))
        assertFalse(areAdjacentNtkFrames(ordered.firstKey(), ordered.lastKey()))
        assertEquals(16_000_000L, functionalSubmissionDeltaNanos(
            NtkFrameOrderKey(3L, 10L), 7L, 100_000_000L,
            NtkFrameOrderKey(3L, 11L), 7L, 116_000_000L
        ))
        assertNull(functionalSubmissionDeltaNanos(
            NtkFrameOrderKey(3L, 10L), 7L, 100_000_000L,
            NtkFrameOrderKey(3L, 12L), 7L, 116_000_000L
        ))
    }

    @Test
    fun retirePrepareOverlapConservesEveryNanosecond() {
        val phases = functionalPhaseDecompositionNanos(
            first = phase(
                draw = 90_000_000L,
                backend = 94_000_000L,
                preSwap = 98_000_000L,
                postSwap = 100_000_000L,
                retired = 112_000_000L
            ),
            second = phase(
                draw = 102_000_000L,
                backend = 108_000_000L,
                preSwap = 114_000_000L,
                postSwap = 116_000_000L,
                retired = 128_000_000L
            ),
            submissionDeltaNanos = 16_000_000L
        )

        assertEquals(2_000_000L, phases?.nextWorkStartDelayNanos)
        assertEquals(6_000_000L, phases?.backendPreparationNanos)
        assertEquals(4_000_000L, phases?.residualPriorTargetGateNanos)
        assertEquals(2_000_000L, phases?.phaseAdmissionAfterBothReadyNanos)
        assertEquals(6_000_000L, phases?.rendererReadyToQueueNanos)
        assertEquals(2_000_000L, phases?.swapCallNanos)
        assertEquals(6_000_000L, phases?.preparationOverlapNanos)
        assertEquals(16_000_000L, phases?.reconstructedSubmissionDeltaNanos)
    }

    @Test
    fun queueBeforePriorRetirementAndSerialGenerationAssumptionsAreRejected() {
        val first = phase(90, 94, 98, 100, 112)
        assertNull(functionalPhaseDecompositionNanos(
            first,
            phase(102, 108, 110, 116, 128),
            16L
        ))
        assertNull(functionalPhaseDecompositionNanos(
            first.copy(targetRetirementCompleteNanos = 0L),
            phase(102, 108, 114, 116, 128),
            16L
        ))
        val serial = functionalPhaseDecompositionNanos(
            first,
            phase(113, 114, 115, 116, 128),
            16L
        )
        assertEquals(0L, serial?.preparationOverlapNanos)
        assertTrue((serial?.nextWorkStartDelayNanos ?: 0L) > 0L)
    }

    @Test
    fun rendererReadyIsExactlyResidualTargetPlusPhaseAdmission() {
        val phases = functionalPhaseDecompositionNanos(
            phase(90, 94, 98, 100, 112),
            phase(102, 108, 114, 116, 128),
            16L
        )!!
        assertEquals(
            phases.rendererReadyToQueueNanos,
            phases.residualPriorTargetGateNanos +
                phases.phaseAdmissionAfterBothReadyNanos
        )
        assertTrue(functionalRendererReadyToQueueWithinBudget(16_670_000L, 16_670_000L))
        assertFalse(functionalRendererReadyToQueueWithinBudget(16_670_001L, 16_670_000L))
    }

    @Test
    fun strictContinuityDebtAndPauseRulesRemainUnchanged() {
        val period = PINNED_90_HZ_FRAME_PERIOD_NANOS
        assertEquals(
            FunctionalRendererReadyFrameDebt(1, 3),
            functionalRendererReadyFrameDebt(listOf(period * 3L + 1L))
        )
        assertEquals(1, countFunctionalSubmissionPauses(
            listOf(16_000_000L, 50_000_000L), 50_000_000L
        ))
        assertEquals(2, functionalSubmissionMaxOverBudgetStreak(
            listOf(
                FunctionalGestureDelta(7L, 17_000_000L),
                FunctionalGestureDelta(7L, 18_000_000L),
                FunctionalGestureDelta(8L, 18_000_000L)
            ),
            16_670_000L
        ))
    }

    @Test
    fun steadyState90HzCase1StateMachineUsesProductionDebtAccumulator() {
        val period = PINNED_90_HZ_FRAME_PERIOD_NANOS
        val callbackBoundIntervals = List(63) { period }
        val callbackBound = functionalRendererReadyFrameDebt(callbackBoundIntervals, period)
        assertEquals(FunctionalRendererReadyFrameDebt(0, 0), callbackBound)
        assertEquals(0, countFunctionalSubmissionPauses(callbackBoundIntervals, 50_000_000L))

        // Expected-failure oracle for the removed retirement-thread handoff: every interval is
        // approximately 2T and therefore owns one full frame of production debt.
        val legacyIntervals = List(63) { period * 2L }
        val legacy = functionalRendererReadyFrameDebt(legacyIntervals, period)
        assertEquals(63, legacy.missedFrames)
        assertEquals(63, legacy.droppedFrames)
    }

    private fun phase(
        draw: Long,
        backend: Long,
        preSwap: Long,
        postSwap: Long,
        retired: Long
    ) = FunctionalFramePhaseTimestamps(
        drawBeginNanos = draw,
        backendWaitReturnNanos = backend,
        preSwapNanos = preSwap,
        postSwapNanos = postSwap,
        targetRetirementCompleteNanos = retired
    )
}
