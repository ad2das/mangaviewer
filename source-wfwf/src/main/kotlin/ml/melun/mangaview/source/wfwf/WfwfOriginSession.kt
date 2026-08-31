package ml.melun.mangaview.source.wfwf

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WfwfOriginSession(initialOrigin: String) {
    private val mutex = Mutex()
    private var origin = normalizeOrigin(initialOrigin)

    suspend fun resolve(path: String): String = mutex.withLock {
        require(path.startsWith('/')) { "WFWF path must be absolute" }
        origin + path
    }

    suspend fun current(): String = mutex.withLock { origin }

    suspend fun replace(value: String) {
        val replacement = normalizeOrigin(value)
        mutex.withLock { origin = replacement }
    }

    suspend fun observe(finalUrl: String) {
        val observed = normalizeOrigin(finalUrl)
        mutex.withLock { origin = observed }
    }

    private companion object {
        fun normalizeOrigin(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" || uri.scheme == "http") { "WFWF origin must use HTTP" }
            require(!uri.host.isNullOrBlank()) { "WFWF origin must include a host" }
            val port = if (uri.port < 0) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port"
        }
    }
}
