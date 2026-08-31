package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkHeavyUxStressSmokeTest {
    @Test
    fun heavyProtectedWebtoonKeepsDecodeWorkOffTheMotionCriticalPath() {
        ViewerUxTestHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "ntk-heavy-ux-stress-smoke",
        ).run(
            LiveEpisode(
                sourceId = "ntk",
                seriesKey = "/webtoon/16972",
                episodeKey = "/webtoon/16972/1516431",
            ),
        )
    }
}
