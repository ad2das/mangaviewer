package ml.melun.mangaview.reader

import kotlin.math.abs

/** Direct validator for the canonical asynchronous NTK10 282-long payload. */
object NtkSchema10FrameValidator {
    const val FIELD_COUNT = 282
    const val NINETY_HZ_PERIOD_NANOS = 11_111_111L
    const val REFRESH_TOLERANCE_NANOS = 100_000L

    @JvmStatic
    fun violation(v: LongArray): String? {
        if (v.size != FIELD_COUNT) return "field-count:${v.size}:$FIELD_COUNT"
        val kind = v[74].toInt()
        if ((0..9).any { v[it] <= 0L }) return "identity"
        if (v[10] < 0L || v[12] < 0L || v[13] != 0L || v[14] <= 0L ||
            v[10] > v[14] || v[12] > v[14] || v[15] != v[10] ||
            v[16] <= v[15] || v[16] > v[14] || v[17] < 0L ||
            v[18] < v[17] || v[19] != -1L || v[20] != 1L || v[21] != 1L
        ) return "geometry"
        if (kind !in 0..2) return "frame-kind"
        if (kind == 0) {
            if (v[22] != 0L || v[23] != 0L) return "stage-input"
        } else {
            if (v[22] <= 0L || v[23] <= 0L ||
                !orderedPositive(v[24], v[25]) ||
                !orderedPositive(v[26], v[27]) ||
                !orderedPositive(v[28], v[29]) ||
                !orderedPositive(v[30], v[31]) ||
                v[25] > v[27] || v[27] > v[29] ||
                v[28] > v[30] || v[31] > v[32]
            ) return "input-timestamps"
        }
        if (v[32] <= 0L || v[33] <= 0L || v[34] < v[33] ||
            v[35] < v[34] || v[36] < v[35] || v[37] < v[36] ||
            v[38] < v[34] || v[39] <= 0L || v[40] <= 0L
        ) return "frame-timestamps"
        if (v[41] < 0L || v[42] < 0L || v[43] < 0L ||
            (44..45).any { v[it] != 0L } || v[46] !in 2L..4L ||
            v[47] != 1L || v[48] <= 0L || v[48] != v[49] ||
            (50..55).any { v[it] != 0L } || v[56] <= 0L ||
            v[57] < v[56] || v[58] < v[57] || v[59] < v[58] ||
            v[61] != v[9] || v[62] != 3L || v[63] <= 0L ||
            v[64] <= 0L || v[64] != v[65] || v[66] != 0L ||
            v[67] != 0L || v[68] != 0L || v[69] < v[56] ||
            v[70] > v[57] || v[71] <= 0L || v[72] < v[58] ||
            v[73] <= 0L
        ) return "sealed-scene"
        if (v[75] != 1L || v[76] <= 0L || v[76] != v[77] ||
            v[80] != v[76] || v[81] != v[76] || v[79] < 0L ||
            v[79] > v[77] || v[82] != 1L
        ) return "submission-conservation"
        if (v[83] != 10L || v[84] != 1L || v[85] != 0L || v[86] != 1L ||
            abs(v[87] - NINETY_HZ_PERIOD_NANOS) > REFRESH_TOLERANCE_NANOS ||
            v[88] <= 0L || v[89] <= v[88] || v[91] <= v[88] || v[92] <= 0L
        ) return "fixed-phase"
        if (v[93] != v[32] || v[94] < v[93] || v[95] < v[94] ||
            v[96] < v[95] || v[163] < v[96] || v[164] < v[163] ||
            v[150] < v[164] || v[161] < v[150] || v[161] != v[88] ||
            v[33] != v[88] || v[34] < v[161] || v[35] < v[34] ||
            v[97] <= 0L || v[98] <= 0L || v[99] !in 0L..7L ||
            v[100] <= 0L || v[101] <= 0L || (102..105).any { v[it] != 1L } ||
            v[106] != 1L || v[107] < v[38] || v[108] < v[37] ||
            v[109] != 1L || v[110] != 0L
        ) return "surfacecontrol-identity"
        if (v[111] !in 0L..1L || v[115] <= 0L || v[116] < v[115] ||
            v[117] != 0L || v[118] != 0L ||
            v[119] != NTK_FIXED_RETIREMENT_RETIRED.toLong() || v[120] != 0L
        ) return "joined-capsule"
        if ((121..125).any { v[it] <= 0L } || v[125] < v[124] ||
            v[126] != 1L || v[127] !in 0L..1L ||
            (v[127] == 0L && (v[128] != 0L || v[129] != 0L)) ||
            (v[127] == 1L && (v[128] <= 0L || v[129] <= 0L)) ||
            v[131] != v[4] || v[132] != v[5] ||
            (133..138).any { v[it] <= 0L } || v[137] < v[136] ||
            v[138] < v[137] || v[88] < v[138] ||
            demandInvalid(v[139], v[140], v[141]) ||
            demandInvalid(v[142], v[143], v[144]) ||
            v[145] < 0L || v[146] < 0L
        ) return "opportunity"
        if (v[147] == 0L || v[148] <= 0L || v[149] != v[87] / 2L ||
            (151..156).any { v[it] <= 0L } || v[157] !in 0L..2L ||
            v[158] !in 0L..1L ||
            (v[158] == 0L && (v[159] != 0L || v[160] != 0L)) ||
            (v[158] == 1L && (v[159] <= 0L || v[160] < v[159])) ||
            v[162] != 1L || v[165] < 0L || v[166] < 0L ||
            v[167] <= 0L || v[165] > v[167] || v[168] < 0L ||
            v[169] < 0L || v[170] != 1L || v[171] != 1L ||
            (172..174).any { v[it] != 0L } || v[175] != 1L
        ) return "transport"
        return postApplyViolation(v) ?: physicalLedgerViolation(v)
    }

