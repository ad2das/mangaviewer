package ml.melun.mangaview.app

import java.math.BigInteger
import ml.melun.mangaview.core.toLongExact
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EnginePixelWork
import ml.melun.mangaview.engine.runtime.EngineTileBands

/** A bounded original-quality raster preview, keyed exactly like the real viewer's decode work. */
internal class EngineOpeningPixels(
    private val pixels: EnginePixelWork,
    private val viewport: () -> EngineViewport,
    private val maximumBytes: Long = 32L * 1024 * 1024,
) {
    init { require(maximumBytes > 0) }

    fun requests(work: EngineSessionWork, plan: EpisodeAccessPlan, position: SessionPosition,
        pages: List<StoredPage>,
    ): List<WorkRequest<EnginePixels>> {
        val viewport = viewport()
        val requests = mutableListOf<WorkRequest<EnginePixels>>()
        val anchor = position.anchor
        val legacy = position.legacy
        var bytes = 0L
        var rows = 0L
        for (stored in pages) {
            val page = PageContentIdentity(stored.pageId, stored.contentRevision, stored.sha256,
                stored.dimensions, stored.byteCount)
            val count = EngineTileBands.count(page, viewport.widthPx)
            val sourceRow = when {
                anchor?.pageId == page.pageId -> anchor.sourceYQ32 / SourceAnchor.SOURCE_UNITS_PER_PIXEL
                legacy?.pageId == page.pageId -> BigInteger.valueOf(legacy.offsetInPageUnits)
                    .multiply(BigInteger.valueOf(page.dimensions.widthPx.toLong()))
                    .divide(BigInteger.valueOf(viewport.widthPx.toLong() * SourceAnchor.SCREEN_UNITS_PER_PIXEL)).toLongExact()
                else -> 0L
            }.coerceIn(0, page.dimensions.heightPx - 1L)
            val first = (((sourceRow + 1) * count - 1) / page.dimensions.heightPx).toInt()
            for (band in first until count) {
                val tile = EngineTileBands.tile(page, band, count, viewport.widthPx)
                if (tile.byteCount > maximumBytes - bytes || rows >= viewport.heightPx.toLong() * 2) return requests
                requests += pixels.request(work.page(plan, page.pageId, WorkPriority.NEXT_IMAGE), tile, WorkPriority.NEXT_IMAGE)
                bytes += tile.byteCount
                val openingRasterRow = sourceRow * tile.rasterHeight / page.dimensions.heightPx
                rows += if (band == first) tile.rasterBottom - openingRasterRow else tile.decodedHeight.toLong()
            }
        }
        return requests
    }
}
