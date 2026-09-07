package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStartupTimingTest {
    @Test
    fun fallbackTimestampCannotClaimFirstImageLatency() {
        val tracker = ViewerStartupTracker()
        val page = PageId.at(EpisodeId(SeriesId(SourceId("test"), "series"), "episode"), 0)
        tracker.markOpenStarted(100L)
        for (kind in PresentationTimestampKind.entries) {
            if (kind == PresentationTimestampKind.DISPLAY_PRESENT) continue
            tracker.markPresented(page, 200L, 300L, kind)
            assertTrue(tracker.needsPresentation())
            assertNull(tracker.snapshot()?.firstActualPresentedAtNanos)
        }
        tracker.markPresented(page, 400L, 500L, PresentationTimestampKind.DISPLAY_PRESENT)
        assertFalse(tracker.needsPresentation())
        assertEquals(500L, tracker.snapshot()?.firstActualPresentedAtNanos)
    }

    @Test
    fun presentationCanOnlyClaimPixelsDecodedBeforeItsSubmission() {
        val tracker = ViewerStartupTracker()
        val page = PageId.at(EpisodeId(SeriesId(SourceId("test"), "series"), "episode"), 3)

        tracker.markDecoded(page, 200L)

        assertFalse(tracker.wasDecodedBy(page, 199L))
        assertTrue(tracker.wasDecodedBy(page, 200L))
        assertTrue(tracker.wasDecodedBy(page, 300L))
    }
}
