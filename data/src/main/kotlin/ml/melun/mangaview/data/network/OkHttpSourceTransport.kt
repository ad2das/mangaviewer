package ml.melun.mangaview.data.network

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

class OkHttpSourceTransport(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    routeDns: ((Int) -> Dns)? = null,
) : SourceTransport, Closeable {
    private val recoveryDispatcher = Dispatcher().apply {
        maxRequests = 6
        maxRequestsPerHost = 6
    }
    private val controlDispatcher = Dispatcher().apply {
        maxRequests = 3
        maxRequestsPerHost = 3
    }
    private val routeClients = routeClients(client, client.dispatcher, routeDns)
    private val recoveryClients = routeClients(client, recoveryDispatcher, routeDns)
    private val controlClients = routeClients(client, controlDispatcher, routeDns)
    private val fastestRoutes = ConcurrentHashMap<String, RouteObservation>()

    override fun retireIdleConnections() {
        routeClients.forEach { it.connectionPool.evictAll() }
        recoveryClients.forEach { it.connectionPool.evictAll() }
        controlClients.forEach { it.connectionPool.evictAll() }
        fastestRoutes.clear()
    }

    override fun close() {
        retireIdleConnections()
        client.dispatcher.executorService.shutdown()
        recoveryDispatcher.executorService.shutdown()
        controlDispatcher.executorService.shutdown()
    }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val index = primaryRouteIndex(request)
        val clients = if (request.isControlRequest()) controlClients else routeClients
        return execute(clients[index], index, request)
    }

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse {
        val index = recoveryRouteIndex(request)
        val clients = if (request.isControlRequest()) controlClients else recoveryClients
        return execute(clients[index], index, request)
    }

    override suspend fun executeOnAlternateRoute(request: SourceRequest): SourceResponse {
        val index = alternateRouteIndex(request)
        val clients = if (request.isControlRequest()) controlClients else recoveryClients
        return execute(clients[index], index, request)
    }

    override fun routeParallelism(): Int = routeClients.size

    private suspend fun execute(
        routeClient: OkHttpClient,
        routeIndex: Int,
        request: SourceRequest,
    ): SourceResponse {
        val okhttpRequest = request.toOkHttpRequest()
        val host = okhttpRequest.url.host
        val startedAtNanos = System.nanoTime()
        val call = routeClient.newCall(okhttpRequest)
        call.timeout().timeout(request.totalTimeoutMillis, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    observeRoute(host, routeIndex, System.nanoTime() - startedAtNanos)
                    runCatching { response.toSourceResponse() }
                        .onSuccess { opened ->
                            continuation.resume(opened) { _, cancelledResponse, _ ->
                                cancelledResponse.close()
                            }
                        }
                        .onFailure {
                            response.close()
                            continuation.resumeWithException(it)
                        }
                }
            })
        }
    }

    private fun primaryRouteIndex(request: SourceRequest): Int {
        val host = request.host()
        fastestRoutes[host]?.let { return it.index.coerceIn(routeClients.indices) }
        return initialRouteIndex(request, routeClients.size)
    }

    private fun recoveryRouteIndex(request: SourceRequest): Int {
        val primary = fastestRoutes[request.host()]?.index
            ?: initialRouteIndex(request, recoveryClients.size)
        return Math.floorMod(primary + 1, recoveryClients.size)
    }

    private fun alternateRouteIndex(request: SourceRequest): Int {
        val primary = fastestRoutes[request.host()]?.index
            ?: initialRouteIndex(request, recoveryClients.size)
        return Math.floorMod(primary + 2, recoveryClients.size)
    }

    private fun SourceRequest.host(): String = url.toHttpUrl().host

    private fun SourceRequest.isControlRequest(): Boolean =
        priority == ml.melun.mangaview.source.PageFetchPriority.NORMAL

    private fun observeRoute(host: String, index: Int, elapsedNanos: Long) {
        fastestRoutes.compute(host) { _, current ->
            if (current == null || elapsedNanos < current.elapsedNanos) {
                RouteObservation(index, elapsedNanos)
            } else {
                current
            }
        }
    }

    private data class RouteObservation(val index: Int, val elapsedNanos: Long)

    private fun SourceRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        return when (method) {
            SourceHttpMethod.GET -> builder.get().build()
            SourceHttpMethod.HEAD -> builder.head().build()
            SourceHttpMethod.POST -> {
                val requestBody = (body ?: ByteArray(0)).toRequestBody(bodyMediaType?.toMediaTypeOrNull())
                builder.post(requestBody).build()
            }
        }
    }

    private fun Response.toSourceResponse(): SourceResponse {
        val responseBody = requireNotNull(body) { "HTTP response has no body" }
        return SourceResponse(
            statusCode = code,
            finalUrl = request.url.toString(),
            headers = headers.toMultimap(),
            body = OkHttpPageByteStream(this, responseBody.source(), ioDispatcher),
            contentLength = responseBody.contentLength().takeIf { it >= 0L },
            contentType = responseBody.contentType()?.toString(),
        )
    }
}

internal fun initialRouteIndex(request: SourceRequest, routeCount: Int): Int {
    require(routeCount > 0)
    return Math.floorMod(request.url.hashCode(), routeCount)
}

private class OkHttpPageByteStream(
    private val response: Response,
    private val source: okio.BufferedSource,
    private val ioDispatcher: CoroutineDispatcher,
) : PageByteStream {
    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int =
        withContext(ioDispatcher) {
            require(offset >= 0 && byteCount >= 0 && offset + byteCount <= destination.size) {
                "Destination range is invalid"
            }
            if (byteCount == 0) return@withContext 0
            val count = source.read(destination, offset, byteCount)
            require(count != 0) { "OkHttp response returned zero bytes" }
            count
        }

    override fun close() = response.close()
}

private fun routeClients(
    base: OkHttpClient,
    dispatcher: Dispatcher,
    routeDns: ((Int) -> Dns)?,
): List<OkHttpClient> {
    val count = if (routeDns == null) 1 else ROUTE_POOL_COUNT
    return List(count) { index ->
        if (index == 0 && dispatcher === base.dispatcher) {
            base
        } else {
            base.newBuilder()
                .dispatcher(dispatcher)
                .dns(routeDns?.invoke(index) ?: base.dns)
                .connectionPool(ConnectionPool(6, 1L, TimeUnit.MINUTES))
                .build()
        }
    }
}

private const val ROUTE_POOL_COUNT = 3
