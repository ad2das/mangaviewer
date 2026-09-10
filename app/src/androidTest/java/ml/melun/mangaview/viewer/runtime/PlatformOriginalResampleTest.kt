package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device control for the platform target-size resample filter used by original rasters. */
@RunWith(AndroidJUnit4::class)
class PlatformOriginalResampleTest {
    @Test fun targetSizeDecodeUsesPixelCenterLinearSampling() {
        verifyTargetSize(SOURCE_WIDTH, SOURCE_HEIGHT, 1080, 722, "scale-0.8012")
        verifyTargetSize(SOURCE_WIDTH, SOURCE_HEIGHT, 674, 450, "scale-0.5")
    }

    private fun verifyTargetSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int,
        label: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("platform-resample-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        try {
            val values = IntArray(sourceWidth * sourceHeight) { sourceColor(it % sourceWidth, it / sourceWidth) }
            bitmap.setPixels(values, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            assertEquals(targetWidth, decoded.width)
            assertEquals(targetHeight, decoded.height)
            val pixels = IntArray(targetWidth * targetHeight)
            decoded.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            val evidence = File(context.getExternalFilesDir(null), "platform-resample-control")
            evidence.mkdirs()
            source.copyTo(File(evidence, "$label-source.png"), overwrite = true)
            val rgba = ByteArray(targetWidth * targetHeight * 4)
            for (index in pixels.indices) {
                val color = pixels[index]
                rgba[index * 4] = (color shr 16 and 255).toByte()
                rgba[index * 4 + 1] = (color shr 8 and 255).toByte()
                rgba[index * 4 + 2] = (color and 255).toByte()
                rgba[index * 4 + 3] = (color ushr 24 and 255).toByte()
            }
            File(evidence, "$label.rgba").writeBytes(rgba)
            File(evidence, "$label.json").writeText(
                """{"sourceWidth":$sourceWidth,"sourceHeight":$sourceHeight,""" +
                    """"targetWidth":$targetWidth,"targetHeight":$targetHeight,"filter":"pixel-center-linear"}""")
            var totalError = 0L
            var maximumError = 0
            for (y in 0 until targetHeight) for (x in 0 until targetWidth) {
                val sx = (x + 0.5) * sourceWidth / targetWidth - 0.5
                val sy = (y + 0.5) * sourceHeight / targetHeight - 0.5
                val left = floor(sx).toInt()
                val top = floor(sy).toInt()
                val wx = sx - left
                val wy = sy - top
                for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
                    fun sample(px: Int, py: Int) = (sourceColor(px.coerceIn(0, sourceWidth - 1),
                        py.coerceIn(0, sourceHeight - 1)) ushr shift) and 255
                    val upper = sample(left, top) * (1 - wx) + sample(left + 1, top) * wx
                    val lower = sample(left, top + 1) * (1 - wx) + sample(left + 1, top + 1) * wx
                    val expected = (upper * (1 - wy) + lower * wy).roundToInt()
                    val observed = rgba[(y * targetWidth + x) * 4 + channel].toInt() and 255
                    totalError += abs(expected - observed)
                    if (abs(expected - observed) > maximumError) maximumError = abs(expected - observed)
                }
                assertEquals(255, rgba[(y * targetWidth + x) * 4 + 3].toInt() and 255)
            }
            val mean = totalError.toDouble() / (targetWidth * targetHeight * 3)
            assertTrue("$label maximumError=$maximumError", maximumError <= 2)
            assertTrue("$label meanError=$mean", mean < 0.1)
        } finally {
            decoded.recycle()
            source.delete()
        }
    }

    private fun sourceColor(x: Int, y: Int): Int = (255 shl 24) or
        (((x * 31 + y * 17) % 256) shl 16) or (((x * 7 + y * 29) % 256) shl 8) or
        ((x * 19 + y * 11) % 256)

    private companion object {
        const val SOURCE_WIDTH = 1348
        const val SOURCE_HEIGHT = 900
    }
}
