package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId

data class PagePlacement(
    val ordinal: Int,
    val pageId: PageId,
    val top: FixedPx,
    val height: FixedPx,
    val pixel: PixelRef?,
)

/** A non-content loading surface that gives immediate spatial feedback before a manifest exists. */
data class LoadingPlacement(
    val ordinal: Int,
    val top: FixedPx,
    val height: FixedPx,
    val sourceTopPx: Int = 0,
    val sourceBottomPx: Int = 1,
    val sourceHeightPx: Int = 1,
)

data class FramePlan(
    val generation: Long,
    val viewport: Viewport,
    val scrollOffset: FixedPx,
    val contentHeight: FixedPx,
    val pages: List<PagePlacement>,
    val loading: List<LoadingPlacement> = emptyList(),
    val frameTimelineVsyncId: Long = INVALID_FRAME_TIMELINE_VSYNC_ID,
    val expectedPresentationTimeNanos: Long = 0L,
)

fun interface RenderPort {
    fun submit(framePlan: FramePlan)
}

class FramePlanner(
    private val overscanScreenfuls: Int = 1,
) {
    init {
        require(overscanScreenfuls >= 0) { "Overscan must not be negative" }
    }

    fun plan(state: ViewerState): FramePlan {
        val overscan = saturatingMultiplyNonNegative(state.viewport.height.units, overscanScreenfuls)
        val start = FixedPx(saturatingSubtract(state.scroll.contentOffset.units, overscan).coerceAtLeast(0L))
        val end = FixedPx(saturatingAdd(
            saturatingAdd(state.scroll.contentOffset.units, state.viewport.height.units),
            overscan,
        ))
        val range = state.layout.indicesIntersecting(start, end)
        val placements = range.map { index ->
            val pageId = state.pageOrder[index]
            PagePlacement(
                ordinal = index,
                pageId = pageId,
                top = state.layout.topAt(index),
                height = state.layout.entries[index].height,
                pixel = state.pages.getValue(pageId).pixel,
            )
        }
        val loading = buildList {
            placements.forEach { placement -> appendLoadingGaps(placement, this) }
        }
        return FramePlan(
            generation = state.generation,
            viewport = state.viewport,
            scrollOffset = state.scroll.contentOffset,
            contentHeight = state.layout.totalHeight,
            pages = placements,
            loading = loading,
            frameTimelineVsyncId = state.frameTimelineVsyncId,
            expectedPresentationTimeNanos = state.expectedPresentationTimeNanos,
        )
    }

    fun planScroll(state: ViewerState, previous: FramePlan?): FramePlan {
        val prior = previous ?: return plan(state)
        if (prior.generation != state.generation || prior.viewport != state.viewport ||
            prior.contentHeight != state.layout.totalHeight || prior.pages.isEmpty()
        ) return plan(state)
        val reuseHeadroom = saturatingMultiplyNonNegative(
            state.viewport.height.units,
            minOf(1, overscanScreenfuls),
        )
        val requiredStart = saturatingSubtract(state.scroll.contentOffset.units, reuseHeadroom)
            .coerceAtLeast(0L)
        val requiredEnd = saturatingAdd(
            saturatingAdd(state.scroll.contentOffset.units, state.viewport.height.units),
            reuseHeadroom,
        ).coerceAtMost(state.layout.totalHeight.units)
        val availableStart = prior.pages.first().top.units
        val last = prior.pages.last()
        val availableEnd = saturatingAdd(last.top.units, last.height.units)
        if (availableStart > requiredStart || availableEnd < requiredEnd) return plan(state)
        return prior.copy(
            scrollOffset = state.scroll.contentOffset,
            frameTimelineVsyncId = state.frameTimelineVsyncId,
            expectedPresentationTimeNanos = state.expectedPresentationTimeNanos,
        )
    }

    private fun appendLoadingGaps(
        placement: PagePlacement,
        output: MutableList<LoadingPlacement>,
    ) {
        val pixel = placement.pixel
        if (pixel == null) {
            output += LoadingPlacement(placement.ordinal, placement.top, placement.height)
            return
        }
        val sourceHeight = pixel.dimensions.heightPx
        var cursor = 0
        pixel.tiles.forEach { tile ->
            val start = tile.sourceTopPx.coerceIn(0, sourceHeight)
            val end = tile.sourceBottomPx.coerceIn(start, sourceHeight)
            if (start > cursor) output += loadingGap(placement, cursor, start, sourceHeight)
            cursor = maxOf(cursor, end)
        }
        if (cursor < sourceHeight) {
            output += loadingGap(placement, cursor, sourceHeight, sourceHeight)
        }
    }

    private fun loadingGap(
        placement: PagePlacement,
        sourceTop: Int,
        sourceBottom: Int,
        sourceHeight: Int,
    ): LoadingPlacement {
        val localTop = multiplyDivideFloorExact(placement.height.units, sourceTop, sourceHeight)
        val localBottom = multiplyDivideFloorExact(placement.height.units, sourceBottom, sourceHeight)
        return LoadingPlacement(
            ordinal = placement.ordinal,
            top = FixedPx(Math.addExact(placement.top.units, localTop)),
            height = FixedPx((localBottom - localTop).coerceAtLeast(1L)),
            sourceTopPx = sourceTop,
            sourceBottomPx = sourceBottom,
            sourceHeightPx = sourceHeight,
        )
    }
}

