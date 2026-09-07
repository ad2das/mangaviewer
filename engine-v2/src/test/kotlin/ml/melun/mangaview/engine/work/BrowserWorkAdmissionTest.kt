package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserWorkAdmissionTest {
    @Test fun nextBrowserWaitsForCancelledExecutionCleanupWhileNativeBodiesRemainIndependent() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val retirement = CompletableDeferred<Unit>()
        val first = coordinator.submit(WorkRequest(
            WorkKey("first", "document", "authorization", "1", String::class.java),
            WorkDomain.BROWSER, WorkPriority.FOCUS, execute = {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { retirement.await() } }
            },
        ))
        started.await()
        var nextStarted = false
        val second = coordinator.submit(WorkRequest(
            WorkKey("second", "document", "authorization", "1", String::class.java),
            WorkDomain.BROWSER, WorkPriority.FOCUS, execute = { nextStarted = true; "authorized" },
        ))
        first.close()
        runCurrent()
        assertFalse(nextStarted)
        val native = coordinator.acquire(WorkRequest(
            WorkKey("first", "image", "body", "1", String::class.java),
            WorkDomain.BODY, WorkPriority.FOCUS, execute = { "image" },
        ))
        assertEquals("image", native.value)
        native.close()
        native.awaitReleased()
        retirement.complete(Unit)
        first.awaitReleased()
        assertEquals("authorized", second.await())
        second.close()
        second.awaitReleased()
        coordinator.close()
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(0, coordinator.snapshot().active)
    }
}
