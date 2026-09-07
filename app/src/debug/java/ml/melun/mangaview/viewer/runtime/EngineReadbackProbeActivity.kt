package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class EngineReadbackSurfaceGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val surfaceEpoch: Long,
)

/** Debug-only exact-pixel fixture for the native asynchronous strip readback contract. */
internal class EngineReadbackProbeActivity : Activity(), SurfaceHolder.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownerThread = HandlerThread("engine-readback-probe").apply { start() }
    private val ownerHandler = Handler(ownerThread.looper)
    private val rendererReady = CompletableFuture<Unit>()
    private val closeFuture = AtomicReference<CompletableFuture<Unit>?>(null)
    private val captureFuture = AtomicReference<CompletableFuture<Unit>?>(null)
    private val captureFrame4Future = AtomicReference<CompletableFuture<Unit>?>(null)
    private val surfaceEpochCounter = AtomicLong(0L)
    private val packetLock = Any()
    private val rawPackets = linkedMapOf<Long, ByteArray>()
    private val presentationLock = Any()
    private val presentedRecords = ArrayList<EngineReadbackPresentationRecord>()
    private val rendererCallback = OwnedRendererCallback { token, atNanos, timestampKind, frameId ->
        synchronized(presentationLock) {
            if (presentedRecords.size < MAX_PRESENTATION_RECORDS) {
                presentedRecords += EngineReadbackPresentationRecord(
                    token, atNanos, timestampKind, frameId,
                )
            }
        }
    }
    private val captureFrameCallback = Choreographer.FrameCallback {
        captureCallbackPosted = false
        if (captureActive && !capturePaused) ownerHandler.post { pumpCapture() }
    }
    private val captureWatchdog = Runnable {
        if (captureActive) {
            ownerHandler.post {
                if (captureActive) failCapture(TimeoutException("engine readback fixture exceeded 5s"))
            }
        }
    }

    @Volatile
    private var rendererHandle = 0L
    @Volatile
    private var currentSurface: Surface? = null
    @Volatile
    private var currentSurfaceReady = CompletableFuture<Unit>()
    @Volatile
    private var captureActive = false
    @Volatile
    private var capturePaused = false
    @Volatile
    private var captureTextureKeys = LongArray(0)

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var fixtureDirectoryValue: java.io.File
    private var recreateReady: CompletableFuture<Unit>? = null
    private var nativeAttached = false
    private var captureCallbackPosted = false
    @Volatile
    private var captureFrameCount = 0
    @Volatile
    private var capturePendingToken = 0L
    @Volatile
    private var captureDeadlineUptimeMillis = 0L
    private var ownedTextureKeys = LongArray(0)
    private var captureSessionId = 0L

    internal val fixtureDirectory: java.io.File
        get() = fixtureDirectoryValue

    internal val sessionId: Long
        get() = captureSessionId

    internal val rendererEpoch: Long = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixtureDirectoryValue = java.io.File(
            requireNotNull(getExternalFilesDir(null)),
            "engine-readback-fixtures/run-${SystemClock.elapsedRealtimeNanos()}",
        ).also { check(it.mkdirs()) }
        captureSessionId = SystemClock.elapsedRealtimeNanos().also { check(it > 0L) }
        root = FrameLayout(this)
        setContentView(root)
        installSurface(CompletableFuture())
        ownerHandler.post {
            try {
                rendererHandle = OwnedRendererBridge.nativeCreate(rendererCallback)
                check(rendererHandle != 0L) { "Native readback fixture renderer creation failed" }
                rendererReady.complete(Unit)
            } catch (failure: Throwable) {
                rendererReady.completeExceptionally(failure)
            }
        }
    }

    internal fun awaitSurfaceReady(): CompletableFuture<Unit> = currentSurfaceReady

    internal fun surfaceGeometry(): CompletableFuture<EngineReadbackSurfaceGeometry> {
        val result = CompletableFuture<EngineReadbackSurfaceGeometry>()
        mainHandler.post {
            try {
                val frame = surfaceView.holder.surfaceFrame
                result.complete(
                    EngineReadbackSurfaceGeometry(
                        surfaceView.width,
                        surfaceView.height,
                        frame.width(),
                        frame.height(),
                        surfaceEpochCounter.get(),
                    ),
                )
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    internal fun uploadCpuTile(cpuTileHandle: Long): CompletableFuture<Long> = postOwner {
        check(cpuTileHandle != 0L)
        val handle = requireRenderer()
        val key = OwnedRendererBridge.nativeUpload(handle, cpuTileHandle, WIDTH, HEIGHT, 0, HEIGHT, HEIGHT)
        check(key > 0L) { "Native fixture texture upload failed" }
        ownedTextureKeys += key
        key
    }

    internal fun startCapture(textureKeys: LongArray): CompletableFuture<Unit> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Fixture capture must start off the UI thread" }
        require(textureKeys.size == 2 && textureKeys.all { it > 0L })
        check(captureFuture.get() == null) { "Fixture capture started more than once" }
        val result = CompletableFuture<Unit>()
        val frame4 = CompletableFuture<Unit>()
        check(captureFuture.compareAndSet(null, result))
        captureFrame4Future.set(frame4)
        captureTextureKeys = textureKeys.copyOf()
        mainHandler.post {
            if (result.isDone) return@post
            check(currentSurfaceReady.isDone && currentSurfaceReady.isCompletedExceptionally.not()) {
                "Fixture surface is not attached"
            }
            captureFrameCount = 0
            capturePendingToken = 0L
            capturePaused = false
            captureActive = true
            captureDeadlineUptimeMillis = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MILLIS
            scheduleCaptureFrame()
            mainHandler.postDelayed(captureWatchdog, CAPTURE_TIMEOUT_MILLIS)
        }
        return result
    }

    internal fun awaitFrame4(): CompletableFuture<Unit> = requireNotNull(captureFrame4Future.get()) {
        "Capture has not started"
    }

    internal fun resumeAfterSurfaceRecreation(): CompletableFuture<Unit> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Fixture resume must run off the UI thread" }
        val result = CompletableFuture<Unit>()
        mainHandler.post {
            try {
                check(captureActive && capturePaused && captureFrameCount == 4) {
                    "Fixture is not paused after frame four"
                }
                capturePaused = false
                scheduleCaptureFrame()
                result.complete(Unit)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    internal fun recreateSurfaceView(): CompletableFuture<Unit> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Surface recreation must run off the UI thread" }
        val result = CompletableFuture<Unit>()
        mainHandler.post {
            if (recreateReady != null) {
                result.completeExceptionally(IllegalStateException("Surface recreation already pending"))
            } else {
                recreateReady = result
                root.removeView(surfaceView)
            }
        }
        return result
    }

    internal fun duplicateFutureTicketProbe(): CompletableFuture<Unit> = postOwner {
        check(!captureActive && capturePendingToken == 0L) { "Duplicate-ticket probe overlapped capture" }
        val handle = requireRenderer()
        val token = 9L
        check(OwnedRendererBridge.nativeTakeReadback(handle, token) == null) {
            "Readback ticket was present before its matching frame"
        }
        check(requestReadback(handle, token)) { "Future readback ticket was rejected" }
        check(!requestReadback(handle, token)) { "Duplicate future readback ticket was accepted" }
        check(nativeAttached) { "Duplicate-ticket probe lost its surface" }
        OwnedRendererBridge.nativeDetach(handle)
        nativeAttached = false
        val cancelled = OwnedRendererBridge.nativeTakeReadback(handle, token)
            ?: error("Cancelled duplicate ticket was not retained")
        check(EngineReadbackPacket.parse(cancelled).status == EngineReadbackPacket.Status.CANCELLED)
        val surface = requireNotNull(currentSurface) { "Fixture surface disappeared during ticket cleanup" }
        check(OwnedRendererBridge.nativeAttach(handle, surface)) { "Fixture surface reattach failed" }
        nativeAttached = true
    }

    internal fun rawPacketSnapshot(): Map<Long, ByteArray> = synchronized(packetLock) {
        rawPackets.mapValues { (_, packet) -> packet.copyOf() }
    }

    internal fun presentedSnapshot(): List<EngineReadbackPresentationRecord> =
        synchronized(presentationLock) { presentedRecords.toList() }

    internal fun assertReadbackCountsZero(): CompletableFuture<Unit> = postOwner {
        assertZeroCounts(requireRenderer())
    }

    internal fun closeFixture(): CompletableFuture<Unit> {
        closeFuture.get()?.let { return it }
        val result = CompletableFuture<Unit>()
        if (!closeFuture.compareAndSet(null, result)) return requireNotNull(closeFuture.get())
        mainHandler.post {
            captureActive = false
            capturePaused = true
            if (captureCallbackPosted) {
                Choreographer.getInstance().removeFrameCallback(captureFrameCallback)
                captureCallbackPosted = false
            }
            mainHandler.removeCallbacks(captureWatchdog)
        }
        ownerHandler.post {
            try {
                val handle = rendererHandle
                if (handle != 0L) {
                    if (nativeAttached || capturePendingToken != 0L) {
                        OwnedRendererBridge.nativeDetach(handle)
                        nativeAttached = false
                    }
                    drainKnownPackets(handle)
                    assertZeroCounts(handle)
                    ownedTextureKeys.forEach { OwnedRendererBridge.nativeReleaseTexture(handle, it) }
                    ownedTextureKeys = LongArray(0)
                    OwnedRendererBridge.nativeDestroy(handle)
                    rendererHandle = 0L
                }
                captureFuture.get()?.takeIf { !it.isDone }?.completeExceptionally(
                    CancellationException("Fixture closed before capture completed"),
                )
                ownerThread.quitSafely()
                result.complete(Unit)
            } catch (failure: Throwable) {
                ownerThread.quitSafely()
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    internal fun joinOwnerThread(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Joining fixture owner on the UI thread" }
        require(timeoutMillis > 0L)
        ownerThread.join(timeoutMillis)
        return !ownerThread.isAlive
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        check(Looper.myLooper() == Looper.getMainLooper())
        surfaceEpochCounter.incrementAndGet()
        currentSurface = holder.surface
        val ready = currentSurfaceReady
        ownerHandler.post {
            try {
                val handle = requireRenderer()
                check(OwnedRendererBridge.nativeAttach(handle, holder.surface)) {
                    "Native readback fixture surface attach failed"
                }
                nativeAttached = true
                ready.complete(Unit)
            } catch (failure: Throwable) {
                ready.completeExceptionally(failure)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (width != WIDTH || height != HEIGHT) {
            currentSurfaceReady.completeExceptionally(
                IllegalStateException("Fixture surface changed to ${width}x$height"),
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (rendererHandle == 0L) {
            currentSurface = null
            return
        }
        val acknowledgement = postOwner {
            val handle = rendererHandle
            if (handle != 0L && nativeAttached) {
                OwnedRendererBridge.nativeDetach(handle)
                nativeAttached = false
            }
        }
        try {
            acknowledgement.get(SURFACE_DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            throw AssertionError("Native detach was not acknowledged before surface destruction returned", failure)
        }
        currentSurface = null
        val replacement = recreateReady
        recreateReady = null
        if (replacement != null) mainHandler.post { installSurface(replacement) }
    }

    override fun onDestroy() {
        closeFixture()
        super.onDestroy()
    }

    private fun installSurface(ready: CompletableFuture<Unit>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        currentSurfaceReady = ready
        surfaceView = SurfaceView(this).also { view ->
            view.holder.setFormat(PixelFormat.OPAQUE)
            view.holder.setFixedSize(WIDTH, HEIGHT)
            view.holder.addCallback(this)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(WIDTH, HEIGHT))
    }

    private fun scheduleCaptureFrame() {
        if (!captureActive || capturePaused || captureCallbackPosted) return
        captureCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(captureFrameCallback)
    }

    private fun pumpCapture() {
        if (!captureActive) return
        if (SystemClock.uptimeMillis() > captureDeadlineUptimeMillis) {
            failCapture(TimeoutException("engine readback fixture exceeded 5s"))
            return
        }
        val handle = requireRenderer()
        OwnedRendererBridge.nativePollPresentations(handle)
        if (capturePendingToken != 0L) {
            val token = capturePendingToken
            val packet = OwnedRendererBridge.nativeTakeReadback(handle, token)
            if (packet == null) {
                postNextCaptureFrameIfPending()
                return
            }
            synchronized(packetLock) { rawPackets[token] = packet.copyOf() }
            capturePendingToken = 0L
            captureFrameCount++
            if (captureFrameCount == 4) {
                capturePaused = true
                requireNotNull(captureFrame4Future.get()).complete(Unit)
                return
            }
        }
        if (captureFrameCount >= FRAME_COUNT.toInt()) {
            completeCapture()
            return
        }
        submitNextFrame(handle)
        postNextCaptureFrameIfPending()
    }

    private fun submitNextFrame(handle: Long) {
        val token = (captureFrameCount + 1).toLong()
        check(OwnedRendererBridge.nativeTakeReadback(handle, token) == null) {
            "Readback ticket was present before matching frame $token"
        }
        check(requestReadback(handle, token)) { "Readback request rejected for frame $token" }
        val key = captureTextureKeys[(token.toInt() - 1) % 2]
        val packed = packScene(key)
        check(
            OwnedRendererBridge.nativeSubmit(
                handle, token, WIDTH, HEIGHT, HEIGHT, 0, 0L, 60.0F, token, 1, packed,
            ) > 0,
        ) { "Native fixture submit failed for frame $token" }
        capturePendingToken = token
    }

    private fun requestReadback(handle: Long, token: Long): Boolean =
        OwnedRendererBridge.nativeRequestReadback(
            handle, token, captureSessionId, rendererEpoch, surfaceEpochCounter.get(), STRIP_TOP, STRIP_BOTTOM,
        )

    private fun postNextCaptureFrameIfPending() {
        if (capturePendingToken == 0L || capturePaused) return
        mainHandler.post { scheduleCaptureFrame() }
    }

    private fun completeCapture() {
        captureActive = false
        mainHandler.post {
            if (captureCallbackPosted) Choreographer.getInstance().removeFrameCallback(captureFrameCallback)
            captureCallbackPosted = false
            mainHandler.removeCallbacks(captureWatchdog)
        }
        requireNotNull(captureFuture.get()).complete(Unit)
    }

    private fun failCapture(failure: Throwable) {
        captureActive = false
        capturePaused = true
        mainHandler.post {
            if (captureCallbackPosted) Choreographer.getInstance().removeFrameCallback(captureFrameCallback)
            captureCallbackPosted = false
            mainHandler.removeCallbacks(captureWatchdog)
        }
        requireNotNull(captureFuture.get()).completeExceptionally(failure)
        requireNotNull(captureFrame4Future.get()).completeExceptionally(failure)
    }

    private fun drainKnownPackets(handle: Long) {
        for (token in 1L..(FRAME_COUNT + 1L)) OwnedRendererBridge.nativeTakeReadback(handle, token)
        capturePendingToken = 0L
    }

    private fun assertZeroCounts(handle: Long) {
        val counts = OwnedRendererBridge.nativeReadbackCounts(handle)
        check(counts.size == 5) { "Native readback count vector is malformed" }
        check(counts.all { it == 0L }) { "Native readback primitive retained counts: ${counts.contentToString()}" }
    }

    private fun requireRenderer(): Long = rendererHandle.also { check(it != 0L) { "Native fixture renderer is unavailable" } }

    private fun <T> postOwner(operation: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (!ownerHandler.post {
            try {
                result.complete(operation())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }) result.completeExceptionally(IllegalStateException("Fixture owner thread is stopped"))
        return result
    }

    private fun packScene(textureKey: Long): IntArray = intArrayOf(
        textureKey.toInt(),
        (textureKey ushr 32).toInt(),
        0,
        HEIGHT,
        HEIGHT,
        0,
        HEIGHT,
    )

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 96
        const val STRIP_TOP = 8
        const val STRIP_BOTTOM = 56
        const val FRAME_COUNT = 8L
        const val CAPTURE_TIMEOUT_MILLIS = 5_000L
        const val SURFACE_DESTROY_TIMEOUT_MILLIS = 5_000L
        const val MAX_PRESENTATION_RECORDS = 16
    }
}

internal data class EngineReadbackPresentationRecord(
    val token: Long,
    val atNanos: Long,
    val timestampKind: Int,
    val frameId: Long,
)
