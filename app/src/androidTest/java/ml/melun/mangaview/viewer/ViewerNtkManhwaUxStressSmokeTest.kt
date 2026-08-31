package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkManhwaUxStressSmokeTest {
    @Test
    fun protectedManhwaHandlesTransientAuthorizationWithoutDelayingTheFirstPage() {
        ViewerUxTestHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "ntk-manhwa-ux-stress-smoke",
        ).run(
            LiveEpisode(
                sourceId = "ntk",
                seriesKey = "/manhwa/2778",
                episodeKey = "/manhwa/2778/10972",
            ),
        )
    }
}
