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
        const val IO_THREADS = 6
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
