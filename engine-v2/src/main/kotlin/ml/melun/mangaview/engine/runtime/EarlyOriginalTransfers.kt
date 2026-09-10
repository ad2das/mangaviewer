package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.WorkPriority

/** Keep bounded, already header-read streams alive when exact geometry lets input pass them. */
internal class EarlyOriginalTransfers {
    private val pages = linkedSetOf<PageId>()

    fun observed(id: PageId) {
        // Two interactive bodies can join twelve background bodies. Retain the whole
        // admitted window so a reversal cannot cancel its last in-flight originals.
        if (pages.size < 14) pages += id
    }

    fun retain(result: LinkedHashMap<PageId, WorkPriority>, prepared: Set<PageId>, failed: Set<PageId>) {
        pages.removeAll { it in prepared || it in failed }
        pages.forEach { result.putIfAbsent(it, WorkPriority.NEXT_IMAGE) }
    }

    fun clear() = pages.clear()
}
