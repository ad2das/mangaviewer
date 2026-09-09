package ml.melun.mangaview.source.ntk

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NtkBrowserDeadlineTest {
    @Test fun aSilentBrowserBecomesAnIoFailureAndReleasesItsWait() = runTest {
        var released = false
        try {
            awaitNtkBrowserResult { try { awaitCancellation() } finally { released = true } }
            fail("Expected a bounded browser failure")
        } catch (failure: IOException) { assertTrue(failure.message!!.contains("NTK")) }
        assertEquals(25_000, testScheduler.currentTime)
        assertTrue(released)
    }

    @Test fun leavingTheReaderKeepsCancellationInsteadOfShowingANetworkError() = runTest {
        var ioFailure = false
        val job = launch {
            try { awaitNtkBrowserResult { awaitCancellation() } }
            catch (_: IOException) { ioFailure = true }
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(ioFailure)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun aSuccessfulAuthorizationIsReturnedUnchanged() = runTest {
        val proof = Any()
        assertSame(proof, awaitNtkBrowserResult { proof })
        assertEquals(0, testScheduler.currentTime)
    }
}
