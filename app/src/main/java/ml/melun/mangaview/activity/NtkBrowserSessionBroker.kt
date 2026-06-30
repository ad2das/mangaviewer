package ml.melun.mangaview.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.R
import ml.melun.mangaview.mangaview.NtkWebViewFallbackManager
import ml.melun.mangaview.reader.ReaderImageCache
import org.json.JSONArray
import org.json.JSONObject

object NtkBrowserSessionBroker {
    private const val TAG = "ViewerPerf"
    private const val READY_DESCRIPTION = "reader-drawable-ready"

    data class ImageSnapshot(
        val baseUrl: String,
        val path: String,
        val documentUrl: String,
        val images: List<String>,
        val source: String,
        val cloudflare: Boolean,
        val createdAtMs: Long
    )

    data class ScrollSnapshot(
        val path: String,
        val scrollY: Int,
        val viewportHeight: Int,
        val contentHeight: Int,
        val maxScroll: Int,
        val nearEnd: Boolean,
        val createdAtMs: Long
    )

    data class VisibleCoverageSnapshot(
        val path: String,
        val viewportPx: Int,
        val drawablePx: Int,
        val missingPx: Int,
        val drawableItems: Int,
        val totalItems: Int,
        val visibleLoading: Int,
        val visibleErrors: Int,
        val pageCount: Int,
        val createdAtMs: Long
    )

    data class PreparedStatus(
        val path: String,
        val pageStarted: Boolean,
        val firstDrawable: Boolean,
        val allDecodedReady: Boolean,
        val imageCount: Int,
        val expected: Int
    )

    interface Listener {
        fun onState(path: String, cloudflare: Boolean, title: String, bodySample: String) {}
        fun onFirstDrawable(path: String) {}
        fun onViewportReady(path: String) {}
        fun onImages(snapshot: ImageSnapshot) {}
        fun onScroll(snapshot: ScrollSnapshot) {}
        fun onCoverage(snapshot: VisibleCoverageSnapshot) {}
        fun onNeedsUserVerification(path: String) {}
        fun onError(path: String, message: String) {}
    }

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var currentBaseUrl = ""
    @Volatile
    private var currentPath = ""
    @Volatile
    private var currentDocumentUrl = ""
    @Volatile
    private var currentLoadTarget = ""
    @Volatile
    private var currentListener: Listener? = null
    @Volatile
    private var currentAppContext: Context? = null
    @Volatile
    private var currentUserAgent = ""
    @Volatile
    private var currentCookieHeader = ""
    @Volatile
    private var firstDrawableSent = false
    @Volatile
    private var viewportReadySent = false
    @Volatile
    private var firstDrawableReadyPath = ""
    @Volatile
    private var viewportReadyPath = ""
    @Volatile
    private var allDecodedReadyPath = ""
    @Volatile
    private var pageStartedAtMs = 0L
    private val snapshots = ConcurrentHashMap<String, ImageSnapshot>()
    private val imageUrlsByPath = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val pendingPrimeUrlsByPath = ConcurrentHashMap<String, List<String>>()
    private val pendingViewerPayloadByPath = ConcurrentHashMap<String, String>()
    private val injectedViewerPayloadSigByPath = ConcurrentHashMap<String, String>()
    private val primeOnlyUrlsByPath = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val pendingDiscoveredUrlsByPath = ConcurrentHashMap<String, List<String>>()
    @Volatile
    private var latestScrollSnapshot: ScrollSnapshot? = null
    @Volatile
    private var latestCoverageSnapshot: VisibleCoverageSnapshot? = null
    @Volatile
    private var scrollFreezeUntilMs = 0L
    @Volatile
    private var scrollFreezeY = 0
    @Volatile
    private var scrollFreezeContentHeight = 0
    @Volatile
    private var currentExpectedImageCount = 0
    @Volatile
    private var currentVisible = false
    @Volatile
    private var controlledStripPath = ""
    @Volatile
    private var controlledStripSig = ""
    @Volatile
    private var controlledDocumentPath = ""
    @Volatile
    private var documentStartScriptHandler: ScriptHandler? = null
    @Volatile
    private var ackDocumentStartScriptHandler: ScriptHandler? = null
    @Volatile
    private var discoveryDocumentStartScriptHandler: ScriptHandler? = null
    private val keyPrecomputeStartedByPath = ConcurrentHashMap<String, Long>()

    fun latestSnapshot(path: String?): ImageSnapshot? {
        val key = normalizePath(path)
        if (key.isEmpty()) return null
        return snapshots[key]
    }

    fun latestScrollSnapshot(path: String?): ScrollSnapshot? {
        val key = normalizePath(path)
        val snapshot = latestScrollSnapshot ?: return null
        return if (snapshot.path == key) snapshot else null
    }

    fun latestCoverageSnapshot(path: String?): VisibleCoverageSnapshot? {
        val key = normalizePath(path)
        val snapshot = latestCoverageSnapshot ?: return null
        return if (snapshot.path == key) snapshot else null
    }

    @JvmStatic
    fun preparedStatus(path: String?): PreparedStatus {
        val key = normalizePath(path)
        val snapshot = snapshots[key]
        return PreparedStatus(
            key,
            key.isNotEmpty() && key == currentPath && pageStartedAtMs > 0L,
            key.isNotEmpty() && key == currentPath && firstDrawableReadyPath == key,
            key.isNotEmpty() && key == currentPath && allDecodedReadyPath == key,
            snapshot?.images?.size ?: 0,
            currentExpectedImageCount
        )
    }

    @JvmStatic
    @JvmOverloads
    fun isAllDecodedReady(path: String?, expected: Int = 0): Boolean {
        val key = normalizePath(path)
        if (key.isEmpty() || key != currentPath || allDecodedReadyPath != key) return false
        val required = maxOf(expected, currentExpectedImageCount)
        val count = snapshots[key]?.images?.size ?: 0
        return required <= 0 || count >= required
    }

