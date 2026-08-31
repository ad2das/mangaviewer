package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceTransport

data class NtkConfig(
    val initialOrigin: String,
    val userAgent: String,
    val searchPageSize: Int = 80,
) {
    init {
        require(searchPageSize in 10..200) { "NTK search page size is invalid" }
    }
}

class NtkContentSource(
    config: NtkConfig,
    transport: SourceTransport,
    accessGateway: NtkAccessGateway,
    parser: NtkDocumentParser = NtkDocumentParser(),
    prefetchScope: CoroutineScope? = null,
) : ContentSource {
    override val id = SourceId("ntk")
    private val documents = NtkDocumentClient(config, transport)
    private val catalog = NtkCatalogService(id, config.searchPageSize, documents, parser)
    private val pages = NtkPageService(
        transport,
        documents,
        accessGateway,
        parser,
        prefetchScope = prefetchScope,
    )

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
        catalog.search(query, cursor)

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return catalog.episodes(seriesId, cursor)
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        requireSource(episodeId)
        val prepared = pages.resolve(episodeId)
        val fallback = if (prepared.previousKnown && prepared.nextKnown) null else {
            val records = catalog.records(episodeId.seriesId, force = false)
            ManifestFallback(
                title = catalog.title(records, episodeId),
                adjacent = catalog.adjacent(records, episodeId),
            )
        }
        return EpisodeManifest(
            id = episodeId,
            title = prepared.title ?: fallback?.title ?: episodeId.remoteKey.substringAfterLast('/'),
            pages = prepared.pages,
            previousEpisodeId = if (prepared.previousKnown) {
                prepared.previousEpisodeId
            } else {
                fallback?.adjacent?.previous
            },
            nextEpisodeId = if (prepared.nextKnown) {
                prepared.nextEpisodeId
            } else {
                fallback?.adjacent?.next
            },
        )
    }

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        requireSource(episodeId)
        return catalog.adjacent(catalog.records(episodeId.seriesId, force = false), episodeId)
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        requireSource(episodeId)
        pages.prepare(episodeId, intent)
    }

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage {
        requireSource(pageId.episodeId)
        return pages.open(pageId, validation)
    }

    private fun requireSource(episodeId: EpisodeId) {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
    }

    private data class ManifestFallback(
        val title: String,
        val adjacent: AdjacentEpisodes,
    )
}
