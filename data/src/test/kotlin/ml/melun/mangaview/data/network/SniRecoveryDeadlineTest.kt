package ml.melun.mangaview.data.network

import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SniRecoveryDeadlineTest {
    @Test fun silentDirectConnectionIsCancelledBeforeRecoveryAndSharesTheOriginalDeadline() = runTest {
        val bytes = "exact document · 원문".toByteArray()
        val body = Body(bytes)
        var active = 0
        var recoveryCalls = 0
        val original = request().copy(headers = mapOf("Referer" to "https://provider.test/series"))
        val protected = SniRecoveryTransport(SourceTransport {
            assertEquals(original, it)
            active++
            try { awaitCancellation() } finally { active-- }
        }, { SourceTransport {
            assertEquals(0, active)
            assertEquals(original.copy(totalTimeoutMillis = 4_000), it)
            recoveryCalls++
            delay(80)
            response(body)
        } }, { testScheduler.currentTime * 1_000_000L })
        try {
            assertArrayEquals(bytes, protected.execute(original).readBytes(1024))
            assertEquals(1_080L, testScheduler.currentTime)
            assertEquals(1, recoveryCalls)
            assertEquals(1, body.closes)
            assertEquals(0, active)
        } finally { protected.close() }
    }

    @Test fun aLateDirectResponseIsClosedBeforeRecoveryStarts() = runTest {
        val late = Body(byteArrayOf(1))
        val winner = Body(byteArrayOf(2))
        val protected = SniRecoveryTransport(SourceTransport {
            withContext(NonCancellable) { delay(1_100); response(late) }
        }, { SourceTransport {
            assertEquals(1, late.closes)
            assertEquals(3_900L, it.totalTimeoutMillis)
            response(winner)
        } }, { testScheduler.currentTime * 1_000_000L })
        try {
            assertArrayEquals(winner.bytes, protected.execute(request()).readBytes(1024))
            assertEquals(1, late.closes)
            assertEquals(1, winner.closes)
        } finally { protected.close() }
    }

    @Test fun callerCancellationDoesNotCreateRecovery() = runTest {
        val entered = CompletableDeferred<Unit>()
        var active = 0
        val protected = SniRecoveryTransport(SourceTransport {
            active++; entered.complete(Unit)
            try { awaitCancellation() } finally { active-- }
        }, { error("Caller cancellation must not recover") })
        try {
            val job = async { protected.execute(request()) }
            entered.await()
            advanceTimeBy(100)
            job.cancelAndJoin()
            assertEquals(0, active)
        } finally { protected.close() }
    }

    @Test fun aPromptDirectResponseKeepsItsPoolAndBodyOwner() = runTest {
        val body = Body(byteArrayOf(3, 4))
        val protected = SniRecoveryTransport(SourceTransport { delay(50); response(body) },
            { error("Prompt direct response must not recover") })
        try {
            assertArrayEquals(body.bytes, protected.execute(request()).readBytes(1024))
            assertEquals(50L, testScheduler.currentTime)
            assertEquals(1, body.closes)
        } finally { protected.close() }
    }

    @Test fun successfulRecoveryIsRememberedForTheNextRequestToThatHost() = runTest {
        var directCalls = 0
        var recoveryCalls = 0
        val protected = SniRecoveryTransport(SourceTransport { directCalls++; awaitCancellation() },
            { SourceTransport { recoveryCalls++; response(Body(byteArrayOf(7))) } },
            { testScheduler.currentTime * 1_000_000L })
        try {
            protected.execute(request()).close()
            protected.execute(request().copy(url = "https://provider.test/next")).close()
            assertEquals(1, directCalls)
            assertEquals(2, recoveryCalls)
            assertEquals(1_000L, testScheduler.currentTime)
        } finally { protected.close() }
    }

    @Test fun anExhaustedShortDeadlineDoesNotStartAnotherRequest() = runTest {
        var active = 0
        val protected = SniRecoveryTransport(SourceTransport {
            active++
            try { awaitCancellation() } finally { active-- }
        }, { error("No deadline remains") }, { testScheduler.currentTime * 1_000_000L })
        try {
            try { protected.execute(request().copy(totalTimeoutMillis = 500)); fail("Expected deadline failure") }
            catch (_: SocketTimeoutException) { }
            assertEquals(500L, testScheduler.currentTime)
            assertEquals(0, active)
        } finally { protected.close() }
    }

    @Test fun postRequestsAreNeitherShortenedNorReplayed() = runTest {
        val original = request().copy(method = SourceHttpMethod.POST,
            body = byteArrayOf(1, 2), bodyMediaType = "application/octet-stream")
        val body = Body(byteArrayOf(9))
        var calls = 0
        val protected = SniRecoveryTransport(SourceTransport {
            assertSame(original, it); calls++; delay(1_500); response(body)
        }, { error("POST must not recover") })
        try {
            protected.execute(original).close()
            assertEquals(1_500L, testScheduler.currentTime)
            assertEquals(1, calls)
            assertEquals(1, body.closes)
        } finally { protected.close() }
    }

    private fun request() = SourceRequest("https://provider.test/episode", totalTimeoutMillis = 5_000)
    private fun response(body: Body) = SourceResponse(200, request().url, emptyMap(), body,
        body.bytes.size.toLong(), "text/html")
    private class Body(val bytes: ByteArray) : PageByteStream {
        var closes = 0
        private var offset = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (this.offset == bytes.size) return -1
            val count = minOf(byteCount, bytes.size - this.offset)
            bytes.copyInto(destination, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        override fun close() { closes++ }
    }
}
