package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.Choreographer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic only: fully prepared GPU pixels, no network, decode, readback or uploads during motion. */
@RunWith(AndroidJUnit4::class)
class EnginePreparedScrollTimingTest {
    private var temporaryOriginal: File? = null
    @Test fun comparePreparedRendererCadenceInAlternatingOrder() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var canvasBitmap: Bitmap? = null
        val hideNavigation = InstrumentationRegistry.getArguments().getString("engineHideNavigationForTiming") == "true"
        val includeCanvas = InstrumentationRegistry.getArguments().getString("engineIncludeCanvasControl") != "false"
        val intent = Intent(instrumentation.targetContext, EngineBufferedProbeActivity::class.java)
            .putExtra("engineHideNavigationForTiming", hideNavigation)
        try { ActivityScenario.launch<EngineBufferedProbeActivity>(intent).use { scenario ->
            lateinit var probe: EngineBufferedProbeActivity
            scenario.onActivity { probe = it }
            val view = probe.ready.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val (page, pixels) = pixels(view.width)
            val results = JSONArray()
            val canvas = requireNotNull(BitmapFactory.decodeFile(page.file.absolutePath)).also { canvasBitmap = it }
            val canvasControls = JSONArray()
            val context = instrumentation.targetContext
            val output = File(context.getExternalFilesDir(null), "engine-capture-prepared-timing-${System.nanoTime()}")
            check(output.mkdirs())
            var failure: Throwable? = null
            try {
                if (includeCanvas) measurePreparedCanvasControl(scenario, canvas, view.width, view.height,
                    File(output, "canvas-before.png")) { canvasControls.put(it) }
                for ((round, buffered) in listOf(false, true, true, false).withIndex()) {
                    val frames = mutableListOf<EngineSurfacePresentation>()
                    val last = CompletableDeferred<Unit>()
                    val failures = mutableListOf<Throwable>()
                    val owner = EngineSurfaceOwner(pixels.byteCount * 2, {
                        frames += it
                        if (it.identity.inputRevision == 239L) last.complete(Unit)
                    }, { failures += it }, {}, bufferedCompositor = buffered)
                    var offered = emptyList<Long>()
                    try {
                        owner.prepare()
                        assertTrue(owner.attach(view.holder.surface, view.width, view.height, 60F))
                        if (!buffered) owner.setSwapIntervalForVerification(0)
                        val textures = List(2) { owner.upload(pixels, owner.rendererEpoch) }
                        offered = animate(scenario, owner, textures, view.width, view.height)
                        withTimeout(10000) { last.await() }
                        owner.clearScene()
                        textures.forEach { owner.release(it) }
                        assertEquals(0L, owner.ownership().bytes)
                    } finally { owner.close() }
                    assertTrue(failures.toString(), failures.isEmpty())
                    assertEquals(owner.closedSubmissionCount, frames.size.toLong())
                    results.put(JSONObject().put("round", round).put("buffered", buffered)
                        .put("offeredFrameTimesNanos", JSONArray(offered))
                        .put("frames", JSONArray(frames.map { frame -> JSONObject()
                            .put("token", frame.identity.token).put("inputRevision", frame.identity.inputRevision)
                            .put("submittedAtNanos", frame.submittedAtNanos).put("renderDurationNanos", frame.renderLatencyNanos)
                            .put("timestampKind", frame.timestampKind.name).put("timestampNanos", frame.timestampNanos)
                            .put("swapSucceeded", frame.swapSucceeded) })))
                }
                if (includeCanvas) measurePreparedCanvasControl(scenario, canvas, view.width, view.height,
                    File(output, "canvas-after.png")) { canvasControls.put(it) }
            } catch (error: Throwable) { failure = error; throw error }
            finally { try {
                File(output, "prepared-timing.json").writeText(JSONObject()
                    .put("scope", "PREPARED_RENDERER_DIAGNOSTIC_ONLY").put("performanceQualified", false)
                    .put("physicalPresentationVerified", false).put("sourceSha256", page.sha256)
                    .put("hideNavigationForTiming", hideNavigation)
                    .put("canvasControlsRequested", includeCanvas)
                    .put("sourceWidth", page.dimensions.widthPx).put("sourceHeight", page.dimensions.heightPx)
                    .put("viewportWidth", view.width).put("viewportHeight", view.height).put("runs", results)
                    .put("canvasControls", canvasControls).put("failure", failure?.toString() ?: JSONObject.NULL).toString())
            } finally { pixels.close(); temporaryOriginal?.let { check(it.delete()) } } }
        } } finally { canvasBitmap?.recycle() }
    }

    private suspend fun animate(scenario: ActivityScenario<EngineBufferedProbeActivity>, owner: EngineSurfaceOwner,
        textures: List<EngineTexture>, width: Int, height: Int,
    ): List<Long> {
        val done = CompletableDeferred<Unit>()
        val times = mutableListOf<Long>()
        val pageHeight = 1080L * width * 1024 / 760
        val travel = (pageHeight * 2 - height * 1024L).coerceAtMost(500 * 1024L)
        require(travel > 0)
        lateinit var choreographer: Choreographer
        lateinit var callback: Choreographer.FrameCallback
        scenario.onActivity {
            choreographer = Choreographer.getInstance()
            callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val index = times.size
                    times += frameTimeNanos
                    val phase = index % 120
                    val offset = (if (phase < 60) phase else 120 - phase) * travel / 60
                    owner.offer(EngineSurfaceScene(1, 1, index.toLong(), 1, EngineViewport(width, height), null,
                        textures.mapIndexed { ordinal, texture -> EngineTexturePlacement(texture,
                            Math.toIntExact(ordinal * pageHeight - offset),
                            Math.toIntExact((ordinal + 1) * pageHeight - offset)) }, 1024, true))
                    if (times.size < 240) choreographer.postFrameCallback(this) else done.complete(Unit)
                }
            }
            choreographer.postFrameCallback(callback)
        }
        try { withTimeout(20000) { done.await() } }
        finally { scenario.onActivity { choreographer.removeFrameCallback(callback) } }
        return times.toList()
    }

    private suspend fun pixels(displayWidth: Int): Pair<StoredPage, NativeEnginePixels> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("enginePreparedTimingSha")
        val file = if (supplied == null) File.createTempFile("engine-prepared-timing-", ".png", context.cacheDir)
            .also { temporaryOriginal = it } else {
            require(supplied.matches(Regex("[0-9a-f]{64}")))
            requireNotNull(File(context.applicationInfo.dataDir, "app_engine_pages_v1/pages").listFiles()
                ?.firstOrNull { it.name.endsWith("-$supplied.page") }) { "Captured original is not present in the cache" }
        }
        if (supplied == null) {
            val bitmap = Bitmap.createBitmap(760, 1080, Bitmap.Config.ARGB_8888)
            try {
                val colors = IntArray(760 * 1080) { index ->
                    val x = index % 760; val y = index / 760
                    (255 shl 24) or ((y * 17 and 255) shl 16) or ((x * 3 and 255) shl 8) or (y * 11 and 255)
                }
                bitmap.setPixels(colors, 0, 760, 0, 0, 760, 1080)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (supplied != null) assertEquals(supplied, sha)
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, header)
        require(header.outWidth == 760 && header.outHeight == 1080)
        val id = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "prepared-timing"), "1"), 0)
        val page = StoredPage(id, "1", file, file.length(), sha, PageDimensions(760, 1080), header.outMimeType)
        val tile = EngineTileSpec(id, "1", sha, page.dimensions, 0, 1080, displayWidth)
        return page to (NativeEngineImageDecoder().decode(page, tile) as NativeEnginePixels)
    }
}
