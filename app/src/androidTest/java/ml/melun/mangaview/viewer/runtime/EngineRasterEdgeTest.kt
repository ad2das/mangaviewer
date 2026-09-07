package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.StoredPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineRasterEdgeTest {
    @Test fun recordsSharedEdgeOwnershipAtTheRealViewportSize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "engine-raster-edge-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) }
        val upperColor = 0xffe02020.toInt()
        val lowerColor = 0xff2040e0.toInt()
        val pixels = listOf(upperColor, lowerColor).mapIndexed { index, color ->
            val file = File(output, "source-$index.png")
            val bitmap = Bitmap.createBitmap(720, 1098, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "raster-edge"), "1"), index)
            val page = StoredPage(id, "1", file, file.length(), digest, PageDimensions(720, 1098), "image/png")
            NativeEngineImageDecoder().decode(page, EngineTileSpec(id, "1", digest, page.dimensions, 0, 1098, 1080))
                as NativeEnginePixels
        }
        val cases = JSONArray()
        val report = JSONObject().put("classification", "SYNTHETIC_RASTER_EDGE_CONTROL").put("corpusCredit", 0)
            .put("physicalPresentationVerified", false).put("cases", cases)
        try {
            val edgeCases = listOf(2, 65, 155, 1069, 1712, 2135).flatMap { row ->
                listOf(508, 509, 510, 511, 512, 513, 514, 515, 516).map { row * 1024 + it }
            }
            for (edge in edgeCases) {
                val owner = EngineSurfaceOwner(pixels.sumOf { it.byteCount }, {}, { throw it }, {})
                val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(1080, 2138) }
                val surface = Surface(consumer)
                try {
                    assertTrue(owner.attach(surface, 1080, 2138, 60F))
                    val info = owner.rasterizationInfoForVerification()
                    assertTrue(info[0] >= 4)
                    assertEquals(0, info[1])
                    val textures = pixels.map { owner.upload(it, owner.rendererEpoch) }
                    val top = (edge / 1024).toInt() - 1
                    val capture = async(start = CoroutineStart.UNDISPATCHED) { owner.captureNextFrame(top, top + 3) }
                    owner.offer(EngineSurfaceScene(1, 1, 1, 1, EngineViewport(1080, 2138), null,
                        listOf(EngineTexturePlacement(textures[0], edge - 1647 * 1024, edge),
                               EngineTexturePlacement(textures[1], edge, edge + 1647 * 1024)),
                        coordinateUnitsPerPixel = 1024))
                    val result = withTimeout(10_000) { capture.await() }
                    assertEquals(EngineRasterizationInfo(info[0], info[1], info[2]), result.rasterizationInfo)
                    assertEquals(EngineReadbackPacket.Status.OK, result.pixels.status)
                    val bytes = result.pixels.rgbaBytes
                    val rows = JSONArray()
                    repeat(3) { y ->
                        var upper = 0; var lower = 0; var other = 0
                        repeat(1080) { x ->
                            val at = (y * 1080 + x) * 4
                            val argb = ((bytes[at + 3].toInt() and 255) shl 24) or
                                ((bytes[at].toInt() and 255) shl 16) or
                                ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() and 255)
                            when (argb) { upperColor -> upper++; lowerColor -> lower++; else -> other++ }
                        }
                        rows.put(JSONObject().put("row", top + y).put("upperPixels", upper)
                            .put("lowerPixels", lower).put("otherPixels", other))
                        assertEquals(0, other)
                    }
                    cases.put(JSONObject().put("edgeUnits", edge).put("subpixelBits", info[0])
                        .put("sampleBuffers", info[1]).put("samples", info[2]).put("rows", rows))
                    owner.clearScene()
                    textures.forEach { owner.release(it) }
                } finally { owner.close(); surface.release(); consumer.release() }
            }
        } finally {
            pixels.forEach { it.close() }
            File(output, "result.json").writeText(report.toString(2))
        }
    }
}
