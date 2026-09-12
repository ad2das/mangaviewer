package ml.melun.mangaview.source.newxtoon

import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
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
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceSeriesDetails
import ml.melun.mangaview.source.SourceThrottledException
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

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
) : ContentSource, Closeable {
    override val id = SourceId("newxtoon")
    private val parser = NewxtoonHtmlParser(config.origin)
    private val origin = config.origin
    private var cachedGenres: List<SourceGenre>? = null
    private var lastSeriesDetails: Pair<SeriesId, SourceSeriesDetails>? = null

    override suspend fun genres(kind: SeriesKind): List<SourceGenre> {
        cachedGenres?.let { return it }
        val parsed = runCatching { parser.genres(fetch("/comics")) }.getOrNull()
        val result = parsed?.takeIf { it.isNotEmpty() } ?: FALLBACK_NEWXTOON_GENRES
        cachedGenres = result
        return result
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        val html = fetch("/search?q=" + URLEncoder.encode(query, "UTF-8"))
        return SourcePage(parser.seriesCards(html).map(::series), null)
    }

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = query.cursor?.toIntOrNull() ?: 1
        val html = fetch("/comics?" + catalogQuery(query, page))
        return SourcePage(parser.seriesCards(html).map(::series), parser.nextPage(html, page)?.toString())
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
        return params.joinToString("&")
    }

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
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

    private suspend fun chapters(seriesId: SeriesId): List<NewxtoonChapter> {
        val html = fetch(seriesPath(seriesId))
        val details = parser.seriesDetails(html)
        lastSeriesDetails = seriesId to SourceSeriesDetails(
            status = details.status,
            description = details.description,
            authors = details.authors,
        )
        return parser.chapters(html)
    }

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
        val chapters = runCatching { chapters(episodeId.seriesId) }.getOrElse { return null to null }
        val index = chapters.indexOfFirst { it.id == episodeId.remoteKey }
        if (index < 0) return null to null
        val previous = chapters.getOrNull(index - 1)?.let { EpisodeId(episodeId.seriesId, it.id) }
        val next = chapters.getOrNull(index + 1)?.let { EpisodeId(episodeId.seriesId, it.id) }
        return previous to next
    }

    private suspend fun fetch(
        path: String,
        extra: Map<String, String> = emptyMap(),
        priority: PageFetchPriority = PageFetchPriority.NORMAL,
    ): String {
        var attempt = 0
        while (true) {
            val response = transport.execute(SourceRequest(
                url = origin + path,
                headers = baseHeaders() + extra,
                priority = priority,
            ))
            val status = response.statusCode
            if (status in 200..299) {
                try {
                    return response.readBytes(MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8)
                } finally {
                    response.close()
                }
            }
            val retryable = status in RETRYABLE_STATUS_CODES && attempt < MAX_FETCH_ATTEMPTS - 1
            val retryAfterMillis = if (retryable) {
                response.header("Retry-After")?.trim()?.toLongOrNull()
                    ?.times(1_000L)
                    ?.coerceIn(MIN_RETRY_DELAY_MILLIS, MAX_RETRY_DELAY_MILLIS)
            } else {
                null
            }
            response.close()
            if (!retryable) {
                if (status == 429) {
                    throw SourceThrottledException("NEWXTOON request throttled with 429: $path")
                }
                throw IOException("NEWXTOON request failed with $status: $path")
            }
            delay(retryAfterMillis ?: RETRY_DELAYS_MILLIS[minOf(attempt, RETRY_DELAYS_MILLIS.lastIndex)])
            attempt += 1
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to config.userAgent,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private fun series(card: NewxtoonSeriesCard) =
        SourceSeries(
            SeriesId(id, card.id),
            card.title,
            subtitle = card.subtitle,
            thumbnailKey = card.thumbnailUrl,
            status = card.status,
        )

    private fun seriesPath(seriesId: SeriesId) = "/comics/${seriesId.remoteKey}"

    private fun episodePath(episodeId: EpisodeId) = "${seriesPath(episodeId.seriesId)}/chapters/${episodeId.remoteKey}"

    private companion object {
        const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
        const val MAX_FETCH_ATTEMPTS = 3
        const val MIN_RETRY_DELAY_MILLIS = 250L
        const val MAX_RETRY_DELAY_MILLIS = 3_000L
        val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)
        val RETRY_DELAYS_MILLIS = longArrayOf(600L, 1_400L)
    }
}
