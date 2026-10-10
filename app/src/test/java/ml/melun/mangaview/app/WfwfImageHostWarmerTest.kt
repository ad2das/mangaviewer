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
class WfwfImageHostWarmerTest {
    @Test fun emptyWarmIssuesNoRequest() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val warmer = WfwfImageHostWarmer(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests), 4_000L)
        warmer.warm(emptyList())
        warmer.warm(listOf("", " ", "signed.example/path?signature=secret"))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun firstWarmOpensAHeadLegPerNewHost() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val warmer = WfwfImageHostWarmer(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests), 4_000L)
        warmer.warm(listOf("i1.imgcloud18.com", "img8cloud.net"))
        advanceUntilIdle()
        assertEquals(
            listOf("https://i1.imgcloud18.com/", "https://img8cloud.net/"),
            requests.map { it.url },
        )
        assertTrue(requests.all { it.method == SourceHttpMethod.HEAD })
        assertTrue(requests.all { it.priority == PageFetchPriority.BACKGROUND })
        assertTrue(requests.all { it.totalTimeoutMillis == 4_000L })
    }

    @Test fun repeatedWarmOfTheSameHostSendsNothingMore() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val warmer = WfwfImageHostWarmer(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests), 4_000L)
        warmer.warm(listOf("i1.imgcloud18.com"))
        advanceUntilIdle()
        assertEquals(1, requests.size)
        warmer.warm(listOf(" I1.ImgCloud18.com "))
        advanceUntilIdle()
        assertEquals(1, requests.size)
    }

    @Test fun mixedCallWarmsOnlyNewHosts() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val warmer = WfwfImageHostWarmer(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests), 4_000L)
        warmer.warm(listOf("i1.imgcloud18.com"))
        advanceUntilIdle()
        warmer.warm(listOf("i1.imgcloud18.com", "img8cloud.net"))
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals("https://img8cloud.net/", requests.last().url)
    }

    @Test fun eachCallWarmsAtMostTwoNewestHosts() = runTest {
        val requests = mutableListOf<SourceRequest>()
        val warmer = WfwfImageHostWarmer(this, StandardTestDispatcher(testScheduler), RecordingTransport(requests), 4_000L)
        warmer.warm(listOf("a.example", "b.example", "c.example"))
        advanceUntilIdle()
        assertEquals(listOf("https://a.example/", "https://b.example/"), requests.map { it.url })
        warmer.warm(listOf("c.example"))
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
