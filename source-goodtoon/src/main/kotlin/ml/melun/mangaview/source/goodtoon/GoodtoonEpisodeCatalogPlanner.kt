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

/**
 * Provider-order chapter pagination accumulator.
 *
 * The provider lists chapters newest first and pages the AJAX fragment with `?t=N`. A page that
 * contributes no unseen episode is the walk's terminal signal: today the deployment serves the
 * whole list for any page, so the probe repeats and stops, while a paginated deployment is walked
 * to its last page. Absorption never reorders and never consults [SourceEpisode.sequenceNumber].
 */
class GoodtoonEpisodeCatalogAccumulator {
    private val seen = LinkedHashMap<String, SourceEpisode>()
    private var pages = 0

    /** Returns true while the page contributes at least one unseen episode. */
    fun absorb(episodes: List<SourceEpisode>): Boolean {
        check(pages < MAX_CHAPTER_PAGES) { "GoodToon chapter catalog exceeded $MAX_CHAPTER_PAGES pages" }
        pages += 1
        var added = false
        for (episode in episodes) {
            if (seen.putIfAbsent(episode.id.remoteKey, episode) == null) added = true
        }
        return added
    }

    fun episodes(): List<SourceEpisode> = Collections.unmodifiableList(seen.values.toList())

    private companion object {
        const val MAX_CHAPTER_PAGES = 512
    }
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
        GoodtoonEpisodeCatalogAccumulator().let { accumulator ->
            pages.forEach { accumulator.absorb(it.episodes) }
            accumulator.episodes()
        }
}
