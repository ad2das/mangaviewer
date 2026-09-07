package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LayoutLedger
import ml.melun.mangaview.viewer.saturatingMultiplyNonNegative

enum class DemandClass {
    RESUME_ANCHOR,
    VISIBLE,
    CURRENT_FORWARD_NEAR,
    ADJACENT_PREFIX,
    CURRENT_FORWARD_FAR,
    CURRENT_BEHIND_NEAR,
    BEHIND,
}

data class PageDemand(
    val pageId: PageId,
    val demandClass: DemandClass,
    val distance: FixedPx,
    val ordinal: Int,
    val sourceRange: SourceRangeFraction?,
)

data class SourceRangeFraction(
    val startQ32: Long,
    val endQ32: Long,
) {
    init {
        require(startQ32 in 0L until SemanticViewportAnchor.Q32_ONE)
        require(endQ32 in 1L..SemanticViewportAnchor.Q32_ONE)
        require(endQ32 > startQ32)
    }
}

data class DemandSnapshot(
    val generation: Long,
    val revision: Long,
    val demands: List<PageDemand>,
    val nextEpisode: EpisodeId? = null,
)

class DemandEngine(
    private val forwardScreens: Int = 6,
) {
    init {
        require(forwardScreens > 0)
    }

    fun snapshot(state: ViewerSessionState): DemandSnapshot {
        val layout = state.layout ?: return DemandSnapshot(
            state.generation,
            state.sceneRevision,
            emptyList(),
        )
        val window = DemandWindow.create(state, layout, forwardScreens)
        val demands = state.timeline.pages.mapIndexed { index, page ->
            demandForPage(state, layout, window, page, index)
        }.sortedWith(
            compareBy<PageDemand> { it.demandClass.ordinal }
                .thenBy { it.distance.units }
                .thenBy { it.ordinal },
        )
        val current = layout.pageAt(state.scroll.contentOffset)?.episodeId
        val next = state.timeline.episodes.firstOrNull { it.manifest.id == current }
            ?.manifest?.nextEpisodeId?.takeIf { state.timeline.episodeIndex(it) == null }
        return DemandSnapshot(state.generation, state.sceneRevision, demands, next)
    }

    private fun demandForPage(
        state: ViewerSessionState,
        layout: LayoutLedger,
        window: DemandWindow,
        page: PageSpec,
        index: Int,
    ): PageDemand {
        val top = layout.topAt(index)
        val bottom = top + layout.entries[index].height
        val demandClass = window.classify(page, top, bottom)
        return PageDemand(
            page.id,
            demandClass,
            window.distance(top, bottom),
            index,
            if (layout.entries[index].resolvedDimensions == null) null else {
                sourceRangeFor(state, demandClass, top, bottom, window)
            },
        )
    }

    private fun sourceRangeFor(
        state: ViewerSessionState,
        demandClass: DemandClass,
        top: FixedPx,
        bottom: FixedPx,
        window: DemandWindow,
    ): SourceRangeFraction? {
        val height = (bottom - top).units
        return when (demandClass) {
            DemandClass.RESUME_ANCHOR -> {
                val savedOffset = state.opening.savedPosition?.offsetInPageUnits ?: 0L
                val start = (savedOffset - state.viewport.height.units / 2L).coerceIn(0L, height)
                sourceRange(start, minOf(height, start + state.viewport.height.units), height)
            }
            DemandClass.VISIBLE -> sourceRange(
                (window.viewportStart.units - top.units).coerceAtLeast(0L),
                (window.visibleForwardEnd.units - top.units).coerceAtMost(height),
                height,
            )
            DemandClass.CURRENT_FORWARD_NEAR -> sourceRange(
                0L,
                (window.forwardEnd.units - top.units).coerceIn(0L, height),
                height,
            )
            DemandClass.ADJACENT_PREFIX ->
                sourceRange(0L, minOf(height, state.viewport.height.units), height)
            DemandClass.CURRENT_BEHIND_NEAR -> sourceRange(
                (window.behindStart.units - top.units).coerceAtLeast(0L),
                (window.viewportStart.units - top.units).coerceAtMost(height),
                height,
            )
            else -> null
        }
    }

    private fun sourceRange(start: Long, end: Long, height: Long): SourceRangeFraction? {
        if (height <= 0L || end <= start) return null
        val startQ32 = ml.melun.mangaview.viewer.multiplyDivideFloorExact(
            start,
            SemanticViewportAnchor.Q32_ONE,
            height,
        ).coerceIn(0L, SemanticViewportAnchor.Q32_ONE - 1L)
        val endQ32 = ml.melun.mangaview.viewer.multiplyDivideFloorExact(
            end,
            SemanticViewportAnchor.Q32_ONE,
            height,
        ).coerceIn(startQ32 + 1L, SemanticViewportAnchor.Q32_ONE)
        return SourceRangeFraction(startQ32, endQ32)
    }
}

private data class DemandWindow(
    val viewportStart: FixedPx,
    val viewportEnd: FixedPx,
    val behindStart: FixedPx,
    val visibleForwardEnd: FixedPx,
    val forwardEnd: FixedPx,
    val currentEpisode: ml.melun.mangaview.core.EpisodeId?,
    val openingAnchor: PageId?,
) {
    fun classify(page: PageSpec, top: FixedPx, bottom: FixedPx): DemandClass = when {
        page.id == openingAnchor -> DemandClass.RESUME_ANCHOR
        bottom > viewportStart && top < viewportEnd -> DemandClass.VISIBLE
        page.id.episodeId == currentEpisode && top >= viewportEnd && top < forwardEnd ->
            DemandClass.CURRENT_FORWARD_NEAR
        page.id.episodeId != currentEpisode && top >= viewportEnd && page.ordinal <= 1 ->
            DemandClass.ADJACENT_PREFIX
        page.id.episodeId == currentEpisode && top >= forwardEnd ->
            DemandClass.CURRENT_FORWARD_FAR
        page.id.episodeId == currentEpisode && bottom <= viewportStart && bottom > behindStart ->
            DemandClass.CURRENT_BEHIND_NEAR
        else -> DemandClass.BEHIND
    }

    fun distance(top: FixedPx, bottom: FixedPx): FixedPx = when {
        top > viewportEnd -> top - viewportEnd
        bottom < viewportStart -> viewportStart - bottom
        else -> FixedPx.ZERO
    }

    companion object {
        fun create(
            state: ViewerSessionState,
            layout: LayoutLedger,
            forwardScreens: Int,
        ): DemandWindow {
            val start = state.scroll.contentOffset
            val end = start + state.viewport.height
            val behindStart = FixedPx((start.units - saturatingMultiplyNonNegative(
                state.viewport.height.units,
                BEHIND_SCREENS,
            )).coerceAtLeast(0L))
            val visibleForwardEnd = end + FixedPx(saturatingMultiplyNonNegative(
                state.viewport.height.units,
                VISIBLE_FORWARD_SCREENS,
            ))
            return DemandWindow(
                start,
                end,
                behindStart,
                visibleForwardEnd,
                end + FixedPx(saturatingMultiplyNonNegative(
                    state.viewport.height.units,
                    forwardScreens,
                )),
                layout.pageAt(start)?.episodeId,
                state.opening.savedPosition?.pageId?.takeIf { !state.opening.applied },
            )
        }

        private const val VISIBLE_FORWARD_SCREENS = 2
        private const val BEHIND_SCREENS = 2
    }
}
