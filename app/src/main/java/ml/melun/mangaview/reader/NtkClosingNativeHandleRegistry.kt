package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Strong process ownership for a CLOSING engine whose admitted release completions are draining.
 * The separate teardown lane prevents a user completion, UI teardown, and registration-finally
 * cycle from blocking the process-wide external-completion dispatcher or the main thread.
 */
internal object NtkClosingNativeHandleRegistry {
    private val engines = ConcurrentHashMap<Long, NtkStripRenderEngine>()
    private val threadSerial = AtomicLong(0L)
    private val teardownExecutor = Executors.newCachedThreadPool(ThreadFactory { command ->
        Thread(command, "ntk-lifecycle-teardown-${threadSerial.incrementAndGet()}").apply {
            isDaemon = true
        }
    })

    fun adopt(engine: NtkStripRenderEngine) {
        require(engine.isClosingNativeHandleOwner())
        val previous = engines.putIfAbsent(engine.engineGeneration, engine)
        check(previous == null || previous === engine) {
            "A different closing engine owns generation ${engine.engineGeneration}"
        }
    }

    fun dispatchTeardown(action: () -> Unit) {
        teardownExecutor.execute(action)
    }

    fun remove(engineGeneration: Long, engine: NtkStripRenderEngine): Boolean =
        engines.remove(engineGeneration, engine)

    internal fun sizeForTesting(): Int = engines.size
}
