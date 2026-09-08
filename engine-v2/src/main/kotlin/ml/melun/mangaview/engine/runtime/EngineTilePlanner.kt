package ml.melun.mangaview.engine.runtime

import java.math.BigInteger
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SourceAnchor
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
    init { require(textureBudgetBytes > 0 && targetTileHeightPx > 2 && preparationViewports in 0..4) }

    fun plan(snapshot: EngineRuntimeSnapshot): EngineTilePlan {
        val visible = linkedMapOf<EngineTileSpec, WorkPriority>()
        val speculative = linkedSetOf<EngineTileSpec>()
        val distant = mutableListOf<Pair<Long, EngineTileSpec>>()
        val placements = mutableListOf<EngineTilePlacement>()
        var complete = snapshot.session.completeViewport
        var previousRegion: VisiblePageRegion? = null
        var previousLastPlacement: Int? = null
        for (region in snapshot.session.visibleRegions) {
            val page = snapshot.pages[region.pageId]
            if (page == null) {
                complete = false
                previousRegion = null
                previousLastPlacement = null
                continue
            }
            require(page.dimensions == region.dimensions)
            val count = bandCount(page, snapshot.session.viewport.widthPx)
            val firstRow = region.sourceTopQ32 / SourceAnchor.SOURCE_UNITS_PER_PIXEL
            val endRow = (region.sourceBottomQ32 - 1) / SourceAnchor.SOURCE_UNITS_PER_PIXEL + 1
            val first = (((firstRow + 1) * count - 1) / page.dimensions.heightPx).toInt()
            val last = ((endRow * count - 1) / page.dimensions.heightPx).toInt()
            val firstPlacement = placements.size
            for (band in first..last) {
                val tile = tile(page, band, count, snapshot.session.viewport.widthPx)
                val anchor = snapshot.session.anchor
                val focus = anchor?.pageId == tile.pageId &&
                    anchor.sourceYQ32 >= tile.sourceTop.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL &&
                    anchor.sourceYQ32 < tile.sourceBottom.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL
                visible[tile] = if (focus) WorkPriority.FOCUS else WorkPriority.VISIBLE
                placements += placement(tile, region)
            }
            stitchBoundary(placements, previousRegion, previousLastPlacement, region, firstPlacement)
            previousRegion = region
            previousLastPlacement = placements.lastIndex
            if (first > 0) speculative += tile(page, first - 1, count, snapshot.session.viewport.widthPx)
            if (last + 1 < count) speculative += tile(page, last + 1, count, snapshot.session.viewport.widthPx)
            if (first == 0) adjacentTile(snapshot, region.pageId, -1)?.let(speculative::add)
            if (last == count - 1) adjacentTile(snapshot, region.pageId, 1)?.let(speculative::add)
            collectDistantBands(snapshot, page, first, last, distant)
        }
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
                distance += candidate.decodedHeight
            }
        }
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
                if (index >= 0) for (at in leading..minOf(leading + 2, manifest.pages.lastIndex)) {
                    val page = snapshot.pages[manifest.pages[at].id] ?: continue
                    speculative += tile(page, 0, bandCount(page, snapshot.session.viewport.widthPx), snapshot.session.viewport.widthPx)
                }
            }
        }
    }

    private fun neighboringBands(snapshot: EngineRuntimeSnapshot, initial: PageContentIdentity,
        start: Int, direction: Int,
    ): Sequence<EngineTileSpec> = sequence {
        var page = initial
        var band = start
        val visited = linkedSetOf(page.pageId)
        val width = snapshot.session.viewport.widthPx
        while (true) {
            var count = bandCount(page, width)
            if (band !in 0 until count) {
                val edge = adjacentTile(snapshot, page.pageId, direction) ?: break
                if (!visited.add(edge.pageId)) break
                page = snapshot.pages[edge.pageId] ?: break
                count = bandCount(page, width)
                band = if (direction > 0) 0 else count - 1
            }
            yield(tile(page, band, count, width))
            band += direction
        }
    }

    private fun stitchBoundary(
        placements: MutableList<EngineTilePlacement>,
        previousRegion: VisiblePageRegion?,
        previousLastPlacement: Int?,
        region: VisiblePageRegion,
        firstPlacement: Int,
    ) {
        if (previousRegion == null || previousLastPlacement == null || previousRegion.pageId == region.pageId ||
            previousRegion.sourceBottomQ32 != previousRegion.dimensions.heightPx.toLong() *
                SourceAnchor.SOURCE_UNITS_PER_PIXEL ||
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
        val count = bandCount(page, width)
        return tile(page, if (direction > 0) 0 else count - 1, count, width)
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
            .multiply(sourceUnit)
        val relative = sourceAtRaster.subtract(BigInteger.valueOf(region.sourceTopQ32).multiply(rasterHeight))
        val numerator = relative.multiply(BigInteger.valueOf(tile.displayWidth.toLong()))
            .multiply(BigInteger.valueOf(SourceAnchor.SCREEN_UNITS_PER_PIXEL))
        val denominator = rasterHeight.multiply(BigInteger.valueOf(tile.dimensions.widthPx.toLong())).multiply(sourceUnit)
        val divided = numerator.divideAndRemainder(denominator)
        val floor = if (divided[1].signum() < 0) divided[0].subtract(BigInteger.ONE) else divided[0]
        return Math.addExact(region.screenTopUnits, floor.toLongExact())
    }
}
