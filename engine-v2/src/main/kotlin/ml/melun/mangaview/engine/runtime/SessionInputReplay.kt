package ml.melun.mangaview.engine.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns only the posted continuation; the session retains all queued input and geometry. */
internal class SessionInputReplay(
    private val scope: CoroutineScope,
    private val generation: () -> Long,
    private val ready: () -> Boolean,
    private val resume: (Long) -> Unit,
) {
    private var job: Job? = null

    fun schedule() {
        if (!ready() || job != null) return
        val expectedGeneration = generation()
        val continuation = scope.launch(start = CoroutineStart.LAZY) {
            // A real suspension also yields on Main.immediate, allowing input and VSYNC
            // messages to run before the next bounded slice.
            delay(1)
            job = null
            if (ready() && generation() == expectedGeneration) resume(expectedGeneration)
        }
        job = continuation
        continuation.start()
    }

    fun cancel() { job?.cancel(); job = null }
    suspend fun close() { job?.cancelAndJoin(); job = null }
}
