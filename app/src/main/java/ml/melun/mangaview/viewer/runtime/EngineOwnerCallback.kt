package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean

/** Completion threads signal only; the owner processes release fences and GL state. */
internal class EngineOwnerCallback(
    private val closing: AtomicBoolean,
    private val handler: () -> Handler,
    private val presented: (Long, Long, Int, Long) -> Unit,
    private val poll: () -> Unit,
) : OwnedRendererCallback {
    private val posted = AtomicBoolean(false)

    override fun onFramePresented(token: Long, atNanos: Long, timestampKind: Int, bufferFrameId: Long) =
        presented(token, atNanos, timestampKind, bufferFrameId)

    override fun onCompletionPending() {
        if (closing.get() || !posted.compareAndSet(false, true)) return
        if (!handler().post {
                posted.set(false)
                if (!closing.get()) poll()
            }) posted.set(false)
    }
}
