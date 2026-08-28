package ml.melun.mangaview.reader

/**
 * Resolves pixels needed while the compositor deliberately holds its last complete frame.
 *
 * The reported physical range can lag the logical scroll anchor when a reverse gesture enters a
 * retired page. Decoding only that old physical range creates a circular wait: no new pixels are
 * requested until a new frame is presented, and no new frame can be presented without pixels.
 */
internal object NtkStrictRollingDecodeDemandPolicy {
    fun bridgePhysicalFrameToLogicalAnchor(
        physicalVisibleRange: IntRange,
        logicalAnchor: Int,
        pageCount: Int,
    ): IntRange {
        if (pageCount <= 0) return IntRange.EMPTY
        val last = pageCount - 1
        val boundedAnchor = logicalAnchor.coerceIn(0, last)
        if (physicalVisibleRange.isEmpty()) return boundedAnchor..boundedAnchor
        val boundedPhysicalFirst = physicalVisibleRange.first.coerceIn(0, last)
        val boundedPhysicalLast = physicalVisibleRange.last.coerceIn(0, last)
        return minOf(boundedPhysicalFirst, boundedAnchor)..
            maxOf(boundedPhysicalLast, boundedAnchor)
    }
}
