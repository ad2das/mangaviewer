package ml.melun.mangaview.engine.session

import java.math.BigInteger
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.SpreadPages
import ml.melun.mangaview.engine.api.VisiblePageRegion
import java.util.LinkedHashMap

internal data class AnchorState(
    val pageId: PageId,
    val sourceQ32: BigRational,
    val viewportOffsetUnits: Long,
)

internal data class Cursor(
    val pageId: PageId,
    val sourceQ32: BigRational,
)

internal sealed interface GeometryBlocker {
    data class Dimension(val pageId: PageId) : GeometryBlocker
    data class Episode(val episodeId: EpisodeId) : GeometryBlocker
    data class Navigation(val episodeId: EpisodeId) : GeometryBlocker
}

internal data class MoveResult(
    val cursor: Cursor,
    val consumed: BigRational,
    val remaining: BigRational,
    val blocker: GeometryBlocker? = null,
    val boundary: DocumentBoundary? = null,
)

internal data class GeometryRequirements(
    val dimensions: Set<PageId>,
    val episodes: Set<EpisodeId>,
    val navigation: Set<EpisodeId>,
)

internal data class VisibleResult(
    val regions: List<VisiblePageRegion>,
    val requirements: GeometryRequirements,
    val complete: Boolean,
)

internal class DocumentGeometry(
    var targetEpisodeId: EpisodeId,
    var viewport: EngineViewport,
) {
    val manifests: LinkedHashMap<EpisodeId, EpisodeManifest> = LinkedHashMap()
    val navigationKnown: LinkedHashMap<EpisodeId, Boolean> = LinkedHashMap()
    val actualDimensions: LinkedHashMap<PageId, PageDimensions?> = LinkedHashMap()
    var anchor: AnchorState? = null

    /** Session-only split reading; a two-page spread then occupies two vertical page heights. */
    var splitMode: Boolean = false

    private val pageMetrics = PageMetricsCache()
    internal val pageIndices = PageIndexCache()

    /** Test-only cost evidence: pages stepped by bounded backward walks, never read in production. */
    internal var backwardWalkPages: Long = 0L
        private set

    fun applySplitMode(enabled: Boolean) {
        if (splitMode == enabled) return
        splitMode = enabled
        if (!enabled) foldAnchorOutOfTheSecondHalf()
    }

    /** Vertical document extent: a split spread scrolls as two stacked original-page heights. */
    private fun documentExtent(dimensions: PageDimensions): BigInteger = pageSourceExtent(dimensions.heightPx)
        .multiply(BigInteger.valueOf(if (splitMode && SpreadPages.isSpread(dimensions)) 2L else 1L))

    private fun metricsFor(pageId: PageId, dimensions: PageDimensions): PageMetrics =
        pageMetrics.forPage(pageId, dimensions, splitMode, viewport.widthPx)

    /** Drops cached page metrics for pages the geometry no longer holds. */
    internal fun pruneMetrics() = pageMetrics.retainPages(actualDimensions.keys)

    /** Drops cached page indices for episodes the geometry no longer holds. */
    internal fun prunePageIndex() = pageIndices.retainEpisodes(manifests.keys)

    fun addManifest(manifest: EpisodeManifest, known: Boolean) {
        manifests[manifest.id] = manifest
        navigationKnown[manifest.id] = known
        manifest.pages.forEach { page ->
            if (!actualDimensions.containsKey(page.id)) actualDimensions[page.id] = page.dimensions
        }
    }

    fun setDimensions(pageId: PageId, dimensions: PageDimensions) {
        actualDimensions[pageId] = dimensions
    }

    /**
     * Replaces placeholder geometry with the recovered original's. An anchor inside the page keeps
     * its relative position, so the reader stays on the same part of the page; anchors elsewhere are
     * page-relative and do not move.
     */
    fun replaceDimensions(pageId: PageId, old: PageDimensions, dimensions: PageDimensions) {
        val value = anchor
        actualDimensions[pageId] = dimensions
        if (value == null || value.pageId != pageId) return
        val scaled = value.sourceQ32 * BigRational.of(documentExtent(dimensions)) / BigRational.of(documentExtent(old))
        anchor = value.copy(sourceQ32 = scaled)
    }

    fun resolveNavigation(
        episodeId: EpisodeId,
        previousEpisodeId: EpisodeId?,
        nextEpisodeId: EpisodeId?,
    ) {
        val manifest = requireNotNull(manifests[episodeId])
        manifests[episodeId] = manifest.copy(
            previousEpisodeId = previousEpisodeId,
            nextEpisodeId = nextEpisodeId,
        )
        navigationKnown[episodeId] = true
    }

    fun isNavigationKnown(episodeId: EpisodeId): Boolean = navigationKnown[episodeId] == true

    fun publicAnchor(): SourceAnchor? {
        val value = anchor ?: return null
        return SourceAnchor(value.pageId, value.sourceQ32.floorToLong(), value.viewportOffsetUnits)
    }

    fun boundaryPage(boundary: DocumentBoundary): PageId? {
        val result = if (boundary == DocumentBoundary.START) firstPageResult() else terminalPageResult()
        return result.first?.pageId
    }

    fun page(pageId: PageId): PageRef? {
        // Every accepted manifest page has an entry, including pages with unknown dimensions.
        if (pageId.episodeId !in manifests || pageId !in actualDimensions) return null
        return PageRef(pageId, actualDimensions[pageId])
    }

    fun move(delta: BigRational): MoveResult {
        val current = anchor ?: return MoveResult(
            Cursor(targetEpisodeId.firstPageId(), BigRational.ZERO),
            BigRational.ZERO,
            delta,
        )
        val cursor = Cursor(current.pageId, current.sourceQ32)
        val result = when {
            delta.signum() > 0 -> moveForward(cursor, delta)
            delta.signum() < 0 -> moveBackward(cursor, -delta)
            else -> MoveResult(cursor, BigRational.ZERO, BigRational.ZERO)
        }
        anchor = AnchorState(result.cursor.pageId, result.cursor.sourceQ32, current.viewportOffsetUnits)
        return result.copy(consumed = if (delta.signum() < 0) -result.consumed else result.consumed,
            remaining = if (delta.signum() < 0) -result.remaining else result.remaining)
    }

    fun visible(): VisibleResult {
        val value = anchor ?: return VisibleResult(
            emptyList(), GeometryRequirements(emptySet(), setOf(targetEpisodeId), emptySet()), false,
        )
        val start = walkBackward(value.toCursor(), value.viewportOffsetUnits)
        val baseRequirements = requirementsForAnchor()
        val requirements = RequirementBuilder(baseRequirements)
        if (start.blocker != null) requirements.add(start.blocker)
        val startCursor = start.cursor ?: value.toCursor()
        val startScreen = if (start.blocker == null) 0L else value.viewportOffsetUnits
        val viewportHeight = viewportHeightUnits()
        val remainingHeight = if (start.blocker == null) viewportHeight else {
            (viewportHeight - value.viewportOffsetUnits).coerceAtLeast(0L)
        }
        val mapped = mapForward(startCursor, startScreen, remainingHeight, requirements)
        return VisibleResult(mapped.regions, requirements.build(), start.blocker == null && mapped.complete)
    }

    private fun moveForward(cursor: Cursor, distance: BigRational): MoveResult {
        val tail = BigRational.of((viewportHeightUnits() - viewportOffsetUnits()).coerceAtLeast(0L))
        // Advance the lower viewport edge first. An unknown trailing page must not let
        // the anchor pass the scroll limit that its eventual dimensions will establish.
        val bottom = walkForward(cursor, tail, null)
        if (bottom.blocker != null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = bottom.blocker)
        if (bottom.remaining.signum() > 0) return MoveResult(cursor, BigRational.ZERO, distance,
            boundary = DocumentBoundary.END)
        val moved = walkForward(bottom.cursor, distance, null)
        val top = walkBackward(moved.cursor, tail)
        // The top-edge correction can cross back into a page whose dimensions were
        // never resolved (for example a restored deep position). That is a resolvable
        // blocker: defer the pending input and request the page instead of failing.
        if (top.blocker != null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = top.blocker)
        return moved.copy(cursor = top.cursor ?: cursor)
    }

    private fun moveBackward(cursor: Cursor, distance: BigRational): MoveResult {
        val currentPage = page(cursor.pageId)
        if (currentPage == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Episode(cursor.pageId.episodeId))
        if (currentPage.dimensions == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Dimension(cursor.pageId))
        val limit = startLimit()
        if (limit.blocker == null && limit.cursor != null) {
            when (val toLimit = distanceBackwardWithin(cursor, limit.cursor, distance)) {
                is BackwardDistance.Exact -> {
                    if (toLimit.value <= BigRational.ZERO) return MoveResult(cursor, BigRational.ZERO, distance,
                        boundary = DocumentBoundary.START)
                    if (distance > toLimit.value) return MoveResult(limit.cursor, toLimit.value,
                        distance - toLimit.value, boundary = DocumentBoundary.START)
                }
                BackwardDistance.Exceeds, BackwardDistance.Unknown -> Unit
            }
        }
        return walkBackwardForInput(cursor, distance, limit.blocker)
    }

    private fun walkForward(cursor: Cursor, distance: BigRational, terminalBlocker: GeometryBlocker?): MoveResult {
        var current = cursor
        var remaining = distance
        var consumed = BigRational.ZERO
        while (remaining.signum() > 0) {
            val ref = page(current.pageId) ?: return MoveResult(current, consumed, remaining,
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
                        blocker = terminalBlocker, boundary = if (terminalBlocker == null) DocumentBoundary.END else null)
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

    private fun walkBackwardForInput(cursor: Cursor, distance: BigRational, startBlocker: GeometryBlocker?): MoveResult {
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

    private fun walkBackward(cursor: Cursor, distanceUnits: Long): BackwardWalk =
        walkBackward(cursor, BigRational.of(distanceUnits))

    private fun walkBackward(cursor: Cursor, distance: BigRational): BackwardWalk {
        var current = cursor
        var remaining = distance
        while (remaining.signum() > 0) {
            val ref = page(current.pageId) ?: return BackwardWalk(null,
                GeometryBlocker.Episode(current.pageId.episodeId), remaining)
            val source = current.sourceQ32
            if (source.signum() <= 0) {
                when (val previous = previousPage(current.pageId)) {
                    is PageStep.Known -> {
                        val previousDimensions = page(previous.pageId)?.dimensions
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

    /**
     * Screen distance from [from] back to [to], walked exactly like the uncapped reference but
     * stopping with [BackwardDistance.Exceeds] once the accumulated total is strictly greater than
     * [cap] (the caller's backlog distance). Every remaining segment is non-negative, so the final
     * value is also greater than the cap, and the caller's decisions are identical to the uncapped
     * walk:
     * - [BackwardDistance.Exceeds] can only hide a final value greater than `distance`, so neither
     *   `toLimit <= 0` (START boundary) nor `distance > toLimit` (clamp at the limit) could fire;
     *   the caller falls through to the bounded input walk, exactly as the old null did.
     * - A value equal to `distance` is still [BackwardDistance.Exact]: the walk only stops strictly
     *   over the cap, so `distance == toLimit` falls through as it did before.
     * - [BackwardDistance.Unknown] replaces every path where the uncapped walk returned null before
     *   the cap was exceeded.
     */
    private fun distanceBackwardWithin(from: Cursor, to: Cursor, cap: BigRational): BackwardDistance {
        if (from.pageId == to.pageId) {
            val value = screenDelta(from.sourceQ32 - to.sourceQ32, from.pageId)
                ?: return BackwardDistance.Unknown
            return if (value > cap) BackwardDistance.Exceeds else BackwardDistance.Exact(value)
        }
        var current = from
        var total = BigRational.ZERO
        while (current.pageId != to.pageId) {
            val page = page(current.pageId) ?: return BackwardDistance.Unknown
            page.dimensions ?: return BackwardDistance.Unknown
            val segment = screenDelta(current.sourceQ32, current.pageId) ?: return BackwardDistance.Unknown
            total += segment
            if (total > cap) return BackwardDistance.Exceeds
            val previous = previousPage(current.pageId)
            if (previous !is PageStep.Known) return BackwardDistance.Unknown
            val previousDimensions = page(previous.pageId)?.dimensions ?: return BackwardDistance.Unknown
            current = Cursor(previous.pageId, metricsFor(previous.pageId, previousDimensions).extent)
            backwardWalkPages++
        }
        val value = total + (screenDelta(current.sourceQ32 - to.sourceQ32, to.pageId)
            ?: return BackwardDistance.Unknown)
        return if (value > cap) BackwardDistance.Exceeds else BackwardDistance.Exact(value)
    }

    private fun screenDelta(source: BigRational, pageId: PageId): BigRational? {
        val dimensions = page(pageId)?.dimensions ?: return null
        return source * metricsFor(pageId, dimensions).screenScale
    }

    private fun mapForward(
        initial: Cursor,
        initialScreen: Long,
        heightUnits: Long,
        requirements: RequirementBuilder,
    ): MappedRegions {
        var current = initial
        var remaining = BigRational.of(heightUnits)
        var screen = BigRational.of(initialScreen)
        val regions = mutableListOf<VisiblePageRegion>()
        if (remaining.isZero()) return MappedRegions(regions, true)
        while (remaining.signum() > 0) {
            val ref = page(current.pageId)
            if (ref == null) {
                requirements.add(GeometryBlocker.Episode(current.pageId.episodeId))
                return MappedRegions(regions, false)
            }
            val dimensions = ref.dimensions
            if (dimensions == null) {
                requirements.add(GeometryBlocker.Dimension(current.pageId))
                return MappedRegions(regions, false)
            }
            val metrics = metricsFor(current.pageId, dimensions)
            val extent = metrics.extent
            val source = current.sourceQ32.coerceAtLeast(BigRational.ZERO)
            if (source >= extent) {
                when (val next = nextPage(current.pageId)) {
                    is PageStep.Known -> {
                        current = Cursor(next.pageId, BigRational.ZERO)
                        continue
                    }
                    is PageStep.Missing -> {
                        requirements.add(next.blocker)
                        return MappedRegions(regions, false)
                    }
                    PageStep.End -> return MappedRegions(regions, true)
                }
            }
            val pageRemaining = (extent - source) * metrics.screenScale
            val take = if (remaining <= pageRemaining) remaining else pageRemaining
            val endSource = source + take * metrics.sourceScale
            if (take.signum() <= 0) return MappedRegions(regions, false)
            appendRegion(
                regions, current.pageId, dimensions, source, endSource, screen, screen + take,
                viewportHeightUnits(), metrics.extent.truncToLong(),
            )
            screen += take
            remaining -= take
            if (remaining.isZero()) return MappedRegions(regions, true)
            when (val next = nextPage(current.pageId)) {
                is PageStep.Known -> current = Cursor(next.pageId, BigRational.ZERO)
                is PageStep.Missing -> {
                    requirements.add(next.blocker)
                    return MappedRegions(regions, false)
                }
                PageStep.End -> return MappedRegions(regions, true)
            }
        }
        return MappedRegions(regions, true)
    }

    private fun firstPageResult(): PageResult {
        val manifest = manifests[targetEpisodeId]
            ?: return PageResult(null, GeometryBlocker.Episode(targetEpisodeId))
        val page = manifest.pages.firstOrNull()
            ?: return PageResult(null, GeometryBlocker.Episode(targetEpisodeId))
        return PageResult(PageRef(page.id, actualDimensions[page.id]), null)
    }

    private fun viewportOffsetUnits(): Long = viewportOffset(anchor?.viewportOffsetUnits ?: 0L)

    private fun viewportHeightUnits(): Long = viewport.heightPx.toLong() * SCREEN_UNITS_PER_PIXEL_LONG

    private fun viewportOffset(value: Long): Long = value.coerceAtLeast(0L)

    private fun AnchorState.toCursor(): Cursor = Cursor(pageId, sourceQ32)

}

private fun DocumentGeometry.foldAnchorOutOfTheSecondHalf() {
    val value = anchor ?: return
    val dimensions = page(value.pageId)?.dimensions ?: return
    if (!SpreadPages.isSpread(dimensions)) return
    val half = BigRational.of(pageSourceExtent(dimensions.heightPx))
    if (value.sourceQ32 >= half) anchor = value.copy(sourceQ32 = value.sourceQ32 - half)
}

private fun appendRegion(
    regions: MutableList<VisiblePageRegion>,
    pageId: PageId,
    dimensions: PageDimensions,
    source: BigRational,
    endSource: BigRational,
    screen: BigRational,
    endScreen: BigRational,
    maxScreen: Long,
    maxSource: Long,
) {
    if (maxSource <= 1L || maxScreen <= 1L) return
    val top = source.floorToLong().coerceIn(0L, maxSource - 1L)
    val bottom = endSource.ceilToLong().coerceIn(top + 1L, maxSource)
    val screenTop = screen.floorToLong().coerceIn(0L, maxScreen - 1L)
    val screenBottom = endScreen.ceilToLong().coerceIn(screenTop + 1L, maxScreen)
    regions += VisiblePageRegion(pageId, dimensions, top, bottom, screenTop, screenBottom)
}

private class RequirementBuilder(initial: GeometryRequirements) {
    private val dimensions = initial.dimensions.toMutableSet()
    private val episodes = initial.episodes.toMutableSet()
    private val navigation = initial.navigation.toMutableSet()

    fun add(blocker: GeometryBlocker) {
        when (blocker) {
            is GeometryBlocker.Dimension -> dimensions += blocker.pageId
            is GeometryBlocker.Episode -> episodes += blocker.episodeId
            is GeometryBlocker.Navigation -> navigation += blocker.episodeId
        }
    }

    fun build(): GeometryRequirements = GeometryRequirements(
        dimensions.toSet(), episodes.toSet(), navigation.toSet(),
    )
}

private fun BigRational.coerceAtLeast(other: BigRational): BigRational =
    if (this < other) other else this

private fun EpisodeId.firstPageId(): PageId = PageId(this, "p0000")

