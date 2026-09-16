package ml.melun.mangaview.source.goodtoon

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries
import org.jsoup.nodes.Document

/** The provider's tied sort order moves titles between pages. Reconcile duplicate slots. */
internal class GoodtoonSearchService(
    private val fetch: suspend (String) -> Document,
    private val parse: (Document) -> List<SourceSeries>,
) {
    private val lock = Mutex()
    private val searches = linkedMapOf<String, SearchSnapshot>()

    suspend fun search(query: SourceSearchQuery): SourcePage<SourceSeries> = lock.withLock {
        val key = query.text.trim()
        val position = SearchPosition.decode(query.cursor)
        val previous = searches[key]
        val snapshot = if (query.cursor == null) SearchSnapshot() else previous?.copyForRequest()
            ?: throw IOException("검색 정보가 만료되었습니다. 검색을 다시 실행해 주세요")
        val first = "/?q=${urlEncode(key)}"
        val returned = linkedMapOf<SeriesId, SourceSeries>()
        var current = position
        var next: SearchPosition? = current
        var warning: String? = null
        // Repair requests collect a few upstream pages so repeated boundary cards don't stall the UI.
        repeat(if (position.round == 1) 1 else 3) {
            if (next == null || warning != null) return@repeat
            current = next!!
            val document = fetch(first + if (current.page > 1) "&pg=${current.page}" else "")
            validatePage(document, current.page)
            val items = parse(document)
            items.forEach { returned[it.id] = it; snapshot.seen.add(it.id) }
            if (snapshot.round != current.round) {
                snapshot.round = current.round
                snapshot.slots.clear()
            }
            snapshot.slots[current.page] = items.size
            val more = GoodtoonCatalogPagination.higherPages(document, first, current.page).isNotEmpty()
            next = if (more) current.copy(page = current.page + 1) else finishRound(snapshot, current)
            if (!more && next != null && current.round % 4 == 0) {
                warning = "사이트의 검색 순서가 바뀌어 일부 결과를 확인 중입니다. 다시 시도를 눌러 추가 확인할 수 있습니다"
            }
        }
        searches.remove(key)
        searches[key] = snapshot
        while (searches.size > 4) searches.remove(searches.keys.first())
        SourcePage(returned.values.toList(), next?.encode(), warning)
    }

    private fun finishRound(snapshot: SearchSnapshot, position: SearchPosition): SearchPosition? {
        snapshot.expected = maxOf(snapshot.expected, snapshot.slots.values.sum())
        return if (snapshot.seen.size >= snapshot.expected) null
            else SearchPosition(position.round + 1, 1)
    }

    private fun validatePage(document: Document, requested: Int) {
        val actual = document.selectFirst(".pagination .current, .pagination .active")?.text()?.toIntOrNull()
        if (actual != null && actual != requested) throw IOException("GoodToon returned a different search page")
    }
}

private data class SearchSnapshot(
    var round: Int = 1,
    var expected: Int = 0,
    val seen: MutableSet<SeriesId> = linkedSetOf(),
    val slots: MutableMap<Int, Int> = linkedMapOf(),
) {
    fun copyForRequest() = copy(seen = seen.toMutableSet(), slots = slots.toMutableMap())
}

private data class SearchPosition(val round: Int, val page: Int) {
    fun encode(): String = if (round == 1) page.toString() else "r$round:$page"

    companion object {
        fun decode(cursor: String?): SearchPosition {
            if (cursor == null || !cursor.startsWith('r')) {
                return SearchPosition(1, GoodtoonCatalogPagination.page(cursor))
            }
            val values = cursor.drop(1).split(':')
            require(values.size == 2) { "Invalid GoodToon search cursor" }
            val round = GoodtoonCatalogPagination.page(values[0])
            require(round > 1) { "Invalid GoodToon search round" }
            return SearchPosition(round, GoodtoonCatalogPagination.page(values[1]))
        }
    }
}
