package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDetachedRetireLedgerTest {
    @Test
    fun oldFenceIdentitySurvivesWhileCurrentSlotStartsNewRevision() {
        val key = NtkStripTileKey(NtkEpisodeToken(8L), 0, 0)
        val old = detached(key, admission = 1L, revision = 1L, install = 11L, retire = 21L)
        val added = NtkDetachedRetireLedger.empty().add(old)
        assertTrue(added.applied)
        val current = NtkTileLifecycle.transition(
            NtkTileRecord.absent(key, old.resourceRevision),
            NtkTileLifecycleEvent.Admit(key, 2L, 3L, 1L, 2L, old.rgbaBytes)
        )
        assertTrue(current.applied)
        assertEquals(NtkTileLifecycleState.ADMITTED, current.record.state)
        assertEquals(old, added.ledger[old.retireLease])

        val removed = added.ledger.remove(NtkTileRetireIdentity(checkNotNull(old.cycle), old.retireLease))
        assertTrue(removed.applied)
        assertEquals(0, removed.ledger.size)
        assertEquals(NtkTileLifecycleState.ADMITTED, current.record.state)
    }

    @Test
    fun duplicateAndMismatchedFenceIdentitiesFailClosed() {
        val key = NtkStripTileKey(NtkEpisodeToken(8L), 0, 0)
        val old = detached(key, 1L, 1L, 11L, 21L)
        val ledger = NtkDetachedRetireLedger.empty().add(old).ledger
        assertFalse(ledger.add(old).applied)
        assertFalse(ledger.remove(NtkTileRetireIdentity(
            NtkTileCycleIdentity(key, 1L, 1L, 12L),
            21L
        )).applied)
        assertEquals(old.rgbaBytes, ledger.rgbaBytes)
    }

    private fun detached(
        key: NtkStripTileKey,
        admission: Long,
        revision: Long,
        install: Long,
        retire: Long
    ) = NtkTileRecord(
        key = key,
        state = NtkTileLifecycleState.DETACHED_FENCE_PENDING,
        admissionId = admission,
        demandEpochAtAdmission = 1L,
        admissionSurfaceEpoch = 1L,
        resourceRevision = revision,
        installLease = install,
        retireLease = retire,
        rgbaBytes = 4096L,
        sceneVersion = 2L,
        retireFenceSerial = 31L,
        shortageBeforeRetire = 4096L,
        retireSurfaceEpoch = 1L,
        retireDemandEpoch = 2L,
        retireProtectedDigest = NtkStripDigests.sha256Tokens("detached")
    )
}
