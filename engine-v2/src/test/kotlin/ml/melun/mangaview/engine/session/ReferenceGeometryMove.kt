package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.EngineViewport

/**
 * Frozen copy of the pre-fix move chain: the uncapped distanceBackward plus the O(pages)
 * previousPage/nextPage identity scans. Equivalence and cost tests compare the production bounded
 * walk against this reference and report its cost; it must not gain optimizations.
 */
internal class ReferenceGeometryMove(private val geometry: DocumentGeometry) {
    private val pageMetrics = PageMetricsCache()

    /** Cost evidence: pages the uncapped backward distance walk steps through. */
    var walkPages: Long = 0L
        private set

    /** Cost evidence: page elements compared by the linear index scans. */
    var indexScanSteps: Long = 0L
        private set

    fun move(delta: BigRational): MoveResult {
        val value = geometry.anchor ?: return MoveResult(
            Cursor(geometry.targetEpisodeId.firstPageId(), BigRational.ZERO),
            BigRational.ZERO,
            delta,
        )
        val cursor = Cursor(value.pageId, value.sourceQ32)
        val result = when {
            delta.signum() > 0 -> moveForward(cursor, delta)
            delta.signum() < 0 -> moveBackward(cursor, -delta)
            else -> MoveResult(cursor, BigRational.ZERO, BigRational.ZERO)
        }
        geometry.anchor = AnchorState(result.cursor.pageId, result.cursor.sourceQ32, value.viewportOffsetUnits)
        return result.copy(
            consumed = if (delta.signum() < 0) -result.consumed else result.consumed,
            remaining = if (delta.signum() < 0) -result.remaining else result.remaining,
        )
    }

    /** Exact screen distance from [cursor] to the session start limit, or null when unknown. */
    fun limitDistance(cursor: Cursor): BigRational? {
        val limit = startLimit()
        if (limit.blocker != null || limit.cursor == null) return null
        return distanceBackward(cursor, limit.cursor)
    }

    private fun moveForward(cursor: Cursor, distance: BigRational): MoveResult {
        val tail = BigRational.of((viewportHeightUnits() - viewportOffsetUnits()).coerceAtLeast(0L))
        val bottom = walkForward(cursor, tail, null)
        if (bottom.blocker != null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = bottom.blocker)
        if (bottom.remaining.signum() > 0) return MoveResult(cursor, BigRational.ZERO, distance,
            boundary = DocumentBoundary.END)
        val moved = walkForward(bottom.cursor, distance, null)
        val top = walkBackward(moved.cursor, tail)
        if (top.blocker != null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = top.blocker)
        return moved.copy(cursor = top.cursor ?: cursor)
    }

    private fun moveBackward(cursor: Cursor, distance: BigRational): MoveResult {
        val currentPage = geometry.page(cursor.pageId)
        if (currentPage == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Episode(cursor.pageId.episodeId))
        if (currentPage.dimensions == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Dimension(cursor.pageId))
        val limit = startLimit()
        if (limit.blocker == null && limit.cursor != null) {
            val toLimit = distanceBackward(cursor, limit.cursor)
            if (toLimit != null) {
                if (toLimit <= BigRational.ZERO) return MoveResult(cursor, BigRational.ZERO, distance,
                    boundary = DocumentBoundary.START)
                if (distance > toLimit) return MoveResult(limit.cursor, toLimit, distance - toLimit,
                    boundary = DocumentBoundary.START)
            }
        }
        return walkBackwardForInput(cursor, distance, limit.blocker)
    }

    private fun walkForward(cursor: Cursor, distance: BigRational, terminalBlocker: GeometryBlocker?): MoveResult {
        var current = cursor
        var remaining = distance
        var consumed = BigRational.ZERO
        while (remaining.signum() > 0) {
            val ref = geometry.page(current.pageId) ?: return MoveResult(current, consumed, remaining,
                blocker = GeometryBlocker.Episode(current.pageId.episodeId))
            val dimensions = ref.dimensions ?: return MoveResult(current, consumed, remaining,
                blocker = GeometryBlocker.Dimension(current.pageId))
            val metrics = metricsFor(current.pageId, dimensions)
            val extent = metrics.extent
            val source = current.sourceQ32
            if (source >= extent) {
                when (val next = nextPage(current.pageId)) {
                    is PageStep.Known -> current = Cursor(next.pageId, BigRational.ZERO)
                    is PageStep.Missing -> return MoveResult(current, consumed, remaining, blocker = next.blocker)
                    PageStep.End -> return MoveResult(current, consumed, remaining,
                        blocker = terminalBlocker,
                        boundary = if (terminalBlocker == null) DocumentBoundary.END else null)
                }
                continue
            }
            val toEnd = (extent - source) * metrics.screenScale
            if (remaining <= toEnd) {
                val moved = remaining * metrics.sourceScale
                current = Cursor(current.pageId, source + moved)
                return MoveResult(current, consumed + remaining, BigRational.ZERO)
            }
            current = Cursor(current.pageId, extent)
            consumed += toEnd
            remaining -= toEnd
        }
        return MoveResult(current, consumed, remaining)
    }

