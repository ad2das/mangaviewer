package ml.melun.mangaview.source.ntk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Separate provider categories keep novels/anime from displacing readable search results. */
internal class NtkSearchService(
    private val sourceId: SourceId,
    private val parser: NtkDocumentParser,
    private val document: suspend (String) -> String,
) {
    suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> {
        val kind = query.kind
        if (kind != null) return page(query, kind, positivePage(query.cursor ?: "1"))
        val cursor = query.cursor?.let { value ->
            requireNotNull(ALL_CURSOR.matchEntire(value)) { "NTK search cursor is malformed" }
                .groupValues.drop(1).map(String::toInt)
        } ?: listOf(1, 1)
        require(cursor.any { it > 0 }) { "NTK search cursor has already ended" }
        return coroutineScope {
            val webtoon = async { if (cursor[0] == 0) SourcePage(emptyList()) else page(query, SeriesKind.WEBTOON, cursor[0]) }
            val comic = async { if (cursor[1] == 0) SourcePage(emptyList()) else page(query, SeriesKind.COMIC, cursor[1]) }
            val left = webtoon.await()
            val right = comic.await()
            val next = if (left.nextCursor == null && right.nextCursor == null) null
                else "w${left.nextCursor ?: "0"}:m${right.nextCursor ?: "0"}"
            SourcePage((left.items + right.items).distinctBy { it.id }, next)
        }
    }

    private suspend fun page(query: SourceSearchQuery, kind: SeriesKind, number: Int): SourcePage<SourceSeries> {
        val wireKind = if (kind == SeriesKind.COMIC) "manhwa" else "webtoon"
        val field = if (query.field == SearchField.AUTHOR) "author" else "title"
        val encoded = URLEncoder.encode(query.text.trim(), "UTF-8")
        val html = document("/search?q=$encoded&field=$field&match=contains&kind=$wireKind&page=$number")
        val dom = Jsoup.parse(html)
        val parsed = parser.searchHtml(html, sourceId)
        check(parsed.isNotEmpty() || dom.selectFirst(".search-results-grid, .search-page-form") != null) {
            "NTK 검색 응답을 확인할 수 없습니다. 다시 시도해 주세요"
        }
        dom.selectFirst(".pager-num.is-active")?.text()?.toIntOrNull()?.let { actual ->
            check(actual == number) { "NTK 검색 페이지가 반복되었습니다. 다시 시도해 주세요" }
        }
        return SourcePage(
            parsed.filter { NtkSeriesKey.decode(it.id).kind.pathSegment == wireKind },
            nextPage(dom, query, wireKind, field, number)?.toString(),
        )
    }

    private fun nextPage(dom: Document, query: SourceSearchQuery, kind: String, field: String, current: Int): Int? =
        dom.select("a[href]").mapNotNull { link ->
            val uri = runCatching { URI(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            if (uri.path != "/search") return@mapNotNull null
            val parameters = runCatching { uri.rawQuery.orEmpty().split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            } }.getOrNull() ?: return@mapNotNull null
            if (parameters["q"] != query.text.trim() || parameters["kind"] != kind ||
                parameters.getOrDefault("field", "title") != field ||
                parameters.getOrDefault("match", "contains") != "contains" ||
                parameters.getOrDefault("sort", "recent") != "recent"
            ) return@mapNotNull null
            parameters["page"]?.toIntOrNull()?.takeIf { it > current }
        }.minOrNull()?.let { current + 1 }

    private fun positivePage(value: String): Int = requireNotNull(value.toIntOrNull()) {
        "NTK search cursor is malformed"
    }.also { require(it > 0 && it.toString() == value) { "NTK search cursor is malformed" } }

    private companion object {
        val ALL_CURSOR = Regex("w(0|[1-9][0-9]*):m(0|[1-9][0-9]*)")
    }
}
