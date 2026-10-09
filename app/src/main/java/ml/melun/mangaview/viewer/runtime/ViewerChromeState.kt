package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition

internal data class ViewerChromeState(
    val episodeId: EpisodeId,
    val title: String,
    val pageNumber: Int,
    val pageCount: Int,
    val position: ReadingPosition,
    val previousEpisodeId: EpisodeId?,
    val nextEpisodeId: EpisodeId?,
    val splitMode: Boolean = false,
    /** Pages of this episode the engine renders from unavailable placeholder geometry. */
    val unavailablePageIds: Set<PageId> = emptySet(),
    /** True once a measured page of this episode is a two-page spread, so splitting means something. */
    val hasSpreads: Boolean = false,
) {
    init {
        require(title.isNotBlank())
        require(pageNumber in 1..pageCount)
        require(position.pageId.episodeId == episodeId)
    }
}
