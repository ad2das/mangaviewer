package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService

class NtkExecutionBootstrapTest {

    @Test
    fun finiteTopologyIsExclusiveToTheDirectWifiAdjacentWebtoonGrant() {
        val policy = NtkDirectWifiAdjacentExecutionTopology

        assertTrue(policy.shouldDeferBootstrap(
            "/webtoon/work/next", true, true, true, false,
        ))
        assertFalse(policy.shouldDeferBootstrap(
            "/webtoon/work/next", true, true, false, false,
        ))
        assertFalse(policy.shouldDeferBootstrap(
            "/webtoon/work/next", true, true, true, true,
        ))
        assertFalse(policy.shouldDeferBootstrap(
            "/webtoon/work/next", true, false, true, false,
        ))
        assertFalse(policy.shouldDeferBootstrap(
            "/manhwa/work/next", true, true, true, false,
        ))
        assertFalse(policy.shouldDeferBootstrap(
            "/webtoon/work/next", false, true, true, false,
        ))
        assertEquals(12, policy.physicalLaneCount(true, 61))
        assertEquals(61, policy.physicalLaneCount(false, 61))
        assertEquals(4, policy.routeLaneCount(true, 8))
        assertEquals(8, policy.routeLaneCount(false, 8))
    }

    @Test
    fun strictSourceBootstrapPrestartsTheCompleteBoundedPhysicalRing() {
        val bootstrap = NtkStrictSourceExecutionBootstrap()
        awaitThreads(1 + NTK_STRICT_PHYSICAL_WORKER_LANES + 8) {
            bootstrap.startedThreadCount()
        }

        assertFalse(bootstrap.isAdopted())
        val engines = bootstrap.adopt()
        assertTrue(bootstrap.isAdopted())
        assertRejected { bootstrap.adopt() }

        engines.routePreparationLanes.forEach(ExecutorService::shutdownNow)
        engines.physicalLanes.forEach(ExecutorService::shutdownNow)
        engines.actor.shutdownNow()
    }

    @Test
    fun adoptedStrictBootstrapCanAbortAFailedSessionConstructor() {
        val bootstrap = NtkStrictSourceExecutionBootstrap()
        val engines = bootstrap.adopt()

        bootstrap.abortConstructionFailure()

        assertTrue(engines.actor.isShutdown)
        assertTrue(engines.physicalLanes.all(ExecutorService::isShutdown))
        assertTrue(engines.routePreparationLanes.all(ExecutorService::isShutdown))
    }

    @Test
    fun deferredStrictBootstrapStartsOnlyActorWhenEveryBodyIsAlreadyOwned() {
        val bootstrap = NtkStrictSourceExecutionBootstrap(deferWorkerLanes = true)
        awaitThreads(1) { bootstrap.startedThreadCount() }

        val engines = bootstrap.adopt(
            requiredPhysicalLanes = 0,
            requiredRoutePreparationLanes = 0,
        )

        assertEquals(1, bootstrap.startedThreadCount())
        assertTrue(engines.physicalLanes.isEmpty())
        assertTrue(engines.routePreparationLanes.isEmpty())
        engines.actor.shutdownNow()
    }

    @Test
    fun deferredStrictBootstrapSizesWorkersFromTheMissingBodyCount() {
        val bootstrap = NtkStrictSourceExecutionBootstrap(deferWorkerLanes = true)

        val engines = bootstrap.adopt(
            requiredPhysicalLanes = 3,
            requiredRoutePreparationLanes = 2,
        )

        awaitThreads(3) { bootstrap.startedThreadCount() }
        assertEquals(3, bootstrap.startedThreadCount())
        assertEquals(3, engines.physicalLanes.size)
        assertEquals(2, engines.routePreparationLanes.size)
        engines.routePreparationLanes.forEach(ExecutorService::shutdownNow)
        engines.physicalLanes.forEach(ExecutorService::shutdownNow)
        engines.actor.shutdownNow()
    }

    @Test
    fun fullSceneBootstrapPrestartsActorLeaseAndThreeDecodeLanesWithZeroTasks() {
        val expectedThreads =
            1 + NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY + 3
        val bootstrap = NtkFullSceneExecutionBootstrap()
        awaitThreads(expectedThreads) { bootstrap.startedThreadCount() }

        assertEquals(0L, bootstrap.submittedTaskCount())
        assertFalse(bootstrap.isAdopted())
        val engines = bootstrap.adopt()
        assertTrue(bootstrap.isAdopted())
        assertRejected { bootstrap.adopt() }

        engines.decodeLanes.forEach(ExecutorService::shutdownNow)
        engines.bodyLease.shutdownNow()
        engines.actor.shutdownNow()
    }

    private fun awaitThreads(expected: Int, count: () -> Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (count() < expected && System.nanoTime() < deadline) Thread.yield()
        assertEquals(expected, count())
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
