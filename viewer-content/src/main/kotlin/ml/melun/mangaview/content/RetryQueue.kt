package ml.melun.mangaview.content

import java.util.PriorityQueue
import ml.melun.mangaview.core.PageId

internal data class RetryEntry(
    val atMillis: Long,
    val pageId: PageId,
)

internal class RetryQueue {
    private val entries = PriorityQueue<RetryEntry>(
        compareBy<RetryEntry>(RetryEntry::atMillis).thenBy { it.pageId.remoteKey },
    )

    fun add(entry: RetryEntry) {
        entries.removeAll { it.pageId == entry.pageId }
        entries += entry
    }

    fun firstAt(): Long? = entries.peek()?.atMillis

    fun removeDue(nowMillis: Long): List<PageId> = buildList {
        while (entries.isNotEmpty() && entries.peek().atMillis <= nowMillis) {
            add(entries.remove().pageId)
        }
    }

    fun clear() = entries.clear()
}
