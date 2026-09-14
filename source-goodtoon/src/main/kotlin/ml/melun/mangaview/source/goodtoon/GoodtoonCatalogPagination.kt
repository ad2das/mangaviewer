package ml.melun.mangaview.source.goodtoon

import java.net.URI
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceGenre
import org.jsoup.nodes.Document

/**
 * Catalog route construction and pagination.
 *
 * The provider exposes exactly one sorted catalog: `/ongoing/` (연재중) and `/end/` (완결). All
 * CatalogOrder values share that order, so POPULAR/LATEST/NEW resolve to the same route; the
 * catalog cards carry no status, so the route supplies it.
 */
internal object GoodtoonCatalogPagination {
    fun path(query: CatalogQuery, page: Int): String {
        require(page > 0) { "GoodToon catalog page must be positive" }
        val route = if (query.statusFilter == SeriesStatus.COMPLETED) "/end/" else "/ongoing/"
        val parameters = buildList {
            genreWire(query.genre)?.let { add("genre=${urlEncode(it)}") }
            if (page > 1) add("pg=$page")
        }
        return if (parameters.isEmpty()) route else "$route?${parameters.joinToString("&")}"
    }

    fun page(cursor: String?): Int {
        if (cursor == null) return 1
        val page = cursor.toIntOrNull()
        require(page != null && page > 0 && page.toString() == cursor) {
            "GoodToon catalog cursor must be a canonical positive page number"
        }
        return page
    }

    fun nextPageCursor(document: Document, canonicalFirstPagePath: String, currentPage: Int): String? =
        higherPages(document, canonicalFirstPagePath, currentPage).firstOrNull()?.toString()

    fun higherPages(document: Document, canonicalFirstPagePath: String, currentPage: Int): List<Int> {
        require(currentPage > 0) { "GoodToon catalog page must be positive" }
        val canonical = runCatching { URI(canonicalFirstPagePath) }.getOrNull() ?: return emptyList()
        val expected = parameters(canonical.rawQuery)
        val base = document.baseUri().takeIf(String::isNotBlank)?.let { runCatching { URI(it) }.getOrNull() }
        return document.select(".pagination a[href]")
            .mapNotNull { link ->
                val candidate = runCatching { URI(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
                if (!sameOrigin(candidate, base) || candidate.rawPath != canonical.rawPath) return@mapNotNull null
                val query = parameters(candidate.rawQuery) ?: return@mapNotNull null
                if (query.filterKeys { it != "pg" } != expected) return@mapNotNull null
                val value = query["pg"] ?: return@mapNotNull null
                val candidatePage = runCatching { page(value) }.getOrNull() ?: return@mapNotNull null
                candidatePage.takeIf { it > currentPage }
            }
            .distinct()
            .sorted()
    }

    private fun genreWire(genre: SourceGenre?): String? {
        if (genre == null) return null
        val separator = genre.key.indexOf(':')
        require(separator > 0 && separator < genre.key.lastIndex) {
            "GoodToon genre key has no provider route"
        }
        require(genre.key.substring(0, separator) == "genre") { "GoodToon genre route is invalid" }
        return genre.key.substring(separator + 1)
    }

    private fun sameOrigin(candidate: URI, base: URI?): Boolean {
        if (candidate.rawAuthority == null && !candidate.isAbsolute) return true
        if (base == null || candidate.host == null) return false
        val scheme = candidate.scheme ?: base.scheme
        return scheme.equals(base.scheme, ignoreCase = true) &&
            candidate.host.equals(base.host, ignoreCase = true) &&
            candidate.port == base.port
    }

    private fun parameters(rawQuery: String?): Map<String, String>? {
        if (rawQuery == null) return emptyMap()
        val values = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { component ->
            if (component.isEmpty()) return@forEach
            val separator = component.indexOf('=')
            val key = if (separator < 0) component else component.substring(0, separator)
            val value = if (separator < 0) "" else component.substring(separator + 1)
            if (key.isEmpty() || values.put(key, value) != null) return null
        }
        return values
    }
}
