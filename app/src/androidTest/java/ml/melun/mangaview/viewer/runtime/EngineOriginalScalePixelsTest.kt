package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineOriginalScalePixelsTest {
    @Test fun gpuEnlargementPreservesSourceSamplingDuringForwardAndReverseOffsets() = runBlocking {
        verifyScale(SOURCE_WIDTH)
        verifyScale(DISPLAY_WIDTH)
    }

    private suspend fun verifyScale(displayWidth: Int) {
        val viewportHeight = if (displayWidth == SOURCE_WIDTH) SOURCE_HEIGHT else VIEWPORT_HEIGHT
        val page = page()
        val tile = EngineTileSpec(page.pageId, page.contentRevision, page.sha256,
            page.dimensions, 0, SOURCE_HEIGHT, displayWidth)
        val pixels = withContext(Dispatchers.IO) { NativeEngineImageDecoder().decode(page, tile) }
        val failures = mutableListOf<Throwable>()
        val owner = EngineSurfaceOwner(pixels.byteCount, {}, { failures += it }, {})
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(displayWidth, viewportHeight) }
        val surface = Surface(consumer)
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "engine-capture-original-scale-$displayWidth-${System.nanoTime()}").apply { check(mkdirs()) }
        page.file.copyTo(File(evidence, "original.png"))
        val pixelFailures = mutableListOf<AssertionError>()
        try {
            assertEquals(SOURCE_WIDTH.toLong() * SOURCE_HEIGHT * 4, pixels.byteCount)
            assertTrue(owner.attach(surface, displayWidth, viewportHeight, 60F))
            val texture = owner.upload(pixels, owner.rendererEpoch)
            assertEquals(pixels.byteCount, owner.ownership().bytes)
            val offsets = if (displayWidth == SOURCE_WIDTH) listOf(0) else listOf(0, 1, 47, 0)
            for ((index, offset) in offsets.withIndex()) {
                val bottom = SOURCE_HEIGHT * displayWidth * 1024 / SOURCE_WIDTH - offset * 1024
                val scene = EngineSurfaceScene(1, 1, index + 1L, 1,
                    EngineViewport(displayWidth, viewportHeight), null,
                    listOf(EngineTexturePlacement(texture, -offset * 1024, bottom)),
                    coordinateUnitsPerPixel = 1024, completeCoverage = true)
                val packet = withTimeout(10_000) { owner.capture(scene, 0, viewportHeight) }
                assertEquals(EngineReadbackPacket.Status.OK, packet.status)
                assertFalse(packet.physicalPresentationVerified)
                File(evidence, "offset-$index-$offset.rgba").writeBytes(packet.rgbaBytes)
                try { assertOriginalSamples(packet.rgbaBytes, offset, displayWidth, viewportHeight) }
                catch (failure: AssertionError) { pixelFailures += failure }
            }
            owner.clearScene()
            owner.release(texture)
            assertEquals(0L, owner.ownership().bytes)
            assertTrue(failures.isEmpty())
            if (pixelFailures.isNotEmpty()) throw pixelFailures.first()
        } finally {
            owner.close(); surface.release(); consumer.release(); pixels.close(); assertTrue(page.file.delete())
        }
    }

    private fun assertOriginalSamples(actual: ByteArray, offset: Int, displayWidth: Int, viewportHeight: Int) {
        assertEquals(displayWidth * viewportHeight * 4, actual.size)
        var totalError = 0L
        // Unscaled readback must preserve every source byte. The existing GL_LINEAR filter
        // uses finite precision during enlargement; constrain both its worst error and mean.
        val maximumError = if (displayWidth == SOURCE_WIDTH) 0 else 2
        for (y in 0 until viewportHeight) for (x in 0 until displayWidth) {
            val sx = (x + 0.5) * SOURCE_WIDTH / displayWidth - 0.5
            val sy = (y + offset + 0.5) * SOURCE_WIDTH / displayWidth - 0.5
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            val wx = sx - left
            val wy = sy - top
            for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
                fun sample(px: Int, py: Int) = (sourceColor(px.coerceIn(0, SOURCE_WIDTH - 1),
                    py.coerceIn(0, SOURCE_HEIGHT - 1)) ushr shift) and 255
                val upper = sample(left, top) * (1 - wx) + sample(left + 1, top) * wx
                val lower = sample(left, top + 1) * (1 - wx) + sample(left + 1, top + 1) * wx
                val expected = (upper * (1 - wy) + lower * wy).roundToInt()
                val observed = actual[(y * displayWidth + x) * 4 + channel].toInt() and 255
                totalError += abs(expected - observed)
                assertTrue("width=$displayWidth offset=$offset x=$x y=$y channel=$channel expected=$expected actual=$observed",
                    abs(expected - observed) <= maximumError)
            }
            assertEquals(255, actual[(y * displayWidth + x) * 4 + 3].toInt() and 255)
        }
        assertTrue("mean RGB error=$totalError/${displayWidth * viewportHeight * 3}",
            totalError.toDouble() / (displayWidth * viewportHeight * 3) < 0.1)
    }

    private fun page(): StoredPage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("engine-original-scale-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val values = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { sourceColor(it % SOURCE_WIDTH, it / SOURCE_WIDTH) }
            bitmap.setPixels(values, 0, SOURCE_WIDTH, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "gpu-original"), "1"), 0)
        return StoredPage(id, "1", file, file.length(), sha, PageDimensions(SOURCE_WIDTH, SOURCE_HEIGHT), "image/png")
    }

    private fun sourceColor(x: Int, y: Int): Int = (255 shl 24) or
        (((x * 31 + y * 17) % 256) shl 16) or (((x * 7 + y * 29) % 256) shl 8) or
        ((x * 19 + y * 11) % 256)

    private companion object {
        const val SOURCE_WIDTH = 101
        const val SOURCE_HEIGHT = 99
        const val DISPLAY_WIDTH = 150
        const val VIEWPORT_HEIGHT = 100
    }
}
