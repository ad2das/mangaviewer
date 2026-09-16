package ml.melun.mangaview.source.newxtoon

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceThrottledException
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

/** One origin-wide document lane: coalesce duplicate loads and respect the server's cooldown. */
internal class NewxtoonDocumentClient(
    private val transport: SourceTransport,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, CachedDocument>(16, 0.75f, true)
    private var nextRequestAt = 0L
    private var cooldownUntil = 0L

    suspend fun fetch(request: SourceRequest, validate: (String) -> Unit = {}): String = lock.withLock {
        cache[request.url]?.takeIf { clock() - it.savedAt in 0..CACHE_TTL_MILLIS }?.let {
            return@withLock it.html
        }
        val cooldown = cooldownUntil - clock()
        if (cooldown > 0) throw throttled(cooldown)
        delay((nextRequestAt - clock()).coerceAtLeast(0))
        val html = fetchWithRetry(request)
        validate(html)
        if (html.length <= MAX_CACHED_DOCUMENT_CHARS) {
            cache[request.url] = CachedDocument(html, clock())
            while (cache.size > MAX_CACHED_DOCUMENTS) cache.remove(cache.keys.first())
        }
        html
    }

    private suspend fun fetchWithRetry(request: SourceRequest): String {
        for (attempt in 0..2) {
            try {
                val response = transport.execute(request)
                nextRequestAt = clock() + REQUEST_INTERVAL_MILLIS
                val html = readResponse(response, attempt)
                if (html != null) return html
            } catch (limited: SourceThrottledException) {
                throw limited
            } catch (rejected: DocumentRejected) {
                throw rejected
            } catch (failure: IOException) {
                if (attempt == 2) throw failure
                delay(1_000L * (attempt + 1))
            }
        }
        error("Newxtoon document retry did not settle")
    }

    private suspend fun readResponse(response: SourceResponse, attempt: Int): String? {
        val status = response.statusCode
        if (status in 200..299) {
            try { return response.readBytes(MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8) }
            finally { response.close() }
        }
        val retryAfter = retryAfterMillis(response.header("Retry-After"), clock())
        response.close()
        if (status == 429) {
            val wait = retryAfter ?: DEFAULT_COOLDOWN_MILLIS
            cooldownUntil = maxOf(cooldownUntil, saturatedAdd(clock(), wait))
            if (wait > MAX_INLINE_RETRY_MILLIS || attempt == 2) throw throttled(wait)
            delay(wait.coerceAtLeast(250L))
            return null
        }
        if (status in RETRYABLE_STATUS_CODES && attempt < 2) {
            val wait = retryAfter ?: (1_000L * (attempt + 1))
            if (wait > MAX_INLINE_RETRY_MILLIS) {
                cooldownUntil = maxOf(cooldownUntil, saturatedAdd(clock(), wait))
                throw throttled(wait)
            }
            delay(wait.coerceAtLeast(250L))
            return null
        }
        throw DocumentRejected(status)
    }

    private fun throttled(delayMillis: Long) = SourceThrottledException(
        "NEWXTOON request throttled with 429; retry after the provider cooldown",
        delayMillis.coerceAtLeast(0L),
    )

    private data class CachedDocument(val html: String, val savedAt: Long)
    private class DocumentRejected(status: Int) : IOException("NEWXTOON request failed with $status")

    private companion object {
        const val REQUEST_INTERVAL_MILLIS = 2_500L
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
        const val MAX_INLINE_RETRY_MILLIS = 2_000L
        const val CACHE_TTL_MILLIS = 15_000L
        const val MAX_CACHED_DOCUMENTS = 8
        const val MAX_CACHED_DOCUMENT_CHARS = 128 * 1024
        const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
        val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)
    }
}

internal fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    text.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
        return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
    }
    return runCatching {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }.parse(text) ?: return null
        (date.time - nowMillis).coerceAtLeast(0L)
    }.getOrNull()
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
