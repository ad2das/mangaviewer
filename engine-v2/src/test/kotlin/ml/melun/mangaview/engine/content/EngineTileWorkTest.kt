package ml.melun.mangaview.engine.content

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.EngineTextureUpload
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.work.WorkCoordinator
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineTileWorkTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val stored = StoredPage(id, "1", File("original.png"), 1, "1".repeat(64), PageDimensions(101, 1000), "image/png")
    private val tile = EngineTileSpec(id, "1", stored.sha256, stored.dimensions, 100, 300, 150)

    @Test fun slowOriginalPreparationDoesNotOccupyTheSerializedUploadPermit() = runTest {
        val coordinator = WorkCoordinator(this)
        val firstReady = CompletableDeferred<Unit>()
        val secondTile = tile.copy(sourceTop = 300, sourceBottom = 500)
        val events = mutableListOf<String>()
        var key = 0L
        val uploader = object : EngineTextureUploader {
            override val rendererId = 1L
            override val rendererEpoch = 1L
            override suspend fun upload(pixels: EnginePixels, expectedEpoch: Long): EngineTexture {
                events += "upload:${pixels.tile.sourceTop}"
                return EngineTexture(pixels.tile, rendererId, expectedEpoch, ++key, pixels.byteCount)
            }
            override suspend fun prepareTexture(pixels: EnginePixels): EngineTextureUpload {
                events += "prepare:${pixels.tile.sourceTop}"
                if (pixels.tile == tile) firstReady.await()
                val actual = super.prepareTexture(pixels)
                return object : EngineTextureUpload by actual {
                    override suspend fun close() {
                        assertEquals(0, (pixels as Pixels).closes)
                        events += "close:${pixels.tile.sourceTop}"
                    }
                }
            }
            override suspend fun release(texture: EngineTexture) = Unit
        }
        val factory = EngineTileWork(EngineImageDecoder { _, spec -> Pixels(spec) },
            StandardTestDispatcher(testScheduler), uploader)
        val first = coordinator.submit(factory.request(page(), tile, WorkPriority.FOCUS))
        val second = coordinator.submit(factory.request(page(), secondTile, WorkPriority.VISIBLE))
        try {
            runCurrent()
            assertEquals(listOf("prepare:100", "prepare:300", "upload:300", "close:300"), events)
            assertEquals(secondTile, second.await().tile)
            firstReady.complete(Unit)
            first.await()
            assertEquals(listOf("upload:100", "close:100"), events.takeLast(2))
        } finally {
            firstReady.complete(Unit)
            first.close(); second.close()
            first.awaitReleased(); second.awaitReleased(); coordinator.close()
        }
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun preparedPixelsAreSharedWithViewerAndSurvivePredictionCancellationDuringUpload() = runTest {
        val coordinator = WorkCoordinator(this)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pixels = Pixels(tile)
        var decodes = 0
        val decoder = EngineImageDecoder { _, _ -> decodes++; pixels }
        val page = page()
        val prediction = coordinator.submit(EnginePixelWork(decoder, dispatcher).request(page, tile, WorkPriority.NEXT_IMAGE))
        assertSame(pixels, prediction.await())
        val uploading = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val uploader = Uploader {
            uploading.complete(Unit)
            finishUpload.await()
            assertEquals(0, pixels.closes)
        }
        val viewer = coordinator.submit(EngineTileWork(decoder, dispatcher, uploader).request(page, tile, WorkPriority.FOCUS))
        uploading.await()
        prediction.close(); prediction.awaitReleased()
        assertEquals(1, decodes)
        assertEquals(0, pixels.closes)
        finishUpload.complete(Unit)
        viewer.await()
        assertEquals(1, pixels.closes)
        viewer.close(); viewer.awaitReleased()
        assertEquals(1, uploader.releases)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun fileAndPixelsAreReleasedAfterUploadWhileTextureStaysOwned() = runTest {
        val coordinator = WorkCoordinator(this)
        var files = 0
        val pixels = Pixels(tile)
        val uploader = Uploader { assertEquals(0, pixels.closes) }
        val page = page({ files++ }, { files-- })
        val factory = EngineTileWork(EngineImageDecoder { _, _ -> pixels }, StandardTestDispatcher(testScheduler), uploader)
        val lease = coordinator.acquire(factory.request(page, tile, WorkPriority.FOCUS))
        assertEquals(tile.byteCount, lease.value.byteCount)
        assertEquals(0, files)
        assertEquals(1, pixels.closes)
        assertEquals(0, uploader.releases)
        assertEquals(2, coordinator.snapshot().retainedResults)
        lease.awaitReleased()
        assertEquals(1, uploader.releases)
        coordinator.close()
    }

    @Test fun cancellationCannotFreePixelsStillBorrowedByTheOwnerQueue() = runTest {
        val coordinator = WorkCoordinator(this)
        val pixels = Pixels(tile)
        var files = 0
        val entered = CompletableDeferred<Unit>()
        val ownerFinished = CompletableDeferred<Unit>()
        val uploader = Uploader {
            entered.complete(Unit)
            withContext(NonCancellable) { ownerFinished.await(); assertEquals(0, pixels.closes) }
        }
        val factory = EngineTileWork(EngineImageDecoder { _, _ -> pixels }, StandardTestDispatcher(testScheduler), uploader)
        val subscription = coordinator.submit(factory.request(page({ files++ }, { files-- }), tile, WorkPriority.FOCUS))
        entered.await()
        val closing = async { subscription.awaitReleased() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, pixels.closes)
        assertEquals(1, files)
        ownerFinished.complete(Unit)
        closing.await()
        assertEquals(1, pixels.closes)
        assertEquals(0, files)
        assertEquals(1, uploader.releases)
        coordinator.close()
    }

    @Test fun cancellationOnDecodeDispatcherReturnDisposesTheLostPixels() = runTest {
        val coordinator = WorkCoordinator(this)
        val pixels = Pixels(tile)
        val uploader = Uploader { error("Cancelled pixels must not upload") }
        val decoder = EngineImageDecoder { _, _ ->
            currentCoroutineContext()[Job]!!.cancel()
            pixels
        }
        val factory = EngineTileWork(decoder, StandardTestDispatcher(testScheduler), uploader)
        val caller = async { coordinator.acquire(factory.request(page(), tile, WorkPriority.FOCUS)) }
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(1, pixels.closes)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    private fun page(open: () -> Unit = {}, close: () -> Unit = {}) = WorkRequest(
        WorkKey("test", "page", "raw", "1", StoredPage::class.java), WorkDomain.STORAGE, WorkPriority.FOCUS,
        execute = { open(); stored }, dispose = { close() },
    )

    private class Pixels(override val tile: EngineTileSpec) : EnginePixels {
        override val byteCount = tile.byteCount
        var closes = 0
        override fun close() { closes++; check(closes == 1) }
    }

    private class Uploader(private val beforeUpload: suspend () -> Unit) : EngineTextureUploader {
        override val rendererId = 1L
        override val rendererEpoch = 1L
        var releases = 0
        override suspend fun upload(pixels: EnginePixels, expectedEpoch: Long): EngineTexture {
            beforeUpload()
            return EngineTexture(pixels.tile, rendererId, expectedEpoch, 1, pixels.byteCount)
        }
        override suspend fun release(texture: EngineTexture) { releases++ }
    }
}
