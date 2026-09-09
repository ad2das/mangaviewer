package ml.melun.mangaview.app

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.Closeable
import ml.melun.mangaview.data.network.BrowserTlsRelay

/** Created only in :ntk_browser. Waits for the proxy callback before any provider navigation. */
internal class NtkBrowserNetwork : Closeable {
    private var relay: BrowserTlsRelay? = null

    fun configure(ready: () -> Unit, failed: (Throwable) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            failed(IllegalStateException("NTK 연결을 위해 Android System WebView를 업데이트해 주세요"))
            return
        }
        try {
            check(relay == null)
            val created = BrowserTlsRelay().also { relay = it }
            val main = Handler(Looper.getMainLooper())
            val config = ProxyConfig.Builder().addProxyRule(created.proxyUrl, ProxyConfig.MATCH_HTTPS).build()
            ProxyController.getInstance().setProxyOverride(config, { main.post(it) }, ready)
        } catch (failure: Throwable) {
            close()
            failed(failure)
        }
    }

    fun credentials(host: String, realm: String) = relay?.credentials(host, realm)

    override fun close() { relay?.close(); relay = null }
}
