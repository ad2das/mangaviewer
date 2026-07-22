package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageCacheTailFlightPolicyTest {
    @Test
    fun visibleRequestReplacesTailOwnerThatHasNotStartedTransport() {
        assertTrue(ReaderImageCache.supersedeProgressiveTailForTest(true, false))
    }

    @Test
    fun visibleRequestPreservesAnAlreadyStartedResponseBody() {
        assertFalse(ReaderImageCache.supersedeProgressiveTailForTest(true, true))
        assertFalse(ReaderImageCache.supersedeProgressiveTailForTest(false, false))
    }
}
