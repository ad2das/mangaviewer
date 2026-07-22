package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Random

class NtkSurfaceDemandProtocolTest {
    @Test
    fun planAndShellRemainTheOnlyEngineWarmPermit() {
        repeat(10_000) { seed ->
            val reducer = NtkSurfaceDemandProtocol()
            val arrivals = mutableListOf<(NtkSurfaceDemandProtocol) -> Boolean>(
                { it.reservePlan(1, "p1", 10).startDetachedWarm },
                { it.onShellFrameCommitted(20).startDetachedWarm },
                { it.reservePlan(1, "p1", 10).startDetachedWarm },
                { it.onShellFrameCommitted(21).startDetachedWarm }
            )
            Collections.shuffle(arrivals, Random(seed.toLong()))
            var starts = 0
            arrivals.forEach { arrival -> if (arrival(reducer)) starts++ }
            assertEquals(1, starts)
            assertTrue(reducer.snapshot().detachedWarmStarted)
            assertFalse(
                NtkSurfaceDemandProtocol()
                    .reservePlan(1, "p1", 10)
                    .startDetachedWarm
            )
            assertFalse(
                NtkSurfaceDemandProtocol()
                    .onShellFrameCommitted(20)
                    .startDetachedWarm
            )
        }
    }

    @Test
    fun planBeforeShellCommitDoesNotWarmOrInstall() {
        val reducer = NtkSurfaceDemandProtocol()
        assertFalse(reducer.reservePlan(1, "p1", 10).startDetachedWarm)
        assertFalse(reducer.snapshot().surfaceViewConstructed)
    }

    @Test
    fun shellCommitBeforePlanDoesNotWarmOrInstall() {
        val reducer = NtkSurfaceDemandProtocol()
        assertFalse(reducer.onShellFrameCommitted(5).startDetachedWarm)
        assertFalse(reducer.snapshot().detachedWarmStarted)
    }

    @Test
    fun planAndShellCommitStartExactlyOneDetachedWarm() {
        val reducer = reservedAndCommitted()
        assertTrue(reducer.snapshot().detachedWarmStarted)
        assertFalse(reducer.onShellFrameCommitted(6).startDetachedWarm)
        assertFalse(reducer.reservePlan(1, "p1", 10).startDetachedWarm)
    }

    @Test
    fun warmReadyInstallsExactlyOneSurfaceView() {
        val reducer = warmBound()
        assertTrue(reducer.onDetachedWarmReady(1, 11).installSurfaceView)
        assertFalse(reducer.onDetachedWarmReady(1, 11).installSurfaceView)
    }

    @Test
    fun manifestBeforeSurfacePermitsSourceClaimButNotPresentationJoin() {
        val reducer = installed()
        assertFalse(reducer.onManifestOwned(1, "p1").promoteManifestSurfaceJoin)
        assertTrue(reducer.markSourceClaimed(1))
        assertTrue(reducer.onSurfacePublished(1, 11, 9).promoteManifestSurfaceJoin)
    }

    @Test
    fun surfaceBeforeManifestPublishesNoAuthority() {
        val reducer = installed()
        assertFalse(reducer.onSurfacePublished(1, 11, 9).promoteManifestSurfaceJoin)
        assertTrue(reducer.onManifestOwned(1, "p1").promoteManifestSurfaceJoin)
    }

    @Test
    fun surfaceAndManifestJoinPromotesExactlyOnce() {
        val reducer = installed()
        reducer.onSurfacePublished(1, 11, 9)
        assertTrue(reducer.onManifestOwned(1, "p1").promoteManifestSurfaceJoin)
        assertFalse(reducer.onManifestOwned(1, "p1").promoteManifestSurfaceJoin)
    }

    @Test
    fun replacedPlanRejectsStaleWarmCompletion() {
        val reducer = reservedAndCommitted()
        assertTrue(reducer.bindDetachedEngine(1, 11))
        reducer.reservePlan(2, "p2", 20)
        assertFalse(reducer.onDetachedWarmReady(1, 11).installSurfaceView)
    }

