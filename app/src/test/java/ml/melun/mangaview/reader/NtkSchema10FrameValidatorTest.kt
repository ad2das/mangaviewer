package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkSchema10FrameValidatorTest {
    @Test
    fun firstStageGolden282VectorPasses() {
        val values = golden()
        assertEquals(NtkSchema10FrameValidator.FIELD_COUNT, values.size)
        assertNull(NtkSchema10FrameValidator.violation(values))
    }

    @Test
    fun acquireFenceSafetyIsExactForBothSignalTimingBranches() {
        assertNull(
            NtkSchema10FrameValidator.violation(
                golden().also { it[201] = 0L }
            )
        )
        assertNull(
            NtkSchema10FrameValidator.violation(
                golden().also { it[201] = 1L }
            )
        )
        listOf(
            golden().also { it[194] = it[95] - 1L },
            golden().also { it[195] = 0L },
            golden().also { it[196] = 0L },
            golden().also { it[197] = 1L },
            golden().also { it[198] = 0L },
            golden().also { it[199] = 0L },
            golden().also { it[200] = 1L },
            golden().also { it[201] = -1L },
            golden().also { it[201] = 2L }
        ).forEach { invalid ->
            assertEquals(
                "acquire-fence",
                NtkSchema10FrameValidator.violation(invalid)
            )
        }
    }

    @Test
    fun slotZeroIsAValidFullPredecessorIdentity() {
        val values = golden().also { v ->
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
            v[235] = 1L
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
            v[267] = 1L
            v[268] = 1_300L
            v[269] = 1_400L
            v[270] = 1L
            v[271] = 1L
            v[272] = v[240]
            v[274] = v[34] - v[256]
            v[276] = 1L
        }
        assertNull(NtkSchema10FrameValidator.violation(values))
    }

    @Test
    fun rejectsLatchGateCapacityWaitAndDrawBeforeReservation() {
        assertEquals(
            "first-stage-prior",
            NtkSchema10FrameValidator.violation(golden().also { it[234] = 1L })
        )
        assertEquals(
            "prior-latch-gate",
            NtkSchema10FrameValidator.violation(
                golden().also { v ->
                    v[191] = 1L
                    v[181] = 4L
                    v[192] = 1L
                    v[207] = 1L
                    v[214] = 2L
                    v[215] = 2L
                    v[216] = 6L
                    v[217] = 6L
                    v[218] = 6L
                    v[219] = 6L
                    v[238] = 1L
                }
            )
        )
        assertEquals(
            "backend-policy",
            NtkSchema10FrameValidator.violation(golden().also { it[223] = 1L })
        )
        assertEquals(
            "reserve-before-draw",
            NtkSchema10FrameValidator.violation(
                golden().also { it[278] = it[277] - 1L }
            )
        )
    }

    internal fun golden() = LongArray(282).also { v ->
        for (index in 0..9) v[index] = index + 1L
        v[10] = 0L
        v[12] = 0L
        v[13] = 0L
        v[14] = 10_000L
        v[15] = 0L
        v[16] = 2_000L
        v[17] = 0L
        v[18] = 1L
        v[19] = -1L
        v[20] = 1L
        v[21] = 1L
        v[32] = 1_000L
        v[33] = 2_000L
        v[34] = 2_100L
        v[35] = 2_200L
        v[36] = 2_300L
        v[37] = 3_000L
        v[38] = 2_500L
        v[39] = 1L
        v[40] = 1L
        v[46] = 2L
        v[47] = 1L
        v[48] = 1L
        v[49] = 1L
        v[56] = 100L
        v[57] = 200L
        v[58] = 300L
        v[59] = 2_500L
        v[61] = v[9]
        v[62] = 3L
        v[63] = 1L
        v[64] = 1L
        v[65] = 1L
        v[69] = 150L
        v[70] = 100L
        v[71] = 1L
        v[72] = 400L
        v[73] = 1L
        v[74] = 0L
        v[75] = 1L
        v[76] = 1L
        v[77] = 1L
        v[80] = 1L
        v[81] = 1L
        v[82] = 1L
        v[83] = 10L
        v[84] = 1L
        v[86] = 1L
        v[87] = NtkSchema10FrameValidator.NINETY_HZ_PERIOD_NANOS
        v[88] = 2_000L
        v[89] = 12_000_000L
        v[91] = 7_000_000L
        v[92] = 1L
        v[93] = v[32]
        v[94] = 1_100L
        v[95] = 1_200L
        v[96] = 1_300L
        v[97] = 1L
        v[98] = 1L
        v[99] = 0L
        v[100] = 1L
        v[101] = 1L
        for (index in 102..106) v[index] = 1L
        v[107] = 2_600L
        v[108] = 3_100L
        v[109] = 1L
        v[115] = 1L
        v[116] = 1L
        v[119] = NTK_FIXED_RETIREMENT_RETIRED.toLong()
        for (index in 121..125) v[index] = index.toLong()
        v[126] = 1L
        v[127] = 0L
        v[131] = v[4]
        v[132] = v[5]
        v[133] = 1L
        v[134] = 1L
        v[135] = 1L
        v[136] = 1_500L
        v[137] = 1_600L
        v[138] = 1_700L
        v[147] = 1L
        v[148] = 1L
        v[149] = v[87] / 2L
        v[150] = 1_900L
        for (index in 151..156) v[index] = 1L
        v[157] = 0L
        v[158] = 0L
        v[161] = v[88]
        v[162] = 1L
        v[163] = 1_400L
        v[164] = 1_500L
        v[165] = 0L
        v[166] = 0L
        v[167] = 1L
        v[168] = 0L
        v[169] = 0L
        v[170] = 1L
        v[171] = 1L
        v[175] = 1L
        v[176] = 1L
        v[177] = 1L
        v[180] = 3L
        v[190] = 1L
        v[194] = 2_000L
        v[195] = 1L
        v[196] = 1L
        v[197] = 2L
        v[198] = 1L
        v[199] = 1L
        v[201] = 0L
        v[205] = 1L
        v[206] = 1L
        v[212] = 1L
        v[213] = 1L
        v[214] = 1L
        v[215] = 1L
        v[216] = 7L
        v[217] = 7L
        v[218] = 7L
        v[219] = 7L
        v[220] = 1L
        v[229] = 1L
        v[230] = 1L
        v[232] = 1L
        v[273] = 1L
        v[277] = 900L
        v[278] = 950L
        v[279] = 0L
        v[280] = 1L
        v[281] = 1L
    }
}
