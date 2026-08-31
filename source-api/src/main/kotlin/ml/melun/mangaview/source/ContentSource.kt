package ml.melun.mangaview.source

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

interface ContentSource {
    val id: SourceId

    suspend fun search(query: String, cursor: String? = null): SourcePage<SourceSeries>

    /** Catalog discovery is source-neutral; provider-specific routes stay in each adapter. */
    suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> = SourcePage(emptyList())

    suspend fun episodes(seriesId: SeriesId, cursor: String? = null): SourcePage<SourceEpisode>

    suspend fun manifest(episodeId: EpisodeId): EpisodeManifest

    suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes

    suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent)

    suspend fun openPage(pageId: PageId, validation: PageValidation? = null): OpenedPage

    /** Opens opaque series artwork for list/card UI. Viewer pages never use this path. */
    suspend fun openArtwork(series: SourceSeries): OpenedPage? = null
}
