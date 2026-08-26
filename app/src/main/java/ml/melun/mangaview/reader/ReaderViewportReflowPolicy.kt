package ml.melun.mangaview.reader

internal data class ReaderViewportReflowAnchor(
    val page: Int,
    val pageFraction: Float,
    val viewportProbeFraction: Float,
)

/** Restores the same semantic point after split-screen, rotation, or divider resize reflow. */
internal object ReaderViewportReflowPolicy {
    fun restoredScrollOffset(
        pageTopPx: Float,
        pageHeightPx: Float,
        viewportHeightPx: Int,
        anchor: ReaderViewportReflowAnchor,
    ): Float {
        val semanticPoint = pageTopPx +
            pageHeightPx.coerceAtLeast(1f) * anchor.pageFraction.coerceIn(0f, 1f)
        val viewportProbe = viewportHeightPx.coerceAtLeast(0) *
            anchor.viewportProbeFraction.coerceIn(0f, 1f)
        return semanticPoint - viewportProbe
    }
}
