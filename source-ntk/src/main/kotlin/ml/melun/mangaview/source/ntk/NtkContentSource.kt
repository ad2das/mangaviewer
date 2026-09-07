package ml.melun.mangaview.source.ntk

import java.io.Closeable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceTransport

data class NtkConfig(
    val initialOrigin: String,
    val userAgent: String,
    val searchPageSize: Int = 80,
    val browserIdentity: NtkBrowserIdentity? = null,
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
    documentTransport: SourceTransport = transport,
) : ContentSource, Closeable {
    override val id = SourceId("ntk")
    private val documents = NtkDocumentClient(config, documentTransport)
    private val catalog = NtkCatalogService(id, config.searchPageSize, documents, parser)
    private val pages = NtkPageService(
        transport,
        documents,
        accessGateway,
        parser,
    )

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
        catalog.search(query, cursor)

    override suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> =
        catalog.search(query)

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> =
        catalog.catalog(query)

    override suspend fun genres(kind: ml.melun.mangaview.source.SeriesKind): List<SourceGenre> =
        catalog.genres(kind)

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return catalog.episodes(seriesId, cursor)
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = coroutineScope {
        requireSource(episodeId)
        val fallbackLock = Any()
        var fallbackFlight: Deferred<ManifestFallback>? = null
        fun startFallback(): Deferred<ManifestFallback> = synchronized(fallbackLock) {
            fallbackFlight ?: async {
                val records = catalog.records(episodeId.seriesId, force = false)
                val fallback = ManifestFallback(
                    title = catalog.title(records, episodeId),
                    adjacent = catalog.adjacent(records, episodeId),
                )
                fallback
            }.also { fallbackFlight = it }
        }
        // The document parser reports missing adjacency before the protected image API completes
        // its minimum-seen ACK, so only a genuinely necessary catalog fallback overlaps that wait.
        val prepared = pages.resolve(episodeId) { startFallback() }
        val fallback = if (prepared.previousKnown && prepared.nextKnown) {
            null
        } else {
            startFallback().await()
        }
        val nextEpisodeId = if (prepared.nextKnown) {
            prepared.nextEpisodeId
        } else {
            fallback?.adjacent?.next
        }
        EpisodeManifest(
            id = episodeId,
            title = prepared.title ?: fallback?.title ?: episodeId.remoteKey.substringAfterLast('/'),
            pages = prepared.pages,
            previousEpisodeId = if (prepared.previousKnown) {
                prepared.previousEpisodeId
            } else {
                fallback?.adjacent?.previous
            },
            nextEpisodeId = nextEpisodeId,
        )
    }

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        requireSource(episodeId)
        return catalog.adjacent(catalog.records(episodeId.seriesId, force = false), episodeId)
    }

    override suspend fun knownAdjacent(episodeId: EpisodeId): AdjacentEpisodes? {
        requireSource(episodeId)
        return catalog.knownAdjacent(episodeId)
    }

    override suspend fun knownForward(episodeId: EpisodeId, limit: Int): List<EpisodeId> {
        requireSource(episodeId)
        return catalog.knownForward(episodeId, limit)
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        requireSource(episodeId)
        pages.prepare(episodeId, intent)
    }

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
    ): OpenedPage = openPage(pageId, validation, PageFetchPriority.NORMAL)

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        requireSource(pageId.episodeId)
        return pages.open(pageId, validation, priority)
    }

    override suspend fun openArtwork(series: SourceSeries): OpenedPage? {
        require(series.id.sourceId == id) { "Series belongs to another source" }
        val key = series.thumbnailKey?.takeIf(String::isNotBlank) ?: return null
        return documents.openArtwork(key, series.id.remoteKey)
    }

    override suspend fun seriesUrl(seriesId: SeriesId): String? {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        return documents.url(seriesId.remoteKey)
    }

    override fun close() = Unit

    private fun requireSource(episodeId: EpisodeId) {
        require(episodeId.seriesId.sourceId == id) { "Episode belongs to another source" }
    }

    private data class ManifestFallback(
        val title: String,
        val adjacent: AdjacentEpisodes,
    )

}
