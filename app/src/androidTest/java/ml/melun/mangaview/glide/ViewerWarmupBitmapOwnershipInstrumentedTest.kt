package ml.melun.mangaview.glide

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWarmupBitmapOwnershipInstrumentedTest {
    @Test
    fun exclusiveConsumerCopyCanRetireWithoutRecyclingTheSharedCacheIdentity() {
        val shared = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(23, 67, 109))
        }
        val exclusive = checkNotNull(ViewerWarmupManager.copyBitmapForExclusiveConsumer(shared))

        try {
            assertNotSame(shared, exclusive)
            assertEquals(Bitmap.Config.ARGB_8888, exclusive.config)
            assertFalse(exclusive.isMutable)
            assertEquals(shared.getPixel(4, 7), exclusive.getPixel(4, 7))

            exclusive.recycle()
            assertTrue(exclusive.isRecycled)
            assertFalse("Reader retirement recycled the shared cache source", shared.isRecycled)
        } finally {
            if (!exclusive.isRecycled) exclusive.recycle()
            if (!shared.isRecycled) shared.recycle()
        }
    }

    @Test
    fun recycledCacheIdentityCannotCrossTheExclusiveOwnershipBoundary() {
        val shared = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        shared.recycle()

        assertNull(ViewerWarmupManager.copyBitmapForExclusiveConsumer(shared))
    }
}
