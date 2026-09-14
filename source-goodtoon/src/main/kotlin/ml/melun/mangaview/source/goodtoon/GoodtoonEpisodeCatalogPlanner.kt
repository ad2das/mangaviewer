package ml.melun.mangaview.source.goodtoon

import java.net.URI
import java.util.Collections
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceRequest
import org.jsoup.Jsoup

class GoodtoonEpisodeCatalogPage(episodes: List<SourceEpisode>) {
    val episodes: List<SourceEpisode> = Collections.unmodifiableList(episodes.toList())
}

/** Pure episode-catalog construction/parsing; no transport, cache, retry or execution ownership. */
class GoodtoonEpisodeCatalogPlanner(private val userAgent: String) {
    private val parser = GoodtoonHtmlParser()

    fun request(series: SeriesId, origin: URI, page: Int): SourceRequest {
        require(series.sourceId.value == GOODTOON_SOURCE_ID && page > 0)
        val key = GoodtoonSeriesKey.decode(series)
        return SourceRequest(
            url = origin.resolve(key.chaptersPath(page)).toString(),
            headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,*/*"),
            priority = PageFetchPriority.NORMAL,
        )
    }

    fun parse(series: SeriesId, document: SourceDocument): GoodtoonEpisodeCatalogPage {
        require(series.sourceId.value == GOODTOON_SOURCE_ID)
        val key = GoodtoonSeriesKey.decode(series)
        return document.openBody().use {
            val parsed = Jsoup.parse(it, null, document.finalUrl.toString())
            GoodtoonEpisodeCatalogPage(parser.chapters(parsed, series, key))
        }
    }

    fun merge(pages: List<GoodtoonEpisodeCatalogPage>): List<SourceEpisode> =
        Collections.unmodifiableList(parser.mergeChapters(pages.map { it.episodes }))
}
