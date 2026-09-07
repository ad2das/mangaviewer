package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWfwfTenEpisodeAutoAppendTest {
    @Test
    fun tenConsecutiveEpisodesAppendUnderContinuousRealGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        ViewerTenEpisodeAutoAppendHarness(
            instrumentation = instrumentation,
            artifactPrefix = arguments.getString(ARG_ARTIFACT_PREFIX)
                ?: "wfwf-ten-episode-auto-append",
            requiredEpisodes = arguments.getString(ARG_REQUIRED_EPISODES)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_REQUIRED_EPISODES,
        ).run(
            LiveEpisode(
                sourceId = "wfwf",
                seriesKey = arguments.getString(ARG_SERIES_KEY) ?: "comic:10007",
                episodeKey = arguments.getString(ARG_EPISODE_KEY) ?: "28",
            ),
        )
    }

    private companion object {
        const val ARG_SERIES_KEY = "wfwfSeriesKey"
        const val ARG_EPISODE_KEY = "wfwfEpisodeKey"
        const val ARG_ARTIFACT_PREFIX = "wfwfArtifactPrefix"
        const val ARG_REQUIRED_EPISODES = "wfwfRequiredEpisodes"
        const val DEFAULT_REQUIRED_EPISODES = 10
    }
}
