package ml.melun.mangaview.app

import kotlinx.coroutines.CancellationException
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSearchQuery

internal class OfflineContentSource(
    private val online: ContentSource,
    private val offline: OfflineEpisodeStore,
) : ContentSource {
    override val id = online.id

    override suspend fun search(query: String, cursor: String?) = online.search(query, cursor)

    override suspend fun search(query: SourceSearchQuery) = online.search(query)

    override suspend fun catalog(query: CatalogQuery) = online.catalog(query)

    override suspend fun genres(kind: SeriesKind): List<SourceGenre> = online.genres(kind)

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        return try {
            online.episodes(seriesId, cursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val saved = if (cursor == null) offline.episodes(seriesId) else emptyList()
            if (saved.isEmpty()) throw failure else SourcePage(saved)
        }
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest =
        offline.manifest(episodeId) ?: online.manifest(episodeId)

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        val manifest = offline.manifest(episodeId)
        return if (manifest == null) online.adjacent(episodeId)
        else AdjacentEpisodes(manifest.previousEpisodeId, manifest.nextEpisodeId)
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        if (offline.manifest(episodeId) != null) return
        try {
            online.prepare(episodeId, intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw failure
        }
    }

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
    ): OpenedPage = online.openPage(pageId, validation)

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage = online.openPage(pageId, validation, priority)

    override suspend fun openArtwork(series: SourceSeries): OpenedPage? = online.openArtwork(series)

    override suspend fun seriesUrl(seriesId: SeriesId): String? = online.seriesUrl(seriesId)

}
