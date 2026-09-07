package ml.melun.mangaview.source.ntk

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.net.URI
import ml.melun.mangaview.source.PreparationIntent

internal data class RemoteRequest(
    val requestId: Long,
    val key: String,
    val userAgent: String,
    val intent: PreparationIntent,
    val fingerprint: String?,
    val persistentId: String?,
    val startedAtMillis: Long,
    val recipients: MutableMap<Long, Messenger>,
    val delivery: NtkManifestDelivery = NtkManifestDelivery(),
    var captureInstalledAtDocumentStart: Boolean = false,
    var authorizationStarted: Boolean = false,
    var authorizationObserved: Boolean = false,
    @Volatile var descriptor: RemoteDescriptor? = null,
    @Volatile var document: NtkBrowserDocumentPayload? = null,
    var documentCookiesApplied: Boolean = false,
    var identityCookiesApplied: Boolean = false,
    var documentEpoch: Long = 0L,
    var descriptorDelivered: Boolean = false,
    var rendererRestarts: Int = 0,
    var deliveryRedrives: Int = 0,
    var ackReadyReported: Boolean = false,
    var manifestDescriptorInstalled: Boolean = false,
    var challengePreflightStarted: Boolean = false,
    var challengePreflightResolved: Boolean = false,
    var challengePayload: String? = null,
    var challengeReceivedAtMillis: Long = 0L,
    val adjacentChallenges: MutableMap<String, NtkAdjacentChallengeFlight> = linkedMapOf(),
    val inheritedChallengeRequestIds: MutableSet<Long> = linkedSetOf(),
    var documentNavigationStarted: Boolean = false,
    var browserDocumentStarted: Boolean = false,
    var ackState: NtkAckPreparationState = NtkAckPreparationState.COLD,
    val captureEvidence: Boolean = false,
) {
    val primaryRecipient: Messenger
        get() = requireNotNull(recipients[requestId])

    val origin: String
        get() = URI(key).let { uri -> "${uri.scheme}://${uri.authority}" }

    val path: String
        get() = requireNotNull(URI(key).path)

    fun add(id: Long, recipient: Messenger) {
        recipients[id] = recipient
    }

    fun remove(id: Long) {
        recipients.remove(id)
    }

    fun contains(id: Long): Boolean = recipients.containsKey(id)

    fun isEmpty(): Boolean = recipients.isEmpty()

    fun replyPayload(value: String) {
        recipients.forEach { (id, recipient) -> sendPayload(id, recipient, value) }
    }

    fun replyError(detail: String) {
        recipients.forEach { (id, recipient) -> sendError(id, recipient, detail) }
    }

    fun replyAckReady() {
        if (ackReadyReported) return
        ackReadyReported = true
        recipients.forEach { (id, recipient) -> sendAckReady(id, recipient) }
    }

    fun advanceAckState(next: NtkAckPreparationState) {
        if (next.ordinal > ackState.ordinal) ackState = next
    }

    fun ageMillis(): Long = SystemClock.elapsedRealtime() - startedAtMillis

    companion object {
        fun from(message: Message): RemoteRequest {
            val recipient = requireNotNull(message.replyTo) { "NTK browser reply channel is missing" }
            val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
            require(requestId > 0L) { "NTK browser request id is invalid" }
            val origin = message.data.requiredString(NtkBrowserProtocol.KEY_ORIGIN)
            val path = message.data.requiredString(NtkBrowserProtocol.KEY_PATH)
            val userAgent = message.data.requiredString(NtkBrowserProtocol.KEY_USER_AGENT)
            val intent = PreparationIntent.valueOf(
                message.data.requiredString(NtkBrowserProtocol.KEY_PREPARATION_INTENT),
            )
            val fingerprint = message.data.getString(NtkBrowserProtocol.KEY_FINGERPRINT)
            val persistentId = message.data.getString(NtkBrowserProtocol.KEY_PERSISTENT_ID)
            require((fingerprint == null) == (persistentId == null)) {
                "NTK browser identity is incomplete"
            }
            require(fingerprint == null || HEX_BROWSER_ID.matches(fingerprint)) {
                "NTK browser fingerprint is invalid"
            }
            require(persistentId == null || HEX_BROWSER_ID.matches(persistentId)) {
                "NTK browser persistent id is invalid"
            }
            require(userAgent.length <= MAX_USER_AGENT_LENGTH) { "NTK user agent is too long" }
            return RemoteRequest(
                requestId = requestId,
                key = validatedKey(origin, path),
                userAgent = userAgent,
                intent = intent,
                fingerprint = fingerprint,
                persistentId = persistentId,
                startedAtMillis = SystemClock.elapsedRealtime(),
                captureEvidence = message.data.getBoolean(NtkBrowserProtocol.KEY_CAPTURE_EVIDENCE, false),
                recipients = linkedMapOf(requestId to recipient),
            ).also { request ->
                NtkTrace.emit(
                    "browser-request-admitted",
                    NtkTraceContext(
                        requestId = request.requestId,
                        sourceEpisodeId = request.path,
                        episodePath = request.path,
                        documentEpoch = request.documentEpoch,
                    ),
                    role = "browser",
                )
            }
        }
    }
}

internal data class NtkAdjacentChallengeFlight(
    var started: Boolean = false,
    var resolved: Boolean = false,
)

