package ml.melun.mangaview.reader

import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSurfaceViewTransitionCardInstrumentedTest {
    @Test
    fun transitionCardHasFullSizeOpaqueNativeBacking() {
        lateinit var bitmapPixels: IntArray
        var bitmapWidth = 0
        var bitmapHeight = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ReaderSurfaceView(
                ApplicationProvider.getApplicationContext()
            )
            val widthSpec = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, 600, 1000)
            view.setPageCount(1)
            view.setPageCard(0, "다음 회차: 408화")

            assertFalse(
                "A structural card must not occupy the real work-image bitmap slot",
                view.pageImageBitmapPresentForTest(0)
            )
            val bitmap = view.pageCardBackingBitmapForTest(0)
            assertNotNull("Native renderer requires a Bitmap-backed transition card", bitmap)
            bitmapWidth = bitmap!!.width
            bitmapHeight = bitmap.height
            bitmapPixels = intArrayOf(
                bitmap.getPixel(0, 0),
                bitmap.getPixel(bitmap.width - 1, 0),
                bitmap.getPixel(0, bitmap.height - 1),
                bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
            )
        }

        assertEquals(600, bitmapWidth)
        assertEquals(ReaderSurfaceView.transitionCardPageHeightForTest().toInt(), bitmapHeight)
        bitmapPixels.forEach { pixel ->
            assertEquals("Every transition-card edge must be opaque", 255, Color.alpha(pixel))
            assertEquals("No previous image may show through the card edge", Color.BLACK, pixel)
        }
    }
}
