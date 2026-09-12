package ml.melun.mangaview.source.newxtoon

import java.io.IOException
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonThrottleRetryTest {
    @Test fun retriesThrottledDocumentRequestsUntilTheySucceed() = runTest {
        val transport = StatusTransport(
            429 to "",
            429 to "",
            200 to "<html><body>ok</body></html>",
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
            200 to "<html><body>ok</body></html>",
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

    override suspend fun execute(request: SourceRequest): SourceResponse {
        calls += 1
        val (status, body) = if (queue.size > 1) queue.removeFirst() else queue.first()
        val bytes = body.toByteArray()
        return SourceResponse(
            statusCode = status,
            finalUrl = request.url,
            headers = mapOf("Retry-After" to listOf("0")),
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
