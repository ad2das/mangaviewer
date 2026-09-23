package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.WorkPriority

/**
 * Per-tile timing provenance: when the render path first asked for a tile and when its pixels became
 * resident. Diagnostic only — an implementation must never block, throw, or affect work order.
 */
interface EngineTileTimingObserver {
    fun tileDemanded(tile: EngineTileSpec, priority: WorkPriority, atNanos: Long)
    fun tileResident(tile: EngineTileSpec, atNanos: Long)
}

object NoopEngineTileTimingObserver : EngineTileTimingObserver {
    override fun tileDemanded(tile: EngineTileSpec, priority: WorkPriority, atNanos: Long) = Unit
    override fun tileResident(tile: EngineTileSpec, atNanos: Long) = Unit
}
