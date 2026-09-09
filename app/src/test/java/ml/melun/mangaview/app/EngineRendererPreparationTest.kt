package ml.melun.mangaview.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineRendererPreparationTest {
    @Test fun readyRendererIsSharedWithAnyReaderAndReturnWaitsForReaderClosure() = runTest {
        val created = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler),
            { (created.size + 1).also(created::add) }, {}, { released += it }, { throw it })
        pool.warm(); runCurrent()
        assertEquals(1, pool.preparedSnapshot())
        val reader = pool.claim()
        assertEquals(1, reader.value)
        pool.warm(); runCurrent()
        assertEquals(listOf(1), created)
        assertTrue(released.isEmpty())
        reader.close(); runCurrent()
        assertEquals(listOf(1), released)
        assertEquals(2, pool.preparedSnapshot())
        reader.close()
        pool.close()
        assertEquals(listOf(1, 2), released)
    }

    @Test fun selectionBeforeCreationDoesNotLeaveASecondRenderer() = runTest {
        var created = 0
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler),
            { ++created }, {}, {}, { throw it })
        pool.warm()
        val reader = pool.claim()
        assertNull(reader.value)
        runCurrent()
        assertEquals(0, created)
        reader.close(); pool.close()
    }

    @Test fun selectionDuringNativePreparationTransfersOwnershipAndCloseWaitsForIt() = runTest {
        val nativeDone = CompletableDeferred<Unit>()
        val released = mutableListOf<Int>()
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler), { 1 },
            { withContext(NonCancellable) { nativeDone.await() } }, { released += it }, { throw it })
        pool.warm(); runCurrent()
        assertNull(pool.preparedSnapshot())
        val reader = pool.claim()
        assertEquals(1, reader.value)
        pool.cancel()
        val close = async { reader.close() }
        val otherClose = async { reader.close() }
        runCurrent()
        assertFalse(close.isCompleted)
        assertFalse(otherClose.isCompleted)
        assertTrue(released.isEmpty())
        nativeDone.complete(Unit)
        close.await(); otherClose.await(); pool.close()
        assertEquals(listOf(1), released)
    }

    @Test fun cancellationAndReplacementDrainNativeWorkBeforeCreatingAnotherOwner() = runTest {
        val nativeDone = CompletableDeferred<Unit>()
        val created = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler),
            { (created.size + 1).also(created::add) },
            { if (it == 1) withContext(NonCancellable) { nativeDone.await() } },
            { released += it }, { throw it })
        pool.warm(); runCurrent()
        pool.cancel(); pool.warm(); runCurrent()
        assertEquals(listOf(1), created)
        assertTrue(released.isEmpty())
        nativeDone.complete(Unit); runCurrent()
        assertEquals(listOf(1), released)
        assertEquals(listOf(1, 2), created)
        assertEquals(2, pool.preparedSnapshot())
        pool.close()
        assertEquals(listOf(1, 2), released)
    }

    @Test fun failedPreparationDisposesItsOwnerAndCanRetry() = runTest {
        val failure = IllegalStateException("native initialization")
        var created = 0
        val released = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler), { ++created },
            { if (it == 1) throw failure }, { released += it }, failures::add)
        pool.warm(); runCurrent()
        assertNull(pool.preparedSnapshot())
        assertEquals(listOf(failure), failures)
        assertEquals(listOf(1), released)
        pool.warm(); runCurrent()
        assertEquals(2, pool.preparedSnapshot())
        pool.close()
        assertEquals(listOf(1, 2), released)
    }

    @Test fun memoryCancellationDoesNotRevivePreparationWhenTheReaderCloses() = runTest {
        var created = 0
        val released = mutableListOf<Int>()
        val pool = EngineRendererPreparation(this, StandardTestDispatcher(testScheduler),
            { ++created }, {}, { released += it }, { throw it })
        pool.warm(); runCurrent()
        val reader = pool.claim()
        pool.warm(); pool.cancel()
        reader.close(); runCurrent()
        assertEquals(1, created)
        assertEquals(listOf(1), released)
        assertNull(pool.preparedSnapshot())
        pool.close()
        pool.warm(); runCurrent()
        assertEquals(1, created)
    }
}
