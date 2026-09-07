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

    private inner class Fixture(scope: TestScope) {
        val coordinator = WorkCoordinator(scope)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        var files = 0
        var pixelCloses = 0
        var failScene = false
        private val tileWork = EngineTileWork(EngineImageDecoder { _, tile ->
            object : EnginePixels {
                override val tile = tile
                override val byteCount = tile.byteCount
                override fun close() { pixelCloses++ }
            }
        }, StandardTestDispatcher(scope.testScheduler), uploader)
        val runtime = EngineRenderRuntime(scope, coordinator, EngineTilePlanner(80_000, 202), tileWork, uploader,
            { _, priority ->
                WorkRequest(WorkKey("test", "page", "read", "1", StoredPage::class.java), WorkDomain.STORAGE, priority,
                    execute = { files++; StoredPage(id, "1", File("original.png"), 1, "1".repeat(64), dimensions, "image/png") },
                    dispose = { files-- })
            }, { scene ->
                if (failScene) error("frame callback failed")
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { uploader.scene(emptySet()) }, { _, failure -> throw failure })

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