class LoadingFramePlanner(
    private val screenfulCount: Int = 1_000_000,
) {
    init {
        require(screenfulCount >= 2) { "Loading geometry needs room to scroll" }
    }

    fun geometry(viewport: Viewport): LoadingGeometry {
        val pageHeight = viewport.height
        val gap = FixedPx.ZERO
        val stride = pageHeight + gap
        return LoadingGeometry(
            viewport = viewport,
            contentHeight = FixedPx(saturatingMultiplyNonNegative(stride.units, screenfulCount)),
            pageHeight = pageHeight,
            gap = gap,
            stride = stride,
            screenfulCount = screenfulCount,
        )
    }

    fun plan(
        geometry: LoadingGeometry,
        scrollOffset: FixedPx,
        frameTimelineVsyncId: Long = INVALID_FRAME_TIMELINE_VSYNC_ID,
        expectedPresentationTimeNanos: Long = 0L,
    ): FramePlan {
        val offset = scrollOffset.coerceIn(FixedPx.ZERO, geometry.maximumOffset)
        return FramePlan(
            generation = LOADING_GENERATION,
            viewport = geometry.viewport,
            scrollOffset = offset,
            contentHeight = geometry.contentHeight,
            pages = emptyList(),
            loading = visiblePlacements(geometry, offset),
            frameTimelineVsyncId = frameTimelineVsyncId,
            expectedPresentationTimeNanos = expectedPresentationTimeNanos,
        )
    }

    private fun visiblePlacements(
        geometry: LoadingGeometry,
        offset: FixedPx,
    ): List<LoadingPlacement> {
        val overscan = geometry.viewport.height.units
        val start = (offset.units - overscan).coerceAtLeast(0L)
        val end = saturatingAdd(offset.units, saturatingAdd(overscan, overscan))
        val lastIndex = geometry.screenfulCount - 1
        val first = (start / geometry.stride.units).toInt().coerceIn(0, lastIndex)
        val last = (end / geometry.stride.units).toInt().coerceIn(first, lastIndex)
        return (first..last).map { index ->
            LoadingPlacement(
                ordinal = index,
                top = FixedPx(geometry.stride.units * index.toLong() + geometry.gap.units / 2L),
                height = geometry.pageHeight,
            )
        }
    }

    companion object {
        const val LOADING_GENERATION = Long.MAX_VALUE
    }
}

data class LoadingGeometry(
    val viewport: Viewport,
    val contentHeight: FixedPx,
    val pageHeight: FixedPx,
    val gap: FixedPx,
    val stride: FixedPx,
    val screenfulCount: Int,
) {
    val maximumOffset: FixedPx
        get() = FixedPx((contentHeight.units - viewport.height.units).coerceAtLeast(0L))
}
