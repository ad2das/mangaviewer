package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkSchema11FrameValidatorTest {
    @Test
    fun firstStageGolden311VectorPasses() {
        val values = firstStage()
        assertEquals(NtkSchema11FrameValidator.FIELD_COUNT, values.size)
        assertNull(NtkSchema11FrameValidator.violation(values))
    }

    @Test
    fun schema11KeepsExactSchema10AcquireFenceOwnership() {
        assertEquals(
            "v10-prefix:acquire-fence",
            NtkSchema11FrameValidator.violation(
                firstStage().also { it[198] = 0L }
            )
        )
    }

    @Test
    fun firstStageRequiresExactRealJoinOpenWithoutPriorObservation() {
        val zeroJoin = firstStage().also { it[296] = 0L }
        assertEquals(
            "swappy-first-stage-retirement",
            NtkSchema11FrameValidator.violation(zeroJoin)
        )

        val foreignJoin = firstStage().also { it[296] = it[137] + 1L }
        assertEquals(
            "swappy-first-stage-retirement",
            NtkSchema11FrameValidator.violation(foreignJoin)
        )

        val fakePriorLatch = firstStage().also {
            it[289] = 1L
            it[290] = 1L
            it[292] = 2L
        }
        assertEquals(
            "swappy-first-stage-retirement",
            NtkSchema11FrameValidator.violation(fakePriorLatch)
        )
    }

    @Test
    fun successorCarriesRetirementAndExactLatchJoinProof() {
        val v = firstStage()
        v[191] = 1L
        v[180] = 3L
        v[181] = 4L
        v[192] = 1L
        v[207] = 1L
        v[214] = 2L
        v[215] = 2L
        v[216] = 6L
        v[217] = 6L
        v[218] = 6L
        v[219] = 6L
        v[233] = 1L
        v[234] = 1L
        v[235] = 0L
        v[236] = 2L
        v[238] = 1L
        v[239] = 1L
        v[240] = 1L
        for (index in 241..251) v[index] = index.toLong()
        v[252] = 0L
        v[253] = 1L
        v[254] = 1L
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
        v[267] = 7L
        v[268] = 1_300L
        v[269] = 1_400L
        v[270] = 1L
        v[271] = 1L
        v[272] = v[240]
        v[273] = 2L
        v[274] = v[34] - v[256]
        v[276] = 1L

        v[282] = 2L
        v[283] = 1L
        v[289] = 1L
        v[290] = 1L
        v[291] = 0L
        v[292] = 2L
        v[293] = v[267]
        v[294] = v[268]
        v[295] = v[269]
        v[296] = v[137]
        v[297] = v[296] - v[295]
        v[299] = v[273]
        v[300] = v[240]
        v[303] = 1L
        v[304] = 1L
        v[305] = 1L
        v[307] = 0L
        v[308] = 1L
        v[309] = v[34] - v[295]
        v[310] = 1L
        assertNull(NtkSchema11FrameValidator.violation(v))

        val missingGate = v.copyOf().also {
            it[233] = 0L
            it[234] = 0L
            it[236] = 1L
            it[237] = 1L
            for (index in 267..271) it[index] = 0L
            it[276] = 0L
            it[289] = 0L
            it[290] = 0L
            it[292] = 1L
            for (index in 293..295) it[index] = 0L
            it[297] = 0L
            it[298] = 1L
        }
        assertEquals(
            "v10-prefix:prior-latch-gate",
            NtkSchema11FrameValidator.violation(missingGate)
        )
    }

    @Test
    fun rejectsReason20AndMissingSubmittedWaitLatchState() {
        assertEquals(
            "renderer-post-submit",
            NtkSchema11FrameValidator.violation(
                firstStage().also { it[287] = 3L }
            )
        )
        assertEquals(
            "surface-latch-watermark",
            NtkSchema11FrameValidator.violation(
                firstStage().also { it[303] = 0L }
            )
        )
    }

    internal fun firstStage(): LongArray {
        val prefix = NtkSchema10FrameValidatorTest().golden()
        return prefix.copyOf(NtkSchema11FrameValidator.FIELD_COUNT).also { v ->
            v[83] = 11L
            v[282] = 1L
            v[283] = 0L
            v[284] = 0L
            v[285] = 1L
            v[286] = 1L
            v[287] = 0L
            v[288] = 1L
            v[296] = v[137]
            v[299] = v[273]
            v[300] = 0L
            v[301] = 1L
            v[302] = 1L
            v[303] = 1L
            v[304] = 1L
            v[305] = 1L
            v[306] = 1L
            v[307] = 0L
            v[308] = 0L
            v[309] = 0L
            v[310] = 0L
        }
    }
}
