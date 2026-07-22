package ml.melun.mangaview.reader

import android.util.Log
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-authoritative, bounded telemetry lane.
 *
 * The episode actor only captures immutable values and offers a closure. String construction and
 * Android logging happen on this lane, so a slow log consumer can neither block viewport reduction
 * nor grow an unbounded queue. Dropping observational telemetry is visible through [snapshot] and
 * never changes reader authority.
 */
internal class NtkAsyncTelemetry(
    capacity: Int = DEFAULT_CAPACITY,
    private val sink: (String, String) -> Unit = { tag, message -> Log.d(tag, message) }
) : Closeable {
    data class Snapshot(
        val offered: Long,
        val accepted: Long,
        val completed: Long,
        val dropped: Long,
        val closed: Boolean
    ) {
        init {
            require(listOf(offered, accepted, completed, dropped).all { it >= 0L })
            require(accepted + dropped == offered)
            require(completed <= accepted)
        }
    }

    private val validatedCapacity = capacity.also { require(it > 0) }
    private val service = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(validatedCapacity),
        { runnable ->
            Thread(runnable, "ntk-strip-telemetry").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val closed = AtomicBoolean(false)
    private val accountingLock = Any()
    private val offered = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val completed = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    /** Returns immediately; [fields] is evaluated only by the telemetry worker. */
    fun offer(event: String, authority: Long, elapsedMs: Long, fields: () -> String): Boolean {
        require(event.isNotBlank())
        require(authority > 0L)
        require(elapsedMs >= 0L)
        return submit {
            sink(
                TAG,
                "$event authority=$authority,${fields()},elapsedMs=$elapsedMs"
            )
        }
    }

    /**
     * Preserves an existing log schema while moving both message construction and the platform
     * logging call off the authority-owning caller.
     */
    fun offerRaw(event: String, authority: Long, message: () -> String): Boolean {
        require(event.isNotBlank())
        require(authority > 0L)
        return submit { sink(TAG, message()) }
    }

    private fun submit(operation: () -> Unit): Boolean {
        val task = Runnable {
            try {
                operation()
            } catch (_: Throwable) {
                // Telemetry never owns reader authority.
            } finally {
                completed.incrementAndGet()
            }
        }
        return synchronized(accountingLock) {
            offered.incrementAndGet()
            if (closed.get()) {
                dropped.incrementAndGet()
                return@synchronized false
            }
            accepted.incrementAndGet()
            try {
                service.execute(task)
                true
            } catch (_: RejectedExecutionException) {
                accepted.decrementAndGet()
                dropped.incrementAndGet()
                false
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(accountingLock) {
        Snapshot(
            offered = offered.get(),
            accepted = accepted.get(),
            completed = completed.get(),
            dropped = dropped.get(),
            closed = closed.get()
        )
    }

    override fun close() {
        var interrupted = false
        synchronized(accountingLock) {
            if (closed.compareAndSet(false, true)) service.shutdown()
        }
        while (!service.isTerminated) {
            try {
                service.awaitTermination(100L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal fun awaitDrainedForTesting(timeout: Long, unit: TimeUnit): Boolean =
        service.awaitTermination(timeout, unit)

    companion object {
        private const val TAG = "ViewerPerf"
        private const val DEFAULT_CAPACITY = 64
    }
}
