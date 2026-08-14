package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkSchema11QualificationAccumulatorTest {
    @Test
    fun latchConjunctionPrefixAcceptsPostOnCommitSuccessorChain() {
        val first = NtkSchema11FrameValidatorTest().firstStage()
        val successor = exactLatchSuccessor(first)
        assertNull(NtkSchema11FrameValidator.violation(first))
        assertNull(NtkSchema11FrameValidator.violation(successor))

        val accumulator = NtkSchema11QualificationAccumulator()
        accumulator.accept(frame(first))
        accumulator.beginInteractionWindow()
        accumulator.accept(frame(successor))
        val base = accumulator.snapshot(LongArray(0), 0, false).base

        assertEquals(0, base.invalidFrames)
        assertEquals(0, base.identityOrOrderInvalidFrames)
        assertEquals(0, base.priorRetirementChainInvalidFrames)
        assertEquals(2L, base.fullJoinCount)
        assertEquals(0, base.successorApplyBeforePriorCommitPairs)
        assertEquals(1L, base.priorLatchGateUsedCount)
        assertEquals(0L, base.waitingPriorLatchStatusCount)
    }

    @Test
    fun observedLatchSidecarMustMatchPreviousIdentityAndTimestamp() {
        val first = NtkSchema11FrameValidatorTest().firstStage()
        val observed = exactLatchSuccessor(first)
        assertNull(NtkSchema11FrameValidator.violation(observed))
        assertEquals(0, snapshot(first, observed).priorRetirementChainInvalidFrames)

        val foreignEvent = observed.copyOf().also {
            it[267] += 7L
            it[293] = it[267]
        }
        assertNull(NtkSchema11FrameValidator.violation(foreignEvent))
        assertEquals(1, snapshot(first, foreignEvent).priorRetirementChainInvalidFrames)

        val foreignTimestamp = observed.copyOf().also {
            it[269] += 1L
            it[295] = it[269]
            it[297] = it[296] - it[295]
            it[309] = it[34] - it[295]
        }
        assertNull(NtkSchema11FrameValidator.violation(foreignTimestamp))
        assertEquals(1, snapshot(first, foreignTimestamp).priorRetirementChainInvalidFrames)
    }

    @Test
    fun surfaceControlApplyMayFinishAfterStartDeadlineWhenCutoffProofIsExact() {
        val first = NtkSchema11FrameValidatorTest().firstStage()
        val successor = exactLatchSuccessor(first).also { values ->
            // finalDecision=2_700 < latest start=2_800, while the asynchronous
            // SurfaceControl apply completes at 2_900.  Transport/cutoff slack fields
            // remain the authoritative completion bounds.
            values[91] = 2_800L
            values[168] = 1L
            values[169] = 1L
        }

        assertNull(NtkSchema11FrameValidator.violation(successor))
        assertEquals(0, snapshot(first, successor).fixedOpportunityInvalidFrames)
    }

    @Test
    fun normalLifetimeDoesNotRetainFullFramesBeforeInteractionIsArmed() {
        val accumulator = NtkSchema11QualificationAccumulator(
            interactionCapacity = 2,
            identityCapacity = 32
        )
        var values = NtkSchema11FrameValidatorTest().firstStage()
        accumulator.accept(frame(values))
        repeat(4) {
            values = exactLatchSuccessor(values)
            accumulator.accept(frame(values))
        }

        val snapshot = accumulator.snapshot(LongArray(0), 0, false)
        assertEquals(5, snapshot.lifetimeEvidenceFrames)
        assertEquals(0, snapshot.interactionEvidenceFrames)
        assertEquals(0, snapshot.retainedInteractionEvidenceFrames)
        assertEquals(0L, snapshot.droppedInteractionEvidenceFrames)
        assertFalse(snapshot.evidenceOverflow)
    }

    @Test
    fun interactionOverflowStopsRetentionAndFailsClosedExplicitly() {
        val accumulator = NtkSchema11QualificationAccumulator(
            interactionCapacity = 1,
            identityCapacity = 32
        )
        val first = NtkSchema11FrameValidatorTest().firstStage()
        val successor = exactLatchSuccessor(first)
        accumulator.accept(frame(first))
        accumulator.beginInteractionWindow()
        accumulator.accept(frame(successor))
        val beforeOverflow = accumulator.snapshot(LongArray(0), 0, false)
        assertEquals(0, beforeOverflow.base.invalidFrames)
        assertFalse(beforeOverflow.evidenceOverflow)

        // Re-publishing the same individually valid capsule is an identity/order defect, but not
        // a per-frame validator defect.  That isolates the synthetic invalidFrames overflow bit.
        accumulator.accept(frame(successor))

        val overflow = accumulator.snapshot(LongArray(0), 0, false)
        assertEquals(2, overflow.interactionEvidenceFrames)
        assertEquals(1, overflow.retainedInteractionEvidenceFrames)
        assertEquals(1L, overflow.droppedInteractionEvidenceFrames)
        assertTrue(overflow.interactionEvidenceOverflow)
        assertTrue(overflow.evidenceOverflow)
        assertEquals(1, overflow.base.invalidFrames)

        accumulator.beginInteractionWindow()
        accumulator.accept(frame(exactLatchSuccessor(successor)))
        val recovered = accumulator.snapshot(LongArray(0), 0, false)
        assertEquals(1, recovered.interactionEvidenceFrames)
        assertEquals(1, recovered.retainedInteractionEvidenceFrames)
        assertEquals(0L, recovered.droppedInteractionEvidenceFrames)
        assertFalse(recovered.interactionEvidenceOverflow)
    }

    @Test
    fun boundedIdentityLedgerFailsClosedWhenItsExactSetIsFull() {
        val accumulator = NtkSchema11QualificationAccumulator(
            interactionCapacity = 8,
            identityCapacity = 2
        )
        var values = NtkSchema11FrameValidatorTest().firstStage()
        accumulator.accept(frame(values))
        repeat(2) {
            values = exactLatchSuccessor(values)
            accumulator.accept(frame(values))
        }

        val snapshot = accumulator.snapshot(LongArray(0), 0, false)
        assertTrue(snapshot.base.identityEvidenceOverflow)
        assertTrue(snapshot.evidenceOverflow)
        assertTrue(snapshot.base.identityOrOrderInvalidFrames > 0)
        assertTrue(snapshot.base.invalidFrames > 0)
    }

    @Test
    fun stripDiagnosticFrameWindowHasAnExplicitFiniteCeiling() {
        assertEquals(2_048, NtkStripSurfaceView.MAX_PRESENT_DIAGNOSTIC_FRAMES)
    }

    private fun snapshot(first: LongArray, successor: LongArray):
        NtkSchema10QualificationSnapshot {
        val accumulator = NtkSchema11QualificationAccumulator()
        accumulator.accept(frame(first))
        accumulator.beginInteractionWindow()
        accumulator.accept(frame(successor))
        return accumulator.snapshot(LongArray(0), 0, false).base
    }

    private fun frame(values: LongArray) =
        NtkStripRenderEngine.FrameSnapshot().apply {
            schema11Values = values.copyOf()
            schema10Values = values.copyOfRange(
                0, NtkSchema10FrameValidator.FIELD_COUNT
            ).also { it[83] = 10L }
        }

    private fun successorBase(previous: LongArray): LongArray =
        previous.copyOf().also { v ->
            for (index in intArrayOf(4, 5, 6, 7, 8, 39, 40, 98, 190, 195, 196, 273)) {
                v[index] = previous[index] + 1L
            }
            v[99] = 1L
            v[100] = 1L
            v[101] = previous[101] + 1L
            v[131] = v[4]
            v[132] = v[5]

            v[176] = 2L
            v[177] = 2L
            v[180] = 4L
            v[181] = 3L
            v[191] = 1L
            v[192] = 1L
            v[205] = 2L
            v[206] = 2L
            v[207] = 1L
            v[210] = 1L
            v[211] = 2L
            v[212] = 1L
            v[213] = 2L
            v[214] = 2L
            v[215] = 2L
            v[216] = 6L
            v[217] = 6L
            v[218] = 6L
            v[219] = 6L
            v[225] = 1L

            v[233] = 1L
            v[234] = 1L
            v[235] = 0L
            v[236] = 2L
            v[237] = 0L
            v[238] = 1L
            v[239] = previous[40]
            v[240] = previous[273]
            v[241] = previous[0]
            v[242] = previous[3]
            v[243] = previous[1]
            v[244] = previous[2]
            v[245] = previous[4]
            v[246] = previous[5]
            v[247] = previous[6]
            v[248] = previous[7]
            v[249] = previous[8]
            v[250] = previous[97]
            v[251] = previous[98]
            v[252] = previous[99]
            v[253] = previous[100]
            v[254] = previous[101]
            v[255] = 1_000L
            v[256] = 1_100L
            v[257] = 1_200L
            v[258] = 1L
            v[259] = 1L
            v[260] = 1L
            v[261] = 1L
            v[262] = 1L
            v[263] = 0L
            v[264] = 1L
            v[265] = NTK_FIXED_RETIREMENT_RETIRED.toLong()
            v[266] = 0L
            for (index in 267..271) v[index] = 0L
            v[272] = v[240]
            v[274] = v[34] - v[256]
            v[276] = 0L

            v[282] = 2L
            v[283] = 1L
            v[284] = 0L
            v[285] = 1L
            v[286] = 1L
            v[287] = 0L
            v[288] = 1L
            v[289] = 1L
            v[290] = 1L
            v[291] = 0L
            v[292] = 2L
            for (index in 293..295) v[index] = 0L
            v[296] = v[137]
            v[297] = 0L
            v[298] = 0L
            v[299] = v[273]
            v[300] = 0L
            v[301] = 1L
            v[302] = 1L
            v[303] = 1L
            v[304] = 1L
            v[305] = 1L
            v[306] = 2L
            v[307] = 0L
            v[308] = 1L
            v[309] = 0L
            v[310] = 1L
        }

    private fun exactLatchSuccessor(previous: LongArray): LongArray =
        successorBase(previous).also { v ->
            v[32] = 2_000L
            v[33] = 2_700L
            v[34] = 2_800L
            v[35] = 2_900L
            v[36] = 3_000L
            v[37] = 4_000L
            v[38] = 3_500L
            v[88] = 2_700L
            v[93] = v[32]
            v[94] = 2_100L
            v[95] = 2_200L
            v[96] = 2_300L
            v[107] = 3_600L
            v[108] = 4_100L
            v[136] = 2_600L
            v[137] = 2_650L
            v[138] = 2_660L
            v[150] = 2_600L
            v[161] = v[88]
            v[163] = 2_400L
            v[164] = 2_500L
            v[194] = 3_000L

            v[236] = 2L
            v[237] = 0L
            v[267] = previous[39]
            v[268] = previous[38]
            v[269] = previous[107]
            v[270] = previous[106]
            v[271] = previous[104]
            v[274] = v[34] - v[256]
            v[276] = 1L

            v[292] = 2L
            v[293] = v[267]
            v[294] = v[268]
            v[295] = v[269]
            v[296] = v[137]
            v[297] = v[296] - v[295]
            v[298] = 0L
            v[300] = v[240]
            v[309] = v[34] - v[295]
        }
}
