package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineTileSpec
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
