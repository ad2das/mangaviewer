package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.VisiblePageRegion

internal data class PageRef(val pageId: PageId, val dimensions: PageDimensions?)

internal data class PageResult(val first: PageRef?, val blocker: GeometryBlocker?)

internal data class Limit(val cursor: Cursor?, val blocker: GeometryBlocker?)

internal data class BackwardWalk(
    val cursor: Cursor?,
    val blocker: GeometryBlocker?,
    val remaining: BigRational = BigRational.ZERO,
)

internal data class MappedRegions(val regions: List<VisiblePageRegion>, val complete: Boolean)

internal sealed interface PageStep {
    data class Known(val pageId: PageId) : PageStep
    data class Missing(val blocker: GeometryBlocker) : PageStep
    data object End : PageStep
}
