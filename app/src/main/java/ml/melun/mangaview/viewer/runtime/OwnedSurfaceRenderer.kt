package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import android.view.Choreographer
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.viewer.session.SceneSnapshot

internal class OwnedSurfaceRenderer(
    private val reportPresented: (OwnedPresentation) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val reportInvalidated: (Long) -> Unit = {},
) : Closeable {
    val rendererEpoch: Long get() = epoch.get()

    private val thread = HandlerThread(
        "viewer-owned-surface",
        Process.THREAD_PRIORITY_DISPLAY,
    ).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val callback = OwnedRendererCallback(::nativePresented)
    private val presentations = ConcurrentHashMap<Long, PendingPresentation>()
    private val textureIdentities = mutableMapOf<Long, UploadedTextureIdentity>()
    private val retiredTextureKeys = mutableSetOf<Long>()
    private var installedTextureKeys: Set<Long> = emptySet()
    private val nativeHandle = OwnedRendererBridge.nativeCreate(callback)
    private val renderTask = Runnable(::renderLatest)
    private var presentationChoreographer: Choreographer? = null
    private val presentationPoll = Choreographer.FrameCallback {
        presentationPollPosted = false
        if (!closed.get()) {
            OwnedRendererBridge.nativePollPresentations(nativeHandle)
            if (presentations.isNotEmpty()) schedulePresentationPoll()
        }
    }
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var frameRate = 60.0F
    private var latest: RenderRequest? = null
    private var renderPosted = false
    private var attached = false
    private val sceneIdentity = OwnedSceneIdentity()
    private var nextToken = 1L
    private var presentationPollPosted = false
    private val closed = AtomicBoolean(false)
    private val epoch = AtomicLong(1L)

    init {
        check(nativeHandle != 0L) { "Owned renderer creation failed" }
    }

    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        refreshRate: Float,
        onComplete: (OwnedRendererAttachment) -> Unit = {},
    ) {
        require(width > 0 && height > 0)
        handler.post {
            if (closed.get()) return@post
            attached = OwnedRendererBridge.nativeAttach(nativeHandle, surface)
            if (!attached) {
                reportFailure(IllegalStateException("Owned renderer surface attach failed"))
                onComplete(OwnedRendererAttachment(false, rendererEpoch, false))
                return@post
            }
            val invalidated = surfaceWidth > 0 && surfaceWidth != width
            if (invalidated) check(epoch.incrementAndGet() > 1L)
            surfaceWidth = width
            surfaceHeight = height
            frameRate = refreshRate.takeIf { it.isFinite() && it > 0.0F } ?: 60.0F
            sceneIdentity.invalidate()
            renderLatest()
            onComplete(OwnedRendererAttachment(true, rendererEpoch, invalidated))
        }
    }

    fun detach() {
        handler.post {
            if (closed.get()) return@post
            attached = false
            sceneIdentity.invalidate()
            OwnedRendererBridge.nativeDetach(nativeHandle)
            clearPresentationPolling()
        }
    }

    fun offer(
        scene: SceneSnapshot,
        metadata: OwnedFrameMetadata? = null,
        frameTimelineVsyncId: Long = -1L,
        expectedPresentationTimeNanos: Long = 0L,
    ) {
        val shouldPost = synchronized(lock) {
            if (closed.get()) return
            latest = RenderRequest(
                scene,
                metadata,
                frameTimelineVsyncId,
                expectedPresentationTimeNanos,
                rendererEpoch,
                System.nanoTime(),
            )
            if (renderPosted) false else true.also { renderPosted = true }
        }
        if (shouldPost) handler.post(renderTask)
    }

    suspend fun upload(tile: NativeCpuTileLease): Long = uploadOnOwnerQueue(
        tile,
        handler::post,
        { pixels ->
            check(!closed.get()) { "Renderer closed before texture upload" }
            val key = OwnedRendererBridge.nativeUpload(
                nativeHandle,
                pixels.nativeHandle,
                pixels.displayWidthPx,
                pixels.displayHeightPx,
                pixels.sourceTopPx,
                pixels.sourceBottomPx,
                pixels.sourceHeightPx,
            )
            if (key > 0L) textureIdentities[key] = UploadedTextureIdentity(
                pixels.pageId, pixels.sourceTopPx, pixels.sourceBottomPx, pixels.sourceHeightPx,
            )
            if (key <= 0L && OwnedRendererBridge.nativeContextLost(nativeHandle)) recoverContext()
            key
        },
        ::release,
    )

    fun release(key: Long) {
        if (key <= 0L || closed.get()) return
        handler.post {
            if (!closed.get()) {
                retiredTextureKeys.add(key)
                pruneTextureIdentities()
                OwnedRendererBridge.nativeReleaseTexture(nativeHandle, key)
            }
        }
    }

    private fun renderLatest() {
        if (!attached || closed.get()) {
            synchronized(lock) { renderPosted = false }
            return
        }
        val request = synchronized(lock) {
            renderPosted = false
            latest.also { latest = null }
        } ?: return
        if (request.rendererEpoch != rendererEpoch) return
        val token = nextToken++
        val tracing = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
        if (tracing) android.os.Trace.beginSection("viewer_prepare:$token:${request.offeredAtNanos}")
        val packed: PackedOwnedScene
        val installation: SceneInstallation
        val pending: PendingPresentation
        try {
            packed = OwnedScenePacker.pack(request.scene)
            installation = sceneIdentity.prepare(packed)
            pending = PendingPresentation(request,
                verifiedTextureIdentities = request.scene.quads.mapNotNull { quad ->
                    val key = quad.visualKey?.value ?: return@mapNotNull null
                    textureIdentities[key]?.let { key to it }
                }.toMap())
        } finally { if (tracing) android.os.Trace.endSection() }
        presentations[token] = pending
        val result = submitFrame(token, pending, packed, installation)
        publishPresentation(token, pending)
        if (result > 0) {
            sceneIdentity.acknowledge(installation)
            installedTextureKeys = request.scene.quads.mapNotNull { it.visualKey?.value }.toSet()
            pruneTextureIdentities()
        } else {
            failedSubmit(token, pending, result)
        }
        val repeat = synchronized(lock) {
            if (latest == null || renderPosted || closed.get()) false
            else true.also { renderPosted = true }
        }
        if (repeat) handler.post(renderTask)
        if (result > 0) schedulePresentationPoll()
    }

    private fun submitFrame(
        token: Long,
        pending: PendingPresentation,
        packed: PackedOwnedScene,
        installation: SceneInstallation,
    ): Int {
        val started = System.nanoTime()
        pending.submittedAtNanos = started
        val result = OwnedRendererBridge.nativeSubmit(
            nativeHandle, token, surfaceWidth, surfaceHeight, maxOf(surfaceHeight, packed.contentHeightPx),
            packed.viewportTopPx, pending.request.frameTimelineVsyncId, frameRate,
            installation.id, packed.count, installation.replacement,
        )
        pending.renderLatencyNanos.set((System.nanoTime() - started).coerceAtLeast(0L))
        return result
    }

    private fun failedSubmit(token: Long, pending: PendingPresentation, result: Int) {
        val kind = if (result == -2) PresentationTimestampKind.CONTEXT_LOST
            else PresentationTimestampKind.UNAVAILABLE
        terminatePresentation(token, pending, kind)
        when (result) {
            -2 -> recoverContext()
            0 -> { attached = false; sceneIdentity.invalidate() }
            else -> reportFailure(IllegalStateException("Owned renderer rejected frame $token"))
        }
    }

    private fun pruneTextureIdentities() {
        retiredTextureKeys.filter { it !in installedTextureKeys }.forEach { key ->
            textureIdentities.remove(key)
            retiredTextureKeys.remove(key)
        }
    }

    private fun schedulePresentationPoll() {
        if (presentationPollPosted || presentations.isEmpty()) return
        val choreographer = presentationChoreographer ?: Choreographer.getInstance().also {
            presentationChoreographer = it
        }
        presentationPollPosted = true
        choreographer.postFrameCallback(presentationPoll)
    }

    private fun recoverContext() {
        sceneIdentity.invalidate()
        textureIdentities.clear()
        retiredTextureKeys.clear()
        installedTextureKeys = emptySet()
        synchronized(lock) { latest = null; renderPosted = false }
        val nextEpoch = epoch.incrementAndGet()
        attached = OwnedRendererBridge.nativeRecreateContext(nativeHandle)
        clearPresentationPolling()
        reportInvalidated(nextEpoch)
        if (!attached) reportFailure(IllegalStateException("Viewer GL context recovery failed"))
    }

    internal fun recreateContextForVerification() {
        handler.post { if (!closed.get()) recoverContext() }
    }

    internal fun setStaticQuadForVerification(enabled: Boolean, completed: (Boolean) -> Unit) {
        handler.post {
            completed(!closed.get() && OwnedRendererBridge.nativeSetStaticQuadForVerification(nativeHandle, enabled))
        }
    }

    internal fun setDirectTextureUploadForVerification(enabled: Boolean, completed: (Boolean) -> Unit) {
        handler.post {
            completed(
                !closed.get() &&
                    OwnedRendererBridge.nativeSetDirectTextureUploadForVerification(nativeHandle, enabled),
            )
        }
    }

    internal fun failNextGlOperationForVerification() {
        // Invoked only by the activity in the debug source set.
        handler.post {
            if (!closed.get()) OwnedRendererBridge.nativeInjectGlContextLossForVerification(nativeHandle)
        }
    }

    private fun cancelPresentationPoll() {
        if (!presentationPollPosted) return
        presentationChoreographer?.removeFrameCallback(presentationPoll)
        presentationPollPosted = false
    }

    private fun clearPresentationPolling() {
        cancelPresentationPoll()
        presentations.entries.toList().forEach { (token, pending) ->
            terminatePresentation(token, pending, PresentationTimestampKind.CANCELLED)
        }
    }

    fun closeAsync(afterClose: () -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            OwnedRendererBridge.nativeDetach(nativeHandle)
            clearPresentationPolling()
            OwnedRendererBridge.nativeDestroy(nativeHandle)
            textureIdentities.clear()
            retiredTextureKeys.clear()
            installedTextureKeys = emptySet()
            afterClose()
            thread.quitSafely()
        }
    }

    override fun close() = closeAsync()

    private fun nativePresented(token: Long, atNanos: Long, timestampKind: Int, frameId: Long) {
        val pending = presentations[token] ?: return
        pending.timestamp.compareAndSet(null,
            PresentationTimestamp(atNanos, PresentationTimestampKind.fromNative(timestampKind), frameId))
        publishPresentation(token, pending)
    }

    private fun terminatePresentation(token: Long, pending: PendingPresentation, kind: PresentationTimestampKind) {
        pending.timestamp.compareAndSet(null, PresentationTimestamp(0L, kind, 0L))
        publishPresentation(token, pending)
    }

    private fun publishPresentation(token: Long, pending: PendingPresentation) {
        val timestamp = pending.timestamp.get() ?: return
        val latency = pending.renderLatencyNanos.get()
        if (latency < 0L || !presentations.remove(token, pending)) return
        reportPresented(OwnedPresentation(
            token,
            timestamp.atNanos,
            pending.submittedAtNanos,
            latency,
            pending.request.frameTimelineVsyncId,
            pending.request.expectedPresentationTimeNanos,
            pending.request.scene,
            pending.request.metadata,
            timestamp.kind,
            timestamp.frameId,
            pending.verifiedTextureIdentities,
            pending.request.rendererEpoch,
        ))
    }

    private data class RenderRequest(
        val scene: SceneSnapshot,
        val metadata: OwnedFrameMetadata?,
        val frameTimelineVsyncId: Long,
        val expectedPresentationTimeNanos: Long,
        val rendererEpoch: Long,
        val offeredAtNanos: Long,
    )

    private data class PendingPresentation(
        val request: RenderRequest,
        @Volatile var submittedAtNanos: Long = 0L,
        val verifiedTextureIdentities: Map<Long, UploadedTextureIdentity> = emptyMap(),
        val renderLatencyNanos: AtomicLong = AtomicLong(-1L),
        val timestamp: AtomicReference<PresentationTimestamp?> = AtomicReference(null),
    )

    private data class PresentationTimestamp(
        val atNanos: Long,
        val kind: PresentationTimestampKind,
        val frameId: Long,
    )

}

internal data class OwnedRendererAttachment(
    val attached: Boolean,
    val rendererEpoch: Long,
    val invalidated: Boolean,
)
