package ml.melun.mangaview.engine.runtime

import java.math.BigInteger
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.SpreadPages
import ml.melun.mangaview.engine.api.VisiblePageRegion
import ml.melun.mangaview.engine.api.WorkPriority

data class EngineTileDemand(val tile: EngineTileSpec, val priority: WorkPriority)
data class EngineTilePlacement(val tile: EngineTileSpec, val topScreenUnits: Long, val bottomScreenUnits: Long)
data class EngineTilePlan(
    val demands: List<EngineTileDemand>,
    val placements: List<EngineTilePlacement>,
    val completeGeometry: Boolean,
    val plannedTextureBytes: Long,
)

/** Pure original-resolution demand and placement; speculative tiles never displace visible tiles. */
class EngineTilePlanner(private val textureBudgetBytes: Long, private val targetTileHeightPx: Int = 2048,
    private val preparationViewports: Int = 0,
) {
    init { require(textureBudgetBytes > 0 && targetTileHeightPx > 2 && preparationViewports in 0..12) }

    fun plan(snapshot: EngineRuntimeSnapshot): EngineTilePlan {
        val visible = linkedMapOf<EngineTileSpec, WorkPriority>()
        val speculative = linkedSetOf<EngineTileSpec>()
        val distant = mutableListOf<Pair<Long, EngineTileSpec>>()
        val placements = mutableListOf<EngineTilePlacement>()
        var complete = snapshot.session.completeViewport
        var previousRegion: VisiblePageRegion? = null
        var previousComplete = false
        var previousLastPlacement: Int? = null
        for (region in snapshot.session.visibleRegions) {
            val page = snapshot.pages[region.pageId]
            if (page == null) {
                complete = false
                previousRegion = null
                previousComplete = false
                previousLastPlacement = null
                continue
            }
            require(page.dimensions == region.dimensions)
            val split = splitPage(snapshot, page)
            val width = snapshot.session.viewport.widthPx
            val count = documentBandCount(page, width, split)
            val documentHeight = region.dimensions.heightPx.toLong() * (if (split) 2L else 1L)
            val firstRow = region.sourceTopQ32 / SourceAnchor.SOURCE_UNITS_PER_PIXEL
            val endRow = (region.sourceBottomQ32 - 1) / SourceAnchor.SOURCE_UNITS_PER_PIXEL + 1
            val first = (((firstRow + 1) * count - 1) / documentHeight).toInt()
            val last = ((endRow * count - 1) / documentHeight).toInt()
            val firstPlacement = placements.size
            for (band in first..last) {
                val tile = documentTile(page, band, count, width, split)
                val anchor = snapshot.session.anchor
                val offsetRows = sourceOffsetRows(tile)
                val focus = anchor?.pageId == tile.pageId &&
                    anchor.sourceYQ32 >= (tile.sourceTop.toLong() + offsetRows) *
                        SourceAnchor.SOURCE_UNITS_PER_PIXEL &&
                    anchor.sourceYQ32 < (tile.sourceBottom.toLong() + offsetRows) *
                        SourceAnchor.SOURCE_UNITS_PER_PIXEL
                visible[tile] = if (focus) WorkPriority.FOCUS else WorkPriority.VISIBLE
                placements += placement(tile, region)
            }
            stitchBoundary(placements, previousRegion, previousLastPlacement, previousComplete, region, firstPlacement)
            previousRegion = region
            previousComplete = region.sourceBottomQ32 == documentEndQ32(region.dimensions, split)
            previousLastPlacement = placements.lastIndex
            if (first > 0) speculative += documentTile(page, first - 1, count, width, split)
            if (last + 1 < count) speculative += documentTile(page, last + 1, count, width, split)
            if (first == 0) adjacentTile(snapshot, region.pageId, -1)?.let(speculative::add)
            if (last == count - 1) adjacentTile(snapshot, region.pageId, 1)?.let(speculative::add)
            collectDistantBands(snapshot, page, first, last, distant)
        }
        addDocumentEndHorizon(snapshot, speculative)
        addPreparedHorizon(snapshot, distant, speculative)
        var bytes = visible.keys.fold(0L) { total, tile -> Math.addExact(total, tile.byteCount) }
        require(bytes <= textureBudgetBytes) { "Visible original-resolution tiles exceed the texture budget" }
        val demands = visible.map { EngineTileDemand(it.key, it.value) }.toMutableList()
        for (tile in speculative) {
            if (tile !in visible && tile.byteCount <= textureBudgetBytes - bytes) {
                demands += EngineTileDemand(tile, WorkPriority.NEXT_IMAGE)
                bytes += tile.byteCount
            }
        }
        return EngineTilePlan(demands, placements, complete, bytes)
    }

    /** A held scene takes precedence over speculation; null requires releasing its native references. */
    internal fun retainDisplayed(plan: EngineTilePlan, displayed: Collection<EngineTileSpec>): EngineTilePlan? {
        val required = plan.placements.mapTo(linkedSetOf()) { it.tile }.apply { addAll(displayed) }
        var bytes = required.sumOf { it.byteCount }
        if (bytes > textureBudgetBytes) return null
        val priorities = plan.demands.associate { it.tile to it.priority }
        val demands = required.map { EngineTileDemand(it, priorities[it]?.takeUnless {
            it == WorkPriority.NEXT_IMAGE } ?: WorkPriority.VISIBLE) }.toMutableList()
        for (demand in plan.demands) {
            if (demand.tile in required || demand.tile.byteCount > textureBudgetBytes - bytes) continue
            required += demand.tile
            demands += demand
            bytes += demand.tile.byteCount
        }
        return plan.copy(demands = demands, plannedTextureBytes = bytes)
    }

    /** Keep already resident pixels only after budgeting visible and preparation demands. */
    internal fun retainReady(plan: EngineTilePlan, snapshot: EngineRuntimeSnapshot,
        mostRecentFirst: List<EngineTileSpec>,
    ): EngineTilePlan {
        val demands = plan.demands.toMutableList()
        val wanted = demands.mapTo(linkedSetOf()) { it.tile }
        var bytes = plan.plannedTextureBytes
        for (tile in mostRecentFirst) {
            if (tile in wanted || tile.byteCount > textureBudgetBytes - bytes) continue
            val page = snapshot.pages[tile.pageId] ?: continue
            if (tile.displayWidth != snapshot.session.viewport.widthPx ||
                !matchesReadingMode(tile, page, snapshot.session.splitMode) ||
                tile.contentRevision != page.contentRevision || tile.sha256 != page.sha256 ||
                tile.dimensions != page.dimensions) continue
            wanted += tile
            demands += EngineTileDemand(tile, WorkPriority.NEXT_IMAGE)
            bytes += tile.byteCount
        }
        return plan.copy(demands = demands, plannedTextureBytes = bytes)
    }

    private fun collectDistantBands(snapshot: EngineRuntimeSnapshot, page: PageContentIdentity,
        first: Int, last: Int, distant: MutableList<Pair<Long, EngineTileSpec>>,
    ) {
        if (preparationViewports == 0) return
        for ((direction, start) in listOf(1 to last + 1, -1 to first - 1)) {
            var distance = 0L
            val horizon = preparationViewports.toLong() * snapshot.session.viewport.heightPx
            for (candidate in neighboringBands(snapshot, page, start, direction)) {
                if (distance >= horizon) break
                distant += distance to candidate
                distance += (candidate.decodedHeight.toLong() * candidate.displayWidth +
                    candidate.rasterWidth - 1L) / candidate.rasterWidth
            }
        }
    }

    /** Keep the anchored document's final band resident so queued input cannot outrun a displayable end. */
    private fun addDocumentEndHorizon(snapshot: EngineRuntimeSnapshot, speculative: MutableSet<EngineTileSpec>) {
        if (preparationViewports == 0) return
        val anchor = snapshot.session.anchor ?: return
        val manifest = snapshot.plans[anchor.pageId.episodeId]?.manifest ?: return
        val index = manifest.pages.indexOfFirst { it.id == anchor.pageId }
        // Short documents are covered by the ordinary preparation horizon.
        if (index < 0 || manifest.pages.size - index <= 8) return
        val last = manifest.pages.lastOrNull()?.id ?: return
        val page = snapshot.pages[last] ?: return
        val split = splitPage(snapshot, page)
        val width = snapshot.session.viewport.widthPx
        val count = documentBandCount(page, width, split)
        speculative += documentTile(page, count - 1, count, width, split)
    }

    private fun addPreparedHorizon(snapshot: EngineRuntimeSnapshot,
        distant: List<Pair<Long, EngineTileSpec>>, speculative: MutableSet<EngineTileSpec>,
    ) {
        if (preparationViewports > 0) {
            distant.sortedBy { it.first }.forEach { speculative += it.second }
            // Missing geometry must not prevent decoding the already verified leading pages.
            // These remain speculative demands, never placements or a claim of complete coverage.
            val anchor = snapshot.session.anchor
            val manifest = anchor?.pageId?.episodeId?.let { snapshot.plans[it]?.manifest }
            if (!snapshot.session.completeViewport && anchor != null && manifest != null) {
                val index = manifest.pages.indexOfFirst { it.id == anchor.pageId }
                val leading = snapshot.session.requiredDimensions.fold(index) { at, id ->
                    maxOf(at, manifest.pages.indexOfFirst { it.id == id })
                }
                if (index >= 0) for (page in preparedPagesFrom(snapshot,
                    manifest.pages[leading].id, 1, includeStart = true).take(3)) {
                    val split = splitPage(snapshot, page)
                    val width = snapshot.session.viewport.widthPx
                    speculative += documentTile(page, 0, documentBandCount(page, width, split), width, split)
                }
            }
        }
    }

    private fun neighboringBands(snapshot: EngineRuntimeSnapshot, initial: PageContentIdentity,
        start: Int, direction: Int,
    ): Sequence<EngineTileSpec> = sequence {
        var page = initial
        var band = start
        val neighbors = preparedPagesFrom(snapshot, page.pageId, direction).iterator()
        val width = snapshot.session.viewport.widthPx
        while (true) {
            var split = splitPage(snapshot, page)
            var count = documentBandCount(page, width, split)
            if (band !in 0 until count) {
                if (!neighbors.hasNext()) break
                page = neighbors.next()
                split = splitPage(snapshot, page)
                count = documentBandCount(page, width, split)
                band = if (direction > 0) 0 else count - 1
            }
            yield(documentTile(page, band, count, width, split))
            band += direction
        }
    }

    /** Keep only crops the current reading mode can place: whole pages, or either spread half. */
    private fun matchesReadingMode(tile: EngineTileSpec, page: PageContentIdentity, splitMode: Boolean): Boolean {
        if (!splitMode || !SpreadPages.isSpread(page.dimensions)) {
            return tile.cropLeftPx == 0 && tile.cropRightPx == page.dimensions.widthPx
        }
        val half = SpreadPages.halfWidth(page.dimensions)
        return tile.sourceWidthPx == half &&
            (tile.cropLeftPx == 0 || tile.cropLeftPx == page.dimensions.widthPx - half)
    }

    private fun splitPage(snapshot: EngineRuntimeSnapshot, page: PageContentIdentity): Boolean =
        snapshot.session.splitMode && SpreadPages.isSpread(page.dimensions)

    private fun documentBandCount(page: PageContentIdentity, width: Int, split: Boolean): Int =
        if (!split) bandCount(page, width) else 2 * EngineTileBands.count(page, width, targetTileHeightPx,
            0, SpreadPages.halfWidth(page.dimensions))

    private fun documentTile(page: PageContentIdentity, band: Int, count: Int, width: Int,
        split: Boolean,
    ): EngineTileSpec {
        if (!split) return tile(page, band, count, width)
        val perHalf = count / 2
        return EngineTileBands.splitTile(page, band / perHalf, band % perHalf, perHalf, width)
    }

    /** A split page's right half starts one original page height into the document. */
    private fun sourceOffsetRows(tile: EngineTileSpec): Int =
        if (tile.cropLeftPx > 0) tile.dimensions.heightPx else 0

    private fun documentEndQ32(dimensions: PageDimensions, split: Boolean): Long =
        dimensions.heightPx.toLong() * (if (split) 2L else 1L) * SourceAnchor.SOURCE_UNITS_PER_PIXEL

    private fun stitchBoundary(
        placements: MutableList<EngineTilePlacement>,
        previousRegion: VisiblePageRegion?,
        previousLastPlacement: Int?,
        previousComplete: Boolean,
        region: VisiblePageRegion,
        firstPlacement: Int,
    ) {
        if (previousRegion == null || previousLastPlacement == null || !previousComplete ||
            previousRegion.pageId == region.pageId ||
            region.sourceTopQ32 != 0L ||
            placements[previousLastPlacement].tile.sourceBottom != previousRegion.dimensions.heightPx ||
            placements[firstPlacement].tile.sourceTop != 0
        ) return
        val seam = region.screenTopUnits
        placements[previousLastPlacement] = placements[previousLastPlacement].copy(bottomScreenUnits = seam)
        placements[firstPlacement] = placements[firstPlacement].copy(topScreenUnits = seam)
    }

    private fun adjacentTile(
        snapshot: EngineRuntimeSnapshot,
        pageId: ml.melun.mangaview.core.PageId,
        direction: Int,
    ): EngineTileSpec? {
        val manifest = snapshot.plans[pageId.episodeId]?.manifest ?: return null
        val index = manifest.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return null
        val adjacent = manifest.pages.getOrNull(index + direction)?.id ?: run {
            val next = if (direction > 0) manifest.nextEpisodeId else manifest.previousEpisodeId
            val neighbor = snapshot.plans[next]?.manifest ?: return null
            if (direction > 0) neighbor.pages.firstOrNull()?.id else neighbor.pages.lastOrNull()?.id
        } ?: return null
        // Only speculate from bytes already verified by the session. Visible tiles
        // are budgeted first, and this edge is never placed until actually visible.
        val page = snapshot.pages[adjacent] ?: return null
        val width = snapshot.session.viewport.widthPx
        val split = splitPage(snapshot, page)
        val count = documentBandCount(page, width, split)
        return documentTile(page, if (direction > 0) 0 else count - 1, count, width, split)
    }

    private fun bandCount(page: PageContentIdentity, width: Int) = EngineTileBands.count(page, width, targetTileHeightPx)

    private fun tile(page: PageContentIdentity, band: Int, count: Int, width: Int) =
        EngineTileBands.tile(page, band, count, width)

    private fun placement(tile: EngineTileSpec, region: VisiblePageRegion) = EngineTilePlacement(
        tile, screenCoordinate(tile.rasterTop, tile, region), screenCoordinate(tile.rasterBottom, tile, region),
    )

    private fun screenCoordinate(row: Int, tile: EngineTileSpec, region: VisiblePageRegion): Long {
        val rasterHeight = BigInteger.valueOf(tile.rasterHeight.toLong())
        val sourceUnit = BigInteger.valueOf(SourceAnchor.SOURCE_UNITS_PER_PIXEL)
        val sourceAtRaster = BigInteger.valueOf(row.toLong()).multiply(BigInteger.valueOf(tile.dimensions.heightPx.toLong()))
            .add(BigInteger.valueOf(sourceOffsetRows(tile).toLong()).multiply(rasterHeight))
            .multiply(sourceUnit)
        val relative = sourceAtRaster.subtract(BigInteger.valueOf(region.sourceTopQ32).multiply(rasterHeight))
        val numerator = relative.multiply(BigInteger.valueOf(tile.displayWidth.toLong()))
            .multiply(BigInteger.valueOf(SourceAnchor.SCREEN_UNITS_PER_PIXEL))
        val denominator = rasterHeight.multiply(BigInteger.valueOf(tile.sourceWidthPx.toLong())).multiply(sourceUnit)
        val divided = numerator.divideAndRemainder(denominator)
        val floor = if (divided[1].signum() < 0) divided[0].subtract(BigInteger.ONE) else divided[0]
        return Math.addExact(region.screenTopUnits, floor.toLongExact())
    }
}
