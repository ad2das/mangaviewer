package ml.melun.mangaview.source.ntk

import java.io.IOException
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkReplicaRaceOwnershipTest {
    @Test
    fun selectedPageIsTransferredWhileEveryOtherPageIsReleasedOnce() {
        val bodies = List(3) { Body() }
        val winners = bodies.map(::winner)
        var leasesReleased = 0
        val ownership = NtkReplicaRaceOwnership { leasesReleased++ }
        winners.forEach(ownership::retain)
        ownership.take(winners[1])
        ownership.close()
        ownership.close()
        assertEquals(listOf(1, 0, 1), bodies.map { it.closes })
        assertEquals(2, leasesReleased)
        winners[1].opened.close()
    }

    @Test
    fun oneCloseFailureDoesNotLeakAnyOtherBodyOrLease() {
        val bodies = listOf(Body(throws = true), Body(), Body(throws = true))
        var leasesReleased = 0
        val ownership = NtkReplicaRaceOwnership { leasesReleased++ }
        bodies.map(::winner).forEach(ownership::retain)
        val result = runCatching { ownership.close() }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, result.exceptionOrNull()?.suppressed?.size)
        assertEquals(listOf(1, 1, 1), bodies.map { it.closes })
        assertEquals(3, leasesReleased)
        ownership.close()
    }

    private fun winner(body: Body) = NtkReplicaWinner(
        OpenedPage(body, 1L, "image/jpeg", null, null),
        NtkReplicaSelector.ReplicaLease(NtkReplicaSelector.ReplicaCandidate("https://test/page", "test")),
        0L, false,
    )

    private class Body(private val throws: Boolean = false) : PageByteStream {
        var closes = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
        override fun close() {
            closes++
            if (throws) throw IOException("body close failed")
        }
    }
}
