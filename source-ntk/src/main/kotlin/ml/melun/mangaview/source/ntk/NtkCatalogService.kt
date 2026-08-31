package ml.melun.mangaview.source.ntk

import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries

internal class NtkCatalogService(
    private val sourceId: SourceId,
    private val searchPageSize: Int,
    private val documents: NtkDocumentClient,
    private val parser: NtkDocumentParser,
) {
    private val mutex = Mutex()
    private var catalogs: Map<SeriesId, List<NtkEpisodeRecord>> = emptyMap()

    suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val path = "/api/works?keyword=$encoded&page=$page&pageSize=$searchPageSize&withTotal=1"
        val api = attempt { parser.searchApi(documents.text(path, json = true), sourceId) }
        if (api != null && api.series.isNotEmpty()) {
            val hasNext = api.total?.let { page * searchPageSize < it }
                ?: (api.series.size == searchPageSize)
            return SourcePage(api.series, if (hasNext) (page + 1).toString() else null)
        }
        return SourcePage(parser.searchHtml(documents.text("/search?q=$encoded", false), sourceId))
    }

    suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> =
        if (cursor == null) SourcePage(records(seriesId, force = true).map(NtkEpisodeRecord::episode))
        else SourcePage(emptyList())

    suspend fun records(seriesId: SeriesId, force: Boolean): List<NtkEpisodeRecord> {
        if (!force) mutex.withLock { catalogs[seriesId] }?.let { return it }
        val key = NtkSeriesKey.decode(seriesId)
        val path = "/api/${key.kind.pathSegment}/${key.workKey}/episodes"
        val api = attempt { parser.episodesApi(documents.text(path, true), seriesId) }
        val loaded = if (api != null && api.episodes.isNotEmpty() && api.isComplete) {
            api.episodes
        } else {
            documentEpisodes(seriesId, key)
        }
        require(loaded.isNotEmpty()) { "NTK series contains no episodes" }
        mutex.withLock { catalogs = catalogs + (seriesId to loaded) }
        return loaded
    }

    fun adjacent(records: List<NtkEpisodeRecord>, episodeId: EpisodeId): AdjacentEpisodes {
        val index = records.indexOfFirst { it.episode.id == episodeId }
        if (index < 0) return AdjacentEpisodes(null, null)
        return AdjacentEpisodes(
            previous = records.getOrNull(index + 1)?.episode?.id,
            next = records.getOrNull(index - 1)?.episode?.id,
        )
    }

    fun title(records: List<NtkEpisodeRecord>, episodeId: EpisodeId): String =
        records.firstOrNull { it.episode.id == episodeId }?.episode?.title
            ?: episodeId.remoteKey.substringAfterLast('/')

    private suspend fun documentEpisodes(
        seriesId: SeriesId,
        key: NtkSeriesKey,
    ): List<NtkEpisodeRecord> = coroutineScope {
        val firstPayload = documents.text(key.path(), false)
        val pageCount = parser.episodePageCount(firstPayload)
        val semaphore = Semaphore(4)
        val remaining = (2..pageCount).map { page ->
            async { semaphore.withPermit { documents.text("${key.path()}?epage=$page", false) } }
        }.awaitAll()
        val merged = linkedMapOf<EpisodeId, NtkEpisodeRecord>()
        (listOf(firstPayload) + remaining).forEach { payload ->
            parser.episodes(payload, seriesId).episodes.forEach { record ->
                merged[record.episode.id] = merge(merged[record.episode.id], record)
            }
        }
        authoritativeEpisodeOrder(merged.values)
    }

    private fun merge(old: NtkEpisodeRecord?, new: NtkEpisodeRecord): NtkEpisodeRecord =
        if (old == null) new else new.copy(
            imageCount = new.imageCount ?: old.imageCount,
            imageEpisodeId = new.imageEpisodeId ?: old.imageEpisodeId,
            sequenceNumber = new.sequenceNumber ?: old.sequenceNumber,
        )

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
