package ml.melun.mangaview.source.ntk

import java.net.URI
import java.util.Collections
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceRequest
import org.jsoup.Jsoup

class NtkEpisodeCatalogPage internal constructor(records: List<NtkEpisodeRecord>, val lastPage: Int) {
    internal val records = Collections.unmodifiableList(records.toList())
}

/** Source catalog parsing and pagination only; all HTTP execution belongs to global work. */
class NtkEpisodeCatalogPlanner(private val userAgent: String) {
    private val parser = NtkDocumentParser()

    fun apiRequest(series: SeriesId, origin: URI): SourceRequest {
        val key = NtkSeriesKey.decode(series)
        return request(origin.resolve("/api/${key.kind.pathSegment}/${key.workKey}/episodes"), true)
    }

    fun documentRequest(series: SeriesId, origin: URI, page: Int): SourceRequest {
        require(page > 0)
        val path = NtkSeriesKey.decode(series).path()
        return request(origin.resolve(if (page == 1) path else "$path?epage=$page"), false)
    }

    /** A missing or incomplete authoritative API total requires the paginated series document. */
    fun parseApi(series: SeriesId, document: SourceDocument): NtkEpisodeCatalogPage? {
        val key = NtkSeriesKey.decode(series)
        require(document.finalUrl.path == "/api/${key.kind.pathSegment}/${key.workKey}/episodes")
        val result = parser.episodesApi(document.text(), series)
        if (result.authoritativeTotal == null || !result.isComplete || result.episodes.isEmpty()) return null
        return NtkEpisodeCatalogPage(result.episodes, 1)
    }

    fun parseDocument(series: SeriesId, document: SourceDocument): NtkEpisodeCatalogPage {
        val path = NtkSeriesKey.decode(series).path()
        require(document.finalUrl.path == path) { "NTK catalog redirected to another series" }
        val text = document.text()
        val records = parser.episodes(text, series).episodes
        require(records.isNotEmpty()) { "NTK catalog page contains no episodes" }
        val pages = Jsoup.parse(text, document.finalUrl.toString()).select("a[href]").mapNotNull { link ->
            val url = runCatching { document.finalUrl.resolve(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            if (url.authority != document.finalUrl.authority || url.path != path) return@mapNotNull null
            url.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("epage=") }?.substringAfter('=')?.let {
                require(it.matches(Regex("[0-9]+"))) { "NTK catalog page is invalid" }
                requireNotNull(it.toIntOrNull()).also { page -> require(page > 0) }
            }
        }
        return NtkEpisodeCatalogPage(records, pages.maxOrNull()?.coerceAtLeast(1) ?: 1)
    }

    fun merge(pages: List<NtkEpisodeCatalogPage>): List<SourceEpisode> {
        val merged = linkedMapOf<ml.melun.mangaview.core.EpisodeId, NtkEpisodeRecord>()
        for (page in pages) for (record in page.records) {
            val previous = merged[record.episode.id]
            if (previous != null) {
                require(previous.sequenceNumber == null || record.sequenceNumber == null ||
                    previous.sequenceNumber == record.sequenceNumber) { "NTK catalog sequence changed during pagination" }
            }
            merged[record.episode.id] = record.copy(
                imageCount = record.imageCount ?: previous?.imageCount,
                imageEpisodeId = record.imageEpisodeId ?: previous?.imageEpisodeId,
                sequenceNumber = record.sequenceNumber ?: previous?.sequenceNumber,
            )
        }
        return Collections.unmodifiableList(authoritativeEpisodeOrder(merged.values).map { it.episode })
    }

    private fun request(url: URI, json: Boolean) = SourceRequest(url.toString(), headers = mapOf(
        "User-Agent" to userAgent, "Accept" to if (json) "application/json" else "text/html,application/xhtml+xml",
    ))
    private fun SourceDocument.text() = openBody().bufferedReader(Charsets.UTF_8).use { it.readText() }
}
