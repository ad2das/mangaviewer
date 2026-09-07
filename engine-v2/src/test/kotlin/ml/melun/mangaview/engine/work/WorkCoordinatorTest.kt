package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkCoordinatorTest {
    @Test
    fun pausedConsumerDoesNotHoldTheRegistryLockWhileAwaitingWork() = runTest {
        val workers = kotlinx.coroutines.CoroutineScope(coroutineContext +
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val coordinator = WorkCoordinator(workers)
        val subscription = coordinator.submit(request("pending", WorkDomain.NETWORK) {
            awaitCancellation()
        })
        coordinator.registry.mutex.lock()
        val consumer = async(start = CoroutineStart.UNDISPATCHED) { subscription.await() }
        coordinator.registry.mutex.unlock()
        val available = coordinator.registry.mutex.tryLock()
        if (available) coordinator.registry.mutex.unlock()
        try {
            assertTrue("A paused consumer dispatcher must not own the work registry", available)
        } finally {
            consumer.cancel()
            runCurrent()
            consumer.join()
            subscription.awaitReleased()
            coordinator.close()
        }
    }

    @Test
    fun cancelledReturnToPausedConsumerReleasesTheDeliveredResult() = runTest {
        val workers = kotlinx.coroutines.CoroutineScope(coroutineContext +
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val coordinator = WorkCoordinator(workers)
        val ready = CompletableDeferred<Unit>()
        val disposed = AtomicInteger()
        val subscription = coordinator.submit(request("delivery", WorkDomain.NETWORK,
            dispose = { disposed.incrementAndGet() }) {
            ready.await()
            "pixels"
        })
        val consumer = async(start = CoroutineStart.UNDISPATCHED) { subscription.await() }
        ready.complete(Unit)
        assertFalse(consumer.isCompleted)
        consumer.cancel()
        runCurrent()
        consumer.join()
        subscription.awaitReleased()
        assertEquals(1, disposed.get())
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test
    fun retiredEpochCannotBeRecreatedByLateOrOutOfOrderRequests() = runTest {
        val coordinator = WorkCoordinator(this)
        coordinator.invalidate("p", 4L)
        coordinator.invalidate("p", 2L)
        val executions = AtomicInteger()
        for (epoch in listOf(0L, 2L, 4L)) {
            assertFailsWith<WorkAuthEpochRetiredException> {
                coordinator.submit(request("late-$epoch", WorkDomain.NETWORK,
                    authEpoch = epoch, principal = "p") {
                    executions.incrementAndGet()
                    "obsolete"
                })
            }
        }
        assertEquals(0, executions.get())
        assertEquals(0, coordinator.snapshot().subscribers)
        val renewed = coordinator.acquire(request("renewed", WorkDomain.NETWORK,
            authEpoch = 5L, principal = "p") { "new" })
        val other = coordinator.acquire(request("other", WorkDomain.NETWORK,
            authEpoch = 0L, principal = "q") { "other" })
        assertEquals("new", renewed.value)
        assertEquals("other", other.value)
        renewed.close()
        other.close()
        renewed.awaitReleased()
        other.awaitReleased()
        coordinator.close()
    }

    @Test
    fun lateReadySubscriberReceivesTheRetainedResult() = runTest {
        val coordinator = WorkCoordinator(this)
        val executions = AtomicInteger()
        val request = request("ready", WorkDomain.NETWORK) {
            executions.incrementAndGet()
            "ready"
        }
        val first = coordinator.acquire(request)
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(request) }
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(1, executions.get())
        assertEquals("ready", second.await().value)
        first.close()
        first.awaitReleased()
        val secondLease = second.await()
        secondLease.close()
        secondLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun cancellationBeforeQueuedWorkerStartDoesNotRetainRecord() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val blocker = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(request("blocker", WorkDomain.NETWORK) {
                blockerGate.await()
                "blocker"
            })
        }
        runCurrent()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(request("queued", WorkDomain.NETWORK) {
                started.incrementAndGet()
                "queued"
            })
        }
        runCurrent()
        queued.cancel()
        runCurrent()
        assertEquals(0, coordinator.snapshot().queued)
        blockerGate.complete(Unit)
        runCurrent()
        assertEquals(0, started.get())
        val blockerLease = blocker.await()
        blockerLease.close()
        blockerLease.awaitReleased()
        assertEquals(0, coordinator.snapshot().active)
        coordinator.close()
    }

    @Test
    fun sameKeyExecutesOnceAndDisposesAfterBothLeasesRelease() = runTest {
        val coordinator = WorkCoordinator(this)
        val executions = AtomicInteger()
        val disposals = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val request = request("same", WorkDomain.NETWORK, dispose = { disposals.incrementAndGet() }) {
            executions.incrementAndGet()
            gate.await()
            "value"
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(request) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(request) }
        runCurrent()
        assertEquals(1, executions.get())
        gate.complete(Unit)
        runCurrent()
        val firstLease = first.await()
        val secondLease = second.await()
        assertEquals("value", firstLease.value)
        assertEquals("value", secondLease.value)
        firstLease.close()
        firstLease.awaitReleased()
        assertEquals(0, disposals.get())
        secondLease.close()
        secondLease.awaitReleased()
        runCurrent()
        assertEquals(1, disposals.get())
        coordinator.close()
    }

    @Test
    fun queuedPriorityIsStableAndSameKeyPromotionIsVisibleToTheExecutor() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val blocker = request("blocker", WorkDomain.NETWORK) {
            events += "blocker"
            blockerGate.await()
            "blocker"
        }
        val low = request("low", WorkDomain.NETWORK, WorkPriority.NEXT_EPISODE) {
            events += "low"
            "low"
        }
        val high = request("high", WorkDomain.NETWORK, WorkPriority.FOCUS) {
            events += "high"
            "high"
        }
        val runningPriority = CompletableDeferred<WorkPriority>()
        val promotable = request("promotable", WorkDomain.NETWORK, WorkPriority.NEXT_EPISODE) { context ->
            context.priority.collectFirst(WorkPriority.FOCUS, runningPriority)
            "promoted"
        }

        val blockerTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(blocker) }
        runCurrent()
        val lowTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(low) }
        val highTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(high) }
        runCurrent()
        blockerGate.complete(Unit)
        runCurrent()
        val blockerLease = blockerTask.await()
        blockerLease.close()
        blockerLease.awaitReleased()
        val highLease = highTask.await()
        val lowLease = lowTask.await()
        assertEquals(listOf("blocker", "high", "low"), events)
        highLease.close()
        lowLease.close()
        highLease.awaitReleased()
        lowLease.awaitReleased()

        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(promotable) }
        runCurrent()
        val promoted = request("promotable", WorkDomain.NETWORK, WorkPriority.FOCUS) { "promoted" }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(promoted) }
        runCurrent()
        assertEquals(WorkPriority.FOCUS, runningPriority.await())
        val firstLease = first.await()
        val secondLease = second.await()
        firstLease.close()
        secondLease.close()
        firstLease.awaitReleased()
        secondLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun bodyNetworkBackgroundCapsHoldAndControlParentCanAwaitChild() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 3, bodies = 1, background = 1))
        val bodyGate = CompletableDeferred<Unit>()
        val normalGate = CompletableDeferred<Unit>()
        val backgroundGate = CompletableDeferred<Unit>()
        val activeBody = AtomicInteger()
        val activeNetwork = AtomicInteger()
        val activeBackground = AtomicInteger()
        fun tracked(
            id: String,
            domain: WorkDomain,
            priority: WorkPriority,
            gate: CompletableDeferred<Unit>,
        ) = request(id, domain, priority) {
            if (domain == WorkDomain.BODY) activeBody.incrementAndGet()
            else activeNetwork.incrementAndGet()
            if (priority.background) activeBackground.incrementAndGet()
            try {
                gate.await()
                id
            } finally {
                if (domain == WorkDomain.BODY) activeBody.decrementAndGet()
                else activeNetwork.decrementAndGet()
                if (priority.background) activeBackground.decrementAndGet()
            }
        }
        val bodyTask = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(tracked("body", WorkDomain.BODY, WorkPriority.VISIBLE, bodyGate))
        }
        val normalTask = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(tracked("normal", WorkDomain.NETWORK, WorkPriority.VISIBLE, normalGate))
        }
        val backgroundTask = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(tracked("background", WorkDomain.NETWORK, WorkPriority.OFFLINE, backgroundGate))
        }
        runCurrent()
        assertEquals(1, activeBody.get())
        assertEquals(2, activeNetwork.get())
        assertEquals(1, activeBackground.get())

        val parent = request("parent", WorkDomain.CONTROL) {
            val child = coordinator.acquire(
                tracked("child", WorkDomain.BODY, WorkPriority.FOCUS, CompletableDeferred<Unit>().also {
                    it.complete(Unit)
                }),
            )
            val value = child.value
            child.close()
            child.awaitReleased()
            value
        }
        val parentTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(parent) }
        runCurrent()
        assertFalse(parentTask.isCompleted)
        bodyGate.complete(Unit)
        normalGate.complete(Unit)
        backgroundGate.complete(Unit)
        runCurrent()
        bodyTask.await().also { it.close(); it.awaitReleased() }
        normalTask.await().also { it.close(); it.awaitReleased() }
        backgroundTask.await().also { it.close(); it.awaitReleased() }
        val parentLease = parentTask.await()
        assertEquals("child", parentLease.value)
        parentLease.close()
        parentLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun speculativeDecodeLeavesOneLaneForForegroundDemand() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, decodes = 2))
        val firstGate = CompletableDeferred<Unit>()
        val foregroundGate = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        fun decode(id: String, priority: WorkPriority, gate: CompletableDeferred<Unit>) =
            request("decode-$id", WorkDomain.DECODE, priority) {
                active.incrementAndGet()
                try {
                    gate.await()
                    id
                } finally {
                    active.decrementAndGet()
                }
            }
        val speculative = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(decode("speculative", WorkPriority.NEXT_IMAGE, firstGate))
        }
        runCurrent()
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(decode("foreground", WorkPriority.FOCUS, foregroundGate))
        }
        runCurrent()
        assertEquals(2, active.get())
        firstGate.complete(Unit)
        foregroundGate.complete(Unit)
        runCurrent()
        val speculativeLease = speculative.await()
        val foregroundLease = foreground.await()
        speculativeLease.close()
        foregroundLease.close()
        speculativeLease.awaitReleased()
        foregroundLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun cancelledLastWaiterRetainsPermitUntilExecutorFinallyReturns() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val started = CompletableDeferred<Unit>()
        val finallyGate = CompletableDeferred<Unit>()
        val request = request("retiring", WorkDomain.NETWORK) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { finallyGate.await() }
            }
            "never"
        }
        val task = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(request) }
        started.await()
        task.cancel()
        runCurrent()
        assertEquals(1, coordinator.snapshot().active)
        assertFailsWith<WorkRetiringException> {
            coordinator.acquire(request)
        }
        finallyGate.complete(Unit)
        runCurrent()
        assertFailsWith(task, CancellationException::class.java)
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(request("retiring", WorkDomain.NETWORK) { "replacement" })
        }
        runCurrent()
        val lease = replacement.await()
        lease.close()
        lease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun oldEpochInvalidationWaitsForTerminationAndLeavesOtherPrincipalAlone() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1))
        val oldGate = CompletableDeferred<Unit>()
        val otherGate = CompletableDeferred<Unit>()
        val old = request("epoch", WorkDomain.NETWORK, authEpoch = 1L, principal = "p") {
            withContext(NonCancellable) { oldGate.await() }
            "old"
        }
        val other = request("other", WorkDomain.NETWORK, authEpoch = 1L, principal = "q") {
            otherGate.await()
            "other"
        }
        val oldTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(old) }
        val otherTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(other) }
        runCurrent()
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) { coordinator.invalidate("p", 1L) }
        runCurrent()
        assertFalse(invalidation.isCompleted)
        assertFailsWith<WorkRetiringException> {
            coordinator.acquire(request("epoch", WorkDomain.NETWORK, authEpoch = 2L, principal = "p") { "new" })
        }
        assertFalse(otherTask.isCompleted)
        oldGate.complete(Unit)
        runCurrent()
        invalidation.await()
        assertFailsWith(oldTask, CancellationException::class.java)
        otherGate.complete(Unit)
        val otherLease = otherTask.await()
        otherLease.close()
        otherLease.awaitReleased()
        val newLease = coordinator.acquire(
            request("epoch", WorkDomain.NETWORK, authEpoch = 2L, principal = "p") { "new" },
        )
        assertEquals("new", newLease.value)
        newLease.close()
        newLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun retryReleasesCapacityAndPromotionDoesNotResetAttemptState() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1))
        val attempts = mutableListOf<Int>()
        val tokens = mutableListOf<Long>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = request(
            "retry",
            WorkDomain.NETWORK,
            WorkPriority.NEXT_EPISODE,
            retryDelaysMillis = listOf(100L),
            retryable = { true },
        ) { context ->
            attempts += context.attempt
            tokens += context.attemptToken
            if (context.attempt == 0) error("retry")
            "retried"
        }
        val second = request("other-retry", WorkDomain.NETWORK) {
            secondStarted.complete(Unit)
            "second"
        }
        val firstTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(first) }
        runCurrent()
        val secondTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(second) }
        runCurrent()
        secondStarted.await()
        val secondLease = secondTask.await()
        secondLease.close()
        secondLease.awaitReleased()
        val promoted = request("retry", WorkDomain.NETWORK, WorkPriority.FOCUS) { "ignored" }
        val promotedTask = async(start = CoroutineStart.UNDISPATCHED) { coordinator.acquire(promoted) }
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
        val firstLease = firstTask.await()
        val promotedLease = promotedTask.await()
        assertEquals(listOf(0, 1), attempts)
        assertEquals(2, tokens.distinct().size)
        firstLease.close()
        promotedLease.close()
        firstLease.awaitReleased()
        promotedLease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun closeTwiceWaitsForDeliveredLeaseAndRealDisposer() = runTest {
        val coordinator = WorkCoordinator(this)
        val disposerGate = CompletableDeferred<Unit>()
        val disposed = AtomicInteger()
        val lease = coordinator.acquire(
            request("close", WorkDomain.NETWORK, dispose = {
                withContext(NonCancellable) { disposerGate.await() }
                disposed.incrementAndGet()
            }) { "close" },
        )
        val firstClose = async(start = CoroutineStart.UNDISPATCHED) { coordinator.close() }
        val secondClose = async(start = CoroutineStart.UNDISPATCHED) { coordinator.close() }
        runCurrent()
        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)
        lease.close()
        val release = async(start = CoroutineStart.UNDISPATCHED) { lease.awaitReleased() }
        runCurrent()
        assertFalse(release.isCompleted)
        assertEquals(0, disposed.get())
        disposerGate.complete(Unit)
        runCurrent()
        release.await()
        firstClose.await()
        secondClose.await()
        assertEquals(1, disposed.get())
    }

    @Test
    fun cancelledDeliveryAndRejectedTypeDisposeOnceAndLeaveNoRecord() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val resultGate = CompletableDeferred<Unit>()
        val disposed = AtomicInteger()
        val cancelledRequest = request("cancelled", WorkDomain.NETWORK, dispose = { disposed.incrementAndGet() }) {
            started.complete(Unit)
            withContext(NonCancellable) {
                resultGate.await()
                "late"
            }
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(cancelledRequest)
        }
        started.await()
        cancelled.cancel()
        runCurrent()
        resultGate.complete(Unit)
        runCurrent()
        assertFailsWith(cancelled, CancellationException::class.java)
        assertEquals(1, disposed.get())

        @Suppress("UNCHECKED_CAST")
        val wrongKey = WorkKey(
            "p", "wrong", "decode", "v", String::class.java as Class<Any>,
        )
        val wrongDisposed = AtomicInteger()
        val wrong = WorkRequest(
            key = wrongKey,
            domain = WorkDomain.DECODE,
            priority = WorkPriority.VISIBLE,
            execute = { 42 },
            dispose = { wrongDisposed.incrementAndGet() },
        )
        assertFailsWith<WorkResultTypeMismatchException> { coordinator.acquire(wrong) }
        runCurrent()
        assertEquals(1, wrongDisposed.get())
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test
    fun emptyCloseCompletesAndDisposerFailureReachesClose() = runTest {
        val empty = WorkCoordinator(this)
        empty.close()
        assertTrue(empty.snapshot().closed)

        val coordinator = WorkCoordinator(this)
        val lease = coordinator.acquire(
            request("dispose-failure", WorkDomain.NETWORK, dispose = { error("dispose-failure") }) {
                "value"
            },
        )
        lease.close()
        assertFailsWith<IllegalStateException> { lease.awaitReleased() }
        assertFailsWith<IllegalStateException> { coordinator.close() }
    }

    private fun limits(
        network: Int,
        bodies: Int,
        background: Int = 1,
        decodes: Int = 2,
    ) = WorkLimits(
        network = network,
        bodies = bodies,
        backgroundNetwork = background,
        decodes = decodes,
    )

    private fun request(
        resource: String,
        domain: WorkDomain,
        priority: WorkPriority = WorkPriority.VISIBLE,
        authEpoch: Long = 0L,
        principal: String = "principal",
        retryDelaysMillis: List<Long> = emptyList(),
        retryable: (Throwable) -> Boolean = { false },
        dispose: suspend (String) -> Unit = {},
        execute: suspend (ml.melun.mangaview.engine.api.WorkContext) -> String,
    ): WorkRequest<String> = WorkRequest(
        key = WorkKey(principal, resource, "test", "revision", String::class.java),
        domain = domain,
        priority = priority,
        authEpoch = authEpoch,
        retryDelaysMillis = retryDelaysMillis,
        retryable = retryable,
        execute = execute,
        dispose = dispose,
    )

    private suspend fun <T : Any> assertFailsWith(
        task: Deferred<T>,
        expected: Class<out Throwable>,
    ) {
        try {
            task.await()
            fail("Expected ${expected.name}")
        } catch (failure: Throwable) {
            assertTrue("Expected ${expected.name}, got ${failure::class.java.name}", expected.isInstance(failure))
        }
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(noinline block: suspend () -> Any?) {
        try {
            block()
            fail("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${failure::class.java.name}", failure is T)
        }
    }

    private suspend fun StateFlow<WorkPriority>.collectFirst(
        wanted: WorkPriority,
        target: CompletableDeferred<WorkPriority>,
    ) {
        if (value == wanted) {
            target.complete(wanted)
            return
        }
        this.first { priority ->
            if (priority == wanted) target.complete(priority)
            priority == wanted
        }
    }
}
