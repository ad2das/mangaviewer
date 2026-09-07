package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.content.DecodeRequest
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.DemandClass
import ml.melun.mangaview.viewer.session.SceneQuad
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.content.SourceRowRange
import ml.melun.mangaview.viewer.session.VisualKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnedRendererCadenceTest {
    @Test
    fun gpuUploadDoesNotStopSurfaceScrolling() {
        val mode = InstrumentationRegistry.getArguments().getString("probeGeometryMode") ?: "static"
        require(mode == "streaming" || mode == "static")
        val launch = Intent(InstrumentationRegistry.getInstrumentation().targetContext, OwnedRendererProbeActivity::class.java)
            .putExtra("probeGeometryMode", mode)
        ActivityScenario.launch<OwnedRendererProbeActivity>(launch).use { scenario ->
            val activity = AtomicReference<OwnedRendererProbeActivity>()
            scenario.onActivity(activity::set)
            val probe = requireNotNull(activity.get())
            assertTrue(probe.awaitSurface())
            val encoded = createEncodedPage(probe)
            val first = decodeAndUpload(probe, encoded)
            scenario.onActivity { it.start(scene(first.texture.key)) }
            SystemClock.sleep(2_000L)

            val second = decodeAndUpload(probe, encoded)
            scenario.onActivity { it.start(scene(second.texture.key)) }
            SystemClock.sleep(5_000L)

            lateinit var diagnosticFinished: java.util.concurrent.CountDownLatch
            scenario.onActivity { diagnosticFinished = it.finishDiagnosticCapture() }
            assertTrue("Diagnostic export did not finish", diagnosticFinished.await(10L, java.util.concurrent.TimeUnit.SECONDS))

            val observations = probe.presentationSnapshot()
            Log.i("OwnedRendererTest", "geometryMode=$mode timestampKinds=${observations.groupingBy { it.timestampKind }.eachCount()}")
            Log.i("OwnedRendererTest", "nativeRenderP95Ms=${observations.map { it.renderLatencyNanos }.sorted().percentile(0.95) / 1_000_000.0}")
            // Closing the fixture cancels pending submissions with a zero timestamp.
            // Neither those cancellations nor composition latches are display times.
            val presentations = observations.filter { it.timestampKind != PresentationTimestampKind.CANCELLED }
                .sortedBy { it.presentedAtNanos }
            assertTrue("Actual display timestamp unavailable: ${presentations.map { it.timestampKind }.distinct()}",
                presentations.isNotEmpty() && presentations.all {
                    it.timestampKind == PresentationTimestampKind.DISPLAY_PRESENT &&
                        it.presentedAtNanos > 0L && it.presentedAtNanos >= it.submittedAtNanos
                })
            val vsyncIntervals = probe.vsyncSnapshot().sortedArray().asList()
                .zipWithNext { left, right -> right - left }
                .filter { it > 0L }
            val intervals = presentations.map { it.presentedAtNanos }
                .zipWithNext { left, right -> right - left }
                .filter { it > 0L }
            val missed = intervals.count { it >= 25_000_000L }
            val ordered = intervals.sorted()
            Log.i("OwnedRendererTest", buildString {
                append("samples=").append(intervals.size)
                append(" p50Ms=").append(ordered.percentile(0.50) / 1_000_000.0)
                append(" p95Ms=").append(ordered.percentile(0.95) / 1_000_000.0)
                append(" maxMs=").append(ordered.lastOrNull()?.div(1_000_000.0))
                append(" stalls=").append(intervals.count { it >= 100_000_000L })
                append(" decodeMs=").append(second.decodeNanos / 1_000_000.0)
                append(" uploadMs=").append(second.uploadNanos / 1_000_000.0)
                append(" renderP95Ms=").append(
                    presentations.map { it.renderLatencyNanos }.sorted().percentile(0.95) /
                        1_000_000.0,
                )
                append(" submitToPresentP95Ms=").append(
                    presentations.map { it.presentedAtNanos - it.submittedAtNanos }
                        .sorted().percentile(0.95) / 1_000_000.0,
                )
                append(" vsyncMissed=").append(vsyncIntervals.count { it >= 25_000_000L })
                append('/').append(vsyncIntervals.size)
                append(" topMs=").append(ordered.takeLast(30).map { it / 1_000_000.0 })
            })
            assertTrue("Too few physical presentations: ${intervals.size}", intervals.size >= 240)
            assertEquals("Physical presentation stalls >=100ms", 0, intervals.count {
                it >= 100_000_000L
            })
            assertTrue("Missed-frame ratio $missed/${intervals.size}", missed * 100 < intervals.size)
            probe.uploadPort.release(first.texture)
            probe.uploadPort.release(second.texture)
        }
    }

    private fun createEncodedPage(probe: OwnedRendererProbeActivity): EncodedPageRef {
        val dimensions = PageDimensions(1_080, 1_920)
        val file = File(probe.cacheDir, "owned-renderer-probe.jpg")
        val bitmap = Bitmap.createBitmap(dimensions.widthPx, dimensions.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        repeat(30) { band ->
            paint.color = Color.rgb(
                (37 * band + 41) % 256,
                (83 * band + 97) % 256,
                (149 * band + 23) % 256,
            )
            canvas.drawRect(
                0.0F,
                (band * 64).toFloat(),
                dimensions.widthPx.toFloat(),
                ((band + 1) * 64).toFloat(),
                paint,
            )
        }
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
        return EncodedPageRef(pageId(), file.absolutePath, file.length(), "probe", dimensions)
    }

    private fun decodeAndUpload(
        probe: OwnedRendererProbeActivity,
        encoded: EncodedPageRef,
    ): TimedTexture = runBlocking {
        val request = DecodeRequest(
            1L,
            PageSpec(encoded.pageId, 0, encoded.dimensions),
            encoded,
            encoded.dimensions,
            1_080,
            SourceRowRange(0, encoded.dimensions.heightPx),
            DemandClass.VISIBLE,
        )
        val decodeStarted = System.nanoTime()
        val pixels = probe.decodePort.decode(request)
        val decodeNanos = System.nanoTime() - decodeStarted
        val uploadStarted = System.nanoTime()
        val texture = probe.uploadPort.upload(probe.rendererEpoch, pixels)
        TimedTexture(texture, decodeNanos, System.nanoTime() - uploadStarted)
    }

    private fun scene(key: Long): SceneSnapshot {
        val pageId = pageId()
        val pageHeight = FixedPx.fromPixels(1_920)
        return SceneSnapshot(
            generation = 1L,
            lifecycleEpoch = 1L,
            sceneRevision = key,
            geometryRevision = 1L,
            viewportRevision = 0L,
            windowId = 0L,
            localOrigin = FixedPx.ZERO,
            scrollOffset = FixedPx.ZERO,
            contentHeight = FixedPx.fromPixels(7_680),
            quads = List(4) { index ->
                SceneQuad(
                    pageId,
                    FixedPx(pageHeight.units * index),
                    pageHeight,
                    0,
                    1_920,
                    1_920,
                    VisualKey(key),
                )
            },
        )
    }

    private fun pageId(): PageId = PageId.at(
        EpisodeId(SeriesId(SourceId("ntk"), "probe"), "episode"),
        0,
    )

    private fun List<Long>.percentile(fraction: Double): Long =
        if (isEmpty()) 0L else get(((lastIndex * fraction).toInt()).coerceIn(indices))

    private data class TimedTexture(
        val texture: ml.melun.mangaview.content.TextureRef,
        val decodeNanos: Long,
        val uploadNanos: Long,
    )
}
