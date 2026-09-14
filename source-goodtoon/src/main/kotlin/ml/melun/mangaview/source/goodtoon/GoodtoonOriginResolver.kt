package ml.melun.mangaview.source.goodtoon

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

/** Last provider origin verified with its real catalog document. */
const val DEFAULT_GOODTOON_ORIGIN = "https://www.goodtoon004.com"

/**
 * GoodToon rotates through `www.goodtoonNNN.com` hosts and redirects its apex domain to the
 * current number. Only a document that still contains catalog markers counts as alive; hosts
 * behind a JS challenge serve 200 with no catalog content and must be rejected.
 */
class GoodtoonOriginResolver(
    private val transport: SourceTransport,
    private val userAgent: String,
    private val probeParallelism: Int = 4,
) {
    init { require(probeParallelism in 1..4) }
    private val flightLock = Mutex()
    private var inFlight: CompletableDeferred<Result<String?>>? = null

    suspend fun resolve(currentOrigin: String): String? {
        val claim = flightLock.withLock {
            inFlight?.let { return@withLock ResolutionClaim(it, leader = false) }
            val result = CompletableDeferred<Result<String?>>()
            inFlight = result
            ResolutionClaim(result, leader = true)
        }
        if (claim.leader) {
            try {
                claim.result.complete(runCatching { resolveNow(currentOrigin) })
            } finally {
                withContext(NonCancellable) {
                    flightLock.withLock {
                        if (inFlight === claim.result) inFlight = null
                    }
                }
            }
        }
        return claim.result.await().getOrThrow()
    }

    private suspend fun resolveNow(currentOrigin: String): String? = coroutineScope {
        val candidates = candidates(currentOrigin)
        val cursor = AtomicInteger()
        val results = Channel<String?>(probeParallelism)
        val workerCount = minOf(probeParallelism, candidates.size)
        val jobs = List(workerCount) {
            launch {
                while (true) {
                    val index = cursor.getAndIncrement()
                    if (index >= candidates.size) break
                    probe(candidates[index])?.let { resolved ->
                        results.send(resolved)
                        return@launch
                    }
                }
                results.send(null)
            }
        }
        try {
            withTimeoutOrNull(RESOLUTION_TIMEOUT_MILLIS) {
                repeat(workerCount) {
                    results.receive()?.let { return@withTimeoutOrNull it }
                }
                null
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
    }

    private suspend fun probe(candidate: String, visited: Set<String> = emptySet()): String? = try {
        val response = transport.execute(
            SourceRequest(
                url = "$candidate/ongoing/",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,*/*"),
                totalTimeoutMillis = PROBE_TIMEOUT_MILLIS,
                priority = PageFetchPriority.BACKGROUND,
            ),
        )
        if (response.statusCode !in 200..499) {
            response.close()
            null
        } else {
            val finalOrigin = originOf(response.finalUrl)
            val body = response.readBytes(MAX_PROBE_BYTES).toString(Charsets.UTF_8)
            val updated = updatedOrigin(body)?.takeIf { it != candidate }
            when {
                updated != null && updated !in visited && visited.size < MAX_ADDRESS_HOPS ->
                    probe(updated, visited + candidate)
                updated != null -> null
                response.statusCode in 200..299 && looksAlive(body) && isProviderOrigin(finalOrigin) ->
                    finalOrigin
                else -> null
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun candidates(currentOrigin: String): List<String> {
        val parsed = NUMBERED_HOST.matchEntire(URI(currentOrigin).host.orEmpty())
        val current = parsed?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_NUMBER
        val numbers = buildList {
            add(current)
            repeat(FORWARD_DISTANCE) { offset -> add(current + offset + 1) }
            repeat(BACKWARD_DISTANCE) { offset ->
                if (current - offset - 1 > 0) add(current - offset - 1)
            }
            if (DEFAULT_NUMBER !in this) add(DEFAULT_NUMBER)
        }
        return numbers.distinct().map { "https://www.goodtoon%03d.com".format(it) } + APEX_ORIGIN
    }

    private fun updatedOrigin(body: String): String? {
        val lower = body.lowercase()
        val hasAddressContext = lower.contains("window.location") || lower.contains("location.href") ||
            lower.contains("주소") || lower.contains("updated address")
        if (!hasAddressContext) return null
        val match = UPDATED_URL.find(body) ?: return null
        val origin = originOf(match.value)
        return origin.takeIf(::isProviderOrigin)
    }

    private fun looksAlive(body: String): Boolean {
        if (!body.contains("/manga/")) return false
        return body.contains("class=\"card\"") || body.contains("card-grid") ||
            body.contains("wp-manga-chapter")
    }

    private fun isProviderOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull() ?: return false
        return NUMBERED_HOST.matches(host) || APEX_HOST.matches(host)
    }

    private fun originOf(value: String): String {
        val uri = URI(value)
        val port = if (uri.port < 0) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    private companion object {
        val DEFAULT_NUMBER = URI(DEFAULT_GOODTOON_ORIGIN).host
            .replace("www.goodtoon", "").replace(".com", "").toInt()
        const val APEX_ORIGIN = "https://goodtoon.top"
        const val MAX_ADDRESS_HOPS = 2
        const val FORWARD_DISTANCE = 12
        const val BACKWARD_DISTANCE = 6
        const val PROBE_TIMEOUT_MILLIS = 2_500L
        const val RESOLUTION_TIMEOUT_MILLIS = 9_000L
        const val MAX_PROBE_BYTES = 512 * 1_024
        val NUMBERED_HOST = Regex("(?:www\\.)?goodtoon([0-9]{1,4})\\.com", RegexOption.IGNORE_CASE)
        val APEX_HOST = Regex("(?:www\\.)?goodtoon\\.top", RegexOption.IGNORE_CASE)
        val UPDATED_URL = Regex("https?://(?:www\\.)?goodtoon[0-9]{1,4}\\.com", RegexOption.IGNORE_CASE)
    }

    private data class ResolutionClaim(
        val result: CompletableDeferred<Result<String?>>,
        val leader: Boolean,
    )
}
