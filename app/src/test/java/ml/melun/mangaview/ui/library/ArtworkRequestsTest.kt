package ml.melun.mangaview.ui.library

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtworkRequestsTest {
    @Test fun leavingFirstCardDoesNotCancelAnotherCardsCover() = runTest {
        val requests = ArtworkRequests<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<String>()
        var fetches = 0
        val first = async { requests.load("cover") { fetches++; finish.await() } }
        runCurrent()
        val second = async { requests.load("cover") { error("Duplicate fetch") } }
        runCurrent()
        first.cancelAndJoin()
        finish.complete("decoded")
        assertEquals("decoded", second.await())
        assertEquals(1, fetches)
    }

    @Test fun lastCardLeavingCancelsTheTransferAndReturningCardCanRetry() = runTest {
        val requests = ArtworkRequests<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val stopped = CompletableDeferred<Unit>()
        val card = async {
            requests.load("cover") {
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        runCurrent()
        card.cancelAndJoin()
        stopped.await()
        assertEquals("retry", requests.load("cover") { "retry" })
    }

    @Test fun leavingBeforeTheTransferStartsDoesNotOpenIt() = runTest {
        val requests = ArtworkRequests<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val admission = CompletableDeferred<Unit>()
        var opened = false
        val card = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            requests.load("cover") { admission.await(); opened = true; "unused" }
        }
        card.cancelAndJoin()
        admission.complete(Unit)
        runCurrent()
        assertFalse(opened)
    }

    @Test fun failingCoverDoesNotCancelUnrelatedCoversOrTheApplicationScope() = runTest {
        val requests = ArtworkRequests<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val failure = IOException("cover unavailable")
        val bad = async { runCatching { requests.load("bad") { throw failure } } }
        val good = async { requests.load("good") { "decoded" } }
        assertSame(failure, bad.await().exceptionOrNull())
        assertEquals("decoded", good.await())
        assertEquals("recovered", requests.load("bad") { "recovered" })
    }
}
