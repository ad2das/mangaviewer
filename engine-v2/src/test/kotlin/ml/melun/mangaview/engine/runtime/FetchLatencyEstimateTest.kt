package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchLatencyEstimateTest {
    private val page = PageId.at(EpisodeId(SeriesId(SourceId("test"), "latency"), "1"), 0)
    private val other = PageId.at(page.episodeId, 1)
    private val second = 1_000_000_000L

    @Test
    fun startsAtSixHundredMilliseconds() {
        assertEquals(0.6, FetchLatencyEstimate().seconds, 1e-9)
    }

    @Test
    fun networkCompletionMovesTheAverage() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 0L)
        estimate.network(page)
        estimate.complete(page, second)
        assertEquals(0.68, estimate.seconds, 1e-9)
    }

    @Test
    fun storageHitsNeverFeedTheAverage() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 0L)
        estimate.complete(page, second)
        assertEquals(0.6, estimate.seconds, 1e-9)
    }

    @Test
    fun completionWithoutADemandIsIgnored() {
        val estimate = FetchLatencyEstimate()
        estimate.complete(other, 10 * second)
        assertEquals(0.6, estimate.seconds, 1e-9)
    }

    @Test
    fun firstDemandInstantWins() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 0L)
        estimate.observe(page, 500_000_000L)
        estimate.network(page)
        estimate.complete(page, second)
        assertEquals(0.68, estimate.seconds, 1e-9)
    }

    @Test
    fun averageStaysInsideItsBounds() {
        val slow = FetchLatencyEstimate()
        repeat(50) {
            slow.observe(page, 0L)
            slow.network(page)
            slow.complete(page, 10 * second)
        }
        assertEquals(3.0, slow.seconds, 1e-9)

        val fast = FetchLatencyEstimate()
        repeat(50) {
            fast.observe(page, 0L)
            fast.network(page)
            fast.complete(page, 10_000_000L)
        }
        assertEquals(0.2, fast.seconds, 1e-9)
    }

    @Test
    fun abandonDropsTheDemand() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 0L)
        estimate.abandon(page)
        estimate.network(page)
        estimate.complete(page, 10 * second)
        assertEquals(0.6, estimate.seconds, 1e-9)
    }

    @Test
    fun retainKeepsOnlyWantedPages() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 0L)
        estimate.observe(other, 0L)
        estimate.retain(setOf(other))
        estimate.network(page)
        estimate.complete(page, second)
        assertEquals(0.6, estimate.seconds, 1e-9)
        estimate.network(other)
        estimate.complete(other, second)
        assertEquals(0.68, estimate.seconds, 1e-9)
    }

    @Test
    fun nonPositiveIntervalsAreIgnored() {
        val estimate = FetchLatencyEstimate()
        estimate.observe(page, 2 * second)
        estimate.network(page)
        estimate.complete(page, second)
        assertEquals(0.6, estimate.seconds, 1e-9)
    }
}
