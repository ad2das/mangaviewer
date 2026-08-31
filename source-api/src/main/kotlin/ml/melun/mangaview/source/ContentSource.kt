package ml.melun.mangaview.source

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

interface ContentSource {
    val id: SourceId

    suspend fun search(query: String, cursor: String? = null): SourcePage<SourceSeries>

    suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> =
        search(query.text, query.cursor)

    /** Catalog discovery is source-neutral; provider-specific routes stay in each adapter. */
    suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> = SourcePage(emptyList())

    /** Complete provider-specific genres for the selected content kind. */
    suspend fun genres(kind: SeriesKind): List<SourceGenre> = emptyList()

    suspend fun episodes(seriesId: SeriesId, cursor: String? = null): SourcePage<SourceEpisode>

    suspend fun manifest(episodeId: EpisodeId): EpisodeManifest

    suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes

    suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent)

    suspend fun openPage(
        pageId: PageId,
        validation: PageValidation? = null,
    ): OpenedPage

    suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage = openPage(pageId, validation)

    /** Opens opaque series artwork for list/card UI. Viewer pages never use this path. */
    suspend fun openArtwork(series: SourceSeries): OpenedPage? = null

    /** Canonical browser URL for share/open actions. */
    suspend fun seriesUrl(seriesId: SeriesId): String? = null
}
