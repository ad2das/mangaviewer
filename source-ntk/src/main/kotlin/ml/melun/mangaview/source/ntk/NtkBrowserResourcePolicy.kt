package ml.melun.mangaview.source.ntk

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Keeps provider authorization JavaScript intact while dropping presentation-only resources. */
internal object NtkBrowserResourcePolicy {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url
        val path = url.path.orEmpty().lowercase()
        val accept = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            .orEmpty()
            .lowercase()
        val blockedMimeType = blockedMimeType(url.host.orEmpty(), path, accept) ?: return null
        return empty(blockedMimeType)
    }

    internal fun blockedMimeType(host: String, path: String, accept: String): String? = when {
        path.startsWith("/api/") || path.startsWith("/wasm/ad-guard/") ||
            path == "/init/block.js" -> null
        path.endsWith(".css") || "text/css" in accept -> "text/css"
        FONT_EXTENSIONS.any(path::endsWith) || "font/" in accept -> "font/woff2"
        host.equals("whoas.xyz", ignoreCase = true) -> "application/javascript"
        path == "/init/theme.js" || path == "/init/auth-modal.js" -> "application/javascript"
        else -> null
    }

    private fun empty(mimeType: String): WebResourceResponse = WebResourceResponse(
        mimeType,
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    private val FONT_EXTENSIONS = listOf(".woff", ".woff2", ".ttf", ".otf")
}
