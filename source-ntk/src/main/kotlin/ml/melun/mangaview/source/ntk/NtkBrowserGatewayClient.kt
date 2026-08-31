package ml.melun.mangaview.source.ntk

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

internal class NtkBrowserGatewayClient(
    private val currentRequest: () -> RemoteRequest?,
    private val startAuthorization: (RemoteRequest) -> Unit,
    private val deliverDescriptor: (RemoteRequest) -> Unit,
    private val fail: (RemoteRequest, String) -> Unit,
    private val rendererGone: (WebView, RemoteRequest?) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentRequest()?.let { request ->
            val redirected = runCatching { URI(url) }.getOrNull()
            val expected = runCatching { URI(request.key) }.getOrNull()
            if (redirected?.scheme in HTTP_SCHEMES && redirected?.path == expected?.path) {
                request.key = validatedKey(
                    "${redirected?.scheme}://${redirected?.authority}",
                    requireNotNull(redirected?.path),
                )
            }
            if (!request.captureInstalledAtDocumentStart) {
                view.evaluateJavascript(NtkBrowserCaptureScript.source, null)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val request = currentRequest() ?: return
        if (keyOf(url) != request.key) return
        view.clearHistory()
        startAuthorization(request)
        deliverDescriptor(request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.scheme !in HTTP_SCHEMES

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = NtkBrowserResourcePolicy.intercept(request)

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        currentRequest()?.takeIf { it.key == request.url.withoutQuery() }?.let {
            fail(it, "NTK browser load failed: ${error.errorCode}")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        rendererGone(view, currentRequest())
        return true
    }

    private fun keyOf(url: String): String? = runCatching {
        val uri = URI(url)
        validatedKey("${uri.scheme}://${uri.authority}", requireNotNull(uri.path))
    }.getOrNull()

    private fun android.net.Uri.withoutQuery(): String = buildString {
        append(scheme).append("://").append(host)
        if (port != -1) append(':').append(port)
        append(path)
    }
}
