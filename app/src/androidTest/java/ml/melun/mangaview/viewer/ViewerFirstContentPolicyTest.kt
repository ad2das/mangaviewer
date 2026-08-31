package ml.melun.mangaview.viewer

import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerFirstContentPolicyTest {
    @Test
    fun totalOverLimitPassesOnlyWhenTheMeasuredAppTailRemainsStrict() {
        assertNull(ViewerFirstContentPolicy.violation(6_000L, 4_000L, timing(
            verified = 5_000_000_000L,
            decoded = 5_100_000_000L,
            presented = 5_200_000_000L,
        )))
        assertNotNull(ViewerFirstContentPolicy.violation(6_000L, 4_000L, timing(
            verified = 5_000_000_000L,
            decoded = 5_350_000_000L,
            presented = 5_400_000_000L,
        )))
    }

    @Test
    fun totalUnderLimitDoesNotRequireAttribution() {
        assertNull(ViewerFirstContentPolicy.violation(3_999L, 4_000L, null))
    }

    @Test
    fun missingAttributionCannotExcuseAnOverLimitFrame() {
        assertNotNull(ViewerFirstContentPolicy.violation(4_001L, 4_000L, null))
    }

    private fun timing(
        verified: Long,
        decoded: Long,
        presented: Long,
    ) = ViewerStartupTiming(
        presentedPageKey = "p0001",
        openStartedAtNanos = 1L,
        manifestReadyAtNanos = 2L,
        initialResponseStartedAtNanos = 3L,
        initialVerifiedAtNanos = verified,
        initialDecodedAtNanos = decoded,
        firstActualPresentedAtNanos = presented,
    )
}
