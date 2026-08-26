package ml.melun.mangaview.reader

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NtkStrictSourceAdmissionWakeTest {
    @Before
    fun setUp() = NtkStrictSourceOwnershipRegistry.clearForTest()

    @After
    fun tearDown() = NtkStrictSourceOwnershipRegistry.clearForTest()

    @Test
    fun finalForeignOperationDispatchesOneExactWake() {
        val waiting = claimOwner("/manhwa/waiting", generation = 1L, sessionId = 11L)
        val foreign = claimOwner("/manhwa/foreign", generation = 2L, sessionId = 22L)
        val lease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()

        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            callbacks.incrementAndGet()
            wake.close()
        }
        assertTrue(
            observation is
                NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked,
        )

        lease.complete(succeeded = true)
        lease.complete(succeeded = true)

        assertEquals(1, callbacks.get())
        assertTrue(
            NtkStrictSourceOwnershipRegistry.canBeginOperationNow(
                waiting.path,
                waiting.manifestDigest,
                waiting.sessionId,
            ),
        )
    }

    @Test
    fun releaseBeforeAtomicObservationReturnsReadyWithoutAListenerGap() {
        val waiting = claimOwner("/manhwa/waiting", generation = 3L, sessionId = 33L)
        val foreign = claimOwner("/manhwa/foreign", generation = 4L, sessionId = 44L)
        val lease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()

        lease.complete(succeeded = true)
        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) {
            callbacks.incrementAndGet()
        }

        assertTrue(
            observation is NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Ready,
        )
        (observation as NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Ready)
            .wake.close()
        assertEquals(0, callbacks.get())
    }

    @Test
    fun partialForeignDrainKeepsTheWakeUntilTheLastOperation() {
        val waiting = claimOwner("/manhwa/waiting", generation = 5L, sessionId = 55L)
        val foreign = claimOwner("/manhwa/foreign", generation = 6L, sessionId = 66L)
        val first = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val second = beginOperation(foreign, pageIndex = 1, laneIndex = 1)
        val callbacks = AtomicInteger()

        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            callbacks.incrementAndGet()
            wake.close()
        }
        assertTrue(
            observation is
                NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked,
        )

        first.complete(succeeded = true)
        assertEquals(0, callbacks.get())
        second.complete(succeeded = true)
        assertEquals(1, callbacks.get())
    }

    @Test
    fun closingBlockedWakePreventsAStaleCallback() {
        val waiting = claimOwner("/manhwa/waiting", generation = 7L, sessionId = 77L)
        val foreign = claimOwner("/manhwa/foreign", generation = 8L, sessionId = 88L)
        val lease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()

        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) {
            callbacks.incrementAndGet()
        } as NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked
        observation.wake.close()
        lease.complete(succeeded = true)

        assertEquals(0, callbacks.get())
    }

    @Test
    fun retiredGenerationWakeCannotTargetItsSamePathReplacement() {
        val path = "/manhwa/same-path"
        val old = claimOwner(path, generation = 9L, sessionId = 99L)
        val foreign = claimOwner("/manhwa/foreign", generation = 10L, sessionId = 100L)
        val lease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()
        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(old) {
            callbacks.incrementAndGet()
        }
        assertTrue(
            observation is
                NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked,
        )
        assertTrue(
            NtkStrictSourceOwnershipRegistry.release(
                old.path,
                old.manifestDigest,
                old.sessionId,
                old.discoveryGeneration,
            ),
        )
        val replacement = claimOwner(path, generation = 11L, sessionId = 111L)

        lease.complete(succeeded = true)

        assertEquals(0, callbacks.get())
        assertEquals(
            replacement.discoveryGeneration,
            NtkStrictSourceOwnershipRegistry.owner(path)?.discoveryGeneration,
        )
    }

    @Test
    fun oneThrowingWakeDoesNotSuppressASiblingForTheSameOwner() {
        val waiting = claimOwner("/manhwa/waiting", generation = 12L, sessionId = 122L)
        val foreign = claimOwner("/manhwa/foreign", generation = 13L, sessionId = 133L)
        val lease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()
        NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) {
            error("expected isolated listener failure")
        }
        NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            callbacks.incrementAndGet()
            wake.close()
        }

        lease.complete(succeeded = true)

        assertEquals(1, callbacks.get())
    }

    @Test
    fun grantedWakePreventsAnotherActorFromObservingTheEmptyGate() {
        val waiting = claimOwner("/manhwa/waiting", generation = 14L, sessionId = 144L)
        val firstForeign = claimOwner("/manhwa/foreign-a", generation = 15L, sessionId = 155L)
        val secondForeign = claimOwner("/manhwa/foreign-b", generation = 16L, sessionId = 166L)
        val firstLease = beginOperation(firstForeign, pageIndex = 0, laneIndex = 0)
        var granted: NtkStrictSourceOwnershipRegistry.OperationAdmissionWake? = null
        val secondCallbacks = AtomicInteger()

        NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            granted = wake
        }
        firstLease.complete(succeeded = true)
        val waitingGrant = checkNotNull(granted)
        assertTrue(waitingGrant.isGranted())

        val secondObservation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(
            secondForeign,
        ) { wake ->
            secondCallbacks.incrementAndGet()
            wake.close()
        }
        assertTrue(
            secondObservation is
                NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked,
        )

        waitingGrant.close()
        assertEquals(1, secondCallbacks.get())
    }

    @Test
    fun grantedWakeIsConsumedByTheFirstExactOperation() {
        val waiting = claimOwner("/manhwa/waiting", generation = 17L, sessionId = 177L)
        val foreign = claimOwner("/manhwa/foreign", generation = 18L, sessionId = 188L)
        val foreignLease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        var granted: NtkStrictSourceOwnershipRegistry.OperationAdmissionWake? = null
        NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            granted = wake
        }

        foreignLease.complete(succeeded = true)
        val wake = checkNotNull(granted)
        val waitingLease = beginOperation(
            waiting,
            pageIndex = 0,
            laneIndex = 0,
            admissionWake = wake,
        )

        assertTrue(wake.isTerminal())
        waitingLease.complete(succeeded = true)
    }

    @Test
    fun closingTheHeadCannotStrandTheNextReadyWaiter() {
        val waiting = claimOwner("/manhwa/waiting", generation = 19L, sessionId = 199L)
        val foreign = claimOwner("/manhwa/foreign", generation = 20L, sessionId = 200L)
        val foreignLease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val callbacks = AtomicInteger()
        val first = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) {}
            as NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked
        NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
            callbacks.incrementAndGet()
            wake.close()
        }

        first.wake.close()
        foreignLease.complete(succeeded = true)

        assertEquals(1, callbacks.get())
    }

    @Test
    fun rollingLateAuthorizationRechecksAWaiterParkedBeforeTheSeal() {
        val waiting = claimOwner("/manhwa/waiting", generation = 21L, sessionId = 211L)
        val foreign = claimOwner("/manhwa/foreign", generation = 22L, sessionId = 222L)
        val foreignLease = beginOperation(foreign, pageIndex = 0, laneIndex = 0)
        val granted = AtomicReference<NtkStrictSourceOwnershipRegistry.OperationAdmissionWake?>()
        assertTrue(
            NtkStrictSourceOwnershipRegistry.observeOperationAdmission(waiting) { wake ->
                granted.set(wake)
            } is NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Blocked,
        )
        assertTrue(
            NtkStrictSourceOwnershipRegistry.sealPrimaryAdmissions(
                waiting.path,
                waiting.manifestDigest,
                waiting.sessionId,
            ),
        )

        foreignLease.complete(succeeded = true)
        assertEquals(null, granted.get())
        assertTrue(
            NtkStrictSourceOwnershipRegistry.setGeometrySealed(
                waiting.path,
                waiting.manifestDigest,
                waiting.sessionId,
            ),
        )
        assertTrue(
            NtkStrictSourceOwnershipRegistry.authorizeRollingLateAdmissions(
                waiting.path,
                waiting.manifestDigest,
                waiting.sessionId,
                setOf(3),
            ),
        )

        val wake = checkNotNull(granted.get())
        assertTrue(wake.isGranted())
        beginOperation(waiting, pageIndex = 3, laneIndex = 0, admissionWake = wake)
            .complete(succeeded = true)
    }

    @Test
    fun closingAnUnusedGrantWakesTheBlockingCompatibilityAbi() {
        val actorOwner = claimOwner("/manhwa/actor", generation = 23L, sessionId = 233L)
        val compatibilityOwner = claimOwner(
            "/manhwa/compatibility",
            generation = 24L,
            sessionId = 244L,
        )
        val observation = NtkStrictSourceOwnershipRegistry.observeOperationAdmission(actorOwner) {}
            as NtkStrictSourceOwnershipRegistry.OperationAdmissionObservation.Ready
        val lease = AtomicReference<NtkStrictSourceOwnershipRegistry.OperationLease?>()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            runCatching {
                beginOperation(compatibilityOwner, pageIndex = 0, laneIndex = 0)
            }.onSuccess(lease::set).onFailure(failure::set)
        }
        thread.start()
        val waitDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (thread.state != Thread.State.WAITING &&
            System.nanoTime() < waitDeadlineNanos
        ) {
            Thread.yield()
        }
        assertEquals(Thread.State.WAITING, thread.state)

        observation.wake.close()
        thread.join(TimeUnit.SECONDS.toMillis(1L))

        assertEquals(null, failure.get())
        val admitted = checkNotNull(lease.get())
        admitted.complete(succeeded = true)
        assertTrue(!thread.isAlive)
    }

    private fun claimOwner(
        path: String,
        generation: Long,
        sessionId: Long,
    ): NtkStrictSourceOwnershipRegistry.Owner {
        val token = NtkPromotionToken(
            episodePath = path,
            discoveryGeneration = generation,
            sessionId = sessionId,
            planBindingDigest = NtkStripDigests.sha256Tokens("plan", generation.toString()),
            exactManifestDigest = NtkStripDigests.sha256Tokens(
                "manifest",
                generation.toString(),
            ),
            exactProofDigest = NtkStripDigests.sha256Tokens("proof", generation.toString()),
            nonce = generation * 1_000L + sessionId,
        )
        NtkStrictSourceOwnershipRegistry.beginDiscoveryFence(path, generation)
        return NtkStrictSourceOwnershipRegistry.claimExact(
            NtkStrictSourceOwnershipRegistry.reserveExact(token),
            sessionId,
        )
    }

    private fun beginOperation(
        owner: NtkStrictSourceOwnershipRegistry.Owner,
        pageIndex: Int,
        laneIndex: Int,
        admissionWake: NtkStrictSourceOwnershipRegistry.OperationAdmissionWake? = null,
    ): NtkStrictSourceOwnershipRegistry.OperationLease {
        val tag = NtkStrictSourceCallTag.strict(
            owner.sessionId,
            owner.manifestDigest,
            NtkStrictSourceOwnershipRegistry.nextOperationId(),
            laneIndex,
            pageIndex,
            attemptOrdinal = 1,
        )
        return NtkStrictSourceOwnershipRegistry.beginOperationWithAdmissionWake(
            owner.path,
            tag,
            routeKeyHash = NtkStripDigests.sha256Tokens(
                "route",
                owner.sessionId.toString(),
                pageIndex.toString(),
            ),
            callFactoryId = "test",
            attempt = 1,
            admissionWake = admissionWake,
        )
    }
}
