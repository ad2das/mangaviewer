package ml.melun.mangaview.engine.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * TEMPORARY per-tile stage probe used to attribute the demand->resident latency to a pipeline stage.
 *
 * Diagnostic only: it never blocks, never throws, and never reorders work. Every stage keeps its
 * first observation for a tile, so retries and re-demands cannot overwrite the latency under test.
 * This file is removed before the final measured gate run.
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
    const val STAGES = 10

    class Row internal constructor(val identity: Int) {
        val at = LongArray(STAGES)
        @Volatile var pageId: String = ""
        @Volatile var priority: String = ""
    }

    // Keyed by the tile object itself, not its identity hash: identity hashes are reused after a
    // tile is collected, and a reused hash merged two different tiles' stages into one row, which
    // attributed a later tile's WORK_ENTER to an earlier tile's PAGE_READY (pageReady then measured
    // milliseconds for two adjacent statements).
    private val rows = ConcurrentHashMap<Any, Row>()

    fun record(identity: Any, stage: Int, nanos: Long, pageId: String? = null, priority: String? = null) {
        val row = rows[identity] ?: rows.computeIfAbsent(identity) { Row(System.identityHashCode(identity)) }
        if (pageId != null && row.pageId.isEmpty()) row.pageId = pageId
        if (priority != null && row.priority.isEmpty()) row.priority = priority
        if (stage in 0 until STAGES && row.at[stage] == 0L) row.at[stage] = nanos
    }

    fun snapshot(): List<Row> = rows.values.toList()

    fun reset() = rows.clear()
}
