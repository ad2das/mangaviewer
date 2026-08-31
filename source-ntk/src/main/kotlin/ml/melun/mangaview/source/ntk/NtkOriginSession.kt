package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NtkOriginSession(initialOrigin: String) {
    private val mutex = Mutex()
    private var origin = normalize(initialOrigin)

    suspend fun url(path: String): String = mutex.withLock {
        require(path.startsWith('/')) { "NTK path must be absolute" }
        origin + path
    }

    suspend fun current(): String = mutex.withLock { origin }

    suspend fun observe(finalUrl: String) {
        val value = normalize(finalUrl)
        mutex.withLock { origin = value }
    }

    private companion object {
        fun normalize(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" || uri.scheme == "http") { "NTK origin must use HTTP" }
            require(!uri.host.isNullOrBlank()) { "NTK origin must have a host" }
            val port = if (uri.port < 0) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port"
        }
    }
}
