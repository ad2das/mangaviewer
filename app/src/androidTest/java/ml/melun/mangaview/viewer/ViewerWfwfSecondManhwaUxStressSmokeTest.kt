package ml.melun.mangaview.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for the comic episode that exposed unpaced cold-entry tile publication. */
@RunWith(AndroidJUnit4::class)
class ViewerWfwfSecondManhwaUxStressSmokeTest {
    @Test
    fun coldEntryPixelsAreCommittedBeforeMoreDecodeWorkIsPublished() {
        ViewerUxTestHarness(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            artifactPrefix = "wfwf-second-manhwa-ux-stress-smoke",
        ).run(
            LiveEpisode(
                sourceId = "wfwf",
                seriesKey = "comic:10017",
                episodeKey = "74",
            ),
        )
    }
}
