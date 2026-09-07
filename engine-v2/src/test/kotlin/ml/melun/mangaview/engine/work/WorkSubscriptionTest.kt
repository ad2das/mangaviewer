package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkSubscriptionTest {
    @Test
    fun submitReturnsWhileQueuedAndPromotionReordersBackgroundWork() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val blocker = coordinator.submit(request("blocker") {
            events += "blocker"
            blockerStarted.complete(Unit)
            blockerGate.await()
            "blocker"
        })
        blockerStarted.await()

        val promotedStarted = CompletableDeferred<Unit>()
        val promoted = coordinator.submit(request("promoted", WorkPriority.OFFLINE) {
            events += "promoted"
            promotedStarted.complete(Unit)
            "promoted"
        })
        val background = coordinator.submit(request("background", WorkPriority.OFFLINE) {
            events += "background"
            "background"
        })
        runCurrent()
        assertFalse(promotedStarted.isCompleted)

        promoted.promote(WorkPriority.FOCUS)
        blockerGate.complete(Unit)
        assertEquals("blocker", blocker.await())
        assertEquals("promoted", promoted.await())
        assertEquals("background", background.await())
        assertEquals(listOf("blocker", "promoted", "background"), events)

        closeAll(blocker, promoted, background)
        coordinator.close()
    }

    @Test
    fun promotionIsVisibleToAnAlreadyRunningExecutionContext() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val promoted = CompletableDeferred<WorkPriority>()
        val subscription = coordinator.submit(
            request("running", WorkPriority.OFFLINE) { context ->
                started.complete(Unit)
                promoted.complete(context.priority.first { it == WorkPriority.FOCUS })
                "running"
            },
        )
        started.await()

        subscription.promote(WorkPriority.FOCUS)

        assertEquals(WorkPriority.FOCUS, promoted.await())
        assertEquals("running", subscription.await())
        closeAll(subscription)
        coordinator.close()
    }

    @Test
    fun closingOneSameKeySubscriptionLeavesTheOtherRunning() = runTest {
        val coordinator = WorkCoordinator(this)
        val executions = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        fun work() = request("same-key") {
            executions.incrementAndGet()
            gate.await()
            "shared"
        }
        val first = coordinator.submit(work())
        runCurrent()
        val second = coordinator.submit(work())
        runCurrent()
        assertEquals(1, executions.get())

        first.close()
        first.awaitReleased()
        assertEquals(1, coordinator.snapshot().subscribers)
        gate.complete(Unit)

        assertEquals("shared", second.await())
        second.close()
        second.awaitReleased()
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test
    fun closingLastPreAwaitSubscriptionWaitsForNonCancellableExecutorCleanup() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val started = CompletableDeferred<Unit>()
        val finallyGate = CompletableDeferred<Unit>()
        val subscription = coordinator.submit(request("cleanup") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finallyGate.await() }
            }
            "unreachable"
        })
        started.await()

        subscription.close()
        val released = async(start = CoroutineStart.UNDISPATCHED) {
            subscription.awaitReleased()
        }
        runCurrent()
        assertFalse(released.isCompleted)
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(1, coordinator.snapshot().active)

        finallyGate.complete(Unit)
        released.await()
        assertEquals(0, coordinator.snapshot().active)
        coordinator.close()
    }

    @Test
    fun repeatedAndConcurrentAwaitShareOneValueAndClosedAwaitRejects() = runTest {
        val coordinator = WorkCoordinator(this)
        val subscription = coordinator.submit(request("repeat") { "value" })
        val first = async(start = CoroutineStart.UNDISPATCHED) { subscription.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { subscription.await() }
        runCurrent()

        assertEquals("value", first.await())
        assertEquals("value", second.await())
        assertEquals("value", subscription.await())
        assertEquals(1, coordinator.snapshot().subscribers)

        subscription.close()
        subscription.awaitReleased()
        assertCancellation { subscription.await() }
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test
    fun cancellationOfAwaitWaitsForExecutorCleanup() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val started = CompletableDeferred<Unit>()
        val finallyGate = CompletableDeferred<Unit>()
        val subscription = coordinator.submit(request("cancel-await") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finallyGate.await() }
            }
            "unreachable"
        })
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { subscription.await() }
        started.await()

        waiting.cancel()
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(1, coordinator.snapshot().active)

        finallyGate.complete(Unit)
        assertCancellation { waiting.await() }
        subscription.awaitReleased()
        assertEquals(0, coordinator.snapshot().active)
        coordinator.close()
    }

    @Test
    fun disposerFailureReachesLastAwaitReleasedAndCoordinatorClose() = runTest {
        val coordinator = WorkCoordinator(this)
        val subscription = coordinator.submit(
            request("dispose-failure", dispose = { error("dispose-failure") }) { "value" },
        )
        assertEquals("value", subscription.await())

        subscription.close()
        assertFailure<IllegalStateException> { subscription.awaitReleased() }
        assertFailure<IllegalStateException> { coordinator.close() }
    }

    @Test
    fun invalidateAndCloseSettleUndeliveredHandles() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        val blocker = coordinator.submit(request("invalidate-blocker", principal = "keep") {
            blockerStarted.complete(Unit)
            blockerGate.await()
            "blocker"
        })
        blockerStarted.await()
        val pending = coordinator.submit(request("invalidate-pending", principal = "drop") { "pending" })
        runCurrent()

        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.invalidate("drop", 0L)
        }
        pending.awaitReleased()
        invalidation.await()
        assertCancellation { pending.await() }

        blockerGate.complete(Unit)
        assertEquals("blocker", blocker.await())
        closeAll(blocker)
        coordinator.close()

        val closing = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val closeGate = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val active = closing.submit(request("close-active") {
            closeStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { closeGate.await() }
            }
            "unreachable"
        })
        closeStarted.await()
        val queued = closing.submit(request("close-queued") { "queued" })
        val closeTask = async(start = CoroutineStart.UNDISPATCHED) { closing.close() }
        queued.awaitReleased()
        assertFalse(closeTask.isCompleted)
        assertCancellation { queued.await() }

        closeGate.complete(Unit)
        closeTask.await()
        active.awaitReleased()
    }

    @Test
    fun awaitCastFailureClosesSubscriptionAndRemovesQueuedRecord() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        val blocker = coordinator.submit(request("cast-blocker") {
            blockerStarted.complete(Unit)
            blockerGate.await()
            "blocker"
        })
        blockerStarted.await()

        val badRequest = request("cast-failure") { "bad" }
        val bad = coordinator.submit(badRequest)
        runCurrent()

        val badRecord = checkNotNull(coordinator.registry.records[badRequest.key])
        checkNotNull(badRecord.subscribers.single()).ready.complete(42)
        assertFailure<ClassCastException> { bad.await() }
        bad.awaitReleased()
        assertEquals(0, coordinator.snapshot().queued)
        assertEquals(1, coordinator.snapshot().subscribers)

        blockerGate.complete(Unit)
        assertEquals("blocker", blocker.await())
        closeAll(blocker)
        coordinator.close()
    }

    private fun limits(network: Int, bodies: Int) = WorkLimits(
        network = network,
        bodies = bodies,
        backgroundNetwork = 1,
    )

    private fun request(
        resource: String,
        priority: WorkPriority = WorkPriority.VISIBLE,
        principal: String = "principal",
        dispose: suspend (String) -> Unit = {},
        execute: suspend (WorkContext) -> String,
    ): WorkRequest<String> = WorkRequest(
        key = WorkKey(principal, resource, "subscription-test", "revision", String::class.java),
        domain = WorkDomain.NETWORK,
        priority = priority,
        execute = execute,
        dispose = dispose,
    )

    private suspend fun closeAll(vararg subscriptions: WorkSubscription<String>) {
        subscriptions.forEach { it.close() }
        subscriptions.forEach { it.awaitReleased() }
    }

    private suspend fun assertCancellation(block: suspend () -> Any?) {
        try {
            block()
        } catch (_: CancellationException) {
            return
        }
        throw AssertionError("Expected CancellationException")
    }

    private suspend inline fun <reified T : Throwable> assertFailure(
        noinline block: suspend () -> Any?,
    ) {
        try {
            block()
        } catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${failure::class.java.name}", failure is T)
            return
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
