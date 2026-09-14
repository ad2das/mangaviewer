package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.FrameIdentity
import ml.melun.mangaview.engine.api.FrameWorkObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWorkProvenanceLedgerTest {
    private val emitted = mutableListOf<String>()
    private val ledger = FrameWorkProvenanceLedger(enabled = { true }, emit = { emitted += it })

    private fun identity(token: Long, inputRevision: Long) =
        FrameIdentity(7L, 2L, 3L, token, inputRevision, 9L)

    private fun arm(vsyncId: Long, frameTimeNanos: Long, sequence: Long, baseInput: Long, baseMovement: Long) {
        ledger.armInputFrame(vsyncId, 0L, frameTimeNanos, sequence, 7L, baseInput, baseMovement)
    }

    private fun ticket(inputRevision: Long, movementRevision: Long): FrameWorkTicket =
        requireNotNull(ledger.ticketForOffer(7L, inputRevision, movementRevision))

    private fun atMarkers(): List<String> = emitted.filter { it.startsWith("engine_frame_origin_at:") }

    private fun supersededMarkers(): List<String> =
        emitted.filter { it.startsWith("engine_frame_origin_superseded:") }

    @Test fun threeDifferentRevisionOffersResolveToTheirOwnOriginsRegardlessOfResolutionOrder() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val first = ticket(5L, 9L)
        arm(51L, 200L, 2L, 5L, 9L)
        ledger.bindInputFrame(7L, 6L, 10L)
        val second = ticket(6L, 10L)
        arm(61L, 300L, 3L, 6L, 10L)
        ledger.bindInputFrame(7L, 7L, 11L)
        val third = ticket(7L, 11L)

        third.resolve(identity(13L, 7L), 2L)
        first.resolve(identity(11L, 5L), 2L)
        second.resolve(identity(12L, 6L), 2L)

        assertEquals(listOf(
            "engine_frame_origin_at:1:12c:3d:7:b",
            "engine_frame_origin_at:1:64:29:5:9",
            "engine_frame_origin_at:1:c8:33:6:a",
        ), atMarkers())
        assertEquals(3, emitted.count { it.startsWith("engine_frame_origin:") })
        assertEquals(2, supersededMarkers().size)
    }

    @Test fun sameRevisionTicketsUseTheirOwnSavedFreshTriggerNotAGloballyNewerOne() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val inputTicket = ticket(5L, 9L)
        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 150L)
        val imageTicket = ticket(5L, 9L)

        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 250L)
        inputTicket.resolve(identity(11L, 5L), 2L)
        imageTicket.resolve(identity(12L, 5L), 2L)

        val lateTicket = ticket(5L, 9L)
        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 350L)
        lateTicket.resolve(identity(13L, 5L), 2L)
        val latestTicket = ticket(5L, 9L)
        latestTicket.resolve(identity(14L, 5L), 2L)

        assertEquals(listOf(
            "engine_frame_origin_at:1:64:29:5:9",
            "engine_frame_origin_at:2:96:0:5:9",
            "engine_frame_origin_at:2:fa:0:5:9",
            "engine_frame_origin_at:2:15e:0:5:9",
        ), atMarkers())
    }

    @Test fun imageTicketCreatedAfterTheInputWasSubmittedResolvesToItsOwnFreshTrigger() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val inputTicket = ticket(5L, 9L)
        val replacement = ticket(5L, 9L)
        inputTicket.resolve(identity(11L, 5L), 2L)
        replacement.resolve(identity(12L, 5L), 2L)

        ledger.noteWorkTrigger(FrameWorkObserver.SNAPSHOT_UPDATE, 400L)
        val afterSubmit = ticket(5L, 9L)
        afterSubmit.resolve(identity(13L, 5L), 2L)

        assertEquals(FrameOriginKind.MISSING, emittedOriginKind(index = 1))
        assertEquals(FrameOriginKind.SNAPSHOT_UPDATE, emittedOriginKind(index = 2))
    }

    private fun emittedOriginKind(index: Int): Int =
        atMarkers()[index].removePrefix("engine_frame_origin_at:").substringBefore(':').toInt(16)

    @Test fun clampedInputClearsItsOwnArmEvenWithAnUnrelatedOutstandingOrigin() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        arm(51L, 200L, 2L, 5L, 9L)
        ledger.bindInputFrame(7L, 6L, 9L)
        ledger.finishInputFrame(deferred = false)

        ledger.bindInputFrame(7L, 6L, 10L)
        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 260L)
        ticket(6L, 10L).resolve(identity(12L, 6L), 2L)

        assertEquals(listOf("engine_frame_origin_at:2:104:0:6:a"), atMarkers())
    }

    @Test fun deferredInputKeepsTheArmForItsReplayBinding() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 8L)
        ledger.finishInputFrame(deferred = true)
        ledger.bindInputFrame(7L, 5L, 9L)
        ticket(5L, 9L).resolve(identity(11L, 5L), 2L)

        assertEquals(listOf("engine_frame_origin_at:1:64:29:5:9"), atMarkers())
    }

    @Test fun firstSubmittedSameRevisionTicketOwnsTheInputAndLaterOneFallsBackExplicitly() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val first = ticket(5L, 9L)
        val second = ticket(5L, 9L)
        second.resolve(identity(12L, 5L), 2L)
        first.resolve(identity(11L, 5L), 2L)

        assertEquals(listOf(
            "engine_frame_origin_at:1:64:29:5:9",
            "engine_frame_origin_at:0:0:0:5:9",
        ), atMarkers())
    }

    @Test fun supersededMarkersAreBoundedAndTheReprecededTicketStillResolves() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val stale = ticket(5L, 9L)
        arm(51L, 200L, 2L, 5L, 9L)
        ledger.bindInputFrame(7L, 6L, 10L)
        arm(61L, 300L, 3L, 6L, 10L)
        ledger.bindInputFrame(7L, 7L, 11L)
        val current = ticket(7L, 11L)

        stale.resolve(identity(11L, 5L), 2L)
        current.resolve(identity(13L, 7L), 2L)

        assertEquals(listOf(
            "engine_frame_origin_superseded:7:5:9:6:a",
            "engine_frame_origin_superseded:7:6:a:7:b",
        ), supersededMarkers())
        assertEquals(listOf(
            "engine_frame_origin_at:1:64:29:5:9",
            "engine_frame_origin_at:1:12c:3d:7:b",
        ), atMarkers())
    }

    @Test fun recoveryAndClearLeaveNoStaleArmOrFreshOrigin() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        ledger.noteRecovery(900L)
        ticket(5L, 9L).resolve(identity(11L, 5L), 2L)
        assertEquals(listOf("engine_frame_origin_at:4:384:0:5:9"), atMarkers())

        ledger.clear()
        emitted.clear()
        ticket(5L, 9L).resolve(identity(12L, 5L), 2L)
        assertEquals(listOf("engine_frame_origin_at:0:0:0:5:9"), atMarkers())
    }

    @Test fun disabledLedgerCreatesNoTicketsStoresNothingAndEmitsNoNames() {
        val silent = mutableListOf<String>()
        val disabled = FrameWorkProvenanceLedger(enabled = { false }, emit = { silent += it })
        disabled.armInputFrame(41L, 0L, 100L, 1L, 7L, 4L, 8L)
        disabled.bindInputFrame(7L, 5L, 9L)
        disabled.finishInputFrame(deferred = false)
        disabled.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 300L)
        disabled.noteRecovery(900L)

        assertTrue(!disabled.isTracking)
        assertNull(disabled.ticketForOffer(7L, 5L, 9L))
        assertTrue(silent.isEmpty())
    }

    @Test fun emittedMarkersKeepTheFrozenSchemaAndTheSectionLimit() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        ticket(5L, 9L).resolve(identity(11L, 5L), 2L)

        assertEquals("engine_frame_origin:7:2:2:3:b", emitted[0])
        assertEquals("engine_frame_origin_at:1:64:29:5:9", emitted[1])
        assertEquals("engine_frame_origin_superseded:7:5:9:6:a",
            supersededTraceName(7L, 5L, 9L, 6L, 10L))

        val identity = FrameIdentity(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE)
        val origin = FrameOrigin(FrameOriginKind.SNAPSHOT_UPDATE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val names = listOf(
            frameOriginTraceName(identity, Long.MAX_VALUE),
            frameOriginAtTraceName(origin),
            supersededTraceName(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        names.forEach { assertTrue("trace section name exceeds 127 chars (${it.length}): $it", it.length <= 127) }
    }

    @Test fun newerInputResolvesItsOwnOriginWhileOlderInFlightTicketKeepsItsSnapshot() {
        arm(41L, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        val stale = ticket(5L, 9L)
        arm(51L, 200L, 2L, 5L, 9L)
        ledger.bindInputFrame(7L, 6L, 10L)

        ledger.noteWorkTrigger(FrameWorkObserver.SNAPSHOT_UPDATE, 500L)
        ticket(6L, 10L).resolve(identity(12L, 6L), 2L)
        stale.resolve(identity(11L, 5L), 2L)

        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 700L)
        val imageTicket = ticket(6L, 10L)
        ledger.noteWorkTrigger(FrameWorkObserver.WORK_RESULT, 900L)
        imageTicket.resolve(identity(13L, 6L), 2L)

        assertEquals(listOf(
            "engine_frame_origin_at:1:c8:33:6:a",
            "engine_frame_origin_at:1:64:29:5:9",
            "engine_frame_origin_at:2:2bc:0:6:a",
        ), atMarkers())
    }

    @Test fun dispatchTimeInputResolvesWithTheInputDispatchKindAndNoVsyncId() {
        arm(NO_VSYNC_ID, 100L, 1L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        ticket(5L, 9L).resolve(identity(11L, 5L), 2L)

        assertEquals(FrameOriginKind.INPUT_DISPATCH, emittedOriginKind(index = 0))
        assertEquals(listOf("engine_frame_origin_at:5:64:8000000000000000:5:9"), atMarkers())
    }

    @Test fun legacyChoreographerFallbackKeepsItsOriginKind() {
        ledger.armInputFrame(-1L, 100L, 100L, 1L, 7L, 4L, 8L)
        ledger.bindInputFrame(7L, 5L, 9L)
        ticket(5L, 9L).resolve(identity(11L, 5L), 2L)

        assertEquals(FrameOriginKind.CHOREOGRAPHER, emittedOriginKind(index = 0))
    }

    @Test fun disabledLedgerAlsoSilencesDispatchOrigins() {
        val silent = mutableListOf<String>()
        val disabled = FrameWorkProvenanceLedger(enabled = { false }, emit = { silent += it })
        disabled.armInputFrame(NO_VSYNC_ID, 0L, 100L, 1L, 7L, 4L, 8L)
        disabled.bindInputFrame(7L, 5L, 9L)

        assertNull(disabled.ticketForOffer(7L, 5L, 9L))
        assertTrue(silent.isEmpty())
    }
}
