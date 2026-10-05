package ml.melun.mangaview.engine.runtime

import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineTileWork
import ml.melun.mangaview.engine.work.WorkCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retain merge and the work-set reconcile run only when their inputs change: the visible tile
 * band set, the resident pixel set, or the access plans. Segments that merely move the viewport
 * inside the same bands must reuse both, while a band crossing must run each exactly once more.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineRenderWorkGatingTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)

    @Test
    fun sameBandSegmentsReuseTheRetainAndReconcilePass() = runTest {
        val fixture = Fixture(this)
        fixture.update(250, 1)
        runCurrent()
        fixture.update(260, 2)
        fixture.update(270, 3)
        fixture.update(280, 4)
        val retain = fixture.tracer.count("engine_retain")
        val reconcile = fixture.tracer.count("engine_reconcile")
        val scenes = fixture.scenes.size
        repeat(40) { index -> fixture.update(250 + index % 40, 5L + index) }
        assertEquals("same-band segments must not re-run the retain merge", retain, fixture.tracer.count("engine_retain"))
        assertEquals("same-band segments must not re-run the reconcile", reconcile, fixture.tracer.count("engine_reconcile"))
        assertEquals("placements still move every segment", scenes + 40, fixture.scenes.size)
        fixture.close()
    }

    @Test
    fun crossingABandBoundaryRunsExactlyOneMorePass() = runTest {
        val fixture = Fixture(this)
        fixture.update(250, 1)
        runCurrent()
        fixture.update(260, 2)
        fixture.update(270, 3)
        val retain = fixture.tracer.count("engine_retain")
        val reconcile = fixture.tracer.count("engine_reconcile")
        fixture.update(450, 4)
        assertEquals("a band crossing must merge once", retain + 1, fixture.tracer.count("engine_retain"))
        assertEquals("a band crossing must reconcile once", reconcile + 1, fixture.tracer.count("engine_reconcile"))
        fixture.close()
    }

    /** Reported JVM benchmark (never asserted): per-segment cost of same-band fling segments. */
    @Test
    fun sameBandSegmentCost() = runTest {
        val fixture = Fixture(this, pageHeight = 8000, budgetBytes = 2_000_000)
        fixture.update(250, 1)
        runCurrent()
        var revision = 2L
        for (band in 2..20) {
            fixture.update(band * 200 + 50, revision++)
            runCurrent()
        }
        repeat(500) { index -> fixture.update(4050 + index % 40, revision++) }
        val allocatedBefore = threadAllocatedBytes()
        val startedAt = System.nanoTime()
        repeat(2_000) { index -> fixture.update(4050 + index % 40, revision++) }
        val elapsed = System.nanoTime() - startedAt
        val allocated = threadAllocatedBytes() - allocatedBefore
        println(
            "RenderWorkGating[same-band] segments=2000 ns/segment=%.1f bytes/segment=%.1f".format(
                elapsed.toDouble() / 2_000, allocated.toDouble() / 2_000,
            ),
        )
        assertTrue("elapsed time must be observable", elapsed > 0L)
        fixture.close()
    }

    private inner class Fixture(
        scope: TestScope,
        pageHeight: Int = 1000,
        budgetBytes: Long = 400_000,
    ) {
        val tracer = RecordingTracer()
        val coordinator = WorkCoordinator(scope)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        var pageRequestBuilds = 0
        private val dimensions = PageDimensions(100, pageHeight)
        private val plans = emptyMap<EpisodeId, EpisodeAccessPlan>()
        private val pages = mapOf(id to PageContentIdentity(id, "1", "1".repeat(64), dimensions, 1))
        private val tileWork = EngineTileWork(EngineImageDecoder { _, tile ->
            object : EnginePixels {
                override val tile = tile
                override val byteCount = tile.byteCount
                override fun close() = Unit
            }
        }, StandardTestDispatcher(scope.testScheduler), uploader)
        val runtime = EngineRenderRuntime(
            scope, coordinator, EngineTilePlanner(budgetBytes, 202, 2), tileWork, uploader,
            { _, priority ->
                pageRequestBuilds++
                WorkRequest(
                    WorkKey("test", "page", "read", "1", StoredPage::class.java), WorkDomain.STORAGE, priority,
                    execute = {
                        StoredPage(id, "1", File("original.png"), 1, "1".repeat(64), dimensions, "image/png")
                    },
                    dispose = {},
                )
            },
            { scene ->
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            },
            { uploader.scene(emptySet()) },
            { _, failure -> error("unexpected failure: $failure") },
            waitForCompleteViewport = false,
            reportSceneFailure = { error("unexpected scene failure: $it") },
            tracer = tracer,
        )

        fun update(anchorPx: Int, inputRevision: Long) {
            val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
            val state = EngineSessionSnapshot(
                1, 1, EngineSessionPhase.ACTIVE, EngineViewport(100, 100),
                SourceAnchor(id, anchorPx.toLong() * q), inputRevision, inputRevision, 0,
                listOf(
                    VisiblePageRegion(
                        id, dimensions, anchorPx.toLong() * q, (anchorPx + 100).toLong() * q, 0, 102_400,
                    ),
                ),
                emptySet(), emptySet(), true,
            )
            runtime.update(
                EngineRuntimeSnapshot(state, plans, pages),
            )
        }

        suspend fun close() {
            runtime.close()
            coordinator.close()
            assertTrue(uploader.live.isEmpty())
        }
    }

    private class RecordingTracer : EngineWorkTracer {
        private val sections = mutableListOf<String>()

        override fun <T> section(name: String, work: () -> T): T {
            sections += name
            return work()
        }

        fun count(name: String): Int = sections.count { it == name }
    }

    private class Uploader : EngineTextureUploader {
        override val rendererId = 1L
        override val rendererEpoch = 1L
        val live = linkedMapOf<Long, EngineTexture>()
        private val retiring = linkedMapOf<Long, CompletableDeferred<Unit>>()
        private var references = emptySet<Long>()
        private var key = 0L

        override suspend fun upload(pixels: EnginePixels, expectedEpoch: Long) =
            EngineTexture(pixels.tile, rendererId, expectedEpoch, ++key, pixels.byteCount).also { live[it.key] = it }

        override suspend fun release(texture: EngineTexture) {
            val completion = CompletableDeferred<Unit>()
            retiring[texture.key] = completion
            collect()
            completion.await()
        }

        fun scene(keys: Set<Long>) {
            references = keys
            collect()
        }

        private fun collect() {
            retiring.keys.filter { it !in references }.forEach {
                live.remove(it)
                retiring.remove(it)!!.complete(Unit)
            }
        }
    }

    private fun threadAllocatedBytes(): Long = try {
        val bean = ManagementFactory.getThreadMXBean()
        if (bean is com.sun.management.ThreadMXBean && bean.isThreadAllocatedMemorySupported) {
            bean.isThreadAllocatedMemoryEnabled = true
            bean.getThreadAllocatedBytes(Thread.currentThread().id)
        } else {
            -1L
        }
    } catch (_: Throwable) {
        -1L
    }
}
