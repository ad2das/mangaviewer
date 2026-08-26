package ml.melun.mangaview.reader

/** Keeps offscreen exact HardwareBuffer writes out of a physical manga motion interval. */
internal object NtkAdjacentExactDecodeMotionPolicy {
    fun shouldDefer(
        directWifiRunwayProfile: Boolean,
        reason: String,
        adjacentManhwaPages: Boolean,
    ): Boolean = directWifiRunwayProfile &&
        (reason == "initial_strict_source" || adjacentManhwaPages)
}
