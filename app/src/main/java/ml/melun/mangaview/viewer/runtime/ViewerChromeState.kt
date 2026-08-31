package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition

internal data class ViewerChromeState(
    val episodeId: EpisodeId,
    val title: String,
    val pageNumber: Int,
    val pageCount: Int,
    val position: ReadingPosition,
    val previousEpisodeId: EpisodeId?,
    val nextEpisodeId: EpisodeId?,
) {
    init {
        require(title.isNotBlank())
        require(pageNumber in 1..pageCount)
        require(position.pageId.episodeId == episodeId)
    }
}
