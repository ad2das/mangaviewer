package ml.melun.mangaview.engine.session

import java.math.BigInteger
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.SpreadPages

/**
 * Exact per-page geometry: document extent and both conversion scales. Entries self-validate on the
 * dimensions, split mode, and viewport width they were built from, so a dimension replacement or a
 * viewport change rebuilds only the entries it actually touches.
 */
internal class PageMetricsCache {
    private val entries = HashMap<PageId, PageMetrics>()

    fun forPage(
        pageId: PageId,
        dimensions: PageDimensions,
        splitMode: Boolean,
        viewportWidthPx: Int,
    ): PageMetrics {
        val split = splitMode && SpreadPages.isSpread(dimensions)
        val cached = entries[pageId]
        if (cached != null && cached.dimensions == dimensions && cached.split == split &&
            cached.viewportWidthPx == viewportWidthPx
        ) {
            return cached
        }
        val built = build(dimensions, split, viewportWidthPx)
        entries[pageId] = built
        return built
    }

    fun retainPages(pages: Set<PageId>) {
        entries.keys.retainAll(pages)
    }

    private fun build(dimensions: PageDimensions, split: Boolean, viewportWidthPx: Int): PageMetrics {
        val scaleWidth = if (split) SpreadPages.halfWidth(dimensions) else dimensions.widthPx
        val factor = if (split) 2L else 1L
        return PageMetrics(
            dimensions = dimensions,
            split = split,
            viewportWidthPx = viewportWidthPx,
            extent = BigRational.of(
                BigInteger.valueOf(dimensions.heightPx.toLong())
                    .multiply(Q32_PER_PIXEL)
                    .multiply(BigInteger.valueOf(factor)),
            ),
            screenScale = sourceToScreenScale(scaleWidth, viewportWidthPx),
            sourceScale = screenToSourceScale(scaleWidth, viewportWidthPx),
        )
    }
}

/** One page's exact extent and unit conversions; immutable once built. */
internal class PageMetrics(
    val dimensions: PageDimensions,
    val split: Boolean,
    val viewportWidthPx: Int,
    val extent: BigRational,
    /** sourceQ32 -> screen units. */
    val screenScale: BigRational,
    /** screen units -> sourceQ32. */
    val sourceScale: BigRational,
)
