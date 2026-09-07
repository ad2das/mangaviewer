package ml.melun.mangaview.source.wfwf

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns WFWF origin discovery and publication for one source instance. */
internal class WfwfOriginCoordinator(
    initialOrigin: String,
    private val resolver: WfwfOriginResolver,
    scope: CoroutineScope?,
) {
    private val originLock = Mutex()
    private var origin = normalizeOrigin(initialOrigin)
    private var revision = 0L
    private val startup: Deferred<String>? = scope?.async(start = CoroutineStart.LAZY) {
        discover(origin)
    }

    fun start() {
        startup?.start()
    }

    suspend fun awaitReady(): String = startup?.await() ?: current()

    suspend fun current(): String = originLock.withLock { origin }

    suspend fun beginDocument(): Long = originLock.withLock { ++revision }

    suspend fun resolve(path: String): String {
        require(path.startsWith('/')) { "WFWF path must be absolute" }
        return current() + path
    }

    suspend fun <T> execute(request: suspend (String) -> T): T {
        val attemptedOrigin = current()
        return try {
            raceStartup(attemptedOrigin, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Exception) {
            val replacement = recover(attemptedOrigin)
            if (replacement == attemptedOrigin) throw firstFailure
            request(replacement)
        }
    }

    suspend fun recover(failedOrigin: String): String {
        val published = current()
        if (published != failedOrigin) return published
        return discover(failedOrigin)
    }

    suspend fun observe(finalUrl: String, ticket: Long) {
        val observed = normalizeOrigin(finalUrl)
        originLock.withLock { if (ticket == revision) origin = observed }
    }

    private suspend fun discover(baseOrigin: String): String {
        val resolved = resolver.resolve(baseOrigin) ?: return current()
        return originLock.withLock {
            if (origin == baseOrigin && origin != normalizeOrigin(resolved)) {
                origin = normalizeOrigin(resolved)
                revision += 1L
            }
            origin
        }
    }

    private suspend fun <T> raceStartup(
        attemptedOrigin: String,
        request: suspend (String) -> T,
    ): T = coroutineScope {
        val readiness = startup ?: return@coroutineScope request(attemptedOrigin)
        val direct = async { request(attemptedOrigin) }
        select {
            direct.onAwait { it }
            readiness.onAwait { readyOrigin ->
                if (readyOrigin == attemptedOrigin) {
                    direct.await()
                } else {
                    direct.cancelAndJoin()
                    request(readyOrigin)
                }
            }
        }
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
