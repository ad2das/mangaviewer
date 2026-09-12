package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SpreadPages

/** Opening preparation and visible rendering must choose identical original-image crops. */
object EngineTileBands {
    fun count(page: PageContentIdentity, width: Int, targetHeight: Int = 2048,
        cropLeft: Int = 0, cropRight: Int = page.dimensions.widthPx,
    ): Int {
        require(width > 0 && targetHeight > 2)
        val sourceWidth = cropRight - cropLeft
        require(cropLeft >= 0 && sourceWidth > 0 && cropRight <= page.dimensions.widthPx)
        val rasterHeight = (page.dimensions.heightPx.toLong() * width + sourceWidth - 1) / sourceWidth
        val target = targetHeight - 2L
        return ((rasterHeight + target - 1) / target).coerceIn(1L, page.dimensions.heightPx.toLong()).toInt()
    }

    fun tile(page: PageContentIdentity, band: Int, count: Int, width: Int,
        cropLeft: Int = 0, cropRight: Int = page.dimensions.widthPx,
    ): EngineTileSpec {
        require(count > 0 && band in 0 until count)
        return EngineTileSpec(page.pageId, page.contentRevision, page.sha256, page.dimensions,
            (page.dimensions.heightPx.toLong() * band / count).toInt(),
            (page.dimensions.heightPx.toLong() * (band + 1) / count).toInt(), width,
            cropLeftPx = cropLeft, cropRightPx = cropRight)
    }

    /** One page of a two-page spread; the left half is 0 and the right half is 1. */
    fun splitTile(page: PageContentIdentity, half: Int, band: Int, count: Int, width: Int): EngineTileSpec {
        val dimensions = page.dimensions
        return tile(page, band, count, width,
            SpreadPages.cropLeft(dimensions, half), SpreadPages.cropRight(dimensions, half))
    }
}
