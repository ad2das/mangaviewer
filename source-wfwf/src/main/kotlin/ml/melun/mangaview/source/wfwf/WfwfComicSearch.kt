package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries

internal data class WfwfComicCatalogPage(
    val page: Int,
    val items: List<SourceSeries>,
    val nextCursor: String?,
    val linkedPages: List<Int> = emptyList(),
)

/** Metadata-only comic catalog cache and title search. It never opens artwork or page images. */
internal class WfwfComicSearch(
    private val fetchCatalogPage: suspend (Int) -> WfwfComicCatalogPage,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val cache = object : LinkedHashMap<Int, CachedPage>(CACHE_CAPACITY, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedPage>?): Boolean =
            size > CACHE_CAPACITY
    }

    suspend fun page(page: Int): WfwfComicCatalogPage {
        require(page > 0) { "WFWF comic catalog page must be positive" }
        val now = nowNanos()
        synchronized(cache) {
            cache[page]?.takeIf { now - it.cachedAtNanos in 0 until CACHE_TTL_NANOS }?.let {
                return it.value
            }
            cache.remove(page)
        }
        val fetched = fetchCatalogPage(page)
        check(fetched.page == page) { "WFWF comic catalog returned page ${fetched.page} for $page" }
        synchronized(cache) { cache[page] = CachedPage(fetched, nowNanos()) }
        return fetched
    }

    fun record(page: WfwfComicCatalogPage) {
        require(page.page > 0) { "WFWF comic catalog page must be positive" }
        synchronized(cache) { cache[page.page] = CachedPage(page, nowNanos()) }
    }

    suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> = supervisorScope {
        val startPage = searchPage(query.cursor)
        val visited = mutableSetOf<Int>()
        val fetching = linkedMapOf<Int, Deferred<WfwfComicCatalogPage>>()
        var pageNumber = startPage
        try {
            while (true) {
                check(visited.add(pageNumber)) { "WFWF comic search cursor cycle at page $pageNumber" }
                val catalogPage = fetching.remove(pageNumber)?.await() ?: page(pageNumber)
                val matches = catalogPage.items.filter { series -> matches(series, query) }
                val nextPage = nextPage(pageNumber, catalogPage.nextCursor)
                if (matches.isNotEmpty()) {
                    return@supervisorScope SourcePage(matches, nextPage?.let { "comic:$it" })
                }
                if (nextPage == null) return@supervisorScope SourcePage(emptyList())
                // Only follow links advertised by the actual catalog, and preserve result order.
                // This starts after an explicit search; no image or episode data is warmed.
                for (linked in catalogPage.linkedPages.sorted()) {
                    if (fetching.size >= 4) break
                    if (linked >= nextPage && linked !in fetching) {
                        fetching[linked] = async { page(linked) }
                    }
                }
                pageNumber = nextPage
            }
            @Suppress("UNREACHABLE_CODE")
            error("Search did not terminate")
        } finally { fetching.values.forEach { it.cancel() } }
    }

    private fun searchPage(cursor: String?): Int {
        if (cursor == null) return 1
        require(cursor.startsWith("comic:")) { "WFWF comic search cursor is malformed" }
        return WfwfCatalogPagination.page(cursor.removePrefix("comic:"))
    }

    private fun nextPage(currentPage: Int, cursor: String?): Int? {
        if (cursor == null) return null
        val next = WfwfCatalogPagination.page(cursor)
        require(next > currentPage) { "WFWF comic catalog cursor did not advance" }
        return next
    }

    private fun matches(series: SourceSeries, query: SourceSearchQuery): Boolean = when (query.field) {
        SearchField.TITLE -> series.title.contains(query.text.trim(), ignoreCase = true)
        SearchField.AUTHOR -> series.subtitle?.contains(query.text, ignoreCase = true) == true
    }

    private data class CachedPage(
        val value: WfwfComicCatalogPage,
        val cachedAtNanos: Long,
    )

    private companion object {
        const val CACHE_CAPACITY = 128
        const val CACHE_TTL_NANOS = 5L * 60L * 1_000_000_000L
    }
}
