package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkRetirementSubscriptionTest {
    private val key = WorkKey("page", "same-original", "load", "1", String::class.java)

    @Test fun aDifferentOwnerWaitsForActualCleanupBeforeRestartingTheSameOriginal() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val first = coordinator.submit(WorkRequest(key, WorkDomain.BODY, WorkPriority.NEXT_IMAGE, execute = {
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaned.await() } }
        }))
        started.await()
        first.close()
        var replacementExecutions = 0
        val next = async { coordinator.submitAfterRetirement(WorkRequest(key, WorkDomain.BODY, WorkPriority.FOCUS,
            execute = { replacementExecutions++; "original" })) }
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals(0, replacementExecutions)
        cleaned.complete(Unit)
        first.awaitReleased()
        val subscription = next.await()
        assertEquals("original", subscription.await())
        assertEquals(1, replacementExecutions)
        subscription.close()
        subscription.awaitReleased()
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun cancellingAWaitingOwnerCannotStartAnUnwantedReplacementAfterCleanup() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val first = coordinator.submit(WorkRequest(key, WorkDomain.BODY, WorkPriority.NEXT_IMAGE, execute = {
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaned.await() } }
        }))
        started.await()
        first.close()
        var replacementExecutions = 0
        val next = async { coordinator.submitAfterRetirement(WorkRequest(key, WorkDomain.BODY, WorkPriority.FOCUS,
            execute = { replacementExecutions++; "unwanted" })) }
        runCurrent()
        next.cancel()
        next.join()
        cleaned.complete(Unit)
        first.awaitReleased()
        runCurrent()
        assertEquals(0, replacementExecutions)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }
}
