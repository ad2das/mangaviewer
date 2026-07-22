package ml.melun.mangaview.ntkack

/**
 * Atomically closes registration and snapshots all work that was admitted before cancellation.
 *
 * The cancellation callback runs outside the monitor so a transport implementation may complete
 * synchronously without re-entering the registry lock. Once [cancelAll] begins, [register] can no
 * longer publish work that the cancellation snapshot did not observe.
 */
internal class NtkAckCancellationRegistry<T>(
    private val cancelValue: (T) -> Unit,
) {
    private val lock = Any()
    private val active = LinkedHashSet<T>()
    private var cancelled = false

    fun register(value: T) {
        synchronized(lock) {
            check(!cancelled) { "ACK work registry is cancelled" }
            check(active.add(value)) { "ACK work was registered twice" }
        }
    }

    fun unregister(value: T) {
        synchronized(lock) { active.remove(value) }
    }

    fun cancelAll() {
        val admitted = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            active.toList()
        }
        admitted.forEach { value -> runCatching { cancelValue(value) } }
    }

    val activeCount: Int
        get() = synchronized(lock) { active.size }

    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }
}
