package ml.melun.mangaview.viewer.runtime

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineViewerLiveProbeTest {
    @Test fun realEpisodeRendersThroughTheNewEngineAfterImmediateUiInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val source = arguments.getString("engineSource", "wfwf")
        val series = arguments.getString("engineSeries", "comic:10001")
        val episode = arguments.getString("engineEpisode", "1")
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val start = System.nanoTime()
        val output = File(context.getExternalFilesDir(null), "engine-live-${System.currentTimeMillis()}").apply { mkdirs() }
        val intent = Intent(context, EngineViewerProbeActivity::class.java)
            .putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, source)
            .putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, series)
            .putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: EngineViewerProbeActivity? = null
        try {
            ActivityScenario.launch<EngineViewerProbeActivity>(intent).use { scenario ->
                scenario.onActivity { activity = it }
                // Input starts before any image/document readiness condition is awaited.
                assertTrue(device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 4, 30))
                val viewer = requireNotNull(activity)
                assertTrue("No rendered image within observation window", viewer.awaitImage(30))
                viewer.failure()?.let { throw AssertionError("New engine live failure", it) }
                assertTrue(viewer.firstImageAtNanos > 0)
                assertTrue("Viewport did not become fully covered", viewer.awaitViewport(30))
                viewer.failure()?.let { throw AssertionError("New engine viewport failure", it) }
                assertTrue(viewer.firstViewportAtNanos > 0)
                assertTrue(requireNotNull(viewer.latest).session.inputRevision > 0)
                assertTrue(requireNotNull(viewer.lastFrame).swapSucceeded)
                device.takeScreenshot(File(output, "screen.png"))
            }
            assertTrue("Session resource close did not finish", requireNotNull(activity).awaitClosed(30))
            activity!!.failure()?.let { throw AssertionError("New engine close failure", it) }
            runBlocking {
                val graph = (context.applicationContext as ViewerApplication).graph.engine
                val work = graph.coordinator.snapshot()
                assertEquals(0, work.subscribers)
                assertEquals(0, work.active + work.queued + work.retiring + work.retainedResults)
                val storage = graph.storageOwnership()
                assertEquals(0, storage.fileLeases + storage.preparedPages + storage.pendingPublications)
            }
        } finally {
            val viewer = activity
            File(output, "report.json").writeText(JSONObject().apply {
                put("source", source); put("series", series); put("episode", episode)
                put("firstSubmittedImageMillis", viewer?.firstImageAtNanos?.takeIf { it > 0 }?.let { (it - start) / 1_000_000.0 })
                put("firstSubmittedViewportMillis", viewer?.firstViewportAtNanos?.takeIf { it > 0 }?.let { (it - start) / 1_000_000.0 })
                put("failure", viewer?.failure()?.stackTraceToString())
                put("inputRevision", viewer?.latest?.session?.inputRevision)
                put("pendingInputCount", viewer?.latest?.session?.pendingInputCount)
                put("timestampKind", viewer?.lastFrame?.timestampKind?.name)
                put("anchor", viewer?.latest?.session?.anchor.toString())
                put("physicalPresentationVerified", false); put("corpusCredit", 0)
            }.toString(2))
        }
    }
}
