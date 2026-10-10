package ml.melun.mangaview.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.source.SourceTransport

/**
 * Session-scoped dedupe around [preconnectImageHosts]: a host is hinted at most once per session,
 * so an episode switch pays the cold connect while its plan is being prepared while the hosts the
 * session already opened are never probed again.
 */
internal class WfwfImageHostWarmer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val transport: SourceTransport,
    private val timeoutMillis: Long,
) {
    private val warmed = LinkedHashSet<String>()

    /** Opens up to [WARM_LIMIT] bodyless HEAD legs for hosts this session has not seen; never awaits. */
    fun warm(hosts: Iterable<String>) {
        val fresh = synchronized(warmed) {
            hosts.mapNotNull(::normalize).filter(warmed::add).take(WARM_LIMIT)
        }
        if (fresh.isEmpty()) return
        preconnectImageHosts(scope, dispatcher, transport, { fresh }, timeoutMillis)
    }

    private fun normalize(host: String): String? = host.trim().lowercase()
        .takeIf { it.isNotEmpty() && !it.contains('/') }

    private companion object {
        const val WARM_LIMIT = 2
    }
}
