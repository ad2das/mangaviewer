package ml.melun.mangaview.source.goodtoon

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoodtoonOriginResolverTest {
    private val aliveBody =
        "<html><body><div class=\"card-grid\"><a class=\"card\" href=\"/manga/gt-1/\"></a></div></body></html>"
    private val challengeBody = "<html><body>Redirecting...</body></html>"

    private fun alive(request: SourceRequest, finalUrl: String = request.url): SourceResponse =
        htmlResponse(request.url, aliveBody, finalUrl = finalUrl)

    @Test
    fun `forward scan resolves the first numbering whose catalog is real`() = runTest {
        val transport = RecordingTransport { request ->
            when {
                request.url.contains("goodtoon001") || request.url.contains("goodtoon002") ->
                    throw IOException("host is down")
                request.url.contains("goodtoon003") ->
                    alive(request, finalUrl = "https://www.goodtoon004.com/ongoing/")
                request.url.contains("goodtoon004") -> alive(request)
                else -> throw IOException("host is down")
            }
        }
        val resolver = GoodtoonOriginResolver(transport, "test-agent", probeParallelism = 4)
        assertEquals("https://www.goodtoon004.com", resolver.resolve("https://www.goodtoon001.com"))
    }

    @Test
    fun `challenge hosts that serve two hundred without catalog markers are rejected`() = runTest {
        val transport = RecordingTransport { request -> htmlResponse(request.url, challengeBody) }
        val resolver = GoodtoonOriginResolver(transport, "test-agent")
        assertNull(resolver.resolve("https://www.goodtoon005.com"))
    }

    @Test
    fun `apex redirect discovers the current numbered host`() = runTest {
        val transport = RecordingTransport { request ->
            if (request.url.contains("goodtoon.top")) {
                alive(request, finalUrl = "https://www.goodtoon004.com/ongoing/")
            } else {
                throw IOException("host is down")
            }
        }
        val resolver = GoodtoonOriginResolver(transport, "test-agent", probeParallelism = 4)
        assertEquals("https://www.goodtoon004.com", resolver.resolve("https://goodtoon.top"))
    }

    @Test
    fun `javascript address hop is followed once`() = runTest {
        val hopBody = "<html><body><script>window.location = \"https://www.goodtoon009.com\";</script></body></html>"
        val transport = RecordingTransport { request ->
            when {
                request.url.contains("goodtoon004") -> htmlResponse(request.url, hopBody)
                request.url.contains("goodtoon009") -> alive(request)
                else -> throw IOException("host is down")
            }
        }
        val resolver = GoodtoonOriginResolver(transport, "test-agent", probeParallelism = 4)
        assertEquals("https://www.goodtoon009.com", resolver.resolve("https://www.goodtoon004.com"))
    }

    @Test
    fun `concurrent resolutions share a single probe flight`() = runTest {
        val transport = RecordingTransport { request ->
            delay(50)
            alive(request)
        }
        val resolver = GoodtoonOriginResolver(transport, "test-agent", probeParallelism = 1)
        val first = async { resolver.resolve("https://www.goodtoon004.com") }
        val second = async { resolver.resolve("https://www.goodtoon004.com") }
        assertEquals("https://www.goodtoon004.com", first.await())
        assertEquals("https://www.goodtoon004.com", second.await())
        assertEquals(1, transport.urls().count { it.contains("goodtoon004") })
    }

    @Test
    fun `a redirect outside the provider numbering is rejected`() = runTest {
        val transport = RecordingTransport { request ->
            alive(request, finalUrl = "https://example.com/ongoing/")
        }
        val resolver = GoodtoonOriginResolver(transport, "test-agent")
        assertNull(resolver.resolve("https://www.goodtoon004.com"))
    }
}
