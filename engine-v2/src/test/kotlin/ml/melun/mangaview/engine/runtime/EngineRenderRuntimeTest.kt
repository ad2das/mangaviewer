package ml.melun.mangaview.engine.runtime

import java.io.File
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
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineRenderRuntimeTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val dimensions = PageDimensions(100, 1000)

    @Test fun disabledStartupMetadataDoesNotQueueBlankBuffersBeforeTheFirstImage() = runTest {
        val fixture = Fixture(this, waitForComplete = true)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        fixture.runtime.enabled(false)
        val initial = snapshot()
        fixture.runtime.update(initial)
        fixture.runtime.update(initial.copy(session = initial.session.copy(geometryRevision = 2)))
        runCurrent()
        assertTrue("A disabled renderer with no prior scene has nothing to clear", fixture.scenes.isEmpty())
        fixture.runtime.enabled(true)
        runCurrent()
        assertTrue("Attachment must not substitute a blank for the pending first image", fixture.scenes.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.scenes.size)
        assertTrue(fixture.scenes.single().completeCoverage)
        assertEquals(initial.session.anchor, fixture.scenes.single().session.anchor)
        fixture.runtime.enabled(false)
        runCurrent()
        assertEquals(2, fixture.scenes.size)
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        fixture.close()
    }

    @Test fun disablingBeforeFirstCompleteCoverageStillClearsSubmittedPartialPixels() = runTest {
        val fixture = Fixture(this, textureBudget = 240_000, tileHeight = 102)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { if (it.sourceTop > 250) gate.await() }
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isNotEmpty())
        assertFalse(fixture.scenes.last().completeCoverage)
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        gate.complete(Unit)
        fixture.close()
    }

    @Test fun fullSceneReleasesFileAndCpuBorrowsThenEmptySceneAllowsGpuRetirement() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(1, fixture.uploader.live.size)
        assertEquals(0, fixture.files)
        assertEquals(1, fixture.pixelCloses)
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertEquals(0, fixture.uploader.live.size)
        fixture.close()
    }

    @Test fun generationReplacementCannotReuseAClosedTexture() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        val oldKey = fixture.scenes.last().quads.single().texture.key
        fixture.runtime.update(snapshot().let { it.copy(session = it.session.copy(generation = 2)) })
        runCurrent()
        val current = fixture.scenes.last()
        assertEquals(2L, current.session.generation)
        assertTrue(current.completeCoverage)
        assertNotEquals(oldKey, current.quads.single().texture.key)
        assertFalse(oldKey in fixture.uploader.live)
        fixture.close()
    }

    @Test fun closeCallbackFailureStillClearsSceneAndReleasesGpuOwnership() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        fixture.failScene = true
        repeat(2) {
            try { fixture.runtime.close(); fail("Expected callback failure") }
            catch (_: IllegalStateException) { }
        }
        assertTrue(fixture.uploader.live.isEmpty())
        assertEquals(0, fixture.coordinator.snapshot().subscribers)
        fixture.coordinator.close()
    }

    @Test fun speculativeDecodeFailureIsRetriedAndReportedOnlyWhenVisible() = runTest {
        val fixture = Fixture(this, 240_000)
        var attempts = 0
        fixture.beforeDecode = { tile ->
            if (tile.sourceTop == 400) { attempts++; error("decode failed") }
        }
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(1, attempts)
        assertTrue(fixture.failures.isEmpty())
        repeat(3) { fixture.runtime.update(snapshot()); runCurrent() }
        assertEquals(1, attempts)
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val next = snapshot().let { it.copy(session = it.session.copy(
            anchor = SourceAnchor(id, 450 * q), inputRevision = 2,
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 450 * q, 550 * q, 0, 102400)))) }
        fixture.runtime.update(next)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, fixture.failures.size)
        assertFalse(fixture.scenes.last().completeCoverage)
        fixture.beforeDecode = {}
        fixture.runtime.retryFailures()
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun diagnosticSnapshotDistinguishesHeldVisibleDecodeFromResidentCoverage() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = {
            entered.complete(Unit)
            gate.await()
        }
        fixture.runtime.update(snapshot())
        runCurrent()
        entered.await()

        val held = fixture.runtime.diagnosticSnapshot()
        assertEquals(setOf(id), held.plannedVisibleTiles.map { it.pageId }.toSet())
        assertTrue(held.residentTextureTiles.isEmpty())
        assertTrue(held.completeGeometry)
        assertFalse(held.completeCoverage)
        assertEquals(1, held.work.active)
        assertEquals(0, held.work.ready)

        gate.complete(Unit)
        runCurrent()

        val released = fixture.runtime.diagnosticSnapshot()
        assertEquals(released.plannedVisibleTiles, released.residentTextureTiles)
        assertTrue(released.completeCoverage)
        assertEquals(1, released.work.ready)
        fixture.close()
    }

    @Test fun forwardAndReverseWithinPreparedDistanceReuseExactTexturesImmediately() = runTest {
        val fixture = Fixture(this, 400_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        assertTrue(fixture.scenes.last().completeCoverage)
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val forward = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 450 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 450 * q, 550 * q, 0, 102400))))
        fixture.runtime.update(forward)
        assertTrue("Forward scene must be ready before any new asynchronous work", fixture.scenes.last().completeCoverage)
        assertEquals(2L, fixture.scenes.last().session.inputRevision)
        runCurrent()
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertTrue("Reverse scene must reuse retained trailing tiles", fixture.scenes.last().completeCoverage)
        assertEquals(originalKeys, fixture.scenes.last().quads.map { it.texture.key })
        assertEquals(3L, fixture.scenes.last().session.inputRevision)
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun returningOutsideThePreparationHorizonReusesTexturesWhenBudgetHasRoom() = runTest {
        val fixture = Fixture(this, 400_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 400_000)
        fixture.beforeDecode = { error("Returning to a retained texture must not decode again") }
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertTrue("Spare budget must retain recently displayed original pixels", fixture.scenes.last().completeCoverage)
        assertEquals(originalKeys, fixture.scenes.last().quads.map { it.texture.key })
        runCurrent()
        assertTrue(fixture.failures.isEmpty())
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun currentPreparationEvictsOldResidencyWhenThereIsNoSpareBudget() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(originalKeys.none { it in fixture.uploader.live })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertFalse(fixture.scenes.last().completeCoverage)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.close()
    }

    @Test fun heldDecodeKeepsCompleteOldSceneAndItsOwnershipUntilCompleteReplacement() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, preparationViewports = 2, waitForComplete = true)
        val initial = snapshot()
        val firstGate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { firstGate.await() }
        fixture.runtime.update(initial)
        runCurrent()
        assertTrue("Startup must not submit a partial scene", fixture.scenes.isEmpty())
        firstGate.complete(Unit)
        runCurrent()
        val displayed = fixture.scenes.last()
        val keys = displayed.quads.map { it.texture.key }
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertEquals(displayed, fixture.scenes.last())
        assertTrue(keys.all { it in fixture.uploader.live })
        assertFalse(fixture.runtime.diagnosticSnapshot().completeCoverage)
        assertTrue(fixture.runtime.diagnosticSnapshot().residentTextureTiles.containsAll(displayed.quads.map { it.texture.tile }))
        gate.complete(Unit)
        runCurrent()
        assertEquals(far.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.scenes.all { it.completeCoverage })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.close()
        assertEquals(0, fixture.coordinator.snapshot().subscribers)
    }

    @Test fun disablingWhileWaitingClearsDisplayedReferencesAndCancelsPendingDecode() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, waitForComplete = true)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        fixture.runtime.update(initial.copy(session = initial.session.copy(anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400)))))
        runCurrent()
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun singleViewportBudgetReleasesNativeReferencesWithoutSubmittingABlankFrame() = runTest {
        val fixture = Fixture(this, 80_000, waitForComplete = true)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val displayed = fixture.scenes.last()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(anchor = SourceAnchor(id, 650 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 650 * q, 750 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertEquals("Clearing texture references must not swap an empty buffer", displayed, fixture.scenes.last())
        assertTrue("Old native references must retire before the pending upload", fixture.uploader.live.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(far.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.scenes.all { it.completeCoverage })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 80_000)
        fixture.close()
    }

    @Test fun offscreenGeometryDoesNotRedrawButNewInputStillSubmits() = runTest {
        val fixture = Fixture(this)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val count = fixture.scenes.size
        val requests = fixture.pageRequestBuilds
        val updated = initial.copy(session = initial.session.copy(geometryRevision = 100))
        fixture.runtime.update(updated)
        runCurrent()
        assertEquals(count, fixture.scenes.size)
        fixture.runtime.update(updated.copy(session = updated.session.copy(inputRevision = 2)))
        runCurrent()
        assertEquals(count + 1, fixture.scenes.size)
        assertEquals(2L, fixture.scenes.last().session.inputRevision)
        assertEquals(100L, fixture.scenes.last().session.geometryRevision)
        assertEquals("Ready scrolling must not rebuild identical tile work", requests, fixture.pageRequestBuilds)
        fixture.close()
    }

    @Test fun renewedAccessPlanRebuildsWorkEvenWhenItsImageIdentityIsUnchanged() = runTest {
        val fixture = Fixture(this)
        val manifest = ml.melun.mangaview.core.EpisodeManifest(id.episodeId, "test",
            listOf(ml.melun.mangaview.core.PageSpec(id, 0)))
        fun access(epoch: Long) = EpisodeAccessPlan(manifest, "1", "0".repeat(64),
            java.net.URI("https://test.example/read"), epoch,
            listOf(PageAccessPlan(id, "0", listOf(java.net.URI("https://test.example/image")))))
        val initial = snapshot().copy(plans = mapOf(id.episodeId to access(0)))
        fixture.runtime.update(initial)
        runCurrent()
        val requests = fixture.pageRequestBuilds
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2)))
        runCurrent()
        assertEquals(requests, fixture.pageRequestBuilds)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2),
            plans = mapOf(id.episodeId to access(1))))
        runCurrent()
        assertTrue(fixture.pageRequestBuilds > requests)
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
    }

    private inner class Fixture(scope: TestScope, textureBudget: Long = 80_000,
        tileHeight: Int = 202, preparationViewports: Int = 0, waitForComplete: Boolean = false,
    ) {
        val coordinator = WorkCoordinator(scope)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        var files = 0
        var pixelCloses = 0
        var pageRequestBuilds = 0
        var failScene = false
        var beforeDecode: suspend (EngineTileSpec) -> Unit = {}
        val failures = mutableListOf<Throwable>()
        private val tileWork = EngineTileWork(EngineImageDecoder { _, tile ->
            beforeDecode(tile)
            object : EnginePixels {
                override val tile = tile
                override val byteCount = tile.byteCount
                override fun close() { pixelCloses++ }
            }
        }, StandardTestDispatcher(scope.testScheduler), uploader)
        val runtime = EngineRenderRuntime(scope, coordinator,
            EngineTilePlanner(textureBudget, tileHeight, preparationViewports), tileWork, uploader,
            { _, priority ->
                pageRequestBuilds++
                WorkRequest(WorkKey("test", "page", "read", "1", StoredPage::class.java), WorkDomain.STORAGE, priority,
                    execute = { files++; StoredPage(id, "1", File("original.png"), 1, "1".repeat(64), dimensions, "image/png") },
                    dispose = { files-- })
            }, { scene ->
                if (failScene) error("frame callback failed")
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { uploader.scene(emptySet()) }, { _, failure -> failures += failure }, waitForComplete)

        suspend fun close() { runtime.close(); coordinator.close(); assertTrue(uploader.live.isEmpty()) }
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
        fun scene(keys: Set<Long>) { references = keys; collect() }
        private fun collect() {
            retiring.keys.filter { it !in references }.forEach {
                live.remove(it)
                retiring.remove(it)!!.complete(Unit)
            }
        }
    }

    private fun snapshot(): EngineRuntimeSnapshot {
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val state = EngineSessionSnapshot(1, 1, EngineSessionPhase.ACTIVE, EngineViewport(100, 100),
            SourceAnchor(id, 250 * q), 1, 1, 0,
            listOf(VisiblePageRegion(id, dimensions, 250 * q, 350 * q, 0, 102400)), emptySet(), emptySet(), true)
        return EngineRuntimeSnapshot(state, emptyMap(), mapOf(id to PageContentIdentity(id, "1", "1".repeat(64), dimensions, 1)))
    }
}
