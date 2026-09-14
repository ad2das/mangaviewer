package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SurfaceAttachmentRetryTest {
    @Test fun transientFailureRecoversWithoutFurtherAttempts() = runTest {
        var attempts = 0
        retrySurfaceAttachment(25, 200, { true }, { ++attempts == 3 }, { error("Unexpected exhaustion") })
        assertEquals(3, attempts)
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test fun persistentFailureExhaustsExactlyOneBoundedWindow() = runTest {
        var attempts = 0
        var exhausted = 0
        retrySurfaceAttachment(25, 200, { true }, { attempts++; false }, { exhausted++ })
        assertEquals(26, attempts)
        assertEquals(1, exhausted)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun detachingDuringDelayCancelsRecoveryWithoutReportingFailure() = runTest {
        var attempts = 0
        val retry = launch {
            retrySurfaceAttachment(25, 200, { true }, { attempts++; false }, { error("Detached") })
        }
        runCurrent()
        retry.cancelAndJoin()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun lateAttachCompletionCannotReviveCancelledRecovery() = runTest {
        val response = CompletableDeferred<Boolean>()
        var attempts = 0
        val retry = launch {
            retrySurfaceAttachment(25, 200, { true }, { attempts++; response.await() }, { error("Detached") })
        }
        runCurrent()
        retry.cancelAndJoin()
        response.complete(false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun invalidSurfaceAfterDelayDoesNotStartAnotherAttach() = runTest {
        var available = true
        var attempts = 0
        val retry = launch {
            retrySurfaceAttachment(25, 200, { available }, { attempts++; false }, { error("Invalid surface") })
        }
        runCurrent()
        available = false
        advanceTimeBy(200)
        runCurrent()
        retry.join()
        assertEquals(1, attempts)
    }
}
