package ml.melun.mangaview.app

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class NewxtoonImageTransportTest {
    @Test fun artworkHostsTakeTheImageRoute() = runTest {
        val seen = mutableListOf<String>()
        val transport = NewxtoonImageTransport(pageRoute(seen, "page"), imageRoute(seen, "image"))
        val response = transport.execute(request("https://user281.quicksharefiles.top/x/cover.jpg"))
        assertEquals(listOf("image:https://user281.quicksharefiles.top/x/cover.jpg"), seen)
        assertEquals("image", response.contentType)
        response.close()
    }

    @Test fun originHostsTakeTheDocumentRoute() = runTest {
        val seen = mutableListOf<String>()
        val transport = NewxtoonImageTransport(pageRoute(seen, "page"), imageRoute(seen, "image"))
        val response = transport.execute(request("https://newxtoon1.com/comics?page=1&sort=latest"))
        assertEquals(listOf("page:https://newxtoon1.com/comics?page=1&sort=latest"), seen)
        assertEquals("page", response.contentType)
        response.close()
    }

    @Test fun freshAndAlternateRoutesFollowTheSameSplit() = runTest {
        val seen = mutableListOf<String>()
        val transport = NewxtoonImageTransport(pageRoute(seen, "page"), imageRoute(seen, "image"))
        transport.executeOnFreshRoute(request("https://user281.quicksharefiles.top/x/cover.jpg")).close()
        transport.executeOnAlternateRoute(request("https://newxtoon1.com/comics")).close()
        assertEquals(
            listOf(
                "image:https://user281.quicksharefiles.top/x/cover.jpg",
                "page:https://newxtoon1.com/comics",
            ),
            seen,
        )
    }

    @Test fun hostLookalikesStayOnTheDocumentRoute() = runTest {
        val seen = mutableListOf<String>()
        val transport = NewxtoonImageTransport(pageRoute(seen, "page"), imageRoute(seen, "image"))
        transport.execute(request("https://quicksharefiles.top.evil.test/x/cover.jpg")).close()
        transport.execute(request("https://notquicksharefiles.top/x/cover.jpg")).close()
        assertEquals(
            listOf(
                "page:https://quicksharefiles.top.evil.test/x/cover.jpg",
                "page:https://notquicksharefiles.top/x/cover.jpg",
            ),
            seen,
        )
    }

    private fun request(url: String) = SourceRequest(url, totalTimeoutMillis = 5_000)

    private fun pageRoute(seen: MutableList<String>, type: String): SourceTransport =
        SourceTransport { request ->
            seen += "page:${request.url}"
            response(type, request.url)
        }

    private fun imageRoute(seen: MutableList<String>, type: String): SourceTransport =
        SourceTransport { request ->
            seen += "image:${request.url}"
            response(type, request.url)
        }

    private fun response(type: String, url: String) =
        SourceResponse(200, url, emptyMap(), object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
            override fun close() = Unit
        }, null, type)
}
