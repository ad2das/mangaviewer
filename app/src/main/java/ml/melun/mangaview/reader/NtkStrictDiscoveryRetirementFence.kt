package ml.melun.mangaview.reader

import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual OkHttp Call.cancel work; admission is already closed synchronously by the caller. */
private object NtkStrictPhysicalCancelDispatcher {
    private val threadId = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        { command ->
            Thread(
                command,
                "ntk-strict-call-cancel-${threadId.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun dispatch(command: Runnable) {
        // ViewerTelemetry serializes strict generations, and each flight owns only one sequential
        // physical Call, so this lane receives at most one constant-time cancel per retirement.
        executor.execute(command)
    }
}

/**
 * Linearization fence between a strict discovery flight and its viewer owner.
 *
 * Physical network waits deliberately run outside this fence so retirement can cancel every
 * owner immediately. Publication is serialized with other publication, but retirement never
 * waits for that monitor: it first flips [retired], cancels the physical calls/ACK owner and
 * interrupts the worker. A publication already in progress must then either observe the source
 * registry's retired lease or fail the post-action ownership check.
 */
internal class NtkStrictDiscoveryRetirementFence(
    private val episodePath: String,
    private val viewerGeneration: Long,
    private val discoveryGeneration: Long,
) {
    private val stateLock = Any()
    private val publicationLock = Any()
    private val retired = AtomicBoolean(false)
    private var ackCancellation: (() -> Unit)? = null
    private var physicalCancellation: (() -> Runnable)? = null
    private var workerThread: Thread? = null

    fun matches(expectedPath: String, expectedViewerGeneration: Long): Boolean =
        episodePath == expectedPath &&
            viewerGeneration == expectedViewerGeneration &&
            expectedViewerGeneration > 0L

    fun isRetired(): Boolean = retired.get()

    /**
     * Attaches the one ACK handle. The cancel AIDL transaction is oneway; local terminal state and
     * the ordered remote cancellation are both published before this method returns.
     */
    fun attachAckCancellation(cancel: () -> Unit): Boolean {
        val cancelNow = synchronized(stateLock) {
            if (retired.get()) {
                true
            } else {
                check(ackCancellation == null) { "Strict discovery ACK handle already attached" }
                ackCancellation = cancel
                false
            }
        }
        if (cancelNow) cancel()
        return !cancelNow
    }

    /** Attaches the flight-owned OkHttp cancellation registry with the same submit/cancel fence. */
    fun attachPhysicalCancellation(cancel: () -> Runnable): Boolean {
        val cancelNow = synchronized(stateLock) {
            if (retired.get()) {
                true
            } else {
                check(physicalCancellation == null) {
                    "Strict discovery physical cancellation already attached"
                }
                physicalCancellation = cancel
                false
            }
        }
        if (cancelNow) NtkStrictPhysicalCancelDispatcher.dispatch(cancel())
        return !cancelNow
    }

    fun attachWorker(thread: Thread): Boolean {
        val interruptNow = synchronized(stateLock) {
            if (retired.get()) {
                true
            } else {
                check(workerThread == null) { "Strict discovery worker already attached" }
                workerThread = thread
                false
            }
        }
        if (interruptNow) thread.interrupt()
        return !interruptNow
    }

    fun detachWorker(thread: Thread) {
        synchronized(stateLock) {
            if (workerThread === thread) workerThread = null
        }
    }

    /**
     * Runs one worker publication at a time. Retirement deliberately does not acquire this lock;
     * otherwise an Activity callback could wait behind an actor future held by [action].
     */
    fun <T> withActiveOwnership(
        expectedPath: String,
        expectedViewerGeneration: Long,
        boundary: String,
        action: () -> T,
    ): T {
        requireActive(expectedPath, expectedViewerGeneration, boundary)
        return synchronized(publicationLock) {
            requireActive(expectedPath, expectedViewerGeneration, boundary)
            val result = action()
            requireActive(expectedPath, expectedViewerGeneration, "$boundary completion")
            result
        }
    }

    /**
     * Linearizes only a short local state commit (cookie-map merge or equivalent) with retirement.
     * Network, Binder, actor joins, file reads and listener delivery are forbidden in [action].
     */
    fun <T> withBoundedActiveOwnership(
        expectedPath: String,
        expectedViewerGeneration: Long,
        boundary: String,
        action: () -> T,
    ): T = synchronized(stateLock) {
        requireActive(expectedPath, expectedViewerGeneration, boundary)
        action()
    }

    /**
     * Retires only the exact viewer generation. The callbacks are detached under the same fence
     * and invoked outside it, preventing both double cancellation and binder work under the lock.
     */
    fun retire(expectedPath: String, expectedViewerGeneration: Long): Boolean {
        val ack: (() -> Unit)?
        val physical: (() -> Runnable)?
        val worker: Thread?
        synchronized(stateLock) {
            if (retired.get() || !matches(expectedPath, expectedViewerGeneration)) return false
            retired.set(true)
            ack = ackCancellation
            ackCancellation = null
            physical = physicalCancellation
            physicalCancellation = null
            worker = workerThread
            workerThread = null
        }
        // Admission closes synchronously before lifecycle retirement returns. The detached Call
        // cancellation itself runs off-main; a late register observes registry.cancelled and fails.
        val physicalWork = physical?.invoke()
        worker?.interrupt()
        physicalWork?.let(NtkStrictPhysicalCancelDispatcher::dispatch)
        // ACK cancellation flips local terminal state and sends an ordered oneway AIDL call.
        ack?.invoke()
        return true
    }

    private fun requireActive(
        expectedPath: String,
        expectedViewerGeneration: Long,
        boundary: String,
    ) {
        if (retired.get() || !matches(expectedPath, expectedViewerGeneration)) {
            throw InterruptedIOException(
                "Strict discovery ownership retired before $boundary " +
                    "path=$episodePath,viewerGeneration=$viewerGeneration," +
                    "discoveryGeneration=$discoveryGeneration"
            )
        }
    }
}
