package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPreparedSurfaceResidencyTrackerTest {
    @Test
    fun nativeResidentAckBeforeAndAfterBindBothBecomeDrawableEvidence() {
        val published = linkedSetOf<NtkStripRenderEngine.TileKey>()
        val tracker = NtkPreparedSurfaceResidencyTracker(published)
        val token = token(1L)
        val first = identity(token, page = 0, slot = 0, admissionId = 1L)
        val second = identity(token, page = 0, slot = 1, admissionId = 2L)

        tracker.open(token)
        assertTrue(tracker.record(token, first, ack(first)))
        assertTrue(published.isEmpty())

        assertTrue(tracker.bind(token))
        assertEquals(setOf(key(token, 0, 0)), published)

        assertTrue(tracker.record(token, second, ack(second)))
        assertEquals(setOf(key(token, 0, 0), key(token, 0, 1)), published)
    }

    @Test
    fun releaseClosesAdmissionSoLateAckCannotResurrectEvidence() {
        val published = linkedSetOf<NtkStripRenderEngine.TileKey>()
        val tracker = NtkPreparedSurfaceResidencyTracker(published)
        val token = token(7L)
        val identity = identity(token, page = 2, slot = 3, admissionId = 9L)

        tracker.open(token)
        assertTrue(tracker.record(token, identity, ack(identity)))
        assertTrue(tracker.bind(token))
        assertTrue(tracker.release(authorityToken(token)))
        assertTrue(published.isEmpty())

        assertFalse(tracker.record(token, identity, ack(identity)))
        assertTrue(published.isEmpty())
    }

    @Test
    fun mismatchedOrUnopenedAckNeverCountsAsPublished() {
        val published = linkedSetOf<NtkStripRenderEngine.TileKey>()
        val tracker = NtkPreparedSurfaceResidencyTracker(published)
        val token = token(11L)
        val expected = identity(token, page = 1, slot = 0, admissionId = 1L)
        val wrong = identity(token, page = 1, slot = 1, admissionId = 2L)

        assertFalse(tracker.record(token, expected, ack(expected)))
        tracker.open(token)
        assertFalse(tracker.record(token, expected, ack(wrong)))
        assertTrue(tracker.residentKeys(token).isEmpty())
        assertTrue(published.isEmpty())
    }

    private fun token(authority: Long) = NtkNativePreparationToken(
        engineGeneration = 1L,
        preparationGeneration = authority + 10L,
        authority = authority,
        manifestRevision = 3L,
        manifestDigest = "11".repeat(32),
        tokenNonce = authority + 100L,
        openedAtNanos = authority + 200L
    )

    private fun identity(
        token: NtkNativePreparationToken,
        page: Int,
        slot: Int,
        admissionId: Long
    ) = NtkNativeInstallIdentity(
        admission = NtkPreparationAdmissionIdentity(
            authority = token.authority,
            key = NtkStripTileKey(NtkEpisodeToken(token.authority), page, slot),
            admissionId = admissionId,
            pageArtifactDigest = "22".repeat(32)
        ),
        preparationGeneration = token.preparationGeneration,
        resourceRevision = 1L,
        installLease = admissionId + 50L
    )

    private fun ack(identity: NtkNativeInstallIdentity) = NtkPreparedTileResidentAck(
        identity = identity,
        tileProofDigest = "33".repeat(32),
        residentInventoryDigest = "44".repeat(32),
        preGeometryPrepared = true,
        resourceCompletionNanos = 10L
    )

    private fun key(token: NtkNativePreparationToken, page: Int, slot: Int) =
        NtkStripRenderEngine.TileKey(token.authority, page, slot)

    private fun authorityToken(token: NtkNativePreparationToken) = NtkNativeAuthorityToken(
        engineGeneration = 1L,
        authorityGeneration = 2L,
        authority = token.authority,
        manifestRevision = token.manifestRevision,
        manifestDigest = token.manifestDigest,
        geometryDigest = "55".repeat(32)
    )
}
