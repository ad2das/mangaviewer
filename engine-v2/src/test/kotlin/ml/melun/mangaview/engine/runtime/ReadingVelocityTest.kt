package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingVelocityTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "velocity"), "1")
    private val next = episode.copy(remoteKey = "2")

    @Test
    fun forwardMovementMeasuresPagesPerSecond() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 0, 0.0, 0L)
        velocity.onSample(episode, 1, 0.0, 125_000_000L)
        velocity.onSample(episode, 2, 0.0, 250_000_000L)
        velocity.onSample(episode, 3, 0.0, 375_000_000L)
        assertEquals(8.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun reversalReportsZero() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 3, 0.0, 0L)
        velocity.onSample(episode, 2, 0.0, 125_000_000L)
        velocity.onSample(episode, 1, 0.0, 250_000_000L)
        assertEquals(0.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun stillAnchorReportsZero() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 4, 0.25, 0L)
        velocity.onSample(episode, 4, 0.25, 125_000_000L)
        velocity.onSample(episode, 4, 0.25, 250_000_000L)
        assertEquals(0.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun tallPageFractionMovesAtTheSamePagesPerSecond() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 5, 0.0, 0L)
        velocity.onSample(episode, 5, 0.5, 125_000_000L)
        velocity.onSample(episode, 5, 1.0, 250_000_000L)
        assertEquals(4.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun trailingWindowSlidesAfterAPause() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 0, 0.0, 0L)
        velocity.onSample(episode, 1, 0.0, 125_000_000L)
        velocity.onSample(episode, 2, 0.0, 250_000_000L)
        velocity.onSample(episode, 3, 0.0, 375_000_000L)
        assertEquals(8.0, velocity.pagesPerSecond, 1e-9)
        velocity.onSample(episode, 3, 0.0, 1_000_000_000L)
        assertEquals(0.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun episodeChangeRestartsTheWindow() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 0, 0.0, 0L)
        velocity.onSample(episode, 1, 0.0, 125_000_000L)
        velocity.onSample(episode, 2, 0.0, 250_000_000L)
        velocity.onSample(next, 0, 0.0, 375_000_000L)
        assertEquals(0.0, velocity.pagesPerSecond, 1e-9)
    }

    @Test
    fun resetClearsTheEstimate() {
        val velocity = ReadingVelocity()
        velocity.onSample(episode, 0, 0.0, 0L)
        velocity.onSample(episode, 1, 0.0, 125_000_000L)
        velocity.reset()
        assertEquals(0.0, velocity.pagesPerSecond, 1e-9)
    }
}
