package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkNativeBandCachePolicyTest {
    @Test
    fun offscreenPageDeliveryKeepsCurrentImmutableBand() {
        assertFalse(
            NtkNativeBandCachePolicy.shouldInvalidateForPage(
                cachedIncludesPage = false,
                pageTop = 8_000f,
                pageHeight = 1_000f,
                bandOrigin = 0f,
                bandBottom = 6_932f,
            ),
        )
    }

    @Test
    fun intersectingOrPreviouslyRepresentedPageInvalidatesBand() {
        assertTrue(
            NtkNativeBandCachePolicy.shouldInvalidateForPage(
                cachedIncludesPage = false,
                pageTop = 6_500f,
                pageHeight = 1_000f,
                bandOrigin = 0f,
                bandBottom = 6_932f,
            ),
        )
        assertTrue(
            NtkNativeBandCachePolicy.shouldInvalidateForPage(
                cachedIncludesPage = true,
                pageTop = 8_000f,
                pageHeight = 1_000f,
                bandOrigin = 0f,
                bandBottom = 6_932f,
            ),
        )
    }
}
