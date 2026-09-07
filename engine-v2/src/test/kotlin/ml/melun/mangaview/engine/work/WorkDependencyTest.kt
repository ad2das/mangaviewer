package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkDependencyTest {
    @Test
    fun resultRetainsChildUntilParentDisposalCompletes() = runTest {
        val coordinator = WorkCoordinator(this)
        val events = mutableListOf<String>()
        val disposing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val child = work("file", WorkDomain.STORAGE, dispose = { events += "child" }) { "file" }
        val parent = coordinator.submit(work("parent", dispose = {
            assertTrue(events.isEmpty())
            disposing.complete(Unit)
            release.await()
            events += "parent"
        }) { it.dependency(child) })
        assertEquals("file", parent.await())
        assertEquals(2, coordinator.snapshot().retainedResults)
        parent.close()
        disposing.await()
        assertTrue(events.isEmpty())
        release.complete(Unit)
        parent.awaitReleased()
        assertEquals(listOf("parent", "child"), events)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test
    fun cancellingOneParentPreservesSharedChildForTheOther() = runTest {
        val coordinator = WorkCoordinator(this)
        var executions = 0
        var disposed = 0
        val gate = CompletableDeferred<Unit>()
        val child = work("shared", WorkDomain.NETWORK, dispose = { disposed++ }) {
            executions++
            gate.await()
            "shared"
        }
        val first = coordinator.submit(work("first") { it.dependency(child) })
        val second = coordinator.submit(work("second") { it.dependency(child) })
        runCurrent()
        first.awaitReleased()
        assertEquals(1, executions)
        assertEquals(0, disposed)
        gate.complete(Unit)
        assertEquals("shared", second.await())
        second.awaitReleased()
        assertEquals(1, disposed)
        coordinator.close()
    }

    @Test
    fun childPriorityTracksPromotionThroughMultipleParents() = runTest {
        val coordinator = WorkCoordinator(this, WorkLimits(network = 1, bodies = 1, backgroundNetwork = 1))
        val gate = CompletableDeferred<Unit>()
        val blocker = coordinator.submit(work("blocker", WorkDomain.NETWORK) { gate.await(); "blocker" })
        runCurrent()
        val events = mutableListOf<String>()
        val child = work("child", WorkDomain.NETWORK) { events += "child"; it.priority.value.name }
        val middle = work("middle") { it.dependency(child) }
        val parent = coordinator.submit(work("parent") { it.dependency(middle) })
        val competitor = coordinator.submit(work("competitor", WorkDomain.NETWORK) {
            events += "competitor"
            "competitor"
        })
        runCurrent()
        parent.promote(WorkPriority.FOCUS)
        gate.complete(Unit)
        assertEquals("FOCUS", parent.await())
        assertEquals("competitor", competitor.await())
        assertEquals(listOf("child", "competitor"), events)
        parent.awaitReleased()
        competitor.awaitReleased()
        blocker.awaitReleased()
        coordinator.close()
    }

    @Test
    fun directAndIndirectCyclesFailWithoutDeadlock() = runTest {
        val coordinator = WorkCoordinator(this)
        lateinit var direct: WorkRequest<String>
        direct = work("direct") { it.dependency(direct) }
        assertFails<IllegalArgumentException> { coordinator.acquire(direct) }
        lateinit var first: WorkRequest<String>
        val second = work("second") { it.dependency(first) }
        first = work("first") { it.dependency(second) }
        assertFails<IllegalArgumentException> { coordinator.acquire(first) }
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test
    fun physicalWorkCannotWaitForAnotherPermit() = runTest {
        val coordinator = WorkCoordinator(this, WorkLimits(network = 1, bodies = 1, backgroundNetwork = 1))
        assertFails<IllegalStateException> {
            coordinator.acquire(work("parent", WorkDomain.NETWORK) {
                it.dependency(work("child", WorkDomain.NETWORK) { "child" })
            })
        }
        assertEquals(0, coordinator.snapshot().queued)
        coordinator.close()
    }

    @Test
    fun failedAttemptReleasesDependenciesBeforeRetry() = runTest {
        val coordinator = WorkCoordinator(this)
        val events = mutableListOf<String>()
        val child = work("child", WorkDomain.STORAGE, dispose = { events += "dispose" }) {
            events += "execute"
            "file"
        }
        val request = WorkRequest(
            key("retry"), WorkDomain.CONTROL, WorkPriority.FOCUS,
            retryDelaysMillis = listOf(0), retryable = { true },
            execute = {
                val file = it.dependency(child)
                if (it.attempt == 0) error("retry")
                file
            },
        )
        val lease = coordinator.acquire(request)
        assertEquals(listOf("execute", "dispose", "execute"), events)
        lease.awaitReleased()
        assertEquals(listOf("execute", "dispose", "execute", "dispose"), events)
        coordinator.close()
    }

    @Test
    fun finishedContextRejectsNewOwnership() = runTest {
        val coordinator = WorkCoordinator(this)
        lateinit var captured: WorkContext
        val lease = coordinator.acquire(work("parent") { captured = it; "parent" })
        assertFails<IllegalStateException> { captured.dependency(work("late") { "late" }) }
        assertEquals(1, coordinator.snapshot().subscribers)
        lease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun closeAwaitsRunningChildCleanup() = runTest {
        val coordinator = WorkCoordinator(this)
        var cleaned = false
        val child = work("child", WorkDomain.NETWORK) {
            try { awaitCancellation() } finally { cleaned = true }
        }
        coordinator.submit(work("parent") { it.dependency(child) })
        runCurrent()
        coordinator.close()
        assertTrue(cleaned)
        assertEquals(0, coordinator.snapshot().active)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test
    fun authBoundaryCannotBeCrossedByDependency() = runTest {
        val coordinator = WorkCoordinator(this)
        val foreign = WorkRequest(key("foreign").copy(principal = "other"), WorkDomain.NETWORK,
            WorkPriority.FOCUS, execute = { "foreign" })
        assertFails<IllegalArgumentException> {
            coordinator.acquire(work("parent") { it.dependency(foreign) })
        }
        coordinator.close()
    }

    @Test
    fun invalidationWaitsForParentLeaseAndThenReleasesEntireGraph() = runTest {
        val coordinator = WorkCoordinator(this)
        var disposed = false
        val lease = coordinator.acquire(work("parent") {
            it.dependency(work("child", WorkDomain.STORAGE, dispose = { disposed = true }) { "file" })
        })
        val invalidation = async { coordinator.invalidate("test", 0) }
        runCurrent()
        assertFalse(invalidation.isCompleted)
        assertFalse(disposed)
        lease.awaitReleased()
        invalidation.await()
        assertTrue(disposed)
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test
    fun parentDisposalFailureStillReleasesEveryChild() = runTest {
        val coordinator = WorkCoordinator(this)
        val disposed = mutableSetOf<String>()
        val lease = coordinator.acquire(work("parent", dispose = { error("parent cleanup") }) {
            it.dependency(work("one", WorkDomain.STORAGE, dispose = { disposed += "one" }) { "one" })
            it.dependency(work("two", WorkDomain.STORAGE, dispose = { disposed += "two" }) { "two" })
        })
        assertFails<IllegalStateException> { lease.awaitReleased() }
        assertEquals(setOf("one", "two"), disposed)
        assertEquals(0, coordinator.snapshot().subscribers)
        assertFails<IllegalStateException> { coordinator.close() }
    }

    @Test
    fun returningWithAnUnfinishedDependencyFailsAndReleasesChild() = runTest {
        val coordinator = WorkCoordinator(this)
        val childStarted = CompletableDeferred<Unit>()
        val childStopped = CompletableDeferred<Unit>()
        val escaped = CompletableDeferred<kotlinx.coroutines.Deferred<Result<String>>>()
        val parent = work("parent") { context ->
            escaped.complete(backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    context.dependency(work("child", WorkDomain.NETWORK) {
                        childStarted.complete(Unit)
                        try { awaitCancellation() } finally { childStopped.complete(Unit) }
                    })
                }
            })
            childStarted.await()
            "invalid"
        }
        assertFails<IllegalStateException> { coordinator.acquire(parent) }
        assertTrue(escaped.await().await().isFailure)
        assertTrue(childStopped.isCompleted)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test
    fun scopedDependencyIsReleasedBeforeParentResultIsRetained() = runTest {
        val coordinator = WorkCoordinator(this)
        var decodedDisposed = false
        val lease = coordinator.acquire(work("upload") { parent ->
            parent.useDependency(work("pixels", WorkDomain.DECODE, dispose = { decodedDisposed = true }) {
                "pixels"
            }) { pixels ->
                assertFalse(decodedDisposed)
                "copied-$pixels"
            }
        })
        assertEquals("copied-pixels", lease.value)
        assertTrue(decodedDisposed)
        assertEquals(1, coordinator.snapshot().retainedResults)
        lease.awaitReleased()
        coordinator.close()
    }

    @Test
    fun scopedCleanupFailureDisposesTheIndependentResultBeforeThrowing() = runTest {
        val coordinator = WorkCoordinator(this)
        var abandoned = false
        val parent = work("upload") {
            it.useDependency(work("pixels", WorkDomain.DECODE, dispose = { error("pixel cleanup") }) { "pixels" },
                disposeAbandoned = { result -> assertEquals("texture", result); abandoned = true }) { "texture" }
        }
        assertFails<IllegalStateException> { coordinator.acquire(parent) }
        assertTrue(abandoned)
        assertEquals(0, coordinator.snapshot().retainedResults)
        assertFails<IllegalStateException> { coordinator.close() }
    }

    private fun key(name: String) = WorkKey("test", name, "read", "1", String::class.java)

    private fun work(
        name: String,
        domain: WorkDomain = WorkDomain.CONTROL,
        dispose: suspend (String) -> Unit = {},
        execute: suspend (WorkContext) -> String,
    ) = WorkRequest(key(name), domain, WorkPriority.OFFLINE, execute = execute, dispose = dispose)

    private suspend inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
