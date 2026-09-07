package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import ml.melun.mangaview.viewer.runtime.EngineReadbackPacket.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineReadbackInstrumentedTest {
    @Test
    fun exactPixelsSurviveSameSizeSurfaceRecreation() {
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            EngineReadbackProbeActivity::class.java,
        )
        ActivityScenario.launch<EngineReadbackProbeActivity>(intent).use { scenario ->
            lateinit var probe: EngineReadbackProbeActivity
            scenario.onActivity { probe = it }
            await(probe.awaitSurfaceReady())
            val initial = await(probe.surfaceGeometry())
            assertGeometry(initial, 1L)

            val sourceFiles = (0..1).map { version -> writeSourcePng(probe.fixtureDirectory, version) }
            val textureKeys = sourceFiles.map { file -> decodeAndUpload(probe, file) }.toLongArray()
            var closed = false
            try {
                val capture = probe.startCapture(textureKeys)
                await(probe.awaitFrame4())
                val beforeRecreate = await(probe.surfaceGeometry())
                assertGeometry(beforeRecreate, 1L)

                await(probe.recreateSurfaceView())
                val afterRecreate = await(probe.surfaceGeometry())
                assertGeometry(afterRecreate, 2L)
                await(probe.resumeAfterSurfaceRecreation())
                await(capture)

                val packets = probe.rawPacketSnapshot()
                assertEquals((1L..8L).toSet(), packets.keys)
                packets.forEach { (token, raw) ->
                    File(probe.fixtureDirectory, "frame-$token.rawpacket.bin").writeBytes(raw)
                }
                packets.toSortedMap().forEach { (token, raw) -> assertPacket(probe, token, raw) }

                await(probe.duplicateFutureTicketProbe())
                await(probe.assertReadbackCountsZero())
                writeManifest(probe, sourceFiles, packets)

                val firstClose = probe.closeFixture()
                val secondClose = probe.closeFixture()
                assertSame(firstClose, secondClose)
                await(firstClose)
                assertTrue("Fixture owner thread did not terminate", probe.joinOwnerThread(5_000L))
                closed = true
            } finally {
                if (!closed) {
                    val firstClose = probe.closeFixture()
                    val secondClose = probe.closeFixture()
                    assertSame(firstClose, secondClose)
                    runCatching { await(firstClose) }
                    assertTrue("Fixture owner thread did not terminate", probe.joinOwnerThread(5_000L))
                }
            }
        }
    }

    private fun decodeAndUpload(probe: EngineReadbackProbeActivity, source: File): Long {
        val cpuHandle = NativeCpuDecodeBridge.nativeDecode(
            source.absolutePath,
            WIDTH,
            HEIGHT,
            0,
            HEIGHT,
            WIDTH,
        )
        assertTrue("Fixture CPU decode failed for ${source.name}", cpuHandle != 0L)
        return try {
            await(probe.uploadCpuTile(cpuHandle))
        } finally {
            NativeCpuDecodeBridge.nativeRelease(cpuHandle)
        }
    }

    private fun assertPacket(probe: EngineReadbackProbeActivity, token: Long, raw: ByteArray) {
        val packet = EngineReadbackPacket.parse(raw)
        val expectedEpoch = if (token <= 4L) 1L else 2L
        val version = ((token - 1L) and 1L).toInt()
        assertEquals("status token=$token", Status.OK, packet.status)
        assertEquals("session token=$token", probe.sessionId, packet.sessionId)
        assertEquals("renderer epoch token=$token", 1L, packet.rendererEpoch)
        assertEquals("surface epoch token=$token", expectedEpoch, packet.surfaceEpoch)
        assertEquals("token", token, packet.token)
        assertTrue("EGL frame id token=$token", packet.eglFrameId > 0L)
        assertEquals("width token=$token", WIDTH.toLong(), packet.width)
        assertEquals("top token=$token", STRIP_TOP.toLong(), packet.top)
        assertEquals("bottom token=$token", STRIP_BOTTOM.toLong(), packet.bottom)
        assertTrue("capture issued token=$token", packet.captureIssuedMonotonicNs > 0L)
        assertTrue(
            "capture/swap timeline token=$token",
            packet.captureReadyMonotonicNs >= packet.swapCompletedMonotonicNs &&
                packet.swapCompletedMonotonicNs >= packet.captureIssuedMonotonicNs,
        )
        assertFalse("Physical presentation proof must remain false", packet.physicalPresentationVerified)
        assertEquals("payload size token=$token", WIDTH * (STRIP_BOTTOM - STRIP_TOP) * 4L, packet.rgbaByteCount)

        val pixels = packet.rgbaBytes
        for (row in 0 until STRIP_BOTTOM - STRIP_TOP) {
            val sourceY = STRIP_TOP + row
            for (x in 0 until WIDTH) {
                val expected = expectedPixel(x, sourceY, version)
                val offset = (row * WIDTH + x) * 4
                assertEquals("red token=$token x=$x y=$sourceY", expected[0], pixels[offset].toInt() and 0xff)
                assertEquals("green token=$token x=$x y=$sourceY", expected[1], pixels[offset + 1].toInt() and 0xff)
                assertEquals("blue token=$token x=$x y=$sourceY", expected[2], pixels[offset + 2].toInt() and 0xff)
                assertEquals("alpha token=$token x=$x y=$sourceY", 255, pixels[offset + 3].toInt() and 0xff)
            }
        }
    }

    private fun writeSourcePng(directory: File, version: Int): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val pixels = IntArray(WIDTH * HEIGHT) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                val color = expectedPixel(x, y, version)
                Color.argb(255, color[0], color[1], color[2])
            }
            bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            File(directory, "source-v$version.png").also { file ->
                file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeManifest(
        probe: EngineReadbackProbeActivity,
        sourceFiles: List<File>,
        packets: Map<Long, ByteArray>,
    ) {
        val sources = JSONArray()
        sourceFiles.forEach { file ->
            sources.put(JSONObject().put("name", file.name).put("sha256", sha256(file)))
        }
        val frames = JSONArray()
        packets.toSortedMap().forEach { (token, raw) ->
            val packet = EngineReadbackPacket.parse(raw)
            frames.put(
                JSONObject()
                    .put("token", token)
                    .put("rawPacket", "frame-$token.rawpacket.bin")
                    .put("sha256", sha256(raw))
                    .put("status", packet.status.name)
                    .put("sessionId", packet.sessionId)
                    .put("rendererEpoch", packet.rendererEpoch)
                    .put("surfaceEpoch", packet.surfaceEpoch)
                    .put("eglFrameId", packet.eglFrameId)
                    .put("width", packet.width)
                    .put("top", packet.top)
                    .put("bottom", packet.bottom)
                    .put("physicalPresentationVerified", packet.physicalPresentationVerified),
            )
        }
        File(probe.fixtureDirectory, "manifest.json").writeText(
            JSONObject()
                .put("status", "FIXTURE_REGRESSION_NO_CORPUS_CREDIT")
                .put("sessionId", probe.sessionId)
                .put("rendererEpoch", 1L)
                .put("surfaceEpochs", JSONArray().put(1L).put(2L))
                .put("surfaceWidth", WIDTH)
                .put("surfaceHeight", HEIGHT)
                .put("stripTop", STRIP_TOP)
                .put("stripBottom", STRIP_BOTTOM)
                .put("sourcePng", sources)
                .put("frames", frames)
                .toString(2),
        )
    }

    private fun assertGeometry(geometry: EngineReadbackSurfaceGeometry, epoch: Long) {
        assertEquals("surface view width", WIDTH, geometry.viewWidth)
        assertEquals("surface view height", HEIGHT, geometry.viewHeight)
        assertEquals("surface buffer width", WIDTH, geometry.bufferWidth)
        assertEquals("surface buffer height", HEIGHT, geometry.bufferHeight)
        assertEquals("surface epoch", epoch, geometry.surfaceEpoch)
    }

    private fun expectedPixel(x: Int, y: Int, version: Int): IntArray = intArrayOf(
        (x * 3 + y + version * 41) and 0xff,
        (y * 5 + x * 7 + version * 53) and 0xff,
        (x xor y xor (version * 87)) and 0xff,
    )

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun <T> await(future: CompletableFuture<T>): T =
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 96
        const val STRIP_TOP = 8
        const val STRIP_BOTTOM = 56
        const val TIMEOUT_SECONDS = 5L
    }
}
