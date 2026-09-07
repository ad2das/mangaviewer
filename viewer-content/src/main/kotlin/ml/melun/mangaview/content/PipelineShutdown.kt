package ml.melun.mangaview.content

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext

/** One closer owns shutdown; every caller awaits all physically executing child jobs. */
internal class PipelineShutdown {
    private val requested = AtomicBoolean(false)
    private val completed = CompletableDeferred<Unit>()
    val started: Boolean get() = requested.get()

    suspend fun close(owner: Job, actor: Job, commands: SendChannel<PipelineCommand>) {
        if (!requested.compareAndSet(false, true)) {
            withContext(NonCancellable) { completed.await() }
            return
        }
        try {
            if (owner.isActive) {
                val reply = CompletableDeferred<Unit>()
                commands.send(PipelineCommand.Close(reply))
                reply.await()
                actor.join()
            }
        } finally {
            withContext(NonCancellable) {
                try { owner.cancelAndJoin() } finally { completed.complete(Unit) }
            }
        }
    }
}
