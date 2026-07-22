package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkGeneratedPageFormatPolicyTest {

    @Test
    fun exactCanonicalGifPageIsAFirstClassGeneratedImage() {
        assertTrue(
            ReaderImageCache.isCanonicalNtkGeneratedPageForTest(
                "https://booktoki8.org/manhwa/2640/5667/p002.gif",
            ),
        )
    }

    @Test
    fun gifAdmissionDoesNotBroadenEpisodeOrAssetAuthority() {
        assertFalse(
            ReaderImageCache.isCanonicalNtkGeneratedPageForTest(
                "https://booktoki8.org/manhwa/2640/5667/banner.gif",
            ),
        )
        assertFalse(
            ReaderImageCache.isCanonicalNtkGeneratedPageForTest(
                "https://booktoki8.org/cdn-cgi/challenge/p002.gif",
            ),
        )
        assertFalse(
            ReaderImageCache.isCanonicalNtkGeneratedPageForTest(
                "https://booktoki8.org/manhwa/2640/5667/p002.svg",
            ),
        )
    }
}
