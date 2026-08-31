package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.viewer.PixelBand
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.PixelTileRef
import ml.melun.mangaview.viewer.nativebridge.ViewerNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImageFormatsInstrumentedTest {
    @Test
    fun jpegPngAndWebpDecodeThroughTheProductionNativePipeline() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until HEIGHT) {
                val color = when (y / BAND_HEIGHT) {
                    0 -> Color.RED
                    1 -> Color.GREEN
                    else -> Color.BLUE
                }
                for (x in 0 until WIDTH) setPixel(x, y, color)
            }
        }
        val pool = NativeTilePool(HardwareTileStore())
        try {
            formats().forEach { fixture ->
                val file = File(cache, "native-format-${System.nanoTime()}.${fixture.extension}")
                try {
                    FileOutputStream(file).use { output ->
                        assertTrue(bitmap.compress(fixture.format, QUALITY, output))
                    }
                    val fullPage = pool.decodeBand(
                        file,
                        PageDimensions(WIDTH, HEIGHT),
                        PixelBand(0, HEIGHT, WIDTH),
                    )
                    assertTileDimensions(fullPage, WIDTH, HEIGHT)
                    assertDominant(fullPage, 1, 1, Channel.RED)
                    assertDominant(fullPage, 1, BAND_HEIGHT + 1, Channel.GREEN)
                    assertDominant(fullPage, 1, HEIGHT - 2, Channel.BLUE)
                    pool.recycle(fullPage)

                    val middleBand = pool.decodeBand(
                        file,
                        PageDimensions(WIDTH, HEIGHT),
                        PixelBand(BAND_HEIGHT, BAND_HEIGHT * 2, HALF_WIDTH),
                    )
                    assertTileDimensions(middleBand, HALF_WIDTH, BAND_HEIGHT / 2)
                    assertDominant(middleBand, 0, 0, Channel.GREEN)
                    assertDominant(middleBand, HALF_WIDTH - 1, BAND_HEIGHT / 2 - 1, Channel.GREEN)
                    assertEquals(-1L, readPixel(fullPage.tiles.single(), 0, 0))
                    pool.recycle(middleBand)
                } finally {
                    file.delete()
                }
            }
        } finally {
            pool.close()
            bitmap.recycle()
        }
    }

    private fun assertTileDimensions(pixel: PixelRef, width: Int, height: Int) {
        val tile = pixel.tiles.single()
        assertEquals(width, tile.displayWidthPx)
        assertEquals(height, tile.displayHeightPx)
        assertTrue(tile.allocationBytes > 0L)
        assertTrue(readPixel(tile, width - 1, height - 1) >= 0L)
        assertEquals(-1L, readPixel(tile, width, height - 1))
        assertEquals(-1L, readPixel(tile, width - 1, height))
    }

    private fun assertDominant(pixel: PixelRef, x: Int, y: Int, channel: Channel) {
        val tile = pixel.tiles.single()
        val argb = readPixel(tile, x, y)
        assertTrue("Published tile pixel was unavailable", argb >= 0L)
        val red = (argb shr 16 and 0xff).toInt()
        val green = (argb shr 8 and 0xff).toInt()
        val blue = (argb and 0xff).toInt()
        val values = listOf(red, green, blue)
        assertTrue("Expected $channel to dominate but decoded RGB was $values", values[channel.index] >= 160)
        values.forEachIndexed { index, value ->
            if (index != channel.index) assertTrue("Decoded RGB was $values", value <= 100)
        }
    }

    private fun readPixel(
        tile: PixelTileRef,
        x: Int,
        y: Int,
    ): Long {
        if (x !in 0 until tile.displayWidthPx || y !in 0 until tile.displayHeightPx) return -1L
        return ViewerNativeBridge.nativeReadPublishedTilePixelArgb(
            tile.handle,
            tile.contentVersion,
            x,
            y,
        )
    }

    private fun formats(): List<ImageFixture> = listOf(
        ImageFixture("jpg", Bitmap.CompressFormat.JPEG),
        ImageFixture("png", Bitmap.CompressFormat.PNG),
        ImageFixture("webp", Bitmap.CompressFormat.WEBP_LOSSLESS),
    )

    private data class ImageFixture(
        val extension: String,
        val format: Bitmap.CompressFormat,
    )

    private enum class Channel(val index: Int) {
        RED(0),
        GREEN(1),
        BLUE(2),
    }

    private companion object {
        const val WIDTH = 32
        const val HALF_WIDTH = WIDTH / 2
        const val BAND_HEIGHT = 16
        const val HEIGHT = BAND_HEIGHT * 3
        const val QUALITY = 100
    }
}
