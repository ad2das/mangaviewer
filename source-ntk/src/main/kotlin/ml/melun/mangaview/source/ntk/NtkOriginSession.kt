package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NtkOriginSession(initialOrigin: String) {
    private val mutex = Mutex()
    private var origin = originOf(initialOrigin)
    private var revision = 0L

    suspend fun begin(): NtkOriginTicket = mutex.withLock { NtkOriginTicket(origin, ++revision) }

    suspend fun url(path: String): String = mutex.withLock {
        require(path.startsWith('/')) { "NTK path must be absolute" }
        origin + path
    }

    suspend fun current(): String = mutex.withLock { origin }

    suspend fun observe(finalUrl: String, ticket: NtkOriginTicket) {
        val value = originOf(finalUrl)
        mutex.withLock { if (ticket.revision == revision) origin = value }
    }

    companion object {
        internal fun originOf(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" || uri.scheme == "http") { "NTK origin must use HTTP" }
            require(!uri.host.isNullOrBlank()) { "NTK origin must have a host" }
            val port = if (uri.port < 0) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port"
        }
    }
}

data class NtkOriginTicket(val origin: String, val revision: Long)