    private fun walkBackwardForInput(
        cursor: Cursor,
        distance: BigRational,
        startBlocker: GeometryBlocker?,
    ): MoveResult {
        val result = walkBackward(cursor, distance)
        val consumed = distance - result.remaining
        if (result.blocker != null) return MoveResult(
            result.cursor ?: cursor, consumed, result.remaining, blocker = result.blocker,
        )
        if (result.remaining.signum() == 0) return MoveResult(result.cursor ?: cursor, consumed, BigRational.ZERO)
        return MoveResult(
            result.cursor ?: cursor, consumed, result.remaining, blocker = startBlocker,
            boundary = if (startBlocker == null) DocumentBoundary.START else null,
        )
    }

    private fun walkBackward(cursor: Cursor, distance: BigRational): BackwardWalk {
        var current = cursor
        var remaining = distance
        while (remaining.signum() > 0) {
            val ref = geometry.page(current.pageId) ?: return BackwardWalk(null,
                GeometryBlocker.Episode(current.pageId.episodeId), remaining)
            val source = current.sourceQ32
            if (source.signum() <= 0) {
                when (val previous = previousPage(current.pageId)) {
                    is PageStep.Known -> {
                        val previousDimensions = geometry.page(previous.pageId)?.dimensions
                        if (previousDimensions == null) return BackwardWalk(current,
                            GeometryBlocker.Dimension(previous.pageId), remaining)
                        current = Cursor(previous.pageId, metricsFor(previous.pageId, previousDimensions).extent)
                    }
                    is PageStep.Missing -> return BackwardWalk(current, previous.blocker, remaining)
                    PageStep.End -> return BackwardWalk(current, null, remaining)
                }
                continue
            }
            val dimensions = ref.dimensions ?: return BackwardWalk(null,
                GeometryBlocker.Dimension(current.pageId), remaining)
            val metrics = metricsFor(current.pageId, dimensions)
            val toStart = source * metrics.screenScale
            if (remaining <= toStart) {
                val moved = remaining * metrics.sourceScale
                return BackwardWalk(Cursor(current.pageId, source - moved), null)
            }
            current = Cursor(current.pageId, BigRational.ZERO)
            remaining -= toStart
        }
        return BackwardWalk(current, null)
    }

    private fun startLimit(): Limit {
        val firstResult = firstPageResult()
        val first = firstResult.first ?: return Limit(null, firstResult.blocker)
        if (first.dimensions == null) return Limit(null, GeometryBlocker.Dimension(first.pageId))
        val walked = walkForward(Cursor(first.pageId, BigRational.ZERO), BigRational.of(viewportOffsetUnits()), null)
        if (walked.blocker != null) return Limit(null, walked.blocker)
        return Limit(walked.cursor, null)
    }

    private fun distanceBackward(from: Cursor, to: Cursor): BigRational? {
        if (from.pageId == to.pageId) return screenDelta(from.sourceQ32 - to.sourceQ32, from.pageId)
        var current = from
        var total = BigRational.ZERO
        while (current.pageId != to.pageId) {
            val page = geometry.page(current.pageId) ?: return null
            page.dimensions ?: return null
            val segment = screenDelta(current.sourceQ32, current.pageId) ?: return null
            total += segment
            val previous = previousPage(current.pageId)
            if (previous !is PageStep.Known) return null
            val previousDimensions = geometry.page(previous.pageId)?.dimensions ?: return null
            current = Cursor(previous.pageId, metricsFor(previous.pageId, previousDimensions).extent)
            walkPages++
        }
        return total + (screenDelta(current.sourceQ32 - to.sourceQ32, to.pageId) ?: return null)
    }

    private fun screenDelta(source: BigRational, pageId: PageId): BigRational? {
        val dimensions = geometry.page(pageId)?.dimensions ?: return null
        return source * metricsFor(pageId, dimensions).screenScale
    }

