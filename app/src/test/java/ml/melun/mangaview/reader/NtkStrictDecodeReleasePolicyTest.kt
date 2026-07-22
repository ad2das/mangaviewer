package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictDecodeReleasePolicyTest {
    @Test
    fun shortEpisodeRequiresEveryResidentPage() {
        assertEquals(8, NtkStrictDecodeReleasePolicy.releaseThreshold(8))
        assertFalse(NtkStrictDecodeReleasePolicy.shouldRelease(7, 8))
        assertTrue(NtkStrictDecodeReleasePolicy.shouldRelease(8, 8))
    }

    @Test
    fun longEpisodeReleasesTheWidePoolAfterABoundedRunway() {
        assertEquals(12, NtkStrictDecodeReleasePolicy.releaseThreshold(114))
        assertFalse(NtkStrictDecodeReleasePolicy.shouldRelease(11, 114))
        assertTrue(NtkStrictDecodeReleasePolicy.shouldRelease(12, 114))
        assertEquals(12, NtkStrictDecodeReleasePolicy.releaseThreshold(95))
        assertEquals(6, NtkStrictDecodeReleasePolicy.releaseThreshold(114, webtoon = true))
        assertFalse(NtkStrictDecodeReleasePolicy.shouldRelease(5, 114, webtoon = true))
        assertTrue(NtkStrictDecodeReleasePolicy.shouldRelease(6, 114, webtoon = true))
    }
}
