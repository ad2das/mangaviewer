package ml.melun.mangaview.source.wfwf

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

class WfwfOriginResolver(
    private val transport: SourceTransport,
    private val userAgent: String,
) {
    suspend fun resolve(currentOrigin: String): String? = coroutineScope {
        probe(currentOrigin)?.let { return@coroutineScope it }
        val results = Channel<String?>(Channel.UNLIMITED)
        val jobs = candidates(currentOrigin).map { candidate ->
            async {
                val result = probe(candidate)
                results.send(result)
            }
        }
        val resolved = withTimeoutOrNull(RESOLUTION_TIMEOUT_MILLIS) {
            repeat(jobs.size) {
                results.receive()?.let { return@withTimeoutOrNull it }
            }
            null
        }
        jobs.forEach { it.cancel() }
        results.cancel()
        resolved
    }

    private suspend fun probe(candidate: String): String? = try {
        val response = transport.execute(
            SourceRequest(
                url = "$candidate/ing",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,*/*"),
                totalTimeoutMillis = PROBE_TIMEOUT_MILLIS,
            ),
        )
        if (response.statusCode !in 200..499) {
            response.close()
            null
        } else {
            val finalOrigin = originOf(response.finalUrl)
            val body = response.readBytes(MAX_PROBE_BYTES).toString(Charsets.UTF_8)
            val updated = updatedOrigin(body)?.takeIf { it != candidate }
            updated ?: finalOrigin.takeIf { looksAlive(body) }
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
            repeat(NEARBY_DISTANCE) { offset ->
                add(current + offset + 1)
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
        const val DEFAULT_NUMBER = 455
        const val NEARBY_DISTANCE = 36
        const val PROBE_TIMEOUT_MILLIS = 1_500L
        const val RESOLUTION_TIMEOUT_MILLIS = 6_000L
        const val MAX_PROBE_BYTES = 512 * 1_024
        val NUMBERED_HOST = Regex("wfwf([0-9]{1,5})\\.com", RegexOption.IGNORE_CASE)
        val UPDATED_URL = Regex("https://wfwf[0-9]{1,5}\\.com", RegexOption.IGNORE_CASE)
    }
}
