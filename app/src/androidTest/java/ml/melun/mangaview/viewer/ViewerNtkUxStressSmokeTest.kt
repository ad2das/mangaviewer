package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkUxStressSmokeTest {
    @Test
    fun protectedEpisodePassesTheSameStrictUxGate() {
        ViewerUxTestHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "ntk-ux-stress-smoke",
        ).run(
            LiveEpisode(
                sourceId = "ntk",
                seriesKey = "/webtoon/57451201",
                episodeKey = "/webtoon/57451201/jjaptoon-1341148",
            ),
        )
    }
}
