package ml.melun.mangaview.source

import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class ObservedSourceTransportTest {
    @Test fun absentObserverReturnsTheOriginalResponseAndStream() = immediate {
        val body = Body("original".toByteArray())
        val response = response(body)
        val transport = ObservedSourceTransport(SourceTransport { response }, "test") { null }
        assertSame(response, transport.execute(SourceRequest("https://example.test/page")))
        assertSame(body, response.body)
        assertEquals(0, body.reads)
    }

    @Test fun hashesActualDeliveredBytesAndExportsExactHtmlWithoutReadingAhead() = immediate {
        val bytes = "<html>원본</html>".toByteArray()
        val body = Body(bytes)
        val events = mutableListOf<SourceExchangeEvidence>()
        val observer = SourceExchangeObserver { events += it }
        val transport = ObservedSourceTransport(SourceTransport { response(body) }, "catalog") { observer }
        val response = transport.execute(SourceRequest("https://example.test/request",
            headers = mapOf("Cookie" to "private", "Authorization" to "private")))
        assertEquals(0, body.reads)
        response.body.promote(PageFetchPriority.FOCUS)
        response.body.awaitReadable()
        val received = mutableListOf<Byte>()
        val buffer = ByteArray(9) { 99 }
        while (true) {
            val count = response.body.readAtMost(buffer, 2, 3)
            if (count < 0) break
            received += buffer.copyOfRange(2, 2 + count).toList()
        }
        response.close()
        assertArrayEquals(bytes, received.toByteArray())
        assertEquals(listOf(SourceExchangePhase.STARTED, SourceExchangePhase.HEADERS,
            SourceExchangePhase.BODY_COMPLETE, SourceExchangePhase.CLOSED), events.map { it.phase })
        val complete = events.single { it.phase == SourceExchangePhase.BODY_COMPLETE }
        assertArrayEquals(bytes, complete.documentBody)
        assertEquals(hash(bytes), complete.bodySha256)
        assertEquals(bytes.size.toLong(), complete.bodyBytes)
        assertEquals("https://example.test/final", complete.finalUrl)
        assertEquals(1, events.map { it.requestId }.distinct().size)
        assertEquals(listOf(PageFetchPriority.FOCUS), body.promotions)
        assertEquals(1, body.readinessCalls)
        assertEquals(1, body.closes)
    }

    @Test fun partialCloseNeverClaimsACompleteResponseBody() = immediate {
        val body = Body("partial-body".toByteArray())
        val events = mutableListOf<SourceExchangeEvidence>()
        val transport = ObservedSourceTransport(SourceTransport { response(body) }, "test") { SourceExchangeObserver { events += it } }
        val response = transport.execute(SourceRequest("https://example.test/page"))
        response.body.readAtMost(ByteArray(2), 0, 2)
        response.close()
        assertFalse(events.any { it.phase == SourceExchangePhase.BODY_COMPLETE })
        assertEquals(2L, events.last().bodyBytes)
        assertNull(events.last().bodySha256)
    }

    @Test fun failedHeaderObservationClosesTheUnreturnedResponse() {
        val body = Body(byteArrayOf(1))
        val failure = IOException("observer")
        val transport = ObservedSourceTransport(SourceTransport { response(body) }, "test") {
            SourceExchangeObserver { if (it.phase == SourceExchangePhase.HEADERS) throw failure }
        }
        try { immediate { transport.execute(SourceRequest("https://example.test/page")) }; fail() }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(1, body.closes)
    }

    @Test fun requestFailureRemainsPrimaryIfItsObserverAlsoFails() {
        val failure = IOException("network")
        val observation = IOException("observation")
        val transport = ObservedSourceTransport(SourceTransport { throw failure }, "test") {
            SourceExchangeObserver { if (it.phase == SourceExchangePhase.REQUEST_FAILED) throw observation }
        }
        try { immediate { transport.execute(SourceRequest("https://example.test/page")) }; fail() }
        catch (actual: IOException) { assertSame(failure, actual); assertArrayEquals(arrayOf(observation), actual.suppressed) }
    }

    @Test fun preservesAlternateRoutingCapabilitiesAndTransportClose() = immediate {
        val body = Body(byteArrayOf(1))
        val routes = mutableListOf<String>()
        var closed = false
        val delegate = object : SourceTransport, Closeable {
            override suspend fun execute(request: SourceRequest): SourceResponse { routes += "primary"; return response(body) }
            override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse { routes += "fresh"; return response(body) }
            override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse { routes += "alternate"; return response(body) }
            override fun routeParallelism() = 3
            override fun supportsProtocolSelection() = true
            override fun close() { closed = true }
        }
        val transport = ObservedSourceTransport(delegate, "test") { null }
        val request = SourceRequest("https://example.test/page")
        transport.execute(request); transport.executeOnFreshRoute(request); transport.executeOnAlternateRoute(request)
        assertEquals(listOf("primary", "fresh", "alternate"), routes)
        assertEquals(3, transport.routeParallelism())
        assertTrue(transport.supportsProtocolSelection())
        transport.close()
        assertTrue(closed)
    }

    private fun response(body: Body) = SourceResponse(200, "https://example.test/final", emptyMap(), body,
        body.bytes.size.toLong(), "text/html; charset=utf-8")

    private class Body(val bytes: ByteArray) : PageByteStream {
        var position = 0
        var reads = 0
        var closes = 0
        var readinessCalls = 0
        val promotions = mutableListOf<PageFetchPriority>()
        override suspend fun awaitReadable() { readinessCalls++ }
        override fun promote(priority: PageFetchPriority) { promotions += priority }
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            reads++
            if (position == bytes.size) return -1
            val count = minOf(byteCount, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }
        override fun close() { closes++ }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return requireNotNull(outcome).getOrThrow()
    }
}
