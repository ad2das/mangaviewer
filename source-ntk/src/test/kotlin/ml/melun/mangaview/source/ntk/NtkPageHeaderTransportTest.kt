package ml.melun.mangaview.source.ntk

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NtkPageHeaderTransportTest {
    private val request = SourceRequest("https://cdn.test/original", totalTimeoutMillis = 120_000)

    @Test fun silentCandidateTimesOutAndCancelsItsRequest() = runTest {
        var cancelled = false
        val transport = NtkPageHeaderTransport(SourceTransport {
            try { awaitCancellation() } finally { cancelled = true }
        })
        val failure = runCatching { transport.execute(request) }.exceptionOrNull()
        assertTrue(failure is SocketTimeoutException)
        assertTrue(cancelled)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test fun originalBodyCanKeepReadingAfterHeaderDeadline() = runTest {
        val body = Body(readDelay = 9_000)
        val response = response(body)
        val transport = NtkPageHeaderTransport(SourceTransport { received ->
            assertSame(request, received)
            delay(1_000)
            response
        })
        assertSame(response, transport.execute(request))
        val pixels = ByteArray(1)
        assertEquals(1, response.body.readAtMost(pixels, 0, 1))
        assertEquals(73, pixels[0].toInt())
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(0, body.closes)
        response.close()
        assertEquals(1, body.closes)
    }

    @Test fun parentCancellationIsNotConvertedIntoCandidateFailure() = runTest {
        var failure: Throwable? = null
        val transport = NtkPageHeaderTransport(SourceTransport { awaitCancellation() })
        val job = launch { failure = runCatching { transport.execute(request) }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(100)
        job.cancelAndJoin()
        assertTrue(failure is CancellationException)
        assertFalse(failure is SocketTimeoutException)
    }

    @Test fun responseDeliveredAfterCancellationIsClosedExactlyOnce() = runTest {
        val body = Body()
        val transport = NtkPageHeaderTransport(SourceTransport {
            withContext(NonCancellable) { delay(9_000) }
            response(body)
        })
        assertTrue(runCatching { transport.execute(request) }.exceptionOrNull() is SocketTimeoutException)
        assertEquals(1, body.closes)
    }

    @Test fun authorizationFailureRemainsAnHttpResponse() = runTest {
        val body = Body()
        val denied = response(body, 403)
        assertSame(denied, NtkPageHeaderTransport(SourceTransport { denied }).execute(request))
        assertEquals(0, body.closes)
        denied.close()
    }

    private fun response(body: Body, status: Int = 200) =
        SourceResponse(status, request.url, emptyMap(), body, 1L, "image/jpeg")

    private class Body(private val readDelay: Long = 0) : PageByteStream {
        var closes = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            delay(readDelay)
            destination[offset] = 73
            return 1
        }
        override fun close() { closes++ }
    }
}
