package ml.melun.mangaview.activity

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineViewerActivityTest {
    @Test fun normalViewerAcceptsImmediateInputAndShowsItsRealEpisodePicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val source = arguments.getString("engineSource", "wfwf")
        val series = arguments.getString("engineSeries", "comic:10001")
        val episode = arguments.getString("engineEpisode", "1")
        val output = File(context.getExternalFilesDir(null), "engine-main-${System.currentTimeMillis()}").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        var viewer: ViewerActivity? = null
        var failure: Throwable? = null
        try {
            ActivityScenario.launch<ViewerActivity>(Intent(context, ViewerActivity::class.java).apply {
                putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, source)
                putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, series)
                putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use { scenario ->
                scenario.onActivity { viewer = it }
                assertTrue(device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 4, 30))
                val activity = requireNotNull(viewer)
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (activity.viewerEngineFrameSnapshot()?.let { it.swapSucceeded && it.scene.completeCoverage } != true) {
                    activity.viewerFailureSnapshot()?.let { throw AssertionError("Main viewer failed", it) }
                    check(SystemClock.elapsedRealtime() < deadline) { "Main viewer did not fill the viewport" }
                    SystemClock.sleep(10)
                }
                assertTrue(requireNotNull(activity.viewerEngineSnapshot()).session.inputRevision > 0)
                assertTrue(device.takeScreenshot(File(output, "content.png")))
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                assertTrue(device.wait(Until.hasObject(By.text("회차")), 5_000))
                assertTrue(device.takeScreenshot(File(output, "toolbar.png")))
                requireNotNull(device.findObject(By.text("회차"))).click()
                assertTrue(activity.episodePickerFailureSnapshot()?.stackTraceToString() ?: "Episode picker did not open",
                    device.wait(Until.hasObject(By.text("회차 선택")), 30_000))
                assertTrue(device.takeScreenshot(File(output, "episodes.png")))
                requireNotNull(device.findObject(By.text("취소"))).click()
                activity.viewerFailureSnapshot()?.let { throw AssertionError("Main viewer failed", it) }
            }
            runBlocking {
                withTimeout(30_000) { requireNotNull(viewer).awaitEngineClosed() }
                val graph = (context.applicationContext as ViewerApplication).graph.engine
                val work = graph.coordinator.snapshot()
                assertEquals(0, work.subscribers + work.active + work.retiring + work.queued + work.retainedResults)
                val storage = graph.storageOwnership()
                assertEquals(0, storage.fileLeases + storage.preparedPages + storage.pendingPublications)
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            File(output, "report.json").writeText(JSONObject().apply {
                put("source", source); put("series", series); put("episode", episode)
                put("failure", failure?.stackTraceToString())
                put("episodePickerFailure", viewer?.episodePickerFailureSnapshot()?.stackTraceToString())
                put("normalViewer", true); put("corpusCredit", 0); put("physicalPresentationVerified", false)
                put("inputRevision", viewer?.viewerEngineSnapshot()?.session?.inputRevision)
                put("anchor", viewer?.viewerEngineSnapshot()?.session?.anchor.toString())
                put("startup", viewer?.viewerStartupTimingSnapshot().toString())
            }.toString(2))
        }
    }
}
