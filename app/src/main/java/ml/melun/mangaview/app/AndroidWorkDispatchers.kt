package ml.melun.mangaview.app

import android.os.Process
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.content.DecodeLane

internal class AndroidWorkDispatcher(
    name: String,
    threads: Int,
    linuxPriority: Int = Process.THREAD_PRIORITY_BACKGROUND,
) : Closeable {
    private val executor = Executors.newFixedThreadPool(
        threads,
        AndroidPriorityThreadFactory(name, linuxPriority),
    )
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    init {
        require(threads > 0) { "Worker thread count must be positive" }
    }

    val coroutineDispatcher: CoroutineDispatcher
        get() = dispatcher

    override fun close() = dispatcher.close()

    val isTerminated: Boolean get() = executor.isTerminated

    suspend fun closeAndAwait(timeoutMillis: Long = 5_000) {
        require(timeoutMillis > 0)
        close()
        withContext(Dispatchers.IO) {
            check(executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                "Worker dispatcher did not terminate within ${timeoutMillis}ms"
            }
        }
    }
}

/**
 * A decode lane whose block runs on the calling thread under [linuxPriority]. The read-ahead decode
 * used to hop onto a dedicated background-priority pool and back: measured on the GPU AVD that was
 * two real dispatcher hops (~0.14-0.29ms plus ~0.16-0.20ms of a ~5.9ms read-ahead budget). The
 * caller's worker already owns the tile record whose decode this is, so the lane runs the block
 * inline — nothing changes threads; the priority wrap is what keeps the horizon from contending with
 * the visible decode and the owner/render threads. Admission bounds how many workers may sit in a
 * decode at once.
 */
internal class InlinePriorityLane(
    private val linuxPriority: Int = Process.THREAD_PRIORITY_BACKGROUND,
) : DecodeLane {
    override suspend fun <R> run(block: suspend () -> R): R {
        val previous = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(linuxPriority)
        return try {
            block()
        } finally {
            Process.setThreadPriority(previous)
        }
    }
}

internal class AppWorkDispatchers : Closeable {
    private val sourceOwner = AndroidWorkDispatcher("app-source", SOURCE_THREADS)
    private val ioOwner = AndroidWorkDispatcher("app-io", IO_THREADS)

    val source: CoroutineDispatcher
        get() = sourceOwner.coroutineDispatcher

    val io: CoroutineDispatcher
        get() = ioOwner.coroutineDispatcher

    override fun close() {
        sourceOwner.close()
        ioOwner.close()
    }

    private companion object {
        // PageRepository can own six network flights. Fewer source workers serialized blocking
        // response-prefix validation before the transport scheduler could apply its priorities.
        const val SOURCE_THREADS = 6
        // Fourteen bounded image-body transfers leave workers available for publication and positions.
        const val IO_THREADS = 16
    }
}

private class AndroidPriorityThreadFactory(
    private val name: String,
    private val linuxPriority: Int,
) : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread = Thread(
        {
            Process.setThreadPriority(linuxPriority)
            runnable.run()
        },
        "$name-${sequence.incrementAndGet()}",
    ).apply {
        priority = Thread.NORM_PRIORITY - 1
    }
}
