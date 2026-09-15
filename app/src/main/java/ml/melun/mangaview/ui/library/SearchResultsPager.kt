package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries

/** One query owns its requests and cursor history; a failed first page surfaces as search failure. */
internal class SearchResultsPager(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val failureMessage: String,
    private val publish: (LibraryContent) -> Unit,
) {
    private var version = 0L
    private var job: Job? = null
    private var fetch: (suspend (String?) -> SourcePage<SourceSeries>)? = null
    private var results = LibraryContent.Series(emptyList())
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
        results = LibraryContent.Series(emptyList())
        publish(LibraryContent.Loading)
        request(null)
    }

    fun next() {
        if (results.loadingNext || fetch == null) return
        val cursor = results.nextCursor
        if (cursor != null || consumed.isEmpty()) request(cursor)
    }

    private fun request(cursor: String?) {
        val load = fetch ?: return
        val expected = version
        results = results.copy(loadingNext = true, nextFailure = null)
        if (consumed.isNotEmpty()) publish(results)
        job = scope.launch {
            try {
                var requested = cursor
                do {
                    val page = withContext(dispatcher) { load(requested) }
                    if (expected != version) return@launch
                    check(page.nextCursor == null || (page.nextCursor != requested && page.nextCursor !in consumed)) {
                        "제공처가 같은 검색 결과를 반복했습니다. 다시 시도해 주세요."
                    }
                    consumed.add(requested)
                    results = results.copy(
                        items = (results.items + page.items).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                    )
                    requested = page.nextCursor
                    // A page can legitimately be empty mid-list when the provider filters it.
                } while (page.items.isEmpty() && requested != null)
                results = results.copy(loadingNext = false)
                publish(results)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (expected != version) return@launch
                results = results.copy(loadingNext = false)
                val message = failureDisplayMessage(failure, failureMessage)
                if (consumed.isEmpty()) {
                    publish(LibraryContent.Failure(message))
                } else {
                    results = results.copy(nextFailure = message)
                    publish(results)
                }
            }
        }
    }
}
