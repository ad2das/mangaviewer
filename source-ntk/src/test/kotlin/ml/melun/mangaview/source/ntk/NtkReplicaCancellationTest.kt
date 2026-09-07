package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NtkReplicaCancellationTest {
    @Test
    fun cancellationWhileJoiningTheLoserClosesTheSelectedBodyExactlyOnce() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var closes = 0
        val body = object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
            override fun close() { closes++ }
        }
        val transport = SourceTransport { request ->
            if (request.url.contains("slow.test")) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            }
            delay(1L)
            SourceResponse(200, request.url, emptyMap(), body, 16L, "image/jpeg")
        }
        val racer = NtkReplicaRacer(transport, NtkReplicaSelector(), hedgeDelayMillis = 0L)
        val opened = async {
            racer.open(listOf("https://fast.test/page", "https://slow.test/page"),
                emptyMap(), "p0", PageFetchPriority.FOCUS, validate = {
                OpenedPage(it.body, it.contentLength, it.contentType, null, null)
            })
        }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        opened.cancel()
        cleanup.complete(Unit)
        opened.join()
        assertEquals(1, closes)
    }
}
