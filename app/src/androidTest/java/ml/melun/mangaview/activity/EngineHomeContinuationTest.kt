package ml.melun.mangaview.activity

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.ui.library.dismissAutomaticUpdateNotice
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real home-card tap selecting a different work from the most-recent prediction. Backup harness required. */
@RunWith(AndroidJUnit4::class)
class EngineHomeContinuationTest {
    @Test fun homeResumesAnotherSavedWorkAtItsExactAnchorUsingTheCommonRenderer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        fun episode(prefix: String) = EpisodeId(SeriesId(SourceId(requireNotNull(args.getString(prefix + "Source"))),
            requireNotNull(args.getString(prefix + "Series"))), requireNotNull(args.getString(prefix + "Episode")))
        val target = episode("capture")
        val predicted = episode("prediction")
        assertNotEquals(target.seriesId, predicted.seriesId)
        val title = "홈 이어보기 검증 · ${target.seriesId.sourceId.value.uppercase()}"
        val graph = (context.applicationContext as ViewerApplication).graph
        val output = File(context.getExternalFilesDir(null), "home-continuation-${System.currentTimeMillis()}")
            .apply { check(mkdir()) }
        val work = graph.engine.session(ViewerLaunchSpec(target.seriesId.sourceId, target.seriesId, target))
        val plan = graph.engine.coordinator.submit(work.episode(target, WorkPriority.FOCUS))
        val expected: SourceAnchor
        try {
            val manifest = plan.await()
            val page = graph.engine.coordinator.submit(work.page(manifest,
                manifest.manifest.pages[args.getString("openingPageIndex", "16").toInt()].id, WorkPriority.FOCUS))
            try {
                val stored = page.await()
                // A fractional source row catches accidental fallback to the less precise legacy offset.
                expected = SourceAnchor(stored.pageId,
                    stored.dimensions.heightPx * 9L / 10 * SourceAnchor.SOURCE_UNITS_PER_PIXEL + 0x12345678L, 0)
                val legacy = BigInteger.valueOf(expected.sourceYQ32)
                    .multiply(BigInteger.valueOf(context.resources.displayMetrics.widthPixels.toLong()))
                    .multiply(BigInteger.valueOf(SourceAnchor.SCREEN_UNITS_PER_PIXEL))
                    .divide(BigInteger.valueOf(stored.dimensions.widthPx.toLong()))
                    .divide(BigInteger.valueOf(SourceAnchor.SOURCE_UNITS_PER_PIXEL)).longValueExact()
                graph.userLibrary.recordOpened(target.seriesId, title, null, target)
                graph.engine.positions.save(expected, legacy)
            } finally { page.close(); page.awaitReleased() }
        } finally { plan.close(); plan.awaitReleased() }
        delay(5)
        graph.userLibrary.recordOpened(predicted.seriesId, "가장 최근 작품 · ${predicted.seriesId.sourceId.value.uppercase()}", null, predicted)
        withTimeout(5000) { graph.userLibrary.snapshot.first { it.recent.firstOrNull()?.episodeId == predicted } }
        val device = UiDevice.getInstance(instrumentation)
        var reader: ViewerActivity? = null
        val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
        var sampler: Thread? = null
        var deadlineCapture: Deferred<Result<Unit>>? = null
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            try {
                dismissAutomaticUpdateNotice(device)
                requireNotNull(device.wait(Until.findObject(By.desc("하단 홈")), 15_000)).click()
                assertNotNull(device.wait(Until.findObject(By.text("이어서 읽기")), 15_000))
                val preparedOwner = withTimeout(45_000) {
                    var value = graph.engine.renderers.preparedSnapshot()
                    while (value == null) { delay(10); value = graph.engine.renderers.preparedSnapshot() }
                    value
                }
                val prediction = withTimeout(45_000) {
                    var value = graph.engine.openings.preparedSnapshot()
                    while (value?.episodeId != predicted) { delay(25); value = graph.engine.openings.preparedSnapshot() }
                    value
                }
                assertEquals(0L, preparedOwner.ownership().textures)
                assertEquals(0L, preparedOwner.ownership().bytes)
                assertNotEquals(target, prediction.episodeId)
                dismissAutomaticUpdateNotice(device)
                assertTrue(device.takeScreenshot(File(output, "home.png")))
                val row = requireNotNull(device.wait(Until.findObject(By.desc("홈 이어보기 목록")), 10_000))
                row.scroll(Direction.RIGHT, 0.8f)
                val card = requireNotNull(device.wait(Until.findObject(By.desc("이어보기: $title")), 10_000))
                assertTrue(device.takeScreenshot(File(output, "home-selected-card.png")))
                val requested = System.nanoTime()
                args.getString("homeDeadlineCaptureMillis")?.toLong()?.let { captureMillis ->
                    require(captureMillis in 1..10_000)
                    deadlineCapture = async(Dispatchers.IO) {
                        runCatching {
                            val deadline = requested + captureMillis * 1_000_000L
                            while (System.nanoTime() < deadline) {
                                delay(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
                            }
                            val started = System.nanoTime()
                            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            val returned = System.nanoTime()
                            try {
                                // Bitmap return is a conservative visibility bound; PNG encoding is later.
                                File(output, "deadline-capture.json").writeText(JSONObject()
                                    .put("requestedAtNanos", requested).put("captureStartedAtNanos", started)
                                    .put("captureReturnedAtNanos", returned).put("scheduledAfterMillis", captureMillis)
                                    .put("returnedAfterMillis", (returned - requested) / 1e6)
                                    .put("sourcePixelsVerified", false).put("corpusCredit", 0).toString(2))
                                File(output, "deadline.png").outputStream().use {
                                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                                }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
                if (args.getString("homeDisplayDiagnostic") == "true") {
                    sampling.set(true)
                    sampler = Thread({
                        File(output, "opening-main-stacks.txt").bufferedWriter().use { writer ->
                            while (sampling.get()) {
                                writer.appendLine("atNanos=${System.nanoTime()} sinceClickMillis=${(System.nanoTime() - requested) / 1e6}")
                                val stack = android.os.Looper.getMainLooper().thread.stackTrace
                                stack.forEach {
                                    writer.appendLine(it.toString())
                                }
                                writer.appendLine()
                                writer.flush()
                                Thread.sleep(100)
                            }
                        }
                    }, "home-opening-stack-sampler").apply { start() }
                }
                card.click()
                reader = withTimeout(15_000) {
                    var found: ViewerActivity? = null
                    while (found == null) {
                        instrumentation.runOnMainSync {
                            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<ViewerActivity>().singleOrNull()
                        }
                        if (found == null) delay(10)
                    }
                    found
                }
                val activity = requireNotNull(reader)
                val frame = withTimeout(30_000) {
                    var value = activity.engineFramesSince(0).observations.firstOrNull {
                        it.presentation.swapSucceeded && it.presentation.scene.completeCoverage &&
                            it.presentation.scene.placements.isNotEmpty()
                    }
                    while (value == null) {
                        assertNull(activity.viewerFailureSnapshot())
                        delay(5)
                        value = activity.engineFramesSince(0).observations.firstOrNull {
                            it.presentation.swapSucceeded && it.presentation.scene.completeCoverage &&
                                it.presentation.scene.placements.isNotEmpty()
                        }
                    }
                    value.presentation
                }
                assertEquals("Home must use the exact saved source anchor", expected, frame.scene.anchor)
                assertEquals("The renderer must be reusable for a different work", preparedOwner.rendererId, frame.rendererId)
                assertEquals(preparedOwner.rendererEpoch, frame.identity.rendererEpoch)
                deadlineCapture?.await()?.getOrThrow()
                // Submission is separate from visibility. The captures show what was visible
                // when taken; the later screenshot is not a first-image timestamp.
                delay(1000)
                device.waitForIdle(1000)
                assertTrue("Reader surface is not the active accessibility window",
                    device.wait(Until.hasObject(By.desc("viewer-surface")), 5000))
                assertFalse("Home still owns the active window", device.hasObject(By.text("이어서 읽기")))
                device.dumpWindowHierarchy(File(output, "viewer-window.xml"))
                assertTrue(device.takeScreenshot(File(output, "viewer.png")))
                if (args.getString("homeDisplayDiagnostic") == "true") {
                    File(output, "window-dump.txt").writeText(device.executeShellCommand("dumpsys window windows"))
                    File(output, "surface-dump.txt").writeText(device.executeShellCommand("dumpsys SurfaceFlinger"))
                    delay(6000)
                    assertTrue(device.takeScreenshot(File(output, "viewer-after-six-seconds.png")))
                }
                File(output, "result.json").writeText(JSONObject()
                    .put("entry", "REAL_HOME_CONTINUATION_CARD_TAP")
                    .put("prediction", predicted.toString()).put("selected", target.toString())
                    .put("anchor", expected.toString()).put("rendererId", frame.rendererId)
                    .put("requestedAtNanos", requested)
                    .put("frames", org.json.JSONArray().also { records ->
                        activity.engineFramesSince(0).observations.forEach { observation ->
                            val value = observation.presentation
                            records.put(JSONObject().put("identity", value.identity.toString())
                                .put("rendererId", value.rendererId).put("eglFrameId", value.eglFrameId)
                                .put("submittedAtNanos", value.submittedAtNanos)
                                .put("renderLatencyNanos", value.renderLatencyNanos)
                                .put("swapSucceeded", value.swapSucceeded)
                                .put("completeCoverage", value.scene.completeCoverage)
                                .put("placementCount", value.scene.placements.size)
                                .put("timestampKind", value.timestampKind.toString())
                                .put("timestampNanos", value.timestampNanos))
                        }
                    })
                    .put("launchToCompleteSubmissionMillis", (frame.submittedAtNanos + frame.renderLatencyNanos - requested) / 1e6)
                    .put("physicalPresentationVerified", false).put("corpusCredit", 0).toString(2))
                // The actual close path also persists the same anchor.
                home.close()
                instrumentation.runOnMainSync { activity.finish() }
                withTimeout(30_000) { activity.awaitEngineClosed() }
                assertEquals(expected, graph.engine.positions.load(target))
            } catch (failure: Throwable) {
                device.takeScreenshot(File(output, "failure.png"))
                device.dumpWindowHierarchy(File(output, "failure-window.xml"))
                File(output, "failure-windows.txt").writeText(device.executeShellCommand("dumpsys window windows"))
                throw failure
            } finally {
                deadlineCapture?.cancelAndJoin()
                sampling.set(false)
                sampler?.join(2000)
                reader?.let { activity ->
                    instrumentation.runOnMainSync { activity.finish() }
                    withTimeout(30_000) { activity.awaitEngineClosed() }
                }
                graph.engine.openings.cancelPrediction()
                graph.engine.renderers.cancel()
            }
        }
        withTimeout(15_000) { while (graph.engine.coordinator.snapshot().subscribers != 0) delay(10) }
    }
}
