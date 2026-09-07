package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.content.Intent
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

/** Isolated native pixel correctness, not a real-gesture/live-episode qualification. */
@RunWith(AndroidJUnit4::class)
class OwnedRendererPixelsTest {
    /**
     * PixelCopy compares the exact SurfaceView buffer after the native completion latch. It does
     * not claim physical scanout or live-corpus coverage; the whole-window screenshot remains a
     * separately named diagnostic in the source-row test below.
     */
    @Test
    fun staticQuadMatchesStreamingAtEveryViewportPixel() {
        ActivityScenario.launch(OwnedRendererProbeActivity::class.java).use { scenario ->
            lateinit var probe: OwnedRendererProbeActivity
            lateinit var bounds: Rect
            scenario.onActivity { probe = it }
            assertTrue(probe.awaitSurface())
            scenario.onActivity { bounds = it.screenBounds() }
            assertTrue("Viewport must allow a visibly different sentinel offset", bounds.height() in 1 until HEIGHT)
            val textures = upload(probe, patternedImage(probe.cacheDir))
            var revision = 100L
            try {
                for (offset in listOf(0, 1, 1_800, HEIGHT - bounds.height(), 0)) {
                    var baseline: Bitmap? = null
                    try {
                        for (mode in listOf("streaming", "static")) {
                            if (mode == "static") {
                                val sentinelOffset = if (offset < HEIGHT - bounds.height()) offset + 1 else offset - 1
                                val sentinelFrame = scene(textures, sentinelOffset, revision++, 0L)
                                lateinit var sentinelShown: CountDownLatch
                                scenario.onActivity { sentinelShown = it.showStill(sentinelFrame) }
                                assertTrue("Sentinel frame did not complete", sentinelShown.await(5L, TimeUnit.SECONDS))
                                val sentinel = probe.copySurfaceBufferForVerification()
                                try {
                                    File(probe.getExternalFilesDir(null), "quad-surface-buffer-sentinel-$sentinelOffset-$revision.png")
                                        .outputStream().use { sentinel.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    assertSourcePatternSamples(sentinel, sentinelOffset, "Sentinel surface buffer")
                                    assertTrue("Old streaming target is still visible; static comparison would be invalid",
                                        !sentinel.sameAs(requireNotNull(baseline)))
                                } finally { sentinel.recycle() }
                            }
                            val configured = CountDownLatch(1)
                            val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
                            scenario.onActivity { it.setGeometryModeForVerification(mode) { value ->
                                accepted.set(value)
                                configured.countDown()
                            } }
                            assertTrue("Geometry mode did not configure", configured.await(5L, TimeUnit.SECONDS) && accepted.get())
                            lateinit var displayed: CountDownLatch
                            val input = scene(textures, offset, revision++, 0L)
                            scenario.onActivity { displayed = it.showStill(input) }
                            assertTrue("Native $mode frame did not complete", displayed.await(5L, TimeUnit.SECONDS))
                            // The native callback establishes renderer completion; this one-shot
                            // copy reads the exact SurfaceView buffer at its own dimensions.
                            val pixels = probe.copySurfaceBufferForVerification()
                            try {
                                File(probe.getExternalFilesDir(null), "quad-surface-buffer-pixels-$offset-$revision-$mode.png")
                                    .outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                assertSourcePatternSamples(pixels, offset, "$mode surface buffer")
                                if (mode == "streaming") baseline = pixels else {
                                    assertTrue("Static and streaming pixels differ at offset $offset", pixels.sameAs(requireNotNull(baseline)))
                                }
                            } finally { if (pixels !== baseline) pixels.recycle() }
                        }
                    } finally { baseline?.recycle() }
                }
            } finally { textures.forEach(probe.uploadPort::release) }
        }
    }

    @Test
    fun exactSourceRowsSurviveSceneReplacementAndReverseOffsets() {
        val mode = InstrumentationRegistry.getArguments().getString("probeGeometryMode") ?: "static"
        require(mode == "streaming" || mode == "static")
        val launch = Intent(InstrumentationRegistry.getInstrumentation().targetContext, OwnedRendererProbeActivity::class.java)
            .putExtra("probeGeometryMode", mode)
        ActivityScenario.launch<OwnedRendererProbeActivity>(launch).use { scenario ->
            lateinit var probe: OwnedRendererProbeActivity
            lateinit var bounds: Rect
            scenario.onActivity { probe = it }
            assertTrue(probe.awaitSurface())
            scenario.onActivity { bounds = it.screenBounds() }
            assertTrue(bounds.height() in 1..HEIGHT)
            var revision = 1L
            repeat(2) { version ->
                if (version > 0) {
                    val oldEpoch = probe.rendererEpoch
                    lateinit var reset: CountDownLatch
                    scenario.onActivity { reset = it.recreateContext() }
                    assertTrue("Context recreation did not finish", reset.await(10L, TimeUnit.SECONDS))
                    assertTrue("Context recreation did not invalidate textures", probe.rendererEpoch > oldEpoch)
                }
                val file = image(probe.cacheDir, version)
                val textures = upload(probe, file)
                try {
                    for (offset in listOf(0, 1_800, HEIGHT - bounds.height(), 0)) {
                        lateinit var latch: CountDownLatch
                        val frame = scene(textures, offset, revision++, version.toLong())
                        scenario.onActivity { latch = it.showStill(frame) }
                        assertTrue("Native frame completion missing", latch.await(5L, TimeUnit.SECONDS))
                        assertWholeWindowScreenshotPixels(probe, bounds, offset, version, revision)
                    }
                } finally {
                    textures.forEach(probe.uploadPort::release)
                }
            }
        }
    }

    /** Separate whole-window screenshot diagnostic; it is not used by the SurfaceView comparison. */
    private fun assertWholeWindowScreenshotPixels(
        probe: OwnedRendererProbeActivity,
        bounds: Rect,
        offset: Int,
        version: Int,
        revision: Long,
    ) {
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val artifact = File(probe.getExternalFilesDir(null), "native-window-screenshot-$version-$revision.png")
            artifact.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (y in 0 until bounds.height()) {
                for (x in listOf(bounds.width() / 4, bounds.width() / 2, bounds.width() * 3 / 4)) {
                    val actual = screenshot.getPixel(bounds.left + x, bounds.top + y)
                    assertEquals("version=$version sourceRow=${offset + y} x=$x", color(offset + y, version), actual)
                }
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertSourcePatternSamples(bitmap: Bitmap, offset: Int, label: String) {
        assertTrue("$label must expose at least two rows", bitmap.height > 1)
        val rows = listOf(0, 1, bitmap.height / 2, bitmap.height - 1).distinct()
        rows.forEach { row ->
            assertEquals(
                "$label has unexpected source row ${offset + row} at x=0",
                sourcePatternColor(0, offset + row),
                bitmap.getPixel(0, row),
            )
        }
    }

    private fun patternedImage(directory: File): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(WIDTH * HEIGHT) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                sourcePatternColor(x, y)
            }
            bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return File(directory, "static-quad-pattern.png").also { file ->
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    private fun upload(probe: OwnedRendererProbeActivity, file: File): List<TextureRef> = runBlocking {
        val dimensions = PageDimensions(WIDTH, HEIGHT)
        val encoded = EncodedPageRef(pageId, file.absolutePath, file.length(), "pixel-fixture", dimensions)
        (0 until 3).map { band ->
            val range = SourceRowRange(HEIGHT * band / 3, HEIGHT * (band + 1) / 3)
            val pixels = probe.decodePort.decode(DecodeRequest(
                1L, PageSpec(pageId, 0, dimensions), encoded, dimensions, WIDTH, range, DemandClass.VISIBLE,
            ))
            probe.uploadPort.upload(probe.rendererEpoch, pixels)
        }
    }

    private fun image(directory: File, version: Int): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(WIDTH * HEIGHT) { color(it / WIDTH, version) }
            bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return File(directory, "native-pixel-source-$version.png").also { file ->
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun scene(textures: List<TextureRef>, offset: Int, revision: Long, window: Long) = SceneSnapshot(
        1L, 1L, 1L, 1L, revision, window, FixedPx.ZERO, FixedPx.fromPixels(offset),
        FixedPx.fromPixels(HEIGHT), textures.map { texture ->
            SceneQuad(pageId, FixedPx.fromPixels(texture.sourceTopPx),
                FixedPx.fromPixels(texture.sourceBottomPx - texture.sourceTopPx),
                texture.sourceTopPx, texture.sourceBottomPx, HEIGHT, VisualKey(texture.key))
        },
    )

    private fun color(row: Int, version: Int): Int {
        val band = if (row < 2_000) 0 else if (row < 4_000) 1 else 2
        return if (version == 0) listOf(Color.RED, Color.GREEN, Color.BLUE)[band]
        else listOf(Color.CYAN, Color.MAGENTA, Color.YELLOW)[band]
    }

    private fun sourcePatternColor(x: Int, row: Int): Int = Color.rgb(
        (x * 31 + row * 17) % 256,
        (x * 7 + row * 29) % 256,
        (x * 19 + row * 11) % 256,
    )

    private val pageId = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "pixels"), "episode"), 0)

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 6_001
    }
}