    fun freezeCurrentScroll(path: String?, scrollY: Int, contentHeight: Int, durationMs: Long = 950L) {
        val key = normalizePath(path)
        if (key.isEmpty() || key != currentPath) return
        scrollFreezeY = scrollY.coerceAtLeast(0)
        scrollFreezeContentHeight = contentHeight.coerceAtLeast(0)
        scrollFreezeUntilMs = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L)
    }

    @JvmOverloads
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(
        activity: Activity,
        parent: FrameLayout,
        baseUrl: String,
        path: String,
        userAgent: String,
        headers: Map<String, String>,
        visible: Boolean,
        listener: Listener,
        expectedImageCount: Int = 0
    ): WebView {
        val key = normalizePath(path)
        val previousPath = currentPath
        val reusedReadyDrawable = key.isNotEmpty() && key == previousPath && firstDrawableReadyPath == key
        val reusedReadyViewport = key.isNotEmpty() && key == previousPath && viewportReadyPath == key
        if (key != previousPath) {
            firstDrawableReadyPath = ""
            viewportReadyPath = ""
            currentLoadTarget = ""
            latestScrollSnapshot = null
            latestCoverageSnapshot = null
        }
        injectedViewerPayloadSigByPath.remove(key)
        currentBaseUrl = baseUrl.trimEnd('/')
        currentPath = key
        currentDocumentUrl = currentBaseUrl + key
        currentListener = listener
        currentAppContext = activity.applicationContext
        currentExpectedImageCount = expectedImageCount.coerceAtLeast(0)
        firstDrawableSent = reusedReadyDrawable
        viewportReadySent = reusedReadyViewport
        if (key != previousPath) controlledDocumentPath = ""
        if (key != previousPath) {
            allDecodedReadyPath = ""
            pageStartedAtMs = 0L
        }
        NtkWebViewFallbackManager.beginForegroundHybridReader(activity.applicationContext, key)
        val view = webView ?: WebView(activity).also { created ->
            webView = created
            val settings = created.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
            val bridge = Bridge()
            created.addJavascriptInterface(bridge, "NtkBrowserBridge")
            created.addJavascriptInterface(bridge, "NtkViewerBridge")
            created.addJavascriptInterface(bridge, "NtkAckBridge")
            created.addJavascriptInterface(bridge, "__NtkViewerBridgeNative")
            created.addJavascriptInterface(bridge, "__NtkAckBridgeNative")
            created.addJavascriptInterface(bridge, "MangaViewerNativeViewerBridge")
            created.addJavascriptInterface(bridge, "MangaViewerNativeAckBridge")
            created.webChromeClient = WebChromeClient()
            created.webViewClient = client()
        }
        if (key != previousPath) {
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
            } catch (_: Throwable) {
            }
        }
        val effectiveUserAgent = browserLikeUserAgent(
            view.settings.userAgentString,
            userAgent
        )
        val target = currentBaseUrl + key
        view.settings.userAgentString = effectiveUserAgent
        currentUserAgent = effectiveUserAgent
        currentCookieHeader = CookieManager.getInstance().getCookie(target).orEmpty()
        currentVisible = visible
        clearGlobalWebViewProxyForReader(key)
        val modernNaturalOwner = isModernNaturalOwnerPath(key)
        val quicBridge = try {
            NtkWebViewFallbackManager.createViewerQuicBridgeApi(
                activity.applicationContext,
                effectiveUserAgent,
                currentCookieHeader
            )
        } catch (_: Throwable) {
            null
        }
        if (quicBridge != null) {
            try {
                view.addJavascriptInterface(quicBridge, "NtkQuicBridge")
                Log.d(TAG, "ntk_browser_broker_quic_bridge_installed path=$key,foreground=${isModernNaturalOwnerPath(key)}")
            } catch (_: Throwable) {
            }
        }
        if (modernNaturalOwner) installForegroundAckDocumentStart(view, key)
        installDiscoveryDocumentStart(view, key)
        installDocumentStartCapture(view, key)
        applyChromeUaMetadata(view.settings, effectiveUserAgent)
        suppressRequestedWithHeader(view.settings)
        view.id = R.id.strip
        view.contentDescription = null
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = if (visible) 0f else -activity.resources.displayMetrics.widthPixels.toFloat() * 2f
        view.translationY = 0f
        try {
            view.onResume()
            view.resumeTimers()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        } catch (_: Throwable) {
        }
        view.isClickable = visible
        view.isFocusable = visible
        view.isFocusableInTouchMode = visible
        view.importantForAccessibility = if (visible) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        view.overScrollMode = View.OVER_SCROLL_NEVER
        if (visible) {
            view.setOnTouchListener { touched, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        touched.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        touched.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                    else -> false
                }
            }
        } else {
            view.setOnTouchListener(null)
        }
        val existingParent = view.parent as? ViewGroup
        if (existingParent != null && existingParent !== parent) {
            existingParent.removeView(view)
        }
        if (view.parent == null) {
            parent.addView(
                view,
                0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (view.url != target && currentLoadTarget != target) {
            val seeded = strictReaderStripUrls(pendingPrimeUrlsByPath[key].orEmpty(), target)
            val canStartControlledFromSeed = currentExpectedImageCount > 0 &&
                seeded.size >= currentExpectedImageCount
            if (canStartControlledFromSeed) {
                maybeLoadControlledStrip(key, seeded, "initial-prime", target)
            } else {
                Log.d(
                    TAG,
                    "ntk_browser_broker_load path=$key,url=$target,visible=$visible," +
                        "ua=${effectiveUserAgent.take(120)}"
                )
                currentLoadTarget = target
                view.loadUrl(target, headers)
            }
        } else {
            installDiscovery(view)
        }
        if (quicBridge != null) {
            beginForegroundKeyPrecompute(quicBridge, target, effectiveUserAgent, key)
        }
        if (reusedReadyDrawable || reusedReadyViewport) {
            view.post {
                if (key != currentPath) return@post
                if (reusedReadyDrawable) currentListener?.onFirstDrawable(key)
                if (reusedReadyViewport) {
                    if (!visible) {
                        view.contentDescription = READY_DESCRIPTION
                        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    }
                    currentListener?.onViewportReady(key)
                }
                Log.d(
                    TAG,
                    "ntk_browser_broker_reuse_ready path=$key," +
                        "first=$reusedReadyDrawable,viewport=$reusedReadyViewport"
                )
            }
        }
        return view
    }

    fun primeImageUrls(path: String?, urls: List<String>?, source: String) {
        val key = normalizePath(path)
        val values = urls.orEmpty().mapNotNull { raw ->
            normalizeImageUrl(raw, currentDocumentUrl.ifBlank { currentBaseUrl + key })
                .takeIf { it.isNotEmpty() && looksLikeReaderImageUrl(it, null) }
        }.distinct()
        if (key.isEmpty() || values.isEmpty()) return
        pendingPrimeUrlsByPath[key] = values
        val primeOnly = primeOnlyUrlsByPath.getOrPut(key) { LinkedHashSet() }
        synchronized(primeOnly) {
            primeOnly.addAll(values)
        }
        if (currentExpectedImageCount > 0 && values.size >= currentExpectedImageCount) {
            maybeLoadControlledStrip(
                key,
                values.take(currentExpectedImageCount),
                "prime-verify-$source",
                currentDocumentUrl.ifBlank { currentBaseUrl + key }
            )
        }
        val view = webView ?: return
        if (key != currentPath) return
        view.post {
            injectPrimeUrls(view, key, values, source)
            renderNaturalStrip(view, key, values, "prime-$source")
        }
    }

    fun publishAuthoritativeImageUrls(path: String?, urls: List<String>?, source: String) {
        val key = normalizePath(path)
        if (key.isEmpty() || key != currentPath || urls.isNullOrEmpty()) return
        recordImages(
            urls,
            source,
            currentDocumentUrl.ifBlank { currentBaseUrl + key },
            false,
            true
        )
    }

    @JvmStatic
    fun publishViewerPayload(path: String?, payload: String?, source: String) {
        val key = normalizePath(path)
        val body = payload.orEmpty()
        if (key.isEmpty() || body.length < 64 || !body.contains("imageApiPath")) return
        pendingViewerPayloadByPath[key] = body
        val signature = "${body.length}:${body.hashCode()}"
        val previous = injectedViewerPayloadSigByPath.putIfAbsent(key, signature)
        if (previous == signature) return
        val view = webView ?: return
        if (key != currentPath) return
        view.post {
            if (view.url.orEmpty().contains(key)) installDiscovery(view)
            injectViewerPayload(view, key, body, source)
        }
    }

    fun detachKeepAlive() {
        val view = webView ?: return
        try {
            NtkWebViewFallbackManager.endForegroundHybridReader(currentAppContext, currentPath)
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (_: Throwable) {
        }
    }

    fun destroyForLogoutOrDataClear() {
        val view = webView
        webView = null
        try {
            documentStartScriptHandler?.remove()
            documentStartScriptHandler = null
            NtkWebViewFallbackManager.endForegroundHybridReader(currentAppContext, currentPath)
            (view?.parent as? ViewGroup)?.removeView(view)
            view?.destroy()
        } catch (_: Throwable) {
        }
        snapshots.clear()
        imageUrlsByPath.clear()
        currentPath = ""
        currentBaseUrl = ""
        currentDocumentUrl = ""
        currentListener = null
        latestScrollSnapshot = null
        injectedViewerPayloadSigByPath.clear()
            controlledDocumentPath = ""
            firstDrawableReadyPath = ""
            viewportReadyPath = ""
            allDecodedReadyPath = ""
            pageStartedAtMs = 0L
    }

    private fun isControlledDocumentForCurrentPath(): Boolean {
        return controlledDocumentPath.isNotEmpty() && controlledDocumentPath == currentPath
    }

    private fun client(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!urlMatchesCurrentPath(url)) {
                    Log.d(TAG, "ntk_browser_broker_page_started_stale path=$currentPath,url=$url")
                    return
                }
                currentDocumentUrl = url
                pageStartedAtMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "ntk_browser_broker_page_started path=$currentPath,url=$url")
                if (isModernNaturalOwnerPath(currentPath)) {
                    installForegroundTrustBridge(view, "page-started")
                    installForegroundAckProof(view, "page-started")
                }
                if (!isControlledDocumentForCurrentPath()) installDiscovery(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!urlMatchesCurrentPath(url)) {
                    Log.d(TAG, "ntk_browser_broker_page_finished_stale path=$currentPath,url=$url")
                    return
                }
                currentDocumentUrl = url
                Log.d(TAG, "ntk_browser_broker_page_finished path=$currentPath,url=$url")
                if (isModernNaturalOwnerPath(currentPath)) {
                    installForegroundTrustBridge(view, "page-finished")
                    installForegroundAckProof(view, "page-finished")
                }
                if (!isControlledDocumentForCurrentPath()) installDiscovery(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (!urlMatchesCurrentPath(url)) {
                    Log.d(TAG, "ntk_browser_broker_page_commit_stale path=$currentPath,url=$url")
                    return
                }
                currentDocumentUrl = url
                if (isModernNaturalOwnerPath(currentPath)) {
                    installForegroundTrustBridge(view, "page-commit")
                    installForegroundAckProof(view, "page-commit")
                }
                if (!isControlledDocumentForCurrentPath()) installDiscovery(view)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                val normalizedUrl = normalizeImageUrl(
                    url,
                    currentDocumentUrl.ifBlank { currentBaseUrl + currentPath }
                )
                val primeOnly = primeOnlyUrlsByPath[currentPath]
                val speculativePrime = primeOnly != null && synchronized(primeOnly) {
                    primeOnly.contains(normalizedUrl)
                }
                if (!speculativePrime && looksLikeReaderImageUrl(url, request.requestHeaders)) {
                    recordImages(
                        listOf(url),
                        "request",
                        currentDocumentUrl.ifBlank { currentBaseUrl + currentPath },
                        false,
                        true
                    )
                }
                val requestPath = try {
                    request.url?.path.orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                if (isModernNaturalOwnerPath(currentPath)) {
                    if (requestPath.startsWith("/api/")) {
                        Log.d(TAG, "ntk_natural_api_intercept_passthrough path=$currentPath,api=$requestPath")
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                val intercepted = try {
                    NtkWebViewFallbackManager.interceptViewerQuicResource(
                        currentAppContext,
                        currentUserAgent,
                        currentCookieHeader,
                        request
                    )
                } catch (_: Throwable) {
                    null
                }
                if (intercepted != null) return intercepted
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun installDiscovery(view: WebView) {
        view.post {
            try {
                view.evaluateJavascript(discoveryScript()) {
                    pendingPrimeUrlsByPath[currentPath]?.let { urls ->
                        injectPrimeUrls(view, currentPath, urls, "pending")
                    }
                    pendingViewerPayloadByPath[currentPath]?.let { payload ->
                        injectViewerPayload(view, currentPath, payload, "pending")
                    }
                }
            } catch (e: Throwable) {
                currentListener?.onError(currentPath, e.toString())
            }
        }
    }

    private fun installDocumentStartCapture(view: WebView, path: String) {
        if (!isModernNaturalOwnerPath(path)) return
        try {
            documentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        documentStartScriptHandler = null
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Log.d(TAG, "ntk_natural_capture_document_start_unsupported path=$path")
                return
            }
            documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view,
                naturalCaptureScript(true),
                setOf("*")
            )
            Log.d(TAG, "ntk_natural_capture_document_start_registered path=$path")
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_natural_capture_document_start_error path=$path,$e")
        }
    }

    private fun installForegroundAckDocumentStart(view: WebView, path: String) {
        if (!isModernNaturalOwnerPath(path)) return
        val base = currentBaseUrl
        if (base.isEmpty()) return
        try {
            ackDocumentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        ackDocumentStartScriptHandler = null
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Log.d(TAG, "ntk_foreground_ack_document_start_unsupported path=$path")
                return
            }
            ackDocumentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view,
                NtkWebViewFallbackManager.foregroundAckProofScript(base, path),
                setOf("*")
            )
            Log.d(TAG, "ntk_foreground_ack_document_start_registered path=$path")
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_foreground_ack_document_start_error path=$path,$e")
        }
    }

    private fun installDiscoveryDocumentStart(view: WebView, path: String) {
        if (!isModernNaturalOwnerPath(path)) return
        try {
            discoveryDocumentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        discoveryDocumentStartScriptHandler = null
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Log.d(TAG, "ntk_discovery_document_start_unsupported path=$path")
                return
            }
            discoveryDocumentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                view,
                discoveryScript(),
                setOf("*")
            )
            Log.d(TAG, "ntk_discovery_document_start_registered path=$path")
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_discovery_document_start_error path=$path,$e")
        }
    }

    private fun isModernNaturalOwnerPath(path: String): Boolean {
        return path.startsWith("/manhwa/", ignoreCase = true) ||
            path.startsWith("/webtoon/", ignoreCase = true)
    }

    private fun ownerPathFromUrl(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val path = try {
            Uri.parse(raw).path.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val normalized = normalizePath(path)
        return if (isModernNaturalOwnerPath(normalized)) normalized else ""
    }

    private fun urlMatchesCurrentPath(value: String?): Boolean {
        val urlPath = ownerPathFromUrl(value)
        return currentPath.isNotEmpty() && urlPath == currentPath
    }

    private fun callbackMatchesCurrentPath(json: JSONObject): Boolean {
        val hrefPath = ownerPathFromUrl(json.optString("href", ""))
        return hrefPath.isEmpty() || currentPath.isEmpty() || hrefPath == currentPath
    }

    private fun beginForegroundKeyPrecompute(
        bridge: NtkWebViewFallbackManager.ViewerQuicBridgeApi,
        pageUrl: String,
        userAgent: String,
        path: String
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = keyPrecomputeStartedByPath[path]
        if (previous != null && now - previous < 10_000L) {
            Log.d(TAG, "ntk_browser_broker_key_precompute_skip_active path=$path")
            return
        }
        keyPrecomputeStartedByPath[path] = now
        val thread = Thread({
            val started = SystemClock.elapsedRealtime()
            try {
                val parsed = URL(pageUrl)
                val origin = "${parsed.protocol}://${parsed.host}"
                val headers = JSONObject()
                    .put("accept", "application/json")
                    .put("origin", origin)
                    .put("referer", pageUrl)
                    .put("user-agent", userAgent)
                    .put("content-type", "application/json")
                bridge.request(
                    "$origin/api/nv-issue",
                    "POST",
                    headers.toString(),
                    ""
                )
                val keyRaw = bridge.ensureViewerBrowserKeyForUserAgent(pageUrl, userAgent)
                webView?.post {
                    try {
                        webView?.evaluateJavascript(
                            "try{window.__ntkBrowserKeyReady=1;window.dispatchEvent(new CustomEvent('ntk-browser-key-ready'));}catch(e){}",
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }
                Log.d(
                    TAG,
                    "ntk_browser_broker_key_precompute path=$path," +
                        "ms=${SystemClock.elapsedRealtime() - started}," +
                        "result=${keyRaw.take(180)}"
                )
            } catch (e: Throwable) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_key_precompute_error path=$path," +
                        "ms=${SystemClock.elapsedRealtime() - started},$e"
                )
            }
        }, "ntk-foreground-key-precompute")
        thread.isDaemon = true
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
    }

    private fun naturalCaptureScript(documentStart: Boolean): String {
        val documentStartLiteral = if (documentStart) "true" else "false"
        return """
            (function(){
              try{
                window.__ntkNaturalSamePageOwner=1;
                if(window.__ntkNaturalCaptureInstalled)return;
                window.__ntkNaturalCaptureInstalled=1;
                function abs(u){try{return new URL(String(u||''),location.href).href;}catch(_){return '';}}
                function apiPath(u){try{return new URL(abs(u)).pathname;}catch(_){return '';}}
                function cookieSummary(){try{var c=String(document.cookie||'');return {len:c.length,hasAdAck:/(?:^|;\s*)ad_ack=/.test(c),hasAdAckC:/(?:^|;\s*)ad_ack_c=/.test(c),hasAdGuardL:/(?:^|;\s*)ad_guard_l=/.test(c),hasCfClearance:/(?:^|;\s*)cf_clearance=/.test(c)};}catch(_){return {};}}
                function state(label,obj){try{obj=obj||{};obj.body=label;obj.href=String(location.href||'');obj.cookie=cookieSummary();NtkBrowserBridge.onState(JSON.stringify(obj));}catch(_){}}
                function isViewerApi(p){return p==='/api/manhwa-images'||p==='/api/webtoon-images'||p==='/api/manga-images';}
                function isAckApi(p){return p.indexOf('/api/ad/')===0||p==='/api/m/i'||p==='/api/m/ev';}
                function publishViewer(p,status,text,source){
                  try{
                    state('natural-viewer-api-response',{api:p,status:status||0,ok:status>=200&&status<300,len:String(text||'').length,text:String(text||'').slice(0,160),source:source});
                    if((status||0)>=200&&(status||0)<300){
                      NtkBrowserBridge.onViewerImages(JSON.stringify({href:String(location.href||''),path:p,status:status||0,body:String(text||''),source:source}));
                    }
                  }catch(_){}
                }
                function publishAck(p,status,text,source){
                  try{state('natural-ack-response',{api:p,status:status||0,ok:status>=200&&status<300,len:String(text||'').length,text:String(text||'').slice(0,160),source:source});}catch(_){}
                }
                var nativeFetch=window.fetch?window.fetch.bind(window):null;
                if(nativeFetch&&!window.fetch.__ntkNaturalCapture){
                  var wrappedFetch=function(input,init){
                    var req=null,p='',method='';
                    try{req=(input instanceof Request)?input.clone():new Request(input,init);p=apiPath(req.url);method=req&&req.method||'';}catch(_){}
                    var watched=isViewerApi(p)||isAckApi(p);
                    if(watched)state('natural-fetch-start',{api:p,method:method,source:'fetch'});
                    var promise=nativeFetch(input,init);
                    if(!watched)return promise;
                    return promise.then(function(resp){
                      try{
                        resp.clone().text().then(function(t){
                          if(isViewerApi(p))publishViewer(p,resp.status||0,t,'natural-fetch');
                          else publishAck(p,resp.status||0,t,'natural-fetch');
                        }).catch(function(){});
                      }catch(_){}
                      return resp;
                    },function(e){state('natural-fetch-error',{api:p,error:String(e).slice(0,160),source:'fetch'});throw e;});
                  };
                  try{wrappedFetch.__ntkNaturalCapture=1;}catch(_){}
                  window.fetch=wrappedFetch;
                }
                if(window.XMLHttpRequest&&!window.XMLHttpRequest.__ntkNaturalCapture){
                  var NativeXHR=window.XMLHttpRequest;
                  var WrappedXHR=function(){
                    var xhr=new NativeXHR(),u='',m='';
                    try{
                      var open=xhr.open;
                      xhr.open=function(method,url){
                        m=String(method||'GET');u=String(url||'');
                        return open.apply(xhr,arguments);
                      };
                      var send=xhr.send;
                      xhr.send=function(){
                        var p=apiPath(u),watched=isViewerApi(p)||isAckApi(p);
                        if(watched){
                          state('natural-xhr-start',{api:p,method:m,source:'xhr'});
                          xhr.addEventListener('loadend',function(){
                            try{
                              var text='';
                              try{text=String(xhr.responseText||'');}catch(_){}
                              if(isViewerApi(p))publishViewer(p,xhr.status||0,text,'natural-xhr');
                              else publishAck(p,xhr.status||0,text,'natural-xhr');
                            }catch(_){}
                          });
                        }
                        return send.apply(xhr,arguments);
                      };
                    }catch(_){}
                    return xhr;
                  };
                  try{
                    Object.keys(NativeXHR).forEach(function(k){try{WrappedXHR[k]=NativeXHR[k];}catch(_){}});
                    WrappedXHR.prototype=NativeXHR.prototype;
                    WrappedXHR.__ntkNaturalCapture=1;
                  }catch(_){}
                  window.XMLHttpRequest=WrappedXHR;
                }
                state('natural-capture-installed',{documentStart:$documentStartLiteral});
              }catch(e){try{NtkBrowserBridge.onState(JSON.stringify({body:'natural-capture-install-error '+String(e).slice(0,160),documentStart:$documentStartLiteral}));}catch(_){}}
            })();
        """.trimIndent()
    }

    private fun injectViewerPayload(view: WebView, path: String, payload: String, source: String) {
        if (path.isEmpty() || path != currentPath || payload.isEmpty()) return
        val payloadJson = JSONObject.quote(payload)
        val sourceJson = JSONObject.quote(source)
        try {
            view.evaluateJavascript(
                "try{window.__mvNtkInjectedViewerData=$payloadJson;if(window.__mvNtkScan)window.__mvNtkScan('payload-'+$sourceJson);}catch(e){}",
                null
            )
            Log.d(TAG, "ntk_browser_broker_payload path=$path,bytes=${payload.length},source=$source")
        } catch (e: Throwable) {
            currentListener?.onError(path, e.toString())
        }
    }

    private fun injectPrimeUrls(view: WebView, path: String, urls: List<String>, source: String) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val json = JSONArray(urls).toString()
        val sourceJson = JSONObject.quote(source)
        try {
            view.evaluateJavascript(
                "try{if(window.__mvNtkPrime)window.__mvNtkPrime($json,$sourceJson);else window.__mvNtkPendingPrime=$json;}catch(e){}",
                null
            )
            Log.d(TAG, "ntk_browser_broker_prime path=$path,count=${urls.size},source=$source")
        } catch (e: Throwable) {
            currentListener?.onError(path, e.toString())
        }
    }

    private fun recordImages(
        urls: List<String>,
        source: String,
        documentUrl: String,
        cloudflare: Boolean,
        allowGenericImageRequest: Boolean = false
    ) {
        val key = currentPath
        if (key.isEmpty() || urls.isEmpty()) return
        val modernNtkReader = key.startsWith("/manhwa/") || key.startsWith("/webtoon/")
        val bucket = imageUrlsByPath.getOrPut(key) { LinkedHashSet() }
        var changed = false
        synchronized(bucket) {
            for (raw in urls) {
                val normalized = normalizeImageUrl(raw, documentUrl)
                if (
                    normalized.isNotEmpty() &&
                    (
                        looksLikeReaderImageUrl(normalized, null) ||
                            (!modernNtkReader &&
                                allowGenericImageRequest &&
                                looksLikeGenericReaderImageRequest(normalized))
                    )
                ) {
                    changed = bucket.add(normalized) || changed
                }
            }
            if (!changed) return
        val snapshotUrls = strictReaderStripUrls(bucket.toList(), documentUrl)
            val snapshot = ImageSnapshot(
                currentBaseUrl,
                key,
                documentUrl,
                snapshotUrls,
                source,
                cloudflare,
                SystemClock.elapsedRealtime()
            )
            snapshots[key] = snapshot
            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(key, snapshotUrls, source)
            Log.d(
                TAG,
                "ntk_browser_broker_images path=$key,count=${snapshotUrls.size},source=$source," +
                    "head=${snapshotUrls.take(3).joinToString("|")}"
            )
            currentListener?.onImages(snapshot)
            maybeLoadControlledStrip(key, snapshotUrls, source, documentUrl)
        }
    }

    private fun clearGlobalWebViewProxyForReader(path: String) {
        if (!path.startsWith("/manhwa/") && !path.startsWith("/webtoon/")) return
        try {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, Runnable {
                Log.d(TAG, "ntk_browser_broker_proxy_cleared path=$path")
            })
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_browser_broker_proxy_clear_error path=$path,$e")
        }
    }

    private fun installForegroundTrustBridge(view: WebView, reason: String) {
        val key = currentPath
        if (key.isEmpty() || (!key.startsWith("/manhwa/") && !key.startsWith("/webtoon/"))) return
        try {
            view.evaluateJavascript(NtkWebViewFallbackManager.foregroundHybridTrustScript(), null)
            Log.d(TAG, "ntk_foreground_trust_bridge_installed path=$key,reason=$reason")
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_foreground_trust_bridge_error path=$key,reason=$reason,$e")
        }
    }

    private fun installForegroundAckProof(view: WebView, reason: String) {
        val key = currentPath
        val base = currentBaseUrl
        if (key.isEmpty() || base.isEmpty() || (!key.startsWith("/manhwa/") && !key.startsWith("/webtoon/"))) return
        if (isControlledDocumentForCurrentPath()) return
        try {
            view.evaluateJavascript(NtkWebViewFallbackManager.foregroundAckProofScript(base, key), null)
            Log.d(TAG, "ntk_foreground_ack_proof_installed path=$key,reason=$reason")
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_foreground_ack_proof_error path=$key,reason=$reason,$e")
        }
    }

    private fun maybeLoadControlledStrip(
        path: String,
        urls: List<String>,
        source: String,
        documentUrl: String
    ) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val expected = currentExpectedImageCount
        val stripUrls = strictReaderStripUrls(urls, documentUrl)
        if (stripUrls.isEmpty()) {
            pendingDiscoveredUrlsByPath[path] = stripUrls
            return
        }
        val finalUrls = if (expected > 0 && stripUrls.size >= expected) stripUrls.take(expected) else stripUrls
        pendingDiscoveredUrlsByPath[path] = finalUrls
        val view = webView ?: return
        if (expected > 0 && finalUrls.size < expected) {
            view.post {
                if (path != currentPath) return@post
                renderNaturalStrip(view, path, finalUrls, "partial-$source")
            }
            Log.d(
                TAG,
                "ntk_browser_broker_natural_strip_update path=$path,count=${finalUrls.size}," +
                    "expected=$expected,source=$source"
            )
            return
        }
        if (isModernNaturalOwnerPath(path)) {
            view.post {
                if (path != currentPath) return@post
                renderNaturalStrip(
                    view,
                    path,
                    finalUrls,
                    if (expected > 0 && finalUrls.size >= expected) "full-$source" else "partial-$source"
                )
            }
            Log.d(
                TAG,
                "ntk_browser_broker_inpage_strip_update path=$path,count=${finalUrls.size}," +
                    "expected=$expected,source=$source"
            )
            return
        }
        val sig = "doc:" + finalUrls.joinToString("|") { controlledStripUrlSignature(it) }
        if (controlledStripPath == path && controlledStripSig == sig) return
        controlledStripPath = path
        controlledStripSig = sig
        controlledDocumentPath = path
        view.post {
            if (path != currentPath) return@post
            try {
                val html = controlledStripHtml(finalUrls, expected, "controlled-$source", documentUrl)
                view.stopLoading()
                view.loadDataWithBaseURL(documentUrl, html, "text/html", "UTF-8", documentUrl)
                Log.d(
                    TAG,
                    "ntk_browser_broker_controlled_document_load path=$path,count=${finalUrls.size}," +
                        "expected=$expected,source=$source"
                )
            } catch (e: Throwable) {
                currentListener?.onError(path, e.toString())
            }
        }
    }

    private fun renderNaturalStrip(view: WebView, path: String, urls: List<String>, source: String) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val json = JSONArray(urls).toString()
        val sourceJson = JSONObject.quote(source)
        val expected = currentExpectedImageCount.coerceAtLeast(0)
        try {
            view.evaluateJavascript(
                "try{if(window.__mvNtkRenderStrip)window.__mvNtkRenderStrip($json,$sourceJson,$expected);" +
                    "else window.__mvNtkPendingRenderStrip={urls:$json,reason:$sourceJson,expected:$expected};}catch(e){}",
                null
            )
        } catch (e: Throwable) {
            currentListener?.onError(path, e.toString())
        }
    }

    private fun strictReaderStripUrls(urls: List<String>, documentUrl: String): List<String> {
        val byPage = LinkedHashMap<Int, String>()
        val out = ArrayList<String>(urls.size)
        val seen = LinkedHashSet<String>()
        for (raw in urls) {
            val normalized = normalizeImageUrl(raw, documentUrl)
            if (
                normalized.isNotEmpty() &&
                looksLikeReaderImageUrl(normalized, null)
            ) {
                if (isProtectedApiImageUrl(normalized)) {
                    val scope = protectedApiImageScope(normalized)
                    if (scope != null && !scope.equals(currentPath, ignoreCase = true)) continue
                }
                val page = readerImagePageIndex(normalized)
                if (page != Int.MAX_VALUE) {
                    val existing = byPage[page]
                    if (existing == null || (isProtectedApiImageUrl(existing) && !isProtectedApiImageUrl(normalized))) {
                        byPage[page] = normalized
                    }
                } else if (seen.add(normalized)) {
                    out.add(normalized)
                }
            }
        }
        if (byPage.isNotEmpty()) {
            return byPage.toSortedMap().values.toList()
        }
        val sorted = out.sortedWith(compareBy<String> { readerImagePageIndex(it) }.thenBy { it })
        val genericByPage = LinkedHashMap<Int, String>()
        val unindexed = ArrayList<String>()
        for (url in sorted) {
            val page = readerImagePageIndex(url)
            if (page == Int.MAX_VALUE) {
                unindexed.add(url)
            } else if (!genericByPage.containsKey(page)) {
                genericByPage[page] = url
            }
        }
        return genericByPage.values.toList() + unindexed
    }

    private fun readerImagePageIndex(url: String): Int {
        return try {
            val normalized = url.replace("&amp;", "&")
            val uri = Uri.parse(normalized)
            val path = uri.path.orEmpty()
            if (path.equals("/api/m/i", ignoreCase = true)) {
                return uri.getQueryParameter("i")?.toIntOrNull() ?: Int.MAX_VALUE
            }
            Regex("""/(?:p)?(\d{1,5})\.(?:jpg|jpeg|png|webp|gif)$""", RegexOption.IGNORE_CASE)
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return (it - 1).coerceAtLeast(0) }
            Int.MAX_VALUE
        } catch (_: Throwable) {
            try {
                val parsed = URL(url.replace("&amp;", "&"))
                if (!parsed.path.equals("/api/m/i", ignoreCase = true)) return Int.MAX_VALUE
                parsed.query
                    .orEmpty()
                    .split('&')
                    .firstOrNull { it.startsWith("i=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.toIntOrNull()
                    ?: Int.MAX_VALUE
            } catch (_: Throwable) {
                Int.MAX_VALUE
            }
        }
    }

    private fun isProtectedApiImageUrl(url: String): Boolean {
        return try {
            Uri.parse(url.replace("&amp;", "&")).path.equals("/api/m/i", ignoreCase = true)
        } catch (_: Throwable) {
            url.contains("/api/m/i?", ignoreCase = true)
        }
    }

    private fun protectedApiImageScope(url: String): String? {
        return try {
            val uri = Uri.parse(url.replace("&amp;", "&"))
            if (!uri.path.equals("/api/m/i", ignoreCase = true)) return null
            val token = uri.getQueryParameter("a").orEmpty()
            val payload = token.substringBefore('.', "")
            if (payload.isEmpty()) return null
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(
                android.util.Base64.decode(
                    padded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
            Regex("\"scope\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
        } catch (_: Throwable) {
            null
        }
    }

    private fun controlledStripUrlSignature(url: String): String {
        return try {
            val uri = Uri.parse(url)
            if (!uri.path.equals("/api/m/i", ignoreCase = true)) return url
            val token = uri.getQueryParameter("a").orEmpty()
            val page = uri.getQueryParameter("i").orEmpty()
            if (token.isEmpty() || page.isEmpty()) return url
            "${uri.scheme}://${uri.host}${uri.path}?a=$token&i=$page"
        } catch (_: Throwable) {
            url
        }
    }

    private fun controlledStripHtml(
        urls: List<String>,
        expected: Int,
        source: String,
        documentUrl: String
    ): String {
        val json = JSONArray(urls).toString()
        val sourceJson = JSONObject.quote(source)
        val docJson = JSONObject.quote(documentUrl)
        val expectedCount = expected.coerceAtLeast(0)
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>
                html,body{margin:0;padding:0;background:#111;overflow-anchor:none;}
                img{display:block;width:100%;height:auto;margin:0;padding:0;border:0;}
              </style>
            </head>
            <body>
              <div id="strip"></div>
              <script>
              (function(){
                var urls=$json;
                var expected=$expectedCount;
                var source=$sourceJson;
                var documentUrl=$docJson;
                var strip=document.getElementById('strip');
                var loaded=0,decoded=0,failed=0;
                var firstSent=false,readySent=false,maxObservedHeight=0;
                function scrollState(){
                  try{
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var vh=innerHeight||de.clientHeight||bd.clientHeight||0;
                    var sh=Math.max(de.scrollHeight||0,bd.scrollHeight||0,de.offsetHeight||0,bd.offsetHeight||0,vh);
                    if(sh>maxObservedHeight)maxObservedHeight=sh;
                    sh=Math.max(sh,maxObservedHeight);
                    var sy=pageYOffset||de.scrollTop||bd.scrollTop||0;
                    NtkBrowserBridge.onScroll(JSON.stringify({href:String(location.href||''),scrollY:Math.max(0,Math.round(sy)),viewport:Math.max(0,Math.round(vh)),content:Math.max(0,Math.round(sh))}));
                    if(window.__mvNtkPostCoverage)window.__mvNtkPostCoverage('scroll');
                  }catch(_){}
                }
                function sendFirst(im){
                  try{
                    if(firstSent)return;
                    if(!im||!im.complete||im.naturalWidth<=0||im.naturalHeight<=0)return;
                    firstSent=true;
                    NtkBrowserBridge.onFirstDrawable(JSON.stringify({href:String(location.href||''),src:im.currentSrc||im.src||''}));
                  }catch(_){}
                }
                function maybeReady(){
                  try{
                    if(readySent)return;
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-progress total '+urls.length+' loaded '+loaded+' decoded '+decoded+' failed '+failed+' source '+source,cloudflare:false,stripCount:urls.length}));}catch(_){}
                    if(expected>0&&urls.length<expected)return;
                    if(loaded+failed<urls.length)return;
                    if(failed>0)return;
                    if(decoded<loaded)return;
                    readySent=true;
                    scrollState();
                    NtkBrowserBridge.onViewportReady(JSON.stringify({href:String(location.href||''),strip:true,total:urls.length,loaded:loaded,decoded:decoded,failed:failed,source:source,documentUrl:documentUrl}));
                  }catch(_){}
                }
                urls.forEach(function(u,i){
                  var im=document.createElement('img');
                  var settled=false;
                  im.loading='eager';
                  im.decoding='async';
                  try{im.fetchPriority='high';}catch(_){}
                  im.onload=function(){
                    if(settled)return;
                    settled=true;
                    loaded++;
                    if(i===0)sendFirst(im);
                    scrollState();
                    try{
                      if(im.decode){
                        im.decode().then(function(){decoded++;maybeReady();}).catch(function(){decoded++;maybeReady();});
                      }else{
                        decoded++;
                        maybeReady();
                      }
                    }catch(_){
                      decoded++;
                      maybeReady();
                    }
                  };
                  im.onerror=function(){if(settled)return;settled=true;failed++;scrollState();maybeReady();};
                  im.src=u;
                  strip.appendChild(im);
                  setTimeout(function(){if(settled)return;settled=true;failed++;try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-timeout index '+i+' src '+String(u||'').slice(0,90),cloudflare:false,stripCount:urls.length}));}catch(_){}scrollState();maybeReady();},4500);
                });
                addEventListener('scroll',scrollState,{passive:true});
                addEventListener('resize',scrollState,{passive:true});
                setTimeout(scrollState,0);
                setTimeout(scrollState,80);
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun notifyFirstDrawable() {
        if (firstDrawableSent) return
        firstDrawableSent = true
        firstDrawableReadyPath = currentPath
        Log.d(TAG, "ntk_browser_broker_first_drawable path=$currentPath")
        currentListener?.onFirstDrawable(currentPath)
    }

    private fun notifyViewportReady() {
        if (viewportReadySent) return
        viewportReadySent = true
        viewportReadyPath = currentPath
        firstDrawableReadyPath = currentPath
        val readyPath = currentPath
        val listener = currentListener
        val view = webView
        val notify = Runnable {
            if (view != null && !currentVisible) {
                view.contentDescription = READY_DESCRIPTION
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            Log.d(TAG, "ntk_browser_broker_viewport_ready path=$readyPath")
            listener?.onViewportReady(readyPath)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify.run()
        } else {
            view?.post(notify) ?: notify.run()
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun onImages(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) {
                    Log.d(TAG, "ntk_browser_broker_images_stale path=$currentPath,href=${json.optString("href", "").take(120)}")
                    return
                }
                val urlsJson = json.optJSONArray("urls") ?: JSONArray()
                val urls = ArrayList<String>(urlsJson.length())
                for (i in 0 until urlsJson.length()) {
                    urls.add(urlsJson.optString(i, ""))
                }
                recordImages(
                    urls,
                    json.optString("reason", "dom"),
                    json.optString("href", ""),
                    json.optBoolean("cloudflare", false),
                    true
                )
            } catch (e: Throwable) {
                currentListener?.onError(currentPath, e.toString())
            }
        }

        @JavascriptInterface
        fun onState(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val cf = json.optBoolean("cloudflare", false)
                Log.d(
                    TAG,
                    "ntk_browser_broker_state path=$currentPath,cf=$cf," +
                        "strip=${json.optInt("stripCount", -1)}," +
                        "api=${json.optString("api", "")}," +
                        "status=${json.optInt("status", -1)}," +
                        "source=${json.optString("source", "")}," +
                        "title=${json.optString("title", "").take(80)}," +
                        "body=${json.optString("body", "").take(180)}"
                )
                currentListener?.onState(
                    currentPath,
                    cf,
                    json.optString("title", ""),
                    json.optString("body", "")
                )
                if (cf) currentListener?.onNeedsUserVerification(currentPath)
            } catch (_: Throwable) {
            }
        }

        @JavascriptInterface
        fun onFirstDrawable(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) {
                    Log.d(TAG, "ntk_browser_broker_first_drawable_stale path=$currentPath,href=${json.optString("href", "").take(120)}")
                    return
                }
                val href = json.optString(
                    "href",
                    currentDocumentUrl.ifBlank { currentBaseUrl + currentPath }
                )
                val src = normalizeImageUrl(json.optString("src", ""), href)
                if (src.isNotEmpty() && looksLikeReaderImageUrl(src, null)) {
                    Log.d(TAG, "ntk_browser_broker_first_drawable_real path=$currentPath,src=${src.take(120)}")
                    notifyFirstDrawable()
                } else {
                    Log.d(TAG, "ntk_browser_broker_first_drawable_deferred path=$currentPath,src=${src.take(120)}")
                }
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_first_drawable_error path=$currentPath,$e")
            }
        }

        @JavascriptInterface
        fun onViewportReady(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) {
                    Log.d(TAG, "ntk_browser_broker_viewport_ready_stale path=$currentPath,href=${json.optString("href", "").take(120)}")
                    return
                }
                val expected = currentExpectedImageCount.coerceAtLeast(0)
                if (isModernNaturalOwnerPath(currentPath) && expected > 0) {
                    val strip = json.optBoolean("strip", false)
                    val total = json.optInt("total", 0)
                    val loaded = json.optInt("loaded", 0)
                    val decoded = json.optInt("decoded", loaded)
                    val failed = json.optInt("failed", 0)
                    val allDecoded = strip &&
                        total >= expected &&
                        loaded >= expected &&
                        decoded >= expected &&
                        failed == 0
                    if (!allDecoded) {
                        Log.d(
                            TAG,
                            "ntk_browser_broker_viewport_ready_deferred path=$currentPath," +
                                "expected=$expected,total=$total,loaded=$loaded," +
                                "decoded=$decoded,failed=$failed,strip=$strip"
                        )
                        return
                    }
                    allDecodedReadyPath = currentPath
                }
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_viewport_ready_parse_error path=$currentPath,$e")
                return
            }
            notifyViewportReady()
        }

        @JavascriptInterface
        fun onScroll(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val key = currentPath
                if (key.isEmpty()) return
                val viewport = json.optInt("viewport", 0).coerceAtLeast(0)
                val rawScrollY = json.optInt("scrollY", 0).coerceAtLeast(0)
                val now = SystemClock.elapsedRealtime()
                val frozen = now < scrollFreezeUntilMs
                val rawContent = json.optInt("content", 0).coerceAtLeast(viewport)
                val content = if (frozen && scrollFreezeContentHeight > 0) {
                    scrollFreezeContentHeight.coerceAtLeast(viewport)
                } else {
                    rawContent
                }
                val maxScroll = (content - viewport).coerceAtLeast(0)
                val scrollY = if (frozen) scrollFreezeY else rawScrollY
                val nearEnd = maxScroll > 0 && scrollY >= maxScroll - 96
                val snapshot = ScrollSnapshot(
                    key,
                    scrollY.coerceAtMost(maxScroll),
                    viewport,
                    content,
                    maxScroll,
                    nearEnd,
                    SystemClock.elapsedRealtime()
                )
                latestScrollSnapshot = snapshot
                currentListener?.onScroll(snapshot)
            } catch (e: Throwable) {
                currentListener?.onError(currentPath, e.toString())
            }
        }

        @JavascriptInterface
        fun onCoverage(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val key = currentPath
                if (key.isEmpty()) return
                val snapshot = VisibleCoverageSnapshot(
                    key,
                    json.optInt("viewport", 0).coerceAtLeast(0),
                    json.optInt("drawable", 0).coerceAtLeast(0),
                    json.optInt("missing", 0).coerceAtLeast(0),
                    json.optInt("drawableItems", 0).coerceAtLeast(0),
                    json.optInt("totalItems", 0).coerceAtLeast(0),
                    json.optInt("loading", 0).coerceAtLeast(0),
                    json.optInt("errors", 0).coerceAtLeast(0),
                    json.optInt("pageCount", 0).coerceAtLeast(0),
                    SystemClock.elapsedRealtime()
                )
                latestCoverageSnapshot = snapshot
                currentListener?.onCoverage(snapshot)
            } catch (e: Throwable) {
                currentListener?.onError(currentPath, e.toString())
            }
        }

        @JavascriptInterface
        fun onAckState(value: String?) {
            val text = value.orEmpty()
            if (!callbackTextMatchesCurrentPath(text)) {
                Log.d(TAG, "ntk_foreground_ack_state_stale path=$currentPath,body=${text.take(180)}")
                return
            }
            Log.d(TAG, "ntk_foreground_ack_state path=$currentPath,body=${text.take(240)}")
            promoteForegroundTrust("ack-state")
            recordForegroundStrictAckProof(text, "ack-state")
            currentListener?.onState(currentPath, false, "", "foreground-ack-state ${text.take(140)}")
        }

        @JavascriptInterface
        fun onAckProof(value: String?) {
            val text = value.orEmpty()
            if (!callbackTextMatchesCurrentPath(text)) {
                Log.d(TAG, "ntk_foreground_ack_proof_stale path=$currentPath,body=${text.take(180)}")
                return
            }
            Log.d(TAG, "ntk_foreground_ack_proof path=$currentPath,body=${text.take(240)}")
            promoteForegroundTrust("ack-proof")
            recordForegroundStrictAckProof(text, "ack-proof")
        }

        @JavascriptInterface
        fun onViewerImages(value: String?) {
            try {
                if (!callbackTextMatchesCurrentPath(value.orEmpty())) {
                    Log.d(TAG, "ntk_foreground_viewer_images_stale path=$currentPath,body=${value.orEmpty().take(180)}")
                    return
                }
                val json = JSONObject(value.orEmpty())
                val body = json.opt("body")
                val urls = ArrayList<String>()
                collectReaderUrls(body, urls, currentDocumentUrl.ifBlank { currentBaseUrl + currentPath })
                if (urls.isEmpty()) {
                    Log.d(TAG, "ntk_foreground_viewer_images_empty path=$currentPath,body=${value.orEmpty().take(180)}")
                    return
                }
                Log.d(TAG, "ntk_foreground_images_publish path=$currentPath,count=${urls.size}")
                publishAuthoritativeImageUrls(currentPath, urls, "foreground-viewer-images")
            } catch (e: Throwable) {
                currentListener?.onError(currentPath, e.toString())
            }
        }

        private fun promoteForegroundTrust(source: String): Boolean {
            val key = currentPath
            if (key.isEmpty()) return false
            val url = currentBaseUrl + key
            val cookieHeader = CookieManager.getInstance().getCookie(url).orEmpty()
            val trusted = NtkWebViewFallbackManager.rememberForegroundTrustedCookies(
                key,
                cookieHeader,
                "broker-$source"
            )
            if (trusted) {
                webView?.post {
                    try {
                        webView?.evaluateJavascript(
                            "try{window.__ntkTrustedBrowserState=1;if(window.__mvNtkScan)window.__mvNtkScan('foreground-trusted');}catch(e){}",
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }
                currentListener?.onState(key, false, "", "foreground-trusted")
            }
            return trusted
        }

        private fun recordForegroundStrictAckProof(value: String, source: String): Boolean {
            val key = normalizePath(currentPath)
            if (key.isEmpty() || value.isBlank()) return false
            try {
                val json = JSONObject(value)
                val detail = json.optJSONObject("detail")
                val scope = normalizePath(
                    json.optString("scope", detail?.optString("scope", "") ?: "")
                        .ifBlank { key }
                )
                val proof200 = json.optBoolean("proof200", false) ||
                    detail?.optBoolean("proof200", false) == true ||
                    json.optBoolean("foregroundHybridAckReady", false) &&
                    detail?.optBoolean("proof200", false) == true
                if (!proof200 || !scope.equals(key, ignoreCase = true)) return false
                MainApplication.getHttpClient().rememberExternalNtkServerAckSuccess(
                    key,
                    "foreground-ack-trusted"
                )
                Log.d(TAG, "ntk_foreground_ack_strict_recorded path=$key,source=$source")
                return true
            } catch (_: Throwable) {
                return false
            }
        }

        private fun collectReaderUrls(value: Any?, out: MutableList<String>, documentUrl: String) {
            when (value) {
                null -> return
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) collectReaderUrls(value.opt(keys.next()), out, documentUrl)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) collectReaderUrls(value.opt(i), out, documentUrl)
                }
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.length >= 2) {
                        try {
                            when (trimmed.first()) {
                                '{' -> {
                                    collectReaderUrls(JSONObject(trimmed), out, documentUrl)
                                    return
                                }
                                '[' -> {
                                    collectReaderUrls(JSONArray(trimmed), out, documentUrl)
                                    return
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    val normalized = normalizeImageUrl(trimmed, documentUrl)
                    if (normalized.isNotEmpty() && looksLikeReaderImageUrl(normalized, null)) {
                        out.add(normalized)
                    }
                }
            }
        }
    }

    private fun callbackTextMatchesCurrentPath(value: String): Boolean {
        val key = normalizePath(currentPath)
        if (key.isEmpty() || value.isBlank()) return true
        val matches = Regex("""/(?:manhwa|webtoon)/[^"'\\\s?&<>]+/[^"'\\\s?&<>]+""", RegexOption.IGNORE_CASE)
            .findAll(value.replace("\\/", "/"))
            .map { normalizePath(it.value) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (matches.isEmpty()) return true
        return matches.any { it.equals(key, ignoreCase = true) }
    }

    private fun discoveryScript(): String {
        val expectedCount = currentExpectedImageCount.coerceAtLeast(0)
        val visibleLiteral = if (currentVisible) "true" else "false"
        return """
            (function(){
              try{
                window.__mvNtkBrokerVisible=$visibleLiteral;
                if(window.__mvNtkBrowserDiscoveryInstalled){
                  window.__mvNtkFirstDrawable=0;
                  window.__mvNtkViewportReady=0;
                  try{if(window.__mvNtkScan)window.__mvNtkScan('reattach');}catch(_){}
                  return;
                }
                window.__mvNtkBrowserDiscoveryInstalled=1;
                window.__ntkNaturalSamePageOwner=1;
                function abs(u){try{return new URL(String(u||''),location.href).href;}catch(_){return '';}}
                ${naturalCaptureScript(false)}
                var mvNtkExpected=$expectedCount;
                var preloaded={};
                function preload(urls){
                  try{
                    (urls||[]).forEach(function(u){
                      u=abs(u);if(!u||preloaded[u])return;preloaded[u]=1;
                      var im=new Image();
                      try{im.loading='eager';im.decoding='async';im.fetchPriority='high';}catch(_){}
                      im.onload=function(){checkDrawable();checkViewport();scrollState();};
                      im.onerror=function(){checkViewport();scrollState();};
                      im.src=u;
                    });
                  }catch(_){}
                }
                window.__mvNtkPrime=function(urls,reason){
                  try{
                    if(isCf()){
                      window.__mvNtkPendingPrime=urls||window.__mvNtkPendingPrime||[];
                      return;
                    }
                    if((urls||[]).length>=1){
                      renderStrip(urls||[],'prime-'+String(reason||''),mvNtkExpected);
                    }
                    preload(urls||[]);
                    checkDrawable();
                    checkViewport();
                    scrollState();
                  }catch(_){}
                };
                function flushPendingPrime(){
                  try{
                    if(isCf())return;
                    if(window.__mvNtkPendingPrime){
                      var pending=window.__mvNtkPendingPrime;
                      window.__mvNtkPendingPrime=null;
                      window.__mvNtkPrime(pending,'pending');
                    }
                  }catch(_){}
                }
                try{flushPendingPrime();}catch(_){}
                var viewerApiStarted=0,viewerApiDone=0,viewerApiNoDataLogged=0;
                function cookieValue(name){
                  try{
                    var parts=String(document.cookie||'').split(';');
                    for(var i=0;i<parts.length;i++){
                      var p=parts[i].trim(),eq=p.indexOf('=');
                      if(eq>0&&p.slice(0,eq)===name)return decodeURIComponent(p.slice(eq+1));
                    }
                  }catch(_){}
                  return '';
                }
                function sameViewerScope(v){
                  try{
                    v=String(v||'');
                    var p=String(location.pathname||'');
                    return v===p||decodeURIComponent(v)===p;
                  }catch(_){return false;}
                }
                function foregroundAckProofed(){
                  try{
                    if(sameViewerScope(window.__ntk_ad_ack_proof_200))return true;
                    var l=window.__ntk_ad_ack_last;
                    if(l&&l.proof200&&sameViewerScope(l.scope))return true;
                    if(cookieValue('ad_ack_c')||cookieValue('ad_guard_l'))return true;
                  }catch(_){}
                  return false;
                }
                function waitForegroundAckProof(ms){
                  return new Promise(function(resolve){
                    try{
                      if(foregroundAckProofed())return resolve(true);
                      var done=false;
                      function finish(v){
                        if(done)return;
                        done=true;
                        try{clearTimeout(to);clearInterval(iv);removeEventListener('ntk-ad-ack-ready',ev);removeEventListener('ntk-ack-rearm',ev);}catch(_){}
                        resolve(!!v);
                      }
                      function ev(e){
                        try{
                          if(foregroundAckProofed()||(e&&e.detail&&sameViewerScope(e.detail.scope)))finish(foregroundAckProofed());
                        }catch(_){}
                      }
                      var to=setTimeout(function(){finish(foregroundAckProofed());},Math.max(1,Number(ms||1)));
                      var iv=setInterval(function(){if(foregroundAckProofed())finish(true);},80);
                      addEventListener('ntk-ad-ack-ready',ev);
                      addEventListener('ntk-ack-rearm',ev);
                    }catch(_){resolve(foregroundAckProofed());}
                  });
                }
                async function ensureForegroundAckBeforeViewerApi(reason){
                  try{
                    if(foregroundAckProofed())return true;
                    if(window.__mvNtkForegroundAckGateTask)return await window.__mvNtkForegroundAckGateTask;
                    window.__mvNtkForegroundAckGateTask=(async function(){
                      try{
                        NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-ack-gate-start '+String(reason||''),cloudflare:false}));
                      }catch(_){}
                      try{dispatchEvent(new CustomEvent('ntk-ack-rearm',{detail:{scope:String(location.pathname||''),reason:'viewer-api-preflight'}}));}catch(_){}
                      try{
                        var fn=(typeof window.__ntkDirectAckStable==='function')?window.__ntkDirectAckStable:((typeof directAck==='function')?directAck:null);
                        if(fn&&!window.__mvNtkForegroundAckGateCalling){
                          window.__mvNtkForegroundAckGateCalling=1;
                          Promise.resolve().then(function(){return fn();}).catch(function(){}).finally(function(){try{window.__mvNtkForegroundAckGateCalling=0;}catch(_){}});
                        }
                      }catch(_){}
                      var ok=await waitForegroundAckProof(window.__ntkBrowserKeyReady?250:300);
                      try{
                        NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-ack-gate-done '+!!ok,cloudflare:false}));
                      }catch(_){}
                      return ok;
                    })();
                    try{return await window.__mvNtkForegroundAckGateTask;}finally{try{window.__mvNtkForegroundAckGateTask=null;}catch(_){}}
                  }catch(_){return foregroundAckProofed();}
                }
                function ub64(bytes){
                  try{
                    var arr=bytes instanceof Uint8Array?bytes:new Uint8Array(bytes),s='';
                    for(var i=0;i<arr.length;i++)s+=String.fromCharCode(arr[i]);
                    return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+${'$'}/,'');
                  }catch(_){return '';}
                }
                async function hmacWeb(key,msg){
                  try{
                    if(!window.crypto||!crypto.subtle||!window.TextEncoder)return '';
                    var enc=new TextEncoder();
                    var k=await crypto.subtle.importKey('raw',enc.encode(String(key||'')),{name:'HMAC',hash:'SHA-256'},false,['sign']);
                    return ub64(await crypto.subtle.sign('HMAC',k,enc.encode(String(msg||''))));
                  }catch(_){return '';}
                }
                function parseViewerData(){
                  try{
                    var text='';
                    if(window.__mvNtkInjectedViewerData){
                      text=String(window.__mvNtkInjectedViewerData||'');
                    }
                    var el=!text?document.getElementById('theme-viewer-data'):null;
                    if(!text)text=el?String(el.textContent||''):'';
                    if(!text){
                      var scripts=[].slice.call(document.scripts||[]);
                      for(var si=0;si<scripts.length;si++){
                        var st=String(scripts[si].textContent||'');
                        if(st.indexOf('imageApiPath')>=0&&st.indexOf('token')>=0){
                          text=st;
                          break;
                        }
                      }
                    }
                    if(!text){
                      var m=String(document.documentElement&&document.documentElement.innerHTML||'').match(/<script[^>]*id=["']theme-viewer-data["'][^>]*>([\s\S]*?)<\/script>/i);
                      if(m&&m[1])text=m[1];
                    }
                    if(!text){
                      var html=String(document.documentElement&&document.documentElement.innerHTML||'');
                      var idx=html.indexOf('imageApiPath');
                      if(idx>=0){
                        var start=Math.max(0,html.lastIndexOf('{',idx));
                        var end=html.indexOf('</script>',idx);
                        if(start>=0&&end>start)text=html.slice(start,end);
                      }
                    }
                    if(!text)return null;
                    text=String(text||'').trim();
                    if(text.charAt(0)!=='{'&&text.indexOf('imageApiPath')>=0){
                      var tm=text.match(/<script[^>]*id=["']theme-viewer-data["'][^>]*>([\s\S]*?)<\/script>/i);
                      if(tm&&tm[1])text=String(tm[1]||'').trim();
                      else{
                        var tidx=text.indexOf('imageApiPath');
                        var tstart=Math.max(0,text.lastIndexOf('{',tidx));
                        var tend=text.indexOf('</script>',tidx);
                        if(tstart>=0&&tend>tstart)text=text.slice(tstart,tend).trim();
                      }
                    }
                    if(text.indexOf('<')>=0)text=text.replace(/<[^>]+>/g,'');
                    var j=JSON.parse(text);
                    if(j&&!j.token)j.token=j.imagesToken||j.imageToken||j.viewerToken||j.apiToken||'';
                    if(!j||!j.token||!j.imageApiPath||!j.sourceWorkId||!j.episodeId)return null;
                    return j;
                  }catch(_){return null;}
                }
                function collectApiUrls(node,out){
                  try{
                    if(!node)return;
                    if(typeof node==='string'){
                      if(readerLike(node))out.push(node);
                      return;
                    }
                    if(Array.isArray(node)){
                      for(var i=0;i<node.length;i++)collectApiUrls(node[i],out);
                      return;
                    }
                    if(typeof node==='object'){
                      ['url','src','imageUrl','image','href','file','path'].forEach(function(k){
                        if(typeof node[k]==='string')collectApiUrls(node[k],out);
                      });
                      if(node.urls)collectApiUrls(node.urls,out);
                      if(node.srcCandidates)collectApiUrls(node.srcCandidates,out);
                      if(node.images&&node.images!==node)collectApiUrls(node.images,out);
                      if(node.data&&node.data!==node)collectApiUrls(node.data,out);
                    }
                  }catch(_){}
                }
                async function tryViewerApi(reason){
                  try{
                    if(viewerApiDone||viewerApiStarted||isCf())return;
                    var data=parseViewerData();
                    if(!data){
                      if(!viewerApiNoDataLogged){
                        viewerApiNoDataLogged=1;
                        try{
                          var html=String(document.documentElement&&document.documentElement.innerHTML||'');
                          NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-no-data html '+html.length+' hasImageApi '+(html.indexOf('imageApiPath')>=0)+' scripts '+((document.scripts&&document.scripts.length)||0),cloudflare:isCf()}));
                        }catch(_){}
                      }
                      return;
                    }
                    if(!foregroundAckProofed()&&!window.__ntkBrowserKeyReady&&!window.NtkQuicBridge){
                      if(!window.__mvNtkViewerApiAckPending){
                        window.__mvNtkViewerApiAckPending=1;
                        ensureForegroundAckBeforeViewerApi(reason).then(function(ok){
                          try{window.__mvNtkViewerApiAckPending=0;}catch(_){}
                          if(ok)tryViewerApi('after-ack-gate-'+String(reason||''));
                        }).catch(function(){
                          try{window.__mvNtkViewerApiAckPending=0;}catch(_){}
                        });
                      }
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-wait-ack-proof '+String(reason||''),cloudflare:false}));}catch(_){}
                      return;
                    }
                    viewerApiStarted=1;
                    NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-start '+String(reason||'')+' pages '+((data.images&&data.images.length)||0),cloudflare:false}));
                    var nv=cookieValue('nv');
                    if(!nv||(String(nv).split('.')[0]||'').length<40){
                      try{
                        var nvCtrl=null,nvTid=0;
                        try{nvCtrl=new AbortController();nvTid=setTimeout(function(){try{nvCtrl.abort();}catch(_){}},700);}catch(_){}
                        await fetch('/api/nv-issue',{method:'POST',credentials:'same-origin',cache:'no-store',signal:nvCtrl&&nvCtrl.signal});
                        try{if(nvTid)clearTimeout(nvTid);}catch(_){}
                      }catch(nvFetchErr){
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-nv-fetch-error '+String(nvFetchErr).slice(0,100),cloudflare:false}));}catch(_){}
                      }
                      if(window.NtkQuicBridge&&(!cookieValue('nv')||(String(cookieValue('nv')).split('.')[0]||'').length<40)){
                        try{
                          function encNv(s){try{return btoa(unescape(encodeURIComponent(String(s||''))));}catch(_){return '';}}
                          var nvRaw=window.NtkQuicBridge.request(abs('/api/nv-issue'),'POST',JSON.stringify({'accept':'application/json','origin':String(location.origin||''),'referer':String(location.href||'')}),encNv(''));
                          var nvObj={};try{nvObj=JSON.parse(String(nvRaw||'{}'));}catch(_){}
                          try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-nv-bridge '+(nvObj.status||0),cloudflare:false}));}catch(_){}
                        }catch(nvBridgeErr){
                          try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-nv-bridge-error '+String(nvBridgeErr).slice(0,100),cloudflare:false}));}catch(_){}
                        }
                      }
                      nv=cookieValue('nv');
                    }
                    if(!nv||(String(nv).split('.')[0]||'').length<40){
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-missing-nv',cloudflare:false}));}catch(_){}
                      viewerApiStarted=0;
                      return;
                    }
                    var nonceBytes=new Uint8Array(24);
                    crypto.getRandomValues(nonceBytes);
                    var nonce=ub64(nonceBytes);
                    var imageToken=String(data.token||data.imagesToken||data.imageToken||data.viewerToken||data.apiToken||'');
                    var proof=await hmacWeb(nv,imageToken+'.'+nonce+'.'+String(navigator.userAgent||''));
                    if(!proof){
                      viewerApiStarted=0;
                      return;
                    }
                    var body={
                      workId:String(data.sourceWorkId||''),
                      episodeId:String(data.episodeId||''),
                      token:imageToken,
                      nonce:nonce,
                      proof:proof
                    };
                    var endpoint=String(data.imageApiPath||'');
                    var headers={'content-type':'application/json','accept':'application/json','x-images-client':'viewer-v1','origin':String(location.origin||''),'referer':String(location.href||'')};
                    try{
                      if(window.NtkQuicBridge){
                        var page=String(location.href||'');
                        var keyRaw='{}';
                        if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent) {
                          keyRaw=window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(page,String(navigator.userAgent||''));
                        } else if(window.NtkQuicBridge.recentViewerBrowserKeyId) {
                          keyRaw=window.NtkQuicBridge.recentViewerBrowserKeyId(page);
                        }
                        var keyJson={};
                        try{keyJson=JSON.parse(String(keyRaw||'{}'));}catch(_){}
                        if(keyJson&&keyJson.keyId)body.requestKeyId=String(keyJson.keyId||'');
                        var bodyTextForSign=JSON.stringify(body);
                        var sigRaw=window.NtkQuicBridge.signViewerRequestFormat?
                          window.NtkQuicBridge.signViewerRequestFormat('POST',endpoint,String(data.scopePath||location.pathname||''),bodyTextForSign,'p1363'):
                          window.NtkQuicBridge.signViewerRequest('POST',endpoint,String(data.scopePath||location.pathname||''),bodyTextForSign);
                        var sig={};
                        try{sig=JSON.parse(String(sigRaw||'{}'));}catch(_){}
                        if(sig&&sig.bodyText){
                          bodyTextForSign=String(sig.bodyText||bodyTextForSign);
                          try{body=JSON.parse(bodyTextForSign||'{}');}catch(_){}
                        }
                        if(sig&&sig.headers){
                          Object.keys(sig.headers).forEach(function(k){headers[k]=sig.headers[k];});
                        }
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-key '+!!(keyJson&&keyJson.keyId)+' sign '+!!(sig&&sig.ok)+' keyHeader '+!!headers['x-ntk-key-id']+' tokenLen '+imageToken.length+' endpoint '+endpoint,cloudflare:false}));}catch(_){}
                      }
                    }catch(signErr){
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-sign-error '+String(signErr).slice(0,120),cloudflare:false}));}catch(_){}
                    }
                    var bodyText=JSON.stringify(body);
                    var status=0,text='',json={};
                    if(window.NtkQuicBridge&&window.NtkQuicBridge.request){
                      try{
                        function encTextFirst(s){try{return btoa(unescape(encodeURIComponent(String(s||''))));}catch(_){return '';}}
                        function decTextFirst(x){try{return decodeURIComponent(escape(atob(x||'')));}catch(e){try{return atob(x||'');}catch(_){return '';}}}
                        try{var dcFirst=String(document.cookie||''); if(dcFirst) headers['x-ntk-document-cookie']=dcFirst;}catch(_){}
                        var rawFirst=window.NtkQuicBridge.request(abs(endpoint),'POST',JSON.stringify(headers),encTextFirst(bodyText));
                        var bridgeFirst={};
                        try{bridgeFirst=JSON.parse(String(rawFirst||'{}'));}catch(_){}
                        var bridgeTextFirst=decTextFirst(bridgeFirst.bodyBase64||'');
                        var bridgeJsonFirst={};
                        try{bridgeJsonFirst=JSON.parse(bridgeTextFirst||'{}');}catch(_){}
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-bridge-first '+(bridgeFirst.status||0)+' ok '+!!bridgeJsonFirst.ok+' count '+((bridgeJsonFirst.images&&bridgeJsonFirst.images.length)||0)+' text '+String(bridgeTextFirst||'').slice(0,120),cloudflare:false}));}catch(_){}
                        if((bridgeFirst.status||0)>0){
                          status=bridgeFirst.status||0;
                          text=bridgeTextFirst;
                          json=bridgeJsonFirst;
                        }
                      }catch(bridgeFirstErr){
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-bridge-first-error '+String(bridgeFirstErr).slice(0,140),cloudflare:false}));}catch(_){}
                      }
                    }
                    if(status<200||status>=300||!json||!json.ok)
                    try{
                      var ctrl=null,tid=0;
                      try{ctrl=new AbortController();tid=setTimeout(function(){try{ctrl.abort();}catch(_){}},2200);}catch(_){}
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-direct-start tokenLen '+imageToken.length+' bodyLen '+bodyText.length+' endpoint '+endpoint,cloudflare:false}));}catch(_){}
                      var resp=await fetch(endpoint,{
                        method:'POST',
                        credentials:'same-origin',
                        cache:'no-store',
                        headers:headers,
                        body:bodyText,
                        signal:ctrl&&ctrl.signal
                      });
                      try{if(tid)clearTimeout(tid);}catch(_){}
                      status=resp.status||0;
                      text=await resp.text().catch(function(){return '';});
                      try{json=JSON.parse(text||'{}');}catch(_){}
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-direct '+status+' ok '+!!json.ok+' len '+String(text||'').length+' text '+String(text||'').slice(0,120),cloudflare:false}));}catch(_){}
                    }catch(fetchErr){
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-fetch-error '+String(fetchErr).slice(0,120),cloudflare:false}));}catch(_){}
                    }
                    if((status<200||status>=300||!json||!json.ok)&&window.NtkQuicBridge&&window.NtkQuicBridge.request){
                      try{
                        function encText(s){try{return btoa(unescape(encodeURIComponent(String(s||''))));}catch(_){return '';}}
                        function decText(x){try{return decodeURIComponent(escape(atob(x||'')));}catch(e){try{return atob(x||'');}catch(_){return '';}}}
                        try{var dc=String(document.cookie||''); if(dc) headers['x-ntk-document-cookie']=dc;}catch(_){}
                        var raw=window.NtkQuicBridge.request(abs(endpoint),'POST',JSON.stringify(headers),encText(bodyText));
                        var bridge={};
                        try{bridge=JSON.parse(String(raw||'{}'));}catch(_){}
                        var bridgeText=decText(bridge.bodyBase64||'');
                        var bridgeJson={};
                        try{bridgeJson=JSON.parse(bridgeText||'{}');}catch(_){}
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-bridge '+(bridge.status||0)+' ok '+!!bridgeJson.ok+' count '+((bridgeJson.images&&bridgeJson.images.length)||0)+' text '+String(bridgeText||'').slice(0,120),cloudflare:false}));}catch(_){}
                        if((bridge.status||0)>0){
                          status=bridge.status||0;
                          text=bridgeText;
                          json=bridgeJson;
                        }
                      }catch(bridgeErr){
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-bridge-error '+String(bridgeErr).slice(0,140),cloudflare:false}));}catch(_){}
                      }
                    }
                    var urls=[];
                    collectApiUrls(json.images||json.data||json,urls);
                    var seenApi={},unique=[];
                    urls.forEach(function(u){
                      u=abs(u);
                      if(readerLike(u)&&!seenApi[u]){seenApi[u]=1;unique.push(u);}
                    });
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api '+status+' ok '+!!json.ok+' count '+unique.length+' text '+String(text||'').slice(0,120),cloudflare:false}));}catch(_){}
                    if(status>=200&&status<300&&unique.length){
                      viewerApiDone=1;
                      post(unique,'viewer-api-'+String(reason||''));
                      renderStrip(unique,'viewer-api-'+String(reason||''),mvNtkExpected);
                    }else{
                      if((status===401||status===403||status===428)&&!window.__mvNtkViewerApiForegroundAckRetry){
                        window.__mvNtkViewerApiForegroundAckRetry=1;
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-foreground-ack-retry '+status,cloudflare:false}));}catch(_){}
                        try{
                          if(typeof window.__ntkDirectAckStable==='function')await window.__ntkDirectAckStable();
                          else if(typeof directAck==='function')await directAck();
                          else if(typeof guardAck==='function')await guardAck();
                        }catch(ackRetryErr){
                          try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-foreground-ack-error '+String(ackRetryErr).slice(0,120),cloudflare:false}));}catch(_){}
                        }
                        viewerApiStarted=0;
                        tryViewerApi('after-foreground-ack-'+status);
                        return;
                      }
                      viewerApiStarted=0;
                    }
                  }catch(e){
                    viewerApiStarted=0;
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-error '+String(e).slice(0,160),cloudflare:false}));}catch(_){}
                  }
                }
                function readerLike(u){
                  try{
                    u=String(u||'').toLowerCase();
                    if(!/^https?:\/\//.test(u))return false;
                    if(/\/api\/m\/i(?:[?#].*)?$/.test(u))return true;
                    if(!/\.(jpg|jpeg|png|webp)([?#].*)?$/.test(u))return false;
                    if(u.indexOf('banner')>=0||u.indexOf('advert')>=0||u.indexOf('sponsor')>=0||u.indexOf('favicon')>=0||u.indexOf('logo')>=0||u.indexOf('captcha')>=0||u.indexOf('turnstile')>=0)return false;
                    if(u.indexOf('/_next/')>=0||u.indexOf('/api/')>=0)return false;
                    return true;
                  }catch(_){return false;}
                }
                function post(urls,reason){
                  try{
                    if(isCf())return;
                    var filtered=[],seenPost={};
                    (urls||[]).forEach(function(u){
                      u=abs(u);
                      if(!readerLike(u)||seenPost[u])return;
                      seenPost[u]=1;
                      filtered.push(u);
                    });
                    try{
                      var now=Date.now();
                      if(!window.__mvNtkCandidateLogAt||now-window.__mvNtkCandidateLogAt>900||filtered.length){
                        window.__mvNtkCandidateLogAt=now;
                        NtkBrowserBridge.onState(JSON.stringify({
                          href:String(location.href||''),
                          title:String(document.title||''),
                          body:'candidate-scan '+String(reason||'')+' raw '+((urls||[]).length)+' filtered '+filtered.length+' head '+filtered.slice(0,3).join('|'),
                          cloudflare:false,
                          stripCount:filtered.length
                        }));
                      }
                    }catch(_){}
                    if(filtered.length){
                      preload(filtered);
                      NtkBrowserBridge.onImages(JSON.stringify({href:String(location.href||''),reason:reason,urls:filtered,cloudflare:false}));
                    }
                  }catch(_){}
                }
                function renderStrip(urls,reason,expected){
                  try{
                    if(isCf())return;
                    var list=[],seenStrip={};
                    (urls||[]).forEach(function(u){
                      u=abs(u);
                      if(!readerLike(u)||seenStrip[u])return;
                      seenStrip[u]=1;
                      list.push(u);
                    });
                    if(list.length<1)return;
                    var sig=list.join('|');
                    if(window.__mvNtkStripSig===sig)return;
                    window.__mvNtkStripSig=sig;
                    window.__mvNtkStripReady=0;
                    window.__mvNtkStripMode=1;
                    window.__mvNtkStripGen=(window.__mvNtkStripGen||0)+1;
                    var stripGen=window.__mvNtkStripGen;
                    var strip=document.getElementById('__mvNtkReaderStrip');
                    if(!strip){
                      strip=document.createElement('div');
                      strip.id='__mvNtkReaderStrip';
                      if(document.body.firstChild)document.body.insertBefore(strip,document.body.firstChild);
                      else document.body.appendChild(strip);
                    }
                    try{strip.replaceChildren();}catch(_){strip.innerHTML='';}
                    strip.__mvNtkLoaded={};
                    strip.__mvNtkDecoded={};
                    strip.__mvNtkFailed={};
                    strip.__mvNtkNodes={};
                    strip.__mvNtkExpected=Math.max(0,expected||0);
                    try{
                      [].slice.call(document.body.children||[]).forEach(function(ch){
                        if(ch!==strip)ch.style.display='none';
                      });
                      document.documentElement.style.background='#111';
                      document.body.style.margin='0';
                      document.body.style.padding='0';
                      document.body.style.background='#111';
                      document.body.style.minHeight='';
                    }catch(_){}
                    strip.style.display='block';
                    strip.style.margin='0';
                    strip.style.padding='0';
                    strip.style.background='#111';
                    var loaded=Object.keys(strip.__mvNtkLoaded).length;
                    var decoded=Object.keys(strip.__mvNtkDecoded).length;
                    var failed=Object.keys(strip.__mvNtkFailed).length;
                    var total=list.length,readySent=false;
                    function stripDone(){
                      try{
                        if(window.__mvNtkStripGen!==stripGen)return;
                        loaded=Object.keys(strip.__mvNtkLoaded||{}).length;
                        decoded=Object.keys(strip.__mvNtkDecoded||{}).length;
                        failed=Object.keys(strip.__mvNtkFailed||{}).length;
                        var expectedTotal=Math.max(strip.__mvNtkExpected||0,total);
                        if(readySent||window.__mvNtkStripReady)return;
                        if(expectedTotal>0&&total<expectedTotal)return;
                        if(loaded+failed<total)return;
                        if(loaded<=0)return;
                        if(failed>0)return;
                        if(decoded<loaded)return;
                        readySent=true;
                        window.__mvNtkStripReady=1;
                        checkDrawable();
                        checkViewport();
                        NtkBrowserBridge.onViewportReady(JSON.stringify({href:String(location.href||''),strip:true,total:total,loaded:loaded,decoded:decoded,failed:failed}));
                        scrollState();
                      }catch(_){}
                    }
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'strip '+reason+' '+list.length,cloudflare:false,stripCount:list.length,stripReason:String(reason||'')}));}catch(_){}
                    list.forEach(function(u,i){
                      if(strip.__mvNtkNodes[u])return;
                      var im=document.createElement('img');
                      strip.__mvNtkNodes[u]=im;
                      im.loading='eager';
                      im.decoding='async';
                      try{im.fetchPriority='high';}catch(_){}
                      im.style.display='block';
                      im.style.width='100%';
                      im.style.height='auto';
                      im.style.minHeight='1px';
                      im.style.margin='0';
                      im.style.padding='0';
                      im.addEventListener('load',function(){
                        if(window.__mvNtkStripGen!==stripGen)return;
                        strip.__mvNtkLoaded[u]=1;
                        function doneDecode(){if(window.__mvNtkStripGen!==stripGen)return;strip.__mvNtkDecoded[u]=1;checkDrawable();checkViewport();scrollState();stripDone();}
                        try{
                          if(im.decode)im.decode().then(doneDecode).catch(doneDecode);
                          else doneDecode();
                        }catch(_){doneDecode();}
                      },{passive:true});
                      im.addEventListener('error',function(){if(window.__mvNtkStripGen!==stripGen)return;strip.__mvNtkFailed[u]=1;checkViewport();scrollState();stripDone();},{passive:true});
                      strip.appendChild(im);
                      try{im.setAttribute('data-mv-src',u);}catch(_){}
                      im.src=u;
                    });
                    setTimeout(function(){if(window.__mvNtkStripGen!==stripGen)return;checkDrawable();checkViewport();scrollState();},80);
                    setTimeout(stripDone,3000);
                  }catch(_){}
                }
                window.__mvNtkRenderStrip=renderStrip;
                try{
                  if(window.__mvNtkPendingRenderStrip){
                    var pendingRender=window.__mvNtkPendingRenderStrip;
                    window.__mvNtkPendingRenderStrip=null;
                    renderStrip(pendingRender.urls||[],'pending-'+String(pendingRender.reason||''),pendingRender.expected||0);
                  }
                }catch(_){}
                function isCf(){try{var t=(String(document.title||'')+' '+String(document.body&&document.body.innerText||'')).toLowerCase();return t.indexOf('just a moment')>=0||t.indexOf('cloudflare')>=0||t.indexOf('security verification')>=0||t.indexOf('verify you are human')>=0;}catch(_){return false;}}
                function state(){try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:String(document.body&&document.body.innerText||'').replace(/\s+/g,' ').slice(0,220),cloudflare:isCf()}));}catch(_){}}
                var lastScrollPost=0;
                var maxObservedHeight=0;
                try{document.documentElement.style.overflowAnchor='none';if(document.body)document.body.style.overflowAnchor='none';}catch(_){}
                function scrollState(){
                  try{
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var vh=innerHeight||de.clientHeight||bd.clientHeight||0;
                    var sh=Math.max(de.scrollHeight||0,bd.scrollHeight||0,de.offsetHeight||0,bd.offsetHeight||0,vh);
                    if(sh>maxObservedHeight)maxObservedHeight=sh;
                    if(maxObservedHeight>sh&&bd&&bd.style)try{bd.style.minHeight=maxObservedHeight+'px';}catch(_){}
                    sh=Math.max(sh,maxObservedHeight);
                    var sy=pageYOffset||de.scrollTop||bd.scrollTop||0;
                    NtkBrowserBridge.onScroll(JSON.stringify({href:String(location.href||''),scrollY:Math.max(0,Math.round(sy)),viewport:Math.max(0,Math.round(vh)),content:Math.max(0,Math.round(sh))}));
                    if(window.__mvNtkPostCoverage)window.__mvNtkPostCoverage('scroll');
                  }catch(_){}
                }
                function maybeScrollState(){try{var n=Date.now();if(n-lastScrollPost>80){lastScrollPost=n;scrollState();}}catch(_){}}
                var sweepStarted=0;
                function maybeWakeLazy(reason){
                  try{
                    if(window.__mvNtkBrokerVisible)return;
                    if(window.__mvNtkStripMode)return;
                    if(isCf())return;
                    if(!sweepStarted)wakeLazyImages(reason);
                  }catch(_){}
                }
                function wakeLazyImages(reason){
                  try{
                    if(window.__mvNtkBrokerVisible)return;
                    if(window.__mvNtkStripMode)return;
                    if(sweepStarted||isCf())return;
                    var original=pageYOffset||0;
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var vh=innerHeight||de.clientHeight||bd.clientHeight||720;
                    var max=Math.max(0,(de.scrollHeight||0),(bd.scrollHeight||0)-vh);
                    if(max<Math.max(480,vh*1.25)){
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'lazy-sweep-defer '+String(reason||'')+' max '+Math.round(max)+' vh '+Math.round(vh),cloudflare:false}));}catch(_){}
                      return;
                    }
                    sweepStarted=1;
                    var points=[0,vh*0.6,vh*1.2,vh*2,vh*3.2,vh*5,vh*8,max*0.25,max*0.5,max*0.75,max];
                    points=points.map(function(v){return Math.max(0,Math.min(max,Math.round(v||0)));});
                    var i=0;
                    function step(){
                      try{
                        if(i<points.length){
                          scrollTo(0,points[i++]);
                          dispatchEvent(new Event('scroll'));
                          scan('lazy-sweep-'+reason);
                          setTimeout(step,55);
                        }else{
                          scrollTo(0,Math.max(0,Math.min(max,original)));
                          dispatchEvent(new Event('scroll'));
                          scan('lazy-sweep-done-'+reason);
                        }
                      }catch(_){}
                    }
                    step();
                  }catch(_){}
                }
                var seen={};
                function add(out,u){u=abs(u);if(!u||seen[u])return;seen[u]=1;out.push(u);}
                function addCandidates(out,text){
                  try{
                    text=String(text||'');
                    var re=/(?:https?:)?\/\/[^"' <>()]+|(?:\/[^"' <>()]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"' <>()]*)?)/ig,m;
                    while((m=re.exec(text))){add(out,m[0]);}
                  }catch(_){}
                }
                function scan(reason){
                  try{
                    var out=[];
                    document.querySelectorAll('*').forEach(function(el){
                      try{
                        add(out,el.currentSrc);add(out,el.src);add(out,el.href);add(out,el.getAttribute&&el.getAttribute('src'));
                        ['data-src','data-original','data-url','data-lazy-src','data-file','data-image','data-full','data-bg','data-background','poster'].forEach(function(a){add(out,el.getAttribute&&el.getAttribute(a));});
                        var ss=String((el.getAttribute&&el.getAttribute('srcset'))||el.srcset||'');
                        ss.split(',').forEach(function(p){add(out,String(p).trim().split(/\s+/)[0]);});
                        addCandidates(out,el.getAttribute&&el.getAttribute('style'));
                        for(var ai=0;el.attributes&&ai<el.attributes.length;ai++){addCandidates(out,el.attributes[ai].value);}
                        if(el.tagName==='IMG'){
                          try{
                            el.loading='eager';
                            el.decoding='async';
                            el.fetchPriority='high';
                            var eagerSrc=el.currentSrc||el.src||el.getAttribute('src')||el.getAttribute('data-src')||el.getAttribute('data-original')||el.getAttribute('data-lazy-src')||el.getAttribute('data-url')||'';
                            if(eagerSrc&&!el.getAttribute('src'))el.setAttribute('src',eagerSrc);
                            if(!el.__mvNtkLoadHooked){
                              el.__mvNtkLoadHooked=1;
                              el.addEventListener('load',function(){checkDrawable();checkViewport();scrollState();},{passive:true});
                              el.addEventListener('error',function(){checkViewport();scrollState();},{passive:true});
                            }
                            if(el.decode&&!el.__mvNtkDecodeAsked){
                              el.__mvNtkDecodeAsked=1;
                              try{el.decode().then(function(){checkDrawable();checkViewport();scrollState();}).catch(function(){});}catch(_){}
                            }
                          }catch(_){}
                        }
                      }catch(_){}
                    });
                    addCandidates(out,document.documentElement&&document.documentElement.innerHTML);
                    tryViewerApi(reason);
                    post(out,reason);
                    maybeWakeLazy(reason);
                    flushPendingPrime();
                    checkDrawable();
                    checkViewport();
                    state();
                    scrollState();
                  }catch(e){}
                }
                window.__mvNtkScan=scan;
                function drawableImg(){
                  try{
                    var imgs=[].slice.call(document.images||[]);
                    for(var i=0;i<imgs.length;i++){
                      var im=imgs[i],r=im.getBoundingClientRect();
                      if(im.complete&&im.naturalWidth>0&&im.naturalHeight>0&&r.width>0&&r.height>0&&r.bottom>0&&r.top<(innerHeight||document.documentElement.clientHeight||1))return im;
                    }
                  }catch(_){}
                  return null;
                }
                function checkDrawable(){try{if(window.__mvNtkFirstDrawable||isCf())return;var im=drawableImg();if(im){window.__mvNtkFirstDrawable=1;NtkBrowserBridge.onFirstDrawable(JSON.stringify({href:String(location.href||''),src:im.currentSrc||im.src||''}));if(!window.__mvNtkStripMode)setTimeout(function(){wakeLazyImages('after-first-drawable');},0);}}catch(_){}}
                function viewportCoverage(){
                  var vh=innerHeight||document.documentElement.clientHeight||0;
                  var covered=0,missing=0,drawableItems=0,totalItems=0,loading=0,errors=0,pageCount=0;
                  try{
                    var strip=document.getElementById('__mvNtkReaderStrip');
                    if(strip&&strip.__mvNtkNodes)pageCount=Object.keys(strip.__mvNtkNodes||{}).length;
                    [].slice.call(document.images||[]).forEach(function(im){
                      var src=im.currentSrc||im.src||'';
                      if(!readerLike(src))return;
                      pageCount=Math.max(pageCount,1);
                      var r=im.getBoundingClientRect();
                      if(r.bottom<=0||r.top>=vh)return;
                      var ov=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));
                      if(ov<=0)return;
                      totalItems++;
                      if(im.complete&&im.naturalWidth>0&&im.naturalHeight>0){
                        drawableItems++;
                        covered+=ov;
                      }else{
                        loading++;
                        missing+=ov;
                      }
                      if(im.complete&&im.naturalWidth===0)errors++;
                    });
                    if(vh>0&&covered<vh)missing=Math.max(missing,vh-covered);
                  }catch(_){
                    if(vh>0)missing=vh;
                  }
                  return {
                    href:String(location.href||''),
                    viewport:Math.max(0,Math.round(vh)),
                    drawable:Math.max(0,Math.round(Math.min(covered,vh))),
                    missing:Math.max(0,Math.round(missing)),
                    drawableItems:drawableItems,
                    totalItems:totalItems,
                    loading:loading,
                    errors:errors,
                    pageCount:Math.max(pageCount,totalItems)
                  };
                }
                function postCoverage(reason){
                  try{
                    var c=viewportCoverage();
                    c.reason=String(reason||'');
                    NtkBrowserBridge.onCoverage(JSON.stringify(c));
                  }catch(_){}
                }
                window.__mvNtkPostCoverage=postCoverage;
                function checkViewport(){
                  try{
                    if(isCf())return;
                    if(mvNtkExpected>0)return;
                    if(window.__mvNtkStripMode&&!window.__mvNtkStripReady)return;
                    var c=viewportCoverage();
                    postCoverage('check');
                    if(!window.__mvNtkViewportReady&&c.viewport>0&&c.drawable>=Math.max(1,c.viewport-24)&&c.missing===0){window.__mvNtkViewportReady=1;NtkBrowserBridge.onViewportReady(JSON.stringify({href:String(location.href||'')}));}
                  }catch(_){}
                }
                try{new MutationObserver(function(){scan('mutation');}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src','srcset','data-src','data-original','data-url','data-lazy-src']});}catch(_){}
                try{addEventListener('scroll',maybeScrollState,{passive:true});addEventListener('resize',maybeScrollState,{passive:true});}catch(_){}
                scan('install');
                setInterval(function(){scan('interval');scrollState();},500);
                setTimeout(function(){try{if(!window.__mvNtkBrokerVisible&&!window.__mvNtkStripMode)window.scrollBy(0,Math.max(600,(innerHeight||720)*1.5));}catch(_){}scan('initial-scroll');},120);
                setTimeout(function(){maybeWakeLazy('install');scan('initial-wake');},80);
              }catch(e){try{NtkBrowserBridge.onState(JSON.stringify({error:String(e)}));}catch(_){}}
            })();
        """.trimIndent()
    }

    private fun normalizePath(path: String?): String {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return if (value.startsWith("/")) value else "/$value"
    }

    private fun normalizeImageUrl(raw: String?, documentUrl: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("data:") || value.startsWith("blob:")) return ""
        val normalized = try {
            URL(URL(documentUrl.ifBlank { currentBaseUrl + currentPath }), value).toString()
        } catch (_: Throwable) {
            if (value.startsWith("//")) "https:$value" else value
        }
        return normalized.replace(
            Regex("^http://(apihost\\d*\\.com/)", RegexOption.IGNORE_CASE),
            "https://$1"
        )
    }

    private fun looksLikeReaderImageUrl(raw: String?, headers: Map<String, String>?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        val ntkViewerImageApi = lower.contains("/api/m/i?")
                || lower.matches(Regex("https?://[^/]+/api/m/i(?:[?#].*)?$"))
        if (ntkViewerImageApi) return true
        if (
            lower.contains("banner") ||
            lower.contains("advert") ||
            lower.contains("sponsor") ||
            lower.contains("favicon") ||
            lower.contains("/_next/") ||
            lower.contains("/api/") ||
            lower.contains("turnstile")
        ) {
            return false
        }
        if (currentPath.startsWith("/manhwa/") || currentPath.startsWith("/webtoon/")) {
            val ntkReaderPath =
                lower.matches(Regex("https?://(?:[^/]+\\.)?apihost\\d*\\.com/manhwa/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp|gif)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?moamoabon\\.com/blacktoon/episodes/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?fifa\\.worldcup73\\.xyz/(?:black/episodes|wt/episodes)/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$"))
            return ntkReaderPath
        }
        val imageAccept = headers?.entries?.any {
            it.key.equals("accept", ignoreCase = true) && it.value.lowercase(Locale.ROOT).contains("image/")
        } == true
        val extension = lower.matches(Regex(".*\\.(jpg|jpeg|png|webp)(?:[?#].*)?$"))
        return imageAccept && extension ||
            lower.contains("/webtoon_uploads/") ||
            lower.contains("/manhwa_uploads/") ||
            lower.contains("/comic_uploads/") ||
            lower.contains("/blacktoon/episodes/") ||
            lower.matches(Regex(".*/p\\d{2,4}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$"))
    }

    private fun looksLikeGenericReaderImageRequest(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        if (
            lower.contains("banner") ||
            lower.contains("advert") ||
            lower.contains("sponsor") ||
            lower.contains("favicon") ||
            lower.contains("logo") ||
            lower.contains("/_next/") ||
            lower.contains("/api/") ||
            lower.contains("turnstile") ||
            lower.contains("captcha") ||
            lower.contains("cloudflare")
        ) {
            return false
        }
        return lower.matches(Regex(".*\\.(jpg|jpeg|png|webp)(?:[?#].*)?$"))
    }

    private fun applyChromeUaMetadata(settings: WebSettings?, userAgent: String?) {
        if (settings == null || userAgent.isNullOrBlank()) return
        val lower = userAgent.lowercase(Locale.ROOT)
        if (lower.contains("; wv") || lower.contains(" wv") || lower.contains("version/4.0")) return
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
            val major = Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.getOrNull(1) ?: "137"
            val full = Regex("Chrome/([0-9.]+)").find(userAgent)?.groupValues?.getOrNull(1)
                ?: "$major.0.0.0"
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium")
                    .setMajorVersion(major)
                    .setFullVersion(full)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome")
                    .setMajorVersion(major)
                    .setFullVersion(full)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not)A;Brand")
                    .setMajorVersion("24")
                    .setFullVersion("24.0.0.0")
                    .build()
            )
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(full)
                .setPlatform("Android")
                .setPlatformVersion("15.0.0")
                .setArchitecture("")
                .setModel("Pixel 8 Pro")
                .setMobile(true)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_browser_broker_ua_metadata_error $e")
        }
    }

    private fun suppressRequestedWithHeader(settings: WebSettings?) {
        if (settings == null) return
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) return
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_browser_broker_requested_with_error $e")
        }
    }

    private fun browserLikeUserAgent(defaultUserAgent: String?, preferredUserAgent: String?): String {
        val preferred = preferredUserAgent?.trim().orEmpty()
        val fallback = defaultUserAgent?.trim().orEmpty()
        var ua = preferred.ifEmpty { fallback }
            .replace("; wv", "")
            .replace(" wv", "")
            .replace("Version/4.0 ", "")
            .trim()
        val lower = ua.lowercase(Locale.ROOT)
        if (
            lower.contains("sdk_gphone") ||
            lower.contains("emulator") ||
            lower.contains("generic") ||
            lower.contains("android sdk")
        ) {
            ua = "Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro Build/AP3A.241105.008) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        }
        if (ua.isEmpty()) {
            ua = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        if (!ua.contains("Mobile Safari/")) ua += " Mobile Safari/537.36"
        return ua
    }
}
