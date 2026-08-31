package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition

/** Read-only evidence derived from one immutable reducer state. */
data class ViewerTelemetrySnapshot(
    val capturedAtNanos: Long,
    val manifests: List<EpisodeManifest>,
    val anchor: ReadingPosition,
    val anchorOrdinal: Int,
    val scrollOffsetUnits: Long,
    val scrollRevision: Long,
    val scrollCause: ScrollMutationCause,
    val userInputRevision: Long,
    val viewportHeightUnits: Long,
    val contentHeightUnits: Long,
    val currentEpisodeProgress: EpisodeProgressTelemetry?,
    val episodeAppends: List<EpisodeAppendTelemetry>,
    val visiblePages: List<VisiblePageTelemetry>,
    val uncoveredViewportUnits: Long,
    val visuallyUncoveredViewportUnits: Long,
    val overlappingViewportUnits: Long,
)

data class EpisodeProgressTelemetry(
    val episodeId: ml.melun.mangaview.core.EpisodeId,
    val pageCount: Int,
    val verifiedCount: Int,
)

data class EpisodeAppendTelemetry(
    val fromEpisodeId: ml.melun.mangaview.core.EpisodeId,
    val targetEpisodeId: ml.melun.mangaview.core.EpisodeId?,
    val terminal: Boolean,
    val hasBoundary: Boolean,
    val retryReason: String?,
)

data class VisiblePageTelemetry(
    val pageId: PageId,
    val globalOrdinal: Int,
    val visibleUnits: Long,
    val coveredUnits: Long,
    val loadingUnits: Long,
    val visualCoveredUnits: Long,
    val overlappingUnits: Long,
    val presented: Boolean,
)

class ViewerTelemetryPlanner {
    fun snapshot(state: ViewerState, capturedAtNanos: Long): ViewerTelemetrySnapshot {
        val viewportStart = state.scroll.contentOffset.units
        val viewportEnd = saturatingAdd(viewportStart, state.viewport.height.units)
        val range = state.layout.indicesIntersecting(FixedPx(viewportStart), FixedPx(viewportEnd))
        val loading = FramePlanner(overscanScreenfuls = 0).plan(state).loading.groupBy { it.ordinal }
        val visible = range.map { index ->
            visiblePage(state, index, viewportStart, viewportEnd, loading[index].orEmpty())
        }
        val covered = visible.fold(0L) { total, page -> saturatingAdd(total, page.coveredUnits) }
        val visuallyCovered = visible.fold(0L) { total, page ->
            saturatingAdd(total, page.visualCoveredUnits)
        }
        val overlapping = visible.fold(0L) { total, page ->
            saturatingAdd(total, page.overlappingUnits)
        }
        val currentProgress = state.episodeProgress[state.currentEpisodeId]
        return ViewerTelemetrySnapshot(
            capturedAtNanos = capturedAtNanos,
            manifests = state.manifests.toList(),
            anchor = state.scroll.anchor,
            anchorOrdinal = requireNotNull(state.layout.indexOf(state.scroll.anchor.pageId)),
            scrollOffsetUnits = viewportStart,
            scrollRevision = state.scroll.revision,
            scrollCause = state.scroll.lastCause,
            userInputRevision = state.userInputRevision,
            viewportHeightUnits = state.viewport.height.units,
            contentHeightUnits = state.layout.totalHeight.units,
            currentEpisodeProgress = currentProgress?.let { progress ->
                EpisodeProgressTelemetry(
                    state.currentEpisodeId,
                    progress.pageCount,
                    progress.verifiedCount,
                )
            },
            episodeAppends = state.episodeAppends.map { (episodeId, runtime) ->
                EpisodeAppendTelemetry(
                    fromEpisodeId = episodeId,
                    targetEpisodeId = runtime.owner?.targetEpisodeId ?: runtime.boundaryPageId?.episodeId,
                    terminal = runtime.terminal,
                    hasBoundary = runtime.boundaryPageId != null,
                    retryReason = runtime.retry?.reason,
                )
            },
            visiblePages = visible,
            uncoveredViewportUnits = (state.viewport.height.units - covered).coerceAtLeast(0L),
            visuallyUncoveredViewportUnits =
                (state.viewport.height.units - visuallyCovered).coerceAtLeast(0L),
            overlappingViewportUnits = overlapping,
        )
    }

    private fun visiblePage(
        state: ViewerState,
        index: Int,
        viewportStart: Long,
        viewportEnd: Long,
        loading: List<LoadingPlacement>,
    ): VisiblePageTelemetry {
        val pageId = state.pageOrder[index]
        val pageTop = state.layout.topAt(index).units
        val pageHeight = state.layout.entries[index].height.units
        val start = maxOf(pageTop, viewportStart)
        val end = minOf(Math.addExact(pageTop, pageHeight), viewportEnd)
        val runtime = state.pages.getValue(pageId)
        val coverage = coverage(runtime.pixel, pageTop, pageHeight, start, end)
        val loadingCoverage = loadingCoverage(loading, start, end)
        val visibleUnits = (end - start).coerceAtLeast(0L)
        return VisiblePageTelemetry(
            pageId = pageId,
            globalOrdinal = index,
            visibleUnits = visibleUnits,
            coveredUnits = coverage.unionUnits,
            loadingUnits = loadingCoverage,
            visualCoveredUnits = minOf(visibleUnits, saturatingAdd(coverage.unionUnits, loadingCoverage)),
            overlappingUnits = coverage.overlappingUnits,
            presented = runtime.isPresented,
        )
    }

    private fun loadingCoverage(
        loading: List<LoadingPlacement>,
        visibleStart: Long,
        visibleEnd: Long,
    ): Long = loading.fold(0L) { total, placement ->
        val start = maxOf(placement.top.units, visibleStart)
        val end = minOf(saturatingAdd(placement.top.units, placement.height.units), visibleEnd)
        saturatingAdd(total, (end - start).coerceAtLeast(0L))
    }

    private fun coverage(
        pixel: PixelRef?,
        pageTop: Long,
        pageHeight: Long,
        visibleStart: Long,
        visibleEnd: Long,
    ): Coverage {
        pixel ?: return Coverage.EMPTY
        var rawUnits = 0L
        var unionUnits = 0L
        var mergedStart = 0L
        var mergedEnd = 0L
        var hasMerged = false
        pixel.tiles.forEach { tile ->
            val top = Math.addExact(
                pageTop,
                scaledOffset(pageHeight, tile.sourceTopPx, pixel.dimensions.heightPx),
            )
            val bottom = Math.addExact(
                pageTop,
                scaledOffset(pageHeight, tile.sourceBottomPx, pixel.dimensions.heightPx),
            )
            val start = maxOf(top, visibleStart)
            val end = minOf(bottom, visibleEnd)
            if (end <= start) return@forEach
            rawUnits += end - start
            if (!hasMerged || start > mergedEnd) {
                if (hasMerged) unionUnits += mergedEnd - mergedStart
                mergedStart = start
                mergedEnd = end
                hasMerged = true
            } else {
                mergedEnd = maxOf(mergedEnd, end)
            }
        }
        if (hasMerged) unionUnits += mergedEnd - mergedStart
        return Coverage(unionUnits, rawUnits - unionUnits)
    }

    private fun scaledOffset(pageHeight: Long, sourceOffset: Int, sourceHeight: Int): Long {
        return multiplyDivideFloorExact(pageHeight, sourceOffset, sourceHeight)
    }

    private data class Coverage(val unionUnits: Long, val overlappingUnits: Long) {
        companion object {
            val EMPTY = Coverage(0L, 0L)
        }
    }
}
