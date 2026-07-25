package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageDimensionPolicyTest {
    @Test
    fun acceptsAuthoritativeFullWidthShortStrip() {
        assertTrue(ReaderImageCache.hasUsableImageDimensionsForTest(690, 30))
        assertTrue(ReaderImageCache.hasUsableImageDimensionsForTest(30, 690))
    }

    @Test
    fun rejectsTrackingPixelsAndCorruptSlivers() {
        assertFalse(ReaderImageCache.hasUsableImageDimensionsForTest(1, 1))
        assertFalse(ReaderImageCache.hasUsableImageDimensionsForTest(4096, 1))
        assertFalse(ReaderImageCache.hasUsableImageDimensionsForTest(63, 63))
    }

    @Test
    fun preservesNormalImageAcceptance() {
        assertTrue(ReaderImageCache.hasUsableImageDimensionsForTest(690, 1600))
        assertTrue(ReaderImageCache.hasUsableImageDimensionsForTest(64, 64))
    }
}