internal data class RemoteDescriptor(
    val workId: String,
    val episodeId: String,
    val token: String,
    val apiPath: String,
    val expectedPageCount: Int?,
) {
    companion object {
        fun from(data: Bundle): RemoteDescriptor {
            val expected = data.getInt(NtkBrowserProtocol.KEY_EXPECTED_COUNT, UNKNOWN_PAGE_COUNT)
            val apiPath = data.requiredString(NtkBrowserProtocol.KEY_API_PATH)
            require(apiPath in IMAGE_API_PATHS) { "NTK descriptor API path is invalid" }
            return RemoteDescriptor(
                workId = data.requiredString(NtkBrowserProtocol.KEY_WORK_ID),
                episodeId = data.requiredString(NtkBrowserProtocol.KEY_EPISODE_ID),
                token = data.requiredString(NtkBrowserProtocol.KEY_TOKEN),
                apiPath = apiPath,
                expectedPageCount = expected.takeIf { it > 0 },
            )
        }
    }
}

internal data class CompletedDelivery(
    val requestId: Long,
    val key: String,
    val payload: String,
) {
    fun matches(request: RemoteRequest): Boolean = requestId == request.requestId && key == request.key
}

internal fun validatedKey(origin: String, path: String): String {
    require(path.startsWith('/') && !path.startsWith("//")) {
        "NTK browser path must be absolute"
    }
    val base = URI(origin)
    require(base.scheme in HTTP_SCHEMES) { "NTK browser origin is invalid" }
    require(!base.host.isNullOrBlank()) { "NTK browser origin has no host" }
    require(base.rawUserInfo == null) { "NTK browser origin must not contain credentials" }
    require(base.rawQuery == null && base.rawFragment == null) { "NTK browser origin is invalid" }
    val port = if (base.port < 0) "" else ":${base.port}"
    return "${base.scheme}://${base.host}$port$path"
}

internal fun Bundle.requiredString(key: String): String =
    requireNotNull(getString(key)).also { require(it.isNotBlank()) { "$key is blank" } }

internal fun sendPayload(requestId: Long, recipient: Messenger, payload: String) {
    sendResponse(NtkBrowserProtocol.MSG_PAYLOAD, requestId, recipient) {
        putString(NtkBrowserProtocol.KEY_PAYLOAD, payload)
    }
}

internal fun sendError(requestId: Long, recipient: Messenger, detail: String) {
    sendResponse(NtkBrowserProtocol.MSG_ERROR, requestId, recipient) {
        putString(NtkBrowserProtocol.KEY_ERROR, detail.take(MAX_ERROR_LENGTH))
    }
}

internal fun sendAckReady(requestId: Long, recipient: Messenger) {
    sendResponse(NtkBrowserProtocol.MSG_ACK_READY, requestId, recipient) {}
}

internal fun sendRequestDetached(requestId: Long, recipient: Messenger) {
    sendResponse(NtkBrowserProtocol.MSG_REQUEST_DETACHED, requestId, recipient) {}
}

internal fun sendDocumentRetired(requestId: Long, recipient: Messenger) {
    sendResponse(NtkBrowserProtocol.MSG_DOCUMENT_RETIRED, requestId, recipient) {}
}

internal fun sendDocumentRequestReady(requestId: Long, recipient: Messenger) {
    sendResponse(NtkBrowserProtocol.MSG_DOCUMENT_REQUEST_READY, requestId, recipient) {}
}

internal fun sendWarmPhase(
    recipient: Messenger,
    phase: String,
    status: Int,
    ageMillis: Long,
) {
    val response = Message.obtain(null, NtkBrowserProtocol.MSG_WARM_PHASE).apply {
        data = Bundle().apply {
            putString(NtkBrowserProtocol.KEY_PHASE, phase.take(MAX_PHASE_LENGTH))
            putInt(NtkBrowserProtocol.KEY_STATUS, status)
            putLong(NtkBrowserProtocol.KEY_AGE_MILLIS, ageMillis.coerceAtLeast(0L))
        }
    }
    try {
        recipient.send(response)
    } catch (failure: RemoteException) {
        Log.w(TAG, "warm phase recipient disappeared", failure)
    }
}

private fun sendResponse(
    what: Int,
    requestId: Long,
    recipient: Messenger,
    body: Bundle.() -> Unit,
) {
    val response = Message.obtain(null, what).apply {
        data = Bundle().apply {
            putLong(NtkBrowserProtocol.KEY_REQUEST_ID, requestId)
            body()
        }
    }
    try {
        recipient.send(response)
    } catch (failure: RemoteException) {
        Log.w(TAG, "response recipient disappeared id=$requestId", failure)
    }
}

internal const val TAG = "NtkBrowserService"
internal const val INVALID_REQUEST_ID = -1L
internal const val MIN_BROWSER_WIDTH_PX = 360
internal const val MIN_BROWSER_HEIGHT_PX = 640
internal const val MAX_USER_AGENT_LENGTH = 2_048
internal const val UNKNOWN_PAGE_COUNT = -1
internal const val MAX_RENDERER_RESTARTS = 1
private const val MAX_ERROR_LENGTH = 1_024
private const val MAX_PHASE_LENGTH = 96
internal val IMAGE_API_PATHS = setOf("/api/webtoon-images", "/api/manhwa-images")
internal val HTTP_SCHEMES = setOf("http", "https")
private val HEX_BROWSER_ID = Regex("^[a-f0-9]{32}$")
