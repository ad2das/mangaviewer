package ml.melun.mangaview.source.wfwf

import java.net.URI
import java.util.Collections
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceRequest
import org.jsoup.Jsoup

class WfwfEpisodeCatalogPage(episodes: List<SourceEpisode>, val lastPage: Int) {
    val episodes: List<SourceEpisode> = Collections.unmodifiableList(episodes.toList())
}

/** Pure episode-catalog construction/parsing; no transport, cache, retry or execution ownership. */
class WfwfEpisodeCatalogPlanner(private val userAgent: String) {
    private val parser = WfwfHtmlParser()

    fun request(series: SeriesId, origin: URI, page: Int): SourceRequest {
        require(series.sourceId.value == "wfwf" && page > 0)
        val key = WfwfSeriesKey.decode(series)
        val route = if (key.kind == WfwfKind.COMIC) "/cl" else "/list"
        return SourceRequest(origin.resolve("$route?toon=${key.titleId}&pg=$page").toString(),
            headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,*/*"),
            priority = PageFetchPriority.NORMAL)
    }

    fun parse(series: SeriesId, document: SourceDocument): WfwfEpisodeCatalogPage {
        require(series.sourceId.value == "wfwf")
        val key = WfwfSeriesKey.decode(series)
        return document.openBody().use {
            val parsed = Jsoup.parse(it, null, document.finalUrl.toString())
            WfwfEpisodeCatalogPage(parser.episodes(parsed, series, key), parser.catalogPageNumbers(parsed, key).max())
        }
    }

    fun merge(pages: List<WfwfEpisodeCatalogPage>): List<SourceEpisode> =
        Collections.unmodifiableList(parser.mergeEpisodePages(pages.map { it.episodes }))
}
