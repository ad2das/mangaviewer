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
        return when {
            path.endsWith(".css") || "text/css" in accept -> empty("text/css")
            FONT_EXTENSIONS.any(path::endsWith) || "font/" in accept -> empty("font/woff2")
            url.host.equals("whoas.xyz", ignoreCase = true) -> empty("application/javascript")
            path == "/init/theme.js" || path == "/init/auth-modal.js" -> {
                empty("application/javascript")
            }
            else -> null
        }
    }

    private fun empty(mimeType: String): WebResourceResponse = WebResourceResponse(
        mimeType,
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    private val FONT_EXTENSIONS = listOf(".woff", ".woff2", ".ttf", ".otf")
}
