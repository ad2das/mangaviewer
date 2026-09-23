package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.runtime.EngineTileTimingObserver

/** One tile's demand and residency instants; [residentAtNanos] stays 0 until its pixels exist. */
internal data class EngineTileTiming(
    val tile: EngineTileSpec,
    val priority: WorkPriority,
    val demandedAtNanos: Long,
    val residentAtNanos: Long,
)

/**
 * Bounded ledger of per-tile demand and residency instants. The render owner records a demand on its
 * own thread and a texture accept arrives from the work thread, so every access is synchronized. The
 * ring is diagnostic: it never gates, delays, or reorders work, and the oldest record is dropped
 * once the capacity is reached.
 */
internal class EngineTileTimingLedger(private val capacity: Int = 4_096) : EngineTileTimingObserver {
    init { require(capacity > 0) }
    private val records = LinkedHashMap<EngineTileSpec, EngineTileTiming>()

    @Synchronized override fun tileDemanded(tile: EngineTileSpec, priority: WorkPriority, atNanos: Long) {
        if (records.containsKey(tile)) return
        evictIfFull()
        records[tile] = EngineTileTiming(tile, priority, atNanos, 0L)
    }

    @Synchronized override fun tileResident(tile: EngineTileSpec, atNanos: Long) {
        val existing = records[tile]
        if (existing == null) {
            evictIfFull()
            records[tile] = EngineTileTiming(tile, WorkPriority.VISIBLE, 0L, atNanos)
            return
        }
        if (existing.residentAtNanos != 0L) return
        records[tile] = existing.copy(residentAtNanos = atNanos)
    }

    @Synchronized fun snapshot(): List<EngineTileTiming> = records.values.toList()

    private fun evictIfFull() {
        if (records.size >= capacity) records.remove(records.keys.first())
    }
}
