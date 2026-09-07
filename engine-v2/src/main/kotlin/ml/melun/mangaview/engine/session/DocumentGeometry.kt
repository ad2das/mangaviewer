package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.SourceAnchor
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
        val manifest = manifests[pageId.episodeId] ?: return null
        val page = manifest.pages.firstOrNull { it.id == pageId } ?: return null
        return PageRef(page.id, actualDimensions[page.id])
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

    fun requirementsForAnchor(): GeometryRequirements {
        val value = anchor ?: return GeometryRequirements(emptySet(), setOf(targetEpisodeId), emptySet())
        val page = page(value.pageId)
        if (page == null) return GeometryRequirements(emptySet(), setOf(value.pageId.episodeId), emptySet())
        val navigation = if (isNavigationKnown(value.pageId.episodeId)) emptySet() else {
            setOf(value.pageId.episodeId)
        }
        if (page.dimensions == null) return GeometryRequirements(setOf(value.pageId), emptySet(), navigation)
        return GeometryRequirements(emptySet(), emptySet(), navigation)
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
        val currentPage = page(cursor.pageId)
        if (currentPage == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Episode(cursor.pageId.episodeId))
        if (currentPage.dimensions == null) return MoveResult(cursor, BigRational.ZERO, distance,
            blocker = GeometryBlocker.Dimension(cursor.pageId))
        val limit = endLimit()
        if (limit.cursor != null) {
            val toLimit = distanceForward(cursor, limit.cursor)
            if (toLimit != null) {
                if (toLimit <= BigRational.ZERO) {
                    return if (limit.blocker == null) MoveResult(
                        cursor, BigRational.ZERO, distance, boundary = DocumentBoundary.END,
                    ) else MoveResult(cursor, BigRational.ZERO, distance, blocker = limit.blocker)
                }
                if (distance > toLimit) {
                    return if (limit.blocker == null) MoveResult(
                        limit.cursor, toLimit, distance - toLimit, boundary = DocumentBoundary.END,
                    ) else MoveResult(limit.cursor, toLimit, distance - toLimit, blocker = limit.blocker)
                }
            }
        }
        return walkForward(cursor, distance, limit.blocker)
    }

    private fun moveBackward(cursor: Cursor, distance: BigRational): MoveResult {
        val currentPage = page(cursor.pageId)
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
            val ref = page(current.pageId) ?: return MoveResult(current, consumed, remaining,
                blocker = GeometryBlocker.Episode(current.pageId.episodeId))
            val dimensions = ref.dimensions ?: return MoveResult(current, consumed, remaining,
                blocker = GeometryBlocker.Dimension(current.pageId))
            val extent = BigRational.of(pageSourceExtent(dimensions.heightPx))
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
            val toEnd = sourceToScreenUnits(extent - source, dimensions.widthPx, viewport.widthPx)
            if (remaining <= toEnd) {
                val moved = screenToSourceQ32(remaining, dimensions.widthPx, viewport.widthPx)
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
            val dimensions = ref.dimensions ?: return BackwardWalk(null,
                GeometryBlocker.Dimension(current.pageId), remaining)
            val source = current.sourceQ32
            if (source.signum() <= 0) {
                when (val previous = previousPage(current.pageId)) {
                    is PageStep.Known -> {
                        val previousDimensions = page(previous.pageId)?.dimensions
                        if (previousDimensions == null) return BackwardWalk(current,
                            GeometryBlocker.Dimension(previous.pageId), remaining)
                        current = Cursor(previous.pageId,
                            BigRational.of(pageSourceExtent(previousDimensions.heightPx)))
                    }
                    is PageStep.Missing -> return BackwardWalk(current, previous.blocker, remaining)
                    PageStep.End -> return BackwardWalk(current, null, remaining)
                }
                continue
            }
            val toStart = sourceToScreenUnits(source, dimensions.widthPx, viewport.widthPx)
            if (remaining <= toStart) {
                val moved = screenToSourceQ32(remaining, dimensions.widthPx, viewport.widthPx)
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

    private fun endLimit(): Limit {
        val terminalResult = terminalPageResult()
        val terminal = terminalResult.first ?: return Limit(null, terminalResult.blocker)
        val terminalDimensions = page(terminal.pageId)?.dimensions
            ?: return Limit(null, GeometryBlocker.Dimension(terminal.pageId))
        val distanceBack = (viewportHeightUnits() - viewportOffsetUnits()).coerceAtLeast(0L)
        val walked = walkBackward(Cursor(terminal.pageId,
            BigRational.of(pageSourceExtent(terminalDimensions.heightPx))), BigRational.of(distanceBack))
        if (walked.blocker != null && walked.remaining.signum() > 0) {
            return Limit(walked.cursor, walked.blocker)
        }
        if (walked.remaining.signum() > 0) {
            val start = startLimit()
            if (start.blocker != null) return Limit(start.cursor, start.blocker)
            if (start.cursor != null) return Limit(start.cursor, terminalResult.blocker)
        }
        return Limit(walked.cursor, terminalResult.blocker)
    }

    private fun distanceForward(from: Cursor, to: Cursor): BigRational? {
        if (from.pageId == to.pageId) return screenDelta(to.sourceQ32 - from.sourceQ32, from.pageId)
        var current = from
        var total = BigRational.ZERO
        while (current.pageId != to.pageId) {
            val page = page(current.pageId) ?: return null
            val dimensions = page.dimensions ?: return null
            val end = BigRational.of(pageSourceExtent(dimensions.heightPx))
            val segment = screenDelta(end - current.sourceQ32, current.pageId) ?: return null
            total += segment
            val next = nextPage(current.pageId)
            if (next !is PageStep.Known) return null
            current = Cursor(next.pageId, BigRational.ZERO)
        }
        return total + (screenDelta(to.sourceQ32, to.pageId) ?: return null)
    }

    private fun distanceBackward(from: Cursor, to: Cursor): BigRational? {
        if (from.pageId == to.pageId) return screenDelta(from.sourceQ32 - to.sourceQ32, from.pageId)
        var current = from
        var total = BigRational.ZERO
        while (current.pageId != to.pageId) {
            val page = page(current.pageId) ?: return null
            page.dimensions ?: return null
            val segment = screenDelta(current.sourceQ32, current.pageId) ?: return null
            total += segment
            val previous = previousPage(current.pageId)
            if (previous !is PageStep.Known) return null
            val previousDimensions = page(previous.pageId)?.dimensions ?: return null
            current = Cursor(previous.pageId,
                BigRational.of(pageSourceExtent(previousDimensions.heightPx)))
        }
        return total + (screenDelta(current.sourceQ32 - to.sourceQ32, to.pageId) ?: return null)
    }

    private fun screenDelta(source: BigRational, pageId: PageId): BigRational? {
        val dimensions = page(pageId)?.dimensions ?: return null
        return sourceToScreenUnits(source, dimensions.widthPx, viewport.widthPx)
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
            val extent = BigRational.of(pageSourceExtent(dimensions.heightPx))
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
            val pageRemaining = sourceToScreenUnits(extent - source, dimensions.widthPx, viewport.widthPx)
            val take = if (remaining <= pageRemaining) remaining else pageRemaining
            val endSource = source + screenToSourceQ32(take, dimensions.widthPx, viewport.widthPx)
            if (take.signum() <= 0) return MappedRegions(regions, false)
            appendRegion(
                regions, current.pageId, dimensions, source, endSource, screen, screen + take,
                viewportHeightUnits(),
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

private fun appendRegion(
    regions: MutableList<VisiblePageRegion>,
    pageId: PageId,
    dimensions: PageDimensions,
    source: BigRational,
    endSource: BigRational,
    screen: BigRational,
    endScreen: BigRational,
    maxScreen: Long,
) {
    val maxSource = saturatingLong(pageSourceExtent(dimensions.heightPx))
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
