package ml.melun.mangaview.data.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.SourceRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpSourceTransportTest {
    @Test
    fun streamsBodyAndPreservesFinalResponseMetadata() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody("payload"),
            )
            val transport = OkHttpSourceTransport(OkHttpClient(), Dispatchers.IO)

            val response = transport.execute(SourceRequest(server.url("/page").toString()))
            val bytes = ByteArray(16)
            val count = response.body.readAtMost(bytes, 0, bytes.size)
            response.close()

            assertEquals(200, response.statusCode)
            assertEquals("image/png", response.contentType)
            assertEquals("payload", bytes.decodeToString(0, count))
        }
    }

    @Test
    fun coroutineCancellationCancelsTheUnderlyingCall() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(30L, TimeUnit.SECONDS)
                    .setBody("late"),
            )
            val client = OkHttpClient.Builder().build()
            val transport = OkHttpSourceTransport(client, Dispatchers.IO)
            val request = async { transport.execute(SourceRequest(server.url("/slow").toString())) }

            request.cancel()
            request.join()

            assertTrue(request.isCancelled)
        }
    }
}
