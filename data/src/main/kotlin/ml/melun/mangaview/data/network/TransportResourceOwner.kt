package ml.melun.mangaview.data.network

/** Owns resources across asynchronous registration and close without running cleanup under lock. */
internal class TransportResourceOwner<T : Any> {
    private val lock = Any()
    private val resources = linkedSetOf<T>()
    private var closed = false

    fun register(resource: T): Boolean = synchronized(lock) {
        if (closed) false else resources.add(resource)
    }

    fun complete(resource: T): Boolean = synchronized(lock) {
        resources.remove(resource)
        resources.isEmpty()
    }

    fun closeAndSnapshot(): List<T> = synchronized(lock) {
        closed = true
        resources.toList()
    }

    fun size(): Int = synchronized(lock) { resources.size }
}
