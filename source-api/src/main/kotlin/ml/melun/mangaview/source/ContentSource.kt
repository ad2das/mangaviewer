package ml.melun.mangaview.source

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

interface ContentSource {
    val id: SourceId

    suspend fun search(query: String, cursor: String? = null): SourcePage<SourceSeries>

    suspend fun episodes(seriesId: SeriesId, cursor: String? = null): SourcePage<SourceEpisode>

    suspend fun manifest(episodeId: EpisodeId): EpisodeManifest

    suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes

    suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent)

    suspend fun openPage(pageId: PageId, validation: PageValidation? = null): OpenedPage
}
