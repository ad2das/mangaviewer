package ml.melun.mangaview.viewer.session

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LayoutLedger
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.multiplyDivideFloorExact

object ScrollModel {
    fun applyInput(
        scroll: CanonicalScroll,
        delta: FixedPx,
        velocityPixelsPerSecond: Float,
        maximum: FixedPx,
    ): CanonicalScroll = CanonicalScroll(
        contentOffset = (scroll.contentOffset + delta).coerceIn(FixedPx.ZERO, maximum),
        velocityPixelsPerSecond = velocityPixelsPerSecond,
    )

    fun anchor(
        scroll: CanonicalScroll,
        viewport: Viewport,
        layout: LayoutLedger,
    ): GeometryAnchor? {
        val viewportPoint = FixedPx(viewport.height.units / 2L)
        val contentPoint = scroll.contentOffset + viewportPoint
        if (contentPoint >= layout.totalHeight) {
            return RunwayViewportAnchor(contentPoint - layout.totalHeight, viewportPoint)
        }
        val pageId = layout.pageAt(contentPoint) ?: return null
        val top = layout.topOf(pageId) ?: return null
        val height = layout.heightOf(pageId) ?: return null
        val inside = (contentPoint.units - top.units).coerceIn(0L, height.units)
        return SemanticViewportAnchor(pageId, FixedPx(inside), viewportPoint, viewport.width)
    }

    fun restore(
        anchor: GeometryAnchor,
        layout: LayoutLedger,
        maximum: FixedPx,
        velocityPixelsPerSecond: Float,
    ): CanonicalScroll = when (anchor) {
        is RunwayViewportAnchor -> CanonicalScroll(
            FixedPx(
                layout.totalHeight.units + anchor.offsetInRunway.units -
                    anchor.viewportOffset.units,
            ).coerceIn(FixedPx.ZERO, maximum),
            velocityPixelsPerSecond,
        )
        is SemanticViewportAnchor -> restorePage(
            anchor,
            layout,
            maximum,
            velocityPixelsPerSecond,
        )
    }

    private fun restorePage(
        anchor: SemanticViewportAnchor,
        layout: LayoutLedger,
        maximum: FixedPx,
        velocityPixelsPerSecond: Float,
    ): CanonicalScroll {
        val top = layout.topOf(anchor.pageId) ?: return CanonicalScroll(
            FixedPx.ZERO,
            velocityPixelsPerSecond,
        )
        val inside = multiplyDivideFloorExact(
            anchor.offsetInPage.units,
            layout.viewportWidth.units,
            anchor.basisViewportWidth.units,
        )
        return CanonicalScroll(
            FixedPx(top.units + inside - anchor.viewportOffset.units)
                .coerceIn(FixedPx.ZERO, maximum),
            velocityPixelsPerSecond,
        )
    }

    fun navigate(
        pageId: PageId,
        offsetInPage: FixedPx,
        viewportOffset: FixedPx,
        layout: LayoutLedger,
        maximum: FixedPx,
    ): CanonicalScroll {
        val top = layout.topOf(pageId) ?: return CanonicalScroll()
        val height = layout.heightOf(pageId) ?: FixedPx.ZERO
        val inside = offsetInPage.coerceIn(FixedPx.ZERO, FixedPx((height.units - 1L).coerceAtLeast(0L)))
        return CanonicalScroll(
            FixedPx(top.units + inside.units - viewportOffset.units)
                .coerceIn(FixedPx.ZERO, maximum),
        )
    }
}
