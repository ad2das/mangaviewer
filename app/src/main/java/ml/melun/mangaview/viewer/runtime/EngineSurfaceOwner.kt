package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Trace
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.FrameIdentity

/** The new engine's sole GL owner. All native effects and resource acknowledgements use this thread. */
internal class EngineSurfaceOwner(
    private val textureAllocationLimit: Long,
    private val reportPresented: (EngineSurfacePresentation) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val reportInvalidated: (Long) -> Unit,
    private val reportSurfaceLost: () -> Unit = {},
    private val maximumPendingForVerification: Int? = null,
    private val presentationPollMillisForVerification: Long? = null,
) : EngineTextureUploader {
    init {
        require(textureAllocationLimit > 0 && (maximumPendingForVerification == null || maximumPendingForVerification > 0))
        require(presentationPollMillisForVerification == null || presentationPollMillisForVerification in 1L..16L)
    }
    override val rendererId: Long = nextRenderer.incrementAndGet()
    private val epoch = AtomicLong(1)
    override val rendererEpoch: Long get() = epoch.get()
    private val closing = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    @Volatile var closedSubmissionCount: Long? = null
        private set
    private val native = OwnedRendererBridge.nativeCreate(OwnedRendererCallback(::presented)).also {
        check(it != 0L) { "GL owner creation failed" }
    }
    private val thread = HandlerThread("engine-gl-$rendererId", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher("engine-gl-$rendererId")
    private val pending = linkedMapOf<Long, Pending>()
    private val readbacks = EngineSurfaceReadbacks(native)
    private val nextCapture = EngineNextFrameCapture()
    private val retiring = linkedMapOf<Long, MutableList<CompletableDeferred<Unit>>>()
    private var capacityChanged = CompletableDeferred<Unit>()
    private val lock = Any()
    private var latest: EngineSurfaceScene? = null
    private var posted = false
    private var attached = false
    private var surfaceEpoch = 0L
    private var width = 0
    private var height = 0
    private var refreshRate = 60F
    private var nextToken = 0L
    private val captureRasterizationReader = EngineCaptureRasterizationReader({ rendererEpoch }, { surfaceEpoch }) {
        requireNotNull(OwnedRendererBridge.nativeRasterizationInfoForVerification(native))
    }
    private var lastFrameStartedNanos = 0L
    private var configured = false
    private var choreographer: Choreographer? = null
    private var drawCallbackPosted = false
    private val draw = Choreographer.FrameCallback {
        drawCallbackPosted = false
        renderLatest()
    }
    private var pollPosted = false
    private val poll = Choreographer.FrameCallback {
        pollPosted = false
        if (!destroyed.get()) {
            OwnedRendererBridge.nativePollPresentations(native)
            readbacks.poll()
            if (maximumPendingForVerification != null && pending.size < maximumPendingForVerification &&
                synchronized(lock) { latest != null }) renderLatest()
            if (pending.isNotEmpty() || readbacks.pending) schedulePoll()
        }
    }
    private val timedPoll = Runnable { poll.doFrame(System.nanoTime()) }

    init {
        check(handler.post {
            configured = OwnedRendererBridge.nativeSetTextureBudget(native, textureAllocationLimit)
            if (!configured) reportFailure(IllegalStateException("Native texture allocation limit rejected"))
        })
    }

    suspend fun attach(surface: Surface, width: Int, height: Int, rate: Float): Boolean = onOwner {
        if (closing.get()) return@onOwner false
        require(width > 0 && height > 0)
        check(configured)
        attached = OwnedRendererBridge.nativeAttach(native, surface)
        if (attached) {
            surfaceEpoch = Math.incrementExact(surfaceEpoch)
            this.width = width
            this.height = height
            refreshRate = rate.takeIf { it.isFinite() && it > 0F } ?: 60F
            renderLatest()
        }
        attached
    }

    suspend fun detach() = onOwner {
        if (!destroyed.get()) {
            attached = false
            synchronized(lock) { latest = null }
            check(OwnedRendererBridge.nativeClearScene(native))
            acknowledgeRetirements()
            OwnedRendererBridge.nativeDetach(native)
            terminatePending(PresentationTimestampKind.CANCELLED)
            if (readbacks.pending) schedulePoll()
        }
    }

    internal suspend fun setSwapIntervalForVerification(interval: Int) = onOwner {
        check(attached && nextToken == 0L && !closing.get())
        check(OwnedRendererBridge.nativeSetSwapIntervalForVerification(native, interval))
    }

    internal suspend fun rasterizationInfoForVerification(): IntArray = onOwner {
        check(attached && !closing.get())
        requireNotNull(OwnedRendererBridge.nativeRasterizationInfoForVerification(native))
    }

    /** Removes native scene references without pretending to have displayed a new buffer. */
    suspend fun clearScene() {
        try {
            onOwner {
                if (!destroyed.get()) {
                    synchronized(lock) { latest = null }
                    check(OwnedRendererBridge.nativeClearScene(native))
                    acknowledgeRetirements()
                }
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun offer(scene: EngineDrawScene) = offer(EngineSurfaceScene.from(scene))

    /** Arms the next natural submission; does not manufacture input, change latest, or force a draw. */
    suspend fun captureNextFrame(top: Int, bottom: Int): EngineCapturedFrame = armCapture(top, bottom)

    suspend fun captureNextViewportFrame(): EngineCapturedFrame = armCapture(0, null)

    private suspend fun armCapture(top: Int, bottom: Int?): EngineCapturedFrame {
        var ticket: EngineNextFrameCapture.Ticket? = null
        var primary: Throwable? = null
        try {
            onOwner {
                check(!closing.get() && (attached || surfaceEpoch == 0L))
                require(top >= 0 && (bottom == null || (bottom > top && (!attached || bottom <= height))))
                // A real catalog tap can return before the first surface callback. Arm that
                // first epoch immediately; bind validates its actual viewport without delaying input.
                ticket = nextCapture.request(if (attached) surfaceEpoch else 1L, top, bottom)
            }
            val capture = requireNotNull(ticket)
            return capture.result(capture.bound.await().await())
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            val owned = ticket
            if (owned != null) withContext(NonCancellable) {
                try {
                    val receipt = if (destroyed.get()) owned.receipt else onOwner { nextCapture.cancel(owned) }
                    receipt?.await()
                } catch (cleanup: Throwable) {
                    if (primary == null) throw cleanup
                    if (cleanup !== primary) primary?.addSuppressed(cleanup)
                }
            }
        }
    }

    /** Captures the exact submitted scene; successful readback does not claim physical presentation. */
    suspend fun capture(scene: EngineSurfaceScene, top: Int, bottom: Int): EngineReadbackPacket =
        withContext(NonCancellable) {
            val receipt = onOwner {
                check(attached && !closing.get() && scene.viewport.widthPx == width && scene.viewport.heightPx == height)
                require(top >= 0 && bottom > top && bottom <= height)
                require(scene.placements.all { it.texture.rendererId == rendererId && it.texture.rendererEpoch == rendererEpoch })
                val identity = FrameIdentity(scene.sessionId, rendererEpoch, surfaceEpoch,
                    Math.incrementExact(nextToken), scene.inputRevision, scene.geometryRevision)
                val receiver = readbacks.request(identity, top, bottom)
                synchronized(lock) { latest = scene.copy(placements = scene.placements.toList()) }
                renderLatest()
                schedulePoll()
                receiver
            }
            receipt.await()
        }

    fun offer(scene: EngineSurfaceScene) {
        val schedule = synchronized(lock) {
            if (closing.get()) return
            latest = scene.copy(placements = scene.placements.toList())
            if (posted) false else true.also { posted = true }
        }
        if (schedule) check(handler.post(::scheduleDraw)) { "GL owner queue rejected a frame" }
    }

    /** Consume the latest accumulated scene at display cadence; input processing remains independent. */
    private fun scheduleDraw() {
        if (drawCallbackPosted || closing.get()) return
        if (attached && EngineFrameCadence.due(lastFrameStartedNanos, System.nanoTime(), refreshRate)) {
            renderLatest()
            return
        }
        drawCallbackPosted = true
        val clock = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        clock.postFrameCallback(draw)
    }

    override suspend fun upload(pixels: EnginePixels, expectedEpoch: Long): EngineTexture {
        val nativePixels = pixels as? NativeEnginePixels ?: error("Native GL owner requires native pixels")
        require(pixels.byteCount <= textureAllocationLimit) { "A texture exceeds the allocation limit" }
        val caller = currentCoroutineContext()[Job]
        var acquired = 0L
        try {
            while (acquired == 0L) {
                val wait = onOwner {
                    caller?.ensureActive()
                    check(!closing.get() && configured && expectedEpoch == rendererEpoch && !nativePixels.isClosed)
                    val used = OwnedRendererBridge.nativeTextureCounts(native)[1]
                    if (pixels.byteCount > textureAllocationLimit - used) return@onOwner capacityChanged
                    val tile = pixels.tile
                    acquired = OwnedRendererBridge.nativeUpload(native, nativePixels.handle, tile.displayWidth,
                        tile.decodedHeight, tile.sourceTop, tile.sourceBottom, tile.dimensions.heightPx)
                    if (acquired <= 0L && OwnedRendererBridge.nativeContextLost(native)) recoverContext()
                    check(acquired > 0L) { "Native texture upload failed" }
                    null
                }
                wait?.await()
            }
            currentCoroutineContext().ensureActive()
            return EngineTexture(pixels.tile, rendererId, expectedEpoch, acquired, pixels.byteCount).also { acquired = 0 }
        } finally {
            if (acquired > 0L) onOwner {
                if (!destroyed.get()) {
                    OwnedRendererBridge.nativeReleaseTexture(native, acquired)
                    check(!OwnedRendererBridge.nativeHasTexture(native, acquired))
                    signalCapacity()
                }
            }
        }
    }

    override suspend fun release(texture: EngineTexture) = withContext(NonCancellable) {
        require(texture.rendererId == rendererId)
        if (closing.get()) { closed.await(); return@withContext }
        val completion = onOwner {
            val done = CompletableDeferred<Unit>()
            if (destroyed.get()) done.complete(Unit) else {
                OwnedRendererBridge.nativeReleaseTexture(native, texture.key)
                acknowledgeRetirements()
                if (!OwnedRendererBridge.nativeHasTexture(native, texture.key)) done.complete(Unit)
                else retiring.getOrPut(texture.key) { mutableListOf() }.add(done)
            }
            done
        }
        completion.await()
    }

    suspend fun ownership(): EngineTextureOwnership {
        if (destroyed.get()) return EngineTextureOwnership(0, 0, 0, 0, 0)
        return onOwner {
            if (destroyed.get()) EngineTextureOwnership(0, 0, 0, 0, 0) else {
                val values = OwnedRendererBridge.nativeTextureCounts(native)
                check(values.size == 5 && values.all { it >= 0 })
                EngineTextureOwnership(values[0], values[1], values[2], values[3], values[4])
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        if (closing.compareAndSet(false, true)) {
            try {
                onOwner {
                    synchronized(lock) { latest = null }
                    choreographer?.removeFrameCallback(draw)
                    drawCallbackPosted = false
                    attached = false
                    terminatePending(PresentationTimestampKind.CANCELLED)
                    OwnedRendererBridge.nativeDestroy(native)
                    destroyed.set(true)
                    readbacks.destroyed()
                    acknowledgeRetirements()
                    closedSubmissionCount = nextToken
                }
                closed.complete(Unit)
            } catch (failure: Throwable) {
                closed.completeExceptionally(failure)
                throw failure
            } finally { thread.quitSafely() }
        }
        closed.await()
        withContext(Dispatchers.IO) { thread.join() }
    }

    private fun renderLatest() {
        if (maximumPendingForVerification != null && pending.size >= maximumPendingForVerification) return
        val scene = synchronized(lock) { posted = false; latest.also { if (attached) latest = null } }
        if (scene == null || !attached || closing.get()) return
        if (scene.viewport.widthPx != width || scene.viewport.heightPx != height) return
        if (scene.placements.any { it.texture.rendererId != rendererId || it.texture.rendererEpoch != rendererEpoch }) return
        val token = Math.incrementExact(nextToken).also { nextToken = it }
        val identity = FrameIdentity(scene.sessionId, rendererEpoch, surfaceEpoch, token, scene.inputRevision, scene.geometryRevision)
        val record = Pending(identity, scene, System.nanoTime())
        nextCapture.bind(rendererId, identity, scene, readbacks, captureRasterizationReader)
        lastFrameStartedNanos = record.submittedAt
        pending[token] = record
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection("engine_frame:${identity.sessionId}:$rendererId:$surfaceEpoch:$token:${scene.inputRevision}:${scene.geometryRevision}")
        val result = try {
            OwnedRendererBridge.nativeSubmitEngine(native, token, width, height, refreshRate,
                token, scene.placements.size, scene.pack(), scene.coordinateUnitsPerPixel)
        } finally {
            record.latency = (System.nanoTime() - record.submittedAt).coerceAtLeast(0)
            if (tracing) Trace.endSection()
        }
        finishSubmission(record, result)
    }

    private fun finishSubmission(record: Pending, result: Int) {
        record.submissionResult = result
        if (result > 0) {
            acknowledgeRetirements()
            deliver(record)
            schedulePoll()
        } else {
            record.timestamp = Timestamp(if (result == -2) PresentationTimestampKind.CONTEXT_LOST else PresentationTimestampKind.UNAVAILABLE, 0, 0)
            deliver(record)
            when (result) {
                -2 -> recoverContext()
                0 -> surfaceLost()
                else -> reportFailure(IllegalStateException("Native frame submission failed: $result"))
            }
        }
        if (readbacks.pending) schedulePoll()
    }

    private fun presented(token: Long, at: Long, kind: Int, frameId: Long) {
        val record = pending[token] ?: return
        if (record.timestamp == null) record.timestamp = Timestamp(PresentationTimestampKind.fromNative(kind), at, frameId)
        deliver(record)
    }

    private fun deliver(record: Pending) = record.deliverFrom(pending, rendererId, reportPresented)

    private fun schedulePoll() {
        if (pollPosted || (pending.isEmpty() && !readbacks.pending) || destroyed.get()) return
        val delay = presentationPollMillisForVerification
        if (delay != null) {
            pollPosted = true
            check(handler.postDelayed(timedPoll, delay))
            return
        }
        val choreographer = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        pollPosted = true
        choreographer.postFrameCallback(poll)
    }

    private fun terminatePending(kind: PresentationTimestampKind) {
        nextCapture.invalidate()
        if (pollPosted) choreographer?.removeFrameCallback(poll)
        handler.removeCallbacks(timedPoll)
        pollPosted = false
        pending.values.toList().forEach { record ->
            if (record.timestamp == null) record.timestamp = Timestamp(kind, 0, 0)
            deliver(record)
        }
    }

    private fun recoverContext() {
        terminatePending(PresentationTimestampKind.CONTEXT_LOST)
        synchronized(lock) { latest = null }
        attached = OwnedRendererBridge.nativeRecreateContext(native)
        readbacks.poll()
        val newEpoch = epoch.incrementAndGet()
        acknowledgeRetirements()
        reportInvalidated(newEpoch)
        if (!attached) reportFailure(IllegalStateException("GL context recreation failed"))
    }

    private fun surfaceLost() {
        attached = false
        synchronized(lock) { latest = null }
        check(OwnedRendererBridge.nativeClearScene(native))
        acknowledgeRetirements()
        terminatePending(PresentationTimestampKind.CANCELLED)
        if (readbacks.pending) schedulePoll()
        reportSurfaceLost()
    }

    private fun acknowledgeRetirements() {
        retiring.keys.toList().forEach { key ->
            if (destroyed.get() || !OwnedRendererBridge.nativeHasTexture(native, key)) {
                retiring.remove(key)?.forEach { it.complete(Unit) }
            }
        }
        signalCapacity()
    }

    private fun signalCapacity() {
        capacityChanged.complete(Unit)
        capacityChanged = CompletableDeferred()
    }

    private suspend fun <T> onOwner(block: () -> T): T = withContext(NonCancellable + dispatcher) { block() }

    private companion object { val nextRenderer = AtomicLong() }
}

private class Pending(val identity: FrameIdentity, val scene: EngineSurfaceScene, val submittedAt: Long) {
    var latency: Long? = null
    var submissionResult: Int? = null
    var timestamp: Timestamp? = null

    fun deliverFrom(pending: MutableMap<Long, Pending>, rendererId: Long, report: (EngineSurfacePresentation) -> Unit) {
        val timestamp = timestamp ?: return
        val latency = latency ?: return
        val result = submissionResult ?: return
        if (pending.remove(identity.token) !== this) return
        report(EngineSurfacePresentation(identity, scene, submittedAt, latency, result > 0,
            timestamp.kind, timestamp.at, timestamp.frameId, rendererId))
    }
}

private data class Timestamp(val kind: PresentationTimestampKind, val at: Long, val frameId: Long)
