package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped owner for unclaimed context-loss tombstones whose SurfaceView has gone away.
 * Entries are proof-only native handles: they retain no Activity, View, backend thread, EGL,
 * ANativeWindow, Swappy lease, JNI global reference, bitmap global reference, or callback lane.
 */
internal object NtkRetiredProofRegistry {
    private val engines = ConcurrentHashMap<Long, NtkStripRenderEngine>()

    fun adopt(engine: NtkStripRenderEngine) {
        require(engine.isRetiredProofOnly()) {
            "Only a retired proof tombstone may enter the process proof registry"
        }
        val previous = engines.putIfAbsent(engine.engineGeneration, engine)
        check(previous == null || previous === engine) {
            "A different retired proof owns engine generation ${engine.engineGeneration}"
        }
    }

    fun find(engineGeneration: Long): NtkStripRenderEngine? = engines[engineGeneration]

    fun remove(engineGeneration: Long, engine: NtkStripRenderEngine): Boolean =
        engines.remove(engineGeneration, engine)

    internal fun sizeForTesting(): Int = engines.size
}
