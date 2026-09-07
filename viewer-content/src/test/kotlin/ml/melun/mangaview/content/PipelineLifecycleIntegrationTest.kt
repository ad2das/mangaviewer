package ml.melun.mangaview.content

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.source.PageFetchPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineLifecycleIntegrationTest {
    @Test fun homeGenerationRendererChangeAndCloseKeepEveryPhysicalOwner() = runTest {
        for (phase in listOf("fetch", "decode", "upload")) exercise(phase)
    }

    private suspend fun TestScope.exercise(phase: String) {
        val fixture = PipelineFixture()
        val gate = CompletableDeferred<Unit>()
        var started = 0
        suspend fun hold(stage: String) {
            if (phase != stage) return
            started++
            withContext(NonCancellable) { gate.await() }
        }
        val raw = object : RawPagePort by FakeRawPort(fixture) {
            override suspend fun fetch(pageId: PageId, priority: PageFetchPriority,
                responseStarted: () -> Unit): EncodedPageRef {
                responseStarted()
                hold("fetch")
                return fixture.encoded(pageId)
            }
        }
        val decoder = ImageDecodePort { request ->
            hold("decode")
            FakeCpuTile(request.page.id, 0, 1600, 1600)
        }
        val released = FakeUploader()
        val uploader = object : TextureUploadPort by released {
            override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef {
                var delegated = false
                try {
                    hold("upload")
                    delegated = true
                    return released.upload(rendererEpoch, pixels)
                } finally { if (!delegated) pixels.close() }
            }
        }
        val pipeline = fixture.pipeline(testScheduler, coroutineContext, raw, decoder, uploader)
        try {
            mixTransitions(pipeline, fixture) { assertEquals(phase, 1, started) }
            val closing = launch { pipeline.closeAndJoin() }
            runCurrent()
            assertFalse("$phase owner escaped joined shutdown", closing.isCompleted)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(closing.isCompleted)
            assertEquals("$phase replacement started during shutdown", 1, started)
            assertTrue(released.liveTextures.isEmpty())
        } finally { gate.complete(Unit); pipeline.closeAndJoin() }
    }

    private suspend fun TestScope.mixTransitions(pipeline: ViewerContentPipeline,
        fixture: PipelineFixture, verify: () -> Unit) {
        for (generation in 1L..8L) {
            pipeline.setForeground(false)
            pipeline.registerManifest(generation, fixture.manifest)
            pipeline.setRendererEpoch(generation)
            pipeline.setForeground(true)
            pipeline.updateDemand(fixture.demand().copy(generation = generation), 1080, 2138)
            runCurrent()
            verify()
            if (generation > 1) assertEquals(1, pipeline.snapshot().retiringPages.size)
        }
    }
}
