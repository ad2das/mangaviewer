package ml.melun.mangaview.data.network

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.QuicOptions
import android.net.http.UrlRequest
import android.os.Process
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport

/** Chromium-backed transport for origins that require HTTP/3 or browser-compatible TLS. */
@RequiresApi(34)
class HttpEngineSourceTransport(
    context: Context,
    private val userAgent: String,
) : SourceTransport, Closeable {
    private val appContext = context.applicationContext
    private val callbackExecutor: ExecutorService = Executors.newFixedThreadPool(8) { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "source-http-engine",
        ).apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val timeoutExecutor: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "source-http-engine-timeout").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }.apply { removeOnCancelPolicy = true }
    private val engineLock = Any()
    private val engines = mutableMapOf<String, EngineEntry>()
    private val engineCreations = mutableMapOf<String, CompletableFuture<EngineEntry>>()
    private val warmedOrigins = linkedSetOf<String>()
    private var engineUseSequence = 0L
    private val exchanges = TransportResourceOwner<HttpEngineExchange>()
    private val bodies = TransportResourceOwner<HttpEngineBodyPageStream>()
    private val closed = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)

    override suspend fun execute(request: SourceRequest): SourceResponse {
        check(!closed.get()) { "HTTP engine transport is closed" }
        val startedAtNanos = System.nanoTime()
        return suspendCancellableCoroutine { continuation ->
            start(request, continuation, startedAtNanos)
        }
    }

    override fun warmConnections(urls: List<String>) {
        if (closed.get()) return
        urls.distinctBy(::engineKey).forEach { url ->
            val key = engineKey(url)
            val schedule = synchronized(engineLock) {
                if (closed.get() || key in warmedOrigins) return@synchronized false
                warmedOrigins += key
                engines[key] == null && engineCreations[key] == null
            }
            if (!schedule) return@forEach
            runCatching {
                callbackExecutor.execute {
                    runCatching { acquireEngine(url) }
                        .onSuccess { lease -> releaseEngine(lease.key) }
                        .onFailure {
                            synchronized(engineLock) { warmedOrigins.remove(key) }
                        }
                }
            }.onFailure {
                synchronized(engineLock) { warmedOrigins.remove(key) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = exchanges.closeAndSnapshot()
        bodies.closeAndSnapshot().forEach(HttpEngineBodyPageStream::close)
        active.forEach(HttpEngineExchange::cancelForClose)
        if (active.isEmpty()) shutdownResources()
    }

    private fun start(
        sourceRequest: SourceRequest,
        continuation: CancellableContinuation<SourceResponse>,
        startedAtNanos: Long,
    ) {
        val exchange = runCatching {
            HttpEngineExchange(
                continuation = continuation,
                finished = ::exchangeFinished,
                registerBody = bodies::register,
                bodyFinished = ::bodyFinished,
            )
        }.getOrElse {
            continuation.resumeWithException(it)
            return
        }
        if (!exchanges.register(exchange)) {
            exchange.abort(IOException("HTTP engine transport is closed"))
            return
        }
        val request = runCatching {
            requestBuilder(sourceRequest, exchange).build()
        }.getOrElse {
            exchange.abort(it)
            return
        }
        exchange.attach(request)
        continuation.invokeOnCancellation { exchange.cancel() }
        val remainingNanos = remainingTimeoutNanos(sourceRequest, startedAtNanos)
        if (remainingNanos <= 0L) {
            exchange.timeout()
            return
        }
        val timeout = runCatching {
            timeoutExecutor.schedule(
                exchange::timeout,
                remainingNanos,
                TimeUnit.NANOSECONDS,
            )
        }.getOrElse {
            exchange.abort(it)
            return
        }
        exchange.armTimeout(timeout)
        exchange.start { closed.get() || !continuation.isActive }
    }

    private fun remainingTimeoutNanos(request: SourceRequest, startedAtNanos: Long): Long {
        return httpEngineRemainingTimeoutNanos(
            request.totalTimeoutMillis,
            startedAtNanos,
            System.nanoTime(),
        )
    }

    private fun requestBuilder(
        request: SourceRequest,
        callback: HttpEngineExchange,
    ): UrlRequest.Builder {
        val lease = acquireEngine(request.url)
        callback.attachEngine(lease.key)
        val builder = lease.engine.newUrlRequestBuilder(
            request.url,
            callbackExecutor,
            callback,
        ).setHttpMethod(request.method.name)
            .setCacheDisabled(true)
        request.headers.forEach(builder::addHeader)
        if (request.method == SourceHttpMethod.POST) {
            val bodyMediaType = request.bodyMediaType
            if (bodyMediaType != null && request.headers.keys.none {
                    it.equals("Content-Type", ignoreCase = true)
                }
            ) {
                builder.addHeader("Content-Type", bodyMediaType)
            }
            builder.setUploadDataProvider(
                HttpEngineUploadProvider(request.body ?: ByteArray(0)),
                callbackExecutor,
            )
        }
        return builder
    }

    private fun acquireEngine(url: String): EngineLease {
        val uri = URI(url)
        val host = requireNotNull(uri.host) { "HTTP engine URL has no host" }.lowercase()
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        val key = engineKey(uri, host, port)
        val acquisition = synchronized(engineLock) {
            check(!closed.get()) { "HTTP engine transport is closed" }
            engines[key]?.let { entry ->
                entry.inFlight += 1
                entry.lastUsed = nextEngineUseSequence()
                return EngineLease(key, entry.engine)
            }
            engineCreations[key]?.let { return@synchronized EngineAcquisition(it, false) }
            val future = CompletableFuture<EngineEntry>()
            engineCreations[key] = future
            EngineAcquisition(future, true)
        }
        if (acquisition.leader) createEngineFlight(key, uri.scheme, host, port, acquisition.future)
        val entry = awaitEngine(acquisition.future)
        return synchronized(engineLock) {
            check(!closed.get()) { "HTTP engine transport is closed" }
            check(engines[key] === entry) { "HTTP engine creation lost ownership" }
            entry.inFlight += 1
            entry.lastUsed = nextEngineUseSequence()
            EngineLease(key, entry.engine)
        }
    }

    private fun createEngineFlight(
        key: String,
        scheme: String?,
        host: String,
        port: Int,
        future: CompletableFuture<EngineEntry>,
    ) {
        val result = runCatching { createEngine(scheme, host, port) }
        synchronized(engineLock) {
            engineCreations.remove(key, future)
            result.onSuccess { entry ->
                if (closed.get()) {
                    runCatching { entry.engine.shutdown() }
                    future.completeExceptionally(IOException("HTTP engine transport is closed"))
                } else {
                    pruneEngines(MAX_ENGINES - 1)
                    engines[key] = entry
                    future.complete(entry)
                }
            }.onFailure(future::completeExceptionally)
        }
    }

    private fun awaitEngine(future: CompletableFuture<EngineEntry>): EngineEntry = try {
        future.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }

    private fun createEngine(scheme: String?, host: String, port: Int): EngineEntry {
        val builder = HttpEngine.Builder(appContext)
            .setEnableHttp2(true)
            .setEnableQuic(scheme == "https")
            .setEnableBrotli(true)
            .setUserAgent(userAgent)
        if (scheme == "https") {
            builder.setQuicOptions(
                QuicOptions.Builder()
                    .addAllowedQuicHost(host)
                    .setHandshakeUserAgent(userAgent)
                    .build(),
            ).addQuicHint(host, port, port)
        }
        return EngineEntry(builder.build())
    }

    private fun releaseEngine(key: String) {
        synchronized(engineLock) {
            val entry = engines[key] ?: return
            entry.inFlight = (entry.inFlight - 1).coerceAtLeast(0)
            entry.lastUsed = nextEngineUseSequence()
            pruneEngines(MAX_ENGINES)
        }
    }

    private fun pruneEngines(targetSize: Int) {
        while (engines.size > targetSize) {
            val victim = engines.entries.filter { it.value.inFlight == 0 }
                .minByOrNull { it.value.lastUsed } ?: return
            engines.remove(victim.key)
            warmedOrigins.remove(victim.key)
            runCatching { victim.value.engine.shutdown() }
        }
    }

    private fun nextEngineUseSequence(): Long {
        if (engineUseSequence == Long.MAX_VALUE) {
            engines.values.forEach { it.lastUsed = 0L }
            engineUseSequence = 0L
        }
        engineUseSequence += 1L
        return engineUseSequence
    }

    private fun exchangeFinished(exchange: HttpEngineExchange) {
        exchange.engineKey()?.let(::releaseEngine)
        if (exchanges.complete(exchange) && closed.get()) shutdownResources()
    }

    private fun bodyFinished(body: HttpEngineBodyPageStream) {
        bodies.complete(body)
    }

    private fun shutdownResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        synchronized(engineLock) {
            engines.values.forEach { entry -> runCatching { entry.engine.shutdown() } }
            engines.clear()
            warmedOrigins.clear()
        }
        callbackExecutor.shutdown()
        timeoutExecutor.shutdown()
    }

    private data class EngineLease(val key: String, val engine: HttpEngine)

    private data class EngineAcquisition(
        val future: CompletableFuture<EngineEntry>,
        val leader: Boolean,
    )

    private data class EngineEntry(
        val engine: HttpEngine,
        var inFlight: Int = 0,
        var lastUsed: Long = 0L,
    )

    private companion object {
        const val MAX_ENGINES = 8
    }
}

private fun engineKey(url: String): String {
    val uri = URI(url)
    val host = requireNotNull(uri.host) { "HTTP engine URL has no host" }.lowercase()
    val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
    return engineKey(uri, host, port)
}

private fun engineKey(uri: URI, host: String, port: Int): String =
    "${uri.scheme}:$host:$port"
