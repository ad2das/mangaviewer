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
class EngineTilePlanner(private val textureBudgetBytes: Long, private val targetTileHeightPx: Int = 2048) {
    init { require(textureBudgetBytes > 0 && targetTileHeightPx > 2) }

    fun plan(snapshot: EngineRuntimeSnapshot): EngineTilePlan {
        val visible = linkedMapOf<EngineTileSpec, WorkPriority>()
        val speculative = linkedSetOf<EngineTileSpec>()
        val placements = mutableListOf<EngineTilePlacement>()
        var complete = snapshot.session.completeViewport
        for (region in snapshot.session.visibleRegions) {
            val page = snapshot.pages[region.pageId]
            if (page == null) { complete = false; continue }
            require(page.dimensions == region.dimensions)
            val count = bandCount(page, snapshot.session.viewport.widthPx)
            val firstRow = region.sourceTopQ32 / SourceAnchor.SOURCE_UNITS_PER_PIXEL
            val endRow = (region.sourceBottomQ32 - 1) / SourceAnchor.SOURCE_UNITS_PER_PIXEL + 1
            val first = (((firstRow + 1) * count - 1) / page.dimensions.heightPx).toInt()
            val last = ((endRow * count - 1) / page.dimensions.heightPx).toInt()
            for (band in first..last) {
                val tile = tile(page, band, count, snapshot.session.viewport.widthPx)
                val anchor = snapshot.session.anchor
                val focus = anchor?.pageId == tile.pageId &&
                    anchor.sourceYQ32 >= tile.sourceTop.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL &&
                    anchor.sourceYQ32 < tile.sourceBottom.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL
                visible[tile] = if (focus) WorkPriority.FOCUS else WorkPriority.VISIBLE
                placements += placement(tile, region)
            }
            if (first > 0) speculative += tile(page, first - 1, count, snapshot.session.viewport.widthPx)
            if (last + 1 < count) speculative += tile(page, last + 1, count, snapshot.session.viewport.widthPx)
            if (first == 0) adjacentTile(snapshot, region.pageId, -1)?.let(speculative::add)
            if (last == count - 1) adjacentTile(snapshot, region.pageId, 1)?.let(speculative::add)
        }
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

    private fun adjacentTile(
        snapshot: EngineRuntimeSnapshot,
        pageId: ml.melun.mangaview.core.PageId,
        direction: Int,
    ): EngineTileSpec? {
        val manifest = snapshot.plans[pageId.episodeId]?.manifest ?: return null
        val index = manifest.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return null
        val adjacent = manifest.pages.getOrNull(index + direction)?.id ?: return null
        // Only speculate from bytes already verified by the session. Visible tiles
        // are budgeted first, and this edge is never placed until actually visible.
        val page = snapshot.pages[adjacent] ?: return null
        val width = snapshot.session.viewport.widthPx
        val count = bandCount(page, width)
        return tile(page, if (direction > 0) 0 else count - 1, count, width)
    }

    private fun bandCount(page: PageContentIdentity, width: Int): Int {
        val rasterHeight = (page.dimensions.heightPx.toLong() * width + page.dimensions.widthPx - 1) /
            page.dimensions.widthPx
        val target = targetTileHeightPx - 2L // full-raster crop rounding can add a row at either boundary
        return ((rasterHeight + target - 1) / target).coerceIn(1L, page.dimensions.heightPx.toLong()).toInt()
    }

    private fun tile(page: PageContentIdentity, band: Int, count: Int, width: Int) = EngineTileSpec(
        page.pageId, page.contentRevision, page.sha256, page.dimensions,
        (page.dimensions.heightPx.toLong() * band / count).toInt(),
        (page.dimensions.heightPx.toLong() * (band + 1) / count).toInt(), width,
    )

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
