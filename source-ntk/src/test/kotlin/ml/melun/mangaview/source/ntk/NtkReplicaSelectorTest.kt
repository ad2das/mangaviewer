package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NtkReplicaSelectorTest {
    @Test
    fun failedHostMovesBehindHealthyAlternativeWithoutDroppingCandidates() = runTest {
        var now = 1_000L
        val selector = NtkReplicaSelector { now }
        val slow = "https://slow.example/page"
        val healthy = "https://healthy.example/page"

        selector.failed(slow)
        selector.succeeded(healthy, 80L)

        assertEquals(listOf(healthy, slow), selector.order(listOf(slow, healthy)))
        now += 10_000L
        assertEquals(listOf(healthy, slow), selector.order(listOf(slow, healthy)))
    }

    @Test
    fun requestKeepsStableDeduplicatedReplicaOrder() {
        val request = NtkPageRequest(
            url = "https://a.example/page",
            alternateUrls = listOf("https://b.example/page", "https://a.example/page"),
        )

        assertEquals(
            listOf("https://a.example/page", "https://b.example/page"),
            request.candidates,
        )
    }

    @Test
    fun concurrentRequestsSpreadAcrossHealthyReplicaHosts() = runTest {
        val selector = NtkReplicaSelector()
        val candidates = listOf(
            "https://a.example/page",
            "https://b.example/page",
            "https://c.example/page",
        )
        val acquired = List(3) { selector.acquire(candidates) }

        assertEquals(candidates, acquired)
        acquired.forEach { selector.release(it) }
        selector.succeeded(candidates.first(), 50L)
        assertEquals(candidates.first(), selector.acquire(candidates))
    }

    @Test
    fun successDoesNotEraseOtherInFlightRequestsForTheSameHost() = runTest {
        val selector = NtkReplicaSelector()
        val a = "https://a.example/page"
        val b = "https://b.example/page"
        assertEquals(a, selector.acquire(listOf(a, b)))
        assertEquals(a, selector.acquire(listOf(a)))

        selector.succeeded(a, 400L)
        selector.release(a)

        assertEquals(b, selector.acquire(listOf(a, b)))
    }

    @Test
    fun preparedLeaseParsesEachOpaqueUrlOnlyOnceAcrossItsLifecycle() = runTest {
        var parseCount = 0
        val selector = NtkReplicaSelector(resolveHost = { url ->
            parseCount += 1
            requireNotNull(URI(url).host).lowercase()
        })
        val prepared = selector.prepare(listOf(
            "https://a.example/opaque/page-token.woff2",
            "https://b.example/opaque/page-token.js",
        ))

        val lease = selector.acquirePrepared(prepared)
        selector.succeeded(lease, 40L)
        selector.release(lease)

        assertEquals(2, parseCount)
    }

    @Test
    fun cooledReplicaIsProbedWhenTheHealthyReplicaIsBusy() = runTest {
        var now = 1_000L
        val selector = NtkReplicaSelector { now }
        val cooled = "https://cooled.example/page"
        val healthy = "https://healthy.example/page"
        selector.failed(cooled)
        repeat(3) { selector.succeeded(healthy, 30L) }
        now += 10_000L
        selector.acquire(listOf(healthy))

        assertEquals(healthy, selector.acquire(listOf(cooled, healthy)))
    }

    @Test
    fun measuredCompletionTimeAssignsMoreWorkToTheFasterReplica() = runTest {
        val selector = NtkReplicaSelector()
        val fast = "https://fast.example/page"
        val slow = "https://slow.example/page"
        repeat(3) { selector.succeeded(fast, 100L) }
        repeat(3) { selector.succeeded(slow, 300L) }

        val acquired = List(6) { selector.acquire(listOf(fast, slow)) }

        assertEquals(listOf(fast, fast, slow, fast, fast, fast), acquired)
        acquired.forEach { selector.release(it) }
    }

    @Test
    fun idleReplicaHistoryIsBounded() = runTest {
        val selector = NtkReplicaSelector()
        repeat(500) { selector.succeeded("https://host-$it.example/page", it.toLong()) }

        assertEquals(64, selector.trackedHostCount())
    }

    @Test
    fun emptyCandidatesAndNegativeLatencyAreRejected() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.test.runTest { NtkReplicaSelector().acquire(emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.test.runTest {
                NtkReplicaSelector().succeeded("https://a.example/page", -1L)
            }
        }
    }
}
