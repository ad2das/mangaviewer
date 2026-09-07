package ml.melun.mangaview.viewer.runtime

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Choreographer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.content.ContentPipelineSnapshot
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.QualificationMemory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual attached GL/pipeline lifecycle regression; no live-site corpus or timing qualification. */
@RunWith(AndroidJUnit4::class)
class SessionMemoryPressureRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cycles = JSONArray()

    @Test fun trimPreservesVisiblePixelsAndThreeCloseCyclesReleaseAllWork() {
        val directory = File(instrumentation.targetContext.getExternalFilesDir("ux-evidence"),
            "session-memory-${System.nanoTime()}").also { check(it.mkdirs()) }
        val memory = QualificationMemory(instrumentation, directory)
        var testFailure: Throwable? = null
        ActivityScenario.launch(SessionMemoryProbeActivity::class.java).use { scenario ->
            lateinit var probe: SessionMemoryProbeActivity
            scenario.onActivity { probe = it }
            probe.fixture.writeImages()
            awaitMain("Empty host was not laid out") { probe.readyForCycle() }
            try {
                repeat(3) { cycle -> verifyCycle(probe, directory, memory, cycle) }
                assertEquals(3, probe.fixture.manifestCalls.get())
                val violations = memory.finish()
                assertTrue(violations.joinToString("\n"), violations.isEmpty())
            } catch (problem: Throwable) {
                testFailure = problem
                throw problem
            } finally {
                lateinit var closed: CountDownLatch
                instrumentation.runOnMainSync { closed = probe.closeCycle() }
                check(closed.await(15, TimeUnit.SECONDS)) { "Fixture cleanup did not complete" }
                val memoryErrors = memory.finish()
                directory.resolve("regression.json").writeText(JSONObject()
                    .put("mode", "FIXTURE_REGRESSION_NO_CORPUS_CREDIT")
                    .put("cycles", cycles).put("fixtureManifestCalls", probe.fixture.manifestCalls.get())
                    .put("fixtureFetchCalls", probe.fixture.fetchCalls.get())
                    .put("memoryBaseline", "First complete close; identical fixture files and empty host in all three cycles")
                    .put("testFailure", testFailure?.stackTraceToString())
                    .put("memoryViolations", JSONArray(memoryErrors))
                    .put("failures", JSONArray(probe.failures.map(Throwable::stackTraceToString))).toString(2))
            }
        }
    }

    private fun verifyCycle(probe: SessionMemoryProbeActivity, directory: File,
        memory: QualificationMemory, cycle: Int) {
        instrumentation.runOnMainSync { probe.startCycle() }
        val current = probe.fixture.pages[2].id
        val distant = probe.fixture.pages.last().id
        awaitMain("Fixture never displayed current and retained offscreen images") {
            assertNoFailures(probe)
            val snapshot = requireNotNull(probe.runtime).resourceSnapshot()
            snapshot.pages.any { it.pageId == distant && it.residentTextureCount > 0 } &&
                probe.frames.any { it.fullActualCoverage && it.anchorOrdinal == 2 }
        }
        lateinit var before: ContentPipelineSnapshot
        lateinit var trimmed: ContentPipelineSnapshot
        lateinit var position: ReadingPosition
        lateinit var bounds: Rect
        instrumentation.runOnMainSync {
            val runtime = requireNotNull(probe.runtime)
            before = runtime.resourceSnapshot()
            position = requireNotNull(runtime.chromeSnapshot()).position
            bounds = probe.screenBounds()
        }
        assertEquals(current, position.pageId)
        assertEquals(FixedPx.fromPixels(137).units, position.offsetInPageUnits)
        val pixels = capture(bounds)
        try {
            assertSourcePixels(pixels, position, bounds)
            save(pixels, directory.resolve("cycle-$cycle-before.png"))
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                probe.application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            }
            awaitMain("Memory pressure did not retire all offscreen residents") {
                assertNoFailures(probe)
                val snapshot = requireNotNull(probe.runtime).resourceSnapshot()
                quiet(snapshot) && snapshot.pages.all { it.pageId == current || it.residentTextureCount == 0 } &&
                    snapshot.pages.any { it.pageId == current && it.residentTextureCount > 0 }
            }
            instrumentation.runOnMainSync { trimmed = requireNotNull(probe.runtime).resourceSnapshot() }
            verifyPreserved(probe, pixels, position, bounds, directory, cycle)
        } finally { pixels.recycle() }
        if (cycle > 0) memory.capture("active")
        finishCycle(probe, before, trimmed, memory, cycle)
    }

    private fun verifyPreserved(probe: SessionMemoryProbeActivity, before: Bitmap,
        position: ReadingPosition, bounds: Rect, directory: File, cycle: Int) {
        instrumentation.runOnMainSync {
            assertEquals("Pressure moved the reading position", position,
                requireNotNull(probe.runtime).chromeSnapshot()?.position)
        }
        val after = capture(bounds)
        try {
            save(after, directory.resolve("cycle-$cycle-after.png"))
            assertTrue("Pressure changed displayed viewport pixels", before.sameAs(after))
            assertSourcePixels(after, position, bounds)
        } finally { after.recycle() }
    }

    private fun finishCycle(probe: SessionMemoryProbeActivity, before: ContentPipelineSnapshot,
        trimmed: ContentPipelineSnapshot, memory: QualificationMemory, cycle: Int) {
        lateinit var closed: CountDownLatch
        instrumentation.runOnMainSync { closed = probe.closeCycle() }
        assertTrue("Session closeAndJoin/renderer destruction did not finish", closed.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            val terminal = probe.terminalSnapshots.last()
            assertTrue("Terminal work remains: $terminal", quiet(terminal))
            assertEquals("Terminal resident pages remain", 0, terminal.pages.sumOf { it.residentTextureCount })
            assertTrue("Terminal page records remain", terminal.pages.isEmpty())
            assertTrue("Terminal retiring work remains", terminal.retiringPages.isEmpty())
            assertNoFailures(probe)
            cycles.put(JSONObject().put("cycle", cycle + 1)
                .put("beforeTrim", snapshotJson(before)).put("afterTrim", snapshotJson(trimmed))
                .put("terminal", snapshotJson(terminal)))
        }
        memory.capture(if (cycle == 0) "before-viewer" else "after-viewer")
        if (cycle > 0) {
            val violations = memory.finish()
            assertTrue(violations.joinToString("\n"), violations.isEmpty())
        }
    }

    private fun quiet(value: ContentPipelineSnapshot): Boolean = value.activeFetches == 0 &&
        value.activeDecodes == 0 && value.activeUploads == 0 && value.activeManifests == 0 &&
        value.retryWakeups == 0 && value.retiringPages.isEmpty()

    private fun snapshotJson(value: ContentPipelineSnapshot) = JSONObject()
        .put("activeFetches", value.activeFetches).put("activeDecodes", value.activeDecodes)
        .put("activeUploads", value.activeUploads).put("activeManifests", value.activeManifests)
        .put("retryWakeups", value.retryWakeups).put("retiringPages", value.retiringPages.size)
        .put("residentTextures", value.pages.sumOf { it.residentTextureCount })

    private fun assertNoFailures(probe: SessionMemoryProbeActivity) {
        if (probe.failures.isNotEmpty()) throw AssertionError("Fixture runtime failed", probe.failures.first())
    }

    private fun assertSourcePixels(image: Bitmap, position: ReadingPosition, bounds: Rect) {
        assertTrue("Fixture viewport spills beyond the intended page",
            137 + bounds.height() < bounds.width() * 4)
        for (y in listOf(image.height / 4, image.height / 2, image.height * 3 / 4)) {
            val sourceRow = ((position.offsetInPageUnits.toDouble() / FixedPx.UNITS_PER_PIXEL + y) *
                256 / bounds.width()).toInt()
            assertTrue("Expected-pixel sample lies on a filtered stripe edge", sourceRow % 128 in 2..125)
            assertEquals("Wrong fixture source pixels at y=$y", SessionMemoryFixture.color(2, sourceRow),
                image.getPixel(image.width / 2, y))
        }
    }

    private fun capture(bounds: Rect): Bitmap {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val clipped = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            if (clipped === screenshot) requireNotNull(clipped.copy(Bitmap.Config.ARGB_8888, false)) else clipped
        } finally { screenshot.recycle() }
    }

    private fun save(bitmap: Bitmap, file: File) = file.outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }

    /** Observe a condition on real UI frames; no sleep, GC request, or settling padding. */
    private fun awaitMain(description: String, condition: () -> Boolean) {
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        lateinit var callback: Choreographer.FrameCallback
        callback = Choreographer.FrameCallback {
            try {
                when {
                    condition() -> complete.countDown()
                    System.nanoTime() >= deadline -> throw AssertionError(description)
                    else -> Choreographer.getInstance().postFrameCallback(callback)
                }
            } catch (problem: Throwable) { failure.set(problem); complete.countDown() }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        try {
            assertTrue(description, complete.await(16, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
        }
    }
}
