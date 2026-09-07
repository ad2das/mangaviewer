package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.content.DecodeRequest
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.content.SourceRowRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCpuDecodeFormatsInstrumentedTest {
    @Test
    fun jpegPngAndWebpDecodeAtFullDisplayResolution() = runBlocking {
        val formats = listOf(
            "jpg" to Bitmap.CompressFormat.JPEG,
            "png" to Bitmap.CompressFormat.PNG,
            "webp" to Bitmap.CompressFormat.WEBP_LOSSLESS,
        )
        formats.forEach { (extension, format) ->
            val file = patternedImage(extension, format)
            val tile = NativeCpuDecodePort().decode(request(file)) as NativeCpuTileLease
            try {
                assertEquals(DISPLAY_WIDTH, tile.displayWidthPx)
                assertEquals(SOURCE_HEIGHT / 2, tile.sourceTopPx)
                assertEquals(SOURCE_HEIGHT, tile.sourceBottomPx)
                assertEquals(DISPLAY_WIDTH * DISPLAY_HEIGHT / 2 * 4L, tile.byteCount)
            } finally {
                tile.close()
                assertTrue(file.delete())
            }
        }
    }

    private fun patternedImage(extension: String, format: Bitmap.CompressFormat): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "native-decode-${System.nanoTime()}.$extension")
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { index ->
            val x = index % SOURCE_WIDTH
            val y = index / SOURCE_WIDTH
            0xff000000.toInt() or ((x * 255 / SOURCE_WIDTH) shl 16) or
                ((y * 255 / SOURCE_HEIGHT) shl 8) or ((x xor y) and 0xff)
        }
        bitmap.setPixels(pixels, 0, SOURCE_WIDTH, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(format, 100, output))
        }
        bitmap.recycle()
        return file
    }

    private fun request(file: File): DecodeRequest {
        val episode = EpisodeId(SeriesId(SourceId("test"), "formats"), "episode")
        val page = PageSpec(PageId.at(episode, 0), 0, PageDimensions(SOURCE_WIDTH, SOURCE_HEIGHT))
        return DecodeRequest(
            generation = 1L,
            page = page,
            encoded = EncodedPageRef(
                page.id,
                file.absolutePath,
                file.length(),
                "instrumented-format",
                requireNotNull(page.dimensions),
            ),
            dimensions = requireNotNull(page.dimensions),
            displayWidthPx = DISPLAY_WIDTH,
            sourceRange = SourceRowRange(SOURCE_HEIGHT / 2, SOURCE_HEIGHT),
            demandClass = DemandClass.VISIBLE,
        )
    }

    private companion object {
        const val SOURCE_WIDTH = 320
        const val SOURCE_HEIGHT = 640
        const val DISPLAY_WIDTH = 640
        const val DISPLAY_HEIGHT = 1_280
    }
}
