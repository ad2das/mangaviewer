package ml.melun.mangaview.content

import ml.melun.mangaview.core.PageId

/** Actor-owned tombstones: changing generation does not terminate a physical worker. */
internal class RetiringPipelineWork {
    private val pending = mutableListOf<PageRecord>()

    fun retain(records: Collection<PageRecord>) {
        records.filter { it.raw is RawState.Fetching || it.decode.isRunning() }.forEach { page ->
            page.demand = null
            pending += page
        }
    }

    fun records(): List<PageRecord> {
        pending.forEach { page ->
            val fetch = page.raw as? RawState.Fetching
            if (fetch?.job?.isCompleted == true) page.raw = RawState.Absent
            val ended = when (val decode = page.decode) {
                is DecodeState.Decoding -> decode.job.isCompleted
                is DecodeState.Uploading -> decode.job.isCompleted
                else -> false
            }
            if (ended) page.decode = DecodeState.Idle
        }
        pending.removeAll { it.raw !is RawState.Fetching && !it.decode.isRunning() }
        return pending
    }

    fun hasFetch(pageId: PageId): Boolean = records().any {
        it.page.id == pageId && it.raw is RawState.Fetching
    }

    fun clear() = pending.clear()
}

internal fun DecodeState.isRunning(): Boolean = this is DecodeState.Decoding || this is DecodeState.Uploading
