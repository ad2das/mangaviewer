package ml.melun.mangaview.engine.runtime

/**
 * Retired per-tile stage probe.
 *
 * It attributed demand->resident to pipeline stages while the tile chain was being folded; the
 * remaining measured metric is the tile ledger's own demand-to-resident interval, so this hook is
 * kept only as the call-site contract and records nothing. Every record() is a no-op: the tile
 * object was a strong key in a concurrent map, and its data-class hashCode ran on every stage of
 * the hot path.
 */
object EngineStageProbe {
    const val DEMAND = 0
    const val WORK_ENTER = 1
    const val PAGE_READY = 2
    const val DECODE_ENTER = 3
    const val DECODE_DONE = 4
    const val PIXELS_READY = 5
    const val UPLOAD_POST = 6
    const val UPLOAD_DONE = 7
    const val RESIDENT = 8
    const val UPLOAD_ENTER = 9
    const val SUBMIT_DONE = 10
    const val UPLOAD_EXIT = 11
    const val DELIVERY_POSTED = 12
    const val ACCEPT_ENTER = 13
    const val PERMIT_UPLOAD = 14
    const val STAGES = 15

    class Row internal constructor(val identity: Int) {
        val at = LongArray(STAGES)
        @Volatile var pageId: String = ""
        @Volatile var priority: String = ""
    }

    fun record(identity: Any, stage: Int, nanos: Long, pageId: String? = null, priority: String? = null) = Unit

    fun snapshot(): List<Row> = emptyList()

    fun reset() = Unit
}
