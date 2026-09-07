package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.FrameLayout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.json.JSONObject
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneSnapshot

internal class OwnedRendererProbeActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surface: SurfaceView
    private lateinit var renderer: OwnedSurfaceRenderer
    private val ready = CountDownLatch(1)
    private val geometryReady = CountDownLatch(1)
    private val geometryAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var geometryMode = "static"
    private var uploadMode = "pbo"
    private val scene = AtomicReference<SceneSnapshot?>()
    private val presentations = CopyOnWriteArrayList<OwnedPresentation>()
    private val presentationMonitor = Object()
    private val stillFrame = AtomicReference<Pair<Long, CountDownLatch>?>()
    private val vsyncs = CopyOnWriteArrayList<Long>()
    private val contextRestored = AtomicReference<CountDownLatch?>()
    private var frame = 0L
    @Volatile private var frameOffers = 0L
    @Volatile private var acceptingFrames = true
    private val clockSampleMonotonicNanos = System.nanoTime()
    private val clockSampleEpochMillis = System.currentTimeMillis()
    private val clockSampleMonotonicAfterNanos = System.nanoTime()
    private lateinit var frameScheduler: ViewerVsyncScheduler

    val uploadPort: OwnedTextureUploadPort by lazy { OwnedTextureUploadPort(renderer) }
    val decodePort = NativeCpuDecodePort()
    val rendererEpoch: Long get() = renderer.rendererEpoch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = OwnedSurfaceRenderer(
            reportPresented = { value ->
                presentations.add(value)
                synchronized(presentationMonitor) { presentationMonitor.notifyAll() }
                stillFrame.get()?.takeIf { it.first == value.scene.viewportRevision }
                    ?.second?.countDown()
            },
            reportFailure = { failure -> throw AssertionError(failure) },
            reportInvalidated = { contextRestored.getAndSet(null)?.countDown() },
        )
        geometryMode = intent.getStringExtra("probeGeometryMode") ?: "static"
        require(geometryMode == "streaming" || geometryMode == "static")
        renderer.setStaticQuadForVerification(geometryMode == "static") { accepted ->
            geometryAccepted.set(accepted)
            geometryReady.countDown()
        }
        frameScheduler = ViewerVsyncScheduler(Choreographer.getInstance(), ::doFrame)
        surface = SurfaceView(this).also { view ->
            view.holder.setFormat(PixelFormat.OPAQUE)
            view.holder.addCallback(this)
        }
        val root = FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        frameScheduler.post()
    }

    fun awaitSurface(): Boolean = ready.await(10L, TimeUnit.SECONDS) &&
        geometryReady.await(5L, TimeUnit.SECONDS) && geometryAccepted.get()

    fun start(input: SceneSnapshot) {
        scene.set(input)
    }

    fun setGeometryModeForVerification(mode: String, completed: (Boolean) -> Unit) {
        require(mode == "streaming" || mode == "static")
        scene.set(null)
        renderer.setStaticQuadForVerification(mode == "static") { accepted ->
            if (accepted) geometryMode = mode
            completed(accepted)
        }
    }

    fun setUploadModeForVerification(mode: String, completed: (Boolean) -> Unit) {
        require(mode == "pbo" || mode == "direct")
        renderer.setDirectTextureUploadForVerification(mode == "direct") { accepted ->
            if (accepted) uploadMode = mode
            completed(accepted)
        }
    }

    fun showStill(input: SceneSnapshot): CountDownLatch {
        scene.set(null)
        val latch = CountDownLatch(1)
        stillFrame.set(input.viewportRevision to latch)
        renderer.offer(input)
        return latch
    }

    fun screenBounds(): Rect {
        val location = IntArray(2)
        surface.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + surface.width, location[1] + surface.height)
    }

    /**
     * Copies the exact SurfaceView buffer for the fixture pixel test. PixelCopy observes the
     * surface buffer, not physical scanout or whole-window composition.
     */
    fun copySurfaceBufferForVerification(): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PixelCopy wait cannot run on the UI thread" }
        check(surface.width > 0 && surface.height > 0) {
            "SurfaceView has no dimensions for PixelCopy: ${surface.width}x${surface.height}"
        }
        val bufferBounds = surface.holder.surfaceFrame
        check(bufferBounds.width() == surface.width && bufferBounds.height() == surface.height) {
            "Fixture surface buffer and viewport dimensions differ"
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        val finished = CountDownLatch(1)
        val ownership = Any()
        var requested = false
        var completed = false
        var abandoned = false
        try {
            PixelCopy.request(
                surface,
                bitmap,
                { status ->
                    synchronized(ownership) {
                        result.set(status)
                        completed = true
                        if (abandoned) bitmap.recycle()
                    }
                    finished.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            requested = true
            check(finished.await(PIXEL_COPY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "PixelCopy surface-buffer callback timed out after ${PIXEL_COPY_TIMEOUT_MILLIS}ms"
            }
            check(result.get() == PixelCopy.SUCCESS) {
                "PixelCopy surface-buffer request failed with status ${result.get()}"
            }
            return bitmap
        } catch (failure: Throwable) {
            // A timed-out request can still own the bitmap. Its callback releases
            // it when the copy completes, instead of recycling an in-flight target.
            synchronized(ownership) {
                abandoned = true
                if (!requested || completed) bitmap.recycle()
            }
            throw failure
        }
    }

    fun presentationSnapshot(): List<OwnedPresentation> = presentations.toList()

    /** Debug-only event waits; avoids polling sleeps in the deferred-poll regression. */
    fun awaitPresentationCountAtLeast(target: Int, timeoutMillis: Long): Boolean {
        require(target >= 0)
        return awaitPresentationCondition(timeoutMillis) { it >= target }
    }

    fun frameProductionActiveForVerification(): Boolean = acceptingFrames

    fun frameOfferCountForVerification(): Long = frameOffers

    /** Waits through the full bounded interval and rejects any callback-count change. */
    fun awaitPresentationCountUnchanged(expected: Int, timeoutMillis: Long): Boolean {
        require(expected >= 0)
        require(timeoutMillis > 0L)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        synchronized(presentationMonitor) {
            while (true) {
                if (presentations.size != expected) return false
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) return true
                try {
                    presentationMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
    }

    private fun awaitPresentationCondition(
        timeoutMillis: Long,
        condition: (Int) -> Boolean,
    ): Boolean {
        require(timeoutMillis > 0L)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        synchronized(presentationMonitor) {
            while (!condition(presentations.size)) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) return false
                try {
                    presentationMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    fun recreateContext(): CountDownLatch {
        scene.set(null)
        val latch = CountDownLatch(1)
        check(contextRestored.compareAndSet(null, latch))
        renderer.recreateContextForVerification()
        return latch
    }

    fun failNextGlOperation(): CountDownLatch {
        scene.set(null)
        val latch = CountDownLatch(1)
        check(contextRestored.compareAndSet(null, latch))
        renderer.failNextGlOperationForVerification()
        return latch
    }

    fun vsyncSnapshot(): LongArray = vsyncs.toLongArray()

    private fun doFrame(
        frameTimeNanos: Long,
        frameTimelineVsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) {
        if (!acceptingFrames) return
        vsyncs += frameTimeNanos
        val input = scene.get()
        if (input != null) {
            val maximum = (input.contentHeight.units - surface.height.toLong() *
                FixedPx.UNITS_PER_PIXEL).coerceAtLeast(0L)
            val offset = ((frame * 24L * FixedPx.UNITS_PER_PIXEL) %
                maxOf(1L, maximum * 2L))
            val bounced = if (offset <= maximum) offset else maximum * 2L - offset
            renderer.offer(input.copy(
                viewportRevision = frame,
                scrollOffset = FixedPx(bounced),
            ), frameTimelineVsyncId = frameTimelineVsyncId,
                expectedPresentationTimeNanos = expectedPresentationTimeNanos)
            frameOffers += 1L
            frame += 1L
        }
        frameScheduler.post()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = attach(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        attach(holder)

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.detach()
    }

    override fun onDestroy() {
        acceptingFrames = false
        frameScheduler.cancel()
        renderer.close()
        super.onDestroy()
    }

    fun finishDiagnosticCapture(): CountDownLatch {
        acceptingFrames = false
        frameScheduler.cancel()
        scene.set(null)
        val bounds = screenBounds()
        val displaySize = android.graphics.Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(displaySize)
        val finished = CountDownLatch(1)
        renderer.closeAsync {
            try { writeDiagnosticEvidence(bounds, displaySize) } finally { finished.countDown() }
        }
        return finished
    }

    fun closeRendererForVerification(): CountDownLatch {
        acceptingFrames = false
        frameScheduler.cancel()
        scene.set(null)
        val finished = CountDownLatch(1)
        renderer.closeAsync { finished.countDown() }
        return finished
    }

    private fun writeDiagnosticEvidence(bounds: Rect, displaySize: android.graphics.Point) {
        val directory = File(getExternalFilesDir("ux-evidence"), "native-display-probe-${System.nanoTime()}")
        check(directory.mkdirs())
        val source = File(cacheDir, "owned-renderer-probe.jpg")
        if (source.isFile) source.copyTo(File(directory, "source.jpg"))
        val sourceSize = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (source.isFile) android.graphics.BitmapFactory.decodeFile(source.absolutePath, sourceSize)
        File(directory, "probe.json").writeText(JSONObject()
            .put("mode", "DIAGNOSTIC_NO_CORPUS_CREDIT")
            .put("geometryMode", geometryMode)
            .put("uploadMode", uploadMode)
            .put("processPid", android.os.Process.myPid()).put("packageName", packageName)
            .put("clockSampleMonotonicNanos", clockSampleMonotonicNanos)
            .put("clockSampleEpochMillis", clockSampleEpochMillis)
            .put("clockPairs", org.json.JSONArray().put(JSONObject()
                .put("nativeBeforeNanos", clockSampleMonotonicNanos)
                .put("epochMillis", clockSampleEpochMillis)
                .put("nativeAfterNanos", clockSampleMonotonicAfterNanos)))
            .put("surfaceBounds", JSONObject().put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            .put("displayWidthPx", displaySize.x).put("displayHeightPx", displaySize.y)
            .put("sourceWidthPx", sourceSize.outWidth).put("sourceHeightPx", sourceSize.outHeight)
            .put("left", bounds.left).put("top", bounds.top)
            .put("width", bounds.width()).put("height", bounds.height()).toString())
        File(directory, "frames.jsonl").bufferedWriter().use { output ->
            presentations.forEach { value ->
                output.appendLine(JSONObject()
                    .put("token", value.token).put("bufferFrameId", value.bufferFrameId)
                    .put("submittedAtNanos", value.submittedAtNanos)
                    .put("renderLatencyNanos", value.renderLatencyNanos)
                    .put("presentedNanos", value.presentedAtNanos)
                    .put("timestampKind", value.timestampKind.name)
                    .put("scrollOffsetPx", value.scene.scrollOffset.toPixels())
                    .put("sceneRevision", value.scene.sceneRevision).toString())
            }
        }
    }

    private fun attach(holder: SurfaceHolder) {
        if (!holder.surface.isValid || surface.width <= 0 || surface.height <= 0 ||
            ready.count == 0L) return
        renderer.attach(
            holder.surface,
            surface.width,
            surface.height,
            display?.refreshRate ?: 60.0F,
        ) { attachment -> if (attachment.attached) ready.countDown() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            surface.requestedFrameRate = SurfaceView.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    private companion object {
        const val PIXEL_COPY_TIMEOUT_MILLIS = 5_000L
    }
}
