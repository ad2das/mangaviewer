package ml.melun.mangaview.source.ntk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.PreparationIntent

/** Main-process client for the provider browser hosted by [NtkBrowserService]. */
class NtkWebViewAccessGateway(
    context: Context,
    private val userAgent: String,
    private val serviceClass: Class<out android.app.Service> = NtkBrowserService::class.java,
) : NtkAccessGateway {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val requestIds = AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))
    private val parser = NtkBrowserManifestParser()
    private val callbackThread = HandlerThread("ntk-browser-ipc").apply { start() }
    private val callback = Messenger(IncomingHandler(callbackThread.looper))
    private var remote: Messenger? = null
    private var bound = false
    private var binding = false
    private var warmRequested = false
    private var warmSent = false
    private var active: BrowserRequest? = null

    /** Starts the isolated browser runtime without navigating or touching the caller UI thread. */
    fun warm() {
        if (closed.get()) return
        synchronized(lock) {
            warmRequested = true
            ensureBoundLocked()
            sendWarmLocked()
        }
    }

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) {
        request(origin, episodePath, intent)
    }

    override suspend fun awaitAuthorization(origin: String, episodePath: String): Boolean {
        val key = runCatching { validatedKey(origin, episodePath) }.getOrNull() ?: return false
        val pending = synchronized(lock) { active?.takeIf { it.key == key } } ?: return false
        return pending.awaitAuthorization()
    }

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        val pending = request(document.origin, document.path, PreparationIntent.INITIAL_VIEW)
        return try {
            val payload = awaitPayload(pending)
            val pages = parser.parse(payload, document, descriptor)
            descriptor.expectedPageCount?.let { expected ->
                require(pages.size == expected) {
                    "NTK browser manifest has ${pages.size} pages; expected $expected"
                }
            }
            synchronized(lock) { if (active === pending) pending.consumed = true }
            pages
        } catch (cancelled: CancellationException) {
            abandon(pending)
            throw cancelled
        } catch (failure: Throwable) {
            abandon(pending)
            throw failure
        }
    }

    private suspend fun awaitPayload(pending: BrowserRequest): String {
        val first = withTimeoutOrNull(DELIVERY_RETRY_MILLIS) { pending.result.await() }
        if (first != null) return first
        synchronized(lock) {
            if (active === pending && !pending.result.isCompleted) {
                pending.sent = false
                pending.descriptorSent = false
                sendRequestLocked(pending)
                sendDescriptorLocked(pending)
                Log.w(TAG, "manifest delivery retry id=${pending.requestId} ageMs=${pending.ageMillis()}")
            }
        }
        return withTimeout(RESOLVE_TIMEOUT_MILLIS - DELIVERY_RETRY_MILLIS) {
            pending.result.await()
        }
    }

    override suspend fun documentAvailable(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) {
        val pending = request(document.origin, document.path, PreparationIntent.INITIAL_VIEW)
        val responseCookies = document.responseHeaders.entries
            .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
            .flatMap(Map.Entry<String, List<String>>::value)
            .filter(String::isNotBlank)
        require(responseCookies.size <= MAX_RESPONSE_COOKIES) {
            "NTK response supplied too many cookies"
        }
        require(responseCookies.all { it.length <= MAX_COOKIE_LENGTH }) {
            "NTK response supplied an oversized cookie"
        }
        val delivered = BrowserDescriptor(
            workId = descriptor.workId,
            episodeId = descriptor.episodeId,
            token = descriptor.token,
            apiPath = descriptor.apiPath,
            expectedPageCount = descriptor.expectedPageCount,
            responseCookies = responseCookies,
        )
        synchronized(lock) {
            if (active !== pending) return
            pending.descriptor = delivered
            pending.descriptorSent = false
            sendDescriptorLocked(pending)
        }
    }

    override fun pageAccessEstablished(origin: String, episodePath: String) {
        val key = runCatching { validatedKey(origin, episodePath) }.getOrNull() ?: return
        synchronized(lock) {
            val pending = active?.takeIf { it.key == key && !it.quiesced } ?: return
            pending.quiesced = true
            sendControlLocked(NtkBrowserProtocol.MSG_QUIESCE, pending.requestId)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            active?.let { pending ->
                sendControlLocked(NtkBrowserProtocol.MSG_CANCEL, pending.requestId)
                pending.result.cancel()
                pending.authorization.cancel()
            }
            active = null
            remote = null
            warmSent = false
            safeUnbindLocked()
        }
        callbackThread.quitSafely()
    }

    private fun request(
        origin: String,
        path: String,
        intent: PreparationIntent,
    ): BrowserRequest {
        check(!closed.get()) { "NTK gateway is closed" }
        val key = validatedKey(origin, path)
        return synchronized(lock) {
            active?.takeIf { it.key == key && !it.consumed }?.let { return@synchronized it }
            active?.let { previous ->
                sendControlLocked(NtkBrowserProtocol.MSG_CANCEL, previous.requestId)
                previous.result.completeExceptionally(IOException("NTK browser request was superseded"))
                previous.authorization.completeExceptionally(
                    IOException("NTK browser request was superseded"),
                )
            }
            BrowserRequest(
                requestId = nextRequestId(),
                key = key,
                origin = origin,
                path = path,
                intent = intent,
                result = CompletableDeferred(),
                authorization = CompletableDeferred(),
                startedAtMillis = SystemClock.elapsedRealtime(),
            ).also { pending ->
                active = pending
                ensureBoundLocked()
                sendRequestLocked(pending)
            }
        }
    }

    private fun ensureBoundLocked() {
        if (remote != null || binding || closed.get()) return
        binding = true
        val intent = Intent(appContext, serviceClass)
        val accepted = runCatching {
            appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrElse {
            binding = false
            failActiveLocked(IOException("NTK browser service bind failed", it))
            return
        }
        if (!accepted) {
            binding = false
            failActiveLocked(IOException("NTK browser service rejected the bind"))
        } else {
            bound = true
        }
    }

    private fun sendRequestLocked(pending: BrowserRequest) {
        val target = remote ?: return
        if (pending.sent) return
        val request = Message.obtain(null, NtkBrowserProtocol.MSG_RESOLVE).apply {
            replyTo = this@NtkWebViewAccessGateway.callback
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, pending.requestId)
                putString(NtkBrowserProtocol.KEY_ORIGIN, pending.origin)
                putString(NtkBrowserProtocol.KEY_PATH, pending.path)
                putString(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
                putString(NtkBrowserProtocol.KEY_PREPARATION_INTENT, pending.intent.name)
            }
        }
        try {
            target.send(request)
            pending.sent = true
        } catch (failure: RemoteException) {
            pending.sent = false
            remote = null
            reconnectLocked(failure)
        }
    }

    private fun sendWarmLocked() {
        val target = remote ?: return
        if (!warmRequested || warmSent) return
        val request = Message.obtain(null, NtkBrowserProtocol.MSG_WARM).apply {
            data = Bundle().apply {
                putString(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
            }
        }
        try {
            target.send(request)
            warmSent = true
        } catch (failure: RemoteException) {
            warmSent = false
            remote = null
            reconnectLocked(failure)
        }
    }

    private fun sendDescriptorLocked(pending: BrowserRequest) {
        val target = remote ?: return
        val descriptor = pending.descriptor ?: return
        if (!pending.sent || pending.descriptorSent) return
        val request = Message.obtain(null, NtkBrowserProtocol.MSG_DESCRIPTOR).apply {
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, pending.requestId)
                putString(NtkBrowserProtocol.KEY_WORK_ID, descriptor.workId)
                putString(NtkBrowserProtocol.KEY_EPISODE_ID, descriptor.episodeId)
                putString(NtkBrowserProtocol.KEY_TOKEN, descriptor.token)
                putString(NtkBrowserProtocol.KEY_API_PATH, descriptor.apiPath)
                putInt(
                    NtkBrowserProtocol.KEY_EXPECTED_COUNT,
                    descriptor.expectedPageCount ?: UNKNOWN_PAGE_COUNT,
                )
                putStringArrayList(
                    NtkBrowserProtocol.KEY_RESPONSE_COOKIES,
                    ArrayList(descriptor.responseCookies),
                )
            }
        }
        try {
            target.send(request)
            pending.descriptorSent = true
        } catch (failure: RemoteException) {
            pending.descriptorSent = false
            remote = null
            reconnectLocked(failure)
        }
    }

    private fun sendControlLocked(what: Int, requestId: Long) {
        val target = remote ?: return
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply { putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId) }
        }
        runCatching { target.send(message) }.onFailure {
            remote = null
            active?.sent = false
            reconnectLocked(it)
        }
    }

    private fun reconnectLocked(cause: Throwable?) {
        if (closed.get()) return
        Log.w(TAG, "remote browser disconnected; rebinding", cause)
        safeUnbindLocked()
        warmSent = false
        ensureBoundLocked()
    }

    private fun safeUnbindLocked() {
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        binding = false
    }

    private fun abandon(pending: BrowserRequest) {
        synchronized(lock) {
            if (active !== pending) return
            sendControlLocked(NtkBrowserProtocol.MSG_CANCEL, pending.requestId)
            active = null
            pending.result.cancel()
            pending.authorization.cancel()
        }
    }

    private fun accept(message: Message) {
        val data = message.data ?: return
        val requestId = data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        synchronized(lock) {
            val pending = active?.takeIf { it.requestId == requestId } ?: return
            when (message.what) {
                NtkBrowserProtocol.MSG_PAYLOAD -> acceptPayloadLocked(pending, data)
                NtkBrowserProtocol.MSG_ACK_READY -> pending.authorization.complete(Unit)
                NtkBrowserProtocol.MSG_ERROR -> {
                    val detail = data.getString(NtkBrowserProtocol.KEY_ERROR).orEmpty()
                    failActiveLocked(IOException(detail.ifBlank { "NTK browser request failed" }))
                }
            }
        }
    }

    private fun acceptPayloadLocked(pending: BrowserRequest, data: Bundle) {
        val payload = data.getString(NtkBrowserProtocol.KEY_PAYLOAD)
        if (payload.isNullOrBlank()) {
            failActiveLocked(IOException("NTK browser returned an empty manifest"))
        } else {
            pending.authorization.complete(Unit)
            pending.result.complete(payload)
        }
    }

    private fun failActiveLocked(failure: Throwable) {
        active?.result?.completeExceptionally(failure)
        active?.authorization?.completeExceptionally(failure)
        active = null
    }

    private fun nextRequestId(): Long = requestIds.updateAndGet { current ->
        if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                if (closed.get()) return
                binding = false
                remote = Messenger(service)
                sendWarmLocked()
                active?.apply { sent = false }?.let(::sendRequestLocked)
                active?.apply { descriptorSent = false }?.let(::sendDescriptorLocked)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                remote = null
                warmSent = false
                active?.sent = false
                reconnectLocked(null)
            }
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                remote = null
                warmSent = false
                active?.sent = false
                reconnectLocked(IOException("NTK browser service binding died"))
            }
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                remote = null
                safeUnbindLocked()
                failActiveLocked(IOException("NTK browser service returned no binder"))
            }
        }
    }

    private inner class IncomingHandler(looper: android.os.Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (message.what == NtkBrowserProtocol.MSG_PAYLOAD ||
                message.what == NtkBrowserProtocol.MSG_ERROR ||
                message.what == NtkBrowserProtocol.MSG_ACK_READY
            ) {
                accept(message)
            } else {
                super.handleMessage(message)
            }
        }
    }

    private companion object {
        const val TAG = "NtkGateway"
        const val RESOLVE_TIMEOUT_MILLIS = 25_000L
        const val DELIVERY_RETRY_MILLIS = 6_000L
        const val INVALID_REQUEST_ID = -1L
        const val UNKNOWN_PAGE_COUNT = -1
        const val MAX_RESPONSE_COOKIES = 24
        const val MAX_COOKIE_LENGTH = 4_096
    }
}

