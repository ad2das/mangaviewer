package ml.melun.mangaview.engine.runtime

import kotlin.math.floor
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFlingHorizonTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "hysteresis"), "1")

    /** Feeds the sample stream a fling produces at 25 ms frame steps. */
    private class LeadDriver(private val episode: EpisodeId) {
        var now = 0L
        private var position = 0.0
        val horizon = SessionFlingHorizon { now }

        fun sample(pagesPerSecond: Double) {
            now += STEP_NANOS
            position += pagesPerSecond * (STEP_NANOS / NANOS_PER_SECOND)
            val ordinal = floor(position).toInt()
            horizon.readingVelocity.onSample(episode, ordinal, position - ordinal, now)
        }

        fun idle() {
            now += STEP_NANOS
        }

        fun lead(): Int = horizon.lead(true)

        fun velocity(): Double = horizon.readingVelocity.pagesPerSecondAt(now)

        private companion object {
            const val STEP_NANOS = 25_000_000L
            const val NANOS_PER_SECOND = 1_000_000_000.0
        }
    }

    @Test
    fun oscillatingVelocityAroundTheLeadBoundaryAppliesASingleDeepening() {
        val driver = LeadDriver(episode)
        repeat(24) { driver.sample(12.3) } // computed lead 12: the immediate deepening
        val leads = mutableListOf(driver.lead())
        var minVelocity = driver.velocity()
        repeat(4) {
            repeat(12) { // 300 ms dip whose computed lead would read 11
                driver.sample(12.1)
                minVelocity = minOf(minVelocity, driver.velocity())
                leads += driver.lead()
            }
            repeat(12) { // and back above the boundary
                driver.sample(12.3)
                minVelocity = minOf(minVelocity, driver.velocity())
                leads += driver.lead()
            }
        }
        assertTrue("the oscillation must cross the ceil boundary: $minVelocity", minVelocity < 12.222)
        assertEquals("a dip shorter than the hold must not move the applied depth",
            listOf(12), leads.distinct())
    }

    @Test
    fun sustainedSlowdownLowersTheLeadOnlyAfterTheHold() {
        val driver = LeadDriver(episode)
        repeat(24) { driver.sample(12.3) }
        assertEquals(12, driver.lead())

        repeat(40) { driver.sample(12.1) } // the dip is younger than the hold window
        assertEquals("a dip younger than the hold must not move the lead", 12, driver.lead())

        repeat(40) { driver.sample(12.1) } // the dip now outlasts the hold
        assertEquals("the lead drops to the dip's own depth", 11, driver.lead())
        assertNotEquals("the lead must not fall straight to the floor",
            PAGES_AHEAD_WHILE_INTERACTING, driver.lead())
    }

    @Test
    fun aStaleVelocityDecaysTheLeadThroughTheSameHold() {
        val driver = LeadDriver(episode)
        repeat(24) { driver.sample(12.3) }
        assertEquals(12, driver.lead())

        repeat(16) { driver.idle(); driver.lead() } // 400 ms: the trailing window expires and v reads zero
        assertEquals("the expired window alone must not lower the lead", 12, driver.lead())

        repeat(40) { driver.idle(); driver.lead() } // the zero-velocity dip is still inside the hold
        assertEquals("the hold still protects the lead just before it expires", 12, driver.lead())

        repeat(8) { driver.idle(); driver.lead() } // the dip now outlasts the hold
        assertEquals(PAGES_AHEAD_WHILE_INTERACTING, driver.lead())
    }
}
