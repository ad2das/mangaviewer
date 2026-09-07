package ml.melun.mangaview.content

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** A resource in transit has one owner even when send cancellation races with disposal. */
internal class ResourceHandoff<T : Any>(value: T, private val release: (T) -> Unit) : Closeable {
    private val owned = AtomicReference<T?>(value)

    fun take(): T = checkNotNull(owned.getAndSet(null)) { "Resource handoff was already consumed" }

    override fun close() {
        owned.getAndSet(null)?.let(release)
    }
}
