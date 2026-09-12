package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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
class NativeEngineImageDecoderTest {
    @Test fun actualNdkCropMatchesTheEngineRasterContract() = runBlocking {
        withContext(Dispatchers.IO) {
            val page = page()
            try {
                val tile = EngineTileSpec(page.pageId, page.contentRevision, page.sha256, page.dimensions, 100, 300, 150)
                val pixels = NativeEngineImageDecoder().decode(page, tile) as NativeEnginePixels
                try {
                    assertEquals(178800L, pixels.byteCount)
                    assertEquals(pixels.byteCount, NativeCpuDecodeBridge.nativeByteCount(pixels.handle))
                    assertFalse(pixels.isClosed)
                } finally { pixels.close() }
                assertTrue(pixels.isClosed)
            } finally { assertTrue(page.file.delete()) }
        }
    }

    @Test fun wrongImmutableIdentityCannotReachNativeDecode() = runBlocking {
        withContext(Dispatchers.IO) {
            val page = page()
            try {
                val tile = EngineTileSpec(page.pageId, page.contentRevision, "0".repeat(64), page.dimensions, 0, 100, 101)
                try {
                    NativeEngineImageDecoder().decode(page, tile)
                    fail("Expected identity rejection")
                } catch (_: IllegalArgumentException) { }
            } finally { assertTrue(page.file.delete()) }
        }
    }

    @Test fun spreadHalfCropsDecodeTheMatchingSourceColumns() = runBlocking {
        val width = 400
        val height = 600
        val viewportWidth = 400
        val viewportHeight = 600
        val red = 0xffe02020.toInt()
        val green = 0xff20c020.toInt()
        val blue = 0xff2040e0.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("engine-split-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val values = IntArray(width * height) { index ->
                when (val x = index % width) {
                    in 0 until 200 -> red
                    in 200 until 202 -> green
                    else -> blue
                }
            }
            bitmap.setPixels(values, 0, width, 0, 0, width, height)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "split"), "1"), 0)
        val page = StoredPage(id, "1", file, file.length(), digest, PageDimensions(width, height), "image/png")
        val failures = mutableListOf<Throwable>()
        try {
            val left = withContext(Dispatchers.IO) {
                NativeEngineImageDecoder().decode(page,
                    EngineTileSpec(id, "1", digest, page.dimensions, 0, height, viewportWidth, 0, 200))
            }
            val right = withContext(Dispatchers.IO) {
                NativeEngineImageDecoder().decode(page,
                    EngineTileSpec(id, "1", digest, page.dimensions, 0, height, viewportWidth, 200, 400))
            }
            assertEquals(200L * height * 4L, left.byteCount)
            assertEquals(200L * height * 4L, right.byteCount)
            val owner = EngineSurfaceOwner(left.byteCount + right.byteCount, {}, { failures += it }, {})
            val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(viewportWidth, viewportHeight) }
            val surface = Surface(consumer)
            try {
                assertTrue(owner.attach(surface, viewportWidth, viewportHeight, 60F))
                val leftTexture = owner.upload(left, owner.rendererEpoch)
                val capturedLeft = withTimeout(10_000) {
                    owner.capture(EngineSurfaceScene(1, 1, 1, 1,
                        EngineViewport(viewportWidth, viewportHeight), null,
                        listOf(EngineTexturePlacement(leftTexture, 0, height * 1024)),
                        coordinateUnitsPerPixel = 1024, completeCoverage = true), 0, viewportHeight)
                }
                assertEquals(EngineReadbackPacket.Status.OK, capturedLeft.status)
                assertUniformColumns(capturedLeft.rgbaBytes, viewportWidth, 0 until viewportWidth, red)
                owner.clearScene()
                owner.release(leftTexture)
                val rightTexture = owner.upload(right, owner.rendererEpoch)
                val capturedRight = withTimeout(10_000) {
                    owner.capture(EngineSurfaceScene(1, 1, 2, 2,
                        EngineViewport(viewportWidth, viewportHeight), null,
                        listOf(EngineTexturePlacement(rightTexture, 0, height * 1024)),
                        coordinateUnitsPerPixel = 1024, completeCoverage = true), 0, viewportHeight)
                }
                assertEquals(EngineReadbackPacket.Status.OK, capturedRight.status)
                assertUniformColumns(capturedRight.rgbaBytes, viewportWidth, 8 until viewportWidth - 8, blue)
                assertPixel(capturedRight.rgbaBytes, viewportWidth, 0, 0, green)
                owner.clearScene()
                owner.release(rightTexture)
                assertEquals(0L, owner.ownership().bytes)
                assertTrue(failures.isEmpty())
            } finally {
                owner.close()
                surface.release()
                consumer.release()
                left.close()
                right.close()
            }
        } finally { assertTrue(file.delete()) }
    }

    private fun assertUniformColumns(pixels: ByteArray, width: Int, columns: IntRange, color: Int) {
        for (y in 0 until pixels.size / (width * 4)) for (x in columns) {
            assertPixel(pixels, width, x, y, color)
        }
    }

    private fun assertPixel(pixels: ByteArray, width: Int, x: Int, y: Int, color: Int) {
        val at = (y * width + x) * 4
        assertEquals("red x=$x y=$y", (color ushr 16) and 255, pixels[at].toInt() and 255)
        assertEquals("green x=$x y=$y", (color ushr 8) and 255, pixels[at + 1].toInt() and 255)
        assertEquals("blue x=$x y=$y", color and 255, pixels[at + 2].toInt() and 255)
        assertEquals("alpha x=$x y=$y", 255, pixels[at + 3].toInt() and 255)
    }

    private fun page(): StoredPage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("engine-native-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(101, 1000, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff4386ca.toInt())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "native"), "1"), 0)
        return StoredPage(id, "1", file, file.length(), digest, PageDimensions(101, 1000), "image/png")
    }
}