    internal fun postApplyViolation(v: LongArray): String? {
        if (v[176] !in 1L..8L || v[177] !in v[176]..8L ||
            v[178] != 0L || v[179] != 0L || v[99] !in 0L..7L
        ) return "post-apply-lanes"
        val states = IntArray(8) { v[180 + it].toInt() }
        if (states[v[99].toInt()] != 3 || states.count { it == 3 } != 1 ||
            states.any { it !in setOf(0, 3, 4) }
        ) return "post-apply-pool"
        val free = states.count { it == 0 }
        val release = states.count { it == 4 }
        if (free + release != 7 || v[191] !in 0L..1L ||
            (v[191] == 0L && (release != 0 || free != 7)) ||
            (v[191] == 1L && release !in 1..7)
        ) return "post-apply-release-state"
        if (v[188] < 0L || v[189] < 0L ||
            v[188] + v[189] > release + v[208] ||
            v[190] <= 0L ||
            (v[191] == 0L && v[192] != 0L) ||
            (v[191] == 1L && v[192] <= 0L) || v[193] != 0L
        ) return "post-apply-receipts"
        if (v[194] < v[95] || v[195] <= 0L || v[196] <= 0L ||
            v[197] != 2L || v[198] != 1L || v[199] != 1L ||
            v[200] != 0L || v[201] !in 0L..1L
        ) return "acquire-fence"
        val stage = v[74] == 0L
        if (stage && (v[202] != 0L || v[203] != 0L || v[204] != 0L)) {
            return "stage-visual-demand"
        }
        if (!stage && (v[202] <= 0L || v[203] < 0L || v[204] !in 0L..1L ||
                (v[204] == 1L && v[203] <= 0L))
        ) return "visual-demand"
        return null
    }

    private fun physicalLedgerViolation(v: LongArray): String? {
        val states = IntArray(8) { v[180 + it].toInt() }
        val release = states.count { it == 4 }.toLong()
        val free = states.count { it == 0 }.toLong()
        if (v[205] != v[176] || v[206] != v[177] ||
            v[207] != release || v[208] !in 0L..8L ||
            v[209] != v[208] || v[210] !in 0L..v[205] ||
            v[211] !in 0L..v[205] || v[212] !in v[210]..8L ||
            v[213] !in v[211]..8L
        ) return "callback-ledger"
        if (v[214] != 1L + release || v[215] !in v[214]..7L ||
            v[216] != free || v[217] !in 1L..v[216] ||
            v[218] != 8L - v[214] || v[219] !in 1L..v[218]
        ) return "buffer-domain"
        if (v[220] != 1L || v[221] != 0L || v[222] != 0L ||
            v[223] != 0L || v[224] != 0L
        ) return "backend-policy"
        if (v[225] < 0L || v[229] != 1L || v[230] != 1L ||
            v[231] != 0L || v[232] != 1L
        ) return "producer-depth"
        if (v[238] != v[191]) return "prior-latch-gate"
        if (v[238] == 0L) {
            if ((233..272).any { v[it] != 0L } || v[274] != 0L ||
                v[276] != 0L
            ) return "first-stage-prior"
        } else {
            if (v[233] != 1L || v[234] != 1L ||
                v[235] !in 0L..1L || v[236] != 2L || v[237] != 0L
            ) return "prior-latch-gate"
            if (v[239] <= 0L || v[240] <= 0L ||
                (241..251).any { v[it] <= 0L } ||
                v[252] < 0L || v[253] <= 0L || v[254] <= 0L ||
                v[255] <= 0L || v[256] < v[255] || v[257] < v[256] ||
                v[258] <= 0L || v[259] <= 0L || v[260] <= 0L ||
                v[261] <= 0L || v[262] != 1L || v[263] != 0L ||
                v[264] != 1L ||
                v[265] != NTK_FIXED_RETIREMENT_RETIRED.toLong() ||
                v[266] != 0L || v[272] != v[240] ||
                v[274] != v[34] - v[256] || v[274] < 0L
            ) return "prior-retirement-proof"
            if (v[267] <= 0L || v[268] <= 0L || v[269] < v[268] ||
                v[34] < v[269] || v[270] != 1L ||
                v[271] != 1L || v[276] != 1L
            ) return "observed-prior-latch"
        }
        if (v[273] <= 0L || v[277] <= 0L || v[278] < v[277] ||
            v[279] < 0L || v[280] <= v[279] || v[281] != 1L
        ) return "reserve-before-draw"
        return null
    }

    private fun orderedPositive(oldest: Long, newest: Long): Boolean =
        oldest > 0L && newest >= oldest

    private fun demandInvalid(issued: Long, satisfied: Long, cancelled: Long): Boolean =
        issued < 0L || satisfied < 0L || cancelled < 0L ||
            issued < satisfied || issued - satisfied < cancelled ||
            issued - satisfied - cancelled !in 0L..1L
}

internal object NtkSchema10PostApplyConservation {
    fun isExact(frame: NtkStripRenderEngine.FrameSnapshot): Boolean =
        NtkSchema10FrameValidator.postApplyViolation(frame.schema10Values) == null
}
