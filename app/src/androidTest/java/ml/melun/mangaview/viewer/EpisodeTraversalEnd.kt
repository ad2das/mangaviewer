package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageId

/** Necessary endpoint check, not proof that every earlier page was displayed correctly. */
internal object EpisodeTraversalEnd {
    fun reached(lastPage: PageId, visiblePages: List<VisiblePageTelemetry>): Boolean =
        visiblePages.any { page ->
            page.pageId == lastPage && page.pageHeightUnits > 0L && page.visibleUnits > 0L &&
                page.visibleOffsetInPageUnits >= 0L &&
                page.visibleOffsetInPageUnits < page.pageHeightUnits &&
                page.visibleUnits == page.pageHeightUnits - page.visibleOffsetInPageUnits &&
                page.coveredUnits == page.visibleUnits && page.loadingUnits == 0L &&
                page.overlappingUnits == 0L && page.presented
        }
}
