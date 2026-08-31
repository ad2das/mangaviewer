package ml.melun.mangaview.source.ntk

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NtkReplicaRacerTest {
    @Test
    fun fastestVerifiedReplicaWinsAndTheSlowAttemptIsCancelled() = runTest {
        val transport = DelayedReplicaTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 250L,
            primaryRouteRetryMillis = 10_000L,
        )

        val winner = racer.open(
            listOf("https://slow.test/page", "https://fast.test/page"),
            emptyMap(),
            "p0001",
        ) { response ->
            val prefix = ByteArray(3)
            assertEquals(3, response.body.readAtMost(prefix, 0, prefix.size))
            assertEquals(listOf(0xff, 0xd8, 0xff), prefix.map { it.toInt() and 0xff })
            OpenedPage(
                response.body,
                response.contentLength,
                response.contentType,
                entityTag = null,
                lastModified = null,
            )
        }

        assertEquals("fast.test", winner.lease.candidate.host)
        assertEquals(300L, testScheduler.currentTime)
        assertEquals(listOf("slow.test", "fast.test"), transport.started)
        assertTrue("slow.test" in transport.cancelled)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun verifiedReplicaRunsAloneWithoutHedgingHealthyPageRequests() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "verified.test" to 50L,
            "alternate.test" to 10L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val prepared = selector.prepare(listOf(
            "https://verified.test/page",
            "https://alternate.test/page",
        ))
        val proof = selector.acquireCandidate(prepared.first())
        selector.accepted(proof, 50L)
        selector.abandoned(proof)
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 250L,
            primaryRouteRetryMillis = 10_000L,
        )

        val winner = racer.open(
            prepared.map { it.url },
            emptyMap(),
            "p0002",
            ::validatedPage,
        )

        assertEquals("verified.test", winner.lease.candidate.host)
        assertEquals(50L, testScheduler.currentTime)
        assertEquals(listOf("verified.test"), transport.started)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun unknownPrimaryGetsOneRouteHedgeWithoutWaitingForOuterBackoff() = runTest {
        val transport = FirstRouteHangsTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 250L,
            primaryRouteRetryMillis = 250L,
        )

        val winner = racer.open(
            listOf("https://route.test/page"),
            emptyMap(),
            "p0003",
            ::validatedPage,
        )

        assertEquals(300L, testScheduler.currentTime)
        assertEquals(2, transport.starts.get())
        assertEquals(1, transport.cancellations.get())
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun speculativeForwardPageLeavesReplicaLanesForAVisiblePage() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "first.test" to 5_000L,
            "second.test" to 50L,
            "third.test" to 1L,
            "fourth.test" to 1L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 0L,
            primaryRouteRetryMillis = 250L,
        )

        val winner = racer.open(
            listOf(
                "https://first.test/page",
                "https://second.test/page",
                "https://third.test/page",
                "https://fourth.test/page",
            ),
            emptyMap(),
            "p0004",
            PageFetchPriority.FORWARD,
            ::validatedPage,
        )

        assertEquals("second.test", winner.lease.candidate.host)
        assertEquals(listOf("first.test", "second.test"), transport.started)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    private suspend fun validatedPage(response: SourceResponse): OpenedPage {
        val prefix = ByteArray(3)
        assertEquals(3, response.body.readAtMost(prefix, 0, prefix.size))
        return OpenedPage(
            response.body,
            response.contentLength,
            response.contentType,
            entityTag = null,
            lastModified = null,
        )
    }
}

private class FirstRouteHangsTransport : SourceTransport {
    val starts = AtomicInteger()
    val cancellations = AtomicInteger()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val attempt = starts.incrementAndGet()
        try {
            delay(if (attempt == 1) 5_000L else 50L)
        } catch (cancelled: CancellationException) {
            cancellations.incrementAndGet()
            throw cancelled
        }
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = ReplicaBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "image/jpeg",
        )
    }
}

private class DelayedReplicaTransport(
    private val delays: Map<String, Long> = emptyMap(),
) : SourceTransport {
    val started = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val host = requireNotNull(URI(request.url).host)
        started += host
        try {
            delay(delays[host] ?: if (host == "slow.test") 5_000L else 50L)
        } catch (cancelledFailure: CancellationException) {
            cancelled += host
            throw cancelledFailure
        }
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = ReplicaBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "image/jpeg",
        )
    }
}

private class ReplicaBytes(private val bytes: ByteArray) : PageByteStream {
    private var offset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (this.offset == bytes.size) return -1
        val count = minOf(byteCount, bytes.size - this.offset)
        bytes.copyInto(destination, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    override fun close() = Unit
}
