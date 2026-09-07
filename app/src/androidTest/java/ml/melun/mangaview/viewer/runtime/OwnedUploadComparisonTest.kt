package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.content.DecodeRequest
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.SourceRowRange
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Isolated original-image upload comparison. Never grants corpus or physical-scanout credit. */
class OwnedUploadComparisonTest {
    @Test
    fun repeatedPboLifecycleClosesAndReopens() {
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("ux-evidence"))
        val source = root.resolve("upload-control/source-p0000.page")
        check(digest(source.readBytes()) == "429752e76ea103a3b3834caa966c547bd60abf6109389dd1bfc3f16ad4639417")
        val directory = root.resolve("upload-pbo-lifecycle-${System.nanoTime()}").apply { check(mkdir()) }
        repeat(3) { index ->
            val (bitmap, record) = measure(source, "pbo", index, 0)
            try {
                directory.resolve("cycle-$index.json").writeText(record
                    .put("scope", "PBO_LIFECYCLE_DIAGNOSTIC_NO_CORPUS_CREDIT").toString(2))
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun compareFiveCounterbalancedPairsWithExactSurfacePixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ux-evidence"))
        val source = root.resolve("upload-control/source-p0000.page")
        val policy = root.resolve("upload-control/policy.json")
        val sourceHash = digest(source.readBytes())
        check(sourceHash == "429752e76ea103a3b3834caa966c547bd60abf6109389dd1bfc3f16ad4639417")
        check(digest(policy.readBytes()) == requireNotNull(arguments.getString("uploadPolicySha256")))
        val runId = requireNotNull(arguments.getString("uploadRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = root.resolve("upload-comparison-$runId").apply { check(mkdir()) }
        val offset = arguments.getString("uploadPairOffset")?.toInt() ?: 0
        require(offset == 0 || offset == 5)
        val order = JSONObject(policy.readText()).getJSONArray("orders")
        val results = JSONArray()
        val comparisons = JSONArray()
        try {
            repeat(5) { index ->
                val pair = offset + index
                var first: Bitmap? = null
                try {
                    val modes = order.getJSONArray(pair)
                    repeat(2) { arm ->
                        val mode = modes.getString(arm)
                        val (bitmap, record) = measure(source, mode, pair, arm)
                        results.put(record)
                        val image = directory.resolve("pair-$pair-$mode.png")
                        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        record.put("imageSha256", digest(image.readBytes()))
                        directory.resolve("arm-$pair-$mode.json").writeText(record.toString(2))
                        if (first == null) first = bitmap else {
                            try {
                                val equal = requireNotNull(first).sameAs(bitmap)
                                comparisons.put(JSONObject().put("pair", pair).put("exactPixelsEqual", equal))
                                check(equal) { "Upload modes produced different surface pixels in pair $pair" }
                            } finally { bitmap.recycle() }
                        }
                    }
                } finally { first?.recycle() }
            }
            check(digest(source.readBytes()) == sourceHash)
        } finally {
            directory.resolve("results.json").writeText(JSONObject()
                .put("scope", "ORIGINAL_IMAGE_UPLOAD_CONTROL_NO_CORPUS_CREDIT")
                .put("exactPhysicalPresentationTimeVerified", false).put("corpusCredit", 0)
                .put("sourceSha256", sourceHash).put("policySha256", digest(policy.readBytes()))
                .put("arms", results).put("pairs", comparisons).toString(2))
        }
    }

    private fun measure(source: File, mode: String, pair: Int, arm: Int): Pair<Bitmap, JSONObject> {
        val entry = System.nanoTime()
        ActivityScenario.launch(OwnedRendererProbeActivity::class.java).use { scenario ->
            lateinit var probe: OwnedRendererProbeActivity
            scenario.onActivity { probe = it }
            check(probe.awaitSurface())
            val modeReady = CountDownLatch(1)
            var accepted = false
            probe.setUploadModeForVerification(mode) { accepted = it; modeReady.countDown() }
            check(modeReady.await(5, TimeUnit.SECONDS) && accepted)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }.let {
                BitmapFactory.decodeFile(source.absolutePath, it)
                PageDimensions(it.outWidth, it.outHeight)
            }
            val page = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "comic:10001"), "1"), 0)
            val encoded = EncodedPageRef(page, source.absolutePath, source.length(), "original-upload-control", dimensions)
            var width = 0
            scenario.onActivity { width = it.screenBounds().width() }
            val decodeStarted = System.nanoTime()
            val pixels = runBlocking { probe.decodePort.decode(DecodeRequest(
                1L, PageSpec(page, 0, dimensions), encoded, dimensions, width,
                SourceRowRange(0, dimensions.heightPx), DemandClass.VISIBLE,
            )) }
            val decoded = System.nanoTime()
            val bytes = pixels.byteCount
            val texture = runBlocking { probe.uploadPort.upload(probe.rendererEpoch, pixels) }
            val uploaded = System.nanoTime()
            try {
                val height = FixedPx.fromPixels((dimensions.heightPx.toLong() * width / dimensions.widthPx).toInt())
                val scene = SceneSnapshot(1L, 1L, 1L, 1L, 1L, 0L, FixedPx.ZERO, FixedPx.ZERO,
                    height, listOf(SceneQuad(page, FixedPx.ZERO, height, 0, dimensions.heightPx,
                        dimensions.heightPx, VisualKey(texture.key))))
                lateinit var rendered: CountDownLatch
                scenario.onActivity { rendered = it.showStill(scene) }
                check(rendered.await(10, TimeUnit.SECONDS))
                val copyStarted = System.nanoTime()
                val bitmap = probe.copySurfaceBufferForVerification()
                val copied = System.nanoTime()
                val colors = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Surface must contain the original image, not one clear color", colors.any { it != colors[0] })
                val record = JSONObject().put("pair", pair).put("arm", arm).put("mode", mode)
                    .put("uploadedBytes", bytes).put("entryNanos", entry).put("decodeStartedNanos", decodeStarted)
                    .put("decodedNanos", decoded).put("uploadedNanos", uploaded)
                    .put("copyStartedNanos", copyStarted).put("copiedNanos", copied)
                    .put("decodeNanos", decoded - decodeStarted).put("uploadReturnNanos", uploaded - decoded)
                    .put("decodeToCopiedBufferNanos", copied - decodeStarted)
                    .put("entryToCopiedBufferNanos", copied - entry)
                return bitmap to record
            } finally {
                probe.uploadPort.release(texture)
                check(probe.closeRendererForVerification().await(10, TimeUnit.SECONDS)) {
                    "Renderer resources did not finish closing before the next comparison arm"
                }
            }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
