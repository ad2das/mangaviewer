package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.runtime.SessionDemand
import ml.melun.mangaview.engine.runtime.SessionWorkSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opt-in orphan path: a page record whose body is already streaming when its last subscriber
 * leaves keeps running to completion, a later demand of the same key reuses it without a refetch,
 * and the retention is bounded by the orphan cap and by foreground admission.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkOrphanRetentionTest {

    @Test
    fun streamingBodySurvivesTheLastSubscriberAndARedemandReusesIt() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val page = PageFixture("reuse")
        val first = coordinator.submit(page.request())
        val firstAwait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { first.await() } }
        page.bodyStarted.await()
        assertEquals(1, page.bodyRequests.get())

        first.close()
        first.awaitReleased()
        runCurrent()
        val retained = coordinator.registry.records[page.key]
        assertTrue("the streaming record must be kept alive", retained != null && !retained.cancelRequested)
        assertEquals(WorkRecordState.RUNNING, retained!!.state)

        val second = coordinator.submitAfterRetirement(page.request())
        val secondValue = async(start = CoroutineStart.UNDISPATCHED) { second.await() }
        runCurrent()
        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("page:reuse", secondValue.await())
        assertEquals("the body transfer must not be repeated", 1, page.bodyRequests.get())
        assertEquals("bytes", page.storage["reuse"])

        second.close()
        second.awaitReleased()
        firstAwait.join()
        runCurrent()
        assertEquals(1, page.disposed.get())
        assertTrue(coordinator.registry.records.isEmpty())
        coordinator.close()
    }

    @Test
    fun lastSubscriberLeavingWhileTheLookupRunsStillCancels() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val page = PageFixture("lookup")
        val lookupGate = CompletableDeferred<Unit>()
        page.lookupGate = lookupGate
        val first = coordinator.submit(page.request())
        val firstAwait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { first.await() } }
        runCurrent()
        assertFalse(firstAwait.isCompleted)
        assertEquals(0, page.bodyRequests.get())

        first.close()
        first.awaitReleased()
        runCurrent()
        assertEquals("not streaming: the record must not be retained", 0, coordinator.snapshot().active)
        assertEquals(0, coordinator.snapshot().queued)

        lookupGate.complete(Unit)
        val second = coordinator.submit(page.request())
        val secondValue = async(start = CoroutineStart.UNDISPATCHED) { second.await() }
        runCurrent()
        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("page:lookup", secondValue.await())
        assertEquals("a fresh record runs the body once", 1, page.bodyRequests.get())
        second.close()
        second.awaitReleased()
        firstAwait.join()
        coordinator.close()
    }

    @Test
    fun lastSubscriberLeavingWhileTheBodyWaitsForAPermitStillCancels() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1, background = 1))
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        val blocker = coordinator.submit(WorkRequest(
            key = WorkKey("principal", "blocker", "content.body", "revision", String::class.java),
            domain = WorkDomain.BODY,
            priority = WorkPriority.VISIBLE,
            execute = {
                blockerStarted.complete(Unit)
                blockerGate.await()
                "blocker"
            },
        ))
        blockerStarted.await()

        val page = PageFixture("queued")
        val first = coordinator.submit(page.request())
        val firstAwait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { first.await() } }
        runCurrent()
        assertEquals("the body child must be queued behind the blocker", 1, coordinator.snapshot().queued)
        assertEquals(0, page.bodyRequests.get())

        first.close()
        first.awaitReleased()
        runCurrent()
        assertTrue("a body still queued for its permit must not be retained",
            coordinator.registry.records[page.key] == null)
        assertEquals(0, coordinator.snapshot().queued)

        blockerGate.complete(Unit)
        runCurrent()
        blocker.close()
        blocker.awaitReleased()
        runCurrent()

        val second = coordinator.submit(page.request())
        val secondValue = async(start = CoroutineStart.UNDISPATCHED) { second.await() }
        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("page:queued", secondValue.await())
        assertEquals(1, page.bodyRequests.get())
        second.close()
        second.awaitReleased()
        firstAwait.join()
        coordinator.close()
    }

    @Test
    fun anOrphanCompletingWithoutSubscribersPublishesAndDisposesSilently() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val page = PageFixture("silent")
        val first = coordinator.submit(page.request())
        page.bodyStarted.await()
        first.close()
        first.awaitReleased()
        runCurrent()
        val retained = coordinator.registry.records[page.key]
        assertTrue(retained != null && !retained.cancelRequested)

        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("the normal publish must still run", "bytes", page.storage["silent"])
        assertEquals("the subscriber-less result must be disposed", 1, page.disposed.get())
        assertEquals(0, coordinator.snapshot().active)
        assertEquals(0, coordinator.snapshot().retainedResults)
        assertEquals(0, coordinator.registry.records.size)
        coordinator.close()
    }

    @Test
    fun anOrphanFailingWithoutSubscribersIsSilentAndDoesNotRetry() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val page = PageFixture("failed", failBody = true,
            retryDelaysMillis = listOf(100L), retryable = { true })
        val first = coordinator.submit(page.request())
        page.bodyStarted.await()
        first.close()
        first.awaitReleased()
        runCurrent()
        val retained = coordinator.registry.records[page.key]
        assertTrue(retained != null && !retained.cancelRequested)

        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("the orphan must not retry without a subscriber", 1, page.pageAttempts.get())
        assertEquals(0, coordinator.snapshot().active)
        assertEquals(0, coordinator.registry.records.size)
        assertEquals("a failed attempt has nothing to dispose", 0, page.disposed.get())
        coordinator.close()
    }

    @Test
    fun orphanCapCancelsTheExtraDeparture() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 4, bodies = 3, background = 2))
        val pages = listOf("cap-a", "cap-b", "cap-c").map { PageFixture(it) }
        val subscriptions = pages.map { coordinator.submit(it.request()) }
        pages.forEach { it.bodyStarted.await() }

        subscriptions.forEach { it.close() }
        runCurrent()
        val pageRecords = pages.map { coordinator.registry.records[it.key] }
        assertEquals("only backgroundNetwork orphans may live at once",
            2, pageRecords.count { it != null && it.orphaned && !it.cancelRequested })
        assertEquals(1, pageRecords.count { it == null })

        pages.forEach { it.bodyGate.complete(Unit) }
        runCurrent()
        assertEquals(0, coordinator.snapshot().active)
        assertEquals(0, coordinator.registry.records.size)
        assertEquals(2, pages.count { it.storage.isNotEmpty() })
        assertEquals(2, pages.sumOf { it.disposed.get() })
        coordinator.close()
    }

    @Test
    fun foregroundDemandEvictsTheOrphanHoldingTheBodyPermitAndLeavesNoPermitLeak() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 1, bodies = 1, background = 1))
        val orphan = PageFixture("orphan")
        val orphanSub = coordinator.submit(orphan.request())
        val orphanAwait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { orphanSub.await() } }
        orphan.bodyStarted.await()
        orphanSub.close()
        orphanSub.awaitReleased()
        runCurrent()
        val retained = coordinator.registry.records[orphan.key]
        assertTrue(retained != null && retained.orphaned && !retained.cancelRequested)

        val focus = PageFixture("focus", priority = WorkPriority.FOCUS)
        val focusSub = coordinator.submit(focus.request())
        val focusValue = async(start = CoroutineStart.UNDISPATCHED) { focusSub.await() }
        runCurrent()
        focus.bodyStarted.await()
        focus.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("page:focus", focusValue.await())
        focusSub.close()
        focusSub.awaitReleased()
        runCurrent()
        assertEquals("the evicted orphan must be gone", 0, coordinator.snapshot().active)
        assertTrue(coordinator.registry.records.isEmpty())
        assertTrue("the orphan await ended cancelled", orphanAwait.await().isFailure)

        val probe = coordinator.acquire(WorkRequest(
            key = WorkKey("principal", "probe", "content.probe", "revision", String::class.java),
            domain = WorkDomain.NETWORK,
            priority = WorkPriority.VISIBLE,
            execute = { "probe" },
        ))
        assertEquals("the sole network permit must be free again", "probe", probe.value)
        probe.close()
        probe.awaitReleased()
        coordinator.close()
    }

    @Test
    fun aNonOptedStreamingBodyStillCancelsAndRefetches() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val page = PageFixture("non-opted", finishWhenOrphaned = false)
        val first = coordinator.submit(page.request())
        page.bodyStarted.await()
        first.close()
        first.awaitReleased()
        runCurrent()
        assertEquals("default requests must cancel as today", 0, coordinator.snapshot().active)

        val second = coordinator.submit(page.request())
        val secondValue = async(start = CoroutineStart.UNDISPATCHED) { second.await() }
        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals("page:non-opted", secondValue.await())
        assertEquals("a fresh record must refetch the body", 2, page.bodyRequests.get())
        second.close()
        second.awaitReleased()
        coordinator.close()
    }

    @Test
    fun aSessionRedemandReusesTheOrphanWithoutRefetchOrReportedFailure() = runTest {
        val coordinator = WorkCoordinator(this, limits(network = 2, bodies = 1, background = 1))
        val failures = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val work = SessionWorkSet(this, coordinator, { key, _ -> failures += key.resource })
        val page = PageFixture("session")
        val demand = SessionDemand(page.request(), accept = { accepted += it })

        work.reconcile(listOf(demand))
        page.bodyStarted.await()
        work.reconcile(emptyList())
        runCurrent()
        val retained = coordinator.registry.records[page.key]
        assertTrue("retiring the entry must not cancel the streaming record",
            retained != null && !retained.cancelRequested)

        work.reconcile(listOf(demand))
        page.bodyGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("page:session"), accepted)
        assertEquals("the session re-demand must not refetch the body", 1, page.bodyRequests.get())
        assertTrue("no failure may be reported for the orphan", failures.isEmpty())

        work.close()
        coordinator.close()
    }

    private fun limits(
        network: Int,
        bodies: Int,
        background: Int,
    ) = WorkLimits(
        network = network,
        bodies = bodies,
        backgroundNetwork = background,
        decodes = 2,
    )

    /** A miniature page graph: lookup (STORAGE_READ) -> body (BODY) -> publish (STORAGE_PUBLISH). */
    private class PageFixture(
        private val resource: String,
        private val priority: WorkPriority = WorkPriority.VISIBLE,
        private val finishWhenOrphaned: Boolean = true,
        private val failBody: Boolean = false,
        private val retryDelaysMillis: List<Long> = emptyList(),
        private val retryable: (Throwable) -> Boolean = { false },
    ) {
        val bodyRequests = AtomicInteger()
        val pageAttempts = AtomicInteger()
        val bodyStarted = CompletableDeferred<Unit>()
        val bodyGate = CompletableDeferred<Unit>()
        val disposed = AtomicInteger()
        val storage = mutableMapOf<String, String>()
        val key = WorkKey("principal", resource, "content.page", "revision", String::class.java)
        var lookupGate: CompletableDeferred<Unit>? = null

        fun request(): WorkRequest<String> = WorkRequest(
            key = key,
            domain = WorkDomain.CONTROL,
            priority = priority,
            retryDelaysMillis = retryDelaysMillis,
            retryable = retryable,
            dispose = { disposed.incrementAndGet() },
            finishWhenOrphaned = finishWhenOrphaned,
            execute = { context ->
                pageAttempts.incrementAndGet()
                context.dependency(child("$resource.lookup", "content.lookup", WorkDomain.STORAGE_READ) {
                    lookupGate?.await()
                    "lookup"
                })
                val bytes = context.dependency(child("$resource.body", "content.body", WorkDomain.BODY) {
                    bodyRequests.incrementAndGet()
                    bodyStarted.complete(Unit)
                    bodyGate.await()
                    if (failBody) error("body failed")
                    "bytes"
                })
                context.dependency(child("$resource.publish", "content.publish", WorkDomain.STORAGE_PUBLISH) {
                    storage[resource] = bytes
                    "pinned"
                })
                "page:$resource"
            },
        )

        private fun child(
            id: String,
            operation: String,
            domain: WorkDomain,
            execute: suspend (WorkContext) -> String,
        ): WorkRequest<String> = WorkRequest(
            key = WorkKey("principal", id, operation, "revision", String::class.java),
            domain = domain,
            priority = priority,
            execute = execute,
        )
    }
}
