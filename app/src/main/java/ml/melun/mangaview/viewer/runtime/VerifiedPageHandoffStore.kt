package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.viewer.VerifiedPageRef

/** Bounded descriptor-only LRU shared by fetches and repeated band decodes. */
internal class VerifiedPageHandoffStore(
    private val capacity: Int = 64,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<PageId, CachedPage>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    fun remember(cached: CachedPage) {
        synchronized(lock) {
            entries[cached.pageId] = cached
            while (entries.size > capacity) {
                entries.remove(entries.entries.first().key)
            }
        }
    }

    fun find(pageId: PageId, expected: VerifiedPageRef): CachedPage? = synchronized(lock) {
        val cached = entries[pageId] ?: return@synchronized null
        if (cached.byteCount == expected.byteCount && cached.sha256 == expected.sha256 &&
            (expected.dimensions == null || cached.dimensions == expected.dimensions)
        ) {
            cached
        } else {
            entries.remove(pageId)
            null
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun size(): Int = synchronized(lock) { entries.size }
}
