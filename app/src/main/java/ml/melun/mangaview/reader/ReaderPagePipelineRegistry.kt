package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Episode-key registry used only while Warmup, Controller, and Session migrate to one owner. */
object ReaderPagePipelineRegistry {
    private data class Entry(val pageCount: Int, val pipeline: ReaderPagePipeline)

    private val epochs = AtomicLong()
    private val entries = ConcurrentHashMap<String, Entry>()

    fun createOrGet(key: String, pageCount: Int): ReaderPagePipeline {
        require(key.isNotBlank()) { "pipeline key must not be blank" }
        require(pageCount > 0) { "page count must be positive" }
        while (true) {
            val existing = entries[key]
            if (existing != null && existing.pageCount == pageCount &&
                !existing.pipeline.invariantSnapshot().retired
            ) return existing.pipeline
            val created = Entry(pageCount, ReaderPagePipeline(epochs.incrementAndGet(), pageCount))
            if (existing == null) {
                if (entries.putIfAbsent(key, created) == null) return created.pipeline
            } else if (entries.replace(key, existing, created)) {
                existing.pipeline.retire("manifest_replaced")
                return created.pipeline
            }
        }
    }

    fun get(key: String?): ReaderPagePipeline? = key?.takeIf { it.isNotBlank() }?.let {
        entries[it]?.pipeline?.takeUnless { pipeline -> pipeline.invariantSnapshot().retired }
    }

    fun retire(key: String?, reason: String): Boolean {
        val safeKey = key?.takeIf { it.isNotBlank() } ?: return false
        val entry = entries.remove(safeKey) ?: return false
        entry.pipeline.retire(reason)
        return true
    }

    internal fun clearForTest() {
        entries.values.forEach { it.pipeline.retire("test_clear") }
        entries.clear()
    }
}
