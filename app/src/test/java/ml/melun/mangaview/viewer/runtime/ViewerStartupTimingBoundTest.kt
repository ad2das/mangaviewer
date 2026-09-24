package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerStartupTimingBoundTest {
    @Test
    fun trackedPagesStopGrowingAtTheBound() {
        val tracker = ViewerStartupTracker()
        val episode = EpisodeId(SeriesId(SourceId("test"), "timing"), "1")
        (0 until 300).forEach { tracker.markDecoded(PageId.at(episode, it), it + 1L) }
        assertEquals(256, tracker.trackedPageCount())
    }
}
