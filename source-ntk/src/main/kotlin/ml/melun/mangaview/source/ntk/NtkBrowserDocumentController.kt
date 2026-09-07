package ml.melun.mangaview.source.ntk

import android.os.Message
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

internal class NtkBrowserDocumentController(
    private val currentRequest: () -> RemoteRequest?,
    private val warmRunning: (String) -> Boolean,
    private val applyCookies: (String, List<String>, (Boolean) -> Unit) -> Unit,
    private val documentReady: (RemoteRequest) -> Unit,
    private val startAuthorization: (RemoteRequest) -> Unit,
    private val installManifestDescriptor: (RemoteRequest) -> Unit,
    private val fail: (RemoteRequest, String) -> Unit,
) {
    fun descriptor(message: Message) {
        val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        val request = currentRequest()?.takeIf { it.contains(requestId) }
        val payload = runCatching { NtkBrowserDocumentPayload.receive(message.data) }.getOrElse { failure ->
            request?.let { fail(it, "NTK document rejected: ${failure.message}") }
            return
        }
        if (request == null || request.document != null) {
            payload.close()
            return
        }
        val descriptor = runCatching {
            require(payload.key == request.key) { "NTK document belongs to another episode" }
            RemoteDescriptor.from(message.data)
        }.getOrElse { failure ->
            payload.close()
            fail(request, "NTK descriptor rejected: ${failure.message}")
            return
        }
        request.document = payload
        request.descriptor = descriptor
        Log.d("NtkAck", "phase=document-received status=200 ageMs=${request.ageMillis()}")
        request.browserTrace("browser-document-received")
        applyCookies(request.origin, payload.cookies) { accepted ->
            if (currentRequest() === request) {
                if (!accepted) {
                    fail(request, "NTK document cookies rejected")
                    return@applyCookies
                }
                request.documentCookiesApplied = true
                if (payload.cookies.isNotEmpty()) {
                    request.challengePreflightStarted = false
                    request.challengePreflightResolved = false
                    request.challengePayload = null
                    request.challengeReceivedAtMillis = 0L
                    request.inheritedChallengeRequestIds.clear()
                }
                Log.d("NtkAck", "phase=document-cookies-ready status=200 ageMs=${request.ageMillis()}")
                request.browserTrace("browser-document-cookies-ready")
                documentReady(request)
                deliver(request)
            }
        }
    }

    fun intercept(resource: WebResourceRequest): WebResourceResponse? {
        if (!resource.isForMainFrame || resource.method != "GET") return null
        val request = currentRequest() ?: return null
        val payload = request.document ?: return null
        if (resource.url.toString().substringBefore('?').substringBefore('#') != payload.key) return null
        return runCatching { payload.response() }.getOrElse {
            WebResourceResponse(
                "text/html", "UTF-8", 410, "Gone", emptyMap(), ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    fun deliver(request: RemoteRequest) {
        if (currentRequest() !== request || request.descriptor == null || !request.documentCookiesApplied ||
            !request.identityCookiesApplied ||
            !request.browserDocumentStarted
        ) return
        if (warmRunning(request.origin) || request.descriptorDelivered) return
        request.descriptorDelivered = true
        startAuthorization(request)
        installManifestDescriptor(request)
    }
}
