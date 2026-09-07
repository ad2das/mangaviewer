package ml.melun.mangaview.source.ntk

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches the provider's immutable guard assets while its episode document is in flight.
 *
 * The bytes still come from the provider and execute inside its real origin. This only removes the
 * otherwise serial block.js -> module -> WASM network waterfall after the main document arrives.
 */
internal class NtkBrowserStaticResourceCache(
    private val diskStore: NtkStaticResourceDiskStore = NtkStaticResourceDiskStore(root = { null }),
) {
    private val closed = AtomicBoolean(false)
    private val resources = ConcurrentHashMap<String, CompletableFuture<NtkStaticResource?>>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    // Fetch in dependency order on one background connection. Three cold connections saturated
    // the origin and delayed the much smaller visible challenge and request-key exchanges.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { work ->
        Thread(work, "ntk-static-prefetch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun prepare(origin: String, userAgent: String) {
        if (closed.get()) return
        val normalized = validatedOrigin(origin) ?: return
        PATHS.forEach { (path, limit) ->
            val key = normalized + path
            resources.compute(key) { _, previous ->
                if (previous != null && (!previous.isDone || previous.getNow(null)?.isFresh() == true)) {
                    return@compute previous
                }
                val result = CompletableFuture<NtkStaticResource?>()
                executor.execute {
                    result.complete(runCatching {
                        diskStore.load(normalized, path, limit, MIME_BY_PATH.getValue(path))
                            ?: fetch(normalized, path, limit, userAgent)?.also(diskStore::save)
                    }.getOrNull())
                }
                result
            }
        }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (closed.get() || request.isForMainFrame || request.method != "GET" ||
            request.url.scheme != "https" || !request.url.query.isNullOrEmpty()
        ) return null
        val key = validatedOrigin(request.url.toString())?.plus(request.url.path.orEmpty()) ?: return null
        val pending = resources[key] ?: return null
        val resource = pending.getNow(null) ?: return null
        if (!resource.isFresh()) {
            resources.remove(key, pending)
            return null
        }
        if (!resource.accepts(request.url)) return null
        return WebResourceResponse(
            resource.mimeType,
            null,
            HttpURLConnection.HTTP_OK,
            "OK",
            mapOf(
                "Cache-Control" to "private, max-age=${resource.remainingSeconds()}",
                "Content-Length" to resource.bytes.size.toString(),
            ),
            ByteArrayInputStream(resource.bytes),
        )
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.clear()
        connections.forEach(HttpURLConnection::disconnect)
        executor.shutdownNow()
    }

    private fun fetch(origin: String, path: String, limit: Int, userAgent: String): NtkStaticResource? {
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(origin + path).openConnection() as HttpURLConnection
            connections += connection
            if (closed.get()) return null
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept", ACCEPT_BY_PATH.getValue(path))
            val status = connection.responseCode
            if (status !in 200..299) return null
            val finalUri = connection.url.toURI()
            if (finalUri.scheme != "https" || finalUri.path != path) return null
            val mime = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (mime !in allowedMimeTypes(path)) return null
            val expiresAt = freshnessDeadline(connection.getHeaderField("Cache-Control"),
                connection.getHeaderFieldLong("Age", 0L)) ?: return null
            val bytes = readAssetBody(connection, limit) ?: return null
            NtkStaticResource(
                path = path,
                originalHost = requireNotNull(URI(origin).host).lowercase(),
                finalHost = requireNotNull(finalUri.host).lowercase(),
                mimeType = MIME_BY_PATH.getValue(path),
                bytes = bytes,
                originalPort = URI(origin).port.takeIf { it >= 0 } ?: 443,
                finalPort = finalUri.port.takeIf { it >= 0 } ?: 443,
                expiresAtMillis = expiresAt,
            ).also {
                val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(0L) / 1_000_000L
                runCatching {
                    Log.d(LOG_TAG, "prefetched path=$path bytes=${bytes.size} elapsedMs=$elapsed")
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
            connection?.let(connections::remove)
        }
    }

    private fun readAssetBody(connection: HttpURLConnection, limit: Int): ByteArray? {
        val declared = connection.contentLengthLong
        if (declared > limit) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream(
                declared.takeIf { it in 1..limit.toLong() }?.toInt() ?: INITIAL_CAPACITY,
            )
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > limit) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return bytes.takeIf { it.isNotEmpty() && (declared < 0L || it.size.toLong() == declared) }
    }

    private fun validatedOrigin(value: String): String? = runCatching {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.query == null && uri.fragment == null)
        val port = uri.port.takeIf { it >= 0 && it != 443 }?.let { ":$it" }.orEmpty()
        "https://${uri.host.lowercase()}$port"
    }.getOrNull()

    private fun allowedMimeTypes(path: String): Set<String> = if (path.endsWith(".wasm")) {
        setOf("application/wasm")
    } else setOf("application/javascript", "text/javascript", "application/x-javascript")

    private fun freshnessDeadline(cacheControl: String?, ageSeconds: Long): Long? {
        val directives = cacheControl.orEmpty().lowercase().split(',').map(String::trim)
        if (directives.any { it in setOf("no-store", "no-cache") }) return null
        val maxAge = directives.firstOrNull { it.startsWith("max-age=") }
            ?.substringAfter('=')?.trim('"')?.toLongOrNull() ?: return null
        val remaining = maxAge - ageSeconds.coerceAtLeast(0L)
        if (remaining <= 0L || remaining > Long.MAX_VALUE / 1_000L) return null
        return System.currentTimeMillis() + remaining.coerceAtMost(86_400L) * 1_000L
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val READ_TIMEOUT_MILLIS = 4_000
        const val INITIAL_CAPACITY = 64 * 1_024
        const val BUFFER_SIZE = 32 * 1_024
        const val LOG_TAG = "NtkStaticWarm"
        const val BLOCK_PATH = "/init/block.js"

        val PATHS = mapOf(
            BLOCK_PATH to 512 * 1_024,
            "/wasm/ad-guard/ad_guard.js" to 2 * 1_024 * 1_024,
            "/wasm/ad-guard/ad_guard_bg.wasm" to 8 * 1_024 * 1_024,
        )
        val MIME_BY_PATH = mapOf(
            "/init/block.js" to "application/javascript",
            "/wasm/ad-guard/ad_guard.js" to "application/javascript",
            "/wasm/ad-guard/ad_guard_bg.wasm" to "application/wasm",
        )
        val ACCEPT_BY_PATH = mapOf(
            "/init/block.js" to "application/javascript,text/javascript,*/*;q=0.1",
            "/wasm/ad-guard/ad_guard.js" to "application/javascript,text/javascript,*/*;q=0.1",
            "/wasm/ad-guard/ad_guard_bg.wasm" to "application/wasm,*/*;q=0.1",
        )
    }
}

internal data class NtkStaticResource(
    val path: String,
    val originalHost: String,
    val finalHost: String,
    val mimeType: String,
    val bytes: ByteArray,
    val originalPort: Int = 443,
    val finalPort: Int = 443,
    val expiresAtMillis: Long = Long.MAX_VALUE,
) {
    val originalOrigin: String
        get() = "https://$originalHost" + if (originalPort == 443) "" else ":$originalPort"

    fun accepts(uri: Uri): Boolean = uri.scheme == "https" && uri.path == path &&
        uri.query.isNullOrEmpty() && uri.host?.lowercase() == originalHost &&
        (uri.port.takeIf { it >= 0 } ?: 443) == originalPort

    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis < expiresAtMillis

    fun remainingSeconds(): Long = ((expiresAtMillis - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
}
