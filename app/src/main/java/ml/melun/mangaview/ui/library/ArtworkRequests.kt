package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A cover request belongs to its visible consumers, not to whichever card asked first. */
internal class ArtworkRequests<T>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private class Entry<T>(val work: Deferred<Result<T>>) { var readers = 0 }
    private val inFlight = mutableMapOf<String, Entry<T>>()

    suspend fun load(key: String, fetch: suspend () -> T): T {
        val entry = synchronized(inFlight) {
            val shared = inFlight.getOrPut(key) {
                Entry(scope.async(dispatcher, start = CoroutineStart.LAZY) { runCatching { fetch() } })
            }
            shared.also { it.readers++ }
        }
        try {
            return entry.work.await().getOrThrow()
        } finally {
            synchronized(inFlight) {
                if (--entry.readers == 0) {
                    if (inFlight[key] === entry) inFlight.remove(key)
                    entry.work.cancel()
                }
            }
        }
    }
}
