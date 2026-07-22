package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit process owner for a FAILED engine whose opaque native handle could not be destroyed.
 * A failed handle is never allowed to become unreachable while native proof/backend ownership
 * remains. Qualification requires this registry to stay empty.
 */
internal object NtkFailedNativeHandleRegistry {
    private val engines = ConcurrentHashMap<Long, NtkStripRenderEngine>()

    fun adopt(engine: NtkStripRenderEngine) {
        require(engine.isFailedNativeHandleOwner())
        val previous = engines.putIfAbsent(engine.engineGeneration, engine)
        check(previous == null || previous === engine) {
            "A different failed engine owns generation ${engine.engineGeneration}"
        }
    }

    fun remove(engineGeneration: Long, engine: NtkStripRenderEngine): Boolean =
        engines.remove(engineGeneration, engine)

    internal fun sizeForTesting(): Int = engines.size
}
