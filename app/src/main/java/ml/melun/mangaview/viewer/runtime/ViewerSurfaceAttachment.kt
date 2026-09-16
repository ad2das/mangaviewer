package ml.melun.mangaview.viewer.runtime

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Surface attachment retries share the lifetime of the current holder and foreground epoch. */
internal class ViewerSurfaceAttachment(
    private val view: SurfaceView,
    private val sink: ViewerSurfaceSink,
) : SurfaceHolder.Callback {
    private val holder get() = view.holder
    private val width get() = view.width
    private val height get() = view.height
    private val display get() = view.display
    private val isAttachedToWindow get() = view.isAttachedToWindow
    private var foreground = true
    private var surfaceReady = false
    private var rendererAttached = false
    private var attachPending = false
    private var attachEpoch = 0L
    private var attachJob: Job? = null
    private var attachedWidth = 0
    private var attachedHeight = 0


    fun enterForeground() {
        foreground = true
        attachIfReady()
    }

    fun enterBackground() {
        foreground = false
        detachRenderer()
    }

    fun resized(width: Int, height: Int) {
        if (rendererAttached && (width != attachedWidth || height != attachedHeight)) {
            detachRenderer()
        }
        attachIfReady()
    }

    /** EGL can observe window loss before SurfaceHolder delivers its lifecycle callback. */
    fun rendererUnavailable() {
        rendererAttached = false
        attachedWidth = 0
        attachedHeight = 0
        surfaceReady = surfaceReady && holder.surface.isValid
        attachIfReady()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = holder.surface.isValid
        attachIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = holder.surface.isValid && width > 0 && height > 0
        if (rendererAttached && (width != attachedWidth || height != attachedHeight)) {
            detachRenderer()
        }
        attachIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        detachRenderer()
    }

    fun attachIfReady() {
        if (rendererAttached || attachPending || !canAttach()) return
        attachPending = true
        val epoch = ++attachEpoch
        attachJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                retrySurfaceAttachment(
                    MAXIMUM_ATTACH_RETRIES,
                    ATTACH_RETRY_DELAY_MILLIS,
                    canRetry = { epoch == attachEpoch && canAttach() },
                    attach = { attachSurface(epoch) },
                    exhausted = sink::surfaceAttachExhausted,
                )
            } finally {
                if (epoch == attachEpoch) attachPending = false
            }
        }
    }

    private fun canAttach(): Boolean = foreground && surfaceReady && isAttachedToWindow && width > 0 && height > 0

    private suspend fun attachSurface(epoch: Long): Boolean = suspendCancellableCoroutine { continuation ->
        rendererAttached = true
        attachedWidth = width
        attachedHeight = height
        sink.surfaceAvailable(holder.surface, width, height, display?.refreshRate ?: 60.0F) { attached ->
            if (epoch != attachEpoch || !continuation.isActive) return@surfaceAvailable
            if (!attached) {
                // A window can die before SurfaceHolder delivers its lifecycle event.
                rendererAttached = false
                attachedWidth = 0
                attachedHeight = 0
            }
            continuation.resume(attached)
        }
    }

    fun detachRenderer() {
        attachEpoch++
        attachJob?.cancel()
        attachJob = null
        attachPending = false
        if (!rendererAttached) return
        rendererAttached = false
        attachedWidth = 0
        attachedHeight = 0
        sink.surfaceUnavailable()
    }


    private companion object {
        const val MAXIMUM_ATTACH_RETRIES = 25
        const val ATTACH_RETRY_DELAY_MILLIS = 200L
    }
}
