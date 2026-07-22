package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkSurfaceHandoffStateMachineTest {
    @Test
    fun created_doesNotPublishBeforeExactAttachReady() {
        val fixture = Fixture()
        fixture.create()
        assertNull(fixture.machine.published)
        assertNull(fixture.machine.engineState)
    }

    @Test
    fun changedBeforeSubmissionIsIncludedInFirstAttach() {
        val fixture = Fixture()
        fixture.create()
        assertEquals(
            NtkSurfaceHandoffStateMachine.ChangeAction.DriveAttach,
            fixture.machine.changed(1080, 2200)
        )
        val submission = checkNotNull(fixture.machine.takeAttachSubmission())
        assertEquals(1080, submission.width)
        assertEquals(2200, submission.height)
        assertEquals(2L, submission.geometryRevision)
    }

    @Test
    fun duplicateCreatedGeometryDoesNotAdvanceRevisionOrQueueResize() {
        val fixture = Fixture()
        fixture.create()
        assertEquals(
            NtkSurfaceHandoffStateMachine.ChangeAction.None,
            fixture.machine.changed(1080, 2000)
        )
        val submission = checkNotNull(fixture.machine.takeAttachSubmission())
        assertEquals(1L, submission.geometryRevision)
    }

    @Test
    fun duplicateGeometryWhileAttachingDoesNotReattachNativeSurface() {
        val fixture = Fixture()
        fixture.submit()
        assertEquals(
            NtkSurfaceHandoffStateMachine.ChangeAction.None,
            fixture.machine.changed(1080, 2000)
        )
        val state = fixture.machine.engineState as
            NtkSurfaceHandoffStateMachine.EngineState.Attaching
        assertEquals(1L, state.requestedGeometryRevision)
    }

    @Test
    fun changedBeforeNativeClaimUpdatesSameGeneration() {
        val fixture = Fixture()
        val key = fixture.submit()
        val action = fixture.machine.changed(1080, 2100)
        assertTrue(action is NtkSurfaceHandoffStateMachine.ChangeAction.UpdateAttach)
        assertEquals(key, (action as NtkSurfaceHandoffStateMachine.ChangeAction.UpdateAttach).key)
        assertEquals(2L, action.geometryRevision)
    }

    @Test
    fun changedAfterClaimRequiresResizeAckBeforePublish() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.changed(1080, 2100)
        val action = fixture.machine.ready(key, 1L, 1080, 2000)
        assertTrue(action is NtkSurfaceHandoffStateMachine.ReadyAction.ResizeBarrier)
        assertNull(fixture.machine.published)
    }

    @Test
    fun destroyedViewOwnedLeaseReleasesExactlyOnce() {
        val fixture = Fixture()
        fixture.create()
        assertEquals(
            NtkSurfaceHandoffStateMachine.DestroyAction.LeaseOnly,
            fixture.machine.destroyed()
        )
        fixture.machine.destroyed()
        assertEquals(1, fixture.released)
    }

    @Test
    fun destroyedUnclaimedAttachCancelsWithoutEngineFailure() {
        val fixture = Fixture()
        val key = fixture.submit()
        assertTrue(
            fixture.machine.destroyed() is
                NtkSurfaceHandoffStateMachine.DestroyAction.Revoke
        )
        assertFalse(fixture.machine.cancelledBeforeClaim(key))
        assertNull(fixture.machine.terminalFailure)
        assertTrue(fixture.machine.detachCompleted(key))
    }

    @Test
    fun destroyedClaimedAttachCompletesThenDetachesWithoutAvailable() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.destroyed()
        assertTrue(
            fixture.machine.ready(key, 1L, 1080, 2000) is
                NtkSurfaceHandoffStateMachine.ReadyAction.Stale
        )
        assertNull(fixture.machine.published)
        assertTrue(fixture.machine.detachCompleted(key))
    }

    @Test
    fun staleReadyFromOldEpochCannotPublish() {
        val fixture = Fixture()
        val old = fixture.submit()
        fixture.machine.destroyed()
        fixture.machine.detachCompleted(old)
        fixture.create(epoch = 2L)
        fixture.machine.takeAttachSubmission()
        val current = KEY.copy(attachGeneration = 2L, surfaceEpoch = 2L)
        assertTrue(fixture.machine.attachSubmitted(current))
        assertTrue(
            fixture.machine.ready(old, 1L, 1080, 2000) is
                NtkSurfaceHandoffStateMachine.ReadyAction.Stale
        )
    }

    @Test
    fun duplicateReadyCannotPublishTwice() {
        val fixture = Fixture()
        val key = fixture.submit()
        val ready = fixture.machine.ready(key, 1L, 1080, 2000)
        assertTrue(ready is NtkSurfaceHandoffStateMachine.ReadyAction.Publish)
        val identity = identity(key)
        assertTrue(fixture.machine.published(identity))
        assertFalse(fixture.machine.published(identity))
    }

    @Test
    fun newSurfaceWaitsWhilePreviousDetachIsRunning() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.destroyed()
        fixture.create(epoch = 2L)
        assertNull(fixture.machine.takeAttachSubmission())
        assertTrue(fixture.machine.detachCompleted(key))
        assertTrue(fixture.machine.takeAttachSubmission() != null)
    }

    @Test
    fun preservedDetachReusesSameEngineOnlyAfterTerminalResult() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.destroyed()
        fixture.create(epoch = 2L)
        assertNull(fixture.machine.takeAttachSubmission())
        fixture.machine.detachCompleted(key)
        val next = checkNotNull(fixture.machine.takeAttachSubmission())
        assertEquals(1L, fixture.machine.engineGeneration)
        assertEquals(2L, next.surfaceEpoch)
    }

    @Test
    fun contextLossCandidateTransfersOnlyToSuccessor() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.destroyed()
        fixture.create(epoch = 2L)
        fixture.machine.detachCompleted(key)
        assertTrue(fixture.machine.installSuccessor(2L))
        val next = checkNotNull(fixture.machine.takeAttachSubmission())
        assertEquals(2L, fixture.machine.engineGeneration)
        assertEquals(2L, next.surfaceEpoch)
    }

    @Test
    fun successorCreationFailureReleasesWaitingLease() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.destroyed()
        fixture.create(epoch = 2L)
        fixture.machine.detachCompleted(key)
        fixture.machine.fail(NtkSurfaceAttachFailure.LIFECYCLE_TASK_FAILED)
        assertEquals(1, fixture.released)
        assertNull(fixture.machine.takeAttachSubmission())
    }

    @Test
    fun listenerReplayUsesPublishedIdentityNotBoolean() {
        val fixture = Fixture()
        val key = fixture.submit()
        fixture.machine.ready(key, 1L, 1080, 2000)
        assertNull(fixture.machine.published)
        assertTrue(fixture.machine.published(identity(key)))
        assertEquals(identity(key), fixture.machine.published)
    }

    @Test
    fun terminalAttachFailureBlocksAllLaterCandidates() {
        val fixture = Fixture()
        fixture.create()
        fixture.machine.fail(NtkSurfaceAttachFailure.NATIVE_ATTACH_FAILED)
        assertFalse(
            fixture.machine.created(
                2L,
                11_111_111L,
                1080,
                2000,
                Lease(2)
            )
        )
        assertNull(fixture.machine.takeAttachSubmission())
        assertEquals(2, fixture.released)
    }

    private class Fixture {
        var released = 0
        val machine = NtkSurfaceHandoffStateMachine<Lease>(1L) {
            if (!it.closed) {
                it.closed = true
                released++
            }
        }

        fun create(epoch: Long = 1L) {
            assertTrue(
                machine.created(
                    epoch,
                    11_111_111L,
                    1080,
                    2000,
                    Lease(epoch.toInt())
                )
            )
        }

        fun submit(): NtkSurfaceAttachKey {
            create()
            checkNotNull(machine.takeAttachSubmission())
            assertTrue(machine.attachSubmitted(KEY))
            return KEY
        }
    }

    private data class Lease(val id: Int, var closed: Boolean = false)

    companion object {
        private val KEY = NtkSurfaceAttachKey(1L, 1L, 1L)

        private fun identity(key: NtkSurfaceAttachKey) = NtkPublishedSurfaceIdentity(
            key.engineGeneration,
            key.attachGeneration,
            key.surfaceEpoch,
            1L,
            1080,
            2000
        )
    }
}
