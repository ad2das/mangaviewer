package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries

/** One selection owns its requests and cursor history, including empty intermediate pages. */
internal class GenreCatalogPager(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val publish: (LibraryContent) -> Unit,
) {
    private var version = 0L
    private var job: Job? = null
    private var fetch: (suspend (String?) -> SourcePage<SourceSeries>)? = null
    private var catalog = LibraryContent.Series(emptyList())
    private val consumed = mutableSetOf<String?>()

    fun reset() {
        version++
        job?.cancel()
        job = null
        fetch = null
        consumed.clear()
    }

    fun start(load: suspend (String?) -> SourcePage<SourceSeries>) {
        reset()
        fetch = load
        catalog = LibraryContent.Series(emptyList())
        publish(LibraryContent.Loading)
        request(null)
    }

    fun next() {
        if (catalog.loadingNext || fetch == null) return
        val cursor = catalog.nextCursor
        if (cursor != null || consumed.isEmpty()) request(cursor)
    }

    private fun request(cursor: String?) {
        val load = fetch ?: return
        val expected = version
        catalog = catalog.copy(loadingNext = true, nextFailure = null)
        if (consumed.isNotEmpty()) publish(catalog)
        job = scope.launch {
            try {
                var requested = cursor
                do {
                    val page = withContext(dispatcher) { load(requested) }
                    if (expected != version) return@launch
                    check(page.nextCursor == null || (page.nextCursor != requested && page.nextCursor !in consumed)) {
                        "제공처가 같은 목록 페이지를 반복했습니다. 다시 시도해 주세요."
                    }
                    consumed.add(requested)
                    catalog = catalog.copy(
                        items = (catalog.items + page.items).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                    )
                    requested = page.nextCursor
                    // A filtered empty page is not the end when the provider supplies a cursor.
                } while (page.items.isEmpty() && requested != null)
                catalog = catalog.copy(loadingNext = false)
                publish(catalog)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (expected == version) {
                    catalog = catalog.copy(loadingNext = false,
                        nextFailure = failure.message ?: "장르 작품을 불러오지 못했습니다")
                    publish(catalog)
                }
            }
        }
    }
}
