package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
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
        val text = query.text.trim()
        val encoded = URLEncoder.encode(text, "UTF-8")
        val field = if (query.field == SearchField.AUTHOR) "author" else "title"
        // Current mirrors render the site search at "/search?q=&kind=&field=&match=contains&page=".
        // Older mirrors keep that URL as an unrelated page and serve search from the listing route
        // ("/manhwa?stx=", "/webtoon?stx="), so a response without the provider search markers is
        // rejected instead of accepting the unfiltered page that route can also render.
        val searchHtml = try {
            document("/search?q=$encoded&field=$field&match=contains&kind=$wireKind&page=$number")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        }
        if (searchHtml != null) {
            val dom = Jsoup.parse(searchHtml)
            if (dom.selectFirst(".search-results-grid, .search-page-form") != null) {
                val parsed = parser.searchHtml(searchHtml, sourceId)
                checkActivePage(dom, number)
                return SourcePage(
                    parsed.filter { NtkSeriesKey.decode(it.id).kind.pathSegment == wireKind },
                    nextPage(dom, text, wireKind, number)?.toString(),
                )
            }
        }
        val html = document("/$wireKind?stx=$encoded&page=$number")
        val dom = Jsoup.parse(html)
        val parsed = parser.searchHtml(html, sourceId)
        // The listing route also renders the unfiltered category page, so the legacy search layout
        // marker must be present; parsed items alone would accept that unfiltered page silently.
        check(dom.selectFirst(".list-page") != null) {
            "NTK 검색 응답을 확인할 수 없습니다. 다시 시도해 주세요"
        }
        checkActivePage(dom, number)
        return SourcePage(
            parsed.filter { NtkSeriesKey.decode(it.id).kind.pathSegment == wireKind },
            nextPage(dom, text, wireKind, number)?.toString(),
        )
    }

    private fun checkActivePage(dom: Document, number: Int) {
        dom.selectFirst(".pager-num.is-active")?.text()?.toIntOrNull()?.let { actual ->
            check(actual == number) { "NTK 검색 페이지가 반복되었습니다. 다시 시도해 주세요" }
        }
    }

    private fun nextPage(dom: Document, text: String, kind: String, current: Int): Int? =
        dom.select("a[href]").mapNotNull { link ->
            val uri = runCatching { URI(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            val parameters = runCatching { uri.rawQuery.orEmpty().split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            } }.getOrNull() ?: return@mapNotNull null
            val supported = when (uri.path) {
                "/search" -> parameters["q"] == text && parameters["kind"] == kind
                "/$kind" -> parameters["stx"] == text
                else -> false
            }
            if (!supported) return@mapNotNull null
            parameters["page"]?.toIntOrNull()?.takeIf { it > current }
        }.minOrNull()?.let { current + 1 }

    private fun positivePage(value: String): Int = requireNotNull(value.toIntOrNull()) {
        "NTK search cursor is malformed"
    }.also { require(it > 0 && it.toString() == value) { "NTK search cursor is malformed" } }

    private companion object {
        val ALL_CURSOR = Regex("w(0|[1-9][0-9]*):m(0|[1-9][0-9]*)")
    }
}
