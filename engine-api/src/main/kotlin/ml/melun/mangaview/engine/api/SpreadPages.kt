package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.PageDimensions

/**
 * One original image can contain two printed pages side by side. Split reading shows such a
 * spread as two stacked single-page halves: the left page first, then the right page.
 */
object SpreadPages {
    /** Require a clear landscape margin so tall single pages are never split. */
    fun isSpread(dimensions: PageDimensions): Boolean =
        dimensions.widthPx.toLong() * 5L >= dimensions.heightPx.toLong() * 6L

    /** Both crops share this width so one uniform screen mapping covers both halves. */
    fun halfWidth(dimensions: PageDimensions): Int = dimensions.widthPx / 2

    /** 0 selects the left page; 1 selects the right page. */
    fun cropLeft(dimensions: PageDimensions, half: Int): Int {
        require(half == 0 || half == 1) { "A spread has exactly two halves" }
        return if (half == 0) 0 else dimensions.widthPx - halfWidth(dimensions)
    }

    fun cropRight(dimensions: PageDimensions, half: Int): Int =
        cropLeft(dimensions, half) + halfWidth(dimensions)

    /** Vertical pages an original occupies: two rows of scrolling for a split spread. */
    fun verticalPages(dimensions: PageDimensions): Long = if (isSpread(dimensions)) 2L else 1L

    fun documentSourceExtentQ32(dimensions: PageDimensions): Long =
        dimensions.heightPx.toLong() * verticalPages(dimensions) * SourceAnchor.SOURCE_UNITS_PER_PIXEL
}
