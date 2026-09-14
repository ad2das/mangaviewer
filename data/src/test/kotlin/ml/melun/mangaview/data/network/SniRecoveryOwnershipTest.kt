package ml.melun.mangaview.data.network

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SniRecoveryOwnershipTest {
    @Test fun delayedHedgeReceivesOnlyTheRemainingBudget() = runTest {
        val budgets = mutableListOf<Long>()
        val transport = SniRecoveryTransport(SourceTransport { throw IOException("blocked") },
            { SourceTransport { budgets += it.totalTimeoutMillis; awaitCancellation() } },
            { testScheduler.currentTime * 1_000_000 })
        try {
            try { transport.execute(request()); fail("Expected timeout") }
            catch (_: TimeoutCancellationException) { }
            assertEquals(listOf(5000L, 4200L), budgets)
        } finally { transport.close() }
    }

    @Test fun hedgesMustRespectOriginalDeadline() = runTest {
        val transport = SniRecoveryTransport(SourceTransport { throw IOException("blocked") },
            { SourceTransport { awaitCancellation() } }, { testScheduler.currentTime * 1_000_000 })
        try {
            try { transport.execute(request()); fail("Expected timeout") }
            catch (_: TimeoutCancellationException) { }
            assertTrue("elapsed=${testScheduler.currentTime}, budget=5000", testScheduler.currentTime <= 5000)
        } finally { transport.close() }
    }

    @Test fun completedWinnerMustCloseIfCallerCancelsDuringLoserCleanup() = runTest {
        val winnerReady = CompletableDeferred<Unit>()
        val loserCleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var calls = 0
        var closes = 0
        val body = object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int) = -1
            override fun close() { closes++ }
        }
        val transport = SniRecoveryTransport(SourceTransport { throw IOException("blocked") },
            { SourceTransport {
                if (++calls == 1) {
                    winnerReady.await()
                    SourceResponse(200, request().url, emptyMap(), body, 0L, null)
                } else {
                    winnerReady.complete(Unit)
                    try { awaitCancellation() } finally {
                        loserCleaning.complete(Unit)
                        withContext(NonCancellable) { finishCleanup.await() }
                    }
                }
            } }, { testScheduler.currentTime * 1_000_000 })
        try {
            val caller = async { transport.execute(request()).close() }
            loserCleaning.await()
            caller.cancel()
            finishCleanup.complete(Unit)
            caller.join()
            assertEquals("A response never delivered to the caller must be closed", 1, closes)
        } finally { transport.close() }
    }

    private fun request() = SourceRequest("https://review.test/page", totalTimeoutMillis = 5000,
        priority = PageFetchPriority.VISIBLE)
}

