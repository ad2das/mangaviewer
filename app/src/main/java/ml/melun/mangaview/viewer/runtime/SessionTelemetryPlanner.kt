package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.content.ContentPipelineSnapshot
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.EpisodeAppendTelemetry
import ml.melun.mangaview.viewer.EpisodeProgressTelemetry
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.ViewerTelemetrySnapshot
import ml.melun.mangaview.viewer.VisiblePageTelemetry
import ml.melun.mangaview.viewer.session.ViewerSessionState

internal class SessionTelemetryPlanner(
    private val networkLimit: Int,
) {
    fun snapshot(
        state: ViewerSessionState,
        pipeline: ContentPipelineSnapshot,
        capturedAtNanos: Long,
    ): ViewerTelemetrySnapshot? {
        val layout = state.layout ?: return null
        val position = position(state) ?: return null
        val viewportStart = state.scroll.contentOffset.units
        val viewportEnd = viewportStart + state.viewport.height.units
        val range = layout.indicesIntersecting(FixedPx(viewportStart), FixedPx(viewportEnd))
        val visible = range.map { index -> visiblePage(state, index, viewportStart, viewportEnd) }
        val covered = visible.sumOf(VisiblePageTelemetry::coveredUnits)
        val anchorEpisode = position.pageId.episodeId
        val episodePages = pipeline.pages.filter { it.pageId.episodeId == anchorEpisode }
        val activePages = pipeline.pages + pipeline.retiringPages
        return ViewerTelemetrySnapshot(
            capturedAtNanos = capturedAtNanos,
            manifests = state.timeline.episodes.map { it.manifest },
            anchor = position,
            anchorOrdinal = layout.indexOf(position.pageId) ?: 0,
            scrollOffsetUnits = viewportStart,
            scrollRevision = state.scrollRevision,
            scrollCause = state.scrollCause,
            userInputRevision = state.userInputRevision,
            viewportHeightUnits = state.viewport.height.units,
            contentHeightUnits = state.totalContentHeight.units,
            velocityUnitsPerSecond = FixedPx.fromPixels(
                state.scroll.velocityPixelsPerSecond.toDouble(),
            ).units,
            networkConcurrency = networkLimit,
            activeFetchCount = pipeline.activeFetches,
            activeFetchPageIds = activePages.withState("Fetching", raw = true),
            activeDecodeCount = pipeline.activeDecodes + pipeline.activeUploads,
            activeDecodePageIds = activePages.filter {
                it.decodeState == "Decoding" || it.decodeState == "Uploading"
            }.map { it.pageId },
            residentPageIds = pipeline.pages.filter { it.residentTextureCount > 0 }.map { it.pageId },
            currentEpisodeProgress = EpisodeProgressTelemetry(
                anchorEpisode,
                state.timeline.episodes.first { it.manifest.id == anchorEpisode }.manifest.pages.size,
                episodePages.count { it.rawState == "Verified" },
            ),
            episodeAppends = state.timeline.episodes.map { episode ->
                val next = episode.manifest.nextEpisodeId
                EpisodeAppendTelemetry(
                    episode.manifest.id,
                    next,
                    terminal = next == null,
                    hasBoundary = next != null && state.timeline.episodeIndex(next) != null,
                    retryReason = null,
                )
            },
            visiblePages = visible,
            uncoveredViewportUnits = (state.viewport.height.units - covered).coerceAtLeast(0L),
            visuallyUncoveredViewportUnits = (state.viewport.height.units - covered).coerceAtLeast(0L),
            overlappingViewportUnits = 0L,
        )
    }

    private fun position(state: ViewerSessionState) = state.layout?.let { layout ->
        val pageId = layout.pageAt(state.scroll.contentOffset) ?: return@let null
        val top = requireNotNull(layout.topOf(pageId))
        ml.melun.mangaview.core.ReadingPosition(
            pageId,
            (state.scroll.contentOffset.units - top.units).coerceAtLeast(0L),
        )
    }

    private fun visiblePage(
        state: ViewerSessionState,
        index: Int,
        viewportStart: Long,
        viewportEnd: Long,
    ): VisiblePageTelemetry {
        val layout = requireNotNull(state.layout)
        val entry = layout.entries[index]
        val pageTop = layout.topAt(index).units
        val pageBottom = pageTop + entry.height.units
        val start = maxOf(pageTop, viewportStart)
        val end = minOf(pageBottom, viewportEnd)
        val visible = (end - start).coerceAtLeast(0L)
        val covered = coveredUnits(state, entry.spec.id, pageTop, entry.height.units, start, end)
        return VisiblePageTelemetry(
            pageId = entry.spec.id,
            globalOrdinal = index,
            visibleUnits = visible,
            coveredUnits = covered,
            loadingUnits = (visible - covered).coerceAtLeast(0L),
            visualCoveredUnits = covered,
            overlappingUnits = 0L,
            presented = covered > 0L,
            pageHeightUnits = entry.height.units,
            visibleOffsetInPageUnits = start - pageTop,
        )
    }

    private fun coveredUnits(
        state: ViewerSessionState,
        pageId: PageId,
        pageTop: Long,
        pageHeight: Long,
        visibleStart: Long,
        visibleEnd: Long,
    ): Long {
        var total = 0L
        var priorEnd = visibleStart
        state.visuals[pageId].orEmpty().sortedBy { it.sourceTopPx }.forEach { band ->
            val top = pageTop + scale(
                pageHeight, band.sourceTopPx, band.sourceHeightPx,
            )
            val bottom = pageTop + scale(
                pageHeight, band.sourceBottomPx, band.sourceHeightPx,
            )
            val start = maxOf(top, visibleStart, priorEnd)
            val end = minOf(bottom, visibleEnd)
            if (end > start) {
                total += end - start
                priorEnd = end
            }
        }
        return total
    }

    private fun scale(value: Long, numerator: Int, denominator: Int): Long {
        require(value >= 0L && numerator in 0..denominator && denominator > 0)
        return value / denominator * numerator + value % denominator * numerator / denominator
    }

    private fun List<ml.melun.mangaview.content.PagePipelineSnapshot>.withState(
        name: String,
        raw: Boolean,
    ): List<PageId> = filter {
        if (raw) it.rawState == name else it.decodeState == name
    }.map { it.pageId }
}
