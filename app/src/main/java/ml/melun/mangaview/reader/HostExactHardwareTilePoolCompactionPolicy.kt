package ml.melun.mangaview.reader

/**
 * Pixel geometry of the host-emulator display cache.
 *
 * The rolling native surface is deliberately 800 pixels wide. Keeping a 1,403-4,096 pixel
 * decoded original in the display cache and then asking GL to shrink it back to 800 duplicated
 * memory and upload work without adding a displayable pixel. Encoded originals remain the
 * rehydration authority; this policy sizes only the immutable native display copy.
 */
internal object HostExactDisplayStorageGeometry {
    const val TARGET_WIDTH_PX = 800
    private const val HEIGHT_BUCKET_PX = 128

    fun contentWidth(sourceWidth: Int): Int = when {
        sourceWidth <= 0 -> 0
        else -> minOf(sourceWidth, TARGET_WIDTH_PX)
    }

    fun contentHeight(sourceWidth: Int, sourceHeight: Int): Int {
        val width = contentWidth(sourceWidth)
        if (width <= 0 || sourceHeight <= 0) return 0
        val numerator = sourceHeight.toLong() * width.toLong()
        return ((numerator + sourceWidth - 1L) / sourceWidth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun capacityWidth(sourceWidth: Int): Int =
        if (sourceWidth > 0) TARGET_WIDTH_PX else 0

    fun capacityHeight(sourceWidth: Int, logicalTileHeight: Int): Int {
        val content = contentHeight(sourceWidth, logicalTileHeight)
        if (content <= 0) return 0
        return (((content.toLong() + HEIGHT_BUCKET_PX - 1L) / HEIGHT_BUCKET_PX) *
            HEIGHT_BUCKET_PX)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}

/**
 * A small set of reusable host-buffer width classes.
 *
 * Exact 256-pixel rounding produced six mutually incompatible pools while ten adjacent chapters
 * moved through 1024, 1536, 1792, 2048, 2816, 3328 and 4096-pixel sources. The pixels copied into
 * a slot still retain their exact source width; only unused row capacity is shared. Four classes
 * keep common 850px pages compact while still letting nearby chapters reuse retired storage.
 */
internal object HostExactHardwareTileCapacityPolicy {
    private const val COMPACT_WIDTH = 1_024
    private const val NARROW_WIDTH = 1_536
    private const val MEDIUM_WIDTH = 2_048
    private const val WIDE_WIDTH = 4_096
    private const val FALLBACK_BUCKET = 256

    fun capacityWidth(sourceWidth: Int): Int = when {
        sourceWidth <= 0 -> sourceWidth
        sourceWidth <= COMPACT_WIDTH -> COMPACT_WIDTH
        sourceWidth <= NARROW_WIDTH -> NARROW_WIDTH
        sourceWidth <= MEDIUM_WIDTH -> MEDIUM_WIDTH
        sourceWidth <= WIDE_WIDTH -> WIDE_WIDTH
        sourceWidth > Int.MAX_VALUE - (FALLBACK_BUCKET - 1) -> sourceWidth
        else -> ((sourceWidth + FALLBACK_BUCKET - 1) / FALLBACK_BUCKET) * FALLBACK_BUCKET
    }

}

/** Pure selection policy for reclaiming idle, unusable host HardwareBuffer slots. */
internal object HostExactHardwareTilePoolCompactionPolicy {
    /**
     * Returns idle slot indexes whose removal creates enough headroom for [requiredBytes].
     *
     * The caller invokes this only after proving that no idle slot can satisfy the requested
     * geometry. Smallest slots are retired first so a wider idle slot remains available for a
     * later page. An incomplete plan is rejected: waiting for another owner to retire is safer
     * than destroying useful storage without making the blocked allocation possible.
     */
    fun idleVictimIndexes(
        slotBytes: LongArray,
        slotInUse: BooleanArray,
        allocatedBytes: Long,
        requiredBytes: Long,
        maxBytes: Long,
    ): IntArray {
        if (slotBytes.size != slotInUse.size || allocatedBytes < 0L || requiredBytes <= 0L ||
            maxBytes <= 0L || allocatedBytes > maxBytes || requiredBytes > maxBytes
        ) return IntArray(0)
        val headroom = maxBytes - allocatedBytes
        if (requiredBytes <= headroom) return IntArray(0)
        val bytesToFree = requiredBytes - headroom
        val idle = slotBytes.indices
            .filter { index -> !slotInUse[index] && slotBytes[index] > 0L }
            .sortedWith(compareBy<Int> { slotBytes[it] }.thenBy { it })
        var freed = 0L
        var count = 0
        while (count < idle.size && freed < bytesToFree) {
            val bytes = slotBytes[idle[count]]
            if (freed > Long.MAX_VALUE - bytes) return IntArray(0)
            freed += bytes
            count += 1
        }
        return if (freed >= bytesToFree) {
            IntArray(count) { position -> idle[position] }
        } else {
            IntArray(0)
        }
    }
}

/** Bounded drawable window retained while the host exact-storage pool is physically full. */
internal object HostExactHardwareTilePoolPressurePolicy {
    private const val BEHIND_PAGES = 1
    private const val AHEAD_PAGES = 4

    fun shouldDeferOffscreenAdjacentRunwayDecode(
        hostGpuRuntime: Boolean,
        directWifiStrictAdjacent: Boolean,
        predecessorIsLaunchEpisode: Boolean,
        viewportAnchor: Int,
        adjacentStart: Int,
        nearBoundaryPages: Int,
    ): Boolean = hostGpuRuntime && directWifiStrictAdjacent && !predecessorIsLaunchEpisode &&
        viewportAnchor >= 0 &&
        adjacentStart > viewportAnchor && nearBoundaryPages >= 0 &&
        adjacentStart - viewportAnchor > nearBoundaryPages

    /**
     * A pressure retirement is newer than every decode which started before it. The marker is
     * consumed only by the physical re-entry path before that path requests a replacement, so a
     * result which arrives while the marker is still present is necessarily stale. Publishing it
     * would cancel the queued Surface clear and keep its host slot owned indefinitely.
     */
    fun mayPublishDecodedPixels(pressureRetirementPending: Boolean): Boolean =
        !pressureRetirementPending

    fun retainedWindow(pageCount: Int, anchor: Int): IntArray {
        if (pageCount <= 0) return intArrayOf(0, -1)
        val safeAnchor = anchor.coerceIn(0, pageCount - 1)
        return intArrayOf(
            (safeAnchor - BEHIND_PAGES).coerceAtLeast(0),
            (safeAnchor + AHEAD_PAGES).coerceAtMost(pageCount - 1),
        )
    }

    /**
     * Layout-height publications can move the reported anchor while the same physical pixels are
     * still on screen. Pool pressure must therefore retain both the directional runway and the
     * exact Surface viewport. Otherwise an off-screen height update can make the pressure callback
     * retire a page that is physically visible, producing a decode/retire loop.
     */
    fun retainedWindowIncludingPhysicalViewport(
        pageCount: Int,
        anchor: Int,
        physicalVisibleFirst: Int,
        physicalVisibleLast: Int,
    ): IntArray {
        val retained = retainedWindow(pageCount, anchor)
        if (pageCount <= 0 || physicalVisibleFirst < 0 ||
            physicalVisibleLast < physicalVisibleFirst
        ) return retained
        val first = physicalVisibleFirst.coerceIn(0, pageCount - 1)
        val last = physicalVisibleLast.coerceIn(first, pageCount - 1)
        return intArrayOf(minOf(retained[0], first), maxOf(retained[1], last))
    }

    /** A pressure-retired page is decoded again only when real viewport pixels intersect it. */
    fun isPhysicalRehydrateEligible(
        pageIndex: Int,
        physicalVisibleFirst: Int,
        physicalVisibleLast: Int,
    ): Boolean = pageIndex >= 0 && physicalVisibleFirst >= 0 &&
        physicalVisibleLast >= physicalVisibleFirst &&
        pageIndex in physicalVisibleFirst..physicalVisibleLast

    /**
     * Protects the real viewport plus one page of hysteresis on either edge. A MOVE or an
     * authoritative-height install can carry pixels into that immediate neighbour before its
     * resulting WindowEvent reaches the decode actor. Keeping one frontier closes that unavoidable
     * producer/consumer interval while remaining much narrower than the speculative proof runway.
     */
    fun physicalViewportWithImmediateInputFrontier(
        pageCount: Int,
        physicalVisibleFirst: Int,
        physicalVisibleLast: Int,
        requestedFirst: Int,
        requestedLast: Int,
    ): IntArray {
        if (pageCount <= 0 || physicalVisibleFirst < 0 ||
            physicalVisibleLast < physicalVisibleFirst
        ) return intArrayOf(0, -1)
        val first = physicalVisibleFirst.coerceIn(0, pageCount - 1)
        val last = physicalVisibleLast.coerceIn(first, pageCount - 1)
        if (requestedFirst < 0 || requestedLast < requestedFirst) {
            return intArrayOf(first, last)
        }
        return intArrayOf(
            (first - 1).coerceAtLeast(0),
            (last + 1).coerceAtMost(pageCount - 1),
        )
    }

    /**
     * A real forward gesture may stop one structural transition card before the first retired body
     * page. The drawable-prefix cap keeps that blank page off screen, so the ordinary physical
     * viewport can never intersect it and cannot open rehydration on its own. Extend only across
     * that single card (at most two display indexes from the visible tail); distant decode runway
     * pages remain parked and consume no host slots.
     */
    fun physicalViewportIncludingBlockedForwardBody(
        pageCount: Int,
        physicalVisibleFirst: Int,
        physicalVisibleLast: Int,
        blockedForwardPage: Int,
    ): IntArray {
        if (pageCount <= 0 || physicalVisibleFirst < 0 ||
            physicalVisibleLast < physicalVisibleFirst
        ) return intArrayOf(0, -1)
        val first = physicalVisibleFirst.coerceIn(0, pageCount - 1)
        val last = physicalVisibleLast.coerceIn(first, pageCount - 1)
        val maximumBodyFrontier = (last + 2).coerceAtMost(pageCount - 1)
        val intentLast = if (blockedForwardPage in last..maximumBodyFrontier) {
            blockedForwardPage
        } else {
            last
        }
        return intArrayOf(first, intentLast)
    }

    /**
     * Orders only off-screen cache pages for an allocation-forced retirement. Pixels behind the
     * active reading direction go first; if more storage is required, the farthest speculative
     * runway page goes before a nearer one. The caller stops as soon as the requested byte count
     * has been selected.
     */
    fun retirementOrder(
        candidateIndexes: IntArray,
        visibleFirst: Int,
        visibleLast: Int,
        directionHint: Int,
    ): IntArray {
        if (visibleFirst < 0 || visibleLast < visibleFirst) return IntArray(0)
        val forward = directionHint >= 0
        return candidateIndexes
            .asSequence()
            .distinct()
            .filter { index -> index < visibleFirst || index > visibleLast }
            .sortedWith(
                compareBy<Int> { index ->
                    if (forward) {
                        if (index < visibleFirst) 0 else 1
                    } else {
                        if (index > visibleLast) 0 else 1
                    }
                }.thenByDescending { index ->
                    if (index < visibleFirst) visibleFirst - index else index - visibleLast
                },
            )
            .toList()
            .toIntArray()
    }

    /**
     * A tail-only structure publication leaves every index below its captured start unchanged.
     * Host-pool pressure may therefore clear an already-published prefix page while the tail's
     * drawable is still being decoded. Requiring one append start for every pending structure
     * owner fails closed when any prepend/removal/reorder overlaps this interval.
     */
    fun canPublishStablePrefixRetirement(
        pendingStructurePublishes: Int,
        appendOnlyStablePrefixCounts: IntArray,
        targetIndexes: IntArray,
    ): Boolean {
        if (pendingStructurePublishes <= 0 || targetIndexes.isEmpty() ||
            appendOnlyStablePrefixCounts.size != pendingStructurePublishes ||
            appendOnlyStablePrefixCounts.any { it < 0 } || targetIndexes.any { it < 0 }
        ) return false
        val stablePrefixCount = appendOnlyStablePrefixCounts.minOrNull() ?: return false
        return targetIndexes.all { index -> index < stablePrefixCount }
    }
}
