package ml.melun.mangaview.app

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.content.PageHttpException
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderOriginTransportTest {
    @Test fun recoverableOriginStatusesSurfaceAsTypedPageFailures() = runTest {
        listOf(403, 404, 410, 421, 451, 502, 503).forEach { status ->
            val body = FakeBody()
            val transport = ProviderOriginTransport(
                SourceTransport { request -> response(status, request.url, body) }, StaticDirectory())
            val failure = try { transport.execute(request()); null } catch (caught: PageHttpException) { caught }
            assertNotNull("status $status did not throw", failure)
            assertEquals(status, failure!!.statusCode)
            assertEquals("body was not closed", 1, body.closes)
        }
    }

    @Test fun statusesOutsideTheRecoverySetPassThrough() = runTest {
        val transport = ProviderOriginTransport(
            SourceTransport { request -> response(500, request.url, FakeBody()) }, StaticDirectory())
        val result = transport.execute(request())
        assertEquals(500, result.statusCode)
        result.close()
    }

    @Test fun sameOriginRecoveryKeepsTheTypedFailure() = runTest {
        val directory = StaticDirectory(recoverOrigin = { _, failed, _ -> failed })
        val transport = ProviderOriginTransport(
            SourceTransport { request -> response(404, request.url, FakeBody()) }, directory)
        val failure = try { transport.execute(request()); null } catch (caught: PageHttpException) { caught }
        assertEquals(404, failure!!.statusCode)
    }

    @Test fun replacementOriginRetriesTheRewrittenPath() = runTest {
        val seen = mutableListOf<String>()
        val directory = StaticDirectory(current = "https://old.test", recoverOrigin = { _, _, _ -> "https://new.test" })
        val transport = ProviderOriginTransport(SourceTransport { request ->
            seen += request.url
            response(if (request.url.startsWith("https://old.test")) 404 else 200, request.url, FakeBody())
        }, directory)
        val result = transport.execute(request())
        assertEquals(200, result.statusCode)
        assertEquals(listOf("https://old.test/manhwa/20182", "https://new.test/manhwa/20182"), seen)
        result.close()
    }

    private fun request() = SourceRequest("https://newtoki1.org/manhwa/20182", totalTimeoutMillis = 5_000)

    private fun response(status: Int, url: String, body: FakeBody) =
        SourceResponse(status, url, emptyMap(), body, null, "text/html")

    private class FakeBody : PageByteStream {
        var closes = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
        override fun close() { closes += 1 }
    }

    private class StaticDirectory(
        private val current: String? = null,
        private val recoverOrigin: suspend (String, String, SourceTransport) -> String? = { _, _, _ -> null },
    ) : ProviderOrigins {
        override fun provider(url: String): String? = "ntk"
        override suspend fun current(provider: String, fallback: String): String = current ?: fallback
        override suspend fun recover(provider: String, failed: String, transport: SourceTransport): String? =
            recoverOrigin(provider, failed, transport)
        override suspend fun observeRedirect(provider: String, finalOrigin: String, transport: SourceTransport) = Unit
    }
}
