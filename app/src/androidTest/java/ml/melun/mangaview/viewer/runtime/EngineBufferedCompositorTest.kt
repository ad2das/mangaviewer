package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineBufferedCompositorTest {
    @Test fun pooledFramesPreserveFractionalPixelsOnScreenAcrossReuseAndReattachment() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(EngineBufferedProbeActivity::class.java).use { scenario ->
            lateinit var probe: EngineBufferedProbeActivity
            scenario.onActivity { probe = it }
            val view = probe.ready.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val width = view.width
            val height = view.height
            val origin = IntArray(2)
            scenario.onActivity { view.getLocationOnScreen(origin) }
            val (page, pixels) = pixels(width, height)
            val frames = Channel<EngineSurfacePresentation>(Channel.UNLIMITED)
            val failures = mutableListOf<Throwable>()
            val owner = EngineSurfaceOwner(pixels.byteCount, { frames.trySend(it) }, { failures += it }, {},
                bufferedCompositor = true)
            try {
                owner.prepare()
                assertTrue(owner.attach(view.holder.surface, width, height, 60F))
                val texture = owner.upload(pixels, owner.rendererEpoch)
                var previousEpoch = 0L
                for (iteration in 0 until 21) {
                    if (iteration == 10) {
                        owner.detach()
                        assertTrue(owner.attach(view.holder.surface, width, height, 60F))
                    }
                    val offset = intArrayOf(0, 128, 256, 512, 640, 768, 896)[iteration % 7]
                    val scene = EngineSurfaceScene(1, 1, iteration.toLong(), 1, EngineViewport(width, height), null,
                        listOf(EngineTexturePlacement(texture, offset, height * 1024 + offset)), 1024)
                    val packet = withTimeout(10000) { owner.capture(scene, 0, height) }
                    assertEquals(EngineReadbackPacket.Status.OK, packet.status)
                    assertEquals(0L, packet.eglFrameId)
                    assertFalse(packet.physicalPresentationVerified)
                    val presentation = withTimeout(10000) { frames.receive() }
                    assertTrue(presentation.swapSucceeded)
                    assertEquals(PresentationTimestampKind.COMPOSITION_LATCH, presentation.timestampKind)
                    assertTrue(presentation.timestampNanos > 0)
                    assertEquals(iteration.toLong(), presentation.identity.inputRevision)
                    if (iteration == 10) assertTrue(presentation.identity.surfaceEpoch > previousEpoch)
                    previousEpoch = presentation.identity.surfaceEpoch
                    assertScreenEquals(packet.rgbaBytes, width, height, origin, iteration)
                }
                owner.clearScene()
                owner.release(texture)
                assertEquals(0L, owner.ownership().bytes)
                assertTrue(failures.toString(), failures.isEmpty())
                assertFalse(pixels.isClosed)
            } finally {
                owner.close()
                pixels.close()
                assertTrue(page.file.delete())
                frames.close()
            }
        }
    }

    private fun assertScreenEquals(rgba: ByteArray, width: Int, height: Int, origin: IntArray, iteration: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var errors = Int.MAX_VALUE
        var locations = listOf<String>()
        var diagnosticSaved = false
        do {
            Thread.sleep(50)
            val screen = automation.takeScreenshot() ?: continue
            errors = 0
            val samples = mutableListOf<String>()
            try {
                for (y in 0 until height step 7) for (x in 0 until width step 13) {
                    val color = screen.getPixel(origin[0] + x, origin[1] + y)
                    val at = (y * width + x) * 4
                    for (channel in 0..2) {
                        val actual = color ushr (16 - channel * 8) and 255
                        val expected = rgba[at + channel].toInt() and 255
                        if (kotlin.math.abs(actual - expected) > 1) {
                            errors++
                            if (samples.size < 200) samples += "$x,$y,$channel:$actual/$expected"
                        }
                    }
                }
                locations = samples
                if (errors > 0 && !diagnosticSaved) {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val output = File(context.getExternalFilesDir(null), "engine-capture-buffered-${System.nanoTime()}").also { it.mkdirs() }
                    File(output, "actual.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(output, "expected.rgba").writeBytes(rgba)
                    File(output, "geometry.txt").writeText("$iteration $width $height ${origin.toList()}\n${locations.joinToString("\n")}")
                    diagnosticSaved = true
                }
            } finally { screen.recycle() }
            if (errors == 0) return
        } while (System.nanoTime() < deadline)
        fail("Final screen does not match fractional GL pixels: iteration=$iteration $errors sampled channel errors; ${locations.take(12)}")
    }

    private suspend fun pixels(width: Int, height: Int): Pair<StoredPage, NativeEnginePixels> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("engine-buffered-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(width * height) { index ->
                val x = index % width; val y = index / width
                (255 shl 24) or ((y * 17 and 255) shl 16) or ((x * 3 and 255) shl 8) or (y * 11 and 255)
            }
            bitmap.setPixels(colors, 0, width, 0, 0, width, height)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "buffered"), "1"), 0)
        val page = StoredPage(id, "1", file, file.length(), sha, PageDimensions(width, height), "image/png")
        val tile = EngineTileSpec(id, "1", sha, page.dimensions, 0, height, width)
        return page to (NativeEngineImageDecoder().decode(page, tile) as NativeEnginePixels)
    }
}
