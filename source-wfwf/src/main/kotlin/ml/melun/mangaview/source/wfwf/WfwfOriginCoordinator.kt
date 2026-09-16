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
    private val onOriginResolved: (String) -> Unit = {},
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
        var candidate = current()
        val tried = mutableSetOf<String>()
        var lastFailure: Exception? = null
        while (tried.size < MAX_ORIGIN_ATTEMPTS) {
            tried += candidate
            try {
                return raceStartup(candidate, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                val replacement = recover(candidate, tried)
                if (replacement == candidate || replacement in tried) break
                candidate = replacement
            }
        }
        throw lastFailure ?: IllegalStateException("WFWF origin is unavailable")
    }

    suspend fun recover(failedOrigin: String): String = recover(failedOrigin, emptySet())

    private suspend fun recover(failedOrigin: String, excluding: Set<String>): String {
        val published = current()
        if (published != failedOrigin && published !in excluding) return published
        return discover(failedOrigin, excluding)
    }

    suspend fun observe(finalUrl: String, ticket: Long) {
        val observed = normalizeOrigin(finalUrl)
        originLock.withLock { if (ticket == revision) origin = observed }
    }

    private suspend fun discover(baseOrigin: String, excluding: Set<String> = emptySet()): String {
        val resolved = resolver.resolve(baseOrigin, excluding) ?: return current()
        val normalized = normalizeOrigin(resolved)
        var published = false
        val publishedOrigin = originLock.withLock {
            if (origin == baseOrigin && origin != normalized) {
                origin = normalized
                revision += 1L
                published = true
            }
            origin
        }
        if (published) onOriginResolved(normalized)
        return publishedOrigin
    }

    private suspend fun <T> raceStartup(
        attemptedOrigin: String,
        request: suspend (String) -> T,
    ): T = coroutineScope {
        val readiness = startup ?: return@coroutineScope request(attemptedOrigin)
        val direct = async { request(attemptedOrigin) }
        select {
            direct.onAwait { it }
            readiness.onAwait {
                // A completed startup may hold an origin older than one already published by a
                // document observation or a recovery. The published origin is authoritative.
                val published = current()
                if (published == attemptedOrigin) {
                    direct.await()
                } else {
                    direct.cancelAndJoin()
                    request(published)
                }
            }
        }
    }

    private companion object {
        const val MAX_ORIGIN_ATTEMPTS = 3

        fun normalizeOrigin(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" || uri.scheme == "http") { "WFWF origin must use HTTP" }
            require(!uri.host.isNullOrBlank()) { "WFWF origin must include a host" }
            val port = if (uri.port < 0) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port"
        }
    }
}
