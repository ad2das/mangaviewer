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
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SearchField

internal class NtkCatalogService(
    private val sourceId: SourceId,
    private val searchPageSize: Int,
    private val documents: NtkDocumentClient,
    private val parser: NtkDocumentParser,
) {
    private val mutex = Mutex()
    private var catalogs: Map<SeriesId, List<NtkEpisodeRecord>> = emptyMap()

    suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        return search(SourceSearchQuery(query, cursor = cursor))
    }

    suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> {
        val page = query.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val encoded = URLEncoder.encode(query.text.trim(), Charsets.UTF_8.name())
        if (query.field == SearchField.AUTHOR) return authorSearch(query, page, encoded)
        val path = "/api/works?keyword=$encoded&page=$page&pageSize=$searchPageSize&withTotal=1"
        val api = attempt { parser.searchApi(documents.text(path, json = true), sourceId) }
        if (api != null && api.series.isNotEmpty()) {
            val items = filterKind(api.series, query.kind)
            val hasNext = api.total?.let { page * searchPageSize < it }
                ?: (api.series.size == searchPageSize)
            return SourcePage(items, if (hasNext) (page + 1).toString() else null)
        }
        val html = parser.searchHtml(documents.text("/search?q=$encoded&field=title&match=contains", false), sourceId)
        return SourcePage(filterKind(html, query.kind))
    }

    private suspend fun authorSearch(query: SourceSearchQuery, page: Int, encoded: String): SourcePage<SourceSeries> {
        if (page > 1) return SourcePage(emptyList())
        val path = "/search?q=$encoded&field=author&match=contains"
        val items = parser.searchHtml(documents.text(path, false), sourceId)
        return SourcePage(filterKind(items, query.kind))
    }

    private fun filterKind(items: List<SourceSeries>, kind: SeriesKind?): List<SourceSeries> {
        if (kind == null) return items
        return items.filter { item ->
            val value = runCatching { NtkSeriesKey.decode(item.id).kind }.getOrNull()
            (kind == SeriesKind.COMIC && value == NtkKind.MANHWA) ||
                (kind == SeriesKind.WEBTOON && value == NtkKind.WEBTOON)
        }
    }

    suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = query.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val endpoint = if (query.kind == SeriesKind.COMIC) "manhwa-list" else "works"
        val parameters = buildList {
            add("status=${if (query.kind == SeriesKind.WEBTOON) "ing" else ""}")
            when (query.order) {
                CatalogOrder.POPULAR -> add("sort=hot")
                CatalogOrder.LATEST -> add("sort=recent")
                CatalogOrder.NEW -> add("sort=new")
            }
            query.genre?.let { genre ->
                val key = if (query.kind == SeriesKind.COMIC) "g" else "tag"
                add("$key=${URLEncoder.encode(genre.key, Charsets.UTF_8.name())}")
            }
            add("page=$page")
            add("pageSize=$searchPageSize")
            add("withTotal=1")
        }
        val parsed = attempt {
            parser.searchApi(
                documents.text("/api/$endpoint?${parameters.joinToString("&")}", json = true),
                sourceId,
                if (query.kind == SeriesKind.COMIC) NtkKind.MANHWA else NtkKind.WEBTOON,
            )
        }
        if (parsed != null && parsed.series.isNotEmpty()) {
            val hasNext = parsed.total?.let { page * searchPageSize < it }
                ?: (parsed.series.size == searchPageSize)
            return SourcePage(parsed.series, if (hasNext) (page + 1).toString() else null)
        }
        if (page > 1) return SourcePage(emptyList())
        return SourcePage(parser.searchHtml(documents.text(catalogPath(query), false), sourceId))
    }

    suspend fun genres(kind: SeriesKind): List<SourceGenre> {
        val path = if (kind == SeriesKind.COMIC) "/manhwa" else "/ing"
        val parsed = attempt { parser.genres(documents.text(path, false), kind) }.orEmpty()
        return parsed.ifEmpty { fallbackGenres(kind) }
    }

    private fun catalogPath(query: CatalogQuery): String {
        val root = if (query.kind == SeriesKind.COMIC) "/manhwa" else "/ing"
        val parameters = buildList {
            if (query.order == CatalogOrder.POPULAR) add("sort=hot")
            if (query.order == CatalogOrder.NEW) add("sort=new")
            query.genre?.let { genre ->
                val key = if (query.kind == SeriesKind.COMIC) "g" else "tag"
                add("$key=${URLEncoder.encode(genre.key, Charsets.UTF_8.name())}")
            }
        }
        return if (parameters.isEmpty()) root else "$root?${parameters.joinToString("&")}"
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

    private fun fallbackGenres(kind: SeriesKind): List<SourceGenre> = when (kind) {
        SeriesKind.WEBTOON -> NTK_WEBTOON_GENRES.map { (key, label) -> SourceGenre(key, label) }
        SeriesKind.COMIC -> NTK_COMIC_GENRES.map { SourceGenre(it, it) }
    }

    private companion object {
        val NTK_WEBTOON_GENRES = listOf(
            "1" to "학원", "2" to "액션", "3" to "SF", "4" to "스토리", "5" to "판타지",
            "6" to "BL/백합", "7" to "개그/코미디", "8" to "연애/순정", "9" to "드라마",
            "10" to "로맨스", "11" to "시대", "12" to "스포츠", "13" to "일상",
            "14" to "추리/미스터리", "15" to "공포/스릴러", "16" to "성인",
            "17" to "옴니버스", "18" to "에피소드", "19" to "무협", "20" to "소년",
            "99" to "기타",
        )
        val NTK_COMIC_GENRES = listOf(
            "순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션",
            "백합", "일상", "SF", "이세계", "스릴러", "애니화", "전생", "스포츠", "TS",
            "소년", "먹방", "붕탁", "게임", "호러", "시대", "로맨스", "추리", "음악", "무협", "BL",
        )
    }
}
