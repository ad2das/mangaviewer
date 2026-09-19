package ml.melun.mangaview.app

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ml.melun.mangaview.source.SourceResponse
import org.json.JSONObject

private const val TAG = "NewxtoonDocCache"

/** One served newxtoon document, durable across process restarts. */
internal class CachedDocument(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val body: ByteArray,
    val storedAt: Long,
    val freshUntil: Long,
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now < freshUntil

    fun response(url: String): SourceResponse = SourceResponse(
        statusCode = statusCode,
        finalUrl = finalUrl.ifBlank { url },
        headers = headers,
        body = ByteArrayPageStream(body),
        contentLength = body.size.toLong(),
        contentType = contentType,
    )
}

/**
 * Disk cache for same-origin newxtoon documents. Every replayed GET otherwise pays a full
 * WebView fetch, so catalog pages, series documents, chapter feeds and reader documents are
 * served stale-while-revalidate: the cached copy answers instantly and a single background
 * refresh keeps the next reader current.
 */
internal class NewxtoonDocumentCache(context: Context) {
    private val dir = File(context.filesDir, "newxtoon_documents").apply { mkdirs() }
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    fun read(url: String): CachedDocument? {
        val meta = metaFile(url)
        val body = bodyFile(url)
        if (!meta.isFile || !body.isFile) return null
        return runCatching {
            val json = JSONObject(meta.readText())
            val headers = linkedMapOf<String, MutableList<String>>()
            val rawHeaders = json.optJSONObject("headers")
            if (rawHeaders != null) {
                for (name in rawHeaders.keys()) {
                    val values = rawHeaders.optJSONArray(name)
                    if (values != null) headers[name] = (0 until values.length()).mapTo(mutableListOf()) {
                        values.optString(it)
                    }
                }
            }
            CachedDocument(
                statusCode = json.getInt("status"),
                finalUrl = json.optString("url"),
                headers = headers,
                contentType = json.optString("type").ifBlank { null },
                body = body.readBytes(),
                storedAt = json.optLong("storedAt"),
                freshUntil = json.optLong("freshUntil"),
            )
        }.onFailure { runCatching { meta.delete() }; runCatching { body.delete() } }.getOrNull()
    }

    fun write(url: String, response: SourceResponse, body: ByteArray, freshForMillis: Long) {
        val now = System.currentTimeMillis()
        runCatching {
            val headers = JSONObject()
            response.headers.forEach { (name, values) ->
                headers.put(name.lowercase(), org.json.JSONArray(values))
            }
            val meta = JSONObject()
                .put("status", response.statusCode)
                .put("url", response.finalUrl)
                .put("type", response.contentType ?: "")
                .put("headers", headers)
                .put("storedAt", now)
                .put("freshUntil", now + freshForMillis)
            val metaFile = metaFile(url)
            val bodyFile = bodyFile(url)
            val tmp = File(dir, bodyFile.name + ".tmp")
            tmp.writeBytes(body)
            // renameTo fails when the target exists on some filesystems; replace guarantees the
            // refreshed body lands instead of silently keeping the stale copy.
            if (!tmp.renameTo(bodyFile)) {
                runCatching { bodyFile.delete() }
                check(tmp.renameTo(bodyFile)) { "cache commit failed for $url" }
            }
            metaFile.writeText(meta.toString())
            prune()
        }.onFailure { Log.w(TAG, "cache write failed for $url", it) }
    }

    /** Serializes concurrent fetches of the same URL; the second caller re-reads the cache. */
    fun urlLock(url: String): Mutex {
        // Unbounded growth turns the map into a leak across a long session, so idle locks retire.
        if (locks.size >= MAX_URL_LOCKS) {
            val iterator = locks.entries.iterator()
            while (iterator.hasNext() && locks.size > MAX_URL_LOCKS / 2) {
                if (!iterator.next().value.isLocked) iterator.remove()
            }
        }
        return locks.getOrPut(url) { Mutex() }
    }

    suspend fun <T> synchronizedOn(url: String, block: suspend () -> T): T = urlLock(url).withLock {
        block()
    }

    private fun metaFile(url: String) = File(dir, key(url) + ".json")
    private fun bodyFile(url: String) = File(dir, key(url) + ".bin")

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Keeps the store bounded; chapter bodies dominate, so the oldest entries leave first. */
    private fun prune() {
        val files = dir.listFiles { file -> file.extension == "bin" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_BYTES) return@forEach
            total -= file.length()
            runCatching { file.delete() }
            runCatching { File(dir, file.nameWithoutExtension + ".json").delete() }
        }
    }

    private companion object {
        const val MAX_BYTES = 96L * 1024 * 1024
        const val MAX_URL_LOCKS = 512
    }
}

/** How long a served document may be re-used before a background refresh re-fetches it. */
internal fun newxtoonDocumentFreshFor(url: String): Long {
    val path = url.substringAfter("://", "").substringAfter('/').let { "/" + it.substringBefore('#') }
    return when {
        // Reader documents are immutable once published; revalidate lazily anyway.
        Regex("/comics/\\d+/chapters/\\d+").containsMatchIn(path) -> 24L * 60 * 60 * 1_000
        // The chapter feed answers JSON for the episode list.
        Regex("/comics/\\d+/chapters(?:\\?|$)").containsMatchIn(path) -> 10L * 60 * 1_000
        Regex("/comics/\\d+(?:\\?|$|/)").containsMatchIn(path) -> 5L * 60 * 1_000
        path.startsWith("/comics") || path.startsWith("/search") -> 60L * 1_000
        else -> 30L * 1_000
    }
}
