package ml.melun.mangaview.data.network

/** Opt-in diagnostic sink. No URL or timing records are retained when it is absent. */
object HttpEngineReadDiagnostics {
    @Volatile var observer: ((Map<String, Any>) -> Unit)? = null

    internal fun begin(url: String): HttpEngineReadTiming? =
        observer?.let { HttpEngineReadTiming(url, it) }
}

/** Partitions body pull latency without changing demand, priority or callback scheduling. */
internal class HttpEngineReadTiming(
    private val url: String,
    private val sink: (Map<String, Any>) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private var headersAt = 0L
    private var lastCompletedAt = 0L
    private var consumerWait = 0L
    private var admissionWait = 0L
    private var dispatchWait = 0L
    private var readWait = 0L
    private var callbackQueueWait = 0L
    private var maxCallbackQueueWait = 0L
    private var chunks = 0L
    private var bytes = 0L
    private var closed = false

    @Synchronized fun headers() { headersAt = clock(); lastCompletedAt = headersAt }

    @Synchronized fun demand(): Pull {
        val now = clock()
        consumerWait += (now - lastCompletedAt).coerceAtLeast(0)
        return Pull(now)
    }

    fun queued(command: Runnable): Runnable {
        val at = clock()
        return Runnable {
            val waited = (clock() - at).coerceAtLeast(0)
            synchronized(this) {
                callbackQueueWait += waited
                maxCallbackQueueWait = maxOf(maxCallbackQueueWait, waited)
            }
            command.run()
        }
    }

    @Synchronized fun close(complete: Boolean) {
        if (closed) return
        closed = true
        sink(linkedMapOf(
            "url" to url, "headersAtNanos" to headersAt, "closedAtNanos" to clock(),
            "lastReadCompletedAtNanos" to lastCompletedAt, "complete" to complete,
            "chunks" to chunks, "bytes" to bytes, "consumerWaitNanos" to consumerWait,
            "admissionWaitNanos" to admissionWait, "dispatchWaitNanos" to dispatchWait,
            "readWaitIncludingCallbackQueueNanos" to readWait,
            "allCallbackQueueWaitNanos" to callbackQueueWait,
            "maxCallbackQueueWaitNanos" to maxCallbackQueueWait,
        ))
    }

    inner class Pull(private val demandedAt: Long) {
        private var admittedAt = 0L
        private var issuedAt = 0L
        fun admitted() { admittedAt = clock() }
        fun issued() { issuedAt = clock() }
        fun completed(count: Int) = synchronized(this@HttpEngineReadTiming) {
            val at = clock()
            if (admittedAt != 0L && issuedAt != 0L) {
                admissionWait += (admittedAt - demandedAt).coerceAtLeast(0)
                dispatchWait += (issuedAt - admittedAt).coerceAtLeast(0)
                readWait += (at - issuedAt).coerceAtLeast(0)
            }
            lastCompletedAt = at
            if (count > 0) { chunks++; bytes += count }
        }
    }
}
