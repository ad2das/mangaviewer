package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.system.Os
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.StoredPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceTileGeometryProbeTest {
    private data class Fixture(val page: StoredPage, val pixels: NativeEnginePixels)
    private data class Pose(
        val sequence: Long,
        val label: String,
        val fixtures: List<Int>,
        val topUnits: List<Int>,
        val bottomUnits: List<Int>,
    )

    @Test fun geometryOnlyTransactionsPreserveFractionalSourcePixels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(EngineBufferedProbeActivity::class.java).use { scenario ->
            lateinit var activity: EngineBufferedProbeActivity
            scenario.onActivity { activity = it }
            val view = activity.ready.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val fenceBaseline = syncFenceFdCount()
            val width = view.width
            val height = view.height
            val origin = IntArray(2)
            scenario.onActivity { view.getLocationOnScreen(origin) }
            val heightR = height - height % 4
            val narrow = width * 3 / 4
            val fixtures = listOf(
                fixture(narrow, heightR, width, "a"),
                fixture(width, heightR, width, "b"),
            )
            val frames = Channel<EngineSurfacePresentation>(Channel.UNLIMITED)
            val failures = mutableListOf<Throwable>()
            val owner = EngineSurfaceOwner(fixtures.sumOf { it.pixels.byteCount },
                { frames.trySend(it) }, { failures += it }, {}, bufferedCompositor = true)
            val allPoses = poses(height, heightR)
            var probe = 0L
            var completed = false
            try {
                owner.prepare()
                assertTrue(owner.attach(view.holder.surface, width, height, 60F))
                assertEquals(narrow, fixtures[0].pixels.tile.rasterWidth)
                assertEquals(width, fixtures[1].pixels.tile.rasterWidth)
                val textures = fixtures.map { owner.upload(it.pixels, owner.rendererEpoch) }
                probe = SourceTileGeometryProbe.create(view.holder.surface, width, height)
                assertTrue("source tile probe creation failed", probe != 0L)
                uploadFixtures(probe, fixtures)
                assertTrue(SourceTileGeometryProbe.await(probe, 10000))
                runPoses(owner, frames, probe, textures, width, height, origin, allPoses)
                val status = requireNotNull(SourceTileGeometryProbe.status(probe))
                assertEquals(fixtures.size.toLong(), status[0])
                assertEquals(allPoses.size.toLong(), status[1])
                val fences = longArrayOf(status[3], status[4])
                assertTrue(SourceTileGeometryProbe.close(probe))
                probe = 0L
                awaitFenceReturn(fenceBaseline)
                verifyRecreated(fixtures, view.holder.surface, width, height, allPoses.last())
                awaitFenceReturn(fenceBaseline)
                writeEvidence(fixtures, allPoses.size, fences)
                textures.forEach { owner.release(it) }
                assertEquals(0L, owner.ownership().bytes)
                assertTrue(failures.toString(), failures.isEmpty())
                assertFalse(fixtures.any { it.pixels.isClosed })
                completed = true
            } finally {
                if (probe != 0L) SourceTileGeometryProbe.close(probe)
                owner.close()
                if (completed) awaitFenceReturn(fenceBaseline)
                fixtures.forEach { it.pixels.close(); assertTrue(it.page.file.delete()) }
                frames.close()
            }
        }
    }

    private suspend fun runPoses(owner: EngineSurfaceOwner, frames: Channel<EngineSurfacePresentation>,
        probe: Long, textures: List<EngineTexture>, width: Int, height: Int, origin: IntArray,
        poses: List<Pose>) {
        for (pose in poses) {
            val expected = captureAndPresent(owner, frames, probe, textures, width, height, pose)
            assertFixtureNotBlank(expected)
            assertScreenEquals(expected, width, height, origin, pose.label)
        }
    }

    private suspend fun verifyRecreated(fixtures: List<Fixture>, surface: Surface, width: Int,
        height: Int, pose: Pose) {
        val probe = SourceTileGeometryProbe.create(surface, width, height)
        assertTrue("recreated source tile probe creation failed", probe != 0L)
        try {
            uploadFixtures(probe, fixtures)
            assertTrue(SourceTileGeometryProbe.await(probe, 10000))
            assertTrue(pose.label, SourceTileGeometryProbe.present(probe, pose.fixtures.toIntArray(),
                pose.topUnits.toIntArray(), pose.bottomUnits.toIntArray(), 0, width, height))
            assertTrue(SourceTileGeometryProbe.await(probe, 10000))
            val status = requireNotNull(SourceTileGeometryProbe.status(probe))
            assertEquals(fixtures.size.toLong(), status[0])
            assertEquals(1L, status[1])
        } finally {
            assertTrue(SourceTileGeometryProbe.close(probe))
        }
    }

    private fun uploadFixtures(probe: Long, fixtures: List<Fixture>) {
        for (item in fixtures) {
            assertTrue("upload failed for raster ${item.pixels.tile.rasterWidth}",
                SourceTileGeometryProbe.upload(probe, item.pixels.handle,
                    item.pixels.tile.rasterWidth, item.pixels.tile.decodedHeight))
        }
    }

    private suspend fun captureAndPresent(owner: EngineSurfaceOwner,
        frames: Channel<EngineSurfacePresentation>, probe: Long, textures: List<EngineTexture>,
        width: Int, height: Int, pose: Pose): ByteArray {
        val scene = EngineSurfaceScene(1, 1, pose.sequence, 1, EngineViewport(width, height), null,
            pose.fixtures.mapIndexed { index, fixture ->
                EngineTexturePlacement(textures[fixture], pose.topUnits[index], pose.bottomUnits[index])
            }, 1024)
        val packet = withTimeout(15000) { owner.capture(scene, 0, height) }
        assertEquals(EngineReadbackPacket.Status.OK, packet.status)
        val presentation = withTimeout(10000) { frames.receive() }
        assertTrue(presentation.swapSucceeded)
        owner.clearScene()
        assertTrue(pose.label, SourceTileGeometryProbe.present(probe, pose.fixtures.toIntArray(),
            pose.topUnits.toIntArray(), pose.bottomUnits.toIntArray(), 0, width, height))
        assertTrue(pose.label, SourceTileGeometryProbe.await(probe, 10000))
        return packet.rgbaBytes
    }

    private fun poses(height: Int, heightR: Int): List<Pose> {
        val offsets = intArrayOf(0, 128, 256, 512, 640, 768, 896)
        val result = mutableListOf<Pose>()
        for ((index, offset) in offsets.withIndex()) {
            val top = offset
            when (index % 3) {
                0 -> result += Pose(index.toLong(), "narrow-d1-$offset", listOf(0), listOf(top),
                    listOf(top + heightR * 1024))
                1 -> result += Pose(index.toLong(), "full-d3-$offset", listOf(1), listOf(top),
                    listOf(top + heightR * 3 / 4 * 1024))
                else -> result += Pose(index.toLong(), "full-d5-$offset", listOf(1), listOf(top),
                    listOf(top + heightR * 5 / 4 * 1024))
            }
        }
        var sequence = result.size.toLong()
        val seam = height / 2 * 1024
        for (offset in offsets) {
            result += Pose(sequence++, "seam-$offset", listOf(0, 1),
                listOf(-(offset + 128), seam + offset),
                listOf(seam + offset, height * 1024 + offset + 128))
        }
        for (offset in offsets) {
            result += Pose(sequence++, "clip-top-$offset", listOf(0), listOf(-(offset + 128)),
                listOf(height * 1024))
        }
        for (offset in offsets) {
            result += Pose(sequence++, "clip-bottom-$offset", listOf(1), listOf(offset),
                listOf(height * 1024 + offset + 128))
        }
        return result
    }

    private fun assertScreenEquals(rgba: ByteArray, width: Int, height: Int, origin: IntArray,
        label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lastErrors = Int.MAX_VALUE
        var lastSamples = listOf<String>()
        do {
            Thread.sleep(50)
            val screen = automation.takeScreenshot() ?: continue
            var errors = 0
            val samples = mutableListOf<String>()
            try {
                val row = IntArray(width)
                for (y in 0 until height) {
                    screen.getPixels(row, 0, width, origin[0], origin[1] + y, width, 1)
                    for (x in 0 until width) {
                        val color = row[x]
                        val at = (y * width + x) * 4
                        for (channel in 0..2) {
                            val actual = color ushr (16 - channel * 8) and 255
                            val expected = rgba[at + channel].toInt() and 255
                            if (abs(actual - expected) > 1) {
                                errors++
                                if (samples.size < 200) samples += "$x,$y,$channel:$actual/$expected"
                            }
                        }
                    }
                }
            } finally { screen.recycle() }
            if (errors == 0) return
            lastErrors = errors
            lastSamples = samples
        } while (System.nanoTime() < deadline)
        saveDiagnostic(rgba, width, height, origin, label, lastSamples)
        fail("Screen does not match geometry-only source tiles: $label $lastErrors channel errors; " +
            "${lastSamples.take(12)}")
    }

    private fun saveDiagnostic(rgba: ByteArray, width: Int, height: Int, origin: IntArray,
        label: String, samples: List<String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null),
            "engine-capture-source-tiles-${System.nanoTime()}").also { it.mkdirs() }
        val screen = instrumentation.uiAutomation.takeScreenshot()
        if (screen != null) {
            try {
                File(output, "actual.png").outputStream().use {
                    screen.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screen.recycle() }
        }
        File(output, "expected.rgba").writeBytes(rgba)
        File(output, "geometry.txt").writeText(
            "$label $width $height ${origin.toList()}\n${samples.joinToString("\n")}")
        println("source-tile-geometry-probe mismatch artifact=$output")
    }

    private fun assertFixtureNotBlank(rgba: ByteArray) {
        var nonBlack = 0
        var at = 0
        while (at + 3 < rgba.size && nonBlack < 64) {
            if ((rgba[at].toInt() and 255) != 0 || (rgba[at + 1].toInt() and 255) != 0 ||
                (rgba[at + 2].toInt() and 255) != 0) nonBlack++
            at += 4
        }
        assertTrue("expected fixture pixels are blank", nonBlack >= 64)
    }

    private fun writeEvidence(fixtures: List<Fixture>, poseCount: Int, fences: LongArray) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.getExternalFilesDir(null),
            "source-tile-geometry-probe-${System.nanoTime()}.json")
        evidence.writeText(JSONObject()
            .put("classification", "SOURCE_TILE_GEOMETRY_PROBE")
            .put("poses", poseCount)
            .put("fixtureRasterWidths", fixtures.map { it.pixels.tile.rasterWidth })
            .put("presentFenceAvailable", fences[0])
            .put("lastPresentFenceFd", fences[1])
            .put("uploadOncePerTile", true)
            .put("physicalPresentationVerified", false)
            .put("performanceQualified", false)
            .put("corpusCredit", 0)
            .toString(2))
        println("source-tile-geometry-probe artifact=$evidence")
    }

    private fun awaitFenceReturn(baseline: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var count = syncFenceFdCount()
        while (count > baseline && System.nanoTime() < deadline) {
            Thread.sleep(50)
            count = syncFenceFdCount()
        }
        assertTrue("retained sync fence fds leaked: baseline=$baseline afterClose=$count",
            count <= baseline)
    }

    private fun syncFenceFdCount(): Int = File("/proc/self/fd").list()?.count { name ->
        runCatching { Os.readlink("/proc/self/fd/$name") }.getOrNull()?.contains("sync_file") == true
    } ?: 0

    private suspend fun fixture(decodedWidth: Int, decodedHeight: Int, displayWidth: Int,
        tag: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("source-tiles-$tag-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(decodedWidth, decodedHeight, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(decodedWidth * decodedHeight) { index ->
                val x = index % decodedWidth
                val y = index / decodedWidth
                (255 shl 24) or ((y * 29 and 255) shl 16) or ((x * 5 and 255) shl 8) or ((x + y) * 11 and 255)
            }
            bitmap.setPixels(colors, 0, decodedWidth, 0, 0, decodedWidth, decodedHeight)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "source-tiles"), "1"),
            tag.first().code)
        val page = StoredPage(id, "1", file, file.length(), sha,
            PageDimensions(decodedWidth, decodedHeight), "image/png")
        val tile = EngineTileSpec(id, "1", sha, page.dimensions, 0, decodedHeight, displayWidth)
        return Fixture(page, NativeEngineImageDecoder().decode(page, tile) as NativeEnginePixels)
    }
}
