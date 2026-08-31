package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkTenEpisodeAutoAppendTest {
    @Test
    fun tenConsecutiveProtectedEpisodesAppendUnderContinuousRealGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val seriesKey = arguments.getString(ARG_SERIES_KEY) ?: DEFAULT_SERIES_KEY
        val episodeKey = arguments.getString(ARG_EPISODE_KEY) ?: DEFAULT_EPISODE_KEY
        val artifactPrefix = arguments.getString(ARG_ARTIFACT_PREFIX)
            ?: "ntk-ten-episode-auto-append"
        val requiredEpisodes = arguments.getString(ARG_REQUIRED_EPISODES)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_REQUIRED_EPISODES
        ViewerTenEpisodeAutoAppendHarness(
            instrumentation = instrumentation,
            artifactPrefix = artifactPrefix,
            requiredEpisodes = requiredEpisodes,
        ).run(
            LiveEpisode(
                sourceId = "ntk",
                seriesKey = seriesKey,
                episodeKey = episodeKey,
            ),
        )
    }

    private companion object {
        const val ARG_SERIES_KEY = "ntkSeriesKey"
        const val ARG_EPISODE_KEY = "ntkEpisodeKey"
        const val ARG_ARTIFACT_PREFIX = "ntkArtifactPrefix"
        const val ARG_REQUIRED_EPISODES = "ntkRequiredEpisodes"
        const val DEFAULT_REQUIRED_EPISODES = 10
        const val DEFAULT_SERIES_KEY = "/webtoon/57451201"
        const val DEFAULT_EPISODE_KEY = "/webtoon/57451201/jjaptoon-1313625"
    }
}
