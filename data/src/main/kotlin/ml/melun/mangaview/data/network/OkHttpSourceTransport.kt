package ml.melun.mangaview.data.network

import java.io.IOException
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpSourceTransport(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : SourceTransport {
    override fun retireIdleConnections() = client.connectionPool.evictAll()

    override suspend fun execute(request: SourceRequest): SourceResponse = execute(client, request)

    override suspend fun executeOnFreshRoute(request: SourceRequest): SourceResponse {
        val fresh = client.newBuilder()
            .connectionPool(ConnectionPool(1, 1L, TimeUnit.MINUTES))
            .build()
        return execute(fresh, request)
    }

    private suspend fun execute(routeClient: OkHttpClient, request: SourceRequest): SourceResponse {
        val call = routeClient.newCall(request.toOkHttpRequest())
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
            source.read(destination, offset, byteCount)
        }

    override fun close() = response.close()
}
