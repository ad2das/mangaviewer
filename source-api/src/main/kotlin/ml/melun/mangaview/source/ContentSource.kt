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

    /** Live catalog with optional partial lists; only the return value is a complete catalog. */
    suspend fun episodeCatalog(
        seriesId: SeriesId,
        onPartial: suspend (List<SourceEpisode>) -> Unit,
    ): List<SourceEpisode> {
        val episodes = linkedMapOf<EpisodeId, SourceEpisode>()
        val visited = mutableSetOf<String?>()
        var cursor: String? = null
        do {
            check(visited.size < 512 && visited.add(cursor)) { "회차 페이지가 반복됩니다. 다시 시도해 주세요" }
            val page = episodes(seriesId, cursor)
            page.items.forEach { episode ->
                check(episode.id.seriesId == seriesId) { "다른 작품의 회차가 반환되었습니다" }
                episodes.putIfAbsent(episode.id, episode)
            }
            cursor = page.nextCursor
            if (cursor != null && episodes.isNotEmpty()) onPartial(episodes.values.toList())
        } while (cursor != null)
        return episodes.values.toList()
    }

    /** Optional series metadata (status/synopsis/authors); sources that expose it may override. */
    suspend fun seriesDetails(seriesId: SeriesId): SourceSeriesDetails? = null

    suspend fun manifest(episodeId: EpisodeId): EpisodeManifest

    suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes

    /** Returns only already-known adjacency; implementations must not start network I/O here. */
    suspend fun knownAdjacent(episodeId: EpisodeId): AdjacentEpisodes? = null

    /** Returns an already-loaded forward sequence without starting catalog network I/O. */
    suspend fun knownForward(episodeId: EpisodeId, limit: Int): List<EpisodeId> = emptyList()

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
