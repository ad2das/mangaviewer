package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.StoredPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineSurfaceOwnerTest {
    @Test fun allocationWaitsForRetirementAndUploaderOnlyBorrowsPixels() = runBlocking {
        val (page, pixels) = pixels()
        val failures = mutableListOf<Throwable>()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { failures += it }, {})
        try {
            val texture = owner.upload(pixels, owner.rendererEpoch)
            assertFalse(pixels.isClosed)
            assertEquals(pixels.byteCount, owner.ownership().bytes)
            val next = async(start = CoroutineStart.UNDISPATCHED) { owner.upload(pixels, owner.rendererEpoch) }
            assertEquals(1, owner.ownership().textures)
            assertFalse(next.isCompleted)
            owner.release(texture)
            val replacement = withTimeout(5000) { next.await() }
            assertEquals(1, owner.ownership().textures)
            owner.release(replacement)
            assertEquals(0, owner.ownership().textures)
            assertFalse(pixels.isClosed)
            assertTrue(failures.isEmpty())
        } finally { owner.close(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun cancellingAnAllocationWaitDoesNotFreeBorrowedPixelsOrTheExistingTexture() = runBlocking {
        val (page, pixels) = pixels()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { throw it }, {})
        try {
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { owner.upload(pixels, owner.rendererEpoch) }
            assertEquals(1, owner.ownership().textures)
            waiting.cancel()
            withTimeout(5000) { waiting.join() }
            assertFalse(pixels.isClosed)
            assertEquals(1, owner.ownership().textures)
            owner.release(texture)
            assertEquals(0, owner.ownership().textures)
        } finally { owner.close(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun sceneRetainsReleasedTextureUntilNativeSceneReferencesAreRemoved() = runBlocking {
        val (page, pixels) = pixels()
        val presented = CompletableDeferred<EngineSurfacePresentation>()
        val owner = EngineSurfaceOwner(pixels.byteCount, { presented.complete(it) }, { presented.completeExceptionally(it) }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 101, 100, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            owner.offer(EngineSurfaceScene(1, 1, 0, 1, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 0, 100))))
            val frame = withTimeout(5000) { presented.await() }
            assertSuccessfulSwap(frame)
            assertEquals(1, frame.identity.surfaceEpoch)
            val release = async(start = CoroutineStart.UNDISPATCHED) { owner.release(texture) }
            val before = owner.ownership()
            assertEquals(1, before.retiringTextures)
            assertEquals(pixels.byteCount, before.retiringBytes)
            assertFalse(release.isCompleted)
            owner.clearScene()
            withTimeout(5000) { release.await() }
            assertEquals(0, owner.ownership().textures)
            assertEquals(0, owner.ownership().sceneEntries)
        } finally { owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun sameSizeReplacementSurfaceGetsANewAttachmentIdentity() = runBlocking {
        val (page, pixels) = pixels()
        val first = CompletableDeferred<EngineSurfacePresentation>()
        val second = CompletableDeferred<EngineSurfacePresentation>()
        val owner = EngineSurfaceOwner(pixels.byteCount, {
            if (!first.isCompleted) first.complete(it) else second.complete(it)
        }, { if (!first.isCompleted) first.completeExceptionally(it) else second.completeExceptionally(it) }, {})
        val consumerA = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val consumerB = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surfaceA = Surface(consumerA)
        val surfaceB = Surface(consumerB)
        try {
            assertTrue(owner.attach(surfaceA, 101, 100, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val scene = EngineSurfaceScene(1, 1, 0, 1, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 0, 100)))
            owner.offer(scene)
            val a = withTimeout(5000) { first.await() }
            assertSuccessfulSwap(a)
            owner.detach()
            assertTrue(owner.attach(surfaceB, 101, 100, 60F))
            owner.offer(scene.copy(inputRevision = 1))
            val b = withTimeout(5000) { second.await() }
            assertSuccessfulSwap(b)
            assertEquals(a.identity.rendererEpoch, b.identity.rendererEpoch)
            assertTrue(b.identity.surfaceEpoch > a.identity.surfaceEpoch)
            assertTrue(b.identity.token > a.identity.token)
            owner.clearScene()
            owner.release(texture)
        } finally {
            owner.close()
            surfaceA.release(); surfaceB.release(); consumerA.release(); consumerB.release()
            pixels.close(); assertTrue(page.file.delete())
        }
    }

    @Test fun repeatedCloseDestroysOwnedAllocationsAndJoinsTheOwnerThread() = runBlocking {
        val (page, pixels) = pixels()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { throw it }, {})
        try {
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val first = async { owner.close() }
            val second = async { owner.close() }
            withTimeout(5000) { first.await(); second.await() }
            owner.release(texture)
            assertEquals(0, owner.ownership().bytes)
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "engine-gl-${owner.rendererId}" })
            assertFalse(pixels.isClosed)
        } finally { owner.close(); pixels.close(); assertTrue(page.file.delete()) }
    }

    private fun assertSuccessfulSwap(frame: EngineSurfacePresentation) {
        assertTrue("Native eglSwapBuffers must succeed independently of presentation evidence", frame.swapSucceeded)
        if (frame.timestampKind == PresentationTimestampKind.UNAVAILABLE ||
            frame.timestampKind == PresentationTimestampKind.DROPPED) assertEquals(0L, frame.timestampNanos)
    }

    @Test fun abandonedWindowSignalsSurfaceLossInsteadOfFatalRenderingFailure() = runBlocking {
        val (page, pixels) = pixels()
        val lost = CompletableDeferred<Unit>()
        val frame = CompletableDeferred<EngineSurfacePresentation>()
        val failures = mutableListOf<Throwable>()
        val owner = EngineSurfaceOwner(pixels.byteCount, { frame.complete(it) }, { failures += it }, {}, { lost.complete(Unit) })
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 101, 100, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            consumer.release()
            owner.offer(EngineSurfaceScene(1, 1, 0, 1, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 0, 100))))
            withTimeout(5000) { lost.await() }
            assertFalse(frame.await().swapSucceeded)
            assertTrue(failures.isEmpty())
            assertEquals(0, owner.ownership().sceneEntries)
            owner.release(texture)
            assertEquals(0, owner.ownership().textures)
        } finally { owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun fractionalScreenCoordinatesReachActualGpuPixelsWithoutIntegerTruncation() = runBlocking {
        val (page, pixels) = pixels()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { throw it }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 101, 100, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val scene = EngineSurfaceScene(1, 1, 1, 1, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 768, 103168)), coordinateUnitsPerPixel = 1024)
            val packet = owner.capture(scene, 0, 2)
            assertEquals(EngineReadbackPacket.Status.OK, packet.status)
            assertFalse(packet.physicalPresentationVerified)
            assertEquals(1L, packet.sessionId)
            assertEquals(owner.rendererEpoch, packet.rendererEpoch)
            assertEquals(808L, packet.rgbaByteCount)
            for (x in 0 until 101) {
                val top = x * 4
                val next = (101 + x) * 4
                assertArrayEquals(byteArrayOf(0, 0, 0, -1), packet.rgbaBytes.copyOfRange(top, top + 4))
                assertArrayEquals(byteArrayOf(0x82.toByte(), 0xb4.toByte(), 0x47, -1),
                    packet.rgbaBytes.copyOfRange(next, next + 4))
            }
            owner.clearScene()
            owner.release(texture)
        } finally { owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun nextCaptureBindsTheActualOfferedInputFrameAndItsPixels() = runBlocking {
        val (page, pixels) = pixels()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { throw it }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 101, 100, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val capture = async(start = CoroutineStart.UNDISPATCHED) { owner.captureNextFrame(0, 2) }
            val offered = EngineSurfaceScene(7, 1, 17, 4, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 768, 103168)), coordinateUnitsPerPixel = 1024)
            owner.offer(offered)
            val result = withTimeout(5000) { capture.await() }
            assertEquals(offered, result.scene)
            assertEquals(17L, result.identity.inputRevision)
            assertEquals(4L, result.identity.geometryRevision)
            assertEquals(result.identity.token, result.pixels.token)
            assertEquals(EngineReadbackPacket.Status.OK, result.pixels.status)
            assertFalse(result.pixels.physicalPresentationVerified)
            assertArrayEquals(byteArrayOf(0, 0, 0, -1), result.pixels.rgbaBytes.copyOfRange(0, 4))
            assertArrayEquals(byteArrayOf(0x82.toByte(), 0xb4.toByte(), 0x47, -1), result.pixels.rgbaBytes.copyOfRange(404, 408))
            owner.clearScene()
            owner.release(texture)
        } finally { owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun nextViewportCaptureUsesTheWholeNaturallySubmittedScene() = runBlocking {
        val (page, pixels) = pixels()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { throw it }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(101, 100) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 101, 100, 60F))
            assertNull(owner.closedSubmissionCount)
            val texture = owner.upload(pixels, owner.rendererEpoch)
            val capture = async(start = CoroutineStart.UNDISPATCHED) { owner.captureNextViewportFrame() }
            val offered = EngineSurfaceScene(7, 1, 18, 4, EngineViewport(101, 100), null,
                listOf(EngineTexturePlacement(texture, 0, 100)))
            owner.offer(offered)
            val result = withTimeout(5000) { capture.await() }
            assertEquals(offered, result.scene)
            assertEquals(18L, result.identity.inputRevision)
            assertEquals(0L, result.pixels.top)
            assertEquals(100L, result.pixels.bottom)
            assertEquals(40400L, result.pixels.rgbaByteCount)
            val green = byteArrayOf(0x82.toByte(), 0xb4.toByte(), 0x47, -1)
            result.pixels.rgbaBytes.asList().chunked(4).forEach { assertArrayEquals(green, it.toByteArray()) }
            owner.clearScene()
            owner.release(texture)
            owner.close()
            assertTrue(requireNotNull(owner.closedSubmissionCount) >= result.identity.token)
        } finally { owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete()) }
    }

    @Test fun captureWaitingForAFrameCancelsWithoutBlockingTheNextRequestOrSurfaceClose() = runBlocking {
        val owner = EngineSurfaceOwner(1024, {}, { throw it }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(10, 10) }
        val surface = Surface(consumer)
        try {
            assertTrue(owner.attach(surface, 10, 10, 60F))
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { owner.captureNextFrame(0, 1) }
            withTimeout(5000) { cancelled.cancelAndJoin() }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { owner.captureNextFrame(0, 1) }
            owner.detach()
            withTimeout(5000) { pending.join() }
            assertTrue(pending.isCancelled)
            assertEquals(0, owner.ownership().sceneEntries)
        } finally { owner.close(); surface.release(); consumer.release() }
    }

    private suspend fun pixels(): Pair<StoredPage, NativeEnginePixels> = withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("engine-gl-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(101, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff82b447.toInt())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "gl"), "1"), 0)
        val page = StoredPage(id, "1", file, file.length(), sha, PageDimensions(101, 100), "image/png")
        val tile = EngineTileSpec(id, "1", sha, page.dimensions, 0, 100, 101)
        page to (NativeEngineImageDecoder().decode(page, tile) as NativeEnginePixels)
    }
}
