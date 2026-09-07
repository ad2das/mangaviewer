package ml.melun.mangaview.data.network

import ml.melun.mangaview.source.PageFetchPriority

/** Bounds simultaneous body pulls and always admits the most urgent waiting viewport read. */
internal class HttpEngineBodyReadScheduler(private val capacity: Int) {
    interface Lease {
        fun finish()
        fun cancel()
    }

    private enum class EntryState { QUEUED, RUNNING, FINISHED }

    private inner class Entry(
        val owner: Any,
        var priority: PageFetchPriority,
        val sequence: Long,
        val start: () -> Unit,
    ) : Lease {
        var state = EntryState.QUEUED

        override fun finish() = release(this)
        override fun cancel() = release(this)
    }

    private val lock = Any()
    private val queued = mutableListOf<Entry>()
    private var running = 0
    private var sequence = 0L

    init {
        require(capacity > 0) { "HTTP engine body read capacity must be positive" }
    }

    fun schedule(owner: Any, priority: PageFetchPriority, start: () -> Unit): Lease {
        val entry: Entry
        val ready = synchronized(lock) {
            entry = Entry(owner, priority, nextSequence(), start)
            queued += entry
            reserveReadyLocked()
        }
        ready.forEach { it.start() }
        return entry
    }

    fun promote(owner: Any, priority: PageFetchPriority) {
        synchronized(lock) {
            queued.asSequence().filter { it.owner === owner }.forEach { entry ->
                if (priority.ordinal < entry.priority.ordinal) entry.priority = priority
            }
        }
    }

    private fun release(entry: Entry) {
        val ready = synchronized(lock) {
            when (entry.state) {
                EntryState.QUEUED -> queued.remove(entry)
                EntryState.RUNNING -> running -= 1
                EntryState.FINISHED -> return
            }
            entry.state = EntryState.FINISHED
            reserveReadyLocked()
        }
        ready.forEach { it.start() }
    }

    private fun reserveReadyLocked(): List<Entry> = buildList {
        while (running < capacity && queued.isNotEmpty()) {
            val selected = queued.minWith(compareBy<Entry>({ it.priority.ordinal }, { it.sequence }))
            queued.remove(selected)
            selected.state = EntryState.RUNNING
            running += 1
            add(selected)
        }
    }

    private fun nextSequence(): Long {
        if (sequence == Long.MAX_VALUE) sequence = 0L
        sequence += 1L
        return sequence
    }
}
