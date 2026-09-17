package ml.melun.mangaview.source.newxtoon

import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSeriesDetails
import ml.melun.mangaview.source.SourceThrottledException
import ml.melun.mangaview.source.SourceTransport

const val DEFAULT_NEWXTOON_ORIGIN = "https://newxtoon1.com"

private val FALLBACK_NEWXTOON_GENRES = listOf(
    "1" to "로맨스", "4" to "드라마", "2" to "판타지", "2739" to "로맨스판타지", "2753" to "성장물",
    "3" to "액션", "2902" to "능력녀", "2774" to "소설원작", "2777" to "왕족/귀족", "2904" to "다정남",
    "2772" to "먼치킨", "2903" to "로맨틱코미디", "2905" to "능력남", "3266" to "완결로맨스",
    "2771" to "달달물", "6" to "개그/코미디", "2874" to "성장", "2754" to "복수", "2743" to "무협/사극",
    "2757" to "빙의",
).map { (id, label) -> SourceGenre("genre:$id", label) }

data class NewxtoonConfig(
    val origin: String = DEFAULT_NEWXTOON_ORIGIN,
    val userAgent: String,
)

/** Server-rendered catalog, chapters and reader pages for newxtoon. */
class NewxtoonContentSource(
    private val config: NewxtoonConfig,
    private val transport: SourceTransport,
    clock: () -> Long = System::currentTimeMillis,
) : ContentSource, Closeable {
    override val id = SourceId("newxtoon")
    private val parser = NewxtoonHtmlParser(config.origin)
    private val origin = config.origin
    private val documents = NewxtoonDocumentClient(transport, clock)
    private var cachedGenres: List<SourceGenre>? = null
    private var lastSeriesDetails: Pair<SeriesId, SourceSeriesDetails>? = null

    override suspend fun genres(kind: SeriesKind): List<SourceGenre> {
        cachedGenres?.let { return it }
        val parsed = try { parser.genres(fetch("/comics")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val result = parsed?.takeIf { it.isNotEmpty() } ?: FALLBACK_NEWXTOON_GENRES
        cachedGenres = result
        return result
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        val text = query.trim()
        require(text.length in 2..100) { "뉴엑스툰 검색어는 2~100자로 입력해 주세요" }
        val page = cursor?.let { value ->
            requireNotNull(value.toIntOrNull()).also {
                require(it > 0 && it.toString() == value) { "검색 페이지를 확인할 수 없습니다" }
            }
        } ?: 1
        val html = fetch("/search?q=" + URLEncoder.encode(text, "UTF-8") + "&page=$page") { parser.searchCards(it) }
        return SourcePage(parser.searchCards(html).map(::series), parser.nextSearchPage(html, text, page)?.toString())
    }

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = query.cursor?.toIntOrNull() ?: 1
        val html = fetch("/comics?" + catalogQuery(query, page))
        val segmentStatus = query.statusFilter?.let {
            if (it == SeriesStatus.HIATUS) SeriesStatus.ONGOING else it
        }
        return SourcePage(
            parser.seriesCards(html).map { series(it, segmentStatus) },
            parser.nextPage(html, page)?.toString(),
        )
    }

    private fun catalogQuery(query: CatalogQuery, page: Int): String {
        val params = mutableListOf(
            "page=$page",
            "sort=" + if (query.order == CatalogOrder.POPULAR) "popular" else "latest",
        )
        query.genre?.key?.let { key ->
            when {
                key.startsWith("genre:") -> params += "genre=" + key.removePrefix("genre:")
                key.startsWith("category:") -> params +=
                    "category=" + URLEncoder.encode(key.removePrefix("category:"), "UTF-8")
            }
        }
        // The provider filters the whole catalog by its Korean status labels.
        query.statusFilter?.let { params += "status=" + URLEncoder.encode(it.statusParam(), "UTF-8") }
        return params.joinToString("&")
    }

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        if (cursor != null) return SourcePage(emptyList())
        val chapters = chapters(seriesId)
        // The provider lists chapters newest-first, so positional sequence numbers count down:
        // the final entry (the first chapter) gets 1 and wins firstEpisode()'s minimum.
        return SourcePage(chapters.mapIndexed { index, chapter ->
            SourceEpisode(
                EpisodeId(seriesId, chapter.id),
                chapter.title,
                sequenceNumber = (chapters.size - index).toDouble(),
            )
        }, null)
    }

    override suspend fun episodeCatalog(
        seriesId: SeriesId,
        onPartial: suspend (List<SourceEpisode>) -> Unit,
    ): List<SourceEpisode> = chapters(seriesId) { partial ->
        onPartial(partial.map { SourceEpisode(EpisodeId(seriesId, it.id), it.title) })
    }.let { chapters ->
        chapters.mapIndexed { index, chapter -> SourceEpisode(EpisodeId(seriesId, chapter.id), chapter.title,
            sequenceNumber = (chapters.size - index).toDouble()) }
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        val html = fetch(episodePath(episodeId))
        val pages = parser.pages(html)
        check(pages.isNotEmpty()) { "NEWXTOON chapter has no page images" }
        val specs = pages.mapIndexed { index, _ ->
            PageSpec(PageId(episodeId, pages[index].url), index)
        }
        val (previous, next) = neighbors(episodeId)
        val title = parser.title(html)?.takeIf { it.isNotBlank() } ?: episodeId.remoteKey
        return EpisodeManifest(episodeId, title, specs, previous, next)
    }

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes {
        val (previous, next) = neighbors(episodeId)
        return AdjacentEpisodes(previous, next)
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
        openPage(pageId, validation, PageFetchPriority.NORMAL)

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        val response = transport.execute(SourceRequest(
            url = pageId.remoteKey,
            headers = baseHeaders() + mapOf(
                "Referer" to origin + episodePath(pageId.episodeId),
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            ),
            priority = priority,
        ))
        if (response.statusCode !in 200..299) {
            response.close()
            throw IOException("NEWXTOON image failed with ${response.statusCode}")
        }
        return OpenedPage(response.body, response.contentLength, response.contentType,
            response.header("ETag"), response.header("Last-Modified"))
    }

    override suspend fun openArtwork(series: SourceSeries): OpenedPage? {
        val url = series.thumbnailKey?.takeIf { it.startsWith("http") } ?: return null
        val response = transport.execute(SourceRequest(url, headers = baseHeaders(), priority = PageFetchPriority.BACKGROUND))
        if (response.statusCode !in 200..299) {
            response.close()
            return null
        }
        return OpenedPage(response.body, response.contentLength, response.contentType, null, null)
    }

    override suspend fun seriesUrl(seriesId: SeriesId): String = origin + seriesPath(seriesId)

    override fun close() = Unit

    private suspend fun chapters(
        seriesId: SeriesId,
        onPartial: suspend (List<NewxtoonChapter>) -> Unit = {},
    ): List<NewxtoonChapter> {
        require(seriesId.sourceId == id) { "Series belongs to another source" }
        val html = fetch(seriesPath(seriesId))
        val details = parser.seriesDetails(html)
        lastSeriesDetails = seriesId to SourceSeriesDetails(
            status = details.status,
            description = details.description,
            authors = details.authors,
        )
        val embedded = parser.chapters(html)
        val pagination = parser.chapterPagination(html) ?: return embedded
        val merged = LinkedHashMap<String, NewxtoonChapter>()
        embedded.forEach { merged.putIfAbsent(it.id, it) }
        // The series page already renders the first chapter page; the feed serves the rest.
        val firstPage = if (embedded.isEmpty()) 1 else pagination.nextPage
        if (firstPage != null && merged.isNotEmpty()) onPartial(merged.values.toList())
        // The header advertises the total chapter count and the feed page size, so the whole feed
        // range is known and can be fetched in parallel windows instead of one round trip per page.
        val pageSize = parser.chapterPageSize(html)
        val advertised = parser.chapterTotal(html)
        // Only trust the advertised total when it can actually cover the embedded list and stays
        // inside a sane page range; the discovery walk remains the authority for anything else.
        val lastPage = if (pageSize != null && advertised != null && advertised >= merged.size) {
            ((advertised + pageSize - 1) / pageSize).coerceAtLeast(1).takeIf { it <= MAX_CHAPTER_PAGES }
        } else null
        if (lastPage != null && advertised != null) {
            try {
                for (window in (2..lastPage).take(MAX_CHAPTER_PAGES).chunked(CHAPTER_PAGE_WINDOW)) {
                    fetchChapterPages(pagination.url, window).forEach { payload ->
                        payload.chapters.forEach { merged.putIfAbsent(it.id, it) }
                    }
                    if (merged.isNotEmpty()) onPartial(merged.values.toList())
                }
                if (merged.size >= advertised) return merged.values.toList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // A refused window must not lose the whole list. The gentler walk below reuses the
                // document cache, so pages the parallel intake already served cost no extra request.
            }
        }
        // No advertised total, or the parallel intake was refused: walk next_page in small windows.
        val visited = mutableSetOf<Int>()
        var next = firstPage
        while (true) {
            val start = next ?: break
            if (visited.size >= MAX_CHAPTER_PAGES) break
            val pages = (start until start + CHAPTER_DISCOVERY_WINDOW)
                .take((MAX_CHAPTER_PAGES - visited.size).coerceAtLeast(0))
                .filter { visited.add(it) }
            if (pages.isEmpty()) break
            val payloads = fetchChapterPages(pagination.url, pages)
            payloads.forEach { payload -> payload.chapters.forEach { merged.putIfAbsent(it.id, it) } }
            next = payloads.last().nextPage
            if (merged.isNotEmpty()) onPartial(merged.values.toList())
        }
        check(next == null) { "뉴엑스툰 회차 페이지가 반복되거나 너무 많습니다. 다시 시도해 주세요" }
        return merged.values.toList()
    }

    /** Fetches feed pages concurrently; the document lane still paces and throttles every request. */
    private suspend fun fetchChapterPages(feedUrl: String, pages: List<Int>): List<NewxtoonChapterPage> =
        coroutineScope {
            pages.map { page -> async { fetchChapterPage(feedUrl, page) } }.awaitAll()
        }

    /** One feed page with bounded retries, so a single throttled or failed page cannot kill the list. */
    private suspend fun fetchChapterPage(feedUrl: String, page: Int): NewxtoonChapterPage {
        var last: IOException? = null
        for (attempt in 0 until CHAPTER_PAGE_ATTEMPTS) {
            try {
                return parser.chapterPage(fetch(chapterPageUrl(feedUrl, page)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throttled: SourceThrottledException) {
                last = throttled
                delay(throttled.retryAfterMillis.coerceIn(250L, CHAPTER_PAGE_MAX_WAIT_MILLIS))
            } catch (failure: IOException) {
                last = failure
                delay(500L * (attempt + 1))
            }
        }
        throw checkNotNull(last) { "Newxtoon chapter page $page failed" }
    }

    private fun chapterPageUrl(feedUrl: String, page: Int): String =
        feedUrl + (if (feedUrl.contains('?')) "&" else "?") + "page=$page"

    /** The series page fetched for the chapter list already carries status/synopsis/authors. */
    override suspend fun seriesDetails(seriesId: SeriesId): SourceSeriesDetails? {
        lastSeriesDetails?.takeIf { it.first == seriesId }?.let { return it.second }
        val html = fetch(seriesPath(seriesId))
        val details = parser.seriesDetails(html)
        return SourceSeriesDetails(
            status = details.status,
            description = details.description,
            authors = details.authors,
        ).also { lastSeriesDetails = seriesId to it }
    }

    private suspend fun neighbors(episodeId: EpisodeId): Pair<EpisodeId?, EpisodeId?> {
        val chapters = try { chapters(episodeId.seriesId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null to null }
        val index = chapters.indexOfFirst { it.id == episodeId.remoteKey }
        if (index < 0) return null to null
        // The list is newest-first, so the earlier chapter sits at the higher index.
        val previous = chapters.getOrNull(index + 1)?.let { EpisodeId(episodeId.seriesId, it.id) }
        val next = chapters.getOrNull(index - 1)?.let { EpisodeId(episodeId.seriesId, it.id) }
        return previous to next
    }

    private suspend fun fetch(
        path: String,
        extra: Map<String, String> = emptyMap(),
        priority: PageFetchPriority = PageFetchPriority.NORMAL,
        validate: (String) -> Unit = {},
    ): String = documents.fetch(SourceRequest(
        url = if (path.startsWith("http://") || path.startsWith("https://")) path else origin + path,
        headers = baseHeaders() + extra,
        priority = priority,
    ), validate)

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to config.userAgent,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private fun series(card: NewxtoonSeriesCard, fallbackStatus: SeriesStatus? = null) =
        SourceSeries(
            SeriesId(id, card.id),
            card.title,
            subtitle = card.subtitle,
            thumbnailKey = card.thumbnailUrl,
            status = card.status ?: fallbackStatus,
        )

    private fun seriesPath(seriesId: SeriesId) = "/comics/${seriesId.remoteKey}"

    private fun episodePath(episodeId: EpisodeId) = "${seriesPath(episodeId.seriesId)}/chapters/${episodeId.remoteKey}"

    /** The provider files paused works under its ongoing segment, so HIATUS maps there too. */
    private fun SeriesStatus.statusParam(): String = when (this) {
        SeriesStatus.ONGOING, SeriesStatus.HIATUS -> "연재중"
        SeriesStatus.COMPLETED -> "완결"
    }

    private companion object {
        const val MAX_CHAPTER_PAGES = 400
        // The whole feed range is known when the header advertises the total, so it is fetched in
        // parallel windows. The window stays small: a wide burst invites the provider's throttle and
        // one refused page must not cost the list. The discovery path speculates at most one page.
        const val CHAPTER_PAGE_WINDOW = 4
        const val CHAPTER_DISCOVERY_WINDOW = 2
        const val CHAPTER_PAGE_ATTEMPTS = 3
        const val CHAPTER_PAGE_MAX_WAIT_MILLIS = 3_000L
    }
}
