package ml.melun.mangaview.reader

/**
 * Canonical NTK11 physical-frame validator.
 *
 * Fields 0..281 preserve the V10 layout. Fields 282..310 are the explicit
 * renderer/Swappy/SurfaceControl latch-conjunction appendix.
 */
object NtkSchema11FrameValidator {
    const val FIELD_COUNT = 311
    const val V10_PREFIX_FIELD_COUNT = NtkSchema10FrameValidator.FIELD_COUNT
    const val NINETY_HZ_PERIOD_NANOS =
        NtkSchema10FrameValidator.NINETY_HZ_PERIOD_NANOS
    const val REFRESH_TOLERANCE_NANOS =
        NtkSchema10FrameValidator.REFRESH_TOLERANCE_NANOS

    @JvmStatic
    fun violation(v: LongArray): String? {
        if (v.size != FIELD_COUNT) return "field-count:${v.size}:$FIELD_COUNT"
        if (v[83] != 11L) return "schema-version:${v[83]}:11"

        // The V10 prefix retains the exact prior retirement+latch JOIN.
        val prefix = v.copyOfRange(0, V10_PREFIX_FIELD_COUNT)
        prefix[83] = 10L
        NtkSchema10FrameValidator.violation(prefix)?.let {
            return "v10-prefix:$it"
        }

        val successful = v[282]
        val latched = v[283]
        val lost = v[284]
        val rendererUnlatched = v[285]
        if (successful <= 0L || latched < 0L || lost != 0L ||
            rendererUnlatched != 1L ||
            successful != latched + lost + rendererUnlatched ||
            v[286] != 1L || v[287] != 0L || v[288] != 1L
        ) return "renderer-post-submit"

        val hasPrior = v[238] == 1L
        if (!hasPrior) {
            // JOIN_OPEN is real for the bootstrap frame too: Swappy publishes the
            // first opportunity only after the renderer observes its exact wake.
            // Only the predecessor-specific latch fields are empty.
            if ((289..295).any { v[it] != 0L } ||
                v[296] != v[137] || v[296] <= 0L ||
                v[297] != 0L || v[298] != 0L
            ) {
                return "swappy-first-stage-retirement"
            }
        } else {
            if (v[289] != 1L || v[290] != 1L || v[291] !in 0L..1L ||
                v[292] != 2L || v[296] != v[137] ||
                v[256] > v[296] || v[296] > v[88] || v[88] > v[34] ||
                v[293] <= 0L || v[294] <= 0L || v[295] < v[294] ||
                v[293] != v[267] || v[294] != v[268] ||
                v[295] != v[269] || v[295] > v[34] ||
                v[297] != v[296] - v[295] || v[298] != 0L
            ) return "swappy-prior-observation"
        }

        if (v[299] != v[273] || v[299] <= 0L ||
            v[301] != 1L || v[302] != 1L ||
            v[303] != 1L || v[304] != 1L ||
            v[305] != 1L || v[306] !in 1L..8L ||
            v[307] != 0L || v[308] < 0L || v[310] !in 0L..1L
        ) return "surface-latch-watermark"
        if (!hasPrior) {
            if (v[300] != 0L || v[309] != 0L || v[310] != 0L) {
                return "surface-first-stage-watermark"
            }
        } else if (v[300] != v[240] || v[300] <= 0L ||
            v[309] != v[34] - v[295] || v[309] < 0L
        ) {
            return "surface-prior-latch-watermark"
        }
        return null
    }

    internal fun postApplyViolation(v: LongArray): String? {
        if (v.size != FIELD_COUNT) return "field-count:${v.size}:$FIELD_COUNT"
        val prefix = v.copyOfRange(0, V10_PREFIX_FIELD_COUNT)
        prefix[83] = 10L
        return NtkSchema10FrameValidator.postApplyViolation(prefix)
    }
}

internal object NtkSchema11PostApplyConservation {
    fun isExact(frame: NtkStripRenderEngine.FrameSnapshot): Boolean =
        NtkSchema11FrameValidator.postApplyViolation(frame.schema11Values) == null
}
