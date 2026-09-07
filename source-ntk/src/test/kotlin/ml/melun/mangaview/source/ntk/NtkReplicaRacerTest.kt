package ml.melun.mangaview.source.ntk

import java.net.URI
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
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
    fun validationTimeoutClosesItsResponseAndFinishesTheRace() = runTest {
        var closes = 0
        val body = object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
            override fun close() { closes++ }
        }
        val transport = SourceTransport { request ->
            SourceResponse(200, request.url, emptyMap(), body, 1024L, "image/jpeg")
        }
        val racer = NtkReplicaRacer(transport,
            NtkReplicaSelector(nowMillis = { testScheduler.currentTime }), attemptTimeoutMillis = 25L)
        val result = runCatching {
            withTimeout(1_000L) {
                racer.open(listOf("https://timeout.test/image"), emptyMap(), "timeout",
                    PageFetchPriority.FORWARD,
                    validate = { response -> delay(10_000L); validatedPage(response) })
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(25L, testScheduler.currentTime)
        assertEquals(1, closes)
    }

    @Test
    fun attemptTimeoutProducesATerminalFailureInsteadOfLosingTheOutcome() = runTest {
        val transport = object : SourceTransport {
            override suspend fun execute(request: SourceRequest): SourceResponse {
                delay(10_000L)
                error("Timed-out request must not return")
            }
        }
        val racer = NtkReplicaRacer(transport,
            NtkReplicaSelector(nowMillis = { testScheduler.currentTime }), attemptTimeoutMillis = 25L)
        val result = runCatching {
            withTimeout(1_000L) {
                racer.open(listOf("https://timeout.test/image"), emptyMap(), "timeout",
                    PageFetchPriority.FORWARD, ::validatedPage)
            }
        }
        assertTrue("The enclosing deadline fired because the route never published failure: $result",
            result.exceptionOrNull() is IOException)
        assertEquals(25L, testScheduler.currentTime)
    }

    @Test
    fun focusRouteHasNoArtificialHedgeDelay() {
        assertEquals(0L, NtkReplicaRacePolicy.routeHedge(PageFetchPriority.FOCUS, 150L))
        assertEquals(150L, NtkReplicaRacePolicy.routeHedge(PageFetchPriority.VISIBLE, 150L))
        assertEquals(150L, NtkReplicaRacePolicy.routeHedge(PageFetchPriority.FORWARD, 150L))
    }

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
            validate = { response ->
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
        })

        assertEquals("fast.test", winner.lease.candidate.host)
        assertEquals(300L, testScheduler.currentTime)
        assertEquals(listOf("slow.test", "fast.test"), transport.started)
        assertTrue("slow.test" in transport.cancelled)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun concurrentVisibleBodiesPreferAnIdleReplicaWithinABoundedGrace() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "busy.test" to 10L,
            "idle.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 0L,
            primaryRouteRetryMillis = 10_000L,
        )
        val first = racer.open(
            listOf("https://busy.test/first"),
            emptyMap(),
            "first",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        val second = racer.open(
            listOf("https://busy.test/second", "https://idle.test/second"),
            emptyMap(),
            "second",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals("busy.test", first.lease.candidate.host)
        assertEquals("idle.test", second.lease.candidate.host)
        first.opened.close()
        second.opened.close()
        racer.abandoned(first)
        racer.abandoned(second)
        selector.abandoned(first.lease)
        selector.abandoned(second.lease)
    }

    @Test
    fun concurrentForwardBodiesDoNotDelayAcceptedHeadersForDistribution() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "busy.test" to 10L,
            "idle.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 0L,
            primaryRouteRetryMillis = 10_000L,
        )
        val first = racer.open(
            listOf("https://busy.test/first"), emptyMap(), "first",
            PageFetchPriority.FORWARD, ::validatedPage,
        )

        val second = racer.open(
            listOf("https://busy.test/second", "https://idle.test/second"),
            emptyMap(), "second", PageFetchPriority.FORWARD, ::validatedPage,
        )

        assertEquals("busy.test", second.lease.candidate.host)
        first.opened.close()
        second.opened.close()
        racer.abandoned(first)
        racer.abandoned(second)
        selector.abandoned(first.lease)
        selector.abandoned(second.lease)
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
        repeat(3) {
            val proof = selector.acquireCandidate(prepared.first())
            selector.completed(proof, 50L)
        }
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
    fun stalledVerifiedVisibleRouteIsHedgedBeforeItsTimeout() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "verified.test" to 5_000L,
            "alternate.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val prepared = selector.prepare(listOf(
            "https://verified.test/page",
            "https://alternate.test/page",
        ))
        repeat(3) {
            val proof = selector.acquireCandidate(prepared.first())
            selector.completed(proof, 50L)
        }
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 250L,
        )

        val winner = racer.open(
            prepared.map { it.url },
            emptyMap(),
            "p0002",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals("alternate.test", winner.lease.candidate.host)
        assertEquals(125L, testScheduler.currentTime)
        assertTrue("verified.test" in transport.cancelled)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun stalledVerifiedForwardRouteIsHedgedBeforeItsTimeout() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "verified.test" to 5_000L,
            "alternate.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val prepared = selector.prepare(listOf(
            "https://verified.test/page",
            "https://alternate.test/page",
        ))
        val proof = selector.acquireCandidate(prepared.first())
        selector.completed(proof, 50L)
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 250L,
        )

        val winner = racer.open(
            prepared.map { it.url },
            emptyMap(),
            "p0002",
            PageFetchPriority.FORWARD,
            ::validatedPage,
        )

        assertEquals("alternate.test", winner.lease.candidate.host)
        assertEquals(125L, testScheduler.currentTime)
        assertTrue("verified.test" in transport.cancelled)
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
    fun visibleRequestHedgesAStalledQuicHandshakeWithHttp2() = runTest {
        val transport = ProtocolHedgeTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 10_000L,
            preferQuic = true,
        )

        val winner = racer.open(
            listOf("https://route.test/page"),
            emptyMap(),
            "p0003",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals(175L, testScheduler.currentTime)
        assertEquals(listOf(true, false), transport.protocols)
        assertEquals(false, winner.usedQuic)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun unknownForwardRequestAlsoHedgesAStalledQuicHandshakeWithHttp2() = runTest {
        val transport = ProtocolHedgeTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 10_000L,
            preferQuic = true,
        )

        val winner = racer.open(
            listOf("https://route.test/page"),
            emptyMap(),
            "p0004",
            PageFetchPriority.FORWARD,
            ::validatedPage,
        )

        assertEquals(175L, testScheduler.currentTime)
        assertEquals(listOf(true, false), transport.protocols)
        assertEquals(false, winner.usedQuic)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun successfulVisibleProtocolIsReusedWithoutAHedgeForForwardPages() = runTest {
        val transport = ProtocolHedgeTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 10_000L,
            preferQuic = true,
        )
        val candidate = listOf("https://route.test/page")
        val visible = racer.open(
            candidate,
            emptyMap(),
            "p0003",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )
        racer.completed(visible)
        selector.completed(visible.lease, 50L)
        visible.opened.close()
        transport.protocols.clear()

        val forward = racer.open(
            candidate,
            emptyMap(),
            "p0004",
            PageFetchPriority.FORWARD,
            ::validatedPage,
        )

        assertEquals(listOf(false), transport.protocols)
        assertEquals(false, forward.usedQuic)
        forward.opened.close()
        selector.abandoned(forward.lease)
    }

    @Test
    fun speculativeForwardPageRacesOnlyTheBoundedThreeReplicaWindow() = runTest {
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

        assertEquals("third.test", winner.lease.candidate.host)
        assertEquals(listOf("first.test", "second.test", "third.test"), transport.started)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun visiblePageCanUseAHealthyThirdReplicaWhenTheFirstPairStalls() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "first.test" to 5_000L,
            "second.test" to 5_000L,
            "third.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport,
            selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 10_000L,
        )

        val winner = racer.open(
            listOf(
                "https://first.test/page",
                "https://second.test/page",
                "https://third.test/page",
            ),
            emptyMap(),
            "visible-third",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals("third.test", winner.lease.candidate.host)
        assertEquals(200L, testScheduler.currentTime)
        assertEquals(listOf("first.test", "second.test", "third.test"), transport.started)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun multiOriginVisibleRaceDoesNotAlsoDuplicateItsPrimaryProtocol() = runTest {
        val transport = MultiOriginProtocolTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport = transport,
            replicas = selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 125L,
            preferQuic = true,
        )

        val winner = racer.open(
            listOf(
                "https://first.test/page",
                "https://second.test/page",
                "https://third.test/page",
            ),
            emptyMap(),
            "visible-protocol-window",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals("third.test", winner.lease.candidate.host)
        assertEquals(listOf(true, true, true), transport.protocols)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun coldFocusHedgesItsPrimaryProtocolEvenWithMultipleOrigins() = runTest {
        val transport = ProtocolHedgeTransport()
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport = transport,
            replicas = selector,
            hedgeDelayMillis = 75L,
            primaryRouteRetryMillis = 10_000L,
            preferQuic = true,
        )

        val winner = racer.open(
            listOf(
                "https://first.test/page",
                "https://second.test/page",
                "https://third.test/page",
            ),
            emptyMap(),
            "cold-focus-protocol-window",
            PageFetchPriority.FOCUS,
            ::validatedPage,
        )

        assertEquals(175L, testScheduler.currentTime)
        assertEquals(listOf(true, true, true, false), transport.protocols)
        assertEquals(false, winner.usedQuic)
        winner.opened.close()
        selector.abandoned(winner.lease)
    }

    @Test
    fun multipleStalledForwardPagesCannotQueueAheadOfAVisiblePage() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "forward-a.test" to 5_000L,
            "forward-b.test" to 5_000L,
            "visible.test" to 50L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport = transport,
            replicas = selector,
            hedgeDelayMillis = 0L,
            primaryRouteRetryMillis = 10_000L,
            maxConcurrentAttempts = 2,
            visibleReservedAttempts = 1,
        )
        val firstForward = async {
            racer.open(
                listOf("https://forward-a.test/page"),
                emptyMap(),
                "forward-a",
                PageFetchPriority.FORWARD,
                ::validatedPage,
            )
        }
        val secondForward = async {
            racer.open(
                listOf("https://forward-b.test/page"),
                emptyMap(),
                "forward-b",
                PageFetchPriority.FORWARD,
                ::validatedPage,
            )
        }
        runCurrent()

        val visible = racer.open(
            listOf("https://visible.test/page"),
            emptyMap(),
            "visible",
            PageFetchPriority.VISIBLE,
            ::validatedPage,
        )

        assertEquals(50L, testScheduler.currentTime)
        assertTrue("visible.test" in transport.started)
        assertEquals(1, transport.started.count { it.startsWith("forward-") })
        visible.opened.close()
        selector.abandoned(visible.lease)
        firstForward.cancelAndJoin()
        secondForward.cancelAndJoin()
    }

    @Test
    fun concurrentViewportPagesReserveDifferentPrimaryReplicas() = runTest {
        val transport = DelayedReplicaTransport(mapOf(
            "first.test" to 5_000L,
            "second.test" to 5_000L,
            "third.test" to 5_000L,
        ))
        val selector = NtkReplicaSelector(nowMillis = { testScheduler.currentTime })
        val racer = NtkReplicaRacer(
            transport = transport,
            replicas = selector,
            hedgeDelayMillis = 750L,
            primaryRouteRetryMillis = 10_000L,
            maxConcurrentAttempts = 8,
            visibleReservedAttempts = 2,
        )
        val candidates = listOf(
            "https://first.test/page",
            "https://second.test/page",
            "https://third.test/page",
        )

        val requests = List(3) { index ->
            async {
                racer.open(
                    candidates,
                    emptyMap(),
                    "p000$index",
                    PageFetchPriority.FORWARD,
                    ::validatedPage,
                )
            }
        }
        runCurrent()

        assertEquals(
            setOf("first.test", "second.test", "third.test"),
            transport.started.take(3).toSet(),
        )
        requests.forEach { it.cancelAndJoin() }
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

private class ProtocolHedgeTransport : SourceTransport {
    val protocols = mutableListOf<Boolean>()

    override fun supportsProtocolSelection(): Boolean = true

    override suspend fun execute(request: SourceRequest): SourceResponse {
        protocols += request.preferQuic
        delay(if (request.preferQuic) 5_000L else 50L)
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

private class MultiOriginProtocolTransport : SourceTransport {
    val protocols = mutableListOf<Boolean>()

    override fun supportsProtocolSelection(): Boolean = true

    override suspend fun execute(request: SourceRequest): SourceResponse {
        protocols += request.preferQuic
        val host = requireNotNull(URI(request.url).host)
        delay(if (host == "third.test") 50L else 5_000L)
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
