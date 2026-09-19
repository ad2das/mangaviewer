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

/** Floor of the adaptive request spacing. Kept well under the old 2.5s cadence but not aggressive. */
internal const val NEWXTOON_MIN_REQUEST_INTERVAL_MILLIS = 200L

/** Ceiling the spacing backs off to after throttling: the previously fixed 2.5s cadence. */
internal const val NEWXTOON_MAX_REQUEST_INTERVAL_MILLIS = 2_500L

private const val INTERVAL_DECAY_MILLIS = 100L

/**
 * One origin-wide document lane: identical loads coalesce onto the first request, the provider's
 * own cooldown is honoured, and requests leave at an adaptive spacing. A throttled response doubles
 * the spacing up to the known-safe cadence; every accepted response walks it back toward the floor,
 * so a series with dozens of chapter pages no longer pays 2.5s per page.
 */
internal class NewxtoonDocumentClient(
    private val transport: SourceTransport,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, CachedDocument>(16, 0.75f, true)
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<String>>()
    private var nextRequestAt = 0L
    private var cooldownUntil = 0L
    @Volatile private var requestIntervalMillis = NEWXTOON_MIN_REQUEST_INTERVAL_MILLIS

    suspend fun fetch(request: SourceRequest, validate: (String) -> Unit = {}): String {
        val url = request.url
        val existing = lock.withLock {
            cache[url]?.takeIf { clock() - it.savedAt in 0..CACHE_TTL_MILLIS }?.let { return it.html }
            inFlight[url]?.let { return@withLock it to false }
            kotlinx.coroutines.CompletableDeferred<String>().also { inFlight[url] = it } to true
        }
        val shared = existing.first
        if (!existing.second) return shared.await()
        try {
            val sendAt = lock.withLock {
                val now = clock()
                val cooldown = cooldownUntil - now
                if (cooldown > 0) throw throttled(cooldown)
                val at = if (nextRequestAt > now) nextRequestAt else now
                nextRequestAt = saturatedAdd(at, requestIntervalMillis)
                at
            }
            delay((sendAt - clock()).coerceAtLeast(0))
            val html = fetchWithRetry(request)
            validate(html)
            lock.withLock {
                if (html.length <= MAX_CACHED_DOCUMENT_CHARS) {
                    cache[url] = CachedDocument(html, clock())
                    while (cache.size > MAX_CACHED_DOCUMENTS) cache.remove(cache.keys.first())
                }
            }
            shared.complete(html)
            return html
        } catch (failure: Throwable) {
            shared.completeExceptionally(failure)
            throw failure
        } finally {
            lock.withLock { if (inFlight[url] === shared) inFlight.remove(url) }
        }
    }

    private suspend fun fetchWithRetry(request: SourceRequest): String {
        for (attempt in 0..2) {
            try {
                val response = transport.execute(request)
                val html = readResponse(response, attempt)
                if (html != null) {
                    // Only a request the provider accepted first time proves the current pace is
                    // safe; a retry that followed a 429 must not immediately unwind the back-off.
                    if (attempt == 0) relaxInterval()
                    return html
                }
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
            tightenInterval()
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

    private fun relaxInterval() {
        requestIntervalMillis = (requestIntervalMillis - INTERVAL_DECAY_MILLIS)
            .coerceAtLeast(NEWXTOON_MIN_REQUEST_INTERVAL_MILLIS)
    }

    private fun tightenInterval() {
        requestIntervalMillis = (requestIntervalMillis * 2)
            .coerceAtMost(NEWXTOON_MAX_REQUEST_INTERVAL_MILLIS)
    }

    private fun throttled(delayMillis: Long) = SourceThrottledException(
        "NEWXTOON request throttled with 429; retry after the provider cooldown",
        delayMillis.coerceAtLeast(0L),
    )

    private data class CachedDocument(val html: String, val savedAt: Long)
    private class DocumentRejected(status: Int) : IOException("NEWXTOON request failed with $status")

    private companion object {
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
