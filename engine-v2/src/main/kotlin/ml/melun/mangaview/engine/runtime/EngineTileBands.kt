package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.PageContentIdentity

/** Opening preparation and visible rendering must choose identical original-image crops. */
object EngineTileBands {
    fun count(page: PageContentIdentity, width: Int, targetHeight: Int = 2048): Int {
        require(width > 0 && targetHeight > 2)
        val rasterHeight = (page.dimensions.heightPx.toLong() * width + page.dimensions.widthPx - 1) /
            page.dimensions.widthPx
        val target = targetHeight - 2L
        return ((rasterHeight + target - 1) / target).coerceIn(1L, page.dimensions.heightPx.toLong()).toInt()
    }

    fun tile(page: PageContentIdentity, band: Int, count: Int, width: Int): EngineTileSpec {
        require(count > 0 && band in 0 until count)
        return EngineTileSpec(page.pageId, page.contentRevision, page.sha256, page.dimensions,
            (page.dimensions.heightPx.toLong() * band / count).toInt(),
            (page.dimensions.heightPx.toLong() * (band + 1) / count).toInt(), width)
    }
}
