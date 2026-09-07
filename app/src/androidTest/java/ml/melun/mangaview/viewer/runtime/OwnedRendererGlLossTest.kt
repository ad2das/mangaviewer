package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.content.DecodeRequest
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.SourceRowRange
import ml.melun.mangaview.content.TextureRef
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
import ml.melun.mangaview.viewer.session.VisualKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Injects the GL error result at the real upload/draw boundary; no live corpus credit. */
@RunWith(AndroidJUnit4::class)
class OwnedRendererGlLossTest {
    @Test fun glUploadLossRecreatesTheContextAndAcceptsNewPixels() = verifyLoss(uploadLoss = true)
    @Test fun glDrawLossProducesATerminalResultAndAcceptsNewPixels() = verifyLoss(uploadLoss = false)

    private fun verifyLoss(uploadLoss: Boolean) {
        ActivityScenario.launch(OwnedRendererProbeActivity::class.java).use { scenario ->
            lateinit var probe: OwnedRendererProbeActivity
            lateinit var bounds: Rect
            scenario.onActivity { probe = it }
            assertTrue(probe.awaitSurface())
            scenario.onActivity { bounds = it.screenBounds() }
            val oldEpoch = probe.rendererEpoch
            val prior = if (uploadLoss) null else upload(probe, Color.RED)
            lateinit var restored: CountDownLatch
            scenario.onActivity { restored = it.failNextGlOperation() }
            if (uploadLoss) {
                val failure = runCatching { upload(probe, Color.RED) }.exceptionOrNull()
                assertTrue("Injected upload error must fail the old upload", failure is IllegalStateException)
            } else {
                scenario.onActivity { it.showStill(scene(requireNotNull(prior), bounds.height(), 1)) }
            }
            assertTrue("GL error did not trigger context recovery", restored.await(10, TimeUnit.SECONDS))
            assertTrue(probe.rendererEpoch > oldEpoch)
            if (!uploadLoss) assertTrue(probe.presentationSnapshot().any {
                it.timestampKind == PresentationTimestampKind.CONTEXT_LOST
            })
            val replacement = upload(probe, Color.CYAN)
            try {
                lateinit var shown: CountDownLatch
                scenario.onActivity { shown = it.showStill(scene(replacement, bounds.height(), 2)) }
                assertTrue(shown.await(5, TimeUnit.SECONDS))
                assertPixel(bounds, Color.CYAN)
            } finally { probe.uploadPort.release(replacement) }
        }
    }

    private fun upload(probe: OwnedRendererProbeActivity, color: Int): TextureRef = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val file = File(probe.cacheDir, "gl-loss-$color.png")
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val dimensions = PageDimensions(64, 64)
        val encoded = EncodedPageRef(pageId, file.absolutePath, file.length(), "gl-loss-$color", dimensions)
        val pixels = probe.decodePort.decode(DecodeRequest(1, PageSpec(pageId, 0, dimensions), encoded,
            dimensions, 64, SourceRowRange(0, 64), DemandClass.VISIBLE))
        probe.uploadPort.upload(probe.rendererEpoch, pixels)
    }

    private fun scene(texture: TextureRef, height: Int, revision: Long) = SceneSnapshot(
        1, 1, revision, 1, revision, 0, FixedPx.ZERO, FixedPx.ZERO, FixedPx.fromPixels(height),
        listOf(SceneQuad(pageId, FixedPx.ZERO, FixedPx.fromPixels(height),
            0, 64, 64, VisualKey(texture.key))))

    private fun assertPixel(bounds: Rect, expected: Int) {
        val capture = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            assertEquals("Recovered context did not display the newly uploaded image",
                expected, capture.getPixel(bounds.centerX(), bounds.centerY()))
        } finally { capture.recycle() }
    }

    private val pageId = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "gl-loss"), "episode"), 0)
}
