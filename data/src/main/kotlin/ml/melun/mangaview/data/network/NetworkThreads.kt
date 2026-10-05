package ml.melun.mangaview.data.network

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The relay and OkHttp pools are created by whichever thread first needs them, and a plain thread
 * inherits its creator's nice value — when that is the UI thread (-10), network workers outrank
 * input and rendering for the pool's whole lifetime. Pin every worker to the default nice value:
 * these threads carry bytes a visible page is waiting for, so they must not be dropped to
 * background either.
 */
internal fun defaultPriorityThreadFactory(name: String): ThreadFactory = ThreadFactory { task ->
    Thread({
        applyDefaultPriority()
        task.run()
    }, name).apply { isDaemon = true }
}

internal fun defaultPriorityExecutor(name: String): ExecutorService =
    Executors.newCachedThreadPool(defaultPriorityThreadFactory(name))

/**
 * Production pins the nice value; the stubbed android.jar of JVM unit tests throws from every
 * android.os method, where the write is a no-op.
 */
private fun applyDefaultPriority() {
    try {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
    } catch (_: RuntimeException) {
        // JVM unit tests stub android.os; production does not take this branch.
    }
}
