package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ml.melun.mangaview.source.SourceThrottledException
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
    private var resumePending = false

    fun reset() {
        version++
        job?.cancel()
        job = null
        fetch = null
        resumePending = false
        consumed.clear()
        results = LibraryContent.Series(emptyList())
    }

    fun start(load: suspend (String?) -> SourcePage<SourceSeries>) {
        reset()
        fetch = load
        results = LibraryContent.Series(emptyList())
        publish(LibraryContent.Loading)
        request(null)
    }

    fun next() {
        if (resumePending || results.loadingNext || fetch == null) return
        val cursor = results.nextCursor
        if (cursor != null || consumed.isEmpty()) request(cursor)
    }

    /** Yield network work to an opened series without losing a partially consumed search. */
    fun pause() {
        if (resumePending) return
        resumePending = job?.isActive == true
        if (!resumePending) return
        version++
        job?.cancel()
        job = null
        results = results.copy(loadingNext = false, nextFailure = null)
        if (consumed.isNotEmpty()) publish(results)
    }

    fun resume() {
        if (!resumePending) return
        resumePending = false
        next()
    }

    private fun request(cursor: String?) {
        val load = fetch ?: return
        val expected = version
        results = results.copy(loadingNext = true, nextFailure = null)
        if (consumed.isNotEmpty()) publish(results)
        job = scope.launch {
            try {
                loadWithCooldown(cursor, expected, load)
                if (expected != version) return@launch
                results = results.copy(loadingNext = false)
                publish(results)
            } catch (timeout: TimeoutCancellationException) {
                fail(expected, "응답이 늦어지고 있습니다. 연결을 확인하고 다시 시도해 주세요")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(expected, failureDisplayMessage(failure, failureMessage))
            }
        }
    }

    private suspend fun loadWithCooldown(
        cursor: String?,
        expected: Long,
        load: suspend (String?) -> SourcePage<SourceSeries>,
    ) {
        var requested = cursor
        for (attempt in 0..2) {
            try {
                withTimeout(SEARCH_REQUEST_TIMEOUT_MILLIS) { loadPages(requested, expected, load) }
                return
            } catch (limited: SourceThrottledException) {
                if (attempt == 2 || limited.retryAfterMillis !in 1L..120_000L) throw limited
                awaitRetry(limited.retryAfterMillis, expected, "사이트 요청 제한으로 대기 중입니다")
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (attempt == 2) throw timeout
                awaitRetry(1_500L, expected, "사이트 응답이 늦어지고 있습니다")
            }
            requested = if (consumed.isEmpty()) cursor else results.nextCursor
        }
    }

    private suspend fun awaitRetry(waitMillis: Long, expected: Long, reason: String) {
        var remaining = waitMillis
        while (remaining > 0 && expected == version) {
            val seconds = (remaining + 999L) / 1_000L
            results = results.copy(loadingNext = true,
                nextFailure = "$reason. ${seconds}초 후 자동으로 다시 시도합니다")
            publish(results)
            val interval = minOf(1_000L, remaining)
            delay(interval)
            remaining -= interval
        }
        if (expected == version) {
            results = results.copy(nextFailure = null)
            publish(results)
        }
    }

    private suspend fun loadPages(
        cursor: String?,
        expected: Long,
        load: suspend (String?) -> SourcePage<SourceSeries>,
    ) {
        var requested = cursor
        repeat(8) {
            val page = withContext(dispatcher) { load(requested) }
            if (expected != version) return
            check(page.nextCursor == null || (page.nextCursor != requested && page.nextCursor !in consumed)) {
                "제공처가 같은 검색 결과를 반복했습니다. 다시 시도해 주세요."
            }
            val previousSize = results.items.size
            consumed.add(requested)
            results = results.copy(
                items = (results.items + page.items).distinctBy { it.id },
                nextCursor = page.nextCursor,
                nextFailure = page.nextWarning,
            )
            requested = page.nextCursor
            if (requested == null || page.nextWarning != null || results.items.size > previousSize) return
        }
        error("새 결과를 찾는 데 시간이 걸립니다. 더 불러오려면 다시 시도해 주세요")
    }

    private fun fail(expected: Long, message: String) {
        if (expected != version) return
        results = results.copy(loadingNext = false, nextFailure = message)
        publish(if (consumed.isEmpty()) LibraryContent.Failure(message) else results)
    }
}

internal const val SEARCH_REQUEST_TIMEOUT_MILLIS = 30_000L
