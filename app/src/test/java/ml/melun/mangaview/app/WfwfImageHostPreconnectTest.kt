package ml.melun.mangaview.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WfwfImageHostPreconnectTest {
    @Test fun sessionStartOpensAHeadLegForRememberedImageHosts() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val transport = RecordingTransport(requests)
        preconnectImageHosts(this, StandardTestDispatcher(testScheduler), transport,
            { listOf("i1.imgcloud18.com", "img8cloud.net") }, 4_000L)
        advanceUntilIdle()
        assertEquals(
            listOf("https://i1.imgcloud18.com/", "https://img8cloud.net/"),
            requests.map { it.url },
        )
        assertTrue(requests.all { it.method == SourceHttpMethod.HEAD })
        assertTrue(requests.all { it.priority == PageFetchPriority.BACKGROUND })
        assertTrue(requests.all { it.totalTimeoutMillis == 4_000L })
    }

    @Test fun emptyMemoryIssuesNoRequest() = runTest {
        val requests = mutableListOf<SourceRequest>()
        preconnectImageHosts(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests),
            { emptyList() }, 4_000L)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun atMostTwoHeadsEvenWhenMemoryReturnedMore() = runTest {
        val requests = mutableListOf<SourceRequest>()
        preconnectImageHosts(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests),
            { listOf("a.example", "b.example", "c.example") }, 4_000L)
        advanceUntilIdle()
        assertEquals(2, requests.size)
    }

    private class RecordingTransport(
        private val requests: MutableList<SourceRequest>,
    ) : SourceTransport {
        override suspend fun execute(request: SourceRequest): SourceResponse {
            requests += request
            return SourceResponse(200, request.url, emptyMap(), EmptyPageBytes, 0L, null)
        }
    }

    private object EmptyPageBytes : PageByteStream {
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
        override fun close() = Unit
    }
}
