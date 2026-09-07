package ml.melun.mangaview.source.wfwf

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.SourcePageUnavailableException
import ml.melun.mangaview.source.SourceResponse

internal suspend fun executeWfwfHedged(
    timeoutMillis: Long,
    hedgeDelayMillis: Long,
    alternateDelayMillis: Long,
    primaryRequest: suspend () -> SourceResponse,
    recoveryRequest: suspend () -> SourceResponse,
    alternateRequest: suspend () -> SourceResponse,
): SourceResponse = coroutineScope {
    val result = CompletableDeferred<Result<SourceResponse>>()
    val decided = AtomicBoolean(false)
    val failures = AtomicInteger(0)

    fun contender(delayMillis: Long = 0L, request: suspend () -> SourceResponse) = launch {
        var response: SourceResponse? = null
        try {
            if (delayMillis > 0L) delay(delayMillis)
            val completed = request()
            response = completed
            if (decided.compareAndSet(false, true)) {
                result.complete(Result.success(completed))
                response = null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failures.incrementAndGet() == WFWF_HEDGED_REQUEST_COUNT &&
                decided.compareAndSet(false, true)
            ) {
                result.complete(Result.failure(failure))
            }
        } finally {
            response?.close()
        }
    }

    val primary = contender(request = primaryRequest)
    val hedge = contender(hedgeDelayMillis, recoveryRequest)
    val alternate = contender(alternateDelayMillis, alternateRequest)
    try {
        val outcome = withTimeoutOrNull(timeoutMillis) { result.await() }
            ?: throw IOException("WFWF routes timed out after ${timeoutMillis}ms")
        outcome.getOrThrow()
    } finally {
        primary.cancel()
        hedge.cancel()
        alternate.cancel()
    }
}

internal fun SourceResponse.openedWfwfPage(): OpenedPage = OpenedPage(
    stream = body,
    contentLength = contentLength,
    contentType = contentType,
    entityTag = header("ETag"),
    lastModified = header("Last-Modified"),
)

internal fun wfwfPageFailure(statusCode: Int): IOException =
    IOException("WFWF page request failed with $statusCode")

internal fun refreshedWfwfPageFailure(statusCode: Int): IOException =
    if (statusCode == 404 || statusCode == 410) {
        SourcePageUnavailableException(
            "WFWF provider still declares a page missing with HTTP $statusCode",
        )
    } else {
        wfwfPageFailure(statusCode)
    }

internal const val WFWF_HEDGED_REQUEST_COUNT = 3
