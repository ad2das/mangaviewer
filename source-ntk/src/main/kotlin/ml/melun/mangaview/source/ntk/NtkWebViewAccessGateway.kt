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
import kotlinx.coroutines.withTimeoutOrNull
import ml.melun.mangaview.source.PreparationIntent

/** Main-process client for the provider browser hosted by [NtkBrowserService]. */
class NtkWebViewAccessGateway(
    context: Context,
    private val userAgent: String,
    private val identity: NtkBrowserIdentity? = null,
    private val serviceClass: Class<out android.app.Service> = NtkBrowserService::class.java,
) : NtkAccessGateway {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val requestIds = AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))
    private val parser = NtkBrowserManifestParser()
    private val callbackThread = HandlerThread("ntk-browser-ipc").apply { start() }
    private val callback = Messenger(NtkGatewayIncomingHandler(callbackThread.looper, ::accept))
    private var remote: Messenger? = null
    private var bound = false
    private var binding = false
    private var warmRequested = false
    private var warmSent = false
    private var warmOrigin: String? = null
    private var active: BrowserRequest? = null

    /** Starts the isolated browser runtime without navigating or touching the caller UI thread. */
    fun warm(origin: String) {
        if (closed.get()) return
        val validatedOrigin = validatedKey(origin, "/").removeSuffix("/")
        synchronized(lock) {
            warmRequested = true
            warmOrigin = validatedOrigin
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

    override fun isAuthorizationReady(origin: String, episodePath: String): Boolean {
        val key = runCatching { validatedKey(origin, episodePath) }.getOrNull() ?: return false
        return synchronized(lock) {
            active?.takeIf { it.key == key }?.authorization?.let { authorization ->
                authorization.isCompleted && !authorization.isCancelled
            } == true
        }
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
        return withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            pending.result.await()
        } ?: throw IOException(
            "NTK browser manifest exceeded ${RESOLVE_TIMEOUT_MILLIS}ms for ${pending.path}",
        )
    }

    override suspend fun documentAvailable(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ) {
        val pending = request(document.origin, document.path, PreparationIntent.INITIAL_VIEW)
        val delivered = browserDescriptor(descriptor)
        val payload = NtkBrowserDocumentPayload.create(appContext.cacheDir, document)
        try {
            synchronized(lock) {
                if (active !== pending || pending.document != null) return
                pending.document = payload
                pending.descriptor = delivered
                pending.descriptorSent = false
                sendDescriptorLocked(pending)
            }
        } finally {
            if (pending.document !== payload) payload.close()
        }
    }

    internal fun ntkTraceContext(origin: String, path: String): NtkTraceContext? {
        val key = runCatching { validatedKey(origin, path) }.getOrNull() ?: return null
        return synchronized(lock) {
            active?.takeIf { it.key == key }?.traceContext()
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

    override fun preflightAdjacentChallenge(
        origin: String,
        episodePath: String,
        adjacentEpisodePath: String,
    ) {
        val key = runCatching { validatedKey(origin, episodePath) }.getOrNull() ?: return
        val adjacentKey = runCatching {
            validatedKey(origin, adjacentEpisodePath)
        }.getOrNull() ?: return
        if (adjacentKey == key) return
        synchronized(lock) {
            val pending = active?.takeIf { it.key == key && it.sent } ?: return
            val target = remote ?: return
            runCatching {
                target.send(NtkBrowserIpcMessages.preflightAdjacent(pending, adjacentEpisodePath))
                pending.adjacentPreflightPaths += adjacentEpisodePath
            }.onFailure {
                remote = null
                reconnectLocked(it)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            active?.let { pending ->
                sendControlLocked(NtkBrowserProtocol.MSG_CANCEL, pending.requestId)
                pending.result.cancel()
                pending.authorization.cancel()
                pending.document?.close()
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
                if (path !in previous.adjacentPreflightPaths) {
                    sendControlLocked(browserSupersessionControl(previous.consumed), previous.requestId)
                }
                previous.result.completeExceptionally(IOException("NTK browser request was superseded"))
                previous.authorization.completeExceptionally(
                    IOException("NTK browser request was superseded"),
                )
                previous.document?.close()
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
                NtkTrace.emit("browser-request-created", pending.traceContext(), role = "main")
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
        val request = NtkBrowserIpcMessages.resolve(pending, callback, userAgent, identity)
        try {
            target.send(request)
            pending.sent = true
            NtkTrace.emit("browser-request-ipc-sent", pending.traceContext(), role = "main")
        } catch (failure: RemoteException) {
            pending.sent = false
            remote = null
            reconnectLocked(failure)
        }
    }

    private fun sendWarmLocked() {
        val target = remote ?: return
        if (!warmRequested || warmSent) return
        val request = NtkBrowserIpcMessages.warm(
            callback,
            userAgent,
            requireNotNull(warmOrigin),
            identity,
        )
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
        val request = NtkBrowserIpcMessages.descriptor(pending, descriptor)
        try {
            target.send(request)
            pending.descriptorSent = true
            NtkTrace.emit(
                "native-document-ipc-sent",
                pending.traceContext(),
                role = "main",
                detail = "descriptor",
            )
        } catch (failure: RemoteException) {
            pending.descriptorSent = false
            remote = null
            reconnectLocked(failure)
        }
    }

    private fun sendControlLocked(what: Int, requestId: Long) {
        val target = remote ?: return
        val message = NtkBrowserIpcMessages.control(what, requestId)
        runCatching { target.send(message) }.onFailure {
            remote = null
            active?.sent = false
            reconnectLocked(it)
        }
    }

    private fun reconnectLocked(cause: Throwable?) {
        if (closed.get()) return
        Log.w(GATEWAY_TAG, "remote browser disconnected; rebinding", cause)
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
            pending.document?.close()
        }
    }

    private fun accept(message: Message) {
        val data = message.data ?: return
        val requestId = data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        synchronized(lock) {
            val pending = active?.takeIf { it.requestId == requestId } ?: return
            when (message.what) {
                NtkBrowserProtocol.MSG_PAYLOAD -> acceptPayloadLocked(pending, data)
                NtkBrowserProtocol.MSG_ACK_READY -> {
                    NtkTrace.emit("browser-ack-ready-received", pending.traceContext(), role = "main")
                    pending.authorization.complete(Unit)
                }
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
            NtkTrace.emit(
                "browser-manifest-payload-received",
                pending.traceContext(),
                role = "main",
                outcome = "accepted",
            )
            pending.authorization.complete(Unit)
            pending.result.complete(payload)
        }
    }

    private fun failActiveLocked(failure: Throwable) {
        active?.result?.completeExceptionally(failure)
        active?.authorization?.completeExceptionally(failure)
        active?.document?.close()
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
                val pending = active
                sendWarmLocked()
                if (pending != null) {
                    pending.sent = false
                    sendRequestLocked(pending)
                }
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

}

private class NtkGatewayIncomingHandler(
    looper: android.os.Looper,
    private val accept: (Message) -> Unit,
) : Handler(looper) {
    override fun handleMessage(message: Message) {
        when (message.what) {
            NtkBrowserProtocol.MSG_PAYLOAD,
            NtkBrowserProtocol.MSG_ERROR,
            NtkBrowserProtocol.MSG_ACK_READY,
            -> accept(message)
            NtkBrowserProtocol.MSG_WARM_PHASE -> Log.d(
                GATEWAY_TAG,
                "warm phase=${message.data.getString(NtkBrowserProtocol.KEY_PHASE).orEmpty()} " +
                    "status=${message.data.getInt(NtkBrowserProtocol.KEY_STATUS)} " +
                    "ageMs=${message.data.getLong(NtkBrowserProtocol.KEY_AGE_MILLIS)}",
            )
            else -> super.handleMessage(message)
        }
    }
}

private fun browserDescriptor(descriptor: NtkViewerDescriptor): BrowserDescriptor =
    BrowserDescriptor(
        workId = descriptor.workId,
        episodeId = descriptor.episodeId,
        token = descriptor.token,
        apiPath = descriptor.apiPath,
        expectedPageCount = descriptor.expectedPageCount,
    )

internal data class BrowserRequest(
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
    var document: NtkBrowserDocumentPayload? = null,
    val adjacentPreflightPaths: MutableSet<String> = linkedSetOf(),
) {
    fun ageMillis(): Long = SystemClock.elapsedRealtime() - startedAtMillis

    fun traceContext(): NtkTraceContext = NtkTraceContext(
        requestId = requestId,
        sourceEpisodeId = path,
        episodePath = path,
        providerEpisodeId = descriptor?.episodeId ?: "unknown",
    )

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

internal data class BrowserDescriptor(
    val workId: String,
    val episodeId: String,
    val token: String,
    val apiPath: String,
    val expectedPageCount: Int?,
)

private const val ACK_READY_DEADLINE_MILLIS = 15_000L
private const val GATEWAY_TAG = "NtkGateway"
private const val RESOLVE_TIMEOUT_MILLIS = 8_000L
