package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkProductionWarmEntryTest {
    @Test
    fun detailScreenWarmupMakesImmediateHeavyWebtoonEntryResponsive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val episode = LiveEpisode(
            sourceId = "ntk",
            seriesKey = arguments.getString(ARG_SERIES_KEY) ?: DEFAULT_SERIES_KEY,
            episodeKey = arguments.getString(ARG_EPISODE_KEY) ?: DEFAULT_EPISODE_KEY,
        )
        withProductionDetailWarmup(instrumentation, episode) {
            ViewerUxTestHarness(
                instrumentation = instrumentation,
                artifactPrefix = arguments.getString(ARG_ARTIFACT_PREFIX)
                    ?: "ntk-production-warm-entry",
            ).run(episode)
        }
    }

    private companion object {
        const val ARG_SERIES_KEY = "ntkSeriesKey"
        const val ARG_EPISODE_KEY = "ntkEpisodeKey"
        const val ARG_ARTIFACT_PREFIX = "artifactPrefix"
        const val DEFAULT_SERIES_KEY = "/webtoon/16972"
        const val DEFAULT_EPISODE_KEY = "/webtoon/16972/1516431"
    }
}