    @Test
    fun replacedPlanRejectsStaleAttachReady() {
        val reducer = installed()
        reducer.reservePlan(2, "p2", 20)
        assertFalse(reducer.onSurfacePublished(1, 11, 9).promoteManifestSurfaceJoin)
    }

    @Test
    fun cancelBeforeHolderCreateAcquiresNoLease() {
        val reducer = warmBound()
        reducer.onDetachedWarmReady(1, 11)
        assertFalse(reducer.revokeCurrentSurface(1).revokeSurface)
    }

    @Test
    fun cancelAfterLeaseBeforeNativeClaimClosesExactLease() {
        val reducer = installed()
        assertTrue(reducer.revokeCurrentSurface(1).revokeSurface)
    }

    @Test
    fun cancelAfterNativeClaimUsesExistingDetachBarrier() {
        val reducer = promoted()
        assertTrue(reducer.markSourceClaimed(1))
        reducer.revokeCurrentSurface(1)
        assertFalse(reducer.snapshot().sourceClaimed)
    }

    @Test
    fun cachedInstantPlanStillWaitsForShellFrameCommit() {
        val reducer = NtkSurfaceDemandProtocol()
        reducer.reservePlan(1, "p1", 10)
        reducer.onManifestOwned(1, "p1")
        assertEquals(0, reducer.snapshot().publishedSurfaceEpoch)
        assertTrue(reducer.onShellFrameCommitted(20).startDetachedWarm)
    }

    @Test
    fun publishedReusableTargetMayServeSuccessorAuthority() {
        val reducer = promoted()
        reducer.normalExit()
        assertTrue(reducer.reservePlan(2, "p2", 20).startDetachedWarm)
        assertTrue(reducer.bindDetachedEngine(2, 11))
        assertTrue(reducer.onDetachedWarmReady(2, 11).installSurfaceView)
        assertTrue(reducer.onSurfaceViewConstructed(2))
        assertTrue(reducer.onSurfaceViewInstalled(2))
        reducer.onSurfacePublished(2, 11, 10)
        assertTrue(reducer.onManifestOwned(2, "p2").promoteManifestSurfaceJoin)
        assertTrue(reducer.markSourceClaimed(2))
    }

    @Test
    fun normalExitReleasesAuthorityButKeepsReusableTarget() {
        val reducer = promoted()
        assertTrue(reducer.markSourceClaimed(1))
        reducer.normalExit()
        assertTrue(reducer.snapshot().surfaceViewInstalled)
        assertFalse(reducer.snapshot().sourceClaimed)
    }

    @Test
    fun activityDestroyClosesTargetAndDetachedEngine() {
        val reducer = installed()
        val transition = reducer.destroy()
        assertTrue(transition.closeEngine)
        assertTrue(reducer.snapshot().destroyed)
        assertFalse(reducer.snapshot().surfaceViewInstalled)
    }

    private fun reservedAndCommitted(): NtkSurfaceDemandProtocol =
        NtkSurfaceDemandProtocol().also {
            it.onShellFrameCommitted(5)
            assertTrue(it.reservePlan(1, "p1", 10).startDetachedWarm)
        }

    private fun warmBound(): NtkSurfaceDemandProtocol = reservedAndCommitted().also {
        assertTrue(it.bindDetachedEngine(1, 11))
    }

    private fun installed(): NtkSurfaceDemandProtocol = warmBound().also {
        assertTrue(it.onDetachedWarmReady(1, 11).installSurfaceView)
        assertTrue(it.onSurfaceViewConstructed(1))
        assertTrue(it.onSurfaceViewInstalled(1))
    }

    private fun promoted(): NtkSurfaceDemandProtocol = installed().also {
        it.onSurfacePublished(1, 11, 9)
        assertTrue(it.onManifestOwned(1, "p1").promoteManifestSurfaceJoin)
    }
}
