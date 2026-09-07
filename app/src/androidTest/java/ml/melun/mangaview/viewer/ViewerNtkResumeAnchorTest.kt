package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkResumeAnchorTest {
    @Test
    fun savedAnchorAndItsForwardPagesOwnResumeEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val episode = LiveEpisode("ntk", SERIES_KEY, EPISODE_KEY)
        val episodeId = EpisodeId(SeriesId(SourceId("ntk"), SERIES_KEY), EPISODE_KEY)
        val savedPage = PageId.at(episodeId, SAVED_ORDINAL)
        val application = instrumentation.targetContext.applicationContext as ViewerApplication
        runBlocking(Dispatchers.IO) {
            application.graph.userLibrary.saveProgress(savedPage, SAVED_OFFSET_UNITS)
        }

        val result = withProductionDetailWarmup(instrumentation, episode) {
            ViewerUxTestHarness(
                instrumentation,
                artifactPrefix = "ntk-resume-anchor-forward",
            ).run(episode)
        }

        val presented = requireNotNull(result.startupTiming?.presentedPageKey)
        val ordinal = presented.removePrefix("p").toInt()
        assertTrue(
            "Resume presented $presented instead of the saved anchor's forward neighborhood",
            ordinal in SAVED_ORDINAL..SAVED_ORDINAL + MAXIMUM_IMMEDIATE_GESTURE_ADVANCE,
        )
    }

    private companion object {
        const val SERIES_KEY = "/webtoon/57451201"
        const val EPISODE_KEY = "/webtoon/57451201/jjaptoon-1313625"
        const val SAVED_ORDINAL = 50
        const val SAVED_OFFSET_UNITS = 300_000L
        const val MAXIMUM_IMMEDIATE_GESTURE_ADVANCE = 16
    }
}
