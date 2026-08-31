package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfOriginResolverTest {
    @Test
    fun findsANearbyLiveOriginWithoutDependingOnTheContentKind() = runTest {
        val transport = ProbeTransport(liveOrigin = "https://wfwf457.com")

        val resolved = WfwfOriginResolver(transport, "agent").resolve("https://wfwf455.com")

        assertEquals("https://wfwf457.com", resolved)
    }
}

private class ProbeTransport(
    private val liveOrigin: String,
) : SourceTransport {
    override suspend fun execute(request: SourceRequest): SourceResponse {
        val alive = request.url.startsWith(liveOrigin)
        val bytes = if (alive) "<a href='/cl?toon=1'>WFWF</a>".toByteArray() else ByteArray(0)
        return SourceResponse(
            statusCode = if (alive) 200 else 404,
            finalUrl = request.url,
            headers = emptyMap(),
            body = ProbeBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html",
        )
    }
}

private class ProbeBytes(private val bytes: ByteArray) : PageByteStream {
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
