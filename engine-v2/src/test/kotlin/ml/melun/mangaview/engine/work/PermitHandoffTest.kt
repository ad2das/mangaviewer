package ml.melun.mangaview.engine.work

import java.util.Random
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLease
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct permit handoff: a release grants the single best waiter instead of waking every
 * suspended coroutine, waiters are ordered by (live priority, enrollment sequence), a promotion
 * applies at grant time, and a waiter cancelled between the grant and its resumption never leaks
 * the claim.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermitHandoffTest {

    private fun limits(queued: Int = 4096) = WorkLimits(
        network = 1,
        bodies = 1,
        backgroundNetwork = 1,
        decodes = 1,
        queued = queued,
    )

    /** A RUNNING parent record for tests that drive [DomainPermitWaiter] without a coordinator. */
    private fun runningParent(id: String): TypedWorkRecord<String> = TypedWorkRecord(
        WorkRequest(
            key = WorkKey("principal", id, "waiter.parent", "revision", String::class.java),
            domain = WorkDomain.CONTROL,
            priority = WorkPriority.VISIBLE,
            execute = { "parent" },
        ),
        0L,
    ).apply { state = WorkRecordState.RUNNING }

    /** A CONTROL record that borrows one physical permit for the block and releases it every time. */
    private fun permitRequest(
        id: String,
        domain: WorkDomain,
        priority: WorkPriority,
        release: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) },
        onGranted: () -> Unit = {},
    ): WorkRequest<String> = WorkRequest(
        key = WorkKey("principal", id, "permit.$id", "revision", String::class.java),
        domain = WorkDomain.CONTROL,
        priority = priority,
        execute = { context ->
            context.withDomainPermit(domain) {
                onGranted()
                release.await()
                "permit:$id"
            }
        },
    )

    private suspend fun WorkCoordinator.holdPermit(
        id: String,
        domain: WorkDomain,
        gate: CompletableDeferred<Unit>,
    ): WorkLease<String> = acquire(permitRequest(id, domain, WorkPriority.FOCUS, release = gate))

    @Test
    fun focusWaiterTakesTheReleasedDecodeBeforeTheVisibleBacklogAndDrainsInOrder() = runTest {
        val coordinator = WorkCoordinator(this, limits())
        val holderGate = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.holdPermit("focus-holder", WorkDomain.DECODE, holderGate)
        }
        runCurrent()

        val grantOrder = mutableListOf<String>()
        val visible = (0 until 500).map { index ->
            coordinator.submit(
                permitRequest("visible-$index", WorkDomain.DECODE, WorkPriority.VISIBLE) {
                    grantOrder += "visible-$index"
                },
            )
        }
        val focus = coordinator.submit(
            permitRequest("focus", WorkDomain.DECODE, WorkPriority.FOCUS) { grantOrder += "focus" },
        )
        runCurrent()
        assertTrue("no waiter may run while the only decode permit is held", grantOrder.isEmpty())

        holderGate.complete(Unit)
        advanceUntilIdle()

        val expected = listOf("focus") + (0 until 500).map { "visible-$it" }
        assertEquals(expected, grantOrder)
        holder.await().close()
        visible.forEach { it.close() }
        focus.close()
        coordinator.close()
    }

    @Test
    fun aSingleReleaseResumesExactlyOneOfTwoThousandWaiters() = runTest {
        val coordinator = WorkCoordinator(this, limits())
        val holderGate = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.holdPermit("storm-holder", WorkDomain.DECODE, holderGate)
        }
        runCurrent()

        val never = CompletableDeferred<Unit>()
        val waiters = (0 until 2000).map { index ->
            coordinator.submit(permitRequest("storm-$index", WorkDomain.DECODE, WorkPriority.VISIBLE, release = never))
        }
        runCurrent()
        assertEquals(0L, coordinator.registry.grantResumeCount.get())

        holderGate.complete(Unit)
        runCurrent()
        assertEquals(
            "one released permit must resume one waiter, not the whole queue",
            1L,
            coordinator.registry.grantResumeCount.get(),
        )

        waiters.forEach { it.close() }
        runCurrent()
        holder.await().close()
        coordinator.close()
    }

    @Test
    fun aWaiterPromotedToFocusIsGrantedBeforeAnEarlierVisibleWaiter() = runTest {
        val coordinator = WorkCoordinator(this, limits())
        val holderGate = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.holdPermit("promote-holder", WorkDomain.DECODE, holderGate)
        }
        runCurrent()

        val grantOrder = mutableListOf<String>()
        val promoted = coordinator.submit(
            permitRequest("promoted", WorkDomain.DECODE, WorkPriority.INTERACTIVE) { grantOrder += "promoted" },
        )
        val earlier = coordinator.submit(
            permitRequest("earlier-visible", WorkDomain.DECODE, WorkPriority.VISIBLE) { grantOrder += "earlier-visible" },
        )
        runCurrent()
        promoted.promote(WorkPriority.FOCUS)
        runCurrent()

        holderGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("promoted", "earlier-visible"), grantOrder)
        holder.await().close()
        promoted.close()
        earlier.close()
        coordinator.close()
    }

    @Test
    fun aBackgroundUploadWaitsForTheForegroundDecodeAndIsGrantedTheMomentItReleases() = runTest {
        val coordinator = WorkCoordinator(this, limits())
        val decodeGate = CompletableDeferred<Unit>()
        val decodeStarted = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                permitRequest("upload-gate-decode", WorkDomain.DECODE, WorkPriority.FOCUS, release = decodeGate) {
                    decodeStarted.complete(Unit)
                },
            )
        }
        runCurrent()
        assertTrue(decodeStarted.isCompleted)

        val uploadGranted = CompletableDeferred<Unit>()
        val upload = coordinator.submit(
            permitRequest(
                "background-upload",
                WorkDomain.UPLOAD,
                WorkPriority.NEXT_IMAGE,
                onGranted = { uploadGranted.complete(Unit) },
            ),
        )
        runCurrent()
        assertFalse(
            "a speculative upload must not become resident while a foreground decode is in flight",
            uploadGranted.isCompleted,
        )

        decodeGate.complete(Unit)
        runCurrent()
        assertTrue("the upload must be granted by the release that ends the foreground decode",
            uploadGranted.isCompleted)

        upload.close()
        holder.await().close()
        coordinator.close()
    }

    @Test
    fun aPromotedWaiterGetsItsPermitWithoutAnyRelease() = runTest {
        val coordinator = WorkCoordinator(
            this,
            WorkLimits(network = 2, bodies = 1, backgroundNetwork = 1, decodes = 2),
        )
        try {
            val holderGate = CompletableDeferred<Unit>()
            val holderStarted = CompletableDeferred<Unit>()
            val holder = coordinator.submit(
                permitRequest("promotion-capacity", WorkDomain.NETWORK, WorkPriority.NEXT_IMAGE, release = holderGate) {
                    holderStarted.complete(Unit)
                },
            )
            runCurrent()
            assertTrue(holderStarted.isCompleted)

            val granted = CompletableDeferred<Unit>()
            val waiter = coordinator.submit(
                permitRequest("promotion-waiter", WorkDomain.NETWORK, WorkPriority.NEXT_IMAGE) {
                    granted.complete(Unit)
                },
            )
            runCurrent()
            assertFalse(
                "a second speculative network transfer must wait for the background sub-limit",
                granted.isCompleted,
            )

            waiter.promote(WorkPriority.VISIBLE)
            runCurrent()
            assertTrue(
                "promotion must re-evaluate the waiters immediately, with no permit released",
                granted.isCompleted,
            )
            waiter.close()
            holder.close()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun aDeliveryFailureBeforeBindCancelsTheAwaitingCoroutineInsteadOfHanging() = runTest {
        val registry = WorkRegistry(WorkLimits(decodes = 1))
        val waiter = DomainPermitWaiter(runningParent("delivery-failure"), WorkDomain.DECODE, 0L)
        val cause = CancellationException("Parent work is no longer running")
        waiter.cancelDelivery(cause)

        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withTimeout(1_000) {
                    suspendCancellableCoroutine<PermitClaim> { continuation ->
                        when (val result = waiter.bind(continuation)) {
                            is PermitWaitResult.Granted -> continuation.resume(result.claim) { _, _, _ -> }
                            is PermitWaitResult.Failed -> continuation.cancel(result.cause)
                            PermitWaitResult.Pending -> Unit
                        }
                    }
                }
                null
            } catch (failure: CancellationException) {
                failure
            }
        }
        val failure = awaiting.await()
        assertEquals("the stored delivery failure must cancel the wait", cause.message, failure?.message)
        assertFalse("the wait must not fall through to the withTimeout", failure is TimeoutCancellationException)
        assertEquals(0, registry.admission.usedPermits(WorkDomain.DECODE))
    }

    @Test
    fun aClaimGrantedBeforeBindIsDeliveredExactlyOnce() = runTest {
        val registry = WorkRegistry(WorkLimits(decodes = 1))
        val returned = mutableListOf<PermitClaim>()
        registry.claimReturn = { claim -> returned += claim }
        val waiter = DomainPermitWaiter(runningParent("grant-before-bind"), WorkDomain.DECODE, 0L)
        val claim = checkNotNull(registry.admission.tryAcquire(WorkDomain.DECODE, WorkPriority.FOCUS))
        assertNull("an unbound grant stores the claim instead of resuming", waiter.grant(claim))

        var deliveries = 0
        val delivered = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<PermitClaim> { continuation ->
                when (val result = waiter.bind(continuation)) {
                    is PermitWaitResult.Granted -> {
                        deliveries += 1
                        continuation.resume(result.claim) { _, _, _ -> }
                    }
                    is PermitWaitResult.Failed -> continuation.cancel(result.cause)
                    PermitWaitResult.Pending -> Unit
                }
            }
        }
        runCurrent()
        assertEquals("the stored claim must be delivered exactly once", 1, deliveries)
        assertSame(claim, delivered.await())
        assertTrue("a delivered claim must not also come back", returned.isEmpty())
        registry.mutex.withLock { registry.releaseClaimLocked(claim) }
        assertEquals(0, registry.admission.usedPermits(WorkDomain.DECODE))
    }

    @Test
    fun aClaimHandedToAnAlreadyCancelledContinuationComesBackToTheNextGrant() = runTest {
        val registry = WorkRegistry(WorkLimits(decodes = 1))
        val returned = mutableListOf<PermitClaim>()
        registry.claimReturn = { claim -> returned += claim }
        val parent = TypedWorkRecord(
            WorkRequest(
                key = WorkKey("principal", "cancelled-waiter", "cancelled.waiter", "revision", String::class.java),
                domain = WorkDomain.CONTROL,
                priority = WorkPriority.VISIBLE,
                execute = { "x" },
            ),
            0L,
        ).apply { state = WorkRecordState.RUNNING }

        var captured: CancellableContinuation<PermitClaim>? = null
        val waiterJob = launch(start = CoroutineStart.LAZY) {
            suspendCancellableCoroutine<PermitClaim> { continuation -> captured = continuation }
        }
        waiterJob.start()
        runCurrent()
        val continuation = checkNotNull(captured)
        waiterJob.cancel()
        runCurrent()

        registry.mutex.withLock {
            val waiter = registry.registerWaiterLocked(WorkDomain.DECODE, parent)
            waiter.bind(continuation)
            val claim = checkNotNull(registry.admission.tryAcquire(WorkDomain.DECODE, WorkPriority.FOCUS))
            registry.releaseClaimLocked(claim)
        }
        assertEquals("the cancelled continuation must hand the claim back", 1, returned.size)
        registry.mutex.withLock { registry.releaseClaimLocked(returned.single()) }
        assertEquals(0, registry.admission.usedPermits(WorkDomain.DECODE))
    }

    @Test
    fun cancellationRaceStressNeverLeaksAPermit() = runTest {
        val random = Random(20261009L)
        repeat(200) { iteration ->
            val coordinator = WorkCoordinator(this, limits())
            try {
                val holderGate = CompletableDeferred<Unit>()
                val holder = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.holdPermit("stress-holder-$iteration", WorkDomain.DECODE, holderGate)
                }
                runCurrent()

                val gates = (0 until 8).map { CompletableDeferred<Unit>() }
                val waiters = gates.mapIndexed { index, gate ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            coordinator.acquire(
                                permitRequest("stress-$iteration-$index", WorkDomain.DECODE, WorkPriority.VISIBLE, release = gate),
                            )
                        }
                    }
                }
                runCurrent()

                val actions = buildList<() -> Unit> {
                    waiters.forEach { waiter -> add { waiter.cancel() } }
                    add { holderGate.complete(Unit) }
                }.shuffled(random)
                for (action in actions) {
                    action()
                    runCurrent()
                }
                runCurrent()
                assertEquals("no decode permit may leak", 0, coordinator.registry.admission.usedPermits(WorkDomain.DECODE))

                val probeGate = CompletableDeferred<Unit>()
                probeGate.complete(Unit)
                val probe = withTimeout(1_000) {
                    coordinator.acquire(
                        permitRequest("stress-probe-$iteration", WorkDomain.DECODE, WorkPriority.FOCUS, release = probeGate),
                    )
                }
                probe.close()
                probe.awaitReleased()
                holder.await().close()
            } finally {
                coordinator.close()
            }
            runCurrent()
        }
    }

    @Test
    fun fiveThousandQueuedControlRecordsStartInPriorityThenSequenceOrderWithinTheBound() = runTest {
        val coordinator = WorkCoordinator(this, WorkLimits(queued = 8192))
        val started = mutableListOf<Int>()
        val subscriptions = ArrayList<WorkSubscription<String>>(5000)
        val elapsed = measureTimeMillis {
            for (index in 0 until 5000) {
                val priority = if (index % 2 == 0) WorkPriority.VISIBLE else WorkPriority.INTERACTIVE
                subscriptions += coordinator.submit(
                    WorkRequest(
                        key = WorkKey("principal", "scheduler-$index", "scheduler.control", "revision", String::class.java),
                        domain = WorkDomain.CONTROL,
                        priority = priority,
                        execute = {
                            started += index
                            "scheduler:$index"
                        },
                    ),
                )
            }
            advanceUntilIdle()
        }

        val expected = (0 until 5000 step 2).toList() + (1 until 5000 step 2).toList()
        assertEquals(expected, started)
        assertTrue("one sorted scheduler pass must stay well under 2s, took $elapsed ms", elapsed < 2000)
        subscriptions.forEach { it.close() }
        advanceUntilIdle()
        coordinator.close()
    }
}
