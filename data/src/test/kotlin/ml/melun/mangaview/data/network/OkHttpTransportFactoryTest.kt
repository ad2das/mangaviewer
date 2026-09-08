package ml.melun.mangaview.data.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.PageFetchPriority
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpTransportFactoryTest {
    @Test fun admittedSameHostRequestsCanStartBeforeEarlierHeadersReturn() = runTest {
        val begun = CountDownLatch(8)
        val releaseHeaders = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    begun.countDown()
                    check(releaseHeaders.await(10, TimeUnit.SECONDS))
                    return MockResponse().setBody("original")
                }
            }
            val transport = OkHttpTransportFactory(Dispatchers.IO, parallelism = 16).create()
            val requests = (0 until 8).map { index -> async(Dispatchers.IO) {
                val response = transport.execute(SourceRequest(server.url("/page-$index").toString(),
                    priority = PageFetchPriority.VISIBLE))
                try { check(response.statusCode == 200) } finally { response.close() }
            } }
            try {
                assertTrue("Admitted requests were queued behind a smaller HTTP host limit",
                    withContext(Dispatchers.IO) { begun.await(5, TimeUnit.SECONDS) })
            } finally {
                releaseHeaders.countDown()
                try { requests.awaitAll() } finally { transport.close() }
            }
        }
    }
}
