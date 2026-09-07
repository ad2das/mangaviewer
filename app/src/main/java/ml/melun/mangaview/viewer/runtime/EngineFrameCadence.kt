package ml.melun.mangaview.viewer.runtime

import kotlin.math.ceil

/** The GL owner may consume a waiting scene once the previous frame's interval has elapsed. */
internal object EngineFrameCadence {
    fun due(lastStartedNanos: Long, nowNanos: Long, refreshRate: Float): Boolean {
        require(lastStartedNanos >= 0 && nowNanos >= lastStartedNanos)
        require(refreshRate.isFinite() && refreshRate > 0F)
        if (lastStartedNanos == 0L) return true
        val interval = ceil(1_000_000_000.0 / refreshRate).toLong().coerceAtLeast(1L)
        return nowNanos - lastStartedNanos >= interval
    }
}
