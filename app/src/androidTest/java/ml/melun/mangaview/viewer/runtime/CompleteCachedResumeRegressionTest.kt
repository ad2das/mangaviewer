package ml.melun.mangaview.viewer.runtime

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.content.ContentPipelineSnapshot
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.viewer.FixedPx
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real descriptor pins, native pixels, and two complete runtime lifetimes; no corpus credit. */
@RunWith(AndroidJUnit4::class)
class CompleteCachedResumeRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun completeCacheRestoresDeepPixelsWithoutCallingTheSourceAndReleasesAllLeases() {
        val directory = File(instrumentation.targetContext.getExternalFilesDir("ux-evidence"),
            "complete-resume-${System.nanoTime()}").also { check(it.mkdirs()) }
        ActivityScenario.launch(CompleteResumeProbeActivity::class.java).use { scenario ->
            lateinit var probe: CompleteResumeProbeActivity
            scenario.onActivity { probe = it }
            probe.fixture.images.writeImages()
            awaitMain("Fixture host has no viewport") { probe.readyForCycle() }
            var first: Bitmap? = null
            try {
                instrumentation.runOnMainSync { probe.startCycle() }
                awaitReadable(probe, requireSnapshot = true)
                val initial = position(probe)
                assertEquals(probe.fixture.images.pages[2].id, initial.pageId)
                assertEquals(FixedPx.fromPixels(137).units, initial.offsetInPageUnits)
                val bounds = bounds(probe)
                first = capture(bounds)
                assertSourcePixels(first, initial, bounds)
                save(first, directory.resolve("first-normal-source-session.png"))
                assertEquals(1, probe.fixture.prepareCalls.get())
                assertEquals(1, probe.fixture.images.manifestCalls.get())
                assertEquals(probe.fixture.images.pages.size, probe.fixture.images.fetchCalls.get())
                closeAndCheck(probe)
                assertEquals(initial, probe.saved)

                // The persisted metadata and normally populated raw cache are the only input.
                probe.fixture.sourceAllowed = false
                instrumentation.runOnMainSync { probe.startCycle() }
                awaitReadable(probe, requireSnapshot = false)
                assertEquals(initial, position(probe))
                assertEquals(1, probe.fixture.leaseCount())
                assertTrue("No descriptors pin the complete episode",
                    probe.fixture.bodyDescriptorCount() >= probe.fixture.images.pages.size)
                assertPinnedBodySurvivesReplacement(probe)
                instrumentation.runOnMainSync { probe.recreateSurface() }
                awaitReadable(probe, requireSnapshot = false)
                val resumed = capture(bounds(probe))
                try {
                    assertSourcePixels(resumed, initial, bounds)
                    assertTrue("Cached resume changed actual viewport pixels", first.sameAs(resumed))
                    save(resumed, directory.resolve("second-complete-cache-session.png"))
                } finally { resumed.recycle() }
                assertEquals("Cached resume called prepare", 1, probe.fixture.prepareCalls.get())
                assertEquals("Cached resume called manifest", 1, probe.fixture.images.manifestCalls.get())
                assertEquals("Cached resume fetched a body", probe.fixture.images.pages.size,
                    probe.fixture.images.fetchCalls.get())
                closeAndCheck(probe)
                directory.resolve("regression.json").writeText(JSONObject()
                    .put("mode", "FIXTURE_REGRESSION_NO_CORPUS_CREDIT")
                    .put("position", initial.toString()).put("exactPixelsEqual", true)
                    .put("fixtureCacheEvictionReplacementAndSurfaceRecreation", true)
                    .put("sourcePrepareCalls", probe.fixture.prepareCalls.get())
                    .put("sourceManifestCalls", probe.fixture.images.manifestCalls.get())
                    .put("sourceFetchCalls", probe.fixture.images.fetchCalls.get())
                    .put("closedCycles", probe.terminalSnapshots.size)
                    .put("remainingBodyDescriptors", probe.fixture.bodyDescriptorCount())
                    .put("remainingLeaseDirectories", probe.fixture.leaseCount()).toString(2))
            } finally {
                first?.recycle()
                closeAndCheck(probe)
            }
        }
    }

    private fun assertPinnedBodySurvivesReplacement(probe: CompleteResumeProbeActivity) = runBlocking(Dispatchers.IO) {
        // Isolated regression fixture only. No production or final-corpus cache is touched.
        val fixture = probe.fixture
        val id = fixture.images.pages[2].id
        requireNotNull(fixture.snapshots().open(fixture.images.episode)).use { lease ->
            val original = lease.page(id).file.readBytes()
            val replacement = lease.page(fixture.images.pages[3].id).file.readBytes()
            fixture.cache.remove(id)
            assertArrayEquals("Eviction invalidated the open descriptor", original, lease.page(id).file.readBytes())
            val input = replacement.inputStream()
            fixture.cache.write(id, OpenedPage(object : PageByteStream {
                override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int =
                    input.read(destination, offset, byteCount)
                override fun close() = input.close()
            }, replacement.size.toLong(), "image/png", null, null))
            assertArrayEquals("Atomic replacement changed a leased positional page", original, lease.page(id).file.readBytes())
            assertNull("A changed normal-cache body must reject a new snapshot lease",
                fixture.snapshots().open(fixture.images.episode))
        }
    }

    private fun awaitReadable(probe: CompleteResumeProbeActivity, requireSnapshot: Boolean) {
        awaitMain("Fixture did not display complete cached/source pixels") {
            assertNoFailures(probe)
            val snapshot = requireNotNull(probe.runtime).resourceSnapshot()
            snapshot.pages.count { it.rawState == "Verified" } == probe.fixture.images.pages.size &&
                probe.frames.any { it.fullActualCoverage && it.anchorOrdinal == 2 } &&
                (!requireSnapshot || probe.fixture.snapshotCount() == 1)
        }
    }

    private fun closeAndCheck(probe: CompleteResumeProbeActivity) {
        lateinit var closed: CountDownLatch
        instrumentation.runOnMainSync { closed = probe.closeCycle() }
        assertTrue("Runtime cleanup did not complete", closed.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertNoFailures(probe)
            probe.terminalSnapshots.lastOrNull()?.let { snapshot ->
                assertTrue("Pipeline ownership survived close: $snapshot", quiet(snapshot))
                assertTrue(snapshot.pages.isEmpty())
            }
        }
        assertEquals("Snapshot leases survived runtime close", 0, probe.fixture.leaseCount())
        assertEquals("Body descriptors survived runtime close", 0, probe.fixture.bodyDescriptorCount())
    }

    private fun quiet(value: ContentPipelineSnapshot): Boolean = value.activeFetches == 0 &&
        value.activeDecodes == 0 && value.activeUploads == 0 && value.activeManifests == 0 &&
        value.retryWakeups == 0 && value.retiringPages.isEmpty()

    private fun position(probe: CompleteResumeProbeActivity): ReadingPosition {
        lateinit var value: ReadingPosition
        instrumentation.runOnMainSync { value = requireNotNull(probe.runtime?.chromeSnapshot()).position }
        return value
    }

    private fun bounds(probe: CompleteResumeProbeActivity): Rect {
        lateinit var value: Rect
        instrumentation.runOnMainSync { value = probe.screenBounds() }
        return value
    }

    private fun assertNoFailures(probe: CompleteResumeProbeActivity) {
        if (probe.failures.isNotEmpty()) throw AssertionError("Fixture runtime failed", probe.failures.first())
    }

    private fun assertSourcePixels(image: Bitmap, position: ReadingPosition, bounds: Rect) {
        assertTrue(137 + bounds.height() < bounds.width() * 4)
        for (y in listOf(image.height / 4, image.height / 2, image.height * 3 / 4)) {
            val row = ((position.offsetInPageUnits.toDouble() / FixedPx.UNITS_PER_PIXEL + y) *
                256 / bounds.width()).toInt()
            assertTrue(row % 128 in 2..125)
            assertEquals("Wrong source page or row at y=$y", SessionMemoryFixture.color(2, row),
                image.getPixel(image.width / 2, y))
        }
    }

    private fun capture(bounds: Rect): Bitmap {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val image = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            if (image === screenshot) requireNotNull(image.copy(Bitmap.Config.ARGB_8888, false)) else image
        } finally { screenshot.recycle() }
    }

    private fun save(image: Bitmap, file: File) = file.outputStream().use {
        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
    }

    private fun awaitMain(description: String, condition: () -> Boolean) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        lateinit var callback: Choreographer.FrameCallback
        callback = Choreographer.FrameCallback {
            try {
                when {
                    condition() -> done.countDown()
                    System.nanoTime() >= deadline -> throw AssertionError(description)
                    else -> Choreographer.getInstance().postFrameCallback(callback)
                }
            } catch (problem: Throwable) { failure.set(problem); done.countDown() }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        try {
            assertTrue(description, done.await(16, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
        }
    }
}
