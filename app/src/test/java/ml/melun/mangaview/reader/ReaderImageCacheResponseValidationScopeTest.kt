package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageCacheResponseValidationScopeTest {
    @Test
    fun ordinaryMangaCdnDoesNotEnterTheNtkAllowList() {
        assertFalse(
            ReaderImageCache.requiresTrustedNtkResponseValidationForTest(
                episodePath = null,
                requestUrl = "https://c11cm.net/10042/example_0.jpg",
            )
        )
    }

    @Test
    fun ntkEpisodeAlwaysKeepsStrictResponseValidation() {
        assertTrue(
            ReaderImageCache.requiresTrustedNtkResponseValidationForTest(
                episodePath = "/manhwa/123/456",
                requestUrl = "https://unexpected.example/image.jpg",
            )
        )
    }
}