private data class BrowserRequest(
    val requestId: Long,
    val key: String,
    val origin: String,
    val path: String,
    val intent: PreparationIntent,
    val result: CompletableDeferred<String>,
    val authorization: CompletableDeferred<Unit>,
    val startedAtMillis: Long,
    var sent: Boolean = false,
    var consumed: Boolean = false,
    var quiesced: Boolean = false,
    var descriptor: BrowserDescriptor? = null,
    var descriptorSent: Boolean = false,
) {
    fun ageMillis(): Long = SystemClock.elapsedRealtime() - startedAtMillis

    suspend fun awaitAuthorization(): Boolean {
        if (authorization.isCompleted) return runCatching { authorization.await() }.isSuccess
        val remaining = ACK_READY_DEADLINE_MILLIS - ageMillis()
        if (remaining <= 0L) return false
        val ready = runCatching {
            withTimeoutOrNull(remaining) { authorization.await(); true } ?: false
        }.getOrDefault(false)
        Log.d("NtkGateway", "authorization ready=$ready ageMs=${ageMillis()}")
        return ready
    }
}

private data class BrowserDescriptor(
    val workId: String,
    val episodeId: String,
    val token: String,
    val apiPath: String,
    val expectedPageCount: Int?,
    val responseCookies: List<String>,
)

private const val ACK_READY_DEADLINE_MILLIS = 15_000L
