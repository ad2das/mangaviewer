package ml.melun.mangaview.source.newxtoon

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import ml.melun.mangaview.source.SourceThrottledException
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewxtoonThrottleRetryTest {
    @Test fun maintenanceResponseIsNotCachedAsASuccessfulSearchDocument() = runTest {
        val transport = StatusTransport(200 to "<html>maintenance</html>", 200 to "<input id='page-search' name='q'>")
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"), transport) { testScheduler.currentTime }
        assertTrue(runCatching { source.search("생존") }.isFailure)
        assertTrue(source.search("생존").items.isEmpty())
        assertEquals(2, transport.calls)
    }

    @Test fun temporaryNetworkFailureRetriesWithoutChangingTheRequestedPage() = runTest {
        val success = StatusTransport(200 to "<input id='page-search' name='q'>")
        val urls = mutableListOf<String>()
        val transport = object : SourceTransport {
            override suspend fun execute(request: SourceRequest): SourceResponse {
                urls += request.url
                if (urls.size == 1) throw java.net.SocketTimeoutException("temporary failure")
                return success.execute(request)
            }
        }
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"), transport) { testScheduler.currentTime }
        assertTrue(source.search("생존", "2").items.isEmpty())
        assertEquals(2, urls.size)
        assertEquals(urls.first(), urls.last())
    }

    @Test fun longRetryAfterIsNotClampedAndBlocksOtherDocumentsUntilItExpires() = runTest {
        val transport = StatusTransport(429 to "", 200 to "<input id='page-search' name='q'>")
        transport.retryAfter = "60"
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"), transport) { testScheduler.currentTime }
        val limited = runCatching { source.search("생존") }.exceptionOrNull() as SourceThrottledException
        assertEquals(60_000L, limited.retryAfterMillis)
        assertEquals(1, transport.calls)
        advanceTimeBy(59_000)
        val other = runCatching { source.search("사랑") }.exceptionOrNull() as SourceThrottledException
        assertEquals(1_000L, other.retryAfterMillis)
        assertEquals(1, transport.calls)
        advanceTimeBy(1_000)
        assertTrue(source.search("생존").items.isEmpty())
        assertEquals(2, transport.calls)
    }

    @Test fun parsesBothRetryAfterFormatsWithoutShorteningTheServersWindow() {
        assertEquals(60_000L, retryAfterMillis("60", 0))
        assertEquals(60_000L, retryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, retryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", 120_000))
        assertEquals(null, retryAfterMillis("invalid", 0))
    }

    @Test fun duplicateSearchRequestsReuseARecentDocumentButDifferentPagesWaitTheirTurn() = runTest {
        val transport = StatusTransport(200 to "<input id='page-search' name='q'>")
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"), transport) { testScheduler.currentTime }
        source.search("생존"); source.search(" 생존 ")
        assertEquals(1, transport.calls)
        source.search("생존", "2")
        assertEquals(2_500L, testScheduler.currentTime)
        assertEquals(2, transport.calls)
    }

    @Test fun retriesThrottledDocumentRequestsUntilTheySucceed() = runTest {
        val transport = StatusTransport(
            429 to "",
            429 to "",
            200 to "<input id='page-search' name='q' value='화산귀환'>",
        )
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

        val page = source.search("화산귀환", cursor = null)

        assertEquals(3, transport.calls)
        assertTrue(page.items.isEmpty())
    }

    @Test fun honoursRetryAfterAndGivesUpAfterMaxAttempts() = runTest {
        val transport = StatusTransport(
            429 to "",
            429 to "",
            429 to "",
        )
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

        val failure = runCatching { source.search("화산귀환", cursor = null) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(failure?.message?.contains("429") == true)
        assertEquals(3, transport.calls)
    }

    @Test fun retriesServerErrorsToo() = runTest {
        val transport = StatusTransport(
            503 to "",
            200 to "<input id='page-search' name='q' value='로맨스'>",
        )
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

        source.search("로맨스", cursor = null)

        assertEquals(2, transport.calls)
    }
}

private class StatusTransport(vararg responses: Pair<Int, String>) : SourceTransport {
    private val queue = ArrayDeque(responses.toList())
    var calls = 0
        private set
    var retryAfter = "0"

    override suspend fun execute(request: SourceRequest): SourceResponse {
        calls += 1
        val (status, body) = if (queue.size > 1) queue.removeFirst() else queue.first()
        val bytes = body.toByteArray()
        return SourceResponse(
            statusCode = status,
            finalUrl = request.url,
            headers = mapOf("Retry-After" to listOf(retryAfter)),
            body = RetryBytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class RetryBytesStream(private val bytes: ByteArray) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position == bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
