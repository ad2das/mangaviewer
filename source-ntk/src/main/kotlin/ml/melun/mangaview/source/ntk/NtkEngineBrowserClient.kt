package ml.melun.mangaview.source.ntk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.source.PreparationIntent

/** Immutable proof returned only after both provider messages and the service retirement reply. */
class NtkEngineAuthorization internal constructor(
    val payload: String,
    val episodeId: EpisodeId,
    val documentSha256: String,
    val documentReplaySha256: String,
    val authEpoch: Long,
    val requestId: Long,
    val ackObservedNanos: Long,
    val manifestObservedNanos: Long,
    val documentRetiredNanos: Long,
) {
    init {
        require(requestId > 0 && ackObservedNanos > 0 && manifestObservedNanos > 0)
        require(documentRetiredNanos >= maxOf(ackObservedNanos, manifestObservedNanos))
    }
    override fun toString() = "NtkEngineAuthorization(requestId=$requestId)"
}

/** Execution is admitted by the caller's global BROWSER domain; this client owns no scheduler. */
class NtkEngineBrowserClient(
    context: Context,
    private val userAgent: String,
    private val identity: NtkBrowserIdentity,
    private val captureEvidence: () -> Boolean = { false },
    private val observeAuthorization: (NtkEngineAuthorization) -> Unit = {},
) {
    private val app = context.applicationContext

    suspend fun capture(document: NtkAccessDocument): NtkEngineAuthorization = withContext(Dispatchers.Main.immediate) {
        requireNotNull(document.descriptor) { "Browser authorization requires a protected document" }
        val payload = NtkBrowserDocumentPayload.create(app.cacheDir, document.browserDocument)
        val exchange = EngineBrowserExchange(app, nextId.incrementAndGet())
        var result: EngineBrowserProof? = null
        var failure: Throwable? = null
        try {
            exchange.bind()
            exchange.resolve(document, userAgent, identity, captureEvidence())
            exchange.ready.await()
            exchange.descriptor(document, payload)
            val ack = exchange.ack.await()
            val manifest = exchange.manifest.await()
            result = EngineBrowserProof(manifest.first, ack, manifest.second)
        } catch (caught: Throwable) { failure = caught }
        withContext(NonCancellable) {
            try { exchange.retire() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure?.addSuppressed(cleanup)
            }
            try { payload.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure?.addSuppressed(cleanup)
            }
            try { exchange.unbind() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure?.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
        val proof = requireNotNull(result)
        NtkEngineAuthorization(proof.payload, document.episodeId, document.sourceDocument.sha256,
            document.sourceDocument.replaySha256, document.authEpoch, exchange.requestId, proof.ackNanos, proof.manifestNanos,
            exchange.retired.await()).also(observeAuthorization)
    }

    private companion object { val nextId = AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)) }
}

private data class EngineBrowserProof(val payload: String, val ackNanos: Long, val manifestNanos: Long)

private class EngineBrowserExchange(private val context: Context, val requestId: Long) : ServiceConnection {
    private val connected = CompletableDeferred<Messenger>()
    val ready = CompletableDeferred<Unit>()
    val ack = CompletableDeferred<Long>()
    val manifest = CompletableDeferred<Pair<String, Long>>()
    val retired = CompletableDeferred<Long>()
    private var remote: Messenger? = null
    private var bound = false
    private var submitted = false
    private var retiring = false
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID) != requestId) return
            val now = SystemClock.elapsedRealtimeNanos()
            when (message.what) {
                NtkBrowserProtocol.MSG_DOCUMENT_REQUEST_READY -> ready.complete(Unit)
                NtkBrowserProtocol.MSG_ACK_READY -> ack.complete(now)
                NtkBrowserProtocol.MSG_PAYLOAD -> {
                    val payload = message.data.getString(NtkBrowserProtocol.KEY_PAYLOAD)
                    if (payload == null) fail(IllegalStateException("NTK manifest payload is missing"))
                    else manifest.complete(payload to now)
                }
                NtkBrowserProtocol.MSG_DOCUMENT_RETIRED -> if (retiring) retired.complete(now)
                NtkBrowserProtocol.MSG_ERROR -> fail(IllegalStateException(
                    message.data.getString(NtkBrowserProtocol.KEY_ERROR) ?: "NTK engine browser failed"))
            }
        }
    })

    fun bind() {
        bound = context.bindService(Intent(context, NtkEngineBrowserService::class.java), this, Context.BIND_AUTO_CREATE)
        check(bound) { "NTK engine browser binding was rejected" }
    }

    suspend fun resolve(document: NtkAccessDocument, userAgent: String, identity: NtkBrowserIdentity, captureEvidence: Boolean) {
        val service = connected.await()
        remote = service
        submitted = true
        service.send(Message.obtain(null, NtkBrowserProtocol.MSG_RESOLVE).apply {
            replyTo = replies
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId)
                putString(NtkBrowserProtocol.KEY_ORIGIN, document.browserDocument.origin)
                putString(NtkBrowserProtocol.KEY_PATH, document.browserDocument.path)
                putString(NtkBrowserProtocol.KEY_USER_AGENT, userAgent)
                putString(NtkBrowserProtocol.KEY_PREPARATION_INTENT, PreparationIntent.INITIAL_VIEW.name)
                putString(NtkBrowserProtocol.KEY_FINGERPRINT, identity.fingerprint)
                putString(NtkBrowserProtocol.KEY_PERSISTENT_ID, identity.persistentId)
                putBoolean(NtkBrowserProtocol.KEY_CAPTURE_EVIDENCE, captureEvidence)
            }
        })
    }

    fun descriptor(document: NtkAccessDocument, payload: NtkBrowserDocumentPayload) {
        val descriptor = requireNotNull(document.descriptor)
        requireNotNull(remote).send(Message.obtain(null, NtkBrowserProtocol.MSG_DESCRIPTOR).apply {
            replyTo = replies
            data = Bundle().apply {
                putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId)
                putString(NtkBrowserProtocol.KEY_WORK_ID, descriptor.workId)
                putString(NtkBrowserProtocol.KEY_EPISODE_ID, descriptor.episodeId)
                putString(NtkBrowserProtocol.KEY_TOKEN, descriptor.token)
                putString(NtkBrowserProtocol.KEY_API_PATH, descriptor.apiPath)
                putInt(NtkBrowserProtocol.KEY_EXPECTED_COUNT, descriptor.expectedPageCount ?: UNKNOWN_PAGE_COUNT)
                payload.writeTo(this)
            }
        })
    }

    suspend fun retire() {
        if (!submitted) return
        retiring = true
        requireNotNull(remote).send(NtkBrowserIpcMessages.control(NtkBrowserProtocol.MSG_RETIRE_DOCUMENT, requestId, replies))
        retired.await()
    }

    fun unbind() {
        if (!bound) return
        bound = false
        context.unbindService(this)
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
    override fun onServiceDisconnected(name: ComponentName) { disconnected("NTK engine browser disconnected") }
    override fun onBindingDied(name: ComponentName) { disconnected("NTK engine browser binding died") }
    override fun onNullBinding(name: ComponentName) { disconnected("NTK engine browser returned no binder") }

    private fun disconnected(detail: String) {
        val failure = IllegalStateException(detail)
        fail(failure)
        retired.completeExceptionally(failure)
    }

    private fun fail(failure: Throwable) {
        connected.completeExceptionally(failure)
        ready.completeExceptionally(failure)
        ack.completeExceptionally(failure)
        manifest.completeExceptionally(failure)
        if (retiring) retired.completeExceptionally(failure)
    }
}
