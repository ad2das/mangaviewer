package ml.melun.mangaview.viewer

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNtkSmokeTest {
    @Test
    fun protectedEpisodeOpensWithoutBlockingTheFirstGesture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val startedAt = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch<ViewerActivity>(Intent(context, ViewerActivity::class.java).apply {
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, "ntk")
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, "/webtoon/57451201")
            putExtra(
                ViewerLaunchSpec.EXTRA_EPISODE_KEY,
                "/webtoon/57451201/jjaptoon-1341148",
            )
        })
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(ViewerUiConditions.waitForSurface(device, 5_000L))
            val width = device.displayWidth
            val height = device.displayHeight
            assertTrue(device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 24))
            assertTrue(
                "A protected NTK image frame was not presented",
                device.wait(Until.hasObject(By.descStartsWith(FRAME_PREFIX)), 25_000L),
            )
            val frame = requireNotNull(device.findObject(By.descStartsWith(FRAME_PREFIX)))
            val presentedAt = requireNotNull(frame.contentDescription)
                .substringAfter(FRAME_PREFIX)
                .toLong()
            val firstContentMillis = presentedAt - startedAt
            var startupViolation: String? = null
            scenario.onActivity { activity ->
                startupViolation = ViewerFirstContentPolicy.violation(
                    firstContentMillis,
                    FIRST_CONTENT_LIMIT_MILLIS,
                    activity.viewerStartupTimingSnapshot(),
                )
            }
            assertTrue(startupViolation ?: "First-content policy failed", startupViolation == null)
            assertFalse("Viewer reported an NTK source failure", device.hasObject(By.desc("viewer-failure")))
            assertNotNull(frame)
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val FRAME_PREFIX = "viewer-frame-presented:"
        const val FIRST_CONTENT_LIMIT_MILLIS = 4_000L
    }
}
