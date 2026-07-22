package ml.melun.mangaview.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkEngineProtocolAsyncSurfaceTest {
    @Test
    fun initialPhaseIsLiveDetached() {
        assertEquals(
            ProtocolPhase.LIVE_DETACHED,
            NtkEngineProtocolCoordinator().phaseSnapshot()
        )
    }

    @Test
    fun attachRegistrationClosesLiveAdmissionUntilExactPublish() {
        val protocol = NtkEngineProtocolCoordinator()
        val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))

        assertEquals(ProtocolPhase.SURFACE_ATTACHING, protocol.phaseSnapshot())
        assertFalse(liveProbe(protocol))
        assertTrue(preparationProbe(protocol))
        assertTrue(protocol.completeSurfaceAttachReady(operation))
        assertEquals(ProtocolPhase.SURFACE_READY, protocol.phaseSnapshot())
        assertFalse(liveProbe(protocol))
        assertTrue(preparationProbe(protocol))
        assertTrue(protocol.publishSurface(KEY))
        assertEquals(ProtocolPhase.LIVE_ATTACHED, protocol.phaseSnapshot())
        assertTrue(liveProbe(protocol))
        assertTrue(preparationProbe(protocol))
    }

    @Test
    fun surfaceLossClosesAdmissionWithoutWaitingForActiveOperation() {
        val protocol = publishedProtocol()
        val nativeEntered = CountDownLatch(1)
        val allowNativeReturn = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<Boolean> {
                protocol.runOperation(
                    operation = "blocked-live",
                    admission = NtkProtocolAdmission.LIVE,
                    rejected = false,
                    prepareLocked = { NtkPreparedOperation(Unit) },
                    nativeCall = NtkProtocolNativeAdapter {
                        nativeEntered.countDown()
                        assertTrue(allowNativeReturn.await(5, TimeUnit.SECONDS))
                        true
                    },
                    completeLocked = { _, result -> result.getOrDefault(false) }
                )
            }
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))

            val started = System.nanoTime()
            val ticket = protocol.closeSurfaceAdmission(KEY) {}
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertNotNull(ticket)
            assertTrue("surface loss blocked for $elapsedMs ms", elapsedMs < 100L)
            assertEquals(ProtocolPhase.DETACH_CLOSING, protocol.phaseSnapshot())
            assertFalse(liveProbe(protocol))
            assertFalse(operation.isDone)
            allowNativeReturn.countDown()
            assertTrue(operation.get(5, TimeUnit.SECONDS))
        } finally {
            allowNativeReturn.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun quiescenceWaitBlocksOnlyTheLifecycleOwner() {
        val protocol = publishedProtocol()
        val nativeEntered = CountDownLatch(1)
        val allowNativeReturn = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val operation = workers.submit<Boolean> {
                protocol.runOperation(
                    operation = "quiescence-live",
                    admission = NtkProtocolAdmission.LIVE,
                    rejected = false,
                    prepareLocked = { NtkPreparedOperation(Unit) },
                    nativeCall = NtkProtocolNativeAdapter {
                        nativeEntered.countDown()
                        assertTrue(allowNativeReturn.await(5, TimeUnit.SECONDS))
                        true
                    },
                    completeLocked = { _, result -> result.getOrDefault(false) }
                )
            }
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
            val ticket = checkNotNull(protocol.closeSurfaceAdmission(KEY) {})
            val detach = workers.submit<NtkPreparedOperation<String>?> {
                protocol.awaitDetachQuiescenceAndPrepare(ticket) { "exact-snapshot" }
            }
            assertFalse(detach.isDone)
            assertEquals(ProtocolPhase.DETACH_CLOSING, protocol.phaseSnapshot())
            allowNativeReturn.countDown()
            assertTrue(operation.get(5, TimeUnit.SECONDS))
            assertEquals("exact-snapshot", detach.get(5, TimeUnit.SECONDS)?.value)
        } finally {
            allowNativeReturn.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun attachCompletionAndLossRaceConservesActiveOperation() {
        val protocol = NtkEngineProtocolCoordinator()
        val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))
        val ticket = checkNotNull(protocol.closeSurfaceAdmission(KEY) {})

        assertFalse(protocol.completeSurfaceAttachReady(operation))
        assertEquals(0, protocol.withProtocolLock { protocol.activeOperationsLocked() })
        assertEquals(
            "snapshot",
            protocol.awaitDetachQuiescenceAndPrepare(ticket) { "snapshot" }?.value
        )
        assertTrue(protocol.completeSurfaceDetach(ticket, ProtocolPhase.LIVE_DETACHED))
        assertEquals(ProtocolPhase.LIVE_DETACHED, protocol.phaseSnapshot())
    }

    @Test
    fun unclaimedCancellationDuringLossDoesNotPoisonEngine() {
        val protocol = NtkEngineProtocolCoordinator()
        val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))
        val ticket = checkNotNull(protocol.closeSurfaceAdmission(KEY) {})

        assertFalse(protocol.completeSurfaceAttachCancelled(operation))
        assertEquals(
            Unit,
            protocol.awaitDetachQuiescenceAndPrepare(ticket) { Unit }?.value
        )
        assertTrue(protocol.completeSurfaceDetach(ticket, ProtocolPhase.LIVE_DETACHED))
        assertEquals(ProtocolPhase.LIVE_DETACHED, protocol.phaseSnapshot())
    }

    @Test
    fun staleAttachKeyCannotChangeCurrentGeneration() {
        val protocol = NtkEngineProtocolCoordinator()
        val current = checkNotNull(protocol.beginSurfaceAttach(KEY))
        val stale = NtkAsyncSurfaceOperation(KEY.copy(attachGeneration = 99L))

        assertFalse(protocol.completeSurfaceAttachReady(stale))
        assertFalse(protocol.completeSurfaceAttachCancelled(stale))
        assertEquals(ProtocolPhase.SURFACE_ATTACHING, protocol.phaseSnapshot())
        assertTrue(protocol.completeSurfaceAttachReady(current))
        assertTrue(protocol.publishSurface(KEY))
    }

    @Test
    fun duplicateReadyAndDuplicatePublishAreRejected() {
        val protocol = NtkEngineProtocolCoordinator()
        val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))

        assertTrue(protocol.completeSurfaceAttachReady(operation))
        assertFalse(protocol.completeSurfaceAttachReady(operation))
        assertTrue(protocol.publishSurface(KEY))
        assertFalse(protocol.publishSurface(KEY))
        assertEquals(ProtocolPhase.LIVE_ATTACHED, protocol.phaseSnapshot())
    }

    @Test
    fun terminalAttachFailurePoisonsWithoutOpeningLiveAdmission() {
        val protocol = NtkEngineProtocolCoordinator()
        val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))

        assertTrue(protocol.completeSurfaceAttachFailed(operation))
        assertEquals(ProtocolPhase.FAILED, protocol.phaseSnapshot())
        assertFalse(liveProbe(protocol))
        assertNull(protocol.beginSurfaceAttach(KEY.copy(attachGeneration = 2L)))
    }

    @Test
    fun contextLossPublishesRetiredBlockedOnlyAfterExactSnapshot() {
        val protocol = publishedProtocol()
        val ticket = checkNotNull(protocol.closeSurfaceAdmission(KEY) {})
        val snapshot = protocol.awaitDetachQuiescenceAndPrepare(ticket) {
            listOf("authority", "release-metadata")
        }

        assertEquals(listOf("authority", "release-metadata"), snapshot?.value)
        assertTrue(protocol.completeSurfaceDetach(ticket, ProtocolPhase.RETIRED_BLOCKED))
        assertEquals(ProtocolPhase.RETIRED_BLOCKED, protocol.phaseSnapshot())
        assertFalse(protocol.externalCompletionDispatchAllowedForTest())
    }

    private fun publishedProtocol(): NtkEngineProtocolCoordinator =
        NtkEngineProtocolCoordinator().also { protocol ->
            val operation = checkNotNull(protocol.beginSurfaceAttach(KEY))
            assertTrue(protocol.completeSurfaceAttachReady(operation))
            assertTrue(protocol.publishSurface(KEY))
        }

    private fun liveProbe(protocol: NtkEngineProtocolCoordinator): Boolean =
        protocol.runOperation(
            operation = "live-probe",
            admission = NtkProtocolAdmission.LIVE,
            rejected = false,
            prepareLocked = { NtkPreparedOperation(Unit) },
            nativeCall = NtkProtocolNativeAdapter { true },
            completeLocked = { _, result -> result.getOrDefault(false) }
        )

    private fun preparationProbe(protocol: NtkEngineProtocolCoordinator): Boolean =
        protocol.runOperation(
            operation = "preparation-probe",
            admission = NtkProtocolAdmission.PREPARATION,
            rejected = false,
            prepareLocked = { NtkPreparedOperation(Unit) },
            nativeCall = NtkProtocolNativeAdapter { true },
            completeLocked = { _, result -> result.getOrDefault(false) }
        )

    private fun NtkEngineProtocolCoordinator.externalCompletionDispatchAllowedForTest(): Boolean =
        withProtocolLock { externalCompletionDispatchAllowedLocked() }

    companion object {
        private val KEY = NtkSurfaceAttachKey(
            engineGeneration = 11L,
            attachGeneration = 1L,
            surfaceEpoch = 7L
        )
    }
}
