package ml.melun.mangaview.data.network

import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import ml.melun.mangaview.source.SourceResponse

@RequiresApi(34)
internal class HttpEngineExchange(
    private val continuation: CancellableContinuation<SourceResponse>,
    private val finished: (HttpEngineExchange) -> Unit,
    private val registerBody: (HttpEngineBodyPageStream) -> Boolean,
    private val bodyFinished: (HttpEngineBodyPageStream) -> Unit,
    private val callbackExecutor: Executor,
    private val bodyReadScheduler: HttpEngineBodyReadScheduler,
    private val initialPriority: ml.melun.mangaview.source.PageFetchPriority,
) : UrlRequest.Callback {
    private val lifecycleLock = Any()
    private val request = AtomicReference<UrlRequest?>()
    private val engineKey = AtomicReference<String?>()
    private val startLock = Any()
    private var started = false
    private var complete = false
    private var responseDelivered = false
    private var body: HttpEngineBodyPageStream? = null
    private var timeout: ScheduledFuture<*>? = null

    fun attach(value: UrlRequest) {
        check(request.compareAndSet(null, value)) { "HTTP engine request was already attached" }
    }

    fun attachEngine(value: String) {
        check(engineKey.compareAndSet(null, value)) { "HTTP engine was already attached" }
    }

    fun engineKey(): String? = engineKey.get()

    fun armTimeout(value: ScheduledFuture<*>) {
        val cancel = synchronized(lifecycleLock) {
            check(timeout == null) { "HTTP engine timeout was already armed" }
            if (complete) true else false.also { timeout = value }
        }
        if (cancel) value.cancel(false)
    }

    fun cancel() = abort(IOException("HTTP engine request was canceled"))

    fun cancelForClose() = abort(IOException("HTTP engine transport was closed"))

    fun timeout() = abort(IOException("HTTP engine request exceeded its total timeout"))

    fun start(isUnavailable: () -> Boolean) {
        val failure = synchronized(startLock) {
            when {
                synchronized(lifecycleLock) { complete } -> null
                isUnavailable() -> IOException("HTTP engine request was canceled before start")
                else -> runCatching {
                    requireNotNull(request.get()) { "HTTP engine request is unavailable" }.start()
                    started = true
                }.exceptionOrNull()
            }
        }
        if (failure != null) abort(failure)
    }

    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String,
    ) = request.followRedirect()

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        val headers = info.headers.asMap
        val expectedLength = runCatching { HttpEngineResponseHeaders.contentLength(headers) }.getOrElse {
            abort(it)
            return
        }
        val stream = runCatching {
            HttpEngineBodyPageStream(
                expectedLength = expectedLength,
                requestRead = request::read,
                cancelExchange = ::abort,
                finished = bodyFinished,
                dispatchRead = { action -> callbackExecutor.execute(Runnable(action)) },
                initialPriority = initialPriority,
                readScheduler = bodyReadScheduler,
            )
        }.getOrElse {
            abort(it)
            return
        }
        if (!registerBody(stream)) {
            stream.close()
            return
        }
        val accepted = synchronized(lifecycleLock) {
            if (complete) false else true.also {
                body = stream
                responseDelivered = true
            }
        }
        if (!accepted) {
            stream.close()
            return
        }
        val response = SourceResponse(
            statusCode = info.httpStatusCode,
            finalUrl = info.url,
            headers = headers,
            body = stream,
            contentLength = expectedLength,
            contentType = HttpEngineResponseHeaders.value(headers, "Content-Type"),
        )
        continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        val stream = synchronized(lifecycleLock) { body }
        if (stream == null) {
            abort(IOException("HTTP engine delivered body bytes before response headers"))
        } else {
            stream.onReadCompleted(byteBuffer)
        }
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        val completion = takeCompletion() ?: return
        completion.timeout?.cancel(false)
        val stream = completion.body
        if (stream == null) {
            resumeHeaderFailure(IOException("HTTP engine succeeded without a response body"), completion)
        } else {
            stream.completeSuccess()
        }
        finished(this)
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) =
        completeFailure(error, "engine_failure_callback")

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) =
        completeFailure(IOException("HTTP engine request was canceled"), "engine_cancel_callback")

    fun abort(failure: Throwable) {
        val activeRequest = synchronized(startLock) { request.get()?.takeIf { started } }
        activeRequest?.cancel()
        completeFailure(failure)
    }

    private fun completeFailure(failure: Throwable, origin: String = "local_abort") {
        val completion = takeCompletion() ?: return
        // Log only the winning terminal event, not a callback arriving after local cancellation.
        runCatching {
            Log.d("SourceHttpEngine", "terminal=$origin headers=${completion.responseDelivered} " +
                "error=${failure.javaClass.simpleName} reason=${failure.message}")
        }
        completion.timeout?.cancel(false)
        completion.body?.fail(failure)
        resumeHeaderFailure(failure, completion)
        finished(this)
    }

    private fun resumeHeaderFailure(failure: Throwable, completion: Completion) {
        if (!completion.responseDelivered) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

    private fun takeCompletion(): Completion? = synchronized(lifecycleLock) {
        if (complete) return null
        complete = true
        Completion(responseDelivered, body, timeout)
    }

    private data class Completion(
        val responseDelivered: Boolean,
        val body: HttpEngineBodyPageStream?,
        val timeout: ScheduledFuture<*>?,
    )
}

@RequiresApi(34)
internal class HttpEngineUploadProvider(private val bytes: ByteArray) : UploadDataProvider() {
    private var offset = 0

    override fun getLength(): Long = bytes.size.toLong()

    override fun read(sink: UploadDataSink, destination: ByteBuffer) {
        val count = minOf(destination.remaining(), bytes.size - offset)
        if (count > 0) destination.put(bytes, offset, count)
        offset += count
        // getLength() declares a fixed-length upload. Cronet rejects `finalChunk=true` for this
        // mode; the engine detects completion from the declared byte count.
        sink.onReadSucceeded(false)
    }

    override fun rewind(sink: UploadDataSink) {
        offset = 0
        sink.onRewindSucceeded()
    }
}
