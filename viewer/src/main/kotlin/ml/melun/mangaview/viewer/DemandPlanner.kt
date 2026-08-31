package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import kotlin.math.ceil

data class PageDemand(
    val pageId: PageId,
    val priority: WorkPriority,
    val distanceUnits: Long,
    val index: Int,
    val decodeBand: PixelBand? = null,
)

class DemandPlanner(
    private val windowPolicy: PixelWindowPolicy = PixelWindowPolicy(),
    private val bandGrid: PixelBandGrid = PixelBandGrid(),
    private val startupBandGrid: PixelBandGrid = PixelBandGrid(
        maximumDisplayBandHeight = 256,
        maximumWholePageDisplayHeight = 0,
    ),
) {
    fun plan(state: ViewerState): List<PageDemand> {
        val window = windowPolicy.window(state)
        val visible = indices(state, window.visibleStartUnits, window.visibleEndUnits)
        val retained = indices(state, window.retainedStartUnits, window.retainedEndUnits)
        val direction = if (state.velocityUnitsPerSecond < 0L) -1 else 1
        val focus = viewportCenterIndex(state, window, visible)
        val planned = orderedIndices(visible, retained, focus, direction).map { index ->
            demandFor(state, window, visible, focus, direction, index)
        }
        if (direction < 0) return planned
        return promoteNextEpisodeRunway(state, planned)
    }

    private fun promoteNextEpisodeRunway(
        state: ViewerState,
        planned: List<PageDemand>,
    ): List<PageDemand> {
        val currentManifest = state.manifests.indexOfFirst { it.id == state.currentEpisodeId }
        val next = state.manifests.getOrNull(currentManifest + 1) ?: return planned
        var runwayUnits = 0L
        val runway = buildList {
            for (page in next.pages) {
                val index = state.layout.indexOf(page.id) ?: break
                add(page.id)
                runwayUnits = saturatingAdd(runwayUnits, state.layout.entries[index].height.units)
                if (runwayUnits >= saturatingMultiplyNonNegative(
                        state.viewport.height.units,
                        NEXT_EPISODE_RAW_RUNWAY_SCREENFULS,
                    )
                ) break
            }
        }
        if (runway.isEmpty()) return planned
        val byPage = planned.associateBy(PageDemand::pageId)
        val promoted = runway.map { pageId ->
            byPage[pageId] ?: run {
                val index = requireNotNull(state.layout.indexOf(pageId))
                val pageTop = state.layout.topAt(index).units
                PageDemand(
                    pageId = pageId,
                    priority = WorkPriority.WARM,
                    distanceUnits = (pageTop - state.scroll.contentOffset.units).coerceAtLeast(0L),
                    index = index,
                    decodeBand = null,
                )
            }
        }
        val promotedIds = runway.toSet()
        val remaining = planned.filterNot { it.pageId in promotedIds }
        val insertion = remaining.indexOfFirst { it.distanceUnits > 0L }
            .takeIf { it >= 0 } ?: remaining.size
        return remaining.take(insertion) + promoted + remaining.drop(insertion)
    }

    private fun viewportCenterIndex(state: ViewerState, window: PixelWindow, visible: IntRange): Int? {
        if (visible.isEmpty()) return null
        val center = saturatingAdd(
            window.visibleStartUnits,
            (window.visibleEndUnits - window.visibleStartUnits) / 2L,
        )
        val pageId = state.layout.pageAt(FixedPx(center)) ?: return null
        return state.layout.indexOf(pageId)
    }

    private fun orderedIndices(
        visible: IntRange,
        retained: IntRange,
        focus: Int?,
        direction: Int,
    ): List<Int> {
        if (retained.isEmpty()) return emptyList()
        if (visible.isEmpty()) return directional(retained, direction)
        val center = requireNotNull(focus).coerceIn(visible.first, visible.last)
        val behind = untilRange(retained.first, visible.first)
        val ahead = fromRange(visible.last + 1, retained.last)
        return if (direction > 0) {
            (center..visible.last).toList() +
                untilRange(visible.first, center).reversed() +
                ahead.toList() +
                behind.reversed()
        } else {
            (visible.first..center).reversed() +
                fromRange(center + 1, visible.last).toList() +
                behind.reversed() +
                ahead.toList()
        }
    }

    private fun demandFor(
        state: ViewerState,
        window: PixelWindow,
        visible: IntRange,
        focus: Int?,
        direction: Int,
        index: Int,
    ): PageDemand {
        val hard = index == focus
        val bounds = bounds(state.layout, index)
        val viewportCenter = saturatingAdd(
            window.visibleStartUnits,
            (window.visibleEndUnits - window.visibleStartUnits) / 2L,
        )
        val decodeBand = if (hard) {
            missingBand(
                state,
                index,
                window.visibleStartUnits,
                window.visibleEndUnits,
                direction,
                viewportCenter,
                true,
            )
                ?: missingBand(state, index, window.retainedStartUnits, window.retainedEndUnits, direction)
        } else if (index in visible) {
            missingBand(
                state,
                index,
                window.visibleStartUnits,
                window.visibleEndUnits,
                direction,
                viewportCenter,
                true,
            ) ?: missingBand(state, index, window.retainedStartUnits, window.retainedEndUnits, direction)
        } else {
            missingBand(state, index, window.retainedStartUnits, window.retainedEndUnits, direction)
        }
        return PageDemand(
            pageId = state.pageOrder[index],
            priority = if (hard) WorkPriority.HARD else WorkPriority.WARM,
            distanceUnits = distanceFromViewport(bounds, window),
            index = index,
            decodeBand = decodeBand,
        )
    }

    private fun missingBand(
        state: ViewerState,
        index: Int,
        contentStart: Long,
        contentEnd: Long,
        direction: Int,
        preferredContentUnits: Long? = null,
        fastFirstVisibleSlice: Boolean = false,
    ): PixelBand? {
        val runtime = state.pages.getValue(state.pageOrder[index])
        if (runtime.encoded == null) return null
        val dimensions = dimensions(state, index, runtime) ?: return null
        val bounds = bounds(state.layout, index)
        val intersectionStart = maxOf(bounds.first, contentStart)
        val pageEnd = saturatingAdd(bounds.last, 1L)
        val intersectionEnd = minOf(pageEnd, contentEnd)
        if (intersectionStart >= intersectionEnd) return null
        val pageUnits = pageEnd - bounds.first
        val sourceStart = sourceCoordinate(intersectionStart - bounds.first, pageUnits, dimensions.heightPx, false)
        val sourceEnd = sourceCoordinate(intersectionEnd - bounds.first, pageUnits, dimensions.heightPx, true)
            .coerceAtLeast(sourceStart + 1)
        val requestedWidth = ceil(state.viewport.width.toPixels())
            .coerceIn(1.0, Int.MAX_VALUE.toDouble())
            .toInt()
        // The first useful frame is always one full-quality viewport slice. Whether the finger
        // happens to be down on the exact manifest-completion frame must not turn that first
        // decode into an entire manga page. Once any pixels exist, normal whole-page/band
        // planning resumes and fills the remainder without reducing resolution.
        val grid = if (state.residentPageIds.isEmpty() ||
            fastFirstVisibleSlice && runtime.pixel == null
        ) {
            startupBandGrid
        } else {
            bandGrid
        }
        val candidates = grid.bandsIntersecting(
            dimensions,
            sourceStart,
            sourceEnd,
            requestedWidth,
        )
        val preferredSource = preferredContentUnits?.let { preferred ->
            val local = preferred.coerceIn(intersectionStart, intersectionEnd - 1L) - bounds.first
            sourceCoordinate(local, pageUnits, dimensions.heightPx, false)
        }
        val ordered = orderBands(candidates, direction, preferredSource)
        return ordered.firstNotNullOfOrNull { candidate ->
            missingSubBand(candidate, runtime.pixel, direction, preferredSource)
        }
    }

    private fun missingSubBand(
        candidate: PixelBand,
        pixel: PixelRef?,
        direction: Int,
        preferredSource: Int?,
    ): PixelBand? {
        var missing = listOf(candidate.sourceTopPx until candidate.sourceBottomPx)
        pixel?.tiles
            ?.filter { tile -> tile.displayWidthPx >= candidate.displayWidthPx }
            ?.forEach { tile ->
                missing = missing.flatMap { range -> subtract(range, tile.sourceTopPx, tile.sourceBottomPx) }
            }
        if (missing.isEmpty()) return null
        val selected = if (preferredSource == null) {
            if (direction > 0) missing.first() else missing.last()
        } else if (direction > 0) {
            missing.firstOrNull { it.last >= preferredSource } ?: missing.first()
        } else {
            missing.lastOrNull { it.first <= preferredSource } ?: missing.last()
        }
        return PixelBand(selected.first, selected.last + 1, candidate.displayWidthPx)
    }

    private fun subtract(range: IntRange, coveredStart: Int, coveredEnd: Int): List<IntRange> {
        if (coveredEnd <= range.first || coveredStart > range.last) return listOf(range)
        return buildList(2) {
            if (range.first < coveredStart) add(range.first until minOf(coveredStart, range.last + 1))
            if (coveredEnd <= range.last) add(maxOf(coveredEnd, range.first)..range.last)
        }
    }

    private fun orderBands(
        candidates: List<PixelBand>,
        direction: Int,
        preferredSource: Int?,
    ): List<PixelBand> {
        if (preferredSource == null || candidates.size < 2) {
            return if (direction > 0) candidates else candidates.asReversed()
        }
        val focus = candidates.indexOfFirst { band ->
            preferredSource >= band.sourceTopPx && preferredSource < band.sourceBottomPx
        }.takeIf { it >= 0 } ?: return if (direction > 0) candidates else candidates.asReversed()
        return if (direction > 0) {
            candidates.subList(focus, candidates.size) + candidates.subList(0, focus).asReversed()
        } else {
            candidates.subList(0, focus + 1).asReversed() + candidates.subList(focus + 1, candidates.size)
        }
    }

    private fun dimensions(state: ViewerState, index: Int, runtime: PageRuntime): PageDimensions? =
        runtime.encoded?.dimensions ?: state.layout.entries[index].resolvedDimensions ?: runtime.spec.dimensions

    private fun sourceCoordinate(local: Long, pageUnits: Long, height: Int, roundUp: Boolean): Int {
        val units = if (roundUp) {
            multiplyDivideCeilExact(local, height.toLong(), pageUnits)
        } else {
            multiplyDivideFloorExact(local, height.toLong(), pageUnits)
        }
        return units.coerceIn(0L, height.toLong()).toInt()
    }

    private fun indices(state: ViewerState, start: Long, end: Long): IntRange =
        state.layout.indicesIntersecting(FixedPx(start), FixedPx(end))

    private fun bounds(ledger: LayoutLedger, index: Int): LongRange {
        val top = ledger.topAt(index).units
        return top until Math.addExact(top, ledger.entries[index].height.units)
    }

    private fun distanceFromViewport(bounds: LongRange, window: PixelWindow): Long = when {
        bounds.last < window.visibleStartUnits -> window.visibleStartUnits - bounds.last
        bounds.first >= window.visibleEndUnits -> bounds.first - window.visibleEndUnits
        else -> 0L
    }

    private fun directional(range: IntRange, direction: Int): List<Int> =
        if (direction > 0) range.toList() else range.reversed().toList()

    private fun untilRange(start: Int, endExclusive: Int): IntRange =
        if (start < endExclusive) start until endExclusive else IntRange.EMPTY

    private fun fromRange(start: Int, endInclusive: Int): IntRange =
        if (start <= endInclusive) start..endInclusive else IntRange.EMPTY

    private companion object {
        const val NEXT_EPISODE_RAW_RUNWAY_SCREENFULS = 6
    }

}
