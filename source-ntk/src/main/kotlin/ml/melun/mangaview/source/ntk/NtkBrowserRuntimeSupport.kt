package ml.melun.mangaview.source.ntk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal class NtkBrowserCookieApplier(
    private val write: (String, String, (Boolean) -> Unit) -> Unit,
) {
    private val pending = ArrayDeque<CookieBatch>()
    private var active: CookieBatch? = null
    constructor(handler: Handler, cookieManager: CookieManager) : this({ origin, value, result ->
        cookieManager.setCookie(origin, value) { accepted -> handler.post { result(accepted) } }
    })

    fun identity(origin: String, identity: NtkBrowserIdentity?, completed: (Boolean) -> Unit) =
        identity(origin, identity, { true }, completed)

    fun identity(
        origin: String, identity: NtkBrowserIdentity?, isCurrent: () -> Boolean, completed: (Boolean) -> Unit,
    ) {
        cookies(
            origin,
            identity?.let { listOf(
                "ntk_fp=${it.fingerprint}; Path=/; Max-Age=31536000; SameSite=Lax; Secure",
                "ntk_pid=${it.persistentId}; Path=/; Max-Age=31536000; SameSite=Lax; Secure",
            ) }.orEmpty(),
            isCurrent = isCurrent,
            completed = completed,
        )
    }

    fun cookies(
        origin: String,
        values: List<String>,
        index: Int = 0,
        isCurrent: () -> Boolean = { true },
        completed: (Boolean) -> Unit,
    ) {
        pending += CookieBatch(origin, values.drop(index), isCurrent, completed)
        drain()
    }

    private fun drain() {
        if (active != null || pending.isEmpty()) return
        val batch = pending.removeFirst()
        active = batch
        advance(batch, 0)
    }

    private fun advance(batch: CookieBatch, index: Int) {
        if (!batch.isCurrent()) return finish(batch, false)
        if (index >= batch.values.size) return finish(batch, true)
        var delivered = false
        runCatching {
            write(batch.origin, batch.values[index]) { accepted ->
                if (!delivered && active === batch) {
                    delivered = true
                    if (accepted) advance(batch, index + 1) else finish(batch, false)
                }
            }
        }.onFailure { if (active === batch) finish(batch, false) }
    }

    private fun finish(batch: CookieBatch, accepted: Boolean) {
        active = null
        try { batch.completed(accepted) } finally { drain() }
    }

    private class CookieBatch(
        val origin: String, val values: List<String>, val isCurrent: () -> Boolean,
        val completed: (Boolean) -> Unit,
    )
}

internal object NtkBrowserViewFactory {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        userAgent: String,
        bridge: HostBridge,
        chromeClient: WebChromeClient,
        gatewayClient: WebViewClient,
        profileName: String? = null,
    ): WebView {
        WebView.setWebContentsDebuggingEnabled(false)
        return WebView(context).apply {
            profileName?.let { name ->
                require(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    "This WebView does not support isolated profiles"
                }
                ProfileStore.getInstance().getOrCreateProfile(name)
                WebViewCompat.setProfile(this, name)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            settings.loadsImagesAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.offscreenPreRaster = false
            settings.userAgentString = userAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            }
            addJavascriptInterface(bridge, NtkBrowserCaptureScript.BRIDGE_NAME)
            webChromeClient = chromeClient
            webViewClient = gatewayClient
            visibility = View.VISIBLE
            setLayerType(View.LAYER_TYPE_NONE, null)
            resumeTimers()
            onResume()
        }
    }
}
