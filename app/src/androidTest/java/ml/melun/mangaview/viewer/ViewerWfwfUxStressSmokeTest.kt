package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWfwfUxStressSmokeTest {
    @Test
    fun realGesturesHomeAndFrameTimingRemainHealthy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        ViewerUxTestHarness(
            instrumentation = instrumentation,
            artifactPrefix = arguments.getString(ARG_ARTIFACT_PREFIX)
                ?: "wfwf-ux-stress-smoke",
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
        const val ARG_ARTIFACT_PREFIX = "artifactPrefix"
    }
}
