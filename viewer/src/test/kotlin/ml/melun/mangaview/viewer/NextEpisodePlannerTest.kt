package ml.melun.mangaview.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePlannerTest {
    private val planner = NextEpisodePlanner()
    private val reducer = ViewerFixtures.reducer()

    @Test
    fun preparesWhenForwardVelocityReachesTheTwelveSecondRunway() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
        )).state
        val velocity = opened.layout.totalHeight.units / 10L

        assertTrue(planner.shouldPrepare(opened.copy(velocityUnitsPerSecond = velocity)))
        assertFalse(planner.shouldPrepare(opened.copy(velocityUnitsPerSecond = -velocity)))
    }

    @Test
    fun preparesAtRestAfterEveryOriginalIsVerified() {
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(2L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state
        val verified = opened.replacePages(
            opened.pages.mapValues { (_, page) ->
                page.copy(encoded = VerifiedPageRef("cache", 1L, "sha"))
            },
        )

        assertTrue(planner.shouldPrepare(verified)
        )
    }
}
