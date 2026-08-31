package ml.melun.mangaview.source.ntk

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import ml.melun.mangaview.source.PreparationIntent

/** Keeps each protected document on one browser lane while allowing independent ACKs in parallel. */
class NtkAccessGatewayPool(
    private val lanes: List<NtkAccessGateway>,
) : NtkAccessGateway {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val capacity = Semaphore(lanes.size)
    private val assignments = mutableMapOf<String, Int>()
    private val occupied = BooleanArray(lanes.size)
    private val residentKeys = arrayOfNulls<String>(lanes.size)

    init {
        require(lanes.isNotEmpty()) { "NTK gateway pool needs at least one lane" }
    }

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) {
        val key = validatedKey(origin, episodePath)
        val lane = claim(key)
        try {
            lanes[lane].prepare(origin, episodePath, intent)
        } catch (failure: Throwable) {
            release(key)
            throw failure
        }
    }

    override suspend fun documentAvailable(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) {
        val key = validatedKey(document.origin, document.path)
        lanes[assigned(key)].documentAvailable(document, descriptor)
    }

    override suspend fun awaitAuthorization(origin: String, episodePath: String): Boolean {
        val key = validatedKey(origin, episodePath)
        val lane = synchronized(lock) {
            assignments[key] ?: residentKeys.indexOf(key).takeIf { it >= 0 }
        } ?: return true
        return lanes[lane].awaitAuthorization(origin, episodePath)
    }

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        val key = validatedKey(document.origin, document.path)
        val lane = assigned(key)
        return try {
            lanes[lane].resolve(document, descriptor)
        } finally {
            release(key)
        }
    }

    override fun pageAccessEstablished(origin: String, episodePath: String) {
        val key = runCatching { validatedKey(origin, episodePath) }.getOrNull() ?: return
        val assignment = synchronized(lock) {
            assignments[key]?.let { it to true }
                ?: residentKeys.indexOf(key).takeIf { it >= 0 }?.let { it to false }
        } ?: return
        val (lane, occupiedAssignment) = assignment
        try {
            lanes[lane].pageAccessEstablished(origin, episodePath)
        } finally {
            if (occupiedAssignment) release(key) else synchronized(lock) {
                if (!occupied[lane] && residentKeys[lane] == key) residentKeys[lane] = null
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lanes.forEach(NtkAccessGateway::close)
    }

    private suspend fun claim(key: String): Int {
        synchronized(lock) { assignments[key]?.let { return it } }
        capacity.acquire()
        return synchronized(lock) {
            check(!closed.get()) { "NTK gateway pool is closed" }
            assignments[key]?.also { capacity.release() } ?: run {
                val lane = selectFreeLane(key)
                check(lane >= 0) { "NTK gateway pool capacity became inconsistent" }
                occupied[lane] = true
                residentKeys[lane] = key
                assignments[key] = lane
                lane
            }
        }
    }

    private fun assigned(key: String): Int = synchronized(lock) {
        assignments[key] ?: error("NTK document was not prepared on a browser lane")
    }

    private fun release(key: String) {
        val released = synchronized(lock) {
            assignments.remove(key)?.also { lane -> occupied[lane] = false }
        }
        if (released != null) capacity.release()
    }

    private fun selectFreeLane(key: String): Int {
        val matching = residentKeys.indices.firstOrNull { !occupied[it] && residentKeys[it] == key }
        if (matching != null) return matching
        val blank = residentKeys.indices.firstOrNull { !occupied[it] && residentKeys[it] == null }
        return blank ?: occupied.indexOfFirst { !it }
    }
}
