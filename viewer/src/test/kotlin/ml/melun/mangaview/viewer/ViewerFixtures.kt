package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

internal object ViewerFixtures {
    val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920))

    fun manifest(
        pageCount: Int,
        episodeKey: String = "episode-1",
        dimensions: (Int) -> PageDimensions? = { null },
    ): EpisodeManifest {
        val series = SeriesId(SourceId("test-source"), "series")
        val episode = EpisodeId(series, episodeKey)
        val pages = (0 until pageCount).map { index ->
            PageSpec(
                id = PageId(episode, "page-$index"),
                ordinal = index,
                dimensions = dimensions(index),
            )
        }
        return EpisodeManifest(episode, "Episode $episodeKey", pages)
    }

    fun reducer(): ViewerReducer = ViewerReducer(
        scrollController = ScrollController(),
        workScheduler = WorkScheduler(DemandPlanner()),
    )
}
