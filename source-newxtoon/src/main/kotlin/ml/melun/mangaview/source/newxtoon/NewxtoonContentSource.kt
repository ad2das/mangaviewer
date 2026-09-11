package ml.melun.mangaview.source.newxtoon

import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

const val DEFAULT_NEWXTOON_ORIGIN = "https://newxtoon1.com"

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

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> {
        val html = fetch("/search?q=" + URLEncoder.encode(query, "UTF-8"))
        return SourcePage(parser.seriesCards(html).map(::series), null)
    }

    override suspend fun catalog(query: CatalogQuery): SourcePage<SourceSeries> {
        val page = query.cursor?.toIntOrNull() ?: 1
        val html = fetch("/comics?page=$page")
        return SourcePage(parser.seriesCards(html).map(::series), parser.nextPage(html, page)?.toString())
    }

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> {
        val chapters = chapters(seriesId)
        return SourcePage(chapters.mapIndexed { index, chapter ->
            SourceEpisode(EpisodeId(seriesId, chapter.id), chapter.title, sequenceNumber = (index + 1).toDouble())
        }, null)
    }

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        val html = fetch(episodePath(episodeId))
        val pages = parser.pages(html)
        check(pages.isNotEmpty()) { "NEWXTOON chapter has no page images" }
        val specs = pages.mapIndexed { index, page ->
            PageSpec(PageId(episodeId, page.url), index, dimensions(page))
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

    private suspend fun chapters(seriesId: SeriesId): List<NewxtoonChapter> =
        parser.chapters(fetch(seriesPath(seriesId)))

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
        val response = transport.execute(SourceRequest(
            url = origin + path,
            headers = baseHeaders() + extra,
            priority = priority,
        ))
        try {
            if (response.statusCode !in 200..299) {
                throw IOException("NEWXTOON request failed with ${response.statusCode}: $path")
            }
            return response.readBytes(MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8)
        } finally {
            response.close()
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to config.userAgent,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private fun series(card: NewxtoonSeriesCard) =
        SourceSeries(SeriesId(id, card.id), card.title, thumbnailKey = card.thumbnailUrl)

    private fun dimensions(page: NewxtoonPage): PageDimensions? {
        val width = page.width ?: return null
        val height = page.height ?: return null
        return if (width > 0 && height > 0) PageDimensions(width, height) else null
    }

    private fun seriesPath(seriesId: SeriesId) = "/comics/${seriesId.remoteKey}"

    private fun episodePath(episodeId: EpisodeId) = "${seriesPath(episodeId.seriesId)}/chapters/${episodeId.remoteKey}"

    private companion object {
        const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
    }
}
