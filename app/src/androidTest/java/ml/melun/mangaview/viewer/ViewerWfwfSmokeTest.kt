package ml.melun.mangaview.viewer

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerWfwfSmokeTest {
    @Test
    fun liveEpisodeOpensAndAcceptsARealSwipe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val startedAt = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch<ViewerActivity>(Intent(context, ViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, "wfwf")
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, "comic:10007")
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, "28")
        })
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(ViewerUiConditions.waitForSurface(device, 5_000L))
            val width = device.displayWidth
            val height = device.displayHeight
            assertTrue(device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 24))
            val firstFrameFound = device.wait(
                Until.hasObject(By.descStartsWith(FRAME_DESCRIPTION_PREFIX)),
                20_000L,
            )

            assertTrue("A real image frame was not presented", firstFrameFound)
            val firstFrame = requireNotNull(device.findObject(By.descStartsWith(FRAME_DESCRIPTION_PREFIX)))
            val contentBounds = firstFrame.visibleBounds
            assertTrue("Viewer must fill the safe width", contentBounds.left == 0 && contentBounds.right == width)
            assertTrue("Viewer must stay below the status bar", contentBounds.top > 0)
            assertTrue("Viewer must stay above the navigation bar", contentBounds.bottom < height)
            val presentedAt = requireNotNull(firstFrame.contentDescription)
                .substringAfter(FRAME_DESCRIPTION_PREFIX)
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
            assertTrue(device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 24))
            assertTrue(device.wait(Until.gone(By.desc("viewer-loading")), 2_000L))
            assertFalse("Viewer reported a source failure", device.hasObject(By.desc("viewer-failure")))
            assertNotNull(device.findObject(By.descStartsWith(FRAME_DESCRIPTION_PREFIX)))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), SCREENSHOT_NAME)))
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val FRAME_DESCRIPTION_PREFIX = "viewer-frame-presented:"
        const val FIRST_CONTENT_LIMIT_MILLIS = 4_000L
        const val SCREENSHOT_NAME = "wfwf-safe-width-smoke.png"
    }
}
