package ml.melun.mangaview.app

import android.os.Handler
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ml.melun.mangaview.data.network.BrowserTlsRelay

private const val TAG = "NewxtoonClearance"

/**
 * Routes Chromium's own TLS through the relay that fragments the first ClientHello record;
 * without it the SNI filter resets the connection before Cloudflare ever answers.
 */
internal suspend fun installChallengeProxyOverride(relay: BrowserTlsRelay, main: Handler): Boolean =
    installChallengeProxyOverride(relay.proxyUrl, main)

internal suspend fun installChallengeProxyOverride(proxyUrl: String, main: Handler): Boolean =
    suspendCancellableCoroutine { continuation ->
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val config = ProxyConfig.Builder()
            .addProxyRule(proxyUrl, ProxyConfig.MATCH_HTTPS)
            .build()
        try {
            ProxyController.getInstance().setProxyOverride(config, { main.post(it) }) {
                if (continuation.isActive) continuation.resume(true)
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "proxy override failed", failure)
            if (continuation.isActive) continuation.resume(false)
        }
    }

internal suspend fun clearChallengeProxyOverride(main: Handler) {
    runCatching {
        suspendCancellableCoroutine<Unit> { continuation ->
            ProxyController.getInstance().clearProxyOverride({ main.post(it) }) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
