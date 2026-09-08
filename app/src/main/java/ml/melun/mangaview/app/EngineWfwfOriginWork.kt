package ml.melun.mangaview.app

import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.engine.api.*

/** Recovery is an owned BODY dependency; only a different validated origin permits replay. */
internal class EngineWfwfOriginWork(
    initialOrigin: URI,
    private val resolve: suspend (String) -> String?,
) {
    private val current = AtomicReference(initialOrigin)

    fun <T : Any> request(factory: (URI) -> WorkRequest<T>): WorkRequest<T> {
        val attempted = current.get()
        val direct = factory(attempted)
        return WorkRequest(direct.key.copy(operation = direct.key.operation + ".origin"),
            WorkDomain.CONTROL, direct.priority, authEpoch = direct.authEpoch, execute = { parent ->
                try {
                    parent.useDependency(direct) { it }
                } catch (failure: IOException) {
                    val replacement = parent.useDependency(recovery(attempted, direct, parent.priority.value)) { it }
                    if (replacement == attempted) throw failure
                    parent.useDependency(factory(replacement)) { it }
                }
            })
    }

    private fun recovery(origin: URI, request: WorkRequest<*>, priority: WorkPriority) = WorkRequest(
        WorkKey(request.key.principal, origin.toString(), "source.origin", "validated", URI::class.java),
        WorkDomain.BODY, priority, authEpoch = request.authEpoch, execute = {
            if (current.get() == origin) {
                resolve(origin.toString())?.let { current.compareAndSet(origin, URI(it)) }
            }
            current.get()
        },
    )
}