    private fun firstPageResult(): PageResult {
        val manifest = geometry.manifests[geometry.targetEpisodeId]
            ?: return PageResult(null, GeometryBlocker.Episode(geometry.targetEpisodeId))
        val page = manifest.pages.firstOrNull()
            ?: return PageResult(null, GeometryBlocker.Episode(geometry.targetEpisodeId))
        return PageResult(PageRef(page.id, geometry.actualDimensions[page.id]), null)
    }

    private fun nextPage(pageId: PageId): PageStep {
        val manifest = geometry.manifests[pageId.episodeId] ?: return PageStep.Missing(
            GeometryBlocker.Episode(pageId.episodeId))
        indexScanSteps += manifest.pages.size
        val index = manifest.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return PageStep.Missing(GeometryBlocker.Episode(pageId.episodeId))
        if (index + 1 < manifest.pages.size) return PageStep.Known(manifest.pages[index + 1].id)
        val next = manifest.nextEpisodeId ?: return if (geometry.isNavigationKnown(manifest.id)) {
            PageStep.End
        } else {
            PageStep.Missing(GeometryBlocker.Navigation(manifest.id))
        }
        if (!geometry.manifests.containsKey(next)) return PageStep.Missing(GeometryBlocker.Episode(next))
        val page = geometry.manifests[next]?.pages?.firstOrNull()
            ?: return PageStep.Missing(GeometryBlocker.Episode(next))
        return PageStep.Known(page.id)
    }

    private fun previousPage(pageId: PageId): PageStep {
        val manifest = geometry.manifests[pageId.episodeId] ?: return PageStep.Missing(
            GeometryBlocker.Episode(pageId.episodeId))
        indexScanSteps += manifest.pages.size
        val index = manifest.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return PageStep.Missing(GeometryBlocker.Episode(pageId.episodeId))
        if (index > 0) return PageStep.Known(manifest.pages[index - 1].id)
        if (pageId.episodeId == geometry.targetEpisodeId) return PageStep.End
        if (manifest.previousEpisodeId == null) return if (geometry.isNavigationKnown(manifest.id)) {
            PageStep.End
        } else {
            PageStep.Missing(GeometryBlocker.Navigation(manifest.id))
        }
        val previous = requireNotNull(manifest.previousEpisodeId)
        if (!geometry.manifests.containsKey(previous)) return PageStep.Missing(GeometryBlocker.Episode(previous))
        val page = geometry.manifests[previous]?.pages?.lastOrNull()
            ?: return PageStep.Missing(GeometryBlocker.Episode(previous))
        return PageStep.Known(page.id)
    }

    private fun metricsFor(pageId: PageId, dimensions: PageDimensions): PageMetrics =
        pageMetrics.forPage(pageId, dimensions, geometry.splitMode, geometry.viewport.widthPx)

    private fun viewportOffsetUnits(): Long = (geometry.anchor?.viewportOffsetUnits ?: 0L).coerceAtLeast(0L)

    private fun viewportHeightUnits(): Long = geometry.viewport.heightPx.toLong() * SCREEN_UNITS_PER_PIXEL_LONG
}

private fun EpisodeId.firstPageId(): PageId = PageId(this, "p0000")

/** Five chained episodes of 400 pages with the anchor deep in the last one. */
internal fun deepWindowGeometry(episodes: Int = 5, pagesPerEpisode: Int = 400): DocumentGeometry {
    val series = SeriesId(SourceId("test"), "deep-window")
    val ids = (0 until episodes).map { EpisodeId(series, "e$it") }
    val geometry = DocumentGeometry(ids.first(), EngineViewport(1080, 1920))
    ids.forEachIndexed { index, episode ->
        val pages = (0 until pagesPerEpisode).map { ordinal ->
            PageSpec(PageId.at(episode, ordinal), ordinal, PageDimensions(720, 4000))
        }
        geometry.addManifest(
            EpisodeManifest(
                episode, "episode-$index", pages,
                previousEpisodeId = ids.getOrNull(index - 1),
                nextEpisodeId = ids.getOrNull(index + 1),
            ),
            true,
        )
    }
    geometry.anchor = AnchorState(
        PageId.at(ids.last(), pagesPerEpisode - (pagesPerEpisode / 4)),
        BigRational.of(pageSourceExtent(4000)),
        0L,
    )
    return geometry
}
