package ml.melun.mangaview.source.ntk

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

/** User-supplied stable entry points. A landing page alone cannot establish a usable origin. */
class NtkOriginResolver(
    private val transport: SourceTransport,
    private val userAgent: String,
    private val probeParallelism: Int = 3,
    private val onProbe: (String) -> Unit = {},
) {
    init { require(probeParallelism in 1..3) }

    /**
     * Probes the current origin and every stable entry point concurrently and returns the first
     * origin that proves itself. A dead shipped default then costs the fastest live candidate
     * instead of the sum of every preceding probe timeout.
     */
    suspend fun resolve(current: String): String? = coroutineScope {
        val candidates = (listOf(current) + ENTRY_POINTS).distinct()
        onProbe("resolve start current=$current candidates=${candidates.size}")
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
                repeat(workerCount) { results.receive()?.let { return@withTimeoutOrNull it } }
                null
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
    }

    private suspend fun probe(candidate: String): String? {
        val origin = try {
            withTimeoutOrNull(CANDIDATE_TIMEOUT_MILLIS) {
                apiProbe(candidate) ?: listProbe(candidate)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        onProbe(if (origin != null) "probe $candidate -> ok $origin" else "probe $candidate -> unavailable")
        return origin
    }

    /** The catalog JSON only exists on the real provider, so it is the strongest proof. */
    private suspend fun apiProbe(candidate: String): String? = try {
        withTimeoutOrNull(API_PROBE_TIMEOUT_MILLIS) {
            val response = transport.execute(SourceRequest(
                "$candidate/api/works?page=1&pageSize=1&withTotal=1",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
                totalTimeoutMillis = API_PROBE_TIMEOUT_MILLIS,
            ))
            response.use {
                if (it.statusCode != 200) return@withTimeoutOrNull null
                val origin = acceptedOrigin(it.finalUrl) ?: return@withTimeoutOrNull null
                val parsed = NtkDocumentParser().searchApi(
                    it.readBytes(512 * 1024).toString(Charsets.UTF_8), SourceId("ntk"), NtkKind.WEBTOON)
                if (!parsed.recognized || parsed.series.isEmpty()) return@withTimeoutOrNull null
                origin
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    /**
     * The JSON backend can be down while the platform still serves its list pages, and a page that
     * carries the platform's own payload proves the origin just as well. The provider stalls the
     * first request after idle, so the page probe retries inside its budget.
     */
    private suspend fun listProbe(candidate: String): String? {
        repeat(LIST_PROBE_ATTEMPTS) {
            val origin = try {
                withTimeoutOrNull(LIST_PROBE_TIMEOUT_MILLIS) {
                    val response = transport.execute(SourceRequest(
                        "$candidate$LIST_PROBE_PATH",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "text/html,application/xhtml+xml,image/webp,*/*;q=0.8",
                        ),
                        totalTimeoutMillis = LIST_PROBE_TIMEOUT_MILLIS,
                    ))
                    response.use {
                        if (it.statusCode != 200) return@withTimeoutOrNull null
                        val origin = acceptedOrigin(it.finalUrl) ?: return@withTimeoutOrNull null
                        val body = it.readBytes(2 * 1024 * 1024).toString(Charsets.UTF_8)
                        if (body.contains(PAYLOAD_MARKER) && body.contains(BRAND_MARKER, ignoreCase = true)) origin else null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            if (origin != null) return origin
        }
        return null
    }

    private fun acceptedOrigin(finalUrl: String): String? {
        val uri = URI(finalUrl)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
    }

    companion object {
        val ENTRY_POINTS = listOf("https://toki31.com", "https://sbxh9.com", "https://newtoki1.org")
        const val DEFAULT_ORIGIN = "https://toki31.com"
        private const val LIST_PROBE_PATH = "/ing"
        private const val PAYLOAD_MARKER = "sourceWorkId"
        private const val BRAND_MARKER = "newtoki"
        private const val API_PROBE_TIMEOUT_MILLIS = 3_000L
        private const val LIST_PROBE_TIMEOUT_MILLIS = 6_000L
        private const val LIST_PROBE_ATTEMPTS = 3
        private const val CANDIDATE_TIMEOUT_MILLIS = 24_000L
        private const val RESOLUTION_TIMEOUT_MILLIS = 10_000L
    }
}
