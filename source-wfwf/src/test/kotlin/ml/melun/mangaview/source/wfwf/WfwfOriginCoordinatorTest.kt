package ml.melun.mangaview.source.wfwf

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfOriginCoordinatorTest {
    @Test
    fun olderDocumentCannotPublishAfterANewerDocument() = runTest {
        val coordinator = WfwfOriginCoordinator("https://wfwf489.com",
            WfwfOriginResolver(CoordinatorProbeTransport("https://wfwf490.com"), "agent"), null)
        val older = coordinator.beginDocument()
        val newer = coordinator.beginDocument()
        coordinator.observe("https://wfwf491.com/view?toon=1", newer)
        coordinator.observe("https://wfwf490.com/view?toon=1", older)
        assertEquals("https://wfwf491.com", coordinator.current())
    }

    @Test
    fun startupPublishesDiscoveredOriginBeforeContentRequests() = runTest {
        val transport = CoordinatorProbeTransport("https://wfwf490.com")
        val coordinator = WfwfOriginCoordinator(
            initialOrigin = "https://wfwf489.com",
            resolver = WfwfOriginResolver(transport, "agent"),
            scope = this,
        )

        coordinator.start()

        assertEquals("https://wfwf490.com", coordinator.awaitReady())
        val startupRequests = transport.requests.get()
        assertEquals("https://wfwf490.com", coordinator.awaitReady())
        assertEquals("https://wfwf490.com/view?toon=7", coordinator.resolve("/view?toon=7"))
        assertEquals(startupRequests, transport.requests.get())
    }

    @Test
    fun discoveredOriginCancelsAStalledRequestInsteadOfWaitingForItsTimeout() = runTest {
        val coordinator = WfwfOriginCoordinator(
            initialOrigin = "https://wfwf489.com",
            resolver = WfwfOriginResolver(CoordinatorProbeTransport("https://wfwf490.com"), "agent"),
            scope = this,
        )
        val staleCancelled = AtomicBoolean(false)
        coordinator.start()

        val result = coordinator.execute { requestOrigin ->
            if (requestOrigin == "https://wfwf489.com") {
                try {
                    awaitCancellation()
                } finally {
                    staleCancelled.set(true)
                }
            }
            requestOrigin
        }

        assertEquals("https://wfwf490.com", result)
        assertEquals(true, staleCancelled.get())
    }
}

private class CoordinatorProbeTransport(
    private val liveOrigin: String,
) : SourceTransport {
    val requests = AtomicInteger()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests.incrementAndGet()
        val alive = request.url.startsWith(liveOrigin)
        val bytes = if (alive) "<a href='/cl?toon=1'>WFWF</a>".toByteArray() else ByteArray(0)
        return SourceResponse(
            statusCode = if (alive) 200 else 404,
            finalUrl = request.url,
            headers = emptyMap(),
            body = CoordinatorProbeBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html",
        )
    }
}

private class CoordinatorProbeBytes(private val bytes: ByteArray) : PageByteStream {
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
