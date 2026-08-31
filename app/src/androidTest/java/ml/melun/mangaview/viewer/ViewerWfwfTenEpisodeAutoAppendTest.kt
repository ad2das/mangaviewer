package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWfwfTenEpisodeAutoAppendTest {
    @Test
    fun tenConsecutiveEpisodesAppendUnderContinuousRealGestures() {
        ViewerTenEpisodeAutoAppendHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "wfwf-ten-episode-auto-append",
        ).run(
            LiveEpisode(
                sourceId = "wfwf",
                seriesKey = "comic:10007",
                episodeKey = "28",
            ),
        )
    }
}
