package ml.melun.mangaview.activity

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Counterbalanced resume measurements; cached originals remain intact in both arms. */
@RunWith(AndroidJUnit4::class)
class EngineContinuationOpeningTest {
    @Test fun libraryAutomaticallyPreparesLastReadAndViewerReusesItsOpening() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val id = EpisodeId(SeriesId(SourceId(requireNotNull(args.getString("captureSource"))),
            requireNotNull(args.getString("captureSeries"))), requireNotNull(args.getString("captureEpisode")))
        val graph = (context.applicationContext as ViewerApplication).graph
        val output = File(context.getExternalFilesDir(null), "continuation-opening-${System.currentTimeMillis()}")
            .apply { check(mkdir()) }
        graph.userLibrary.recordOpened(id.seriesId, "Continuation verification", null, id)
        var expectedAnchor: ml.melun.mangaview.engine.api.SourceAnchor? = null
        args.getString("openingPageIndex")?.toInt()?.let { pageIndex ->
            val work = graph.engine.session(ViewerLaunchSpec(id.seriesId.sourceId, id.seriesId, id))
            val plan = graph.engine.coordinator.submit(work.episode(id, ml.melun.mangaview.engine.api.WorkPriority.FOCUS))
            try {
                val manifest = plan.await()
                val page = graph.engine.coordinator.submit(work.page(manifest, manifest.manifest.pages[pageIndex].id,
                    ml.melun.mangaview.engine.api.WorkPriority.FOCUS))
                try {
                    val stored = page.await()
                    val row = stored.dimensions.heightPx * 9L / 10
                    val anchor = ml.melun.mangaview.engine.api.SourceAnchor(stored.pageId,
                        row * ml.melun.mangaview.engine.api.SourceAnchor.SOURCE_UNITS_PER_PIXEL, 0)
                    expectedAnchor = anchor
                    val legacy = row * context.resources.displayMetrics.widthPixels *
                        ml.melun.mangaview.engine.api.SourceAnchor.SCREEN_UNITS_PER_PIXEL / stored.dimensions.widthPx
                    graph.engine.positions.save(anchor, legacy)
                } finally { page.close(); page.awaitReleased() }
            } finally { plan.close(); plan.awaitReleased() }
        }
        val records = JSONArray()
        for ((index, warm) in listOf(false, true, true, false).withIndex()) {
            graph.engine.openings.cancelPrediction()
            var library: ActivityScenario<MainActivity>? = null
            var viewer: ViewerActivity? = null
            val record = JSONObject().put("index", index).put("prepared", warm)
            try {
                if (warm) {
                    val preparationStarted = System.nanoTime()
                    library = ActivityScenario.launch(MainActivity::class.java)
                    val ready = withTimeout(45_000) {
                        var prepared = graph.engine.openings.preparedSnapshot()
                        while (prepared?.episodeId != id || prepared.pixelTiles == 0) {
                            delay(25)
                            prepared = graph.engine.openings.preparedSnapshot()
                        }
                        prepared
                    }
                    record.put("preparationMillis", (ready.readyAtNanos - preparationStarted) / 1e6)
                        .put("preparedOriginals", ready.originalPages).put("preparedTiles", ready.pixelTiles)
                        .put("preparedPixelBytes", ready.pixelBytes)
                    assertTrue(ready.pixelBytes in 1..32L * 1024 * 1024)
                }
                val requested = System.nanoTime()
                ActivityScenario.launch<ViewerActivity>(Intent(context, ViewerActivity::class.java).apply {
                    putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, id.seriesId.sourceId.value)
                    putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, id.seriesId.remoteKey)
                    putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, id.remoteKey)
                }).use { scenario ->
                    scenario.onActivity { viewer = it }
                    val activity = requireNotNull(viewer)
                    val frame = withTimeout(30_000) {
                        var first = activity.engineFramesSince(0).observations.firstOrNull {
                            it.presentation.swapSucceeded && it.presentation.scene.completeCoverage &&
                                it.presentation.scene.placements.isNotEmpty()
                        }
                        while (first == null) {
                            assertNull(activity.viewerFailureSnapshot())
                            delay(5)
                            first = activity.engineFramesSince(0).observations.firstOrNull {
                                it.presentation.swapSucceeded && it.presentation.scene.completeCoverage &&
                                    it.presentation.scene.placements.isNotEmpty()
                            }
                        }
                        first.presentation
                    }
                    record.put("launchToCompleteSubmissionMillis",
                        (frame.submittedAtNanos + frame.renderLatencyNanos - requested) / 1e6)
                        .put("anchor", frame.scene.anchor.toString())
                        .put("width", frame.scene.viewport.widthPx).put("height", frame.scene.viewport.heightPx)
                        .put("timestampKind", frame.timestampKind.name).put("physicalPresentationVerified", false)
                    assertEquals(id, frame.scene.anchor?.pageId?.episodeId)
                    expectedAnchor?.let { assertEquals("Saved mid-page position changed", it, frame.scene.anchor) }
                    assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(output, "opening-$index.png")))
                    // Close the library before the viewer so resume does not start a new prediction.
                    library?.close(); library = null
                }
            } finally {
                library?.close()
                viewer?.let { withTimeout(30_000) { it.awaitEngineClosed() } }
                graph.engine.openings.cancelPrediction()
            }
            withTimeout(15_000) {
                while (graph.engine.coordinator.snapshot().subscribers != 0) delay(10)
            }
            records.put(record)
            File(output, "measurements.json").writeText(JSONObject().put("scope", "RESUME_PREPARATION_COMPARISON")
                .put("entry", "DIRECT_VIEWER_INTENT_AFTER_REAL_LIBRARY_AUTOMATIC_PREPARATION")
                .put("corpusCredit", 0).put("records", records).toString(2))
        }
        val anchors = (0 until records.length()).map { records.getJSONObject(it).getString("anchor") }
        assertEquals("Opening preparation changed the saved source anchor", 1, anchors.distinct().size)
    }
}
