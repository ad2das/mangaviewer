package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWfwfUxStressSmokeTest {
    @Test
    fun realGesturesHomeAndFrameTimingRemainHealthy() {
        ViewerUxTestHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "wfwf-ux-stress-smoke",
        ).run(
            LiveEpisode(
                sourceId = "wfwf",
                seriesKey = "comic:10007",
                episodeKey = "28",
            ),
        )
    }
}
