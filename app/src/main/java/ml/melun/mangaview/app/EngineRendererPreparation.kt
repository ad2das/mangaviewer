package ml.melun.mangaview.app

import kotlinx.coroutines.*

/** One content-independent renderer; ownership moves to a reader even during preparation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineRendererPreparation<T : Any>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val create: () -> T,
    private val prepare: suspend (T) -> Unit,
    private val dispose: suspend (T) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
) {
    private class Entry<T> {
        var value: T? = null
        var claimed = false
        var ready = false
        lateinit var job: Job
    }
    private val lock = Any()
    private var pending: Entry<T>? = null
    private var tail: Job? = null
    private var readers = 0
    private var requested = false
    private var closed = false

    fun preparedSnapshot(): T? = synchronized(lock) { pending?.takeIf { it.ready }?.value }

    fun warm() = synchronized(lock) {
        if (closed) return@synchronized
        requested = true
        if (readers != 0 || pending != null) return@synchronized
        val entry = Entry<T>()
        val previous = tail
        pending = entry
        entry.job = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
            var value: T? = null
            try {
                previous?.join()
                ensureActive()
                val created = create()
                value = created
                val accepted = synchronized(lock) {
                    if (pending === entry) { entry.value = created; true } else false
                }
                if (!accepted) return@launch
                prepare(created)
                synchronized(lock) { entry.ready = true }
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportFailure(failure) }
            finally {
                withContext(NonCancellable) {
                    previous?.join()
                    val release = synchronized(lock) {
                        if (pending === entry) pending = null
                        if (entry.claimed) null else value
                    }
                    release?.let { dispose(it) }
                }
            }
        }
        tail = entry.job
    }

    fun cancel() = synchronized(lock) {
        requested = false
        pending?.job?.cancel()
        pending = null
    }

    fun claim(): Lease = synchronized(lock) {
        check(!closed)
        requested = false
        readers++
        val entry = pending
        pending = null
        val value = entry?.value
        if (value != null) entry.claimed = true
        entry?.job?.cancel()
        Lease(value, entry?.job ?: tail)
    }

    inner class Lease internal constructor(val value: T?, private val preparation: Job?) {
        private var finished = false
        private val done = CompletableDeferred<Unit>()
        suspend fun close() = withContext(NonCancellable) {
            val first = synchronized(lock) { if (finished) false else true.also { finished = true } }
            if (first) {
                try {
                    preparation?.join()
                    value?.let { dispose(it) }
                    done.complete(Unit)
                } catch (failure: Throwable) { done.completeExceptionally(failure) }
                finally {
                    synchronized(lock) {
                        readers--
                        if (!closed && requested && readers == 0) warm()
                    }
                }
            }
            done.await()
        }
    }

    suspend fun close() {
        val last = synchronized(lock) { closed = true; cancel(); tail }
        last?.cancelAndJoin()
    }
}
