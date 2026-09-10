package ml.melun.mangaview.engine.runtime

import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.SessionPosition
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.content.EngineTileWork
import ml.melun.mangaview.engine.session.EngineSession
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineRuntimeBoundaryIntegrationTest {
    private val series = SeriesId(SourceId("test"), "boundary")
    private val current = EpisodeId(series, "current")
    private val next = EpisodeId(series, "next")
    private val dimensions = PageDimensions(622, 900)
    private val viewport = EngineViewport(1080, 2138)

    @Test fun boundaryOriginalPreparationContinuesWhileVisibleDecodeIsBlocked() = runTest {
        val visibleDecodeGate = CompletableDeferred<Unit>()
        val reverseReadAheadGate = CompletableDeferred<Unit>()
        val source = Source(reverseReadAheadGate)
        val coordinator = WorkCoordinator(this)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        val failures = mutableListOf<Throwable>()
        val decoder = EngineImageDecoder { _, tile ->
            if (tile.pageId in boundaryPages) visibleDecodeGate.await()
            Pixels(tile)
        }
        val tiles = EngineTileWork(decoder, StandardTestDispatcher(testScheduler), uploader)
        val reducer = EngineSession(1, current, viewport) { testScheduler.currentTime * 1_000_000L }
        lateinit var content: EngineSessionRuntime
        lateinit var render: EngineRenderRuntime
        content = EngineSessionRuntime(this, coordinator, reducer, source, current, { snapshot, _ ->
            render.update(snapshot)
        }, { _, failure -> failures += failure })
        render = EngineRenderRuntime(this, coordinator, EngineTilePlanner(64L * 1_024L * 1_024L), tiles,
            uploader, content::pageRequest, { scene ->
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { uploader.scene(emptySet()) }, { _, failure -> failures += failure })

        try {
            content.open()
            runCurrent()

            val heldContent = content.diagnosticSnapshot()
            val heldRender = render.diagnosticSnapshot()
            assertEquals(heldContent.runtime.session, heldRender.session)
            assertEquals(boundaryPages, content.snapshot.session.visibleRegions.map { it.pageId }.toSet())
            assertEquals(boundaryPages, heldRender.plannedVisibleTiles.map { it.pageId }.toSet())
            assertTrue(heldRender.residentTextureTiles.isEmpty())
            assertFalse(heldRender.completeCoverage)
            assertFalse(scenes.last().completeCoverage)
            assertTrue(PageId.at(current, 13) in source.requestedPages)
            assertTrue(PageId.at(next, 2) in source.requestedPages)

            visibleDecodeGate.complete(Unit)
            runCurrent()

            val releasedContent = content.diagnosticSnapshot()
            val releasedRender = render.diagnosticSnapshot()
            assertEquals(releasedContent.runtime.session, releasedRender.session)
            assertTrue(releasedRender.completeCoverage)
            assertTrue(releasedRender.residentTextureTiles.containsAll(releasedRender.plannedVisibleTiles))
            assertTrue(PageId.at(current, 13) in source.requestedPages)
            assertTrue(PageId.at(next, 2) in source.requestedPages)
            assertTrue(scenes.any { it.completeCoverage && it.quads.map { quad -> quad.texture.tile.pageId }.toSet() == boundaryPages })

            reverseReadAheadGate.complete(Unit)
            runCurrent()

            assertEquals((0 until 15).map { PageId.at(current, it) }.toSet(),
                source.requestedPages.filter { it.episodeId == current }.toSet())
            assertEquals((0 until 23).map { PageId.at(next, it) }.toSet(),
                source.requestedPages.filter { it.episodeId == next }.toSet())
            assertEquals(WorkPriority.NEXT_EPISODE, source.priorities.getValue(PageId.at(next, 2)))
            assertTrue(render.diagnosticSnapshot().residentTextureTiles.any { it.pageId == PageId.at(next, 2) })
            val final = scenes.last()
            assertTrue(final.completeCoverage)
            assertEquals(boundaryPages, final.quads.map { it.texture.tile.pageId }.toSet())
            assertTrue(failures.toString(), failures.isEmpty())

        } finally {
            render.close()
            content.close()
            coordinator.close()
        }
        assertEquals(0, coordinator.snapshot().subscribers)
        assertTrue(uploader.live.isEmpty())
    }

    @Test fun startupFramePrecedesCrossEpisodeReplayAndInputRemainsExact() = runTest {
        val decodeGate = CompletableDeferred<Unit>()
        val nextManifestGate = CompletableDeferred<Unit>()
        val reverseReadAheadGate = CompletableDeferred<Unit>()
        val source = StartupSource(nextManifestGate, reverseReadAheadGate)
        val coordinator = WorkCoordinator(this)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        val receipts = mutableListOf<ml.melun.mangaview.engine.api.InputReceipt>()
        val failures = mutableListOf<Throwable>()
        val tiles = EngineTileWork(EngineImageDecoder { _, tile ->
            if (tile.pageId == PageId.at(current, 13)) decodeGate.await()
            Pixels(tile)
        }, StandardTestDispatcher(testScheduler), uploader)
        val reducer = EngineSession(2, current, EngineViewport(100, 50),
            { testScheduler.currentTime * 1_000_000L }).apply { engageStartupInputBarrier() }
        lateinit var content: EngineSessionRuntime
        lateinit var render: EngineRenderRuntime
        content = EngineSessionRuntime(this, coordinator, reducer, source, current, { snapshot, values ->
            receipts += values
            render.update(snapshot)
        }, { _, failure -> failures += failure })
        render = EngineRenderRuntime(this, coordinator, EngineTilePlanner(1_000_000, 202), tiles, uploader,
            content::pageRequest, { scene ->
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { uploader.scene(emptySet()) }, { _, failure -> failures += failure })

        try {
            content.open()
            val sample = ml.melun.mangaview.engine.api.InputSample(1, 1, 0, 250L * 1_024L)
            content.input(sample)
            runCurrent()

            val deferred = receipts.single { it.sample == sample }
            assertEquals(ml.melun.mangaview.engine.api.InputOutcome.DEFERRED, deferred.outcome)
            assertEquals(0L, deferred.appliedScreenUnits)
            assertTrue(next in source.requestedEpisodes)

            decodeGate.complete(Unit)
            runCurrent()

            assertTrue(scenes.any { scene -> scene.quads.any { it.texture.tile.pageId == PageId.at(current, 13) } })
            assertTrue(PageId.at(current, 14) in source.requestedPages)
            assertTrue(next in source.requestedEpisodes)
            assertFalse(receipts.any { it.sample == sample && it.outcome == ml.melun.mangaview.engine.api.InputOutcome.APPLIED })

            // Models EngineViewerRuntime's callback after a successful, nonempty native submission.
            content.releaseStartupInput()
            runCurrent()
            assertTrue(next in source.requestedEpisodes)
            assertTrue(next in content.snapshot.session.requiredEpisodes)
            assertTrue(content.snapshot.session.completeViewport)
            assertTrue(scenes.last().completeCoverage)
            assertFalse(receipts.any { it.sample == sample && it.outcome == ml.melun.mangaview.engine.api.InputOutcome.APPLIED })

            nextManifestGate.complete(Unit)
            runCurrent()

            val applied = receipts.last { it.sample == sample }
            assertEquals(ml.melun.mangaview.engine.api.InputOutcome.APPLIED, applied.outcome)
            assertEquals(deferred.acceptedAtNanos, applied.acceptedAtNanos)
            assertEquals(sample.deltaScreenUnits, applied.appliedScreenUnits)
            assertEquals(PageId.at(next, 0), content.snapshot.session.anchor!!.pageId)
            assertEquals(50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL, content.snapshot.session.anchor!!.sourceYQ32)
            assertTrue(failures.toString(), failures.isEmpty())

            reverseReadAheadGate.complete(Unit)
        } finally {
            render.close()
            content.close()
            coordinator.close()
        }
    }

    @Test fun readyPixelsDrainOrderedInputsAcrossDelayedManifestWithoutWaitingForDisplayTimestamps() = runTest {
        val decodeGate = CompletableDeferred<Unit>()
        val manifestGate = CompletableDeferred<Unit>()
        val source = StartupSource(manifestGate, CompletableDeferred())
        val coordinator = WorkCoordinator(this)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        val receipts = mutableListOf<ml.melun.mangaview.engine.api.InputReceipt>()
        val failures = mutableListOf<Throwable>()
        val tiles = EngineTileWork(EngineImageDecoder { _, tile ->
            if (tile.pageId == PageId.at(current, 13)) decodeGate.await()
            Pixels(tile)
        }, StandardTestDispatcher(testScheduler), uploader)
        val reducer = EngineSession(3, current, EngineViewport(100, 50),
            { testScheduler.currentTime * 1_000_000L }).apply { engageViewportReadinessBarrier() }
        lateinit var content: EngineSessionRuntime
        lateinit var render: EngineRenderRuntime
        content = EngineSessionRuntime(this, coordinator, reducer, source, current, { snapshot, values ->
            receipts += values
            render.update(snapshot)
        }, { _, failure -> failures += failure })
        render = EngineRenderRuntime(this, coordinator, EngineTilePlanner(1_000_000, 202), tiles, uploader,
            content::pageRequest, { scene ->
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { uploader.scene(emptySet()) }, { _, failure -> failures += failure },
            waitForCompleteViewport = true, reportViewportReady = content::viewportReady)
        val samples = listOf(250L, -50L, 10L).mapIndexed { index, delta ->
            ml.melun.mangaview.engine.api.InputSample(index + 1L, 1, 0, delta * 1024L)
        }
        try {
            content.open()
            samples.forEach(content::input)
            runCurrent()
            assertTrue(scenes.isEmpty())
            decodeGate.complete(Unit)
            runCurrent()
            assertTrue(scenes.isNotEmpty())
            assertTrue(next in content.snapshot.session.requiredEpisodes)
            assertEquals(3, content.snapshot.session.pendingInputCount)
            manifestGate.complete(Unit)
            runCurrent()
            val terminal = receipts.filter { it.outcome != ml.melun.mangaview.engine.api.InputOutcome.DEFERRED }
            assertEquals(samples, terminal.map { it.sample })
            assertEquals(samples.map { it.deltaScreenUnits }, terminal.map { it.appliedScreenUnits })
            assertEquals(0, content.snapshot.session.pendingInputCount)
            assertEquals(SourceAnchor(PageId.at(next, 0), 10L * SourceAnchor.SOURCE_UNITS_PER_PIXEL),
                content.snapshot.session.anchor)
            assertTrue(scenes.all { it.completeCoverage })
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            render.close()
            content.close()
            coordinator.close()
        }
        assertTrue(uploader.live.isEmpty())
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    private val boundaryPages = setOf(PageId.at(current, 14), PageId.at(next, 0), PageId.at(next, 1))

    private inner class Source(private val reverseReadAheadGate: CompletableDeferred<Unit>) : EngineSessionWork {
        val requestedPages = mutableListOf<PageId>()
        val priorities = linkedMapOf<PageId, WorkPriority>()

        override fun position(episodeId: EpisodeId) = request(episodeId.toString(), "position",
            SessionPosition::class.java, WorkDomain.STORAGE, WorkPriority.FOCUS) {
            SessionPosition(SourceAnchor(PageId.at(current, 14), 780L * SourceAnchor.SOURCE_UNITS_PER_PIXEL))
        }

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "episode", EpisodeAccessPlan::class.java, WorkDomain.CONTROL, priority) { plan(episodeId) }

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "navigation", AdjacentEpisodes::class.java, WorkDomain.NETWORK, priority) {
            AdjacentEpisodes(current.takeIf { episodeId == next }, next.takeIf { episodeId == current })
        }

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = WorkRequest(
            WorkKey("test", pageId.toString(), "page", "revision", StoredPage::class.java), WorkDomain.BODY,
            priority, execute = {
                requestedPages += pageId
                priorities.putIfAbsent(pageId, priority)
                if (pageId == PageId.at(current, 13)) reverseReadAheadGate.await()
                StoredPage(pageId, plan.contentRevision, File("immutable-${pageId.remoteKey}.png"), 1,
                    "1".repeat(64), dimensions, "image/png")
            },
        )

        private fun plan(id: EpisodeId): EpisodeAccessPlan {
            val count = if (id == current) 15 else 23
            val pages = (0 until count).map { PageSpec(PageId.at(id, it), it) }
            val manifest = EpisodeManifest(id, id.remoteKey, pages,
                previousEpisodeId = current.takeIf { id == next }, nextEpisodeId = next.takeIf { id == current })
            return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/read"), 0,
                pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
        }

        private fun <T : Any> request(resource: String, operation: String, type: Class<T>, domain: WorkDomain,
            priority: WorkPriority, execute: suspend () -> T) = WorkRequest(WorkKey("test", resource, operation,
            "1", type), domain, priority, execute = { execute() })
    }

    private inner class StartupSource(
        private val nextManifestGate: CompletableDeferred<Unit>,
        private val reverseReadAheadGate: CompletableDeferred<Unit>,
    ) : EngineSessionWork {
        private val pageDimensions = PageDimensions(100, 100)
        val requestedEpisodes = mutableListOf<EpisodeId>()
        val requestedPages = mutableListOf<PageId>()

        override fun position(episodeId: EpisodeId) = request(episodeId.toString(), "position",
            SessionPosition::class.java, WorkDomain.STORAGE, WorkPriority.FOCUS) {
            SessionPosition(SourceAnchor(PageId.at(current, 13), 0))
        }

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "episode", EpisodeAccessPlan::class.java, WorkDomain.CONTROL, priority) {
            requestedEpisodes += episodeId
            if (episodeId == next) nextManifestGate.await()
            plan(episodeId)
        }

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "navigation", AdjacentEpisodes::class.java, WorkDomain.NETWORK, priority) {
            AdjacentEpisodes(current.takeIf { episodeId == next }, next.takeIf { episodeId == current })
        }

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = WorkRequest(
            WorkKey("startup", pageId.toString(), "page", "revision", StoredPage::class.java), WorkDomain.BODY,
            priority, execute = {
                requestedPages += pageId
                if (pageId == PageId.at(current, 12)) reverseReadAheadGate.await()
                StoredPage(pageId, plan.contentRevision, File("startup-${pageId.remoteKey}.png"), 1,
                    "2".repeat(64), pageDimensions, "image/png")
            })

        private fun plan(id: EpisodeId): EpisodeAccessPlan {
            val count = if (id == current) 15 else 2
            val pages = (0 until count).map { PageSpec(PageId.at(id, it), it) }
            val manifest = EpisodeManifest(id, id.remoteKey, pages,
                previousEpisodeId = current.takeIf { id == next }, nextEpisodeId = next.takeIf { id == current })
            return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/startup"), 0,
                pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
        }

        private fun <T : Any> request(resource: String, operation: String, type: Class<T>, domain: WorkDomain,
            priority: WorkPriority, execute: suspend () -> T) = WorkRequest(WorkKey("startup", resource, operation,
            "1", type), domain, priority, execute = { execute() })
    }

    private class Pixels(override val tile: EngineTileSpec) : EnginePixels {
        override val byteCount = tile.byteCount
        override fun close() = Unit
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
}
