package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkResourceCycleLedgerTest {
    private val key = NtkStripTileKey(NtkEpisodeToken(7L), 0, 0)

    @Test
    fun revisionTwoRequiresFreedMemoryPressureProofWithPositiveShortage() {
        val first = NtkTileCycleIdentity(key, 1L, 1L, 11L)
        val admitted = NtkResourceCycleLedger.empty()
            .admit(1L, first.copy(installLease = 0L)).ledger
            .bindInstall(1L, first).ledger
        assertFalse(admitted.admit(1L, NtkTileCycleIdentity(key, 2L, 2L)).applied)
        val pending = admitted.admit(
            1L,
            NtkTileCycleIdentity(key, 2L, 2L),
            allowPendingPreviousFreed = true
        )
        assertTrue(pending.applied)
        assertFalse(pending.ledger.isValid)
        assertEquals(
            NtkResourceCycleAdmissionStatus.AWAITING_PREVIOUS_FREED,
            pending.ledger.publicationEligibility(
                1L,
                NtkTileCycleIdentity(key, 2L, 2L)
            ).status
        )
        val resident = residentRecord(first).copy(
            state = NtkTileLifecycleState.DETACHED_FENCE_PENDING,
            retireLease = 21L,
            shortageBeforeRetire = 4096L,
            retireSurfaceEpoch = 1L,
            retireDemandEpoch = 3L,
            retireProtectedDigest = NtkStripDigests.sha256Tokens("protected"),
            retireFenceSerial = 31L
        )
        val released = pending.ledger.releaseFor(
            1L,
            resident,
            NtkResourceCycleReleaseReason.MEMORY_PRESSURE,
            freed = true
        )
        assertTrue(released.applied)
        assertTrue(released.ledger.isValid)
        assertEquals(
            NtkResourceCycleAdmissionStatus.ELIGIBLE,
            released.ledger.publicationEligibility(
                1L,
                NtkTileCycleIdentity(key, 2L, 2L)
            ).status
        )
        assertEquals(1, released.ledger.reentryCount)
        assertEquals(1, released.ledger.memoryPressureReleaseCount)
    }

    @Test
    fun contextLossIsExplicitAndSameAuthorityCannotRestartAtRevisionOne() {
        val first = NtkTileCycleIdentity(key, 1L, 1L, 11L)
        var ledger = NtkResourceCycleLedger.empty()
            .admit(1L, first.copy(installLease = 0L)).ledger
            .bindInstall(1L, first).ledger
        val released = ledger.releaseFor(
            1L,
            residentRecord(first),
            NtkResourceCycleReleaseReason.CONTEXT_LOSS,
            freed = false
        )
        assertTrue(released.applied)
        ledger = released.ledger
        val newSurface = ledger.admit(
            2L,
            NtkTileCycleIdentity(key, 2L, 1L, admissionSurfaceEpoch = 2L)
        )
        assertFalse(newSurface.applied)
        assertTrue(newSurface.violation.orEmpty().contains("Revision one"))
        assertEquals(1, ledger.contextLossReleaseCount)
        assertEquals(0, ledger.reentryCount)
        assertTrue(ledger.isValid)
    }

    @Test
    fun duplicateReleaseAndDuplicateAdmissionFailClosed() {
        val first = NtkTileCycleIdentity(key, 1L, 1L, 11L)
        val ledger = NtkResourceCycleLedger.empty()
            .admit(1L, first.copy(installLease = 0L)).ledger
            .bindInstall(1L, first).ledger
        assertFalse(ledger.admit(1L, first.copy(installLease = 0L)).applied)
        val proof = NtkResourceCycleReleaseProof(
            cycleKey = NtkResourceCycleKey(1L, key, 1L),
            admissionId = 1L,
            installLease = 11L,
            retireLease = 21L,
            reason = NtkResourceCycleReleaseReason.MEMORY_PRESSURE,
            shortageBeforeRetire = 1L,
            freed = true
        )
        val released = ledger.release(proof)
        assertTrue(released.applied)
        assertFalse(released.ledger.release(proof).applied)
        assertFalse(ledger.bindInstall(1L, first).applied)
    }

    private fun residentRecord(cycle: NtkTileCycleIdentity) = NtkTileRecord(
        key = key,
        state = NtkTileLifecycleState.RESIDENT,
        admissionId = cycle.admissionId,
        demandEpochAtAdmission = 1L,
        admissionSurfaceEpoch = cycle.admissionSurfaceEpoch,
        resourceRevision = cycle.resourceRevision,
        installLease = cycle.installLease,
        retireLease = 0L,
        rgbaBytes = 4096L,
        sceneVersion = 1L,
        retireFenceSerial = 0L
    )
}
