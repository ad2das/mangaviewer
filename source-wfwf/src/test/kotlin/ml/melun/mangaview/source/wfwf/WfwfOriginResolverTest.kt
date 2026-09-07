package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfOriginResolverTest {
    @Test
    fun findsANearbyLiveOriginWithoutDependingOnTheContentKind() = runTest {
        val transport = ProbeTransport(liveOrigin = "https://wfwf457.com")

        val resolved = WfwfOriginResolver(transport, "agent").resolve("https://wfwf455.com")

        assertEquals("https://wfwf457.com", resolved)
    }

    @Test
    fun bootstrapUsesTheSameProviderSeedAsTheApp() = runTest {
        val transport = ProbeTransport(liveOrigin = DEFAULT_WFWF_ORIGIN)
        assertEquals(DEFAULT_WFWF_ORIGIN, WfwfOriginResolver(transport, "agent").resolve(DEFAULT_WFWF_ORIGIN))
    }

    @Test
    fun anAddressGuideCannotPublishAnUnreachableDestination() = runTest {
        val transport = OriginDocuments(mapOf(
            "https://wfwf490.com" to "<a class='main-btn' href='https://wfwf499.com'>updated address</a>",
            "https://wfwf492.com" to "<a href='/cl?toon=10007'>Provider catalog</a>",
        ))
        assertEquals("https://wfwf492.com", WfwfOriginResolver(transport, "agent").resolve("https://wfwf490.com"))
        assertTrue(transport.requested.contains("https://wfwf499.com/ing"))
    }

    @Test
    fun parkingPagesAndCyclicAddressGuidesAreNotLiveOrigins() = runTest {
        val transport = OriginDocuments(mapOf(
            "https://wfwf490.com" to "<a class='main-btn' href='https://wfwf491.com'>updated address</a>",
            "https://wfwf491.com" to "<a class='main-btn' href='https://wfwf490.com'>updated address</a>",
            "https://wfwf492.com" to "<script>window.location.href='/lander'</script>",
        ))
        assertNull(WfwfOriginResolver(transport, "agent").resolve("https://wfwf490.com"))
    }
}

private class OriginDocuments(private val documents: Map<String, String>) : SourceTransport {
    val requested = mutableListOf<String>()
    override suspend fun execute(request: SourceRequest): SourceResponse {
        requested += request.url
        val html = documents[request.url.removeSuffix("/ing")]
        val bytes = html.orEmpty().toByteArray()
        return SourceResponse(if (html == null) 404 else 200, request.url, emptyMap(),
            ProbeBytes(bytes), contentLength = bytes.size.toLong(), contentType = "text/html")
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
