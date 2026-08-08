package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiShortWebtoonTailMemoryBoundTest {
    @Test
    fun currentSuffixIsAdmittedAtTheBufferCapAndRejectedOneByteAboveIt() {
        val delivered = 1L * 1024L * 1024L
        val cap = NtkDirectWifiShortWebtoonProjectedTailPolicy.MAX_BUFFERED_SUFFIX_BYTES
        val tailPercent = 100L -
            NtkDirectWifiShortWebtoonProjectedTailPolicy.PRIMARY_SHARE_PERCENT
        // The partition uses floor(primary), so the suffix is ceil(remaining * tail% / 100).
        val exactCapRemaining = (cap - 1L) * 100L / tailPercent + 1L
        val aboveCapRemaining = cap * 100L / tailPercent + 1L

        val admitted = NtkDirectWifiShortWebtoonProjectedTailPolicy.disjointTailSegments(
            deliveredBytes = delivered,
            expectedLength = delivered + exactCapRemaining,
            maximumSuffixes = 1,
        )
        assertEquals(1, admitted.size)
        assertEquals(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.MAX_BUFFERED_SUFFIX_BYTES,
            admitted.single().last - admitted.single().first + 1L,
        )

        assertTrue(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.disjointTailSegments(
                deliveredBytes = delivered,
                expectedLength = delivered + aboveCapRemaining,
                maximumSuffixes = 1,
            ).isEmpty()
        )
        assertFalse(
            NtkDirectWifiShortWebtoonProjectedTailPolicy.shouldStart(
                bodyElapsedMs = NtkDirectWifiShortWebtoonProjectedTailPolicy.MIN_SAMPLE_MS,
                deliveredBytes = delivered,
                expectedLength = delivered + aboveCapRemaining,
                maximumSuffixes = 1,
            )
        )
    }
}
