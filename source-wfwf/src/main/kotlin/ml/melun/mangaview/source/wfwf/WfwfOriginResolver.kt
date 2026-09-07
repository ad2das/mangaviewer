package ml.melun.mangaview.source.wfwf

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.readBytes

/** Last provider origin verified with its real catalog and episode documents. */
const val DEFAULT_WFWF_ORIGIN = "https://wfwf492.com"

class WfwfOriginResolver(
    private val transport: SourceTransport,
    private val userAgent: String,
) {
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
        val results = Channel<String?>(PROBE_PARALLELISM)
        val workerCount = minOf(PROBE_PARALLELISM, candidates.size)
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
                url = "$candidate/ing",
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
                response.statusCode in 200..299 && looksAlive(body) -> finalOrigin
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
        return numbers.distinct().map { "https://wfwf$it.com" }
    }

    private fun updatedOrigin(body: String): String? {
        val lower = body.lowercase()
        val hasAddressContext = lower.contains("window.location") || lower.contains("main-btn") ||
            lower.contains("새로운 주소") || lower.contains("주소가 변경") || lower.contains("updated address")
        if (!hasAddressContext) return null
        val match = UPDATED_URL.find(body) ?: return null
        return originOf(match.value).takeIf { NUMBERED_HOST.matches(URI(it).host.orEmpty()) }
    }

    private fun looksAlive(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("webtoon-list") || lower.contains("/webtoon/") ||
            lower.contains("/manhwa/") || lower.contains("/view?toon=") ||
            lower.contains("/list?toon=") || lower.contains("/cv?toon=") ||
            lower.contains("/cl?toon=")
    }

    private fun originOf(value: String): String {
        val uri = URI(value)
        val port = if (uri.port < 0) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    private companion object {
        val DEFAULT_NUMBER = URI(DEFAULT_WFWF_ORIGIN).host.removePrefix("wfwf").removeSuffix(".com").toInt()
        const val MAX_ADDRESS_HOPS = 2
        const val FORWARD_DISTANCE = 36
        const val BACKWARD_DISTANCE = 4
        const val PROBE_PARALLELISM = 4
        const val PROBE_TIMEOUT_MILLIS = 1_500L
        const val RESOLUTION_TIMEOUT_MILLIS = 6_000L
        const val MAX_PROBE_BYTES = 512 * 1_024
        val NUMBERED_HOST = Regex("wfwf([0-9]{1,5})\\.com", RegexOption.IGNORE_CASE)
        val UPDATED_URL = Regex("https://wfwf[0-9]{1,5}\\.com", RegexOption.IGNORE_CASE)
    }

    private data class ResolutionClaim(
        val result: CompletableDeferred<Result<String?>>,
        val leader: Boolean,
    )
}
