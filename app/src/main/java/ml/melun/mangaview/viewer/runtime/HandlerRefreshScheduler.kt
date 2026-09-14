package ml.melun.mangaview.viewer.runtime

import android.os.Handler
import ml.melun.mangaview.engine.runtime.EngineRefreshScheduler

/** Owner-thread message queue that carries at most one pending engine refresh drain. */
internal interface RefreshMessageQueue {
    /** Queues [message] for the owner thread; false means the queue is shutting down. */
    fun post(message: Runnable): Boolean

    /** Removes [message] if it is still queued; safe after it was already delivered. */
    fun remove(message: Runnable)
}

/** Handler-backed queue; the app passes an async main handler so vsync sync barriers cannot defer the drain. */
internal class HandlerRefreshMessageQueue(private val handler: Handler) : RefreshMessageQueue {
    override fun post(message: Runnable): Boolean = handler.post(message)
    override fun remove(message: Runnable) = handler.removeCallbacks(message)
}

/**
 * Queued main-looper delivery for the engine refresh port. [post] keeps at most one pending message
 * and never delivers inline; the delivery clears the pending flag before the drain runs, so a
 * request arriving during the drain queues exactly one follow-up message (the next message, never
 * a nested call) while repeated requests coalesce into the single pending one.
 *
 * [cancel] removes the queued message and is idempotent. Cancel and delivery share the owner
 * thread, so a removed message cannot run through the queue; the pending guard stays defensive:
 * a removed message run before any new [post] no-ops. A later [post] enqueues the same single
 * message instance and delivers normally. A rejected enqueue clears this adapter's pending flag
 * first (so a later [post] can enqueue again) and then fails fast. That rejection is a
 * shutting-down looper: the engine's own scheduled flag may stay set, and no whole-engine retry
 * is claimed.
 */
internal class HandlerRefreshScheduler(
    private val queue: RefreshMessageQueue,
    private val drain: () -> Unit,
) : EngineRefreshScheduler {
    private var pending = false
    private val message = Runnable { deliver() }

    override fun post() {
        if (pending) return
        pending = true
        if (!queue.post(message)) {
            pending = false
            throw IllegalStateException("Refresh message queue is unavailable")
        }
    }

    override fun cancel() {
        if (!pending) return
        pending = false
        queue.remove(message)
    }

    private fun deliver() {
        if (!pending) return
        pending = false
        drain()
    }
}
