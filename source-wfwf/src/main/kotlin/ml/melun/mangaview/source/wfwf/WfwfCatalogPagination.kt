package ml.melun.mangaview.source.wfwf

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceGenre
import org.jsoup.nodes.Document

/** Series-catalog pagination only; episode catalog page links use a separate parser contract. */
internal object WfwfCatalogPagination {
    fun path(query: CatalogQuery, page: Int): String {
        require(page > 0) { "WFWF catalog page must be positive" }
        val order = when (query.order) {
            CatalogOrder.LATEST -> "n"
            CatalogOrder.NEW -> "r"
            CatalogOrder.POPULAR -> "f"
        }
        val route = query.genre?.let(::genreRoute)
        return when (query.kind) {
            SeriesKind.COMIC -> {
                require(route == null || route.first == "t3") { "WFWF comic genre route is invalid" }
                "/cm?o=$order&pg=$page&t3=${encoded(route?.second.orEmpty())}"
            }
            SeriesKind.WEBTOON -> {
                val t2 = route?.takeIf { it.first == "t2" }?.second.orEmpty()
                val t3 = route?.takeIf { it.first == "t3" }?.second.orEmpty()
                "/ing?o=$order&pg=$page&t1=&t2=${encoded(t2)}&t3=${encoded(t3)}"
            }
        }
    }

    private fun genreRoute(genre: SourceGenre): Pair<String, String> {
        val separator = genre.key.indexOf(':')
        require(separator > 0 && separator < genre.key.lastIndex) {
            "WFWF genre key has no provider route"
        }
        val parameter = genre.key.substring(0, separator)
        require(parameter == "t2" || parameter == "t3") { "WFWF genre route is invalid" }
        return parameter to genre.key.substring(separator + 1)
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, Charset.forName("EUC-KR").name())

    fun page(cursor: String?): Int {
        if (cursor == null) return 1
        val page = cursor.toIntOrNull()
        require(page != null && page.toString() == cursor) {
            "WFWF catalog cursor must be a canonical positive page number"
        }
        require(page > 0) {
            "WFWF catalog cursor must be a canonical positive page number"
        }
        return page
    }

    fun nextPageCursor(document: Document, canonicalFirstPagePath: String, currentPage: Int): String? {
        val next = higherPages(document, canonicalFirstPagePath, currentPage).firstOrNull() ?: return null
        val expectedNext = currentPage.toLong() + 1L
        check(expectedNext <= Int.MAX_VALUE && next.toLong() == expectedNext) {
            "WFWF catalog pagination skipped an observed page after $currentPage"
        }
        return next.toString()
    }

    fun higherPages(document: Document, canonicalFirstPagePath: String, currentPage: Int): List<Int> {
        require(currentPage > 0)
        val canonical = runCatching { URI(canonicalFirstPagePath) }.getOrNull()
            ?: return emptyList()
        val expectedQuery = query(canonical.rawQuery) ?: return emptyList()
        val base = document.baseUri().takeIf(String::isNotBlank)?.let { runCatching { URI(it) }.getOrNull() }

        return document.select(".pagi a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val candidate = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
                if (!sameOrigin(candidate, base) || candidate.rawPath != canonical.rawPath) return@mapNotNull null
                val candidateQuery = query(candidate.rawQuery) ?: return@mapNotNull null
                if (candidateQuery.keys != expectedQuery.keys) return@mapNotNull null
                if (candidateQuery.any { (key, value) -> key != "pg" && expectedQuery[key] != value }) {
                    return@mapNotNull null
                }
                val pageValue = candidateQuery["pg"] ?: return@mapNotNull null
                val candidatePage = runCatching { page(pageValue) }.getOrNull()
                    ?: return@mapNotNull null
                candidatePage.takeIf { it > currentPage }
            }
            .distinct()
            .sorted()
    }

    private fun sameOrigin(candidate: URI, base: URI?): Boolean {
        if (candidate.rawAuthority == null && !candidate.isAbsolute) return true
        if (base == null || candidate.host == null || base.host == null) return false
        val scheme = candidate.scheme ?: base.scheme
        return scheme.equals(base.scheme, ignoreCase = true) &&
            candidate.host.equals(base.host, ignoreCase = true) &&
            candidate.port == base.port
    }

    private fun query(rawQuery: String?): Map<String, String>? {
        if (rawQuery == null) return null
        val values = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { component ->
            val separator = component.indexOf('=')
            if (separator <= 0) return null
            val key = component.substring(0, separator)
            val value = component.substring(separator + 1)
            if (values.put(key, value) != null) return null
        }
        return values
    }
}
