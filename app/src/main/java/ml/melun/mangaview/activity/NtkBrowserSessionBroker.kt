package ml.melun.mangaview.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.R
import ml.melun.mangaview.mangaview.NtkWebViewFallbackManager
import ml.melun.mangaview.reader.NtkSourceSpoolRegistry
import ml.melun.mangaview.reader.ReaderImageCache
import ml.melun.mangaview.reader.ReaderSurfaceView
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

    data class DecodeSnapshot(
        val path: String,
        val expected: Int,
        val total: Int,
        val activated: Int,
        val loaded: Int,
        val decoded: Int,
        val failed: Int,
        val allDecoded: Boolean,
        val createdAtMs: Long
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
    private var currentCacheProducerGeneration = -1L
    @Volatile
    private var currentDocumentUrl = ""
    @Volatile
    private var currentLoadTarget = ""
    @Volatile
    private var payloadShellPath = ""
    @Volatile
    private var currentListener: Listener? = null
    @Volatile
    private var currentAppContext: Context? = null
    @Volatile
    private var currentUserAgent = ""
    @Volatile
    private var currentCookieHeader = ""
    private val controlledForegroundBytes = ConcurrentHashMap<String, ByteArray>()
    private val controlledForegroundFlights = ConcurrentHashMap.newKeySet<String>()
    private val protectedPrimeSigByPath = ConcurrentHashMap<String, String>()
    private val controlledForegroundExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "NtkControlledForeground").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }
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
    private val authoritativeUrlsByPath = ConcurrentHashMap<String, List<String>>()
    private val authoritativeSourceByPath = ConcurrentHashMap<String, String>()
    private val controlledHtmlByPath = ConcurrentHashMap<String, String>()
    private val decodeSnapshotsByPath = ConcurrentHashMap<String, DecodeSnapshot>()
    private val frameStatsByPath = ConcurrentHashMap<String, ReaderSurfaceView.FrameStatsSnapshot>()
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
    private var currentForceBrowserAuthoritative = false
    @Volatile
    private var controlledStripPath = ""
    @Volatile
    private var controlledStripSig = ""
    @Volatile
    private var controlledStripSetSig = ""
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

    fun latestFrameStatsSnapshot(path: String?): ReaderSurfaceView.FrameStatsSnapshot? {
        val key = normalizePath(path)
        if (key.isEmpty()) return null
        return frameStatsByPath[key]
    }

    fun resetFrameStats(path: String?) {
        val key = normalizePath(path)
        if (key.isNotEmpty()) frameStatsByPath.remove(key)
    }

    @JvmStatic
    fun latestDecodeSnapshot(path: String?): DecodeSnapshot? {
        val key = normalizePath(path)
        if (key.isEmpty()) return null
        return decodeSnapshotsByPath[key]
    }

    @JvmStatic
    fun preparedStatus(path: String?): PreparedStatus {
        val key = normalizePath(path)
        val snapshot = snapshots[key]
        val decode = decodeSnapshotsByPath[key]
        val expected = maxOf(currentExpectedImageCount, decode?.expected ?: 0)
        return PreparedStatus(
            key,
            key.isNotEmpty() && key == currentPath && pageStartedAtMs > 0L,
            key.isNotEmpty() && key == currentPath && firstDrawableReadyPath == key,
            key.isNotEmpty() && key == currentPath && isAllDecodedReady(key, expected),
            maxOf(snapshot?.images?.size ?: 0, decode?.total ?: 0),
            expected
        )
    }

    @JvmStatic
    @JvmOverloads
    fun isAllDecodedReady(path: String?, expected: Int = 0): Boolean {
        val key = normalizePath(path)
        if (key.isEmpty() || key != currentPath) return false
        val snapshot = decodeSnapshotsByPath[key] ?: return false
        val required = maxOf(expected, currentExpectedImageCount, snapshot.expected)
        return required > 0 &&
            snapshot.total >= required &&
            snapshot.activated >= required &&
            snapshot.loaded >= required &&
            snapshot.decoded >= required &&
            snapshot.failed == 0 &&
            snapshot.allDecoded
    }

    fun freezeCurrentScroll(path: String?, scrollY: Int, contentHeight: Int, durationMs: Long = 950L) {
        return
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
        expectedImageCount: Int = 0,
        forceBrowserAuthoritative: Boolean = false
    ): WebView {
        val attachStartedAt = SystemClock.elapsedRealtime()
        val key = normalizePath(path)
        check(!isStrictEpisodePath(key)) {
            "Strict NTK episodes are owned only by the isolated ACK/native coordinator"
        }
        val previousPath = currentPath
        val reusedReadyDrawable = key.isNotEmpty() && key == previousPath && firstDrawableReadyPath == key
        val reusedReadyViewport = key.isNotEmpty() && key == previousPath && viewportReadyPath == key
        if (key != previousPath) {
            firstDrawableReadyPath = ""
            viewportReadyPath = ""
            allDecodedReadyPath = ""
            currentLoadTarget = ""
            payloadShellPath = ""
            latestScrollSnapshot = null
            latestCoverageSnapshot = null
            decodeSnapshotsByPath.remove(previousPath)
        }
        injectedViewerPayloadSigByPath.remove(key)
        currentBaseUrl = baseUrl.trimEnd('/')
        currentPath = key
        currentCacheProducerGeneration = ReaderImageCache.cacheGenerationForProducer()
        currentDocumentUrl = currentBaseUrl + key
        currentListener = listener
        currentAppContext = activity.applicationContext
        currentExpectedImageCount = expectedImageCount.coerceAtLeast(0)
        firstDrawableSent = reusedReadyDrawable
        viewportReadySent = reusedReadyViewport
        if (key != previousPath) {
            controlledDocumentPath = ""
            controlledStripPath = ""
            controlledStripSig = ""
            controlledStripSetSig = ""
            allDecodedReadyPath = ""
            pageStartedAtMs = 0L
            if (previousPath.isNotEmpty()) controlledHtmlByPath.remove(previousPath)
            if (previousPath.isNotEmpty()) protectedPrimeSigByPath.remove(previousPath)
        }
        NtkWebViewFallbackManager.beginForegroundHybridReader(activity.applicationContext, key)
        val webViewCreateStartedAt = SystemClock.elapsedRealtime()
        val reusedWebView = webView != null
        val view = webView ?: WebView(activity).also { created ->
            webView = created
            val settings = created.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
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
            created.webChromeClient = chromeClient()
            created.webViewClient = client()
            created.setOnTouchListener { _, event ->
                val key = currentPath
                if (
                    key.isNotEmpty() &&
                    isKpWebtoonSlugPath(key) &&
                    controlledDocumentPath == key &&
                    firstDrawableReadyPath != key
                ) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_MOVE ||
                        event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        try {
                            created.evaluateJavascript(
                                "try{if(window.__mvNtkPromoteTop)window.__mvNtkPromoteTop(8);}catch(e){}",
                                null
                            )
                        } catch (_: Throwable) {
                        }
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
        Log.d(
            TAG,
            "ntk_browser_broker_attach_webview_ready path=$key,reused=$reusedWebView," +
                "createMs=${SystemClock.elapsedRealtime() - webViewCreateStartedAt}"
        )
        if (key != previousPath) {
            try {
                view.scrollTo(0, 0)
            } catch (_: Throwable) {
            }
        }
        val authoritativeSeed = authoritativeUrlsByPath[key].orEmpty()
        val hasAuthoritativeSeedForAttach = authoritativeSeed.isNotEmpty()
        val hasControlledSeedForAttach = currentExpectedImageCount > 0 &&
            strictReaderStripUrls(pendingPrimeUrlsByPath[key].orEmpty(), currentBaseUrl + key)
                .size >= currentExpectedImageCount
        if (
            key != previousPath &&
            !hasControlledSeedForAttach &&
            !(visible && isKpWebtoonSlugPath(key))
        ) {
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
        currentForceBrowserAuthoritative = forceBrowserAuthoritative
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
        if (hasControlledSeedForAttach) {
            clearDocumentStartScripts()
        } else {
            if (modernNaturalOwner) installForegroundAckDocumentStart(view, key)
            installDiscoveryDocumentStart(view, key)
            installDocumentStartCapture(view, key)
        }
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
        if (visible) {
            try {
                view.scrollTo(0, 0)
                view.evaluateJavascript(
                    "try{scrollTo(0,0);if(window.__mvNtkPromoteTop)window.__mvNtkPromoteTop(24);}catch(e){}",
                    null
                )
            } catch (_: Throwable) {
            }
        }
        val reuseControlledDocumentForAttach = key.isNotEmpty() &&
            key == previousPath &&
            controlledDocumentPath == key
        if (reuseControlledDocumentForAttach) {
            installDiscovery(view)
            Log.d(
                TAG,
                "ntk_browser_broker_reuse_controlled_document path=$key,visible=$visible,url=${view.url.orEmpty().take(120)}"
            )
        } else if (view.url != target && currentLoadTarget != target) {
            val authoritative = strictReaderStripUrls(authoritativeSeed, target)
            val seeded = strictReaderStripUrls(pendingPrimeUrlsByPath[key].orEmpty(), target)
            val canStartControlledFromSeed = currentExpectedImageCount > 0 &&
                seeded.size >= currentExpectedImageCount
            val kpAuthoritativeBrowserOwned = isKpWebtoonSlugPath(key) &&
                authoritative.isNotEmpty() &&
                authoritative.all { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
            val kpSeedBrowserOwned = isKpWebtoonSlugPath(key) &&
                seeded.isNotEmpty() &&
                seeded.all { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
            if (kpAuthoritativeBrowserOwned || (canStartControlledFromSeed && kpSeedBrowserOwned)) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_attach_skip_controlled_kp_browser_owned path=$key," +
                        "authoritative=${authoritative.size},seeded=${seeded.size},expected=$currentExpectedImageCount"
                )
                currentLoadTarget = target
                payloadShellPath = key
                view.loadUrl(target, headers)
            } else if (authoritative.isNotEmpty()) {
                val source = authoritativeSourceByPath[key].orEmpty().ifBlank { "attach-authoritative" }
                Log.d(
                    TAG,
                    "ntk_browser_broker_attach_authoritative_seed path=$key,count=${authoritative.size}," +
                        "expected=$currentExpectedImageCount,source=$source"
                )
                maybeLoadControlledStrip(
                    key,
                    authoritative,
                    "attach-authoritative-$source",
                    target,
                    allowNativeHandoffWindow = !forceBrowserAuthoritative
                )
            } else if (canStartControlledFromSeed) {
                maybeLoadControlledStrip(
                    key,
                    seeded,
                    "initial-prime",
                    target,
                    allowNativeHandoffWindow = !forceBrowserAuthoritative
                )
            } else if (
                modernNaturalOwner &&
                !isKpWebtoonSlugPath(key) &&
                pendingViewerPayloadByPath[key].orEmpty().contains("imageApiPath")
            ) {
                val payload = pendingViewerPayloadByPath[key].orEmpty()
                currentLoadTarget = target
                Log.d(
                    TAG,
                    "ntk_browser_broker_payload_bootstrap_load path=$key,bytes=${payload.length},visible=$visible"
                )
                payloadShellPath = key
                view.loadDataWithBaseURL(
                    target,
                    viewerPayloadBootstrapHtml(payload, target),
                    "text/html",
                    "UTF-8",
                    null
                )
            } else if (modernNaturalOwner) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_payload_shell_load path=$key,url=$target,visible=$visible"
                )
                currentLoadTarget = target
                if (visible && isKpWebtoonSlugPath(key)) {
                    payloadShellPath = ""
                    Log.d(TAG, "ntk_browser_broker_kp_waiting_shell_load path=$key,url=$target")
                    view.loadDataWithBaseURL(
                        target,
                        controlledWaitingHtml(),
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else if (visible) {
                    payloadShellPath = key
                    Log.d(TAG, "ntk_browser_broker_visible_natural_load path=$key,url=$target")
                    view.loadUrl(target, headers)
                } else if (isKpWebtoonSlugPath(key)) {
                    payloadShellPath = ""
                    view.loadDataWithBaseURL(
                        target,
                        controlledWaitingHtml(),
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else {
                    payloadShellPath = key
                    view.loadDataWithBaseURL(
                        target,
                        viewerPayloadBootstrapHtml("", target),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
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
        Log.d(
            TAG,
            "ntk_browser_broker_attach_done path=$key,ms=${SystemClock.elapsedRealtime() - attachStartedAt}"
        )
        if (quicBridge != null && !isKpWebtoonSlugPath(key)) {
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
        if (isStrictEpisodePath(key)) {
            Log.d(
                TAG,
                "ntk_browser_broker_prime_fenced_by_strict_authority path=$key,source=$source"
            )
            return
        }
        val documentUrl = currentDocumentUrl.ifBlank { currentBaseUrl + key }
        val values = strictReaderStripUrls(urls.orEmpty(), documentUrl)
        if (key.isEmpty() || values.isEmpty()) return
        pendingPrimeUrlsByPath[key] = values
        val primeOnly = primeOnlyUrlsByPath.getOrPut(key) { LinkedHashSet() }
        synchronized(primeOnly) {
            primeOnly.addAll(values)
        }
        if (key != currentPath) return
        if (currentExpectedImageCount > 0 && values.size >= currentExpectedImageCount) {
            if (source.contains("episode-visible-prepared", ignoreCase = true) && currentListener != null) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_prime_authoritative_native path=$key,count=${values.size}," +
                        "expected=$currentExpectedImageCount,source=$source"
                )
                publishAuthoritativeImageUrls(key, values, source)
                return
            }
            maybeLoadControlledStrip(
                key,
                values,
                "prime-verify-$source",
                currentDocumentUrl.ifBlank { currentBaseUrl + key },
                allowNativeHandoffWindow = !currentForceBrowserAuthoritative
            )
        }
        val view = webView ?: return
        view.post {
            injectPrimeUrls(view, key, values, source)
            val sourceValue = source.lowercase(Locale.ROOT)
            val controlledManifestSource = sourceValue.contains("payload") ||
                sourceValue.contains("viewer-api") ||
                sourceValue.contains("foreground-viewer-images") ||
                sourceValue.contains("authoritative")
            if (
                !controlledManifestSource &&
                canDisplayControlledStripFromSource("prime-$source", values.size, currentExpectedImageCount)
            ) {
                renderNaturalStrip(view, key, values, "prime-$source")
            }
        }
    }

    fun prefetchImageUrls(path: String?, urls: List<String>?, source: String) {
        val key = normalizePath(path)
        if (isStrictEpisodePath(key)) {
            Log.d(
                TAG,
                "ntk_browser_broker_prefetch_fenced_by_strict_authority path=$key,source=$source"
            )
            return
        }
        val values = urls.orEmpty().mapNotNull { raw ->
            normalizeImageUrl(raw, currentDocumentUrl.ifBlank { currentBaseUrl + key })
                .takeIf { it.isNotEmpty() && looksLikeReaderImageUrl(it, null) }
        }.distinct().take(128)
        val view = webView ?: return
        if (key.isEmpty() || values.isEmpty()) return
        val json = JSONArray(values).toString()
        val sourceJson = JSONObject.quote(source)
        runOnWebViewThread(view) {
            try {
                view.evaluateJavascript(
                    """
                    (function(urls,source){
                      try{
                        window.__mvNtkAdjacentPrefetch=window.__mvNtkAdjacentPrefetch||{};
                        var key=String(source||'adjacent');
                        if(window.__mvNtkAdjacentPrefetch[key])return;
                        window.__mvNtkAdjacentPrefetch[key]=1;
                        var i=0,held=[];
                        function step(){
                          try{
                            var end=Math.min(urls.length,i+8);
                            for(;i<end;i++){
                              var im=new Image();
                              im.decoding='async';
                              try{im.fetchPriority='low';}catch(_){}
                              im.src=urls[i];
                              held.push(im);
                            }
                            if(i<urls.length)setTimeout(step,50);
                          }catch(_){}
                        }
                        setTimeout(step,0);
                      }catch(_){}
                    })($json,$sourceJson);
                    """.trimIndent(),
                    null
                )
                Log.d(TAG, "ntk_browser_broker_prefetch_images path=$key,count=${values.size},source=$source")
            } catch (_: Throwable) {
            }
        }
    }

    fun publishAuthoritativeImageUrls(path: String?, urls: List<String>?, source: String) {
        val key = normalizePath(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        if (isStrictEpisodePath(key)) {
            val authority = NtkSourceSpoolRegistry.currentAuthoritativeManifest(key)
            val exact = urls.map(String::trim) ==
                authority?.seal?.normalizedCanonicalAssets
            if (source != "strict-exact-owner" || !exact) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_authoritative_fenced_by_strict_authority " +
                        "path=$key,count=${urls.size},source=$source"
                )
                return
            }
        }
        val documentUrl = if (key == currentPath) {
            currentDocumentUrl.ifBlank { currentBaseUrl + key }
        } else {
            key
        }
        val values = strictReaderStripUrls(urls, documentUrl)
        if (values.isEmpty()) return
        if (
            !isKpWebtoonSlugPath(key) &&
            values.all { isNtkUploadDescriptorUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_descriptor_only path=$key," +
                    "count=${values.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            values.all { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_kp_browser_owned_manifest path=$key," +
                    "count=${values.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            values.any { isNtkUploadDescriptorUrl(it) } &&
            !values.all { isNtkKpDescriptorImageUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_descriptor path=$key," +
                    "count=${values.size},source=$source"
            )
            return
        }
        val existingValues = authoritativeUrlsByPath[key].orEmpty()
        if (existingValues == values) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_identical path=$key,count=${values.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            values.any { isNtkUploadCdnImageUrl(it) } &&
            authoritativeUrlsByPath[key].orEmpty().any { isProtectedApiImageUrl(it) }
        ) {
            if (values.any { isNtkUploadDescriptorUrl(it) }) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_authoritative_skip_upload_txt_over_protected path=$key," +
                        "count=${values.size},source=$source"
                )
                return
            }
            val allowVerifiedUploadOverProtected =
                (source.contains("viewer-api-token", ignoreCase = true) ||
                    source.contains("native-api", ignoreCase = true)) &&
                    values.none { isNtkUploadDescriptorUrl(it) }
            if (allowVerifiedUploadOverProtected) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_authoritative_allow_upload_over_protected path=$key," +
                        "count=${values.size},source=$source"
                )
            } else {
            val expanded = expandKpProtectedApiUrlsFromExisting(key, values.size)
            if (expanded.size >= values.size) {
                authoritativeUrlsByPath[key] = expanded
                authoritativeSourceByPath[key] = "expanded-protected-$source"
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                    key,
                    expanded,
                    "expanded-protected-$source",
                    currentCacheProducerGeneration
                )
                Log.d(
                    TAG,
                    "ntk_browser_broker_authoritative_expand_protected_over_upload path=$key," +
                        "count=${expanded.size},upload=${values.size},source=$source"
                )
                if (key == currentPath) {
                    replaceWithAuthoritativeImages(
                        key,
                        expanded,
                        "expanded-protected-$source",
                        documentUrl
                    )
                }
                return
            }
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_upload_over_protected path=$key," +
                    "count=${values.size},source=$source"
            )
            return
            }
        }
        authoritativeUrlsByPath[key] = values
        authoritativeSourceByPath[key] = source
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            key,
            values,
            source,
            currentCacheProducerGeneration
        )
        if (key != currentPath) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_pending path=$key,count=${values.size},source=$source"
            )
            return
        }
        replaceWithAuthoritativeImages(key, values, source, documentUrl)
    }

    private fun primeProtectedApiImagesInWebView(path: String, urls: List<String>, source: String) {
        val view = webView ?: return
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val normalized = strictReaderStripUrls(urls, currentDocumentUrl.ifBlank { currentBaseUrl + path })
            .filter { isProtectedApiImageUrl(it) }
            .distinct()
        if (normalized.isEmpty()) return
        val sig = normalized.joinToString("|") { controlledStripUrlSignature(it) }
        val previousSig = protectedPrimeSigByPath.putIfAbsent(path, sig)
        if (previousSig != null) {
            Log.d(
                TAG,
                "ntk_browser_broker_protected_prime_skip_started path=$path,count=${normalized.size},source=$source"
            )
            return
        }
        val json = JSONArray(normalized).toString()
        val sourceJson = JSONObject.quote(source)
        runOnWebViewThreadFront(view) {
            if (path != currentPath) return@runOnWebViewThreadFront
            try {
                view.evaluateJavascript(
                    """
                    (function(urls,source){
                      try{
                        window.__mvNtkProtectedPrime=window.__mvNtkProtectedPrime||{};
                        window.__mvNtkProtectedPrimeHeld=window.__mvNtkProtectedPrimeHeld||[];
                        for(var i=0;i<urls.length;i++){
                          var u=String(urls[i]||'').replace(/&amp;/g,'&');
                          if(!u||window.__mvNtkProtectedPrime[u])continue;
                          window.__mvNtkProtectedPrime[u]=1;
                          var im=new Image();
                          im.loading='eager';
                          im.decoding='async';
                          try{im.fetchPriority='high';}catch(_){}
                          im.src=u;
                          window.__mvNtkProtectedPrimeHeld.push(im);
                        }
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-prime-start '+urls.length+' source '+String(source||''),cloudflare:false}));}catch(_){}
                      }catch(_){}
                    })($json,$sourceJson);
                    """.trimIndent(),
                    null
                )
                Log.d(TAG, "ntk_browser_broker_protected_prime path=$path,count=${normalized.size},source=$source")
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun publishViewerPayload(path: String?, payload: String?, source: String) {
        val key = normalizePath(path)
        val body = payload.orEmpty()
        if (isStrictEpisodePath(key)) {
            Log.d(
                TAG,
                "ntk_browser_broker_payload_fenced_by_strict_authority path=$key,source=$source"
            )
            return
        }
        if (key.isEmpty() || body.length < 64 || !body.contains("imageApiPath")) return
        pendingViewerPayloadByPath[key] = body
        val signature = "${body.length}:${body.hashCode()}"
        val previous = injectedViewerPayloadSigByPath.put(key, signature)
        if (previous == signature) return
        val view = webView ?: return
        if (key != currentPath) return
        view.post {
            if (key != currentPath) return@post
            val target = currentBaseUrl + key
            val isModernOwner = key.startsWith("/webtoon/") || key.startsWith("/manhwa/")
            if (
                previous == null &&
                isModernOwner &&
                controlledDocumentPath != key &&
                (payloadShellPath != key || isKpWebtoonSlugPath(key))
            ) {
                try {
                    Log.d(
                        TAG,
                        "ntk_browser_broker_payload_bootstrap_replace path=$key,bytes=${body.length},source=$source"
                    )
                    currentLoadTarget = target
                    payloadShellPath = key
                    view.stopLoading()
                    view.loadDataWithBaseURL(
                        target,
                        viewerPayloadBootstrapHtml(body, target),
                        "text/html",
                        "UTF-8",
                        null
                    )
                } catch (e: Throwable) {
                    currentListener?.onError(key, e.toString())
                }
            } else {
                if (view.url.orEmpty().contains(key)) installDiscovery(view)
                injectViewerPayload(view, key, body, source)
            }
        }
    }

    @JvmStatic
    fun notifyNativeAckProof(path: String?, reason: String) {
        val key = normalizePath(path)
        val view = webView ?: return
        if (key.isEmpty() || key != currentPath) return
        view.post {
            if (key != currentPath) return@post
            view.evaluateJavascript(
                "try{window.__ntkTrustedBrowserState=1;" +
                    "if(window.__mvNtkRetryFailed)window.__mvNtkRetryFailed(${JSONObject.quote(reason)});" +
                    "}catch(e){}",
                null
            )
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

    @JvmStatic
    fun quietForNativeReader(path: String?, reason: String) {
        val key = normalizePath(path)
        val view = webView ?: return
        if (key.isEmpty() || key != currentPath) return
        val action = Runnable {
            try {
                if (key != currentPath) return@Runnable
                Log.d(TAG, "ntk_browser_broker_destroy_native path=$key,reason=$reason")
                webView = null
                documentStartScriptHandler?.remove()
                documentStartScriptHandler = null
                currentLoadTarget = ""
                payloadShellPath = ""
                NtkWebViewFallbackManager.quietForForegroundNativeReader(
                    currentAppContext,
                    key,
                    "broker_native_destroy:$reason"
                )
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.onPause()
                view.removeAllViews()
                view.destroy()
                if (currentPath == key) {
                    currentPath = ""
                    currentCacheProducerGeneration = -1L
                    currentListener = null
                    currentDocumentUrl = ""
                    controlledDocumentPath = ""
                    controlledStripPath = ""
                    controlledStripSig = ""
                    controlledStripSetSig = ""
                    firstDrawableReadyPath = ""
                    viewportReadyPath = ""
                    allDecodedReadyPath = ""
                    decodeSnapshotsByPath.remove(key)
                    protectedPrimeSigByPath.remove(key)
                    pageStartedAtMs = 0L
                }
            } catch (_: Throwable) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                if (webView === view) action.run()
            }
        }
    }

    @JvmStatic
    fun quietAllForNativeReader(reason: String) {
        val view = webView ?: return
        val key = currentPath
        val action = Runnable {
            try {
                if (webView !== view) return@Runnable
                Log.d(TAG, "ntk_browser_broker_destroy_all_native path=$key,reason=$reason")
                webView = null
                documentStartScriptHandler?.remove()
                documentStartScriptHandler = null
                currentLoadTarget = ""
                payloadShellPath = ""
                if (key.isNotEmpty()) {
                    NtkWebViewFallbackManager.quietForForegroundNativeReader(
                        currentAppContext,
                        key,
                        "broker_native_destroy_all:$reason"
                    )
                }
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.onPause()
                view.removeAllViews()
                view.destroy()
                currentPath = ""
                currentCacheProducerGeneration = -1L
                currentListener = null
                currentDocumentUrl = ""
                controlledDocumentPath = ""
                controlledStripPath = ""
                controlledStripSig = ""
                controlledStripSetSig = ""
                firstDrawableReadyPath = ""
                viewportReadyPath = ""
                allDecodedReadyPath = ""
                if (key.isNotEmpty()) decodeSnapshotsByPath.remove(key)
                if (key.isNotEmpty()) protectedPrimeSigByPath.remove(key)
                pageStartedAtMs = 0L
            } catch (_: Throwable) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            view.post(action)
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
        controlledHtmlByPath.clear()
        decodeSnapshotsByPath.clear()
        currentPath = ""
        currentCacheProducerGeneration = -1L
        currentBaseUrl = ""
        currentDocumentUrl = ""
        currentLoadTarget = ""
        payloadShellPath = ""
        currentListener = null
        latestScrollSnapshot = null
        injectedViewerPayloadSigByPath.clear()
        protectedPrimeSigByPath.clear()
        controlledDocumentPath = ""
        controlledStripPath = ""
        controlledStripSig = ""
        controlledStripSetSig = ""
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                if (request.isForMainFrame && shouldBlockModernViewerMainFrameNavigation(url)) {
                    Log.d(
                        TAG,
                        "ntk_browser_broker_main_frame_nav_block path=$currentPath,url=${url.take(160)}"
                    )
                    return true
                }
                return false
            }

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
                if (request.isForMainFrame && controlledHtmlByPath[currentPath] != null && urlMatchesCurrentPath(url)) {
                    val html = controlledHtmlByPath[currentPath].orEmpty()
                    if (html.isNotEmpty()) {
                        Log.d(TAG, "ntk_browser_broker_controlled_document_intercept path=$currentPath,url=$url")
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            html.byteInputStream(Charsets.UTF_8)
                        )
                    }
                }
                val normalizedUrl = normalizeImageUrl(
                    url,
                    currentDocumentUrl.ifBlank { currentBaseUrl + currentPath }
                )
                val controlledNow = isControlledDocumentForCurrentPath()
                val knownControlledImages = if (controlledNow && isKpWebtoonSlugPath(currentPath)) {
                    pendingDiscoveredUrlsByPath[currentPath].orEmpty()
                        .map { normalizeImageUrl(it, currentDocumentUrl.ifBlank { currentBaseUrl + currentPath }) }
                        .toHashSet()
                } else {
                    emptySet()
                }
                val unknownKpControlledImage = controlledNow &&
                    knownControlledImages.isNotEmpty() &&
                    looksLikeReaderImageUrl(url, request.requestHeaders) &&
                    !knownControlledImages.contains(normalizedUrl)
                if (unknownKpControlledImage) {
                    Log.d(TAG, "ntk_browser_broker_controlled_image_block_unknown_kp path=$currentPath,url=$url")
                    return WebResourceResponse(
                        "image/gif",
                        null,
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                val primeOnly = primeOnlyUrlsByPath[currentPath]
                val speculativePrime = primeOnly != null && synchronized(primeOnly) {
                    primeOnly.contains(normalizedUrl)
                }
                if (!controlledNow && !speculativePrime && looksLikeReaderImageUrl(url, request.requestHeaders)) {
                    recordImages(
                        listOf(normalizedUrl),
                        "request",
                        currentDocumentUrl.ifBlank { currentBaseUrl + currentPath },
                        false,
                        true
                    )
                }
                val controlledImageRequest = controlledNow &&
                    (looksLikeReaderImageUrl(url, request.requestHeaders) ||
                        Regex("""^https?://[^/]+/(?:.*)/(?:p)?\d{1,5}\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$""", RegexOption.IGNORE_CASE).matches(url))
                if (controlledImageRequest) {
                    if (isModernNaturalOwnerPath(currentPath)) {
                        cachedControlledImageResponse(normalizedUrl)?.let { response ->
                            Log.d(
                                TAG,
                                "ntk_browser_broker_controlled_image_cache_hit path=$currentPath,url=$normalizedUrl"
                            )
                            return response
                        }
                        if (isKpWebtoonSlugPath(currentPath)) {
                            Log.d(
                                TAG,
                                "ntk_browser_broker_controlled_image_kp_intercept_start path=$currentPath,url=$url"
                            )
                            val kpIntercepted = try {
                                NtkWebViewFallbackManager.interceptViewerQuicResource(
                                    currentAppContext,
                                    currentUserAgent,
                                    currentCookieHeader,
                                    request
                                )
                            } catch (throwable: Throwable) {
                                Log.d(TAG, "ntk_browser_broker_controlled_image_kp_intercept_error path=$currentPath,url=$url,error=${throwable.javaClass.simpleName}:${throwable.message}")
                                null
                            }
                            if (kpIntercepted != null) return kpIntercepted
                            Log.d(
                                TAG,
                                "ntk_browser_broker_controlled_image_kp_intercept_miss path=$currentPath,url=$url"
                            )
                            return super.shouldInterceptRequest(view, request)
                        }
                        Log.d(
                            TAG,
                            "ntk_browser_broker_controlled_image_modern_intercept_start path=$currentPath,url=$url"
                        )
                        fetchModernControlledImageResponse(url, request.requestHeaders)?.let { response ->
                            Log.d(
                                TAG,
                                "ntk_browser_broker_controlled_image_modern_http_ok path=$currentPath,url=$url"
                            )
                            return response
                        }
                    }
                    Log.d(
                        TAG,
                        "ntk_browser_broker_controlled_image_intercept_start path=$currentPath,url=$url,main=${request.isForMainFrame},method=${request.method},headers=${request.requestHeaders}"
                    )
                    val intercepted = try {
                        NtkWebViewFallbackManager.interceptViewerQuicResource(
                            currentAppContext,
                            currentUserAgent,
                            currentCookieHeader,
                            request
                        )
                    } catch (throwable: Throwable) {
                        Log.d(TAG, "ntk_browser_broker_controlled_image_intercept_error path=$currentPath,url=$url,error=${throwable.javaClass.simpleName}:${throwable.message}")
                        null
                    }
                    if (intercepted != null) return intercepted
                    if (isModernNaturalOwnerPath(currentPath)) {
                        Log.d(
                            TAG,
                            "ntk_browser_broker_controlled_image_passthrough path=$currentPath,url=$url"
                        )
                        return super.shouldInterceptRequest(view, request)
                    }
                    Log.d(TAG, "ntk_browser_broker_controlled_image_intercept_miss path=$currentPath,url=$url")
                }
                val requestPath = try {
                    request.url?.path.orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                if (isModernNaturalOwnerPath(currentPath)) {
                    if (requestPath.startsWith("/api/")) {
                        Log.d(TAG, "ntk_natural_api_intercept_passthrough path=$currentPath,api=$requestPath")
                    } else if (
                        !request.isForMainFrame &&
                        shouldBlockModernViewerDecorRequest(url, requestPath) &&
                        !shouldAllowKpModernViewerEssentialScript(currentPath, url, requestPath)
                    ) {
                        Log.d(TAG, "ntk_browser_broker_decor_block path=$currentPath,url=${url.take(120)}")
                        return emptyWebResourceResponse(url)
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

    private fun chromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                Log.d(
                    TAG,
                    "ntk_browser_broker_popup_block path=$currentPath,userGesture=$isUserGesture,dialog=$isDialog"
                )
                return false
            }
        }
    }

    private fun controlledImagePageIndex(url: String): Int {
        val match = Regex("""/(?:p)?(\d{1,5})\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?').substringBefore('#'))
            ?: return -1
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun cachedControlledImageResponse(url: String): WebResourceResponse? {
        controlledForegroundBytes[controlledCacheKey(url)]?.let { bytes ->
            if (bytes.isNotEmpty()) {
                return WebResourceResponse(
                    imageMimeType(url),
                    null,
                    ByteArrayInputStream(bytes)
                )
            }
        }
        val bytes = ReaderImageCache.cachedNtkGeneratedImageBytes(currentAppContext, url)
        if (bytes == null || bytes.isEmpty()) return null
        return WebResourceResponse(
            imageMimeType(url),
            null,
            ByteArrayInputStream(bytes)
        )
    }

    private fun fetchModernControlledImageResponse(
        url: String,
        requestHeaders: Map<String, String>?
    ): WebResourceResponse? {
        controlledForegroundBytes[controlledCacheKey(url)]?.let { bytes ->
            if (bytes.isNotEmpty()) {
                Log.d(TAG, "ntk_browser_broker_controlled_image_foreground_cache_hit path=$currentPath,url=$url")
                return WebResourceResponse(imageMimeType(url), null, ByteArrayInputStream(bytes))
            }
        }
        val responseBytes = fetchModernControlledImageBytes(url, requestHeaders, cacheResult = true) ?: return null
        return WebResourceResponse(imageMimeType(url), null, ByteArrayInputStream(responseBytes))
    }

    private fun fetchModernControlledImageBytes(
        url: String,
        requestHeaders: Map<String, String>?,
        cacheResult: Boolean
    ): ByteArray? {
        if (!isModernNaturalOwnerPath(currentPath) || url.isBlank()) return null
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", currentUserAgent.ifBlank { requestHeaders?.get("User-Agent").orEmpty() })
                setRequestProperty(
                    "Accept",
                    requestHeaders?.get("Accept") ?: "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                )
                setRequestProperty(
                    "Referer",
                    requestHeaders?.get("Referer") ?: controlledStripBaseUrl(url, currentDocumentUrl.ifBlank { currentBaseUrl + currentPath })
                )
                val cookie = currentCookieHeader.ifBlank { requestHeaders?.get("Cookie").orEmpty() }
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                connection = null
                return null
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.isNotBlank() && !contentType.startsWith("image/")) {
                connection.disconnect()
                connection = null
                Log.d(
                    TAG,
                    "ntk_browser_broker_controlled_image_modern_http_reject path=$currentPath," +
                        "url=$url,reason=content_type,contentType=$contentType"
                )
                return null
            }
            val rawBytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            connection = null
            if (rawBytes.size < 1024) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_controlled_image_modern_http_reject path=$currentPath," +
                        "url=$url,reason=small,bytes=${rawBytes.size}"
                )
                return null
            }
            val responseBytes = resizeModernControlledImageBytes(rawBytes, url) ?: rawBytes
            if (cacheResult && responseBytes.isNotEmpty()) {
                controlledForegroundBytes[controlledCacheKey(url)] = responseBytes
            }
            responseBytes
        } catch (e: Throwable) {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_image_modern_http_miss path=$currentPath," +
                    "url=$url,error=${e.javaClass.simpleName}"
            )
            null
        }
    }

    private fun controlledCacheKey(url: String): String {
        return try {
            val parsed = Uri.parse(url)
            "${parsed.scheme.orEmpty().lowercase(Locale.ROOT)}://${parsed.encodedAuthority.orEmpty().lowercase(Locale.ROOT)}${parsed.encodedPath.orEmpty()}"
        } catch (_: Throwable) {
            url.substringBefore('?').substringBefore('#')
        }
    }

    private fun resizeModernControlledImageBytes(bytes: ByteArray, url: String): ByteArray? {
        if (bytes.size < 256 * 1024) return null
        val context = currentAppContext ?: return null
        val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0 || sourceWidth <= (targetWidth * 1.15f).toInt()) return null
            var sample = 1
            while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return null
            val scaled = if (decoded.width > targetWidth) {
                val targetHeight = ((decoded.height.toLong() * targetWidth) / decoded.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            } else {
                decoded
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val resized = out.toByteArray()
            if (resized.isEmpty() || resized.size >= bytes.size) null else resized
        } catch (e: Throwable) {
            Log.d(TAG, "ntk_browser_broker_controlled_image_resize_miss path=$currentPath,url=$url,error=${e.javaClass.simpleName}")
            null
        }
    }

    private fun imageMimeType(url: String): String {
        val lower = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun emptyWebResourceResponse(url: String): WebResourceResponse {
        val lower = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val mime = when {
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff") || lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ttf") -> "font/ttf"
            else -> "text/plain"
        }
        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun shouldBlockModernViewerDecorRequest(url: String, path: String): Boolean {
        if (url.isBlank()) return false
        if (looksLikeReaderImageUrl(url, null)) return false
        if (path.startsWith("/api/")) return false
        val lower = path.lowercase(Locale.ROOT)
        if (lower.contains("theme-viewer-data")) return false
        if (lower.endsWith(".css") || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")) {
            return true
        }
        return lower.startsWith("/thema/") ||
            lower.startsWith("/assets/css/") ||
            lower.startsWith("/assets/img/") ||
            lower.startsWith("/assets/plugin/") ||
            lower.contains("/widget/")
    }

    private fun shouldAllowKpModernViewerEssentialScript(currentPath: String, url: String, path: String): Boolean {
        if (!isKpWebtoonSlugPath(currentPath)) return false
        val lowerPath = path.lowercase(Locale.ROOT)
        val lowerUrl = url.lowercase(Locale.ROOT)
        if (!lowerPath.endsWith(".js")) return false
        return lowerUrl.contains("jquery") ||
            lowerUrl.contains("js.cookie") ||
            lowerUrl.contains("common") ||
            lowerUrl.contains("apms") ||
            lowerUrl.contains("wrest") ||
            lowerUrl.contains("viewer") ||
            lowerPath.startsWith("/assets/js/") ||
            lowerPath.startsWith("/thema/")
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
                Log.d(TAG, "ntk_browser_broker_images_parse_error path=$currentPath,$e")
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

    private fun clearDocumentStartScripts() {
        try {
            documentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        try {
            ackDocumentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        try {
            discoveryDocumentStartScriptHandler?.remove()
        } catch (_: Throwable) {
        }
        documentStartScriptHandler = null
        ackDocumentStartScriptHandler = null
        discoveryDocumentStartScriptHandler = null
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

    private fun shouldBlockModernViewerMainFrameNavigation(rawUrl: String?): Boolean {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) return false
        val lower = value.lowercase(Locale.ROOT)
        if (lower == "about:blank" || lower.startsWith("data:text/html")) return false
        if (!isModernNaturalOwnerPath(currentPath)) return false
        if (
            lower.startsWith("intent:") ||
            lower.startsWith("market:") ||
            lower.startsWith("tel:") ||
            lower.startsWith("mailto:")
        ) {
            return true
        }
        return try {
            val uri = Uri.parse(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            if (scheme != "http" && scheme != "https") return true
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path.orEmpty()
            val currentHost = Uri.parse(currentBaseUrl).host?.lowercase(Locale.ROOT).orEmpty()
            if (host == currentHost && normalizePath(path) == currentPath) return false
            if (host == currentHost && path.equals("/api/webtoon-images", ignoreCase = true)) return false
            if (looksLikeReaderImageUrl(value, null)) return false
            val adLike = lower.contains("tvwiki") ||
                lower.contains("adsterra") ||
                lower.contains("doubleclick") ||
                lower.contains("googlesyndication") ||
                lower.contains("googleadservices") ||
                lower.contains("popads") ||
                lower.contains("onclick") ||
                lower.contains("banner") ||
                lower.contains("advert") ||
                lower.contains("sponsor")
            if (adLike) return true
            host.isNotEmpty() && host != currentHost && !host.endsWith(".${currentHost}")
        } catch (_: Throwable) {
            true
        }
    }

    private fun isKpWebtoonSlugPath(path: String): Boolean {
        return path.matches(Regex("^/webtoon/\\d{1,12}/kp-[^/?#]+(?:[/?#].*)?$", RegexOption.IGNORE_CASE))
    }

    private fun isNtkUploadCdnImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (isTrustedNtkUploadPayloadImageUrl(lower)) return true
        if (isNtkUploadDescriptorUrl(lower)) return false
        return lower.contains("/webtoon_uploads/") ||
            lower.contains("/manhwa_uploads/") ||
            lower.contains("/comic_uploads/") ||
            lower.matches(Regex("^https?://[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#].*)?$"))
    }

    private fun isNtkUploadTextImageUrl(url: String): Boolean {
        return isNtkUploadDescriptorUrl(url)
    }

    private fun isNtkUploadDescriptorUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (isTrustedNtkUploadPayloadImageUrl(lower)) return false
        if (isNtkKpDescriptorImageUrl(lower)) return true
        return lower.matches(Regex(".*(?:/webtoon_uploads/|/manhwa_uploads/|/comic_uploads/|messiimage\\.online/|aws-cdn\\d*\\.site/)[^/?#]+\\.(?:txt|xml|json|css|js)(?:[?#].*)?$"))
    }

    private fun isNtkKpDescriptorImageUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        val lower = value.lowercase(Locale.ROOT)
            .replace("\\/", "/")
            .replace("\\u002f", "/")
        return try {
            val uri = Uri.parse(lower)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
            val extOk = Regex(".*\\.(?:txt|xml|json|css|js|woff|woff2)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
                .matches(lower)
            extOk &&
                ((host == "ioiocdn.org" || host.endsWith(".ioiocdn.org")) ||
                    Regex(".*/(?:cv|mx|qc|rs)/.*", RegexOption.IGNORE_CASE).matches(path)) &&
                path.isNotBlank() &&
                !path.contains("banner") &&
                !path.contains("advert") &&
                !path.contains("sponsor")
        } catch (_: Throwable) {
            Regex(
                "^https?://[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\.(?:txt|xml|json|css|js|woff|woff2)(?:[?#].*)?$",
                RegexOption.IGNORE_CASE
            ).matches(lower)
        }
    }

    private fun isTrustedNtkUploadPayloadImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.matches(
            Regex(
                "^https?://(?:[^/?#]+\\.)?(?:hkhk\\d+\\.store|ronald\\d+\\.online|christ\\d+\\.shop|aws-cdn\\d*\\.site|messiimage\\.online|fvcdn\\d*\\.com|flysky\\d*m\\.com|apihost\\d*\\.com)/(?:webtoon_uploads|manhwa_uploads|comic_uploads)/[^/?#]+\\.(?:txt|xml)(?:[?#].*)?$"
            )
        )
    }

    private fun expandKpProtectedApiUrlsFromExisting(path: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val existing = authoritativeUrlsByPath[path].orEmpty()
            .firstOrNull { isProtectedApiImageUrl(it) }
            ?: snapshots[path]?.images.orEmpty().firstOrNull { isProtectedApiImageUrl(it) }
            ?: return emptyList()
        val token = try {
            Uri.parse(existing).getQueryParameter("a").orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (token.isBlank()) return emptyList()
        val base = currentBaseUrl.trimEnd('/').ifBlank {
            try {
                val uri = Uri.parse(existing)
                "${uri.scheme}://${uri.host}".trimEnd('/')
            } catch (_: Throwable) {
                ""
            }
        }
        if (base.isBlank()) return emptyList()
        val encoded = Uri.encode(token)
        return (0 until count.coerceIn(1, 128)).map { index ->
            "$base/api/m/i?a=$encoded&i=$index"
        }
    }

    private fun isCanonicalGeneratedDirectPath(path: String): Boolean {
        return path.matches(Regex("^/(?:manhwa|webtoon)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$", RegexOption.IGNORE_CASE))
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
                if (path != currentPath || webView == null) {
                    Log.d(TAG, "ntk_browser_broker_key_precompute_cancel_stale_start path=$path,current=$currentPath")
                    return@Thread
                }
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
                if (path != currentPath || webView == null) {
                    Log.d(TAG, "ntk_browser_broker_key_precompute_cancel_stale_after_nv path=$path,current=$currentPath")
                    return@Thread
                }
                val keyRaw = bridge.ensureViewerBrowserKeyForUserAgent(pageUrl, userAgent)
                if (path != currentPath || webView == null) {
                    Log.d(TAG, "ntk_browser_broker_key_precompute_cancel_stale_after_key path=$path,current=$currentPath")
                    return@Thread
                }
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
        thread.priority = Thread.NORM_PRIORITY - 1
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
                "try{window.__mvNtkInjectedViewerData=$payloadJson;" +
                    "if(window.__mvNtkTryViewerApiFast)window.__mvNtkTryViewerApiFast('payload-fast-'+$sourceJson);" +
                    "if(window.__mvNtkScan)window.__mvNtkScan('payload-'+$sourceJson);}catch(e){}",
                null
            )
            Log.d(TAG, "ntk_browser_broker_payload path=$path,bytes=${payload.length},source=$source")
        } catch (e: Throwable) {
            currentListener?.onError(path, e.toString())
        }
    }

    private fun injectPrimeUrls(view: WebView, path: String, urls: List<String>, source: String) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val documentUrl = currentDocumentUrl.ifBlank { currentBaseUrl + path }
        val normalizedUrls = strictReaderStripUrls(urls, documentUrl)
        if (normalizedUrls.isEmpty()) return
        val json = JSONArray(normalizedUrls).toString()
        val sourceJson = JSONObject.quote(source)
        try {
            view.evaluateJavascript(
                "try{if(window.__mvNtkPrime)window.__mvNtkPrime($json,$sourceJson);else window.__mvNtkPendingPrime=$json;}catch(e){}",
                null
            )
            Log.d(
                TAG,
                "ntk_browser_broker_prime path=$path,count=${normalizedUrls.size},source=$source," +
                    "head=${normalizedUrls.take(3).joinToString("|")}"
            )
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
        if (isStrictEpisodePath(key)) {
            Log.d(TAG, "ntk_browser_broker_images_rejected_by_strict_owner path=$key,source=$source")
            return
        }
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
            if (
                modernNtkReader &&
                isKpWebtoonSlugPath(key) &&
                snapshotUrls.isNotEmpty() &&
                snapshotUrls.all { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
            ) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_images_skip_kp_browser_owned_manifest path=$key," +
                        "count=${snapshotUrls.size},source=$source"
                )
                return
            }
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
            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                key,
                snapshotUrls,
                source,
                currentCacheProducerGeneration
            )
            Log.d(
                TAG,
                "ntk_browser_broker_images path=$key,count=${snapshotUrls.size},source=$source," +
                    "head=${snapshotUrls.take(3).joinToString("|")}"
            )
            currentListener?.onImages(snapshot)
            if (!currentForceBrowserAuthoritative &&
                currentListener != null &&
                shouldGiveNativeHandoffWindow(key, snapshotUrls, source, currentExpectedImageCount)
            ) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_skip_controlled_for_native path=$key,count=${snapshotUrls.size}," +
                        "expected=$currentExpectedImageCount,source=$source"
                )
                return
            }
            if (canDisplayControlledStripFromSource(source, snapshotUrls.size, currentExpectedImageCount)) {
                maybeLoadControlledStrip(
                    key,
                    snapshotUrls,
                    source,
                    documentUrl,
                    allowNativeHandoffWindow = !currentForceBrowserAuthoritative
                )
            } else {
                Log.d(
                    TAG,
                    "ntk_browser_broker_images_record_only path=$key,count=${snapshotUrls.size}," +
                        "expected=$currentExpectedImageCount,source=$source"
                )
            }
        }
    }

    private fun replaceWithAuthoritativeImages(
        key: String,
        urls: List<String>,
        source: String,
        documentUrl: String
    ) {
        if (key.isEmpty() || key != currentPath || urls.isEmpty()) return
        if (isStrictEpisodePath(key)) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_rejected_by_strict_owner path=$key,source=$source"
            )
            return
        }
        val snapshotUrls = strictReaderStripUrls(urls, documentUrl)
        if (snapshotUrls.isEmpty()) return
        val expectedBefore = currentExpectedImageCount.coerceAtLeast(0)
        if (isKpWebtoonSlugPath(key) && expectedBefore > 0 && snapshotUrls.size < expectedBefore) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_kp_shrink path=$key,count=${snapshotUrls.size}," +
                    "expected=$expectedBefore,source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            controlledDocumentPath == key &&
            source.contains("foreground-viewer-images", ignoreCase = true)
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_kp_foreground_echo path=$key," +
                    "count=${snapshotUrls.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            controlledDocumentPath == key &&
            source.contains("kp-web-primary-request", ignoreCase = true)
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_kp_request_echo path=$key," +
                    "count=${snapshotUrls.size},expected=$currentExpectedImageCount,source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(key) &&
            controlledDocumentPath == key &&
            currentExpectedImageCount > 0 &&
            snapshotUrls.size >= currentExpectedImageCount &&
            snapshotUrls.all { isProtectedApiImageUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_skip_kp_protected_reload path=$key," +
                    "count=${snapshotUrls.size},expected=$currentExpectedImageCount,source=$source"
            )
            return
        }
        if (expectedBefore <= 0 || snapshotUrls.size < expectedBefore || isAuthoritativeUrlSource(source)) {
            currentExpectedImageCount = snapshotUrls.size
        }
        val previousPrime = pendingPrimeUrlsByPath[key].orEmpty()
        pendingPrimeUrlsByPath[key] = snapshotUrls
        val bucket = imageUrlsByPath.getOrPut(key) { LinkedHashSet() }
        synchronized(bucket) {
            bucket.clear()
            bucket.addAll(snapshotUrls)
        }
        val snapshot = ImageSnapshot(
            currentBaseUrl,
            key,
            documentUrl,
            snapshotUrls,
            source,
            false,
            SystemClock.elapsedRealtime()
        )
        snapshots[key] = snapshot
        val kpProtectedManifest = isKpWebtoonSlugPath(key) &&
            snapshotUrls.isNotEmpty() &&
            snapshotUrls.all { isProtectedApiImageUrl(it) } &&
            (currentExpectedImageCount <= 0 || snapshotUrls.size >= currentExpectedImageCount)
        if (kpProtectedManifest) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_kp_protected_metric_manifest path=$key," +
                    "count=${snapshotUrls.size},expected=$currentExpectedImageCount,source=$source"
            )
            return
        }
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            key,
            snapshotUrls,
            source,
            currentCacheProducerGeneration
        )
        currentListener?.onImages(snapshot)
        if (
            currentListener != null &&
            isKpWebtoonSlugPath(key) &&
            snapshotUrls.any { isProtectedApiImageUrl(it) } &&
            hasStrictNtkProtectedApiProof(key)
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_kp_protected_controlled path=$key," +
                    "count=${snapshotUrls.size},expected=$currentExpectedImageCount,source=$source"
            )
        }
        Log.d(
            TAG,
            "ntk_browser_broker_authoritative_replace path=$key,count=${snapshotUrls.size}," +
                "expectedBefore=$expectedBefore,expectedNow=$currentExpectedImageCount,source=$source," +
                "head=${snapshotUrls.take(3).joinToString("|")}"
        )
        if (!currentForceBrowserAuthoritative &&
            currentListener != null &&
            shouldGiveNativeHandoffWindow(key, snapshotUrls, source, currentExpectedImageCount)
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_native_only path=$key,count=${snapshotUrls.size}," +
                    "expected=$currentExpectedImageCount,source=$source"
            )
            return
        }
        if (
            controlledDocumentPath == key &&
            previousPrime.isNotEmpty() &&
            snapshotUrls.size > previousPrime.size &&
            hasSameControlledPrefix(previousPrime, snapshotUrls, documentUrl)
        ) {
            appendControlledStripImages(key, previousPrime.size, snapshotUrls, source, documentUrl)
            Log.d(
                TAG,
                "ntk_browser_broker_authoritative_extend_append path=$key," +
                    "old=${previousPrime.size},new=${snapshotUrls.size},source=$source"
            )
            return
        }
        maybeLoadControlledStrip(
            key,
            snapshotUrls,
            "authoritative-$source",
            documentUrl,
            allowNativeHandoffWindow = !currentForceBrowserAuthoritative
        )
    }

    private fun appendControlledStripImages(
        path: String,
        previousCount: Int,
        urls: List<String>,
        source: String,
        documentUrl: String
    ) {
        val view = webView ?: return
        if (path != currentPath || controlledDocumentPath != path) return
        val finalUrls = strictReaderStripUrls(urls, documentUrl)
        if (finalUrls.size <= previousCount) return
        val expected = currentExpectedImageCount.coerceAtLeast(finalUrls.size)
        val sig = "doc:" + finalUrls.joinToString("|") { controlledStripUrlSignature(it) }
        val setSig = if (isKpWebtoonSlugPath(path)) {
            "set:" + finalUrls.map { controlledStripUrlSignature(it) }.sorted().joinToString("|")
        } else {
            sig
        }
        controlledStripPath = path
        controlledStripSig = sig
        controlledStripSetSig = setSig
        pendingDiscoveredUrlsByPath[path] = finalUrls
        startControlledForegroundFetch(path, finalUrls)
        val json = JSONArray(finalUrls).toString()
        val sourceJson = JSONObject.quote(source)
        runOnWebViewThread(view) {
            if (path != currentPath || controlledDocumentPath != path) return@runOnWebViewThread
            try {
                view.evaluateJavascript(
                    "window.__mvNtkAppendControlledStrip && " +
                        "window.__mvNtkAppendControlledStrip($json,$expected,$sourceJson);",
                    null
                )
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_authoritative_append_error path=$path,error=${e.javaClass.simpleName}")
            }
        }
    }

    private fun hasSameControlledPrefix(
        previous: List<String>,
        next: List<String>,
        documentUrl: String
    ): Boolean {
        val oldUrls = strictReaderStripUrls(previous, documentUrl)
        val newUrls = strictReaderStripUrls(next, documentUrl)
        if (oldUrls.isEmpty() || newUrls.size < oldUrls.size) return false
        for (index in oldUrls.indices) {
            if (controlledStripUrlSignature(oldUrls[index]) != controlledStripUrlSignature(newUrls[index])) {
                return false
            }
        }
        return true
    }

    private fun isAuthoritativeUrlSource(source: String): Boolean {
        val s = source.lowercase(Locale.ROOT)
        return s.contains("authoritative") ||
            s.contains("early-cache") ||
            s.contains("verified-generated") ||
            s.contains("native-api") ||
            s.contains("viewer-api-token") ||
            s.contains("resolved") ||
            s.contains("canonical-direct")
    }

    private fun canDisplayControlledStripFromSource(source: String, count: Int, expected: Int): Boolean {
        val s = source.lowercase(Locale.ROOT)
        if (isKpWebtoonSlugPath(currentPath) && count >= 8 &&
            (s.contains("candidate") || s.contains("mutation") || s.contains("interval"))
        ) {
            return true
        }
        if (
            s.contains("viewer-api") ||
            s.contains("native-api") ||
            s.contains("foreground-viewer-images") ||
            s.contains("payload") ||
            s.contains("verified-generated") ||
            s.contains("early-cache") ||
            s.contains("authoritative")
        ) {
            return true
        }
        if (
            s.contains("request") ||
            s.contains("dom") ||
            s.contains("mutation") ||
            s.contains("interval") ||
            s.contains("install") ||
            (s.contains("candidate") && !s.contains("prime-verify"))
        ) {
            return false
        }
        if (
            s.contains("initial-prime") ||
            s.contains("prime-verify")
        ) {
            return true
        }
        return expected >= 8 && count >= expected
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
        documentUrl: String,
        allowNativeHandoffWindow: Boolean = true
    ) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val expected = currentExpectedImageCount
        val stripUrls = strictReaderStripUrls(urls, documentUrl)
        if (stripUrls.isEmpty()) {
            pendingDiscoveredUrlsByPath[path] = stripUrls
            return
        }
        val renderableNonDescriptorUrls = stripUrls.filterNot { isNtkUploadDescriptorUrl(it) }
        val finalUrls = if (!isKpWebtoonSlugPath(path) && renderableNonDescriptorUrls.isNotEmpty()) {
            renderableNonDescriptorUrls
        } else {
            stripUrls
        }
        if (
            !isKpWebtoonSlugPath(path) &&
            finalUrls.all { isNtkUploadDescriptorUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_skip_descriptor_only path=$path," +
                    "count=${finalUrls.size},source=$source"
            )
            return
        }
        pendingDiscoveredUrlsByPath[path] = finalUrls
        val view = webView ?: return
        if (
            isKpWebtoonSlugPath(path) &&
            finalUrls.isNotEmpty() &&
            finalUrls.all { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
        ) {
            val target = documentUrl.ifBlank { currentBaseUrl + path }
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_skip_kp_browser_owned path=$path,count=${finalUrls.size}," +
                    "expected=$expected,source=$source,url=${view.url.orEmpty().take(120)}"
            )
            if (view.url != target && currentLoadTarget != target) {
                runOnWebViewThreadFront(view) {
                    if (path != currentPath) return@runOnWebViewThreadFront
                    currentLoadTarget = target
                    payloadShellPath = path
                    view.loadUrl(target)
                }
            }
            return
        }
        if (allowNativeHandoffWindow && shouldGiveNativeHandoffWindow(path, finalUrls, source, expected)) {
            val scheduledSig = finalUrls.joinToString("|") { controlledStripUrlSignature(it) }
            val handoffWindowMs = if (source.contains("authoritative", ignoreCase = true) ||
                source.contains("payload", ignoreCase = true) ||
                source.contains("viewer-api", ignoreCase = true) ||
                source.contains("foreground-viewer-images", ignoreCase = true)
            ) {
                0L
            } else {
                120L
            }
            val runHandoff = Runnable {
                if (path != currentPath || webView !== view) return@Runnable
                if (controlledDocumentPath == path) return@Runnable
                val latest = pendingDiscoveredUrlsByPath[path].orEmpty()
                val latestSig = latest.joinToString("|") { controlledStripUrlSignature(it) }
                if (latestSig != scheduledSig) return@Runnable
                maybeLoadControlledStrip(path, latest, source, documentUrl, allowNativeHandoffWindow = false)
            }
            if (handoffWindowMs <= 0L) {
                view.post(runHandoff)
            } else {
                view.postDelayed(runHandoff, handoffWindowMs)
            }
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_native_handoff_window path=$path,count=${finalUrls.size}," +
                    "expected=$expected,delayMs=$handoffWindowMs,source=$source"
            )
            return
        }
        if (!canDisplayControlledStripFromSource(source, finalUrls.size, expected)) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_defer_untrusted path=$path,count=${finalUrls.size}," +
                    "expected=$expected,source=$source"
            )
            return
        }
        if (expected > 0 && finalUrls.size < expected) {
            val fullPrimeUrls = strictReaderStripUrls(pendingPrimeUrlsByPath[path].orEmpty(), documentUrl)
            if (controlledDocumentPath == path || fullPrimeUrls.size >= expected) {
                Log.d(
                    TAG,
                    "ntk_browser_broker_partial_strip_ignored path=$path,count=${finalUrls.size}," +
                        "expected=$expected,source=$source,controlled=${controlledDocumentPath == path}," +
                        "prime=${fullPrimeUrls.size}"
                )
                return
            }
            runOnWebViewThread(view) {
                if (path != currentPath) return@runOnWebViewThread
                renderNaturalStrip(view, path, finalUrls, "partial-$source")
            }
            Log.d(
                TAG,
                "ntk_browser_broker_natural_strip_update path=$path,count=${finalUrls.size}," +
                    "expected=$expected,source=$source"
            )
            return
        }
        val sig = "doc:" + finalUrls.joinToString("|") { controlledStripUrlSignature(it) }
        val setSig = if (isKpWebtoonSlugPath(path)) {
            "set:" + finalUrls.map { controlledStripUrlSignature(it) }.sorted().joinToString("|")
        } else {
            sig
        }
        if (
            controlledDocumentPath == path &&
            isKpWebtoonSlugPath(path) &&
            currentExpectedImageCount > 0 &&
            finalUrls.size >= currentExpectedImageCount &&
            finalUrls.none { isProtectedApiImageUrl(it) || isNtkKpDescriptorImageUrl(it) }
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_keep_existing_kp_full path=$path," +
                    "count=${finalUrls.size},expected=$expected,source=$source"
            )
            runOnWebViewThread(view) {
                if (path != currentPath) return@runOnWebViewThread
                try {
                    view.evaluateJavascript(
                        "try{if(window.__mvNtkPumpPromoteAll)window.__mvNtkPumpPromoteAll();else if(window.__mvNtkPromoteTop)window.__mvNtkPromoteTop(4);}catch(e){}",
                        null
                    )
                } catch (_: Throwable) {
                }
            }
            return
        }
        if (
            controlledDocumentPath == path &&
            controlledStripPath == path &&
            isKpWebtoonSlugPath(path) &&
            currentExpectedImageCount > 0 &&
            controlledStripSig == sig &&
            finalUrls.size >= currentExpectedImageCount
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_keep_existing_kp path=$path," +
                    "count=${finalUrls.size},expected=$expected,source=$source"
            )
            runOnWebViewThread(view) {
                if (path != currentPath) return@runOnWebViewThread
                try {
                    view.evaluateJavascript(
                        "try{if(window.__mvNtkPumpPromoteAll)window.__mvNtkPumpPromoteAll();else if(window.__mvNtkPromoteTop)window.__mvNtkPromoteTop(4);}catch(e){}",
                        null
                    )
                } catch (_: Throwable) {
                }
            }
            return
        }
        if (controlledStripPath == path && controlledStripSig == sig && controlledDocumentPath != path) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_pending_same path=$path,count=${finalUrls.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(path) &&
            controlledStripPath == path &&
            controlledStripSetSig == setSig &&
            controlledDocumentPath != path
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_pending_same_set_kp path=$path,count=${finalUrls.size},source=$source"
            )
            return
        }
        if (controlledStripPath == path && controlledStripSig == sig && controlledDocumentPath == path) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_skip_same path=$path,count=${finalUrls.size},source=$source"
            )
            return
        }
        if (
            isKpWebtoonSlugPath(path) &&
            controlledStripPath == path &&
            controlledStripSetSig == setSig &&
            controlledDocumentPath == path
        ) {
            Log.d(
                TAG,
                "ntk_browser_broker_controlled_document_keep_existing_set_kp path=$path," +
                    "count=${finalUrls.size},expected=$expected,source=$source"
            )
            runOnWebViewThread(view) {
                if (path != currentPath) return@runOnWebViewThread
                try {
                    view.evaluateJavascript(
                        "try{if(window.__mvNtkPumpPromoteAll)window.__mvNtkPumpPromoteAll();else if(window.__mvNtkPromoteTop)window.__mvNtkPromoteTop(4);}catch(e){}",
                        null
                    )
                } catch (_: Throwable) {
                }
            }
            return
        }
        startControlledForegroundFetch(path, finalUrls)
        controlledStripPath = path
        controlledStripSig = sig
        controlledStripSetSig = setSig
        payloadShellPath = ""
        val html = controlledStripHtml(finalUrls, expected, "controlled-$source", documentUrl)
        val controlledBaseUrl = controlledStripBaseUrl(finalUrls.firstOrNull(), documentUrl)
        if (isKpWebtoonSlugPath(path)) clearDocumentStartScripts()
        runOnWebViewThreadFront(view) {
            if (path != currentPath) return@runOnWebViewThreadFront
            try {
                view.stopLoading()
                view.scrollTo(0, 0)
                currentLoadTarget = documentUrl
                controlledDocumentPath = path
                view.loadDataWithBaseURL(controlledBaseUrl, html, "text/html", "UTF-8", null)
                Log.d(
                    TAG,
                    "ntk_browser_broker_controlled_document_load path=$path,count=${finalUrls.size}," +
                        "expected=$expected,source=$source,base=$controlledBaseUrl"
                )
            } catch (e: Throwable) {
                if (controlledDocumentPath == path) controlledDocumentPath = ""
                currentListener?.onError(path, e.toString())
            }
        }
    }

    private fun controlledWaitingHtml(): String {
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>
                html,body{margin:0;padding:0;background:#111;overflow:hidden;}
              </style>
            </head>
            <body></body>
            </html>
        """.trimIndent()
    }

    private fun shouldGiveNativeHandoffWindow(
        path: String,
        urls: List<String>,
        source: String,
        expected: Int
    ): Boolean {
        if (!isModernNaturalOwnerPath(path)) return false
        if (isKpWebtoonSlugPath(path)) return false
        if (expected <= 0 || urls.size < expected) return false
        if (urls.any { it.contains("/api/m/i?", ignoreCase = true) }) return false
        return isAuthoritativeUrlSource(source) || canDisplayControlledStripFromSource(source, urls.size, expected)
    }

    @JvmStatic
    fun pumpControlledForegroundFetch(path: String?, reason: String) {
        val key = normalizePath(path)
        if (key.isEmpty() || key != currentPath) return
        val pending = authoritativeUrlsByPath[key].orEmpty().ifEmpty {
            pendingDiscoveredUrlsByPath[key].orEmpty()
        }
        if (pending.isEmpty()) return
        val documentUrl = currentDocumentUrl.ifBlank { currentBaseUrl + key }
        val normalized = strictReaderStripUrls(pending, documentUrl)
        if (normalized.isEmpty()) return
        Log.d(
            TAG,
            "ntk_browser_broker_controlled_foreground_pump_retry path=$key,count=${normalized.size},reason=$reason"
        )
        scheduleControlledForegroundFetch(key, normalized, "retry-$reason")
    }

    private fun startControlledForegroundFetch(path: String, urls: List<String>) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        if (!isModernNaturalOwnerPath(path)) return
        val normalized = strictReaderStripUrls(urls, currentDocumentUrl.ifBlank { currentBaseUrl + path })
        if (normalized.isEmpty()) return
        Log.d(
            TAG,
            "ntk_browser_broker_controlled_foreground_start_full path=$path,count=${normalized.size}"
        )
        scheduleControlledForegroundFetch(path, normalized, "initial-full")
    }

    private fun scheduleControlledForegroundFetch(path: String, urls: List<String>, reason: String) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val priority = ArrayList<String>(urls.size)
        priority.addAll(urls.take(minOf(12, urls.size)))
        if (urls.size > 12) {
            priority.addAll(urls.subList(12, urls.size))
        }
        for (url in priority.distinct()) {
            val key = controlledCacheKey(url)
            if (controlledForegroundBytes.containsKey(key)) continue
            if (!controlledForegroundFlights.add(key)) continue
            controlledForegroundExecutor.execute {
                try {
                    if (path != currentPath) return@execute
                    val startedAt = SystemClock.elapsedRealtime()
                    val bytes = fetchModernControlledImageBytes(url, null, cacheResult = true)
                    val ms = SystemClock.elapsedRealtime() - startedAt
                    if (bytes != null && bytes.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "ntk_browser_broker_controlled_foreground_fetch_ok path=$path," +
                                "url=$url,bytes=${bytes.size},ms=$ms,reason=$reason"
                        )
                    }
                } finally {
                    controlledForegroundFlights.remove(key)
                }
            }
        }
    }

    private fun controlledStripBaseUrl(firstImageUrl: String?, documentUrl: String): String {
        if (isKpWebtoonSlugPath(currentPath) && isNtkKpDescriptorImageUrl(firstImageUrl)) {
            return documentUrl
        }
        return try {
            val parsed = Uri.parse(firstImageUrl.orEmpty())
            val scheme = parsed.scheme
            val authority = parsed.encodedAuthority
            if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
                "$scheme://$authority/"
            } else {
                documentUrl
            }
        } catch (_: Throwable) {
            documentUrl
        }
    }

    private inline fun runOnWebViewThread(view: WebView, crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            view.post { block() }
        }
    }

    private inline fun runOnWebViewThreadFront(view: WebView, crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                if (webView === view) block()
            }
        }
    }

    private fun renderNaturalStrip(view: WebView, path: String, urls: List<String>, source: String) {
        if (path.isEmpty() || path != currentPath || urls.isEmpty()) return
        val documentUrl = currentDocumentUrl.ifBlank { currentBaseUrl + path }
        val normalizedUrls = strictReaderStripUrls(urls, documentUrl)
        if (normalizedUrls.isEmpty()) return
        val json = JSONArray(normalizedUrls).toString()
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
        val kpContext = isKpWebtoonSlugPath(currentPath) ||
            try {
                isKpWebtoonSlugPath(Uri.parse(documentUrl).path.orEmpty())
            } catch (_: Throwable) {
                false
            }
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
                    val preferIncoming = if (isKpWebtoonSlugPath(currentPath)) {
                        existing == null ||
                            (!isProtectedApiImageUrl(existing) && isProtectedApiImageUrl(normalized))
                    } else {
                        existing == null ||
                            (isProtectedApiImageUrl(existing) && !isProtectedApiImageUrl(normalized))
                    }
                    if (preferIncoming) {
                        byPage[page] = normalized
                    }
                } else if (
                    normalized.contains("mvpage=", ignoreCase = true) ||
                    seen.add(normalized)
                ) {
                    out.add(normalized)
                }
            }
        }
        if (byPage.isNotEmpty()) {
            if (kpContext) {
                val renderableOut = out.filter {
                    !isProtectedApiImageUrl(it) && !isNtkKpDescriptorImageUrl(it)
                }
                if (renderableOut.isNotEmpty()) {
                    return renderableOut
                }
            }
            return byPage.toSortedMap().values.toList()
        }
        if (out.none { readerImagePageIndex(it) != Int.MAX_VALUE }) {
            return out
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
            uri.fragment.orEmpty()
                .split('&')
                .firstOrNull { it.startsWith("mvpage=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.toIntOrNull()
                ?.let { return (it - 1).coerceAtLeast(0) }
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

    private fun hasStrictNtkProtectedApiProof(path: String): Boolean {
        return try {
            MainApplication.getHttpClient().hasRecentStrictNtkAdAckProof(path)
        } catch (_: Throwable) {
            false
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
        val normalizedUrls = urls.map { normalizeImageUrl(it, documentUrl) }.distinct()
        if (normalizedUrls.isNotEmpty() && normalizedUrls.all { isProtectedApiImageUrl(it) }) {
            return controlledProtectedApiStripHtml(normalizedUrls, expected, source, documentUrl)
        }
        val json = JSONArray(normalizedUrls).toString()
        val sourceJson = JSONObject.quote(source)
        val docJson = JSONObject.quote(documentUrl)
        val expectedCount = expected.coerceAtLeast(0)
        val backgroundPrepared = source.contains("initial-prime", ignoreCase = true) ||
            source.contains("hidden", ignoreCase = true) ||
            source.contains("visible", ignoreCase = true) ||
            source.contains("likely", ignoreCase = true)
        val kpDirectControlledStrip = isKpWebtoonSlugPath(currentPath) &&
            normalizedUrls.any { it.contains("/board_uploads/", ignoreCase = true) }
        val initialStaticImages = normalizedUrls.size
        val highPriorityImages = minOf(normalizedUrls.size, 4)
        val preloadLinks = normalizedUrls.take(highPriorityImages).joinToString("\n") {
            """              <link rel="preload" as="image" fetchpriority="high" href="${escapeHtmlAttribute(it)}">"""
        }
        val preconnectLinks = normalizedUrls.asSequence()
            .mapNotNull {
                try {
                    val parsed = Uri.parse(it)
                    val scheme = parsed.scheme ?: return@mapNotNull null
                    val authority = parsed.encodedAuthority ?: return@mapNotNull null
                    "$scheme://$authority"
                } catch (_: Throwable) {
                    null
                }
            }
            .distinct()
            .take(2)
            .joinToString("\n") {
                """              <link rel="dns-prefetch" href="${escapeHtmlAttribute(it)}">
              <link rel="preconnect" href="${escapeHtmlAttribute(it)}" crossorigin>"""
        }
        val imageTags = normalizedUrls.mapIndexed { index, url ->
            val priority = if (index < highPriorityImages) "high" else "auto"
            val pageAttr = "data-index=\"$index\""
            """                <div class="mv-page" $pageAttr><img data-index="$index" src="${escapeHtmlAttribute(url)}" data-src="${escapeHtmlAttribute(url)}" loading="eager" decoding="async" fetchpriority="$priority"></div>"""
        }.joinToString("\n")
        val kpDirectControlledStripJs = if (kpDirectControlledStrip) "true" else "false"
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
${preconnectLinks}
${preloadLinks}
              <style>
                html,body{margin:0;padding:0;background:#111;overflow-anchor:none;overscroll-behavior:none;scroll-behavior:auto;}
                #strip,.mv-page,img{overflow-anchor:none;}
                .mv-page{display:block;width:100%;min-height:100vh;margin:0;padding:0;background:#111;}
                .mv-page>img{display:block;width:100%;height:auto;min-height:0;margin:0;padding:0;border:0;background:#111;}
              </style>
            </head>
            <body>
              <div id="strip">
${imageTags}
              </div>
              <script>
              (function(){
                window.__mvNtkControlledStrip=1;
                window.__mvNtkStripMode=1;
                function canonicalImageUrl(u){
                  try{
                    return String(u||'')
                      .replace(/&amp;/g,'&')
                      .replace(/\\u0026amp;/g,'&')
                      .replace(/([^:])\/{2,}/g,'$1/');
                  }catch(_){return '';}
                }
                var urls=($json||[]).map(canonicalImageUrl);
                var initialStaticImages=$initialStaticImages;
                var expected=$expectedCount;
                var source=$sourceJson;
                var documentUrl=$docJson;
                var kpDirectControlledStrip=$kpDirectControlledStripJs;
                var backgroundPrepared=source.indexOf('initial-prime')>=0||source.indexOf('hidden')>=0||source.indexOf('visible')>=0||source.indexOf('likely')>=0;
                var strip=document.getElementById('strip');
                var pages=[].slice.call(document.querySelectorAll('.mv-page'));
                var imageByIndex=[];
                var loaded=0,decoded=0,failed=0;
                var firstSent=false,readySent=false,viewportSent=false,maxObservedHeight=0,promoteCursor=0,promotePumpRunning=false;
                var lastScrollY=0,lastScrollAt=0,lastVelocityY=0,promotionFramePending=false;
                var frameStats={activeUntil:0,raf:false,lastTs:0,samples:0,gaps:[],missedIntervals:0,missedFrames:0,maxMissingPx:0,maxPlaceholderPx:0,maxVisibleLoading:0,lastEmit:0,coalesced:0};
                var frameStatsGeneration=0,decodeEmitTimer=0,lastDecodeEmitAt=0,lastProgressStateAt=0;
                function recountFailed(){
                  try{
                    var imgs=[].slice.call(document.images||[]),n=0;
                    for(var ri=0;ri<imgs.length;ri++)if(imgs[ri].__mvNtkFailedCounted)n++;
                    failed=n;
                  }catch(_){}
                }
                function maybeTrimMissingTail(){
                  try{
                    var imgs=imageByIndex.slice(0,urls.length).filter(function(x){return !!x;});
                    if(imgs.length<8)return;
                    var trim=-1;
                    for(var ti=4;ti<urls.length-2;ti++){
                      var a=imageByIndex[ti],b=imageByIndex[ti+1],c=imageByIndex[ti+2];
                      if(a&&b&&c&&a.__mvNtkFailedCounted&&b.__mvNtkFailedCounted&&c.__mvNtkFailedCounted){trim=ti;break;}
                    }
                    if(trim<0){
                      for(var pi=8;pi<urls.length-1;pi++){
                        var x=imageByIndex[pi],y=imageByIndex[pi+1];
                        if(!(x&&y&&x.__mvNtkFailedCounted&&y.__mvNtkFailedCounted))continue;
                        var hasDrawableAfter=false;
                        for(var ai=pi+2;ai<urls.length;ai++){
                          var z=imageByIndex[ai];
                          if(z&&z.__mvNtkCompleteCounted&&!z.__mvNtkFailedCounted){hasDrawableAfter=true;break;}
                        }
                        if(!hasDrawableAfter){trim=pi;break;}
                      }
                    }
                    if(trim<4)return;
                    trimTailAt(trim,'trim-tail');
                  }catch(_){}
                }
                function trimTailAt(trim,reason){
                  try{
                    trim=Math.max(4,Math.min(urls.length,trim|0));
                    if(trim>=urls.length)return;
                    for(var rm=trim;rm<pages.length;rm++){
                      try{
                        var rim=imageByIndex[rm];
                        if(rim){
                          rim.__mvNtkTrimmed=1;
                          rim.onload=null;
                          rim.onerror=null;
                          rim.remove();
                        }
                        pages[rm]&&pages[rm].remove();
                      }catch(_){}
                    }
                    imageByIndex=imageByIndex.slice(0,trim);
                    pages=pages.slice(0,trim);
                    urls=urls.slice(0,trim);
                    expected=Math.min(Math.max(0,expected||0),trim);
                    if(promoteCursor>trim)promoteCursor=trim;
                    recountFailed();
                    emitDecodeState(reason||'trim-tail');
                    scrollState();
                    if(window.__mvNtkPostCoverage)window.__mvNtkPostCoverage(reason||'trim-tail');
                  }catch(_){}
                }
                function viewportDrawableReady(){
                  try{
                    var vh=innerHeight||document.documentElement.clientHeight||document.body.clientHeight||0;
                    if(vh<=0)return false;
                    var targetVh=Math.max(vh,Math.ceil(vh*Math.max(1,Number(devicePixelRatio||1))));
                    var covered=0,total=0;
                    for(var ii=0;ii<pages.length;ii++){
                      var page=pages[ii],im=imageByIndex[ii],r=page.getBoundingClientRect();
                      if(r.bottom<=0||r.top>=targetVh||r.width<=0||r.height<=0)continue;
                      var ov=Math.max(0,Math.min(targetVh,r.bottom)-Math.max(0,r.top));
                      if(ov<=0)continue;
                      total++;
                      if(!(im&&im.parentNode&&im.complete&&im.naturalWidth>0&&im.naturalHeight>0))return false;
                      covered+=ov;
                    }
                    return total>0&&covered>=Math.max(1,targetVh-8);
                  }catch(_){return false;}
                }
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
                function resetFrameStats(){
                  frameStatsGeneration++;
                  frameStats={activeUntil:0,raf:false,lastTs:0,samples:0,gaps:[],missedIntervals:0,missedFrames:0,maxMissingPx:0,maxPlaceholderPx:0,maxVisibleLoading:0,lastEmit:0,coalesced:0};
                }
                window.__mvNtkResetFrameStats=resetFrameStats;
                function emitFrameStats(reason,force){
                  try{
                    var now=Date.now();
                    if(!force&&now-frameStats.lastEmit<250)return;
                    frameStats.lastEmit=now;
                    var gaps=frameStats.gaps.slice().sort(function(a,b){return a-b;});
                    var p95=gaps.length?gaps[Math.min(gaps.length-1,Math.floor(gaps.length*0.95))]:0;
                    var max=gaps.length?gaps[gaps.length-1]:0;
                    NtkBrowserBridge.onFrameStats(JSON.stringify({
                      href:String(location.href||''),
                      reason:String(reason||''),
                      samples:frameStats.samples,
                      missedIntervals:frameStats.missedIntervals,
                      missedFrames:frameStats.missedFrames,
                      callbackP95:p95,
                      callbackMax:max,
                      maxMissingPx:frameStats.maxMissingPx,
                      maxPlaceholderPx:frameStats.maxPlaceholderPx,
                      maxVisibleLoading:frameStats.maxVisibleLoading,
                      coalesced:frameStats.coalesced
                    }));
                  }catch(_){}
                }
                window.__mvNtkPostFrameStats=function(reason){emitFrameStats(reason||'external',true);};
                function frameStatsTick(ts,gen){
                  try{
                    if(gen!==frameStatsGeneration)return;
                    if(frameStats.lastTs>0){
                      var gap=Math.max(0,ts-frameStats.lastTs);
                      frameStats.samples++;
                      frameStats.gaps.push(gap);
                      if(frameStats.gaps.length>180)frameStats.gaps.shift();
                      if(gap>24)frameStats.missedIntervals++;
                      if(gap>34)frameStats.missedFrames+=Math.max(1,Math.round(gap/16.67)-1);
                    }
                    frameStats.lastTs=ts;
                    if(Date.now()<frameStats.activeUntil){
                      requestAnimationFrame(function(next){frameStatsTick(next,gen);});
                    }else{
                      frameStats.raf=false;
                      emitFrameStats('idle',true);
                    }
                  }catch(_){frameStats.raf=false;}
                }
                function startFrameStats(reason){
                  try{
                    var now=Date.now();
                    frameStats.activeUntil=Math.max(frameStats.activeUntil,now+6500);
                    if(frameStats.raf){
                      frameStats.coalesced++;
                      emitFrameStats(reason||'coalesced',false);
                      return;
                    }
                    frameStats.raf=true;
                    frameStats.lastTs=0;
                    var gen=++frameStatsGeneration;
                    requestAnimationFrame(function(ts){frameStatsTick(ts,gen);});
                    emitFrameStats(reason||'start',false);
                  }catch(_){}
                }
                window.__mvNtkPostCoverage=function(reason){
                  try{
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var vh=innerHeight||de.clientHeight||bd.clientHeight||0;
                    var imgs=imageByIndex;
                    var drawable=0,missing=0,drawableItems=0,totalItems=0,loading=0,errors=0;
                    var samples=[];
                    for(var ci=0;ci<pages.length;ci++){
                      var page=pages[ci],im=imgs[ci],r=page.getBoundingClientRect();
                      if(r.bottom<=0||r.top>=vh||r.width<=0||r.height<=0)continue;
                      var ov=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));
                      if(ov<=0)continue;
                      totalItems++;
                      var ok=!!(im&&im.parentNode&&im.complete&&im.naturalWidth>0&&im.naturalHeight>0);
                      if(ok){
                        drawable+=ov;
                        drawableItems++;
                      }else{
                        missing+=ov;
                        if(samples.length<3)samples.push(String((im&&(im.currentSrc||im.src||im.getAttribute('data-src')))||page.getAttribute('data-src')||'').slice(0,120));
                        if(im&&im.parentNode&&im.complete&&im.naturalWidth===0)errors++; else loading++;
                      }
                    }
                    var roundedDrawable=Math.max(0,Math.round(drawable));
                    var roundedMissing=Math.max(0,Math.round(Math.max(missing,vh-roundedDrawable)));
                    frameStats.maxMissingPx=Math.max(frameStats.maxMissingPx,roundedMissing);
                    frameStats.maxPlaceholderPx=Math.max(frameStats.maxPlaceholderPx,roundedMissing);
                    frameStats.maxVisibleLoading=Math.max(frameStats.maxVisibleLoading,loading);
                    NtkBrowserBridge.onCoverage(JSON.stringify({
                      href:String(location.href||''),
                      reason:String(reason||''),
                      viewport:Math.max(0,Math.round(vh)),
                      drawable:roundedDrawable,
                      missing:roundedMissing,
                      drawableItems:drawableItems,
                      totalItems:totalItems,
                      loading:loading,
                      errors:errors,
                      pageCount:urls.length,
                      loadingSamples:samples
                    }));
                  }catch(_){}
                };
                function guardedScrollState(){
                  updateScrollVelocity();
                  scheduleViewportPromotion('scroll');
                  scrollState();
                }
                function promoteImagePriority(im){
                  try{
                    if(!im)return;
                    im.__mvNtkSuspended=0;
                    im.__mvNtkSuspending=0;
                    var idx=Number(im.getAttribute('data-index')||-1);
                    var page=idx>=0?pages[idx]:null;
                    if(page&&!im.parentNode)page.appendChild(im);
                    im.loading='eager';
                    try{im.fetchPriority='high';}catch(_){}
                    var src=canonicalImageUrl(im.getAttribute('data-src')||im.currentSrc||im.src||'');
                    if(src&&(!im.currentSrc&&!im.src)){
                      activateImage(im,src);
                    }
                  }catch(_){}
                }
                function isImageNearViewport(im,extraBefore,extraAfter){
                  try{
                    if(!im)return false;
                    var idx=Number(im.getAttribute('data-index')||-1);
                    var page=idx>=0?pages[idx]:null;
                    if(!page)return false;
                    var vh=innerHeight||document.documentElement.clientHeight||document.body.clientHeight||0;
                    var r=page.getBoundingClientRect();
                    return r.bottom>=-Math.max(0,extraBefore||0)&&r.top<=vh+Math.max(0,extraAfter||0);
                  }catch(_){return false;}
                }
                function promoteRange(start,end){
                  try{
                    var s=Math.max(0,start|0),e=Math.min(urls.length,Math.max(s,end|0));
                    for(var ri=s;ri<e;ri++)promoteImagePriority(imageByIndex[ri]);
                  }catch(_){}
                }
                function updateScrollVelocity(){
                  try{
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var y=pageYOffset||de.scrollTop||bd.scrollTop||0;
                    var now=(performance&&performance.now)?performance.now():Date.now();
                    if(lastScrollAt>0){
                      var dt=Math.max(1,now-lastScrollAt);
                      lastVelocityY=(y-lastScrollY)/dt;
                    }
                    lastScrollY=y;
                    lastScrollAt=now;
                  }catch(_){}
                }
                function scheduleViewportPromotion(reason){
                  try{
                    if(promotionFramePending)return;
                    promotionFramePending=true;
                    requestAnimationFrame(function(){
                      promotionFramePending=false;
                      promoteViewportCorridor(reason||'frame');
                    });
                  }catch(_){
                    promotionFramePending=false;
                    promoteViewportCorridor(reason||'direct');
                  }
                }
                function promoteViewportCorridor(reason){
                  try{
                    pumpPromoteAll();
                  }catch(_){}
                }
                function pumpPromoteAll(){
                  try{
                    if(promotePumpRunning)return;
                    promotePumpRunning=true;
                    var step=function(){
                      try{
                        var imgs=imageByIndex;
                        var fast=firstSent;
                        var batch=fast?16:12;
                        var delay=fast?0:16;
                        var start=promoteCursor,end=Math.min(urls.length,start+batch);
                        for(var ri=start;ri<end;ri++)promoteImagePriority(imgs[ri]);
                        promoteCursor=end;
                        if(promoteCursor<urls.length)setTimeout(step,delay);
                        else promotePumpRunning=false;
                      }catch(_){promotePumpRunning=false;}
                    };
                    setTimeout(step,0);
                  }catch(_){promotePumpRunning=false;}
                }
                window.__mvNtkPumpPromoteAll=pumpPromoteAll;
                window.__mvNtkPromoteTop=function(count){
                  try{
                    var requested=Math.max(kpDirectControlledStrip?initialStaticImages:4,Number(count||24));
                    promoteRange(0,Math.min(urls.length,requested));
                    scheduleViewportPromotion('visible-top');
                    scrollState();
                  }catch(_){}
                };
                window.__mvNtkRetryFailed=function(reason){
                  try{
                    var imgs=imageByIndex;
                    for(var fi=0;fi<imgs.length;fi++){
                      var im=imgs[fi];
                      try{if(im&&typeof im.__mvNtkRetry==='function')im.__mvNtkRetry(reason||'retry');}catch(_){}
                    }
                    emitDecodeState('retry-'+String(reason||''));
                    scrollState();
                  }catch(_){}
                };
                function sendFirst(im){
                  try{
                    if(firstSent)return;
                    if(!im||!im.complete||im.naturalWidth<=0||im.naturalHeight<=0)return;
                    var firstIdx=Number(im.getAttribute('data-index')||-1);
                    if(firstIdx>=initialStaticImages&&!viewportDrawableReady())return;
                    firstSent=true;
                    NtkBrowserBridge.onFirstDrawable(JSON.stringify({href:String(location.href||''),src:im.currentSrc||im.src||''}));
                    promoteRange(0,Math.min(kpDirectControlledStrip?initialStaticImages:12,urls.length));
                    scheduleViewportPromotion('first');
                  }catch(_){}
                }
                function emitDecodeState(reason){
                  try{
                    var total=urls.length;
                    var required=Math.max(0,expected||0);
                    var all=total>0&&total>=required&&loaded>=total&&decoded>=total&&failed===0;
                    NtkBrowserBridge.onDecodeState(JSON.stringify({href:String(location.href||''),strip:true,reason:String(reason||''),source:source,documentUrl:documentUrl,expected:required,total:total,activated:document.images.length,loaded:loaded,decoded:decoded,failed:failed,allDecoded:all}));
                    if(all)NtkBrowserBridge.onAllDecoded(JSON.stringify({href:String(location.href||''),strip:true,reason:String(reason||''),source:source,documentUrl:documentUrl,expected:required,total:total,activated:document.images.length,loaded:loaded,decoded:decoded,failed:failed,allDecoded:true}));
                  }catch(_){}
                }
                function scheduleDecodeState(reason,force){
                  try{
                    var now=Date.now();
                    var total=urls.length;
                    var required=Math.max(0,expected||0);
                    var all=total>0&&total>=required&&loaded>=total&&decoded>=total&&failed===0;
                    if(force||all||failed>0||now-lastDecodeEmitAt>180){
                      lastDecodeEmitAt=now;
                      if(decodeEmitTimer){clearTimeout(decodeEmitTimer);decodeEmitTimer=0;}
                      emitDecodeState(reason);
                      return;
                    }
                    if(!decodeEmitTimer){
                      decodeEmitTimer=setTimeout(function(){
                        decodeEmitTimer=0;
                        lastDecodeEmitAt=Date.now();
                        emitDecodeState(reason||'batched');
                      },180);
                    }
                  }catch(_){emitDecodeState(reason);}
                }
                function releaseInitialScrollIfReady(){
                  try{
                    if(!viewportDrawableReady())return;
                    if(!viewportSent){
                      viewportSent=true;
                      NtkBrowserBridge.onViewportReady(JSON.stringify({href:String(location.href||''),strip:true,viewportOnly:true,total:urls.length,loaded:loaded,decoded:decoded,failed:failed,source:source,documentUrl:documentUrl}));
                    }
                  }catch(_){}
                }
                function maybeReady(){
                  try{
                    if(readySent)return;
                    scheduleDecodeState('progress',false);
                    var now=Date.now();
                    var progressAll=urls.length>0&&loaded>=urls.length&&decoded>=urls.length&&failed===0;
                    if(progressAll||now-lastProgressStateAt>700){
                      lastProgressStateAt=now;
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-progress total '+urls.length+' loaded '+loaded+' decoded '+decoded+' failed '+failed+' source '+source,cloudflare:false,stripCount:urls.length}));}catch(_){}
                    }
                    if(expected>0&&urls.length<expected)return;
                    if(loaded+failed<urls.length)return;
                    if(failed>0)return;
                    if(decoded<loaded)return;
                    readySent=true;
                    scrollState();
                    NtkBrowserBridge.onViewportReady(JSON.stringify({href:String(location.href||''),strip:true,total:urls.length,loaded:loaded,decoded:decoded,failed:failed,source:source,documentUrl:documentUrl}));
                  }catch(_){}
                }
                var pendingActivations=[];
                function canonicalImageUrl(u){
                  try{
                    var raw=String(u||'')
                      .replace(/&amp;/g,'&')
                      .replace(/&#38;/g,'&')
                      .replace(/&quot;/g,'"')
                      .replace(/&apos;/g,"'");
                    raw=raw.replace(/([?&])mv_retry=[^&#]*&?/g,function(m,p){return p;}).replace(/[?&]$/,'');
                    var x=new URL(raw,location.href);
                    x.pathname=String(x.pathname||'/').replace(/\/{2,}/g,'/');
                    return x.href;
                  }catch(_){
                    try{return String(u||'').replace(/&amp;/g,'&').replace(/([^:])\/{2,}/g,'$1/');}catch(__){return '';}
                  }
                }
                var protectedByteFlights={};
                function isProtectedApiUrl(u){
                  try{return /\/api\/m\/i\?/i.test(String(u||''));}catch(_){return false;}
                }
                function bytesToBase64(buf){
                  var bytes=new Uint8Array(buf),chunk=0x8000,out='';
                  for(var bi=0;bi<bytes.length;bi+=chunk){
                    var sub=bytes.subarray(bi,bi+chunk);
                    out+=String.fromCharCode.apply(null,sub);
                  }
                  return btoa(out);
                }
                function cacheProtectedImageBytes(u,index,reason){
                  try{
                    var src=canonicalImageUrl(u);
                    if(!isProtectedApiUrl(src)||protectedByteFlights[src])return;
                    protectedByteFlights[src]=1;
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-byte-fetch-start index '+index+' reason '+String(reason||''),cloudflare:false}));}catch(_){}
                    fetch(src,{method:'GET',credentials:'include',cache:'no-store',headers:{'accept':'image/avif,image/webp,image/apng,image/*,*/*;q=0.8'}})
                      .then(function(resp){return resp.arrayBuffer().then(function(buf){return{status:resp.status||0,buf:buf};});})
                      .then(function(res){
                        try{
                          var len=res&&res.buf?res.buf.byteLength||0:0;
                          if(isProtectedApiUrl(src)){
                            try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-byte-fetch status '+(res.status||0)+' bytes '+len+' index '+index+' reason '+String(reason||''),cloudflare:false}));}catch(_){}
                          }
                          if((res.status||0)<200||(res.status||0)>=300||len<=1024)return;
                          NtkBrowserBridge.onProtectedImageBytes(JSON.stringify({
                            href:String(location.href||''),
                            url:src,
                            index:index,
                            reason:String(reason||''),
                            bodyBase64:bytesToBase64(res.buf)
                          }));
                        }catch(_){}
                      })
                      .catch(function(e){try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-byte-fetch-error '+String(e).slice(0,120)+' index '+index,cloudflare:false}));}catch(_){}})
                      .then(function(){try{delete protectedByteFlights[src];}catch(_){}});
                  }catch(_){}
                }
                function activateImage(im,u){
                  if(im.__mvNtkActivated)return;
                  im.__mvNtkActivated=true;
                  im.__mvNtkActivatedAt=Date.now();
                  var src=canonicalImageUrl(u);
                  im.setAttribute('data-src',src);
                  im.src=src;
                  setTimeout(function(){
                    try{
                      if(im.complete&&im.naturalWidth>0&&im.naturalHeight>0&&!im.__mvNtkCompleteObserved){
                        im.__mvNtkCompleteObserved=1;
                        im.onload&&im.onload();
                      }
                    }catch(_){}
                  },0);
                }
                function finishImageDecode(im,reason){
                  try{
                    if(!im||im.__mvNtkDecodeCounted)return;
                    im.__mvNtkDecodePending=0;
                    im.__mvNtkDecodeCounted=1;
                    decoded++;
                    releaseInitialScrollIfReady();
                    maybeReady();
                  }catch(_){emitDecodeState('decode-finish-error-'+String(reason||''));}
                }
                function decodeLoadedImage(im,reason,attempt){
                  try{
                    if(!im||im.__mvNtkDecodeCounted||im.__mvNtkDecodePending)return;
                    if(!(im.complete&&im.naturalWidth>0&&im.naturalHeight>0))return;
                    if(typeof im.decode!=='function'){
                      finishImageDecode(im,'unsupported-'+String(reason||''));
                      return;
                    }
                    im.__mvNtkDecodePending=1;
                    Promise.resolve(im.decode()).then(function(){
                      finishImageDecode(im,reason);
                    }).catch(function(err){
                      im.__mvNtkDecodePending=0;
                      var next=Number(attempt||0)+1;
                      if(im.complete&&im.naturalWidth>0&&im.naturalHeight>0&&next<3){
                        setTimeout(function(){decodeLoadedImage(im,reason,next);},Math.min(240,40*next));
                        return;
                      }
                      emitDecodeState('decode-rejected-'+String(reason||''));
                      try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-decode-rejected '+String(err||'').slice(0,100),cloudflare:false,stripCount:urls.length}));}catch(_){}
                    });
                  }catch(err){
                    try{if(im)im.__mvNtkDecodePending=0;}catch(_){}
                    emitDecodeState('decode-exception-'+String(reason||''));
                  }
                }
                function activatePending(limit){
                  var max=Math.min(pendingActivations.length,limit||pendingActivations.length);
                  for(var pi=0;pi<max;pi++){
                    var p=pendingActivations[pi];
                    if(p){
                      if(!p.added&&pages[pi]){pages[pi].appendChild(p.im);p.added=1;}
                      activateImage(p.im,p.u);
                    }
                  }
                }
                function reconcileLoadedImages(reason){
                  try{
                    var changed=false;
                    for(var ri=0;ri<imageByIndex.length;ri++){
                      var im=imageByIndex[ri];
                      if(!im||im.__mvNtkTrimmed||im.__mvNtkCompleteCounted||im.__mvNtkFailedCounted)continue;
                      if(im.complete&&im.naturalWidth>0&&im.naturalHeight>0){
                        im.__mvNtkCompleteObserved=1;
                        try{im.onload&&im.onload();changed=true;}catch(_){}
                      }
                    }
                    if(changed){
                      emitDecodeState('reconcile-'+String(reason||''));
                      releaseInitialScrollIfReady();
                      scrollState();
                      if(window.__mvNtkPostCoverage)window.__mvNtkPostCoverage('reconcile-'+String(reason||''));
                    }
                  }catch(_){}
                }
                var existingImages=[];
                for(var ei=0;ei<pages.length;ei++)existingImages[ei]=pages[ei].querySelector('img');
                urls.forEach(function(u,i){
                  var im=existingImages[i]||document.createElement('img');
                  imageByIndex[i]=im;
                  var settled=false;
                  im.setAttribute('data-index',String(i));
                  im.loading=i<initialStaticImages?'eager':'lazy';
                  im.decoding='async';
                  try{im.fetchPriority=i<4?'high':'auto';}catch(_){}
                  im.onload=function(){
                    if(im.__mvNtkTrimmed||i>=urls.length)return;
                    if(settled&&im.__mvNtkCompleteCounted)return;
                    try{if(isProtectedApiUrl(im.currentSrc||im.src||u))NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-img-onload index '+i+' w '+(im.naturalWidth||0)+' h '+(im.naturalHeight||0),cloudflare:false}));}catch(_){}
                    im.__mvNtkCompleteCounted=1;
                    settled=true;
                    loaded++;
                    sendFirst(im);
                    releaseInitialScrollIfReady();
                    scrollState();
                    decodeLoadedImage(im,'initial-'+i,0);
                  };
                  im.onerror=function(){
                    if(im.__mvNtkTrimmed||i>=urls.length)return;
                    if(im.__mvNtkSuspending||im.__mvNtkSuspended)return;
                    if(settled)return;
                    try{if(isProtectedApiUrl(im.currentSrc||im.src||u))NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),body:'protected-img-onerror index '+i+' src '+String(im.currentSrc||im.src||u).slice(0,120),cloudflare:false}));}catch(_){}
                    try{
                      if(i>=32){
                        settled=true;im.__mvNtkFailedCounted=1;failed++;trimTailAt(i,'generated-tail-error');maybeReady();
                        return;
                      }
                      var visibleRetry=isImageNearViewport(im,Math.max(innerHeight||0,1024),Math.max((innerHeight||0)*2,2048));
                      var canRetry=(window.__ntkTrustedBrowserState||visibleRetry||i<initialStaticImages)&&Number(im.__mvNtkRetryCount||0)<4;
                      if(canRetry){
                        im.__mvNtkRetryCount=Number(im.__mvNtkRetryCount||0)+1;
                        setTimeout(function(){try{im.__mvNtkRetry&&im.__mvNtkRetry('error');}catch(_){}},visibleRetry?Math.min(420,90*im.__mvNtkRetryCount):Math.min(1200,220*im.__mvNtkRetryCount));
                        return;
                      }
                    }catch(_){}
                    settled=true;im.__mvNtkFailedCounted=1;failed++;maybeTrimMissingTail();emitDecodeState('error');scrollState();maybeReady();
                  };
                  u=canonicalImageUrl(u);
                  im.setAttribute('data-src',u);
                  im.__mvNtkRetry=function(reason){
                    try{
                      if(!im.__mvNtkActivated&&!im.__mvNtkFailedCounted)return;
                      if(!im.__mvNtkFailedCounted&&!(im.complete&&im.naturalWidth===0&&(im.currentSrc||im.src)))return;
                      if(im.__mvNtkFailedCounted){failed=Math.max(0,failed-1);im.__mvNtkFailedCounted=0;}
                      settled=false;
                      im.__mvNtkCompleteCounted=0;
                      im.__mvNtkSuspended=0;
                      im.__mvNtkSuspending=0;
                      var src=canonicalImageUrl(im.getAttribute('data-src')||u||im.currentSrc||im.src||'');
                      if(!src)return;
                      var sep=src.indexOf('?')>=0?'&':'?';
                      im.src=src+sep+'mv_retry='+(Date.now())+'_'+i;
                      promoteImagePriority(im);
                    }catch(_){}
                  };
                  var added=!!existingImages[i];
                  if(!added&&pages[i]){pages[i].appendChild(im);added=true;}
                  pendingActivations.push({im:im,u:u,added:added?1:0});
                  if(added){
                    activateImage(im,u);
                  }
                  setTimeout(function checkTimeout(){
                    if(settled||im.__mvNtkTrimmed||i>=urls.length)return;
                    if(!im.__mvNtkActivated){setTimeout(checkTimeout,1500);return;}
                    var age=Date.now()-Number(im.__mvNtkActivatedAt||Date.now());
                    if(age<15000){setTimeout(checkTimeout,Math.max(500,15000-age));return;}
                    settled=true;im.__mvNtkFailedCounted=1;failed++;maybeTrimMissingTail();try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-timeout index '+i+' src '+String(u||'').slice(0,90),cloudflare:false,stripCount:urls.length}));}catch(_){}scrollState();maybeReady();
                  },15000);
                });
                window.__mvNtkAppendControlledStrip=function(nextUrls,nextExpected,nextSource){
                  try{
                    if(!nextUrls||!nextUrls.length)return;
                    var oldCount=urls.length;
                    if(nextUrls.length<=oldCount)return;
                    expected=Math.max(Number(nextExpected||0),nextUrls.length);
                    source=String(nextSource||source||'');
                    for(let ai=oldCount;ai<nextUrls.length;ai++){
                      let u=canonicalImageUrl(nextUrls[ai]);
                      urls[ai]=u;
                      let page=document.createElement('div');
                      page.className='mv-page';
                      page.setAttribute('data-index',String(ai));
                      let im=document.createElement('img');
                      imageByIndex[ai]=im;
                      pages[ai]=page;
                      let settled=false;
                      im.setAttribute('data-index',String(ai));
                      im.setAttribute('data-src',u);
                      im.loading='eager';
                      im.decoding='async';
                      try{im.fetchPriority='auto';}catch(_){}
                      im.onload=function(){
                        if(im.__mvNtkTrimmed)return;
                        if(settled&&im.__mvNtkCompleteCounted)return;
                        im.__mvNtkCompleteCounted=1;
                        settled=true;
                        loaded++;
                        sendFirst(im);
                        releaseInitialScrollIfReady();
                        scrollState();
                        decodeLoadedImage(im,'append-'+ai,0);
                      };
                      im.onerror=function(){
                        if(im.__mvNtkTrimmed||im.__mvNtkSuspending||im.__mvNtkSuspended||settled)return;
                        var visibleRetry=isImageNearViewport(im,Math.max(innerHeight||0,1024),Math.max((innerHeight||0)*2,2048));
                        if((window.__ntkTrustedBrowserState||visibleRetry)&&Number(im.__mvNtkRetryCount||0)<4){
                          im.__mvNtkRetryCount=Number(im.__mvNtkRetryCount||0)+1;
                          setTimeout(function(){try{im.__mvNtkRetry&&im.__mvNtkRetry('append-error');}catch(_){}},Math.min(420,90*im.__mvNtkRetryCount));
                          return;
                        }
                        settled=true;
                        im.__mvNtkFailedCounted=1;
                        failed++;
                        emitDecodeState('append-error');
                        scrollState();
                        maybeReady();
                      };
                      im.__mvNtkRetry=function(reason){
                        try{
                          if(!im.__mvNtkActivated&&!im.__mvNtkFailedCounted)return;
                          if(!im.__mvNtkFailedCounted&&!(im.complete&&im.naturalWidth===0&&(im.currentSrc||im.src)))return;
                          if(im.__mvNtkFailedCounted){failed=Math.max(0,failed-1);im.__mvNtkFailedCounted=0;}
                          settled=false;
                          im.__mvNtkCompleteCounted=0;
                          im.__mvNtkSuspended=0;
                          im.__mvNtkSuspending=0;
                          var src=canonicalImageUrl(im.getAttribute('data-src')||u||im.currentSrc||im.src||'');
                          if(!src)return;
                          var sep=src.indexOf('?')>=0?'&':'?';
                          im.src=src+sep+'mv_retry='+(Date.now())+'_'+ai;
                          promoteImagePriority(im);
                        }catch(_){}
                      };
                      page.appendChild(im);
                      strip.appendChild(page);
                      pendingActivations.push({im:im,u:u,added:1});
                    }
                    promoteCursor=Math.min(promoteCursor,oldCount);
                    emitDecodeState('append');
                    pumpPromoteAll();
                    scrollState();
                  }catch(e){
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'controlled-strip-append-error '+String(e&&e.message||e),cloudflare:false,stripCount:urls.length}));}catch(_){}
                  }
                };
                addEventListener('scroll',function(){startFrameStats('scroll');guardedScrollState();},{passive:true});
                addEventListener('touchmove',function(){updateScrollVelocity();startFrameStats('touchmove');scheduleViewportPromotion('touchmove');},{passive:true});
                addEventListener('resize',function(){startFrameStats('resize');guardedScrollState();},{passive:true});
                pumpPromoteAll();
                setTimeout(function(){updateScrollVelocity();startFrameStats('initial');scheduleViewportPromotion('initial');},0);
                setTimeout(function(){reconcileLoadedImages('initial');},0);
                setTimeout(function(){reconcileLoadedImages('early');},80);
                setTimeout(function(){reconcileLoadedImages('mid');},240);
                setTimeout(function(){reconcileLoadedImages('late');},700);
                setInterval(function(){reconcileLoadedImages('interval');},1000);
                setTimeout(function(){emitDecodeState('install');},0);
                setTimeout(scrollState,0);
                setTimeout(scrollState,80);
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun controlledProtectedApiStripHtml(
        normalizedUrls: List<String>,
        expected: Int,
        source: String,
        documentUrl: String
    ): String {
        val json = JSONArray(normalizedUrls).toString()
        val sourceJson = JSONObject.quote(source)
        val docJson = JSONObject.quote(documentUrl)
        val expectedCount = expected.coerceAtLeast(0)
        val initialStaticImages = normalizedUrls.size
        val highPriorityImages = minOf(normalizedUrls.size, 4)
        val preloadLinks = normalizedUrls.take(highPriorityImages).joinToString("\n") {
            """              <link rel="preload" as="image" fetchpriority="high" href="${escapeHtmlAttribute(it)}">"""
        }
        val preconnectLinks = normalizedUrls.asSequence()
            .mapNotNull {
                try {
                    val parsed = Uri.parse(it)
                    val scheme = parsed.scheme ?: return@mapNotNull null
                    val authority = parsed.encodedAuthority ?: return@mapNotNull null
                    "$scheme://$authority"
                } catch (_: Throwable) {
                    null
                }
            }
            .distinct()
            .take(2)
            .joinToString("\n") {
                """              <link rel="dns-prefetch" href="${escapeHtmlAttribute(it)}">
              <link rel="preconnect" href="${escapeHtmlAttribute(it)}" crossorigin>"""
            }
        val imageTags = normalizedUrls.mapIndexed { index, url ->
            val priority = if (index < highPriorityImages) "high" else "auto"
            """                <div class="mv-page" data-index="$index" data-src="${escapeHtmlAttribute(url)}"><img data-index="$index" data-src="${escapeHtmlAttribute(url)}" loading="eager" decoding="async" fetchpriority="$priority"></div>"""
        }.joinToString("\n")
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
${preconnectLinks}
${preloadLinks}
              <style>
                html,body{margin:0;padding:0;background:#111;overflow-anchor:none;overscroll-behavior:none;scroll-behavior:auto;}
                #strip,.mv-page,img{overflow-anchor:none;}
                .mv-page{display:block;width:100%;min-height:100vh;margin:0;padding:0;background:#111;}
                .mv-page>img{display:block;width:100%;height:auto;min-height:100vh;margin:0;padding:0;border:0;background:#111;object-fit:contain;}
              </style>
            </head>
            <body>
              <div id="strip">
${imageTags}
              </div>
              <script>
              (function(){
                window.__mvNtkControlledStrip=1;
                window.__mvNtkStripMode=1;
                window.__mvNtkProtectedFastStrip=1;
                var urls=$json;
                var expected=$expectedCount;
                var source=$sourceJson;
                var documentUrl=$docJson;
                var pages=[].slice.call(document.querySelectorAll('.mv-page'));
                var imgs=[].slice.call(document.images||[]);
                var loaded=0,decoded=0,failed=0,firstSent=false,viewportSent=false,readySent=false;
                var initialStaticImages=$initialStaticImages;
                var inflight=0,maxInflight=Math.min(12,Math.max(6,Number(navigator.hardwareConcurrency||8)));
                var queue=[];
                var states=imgs.map(function(){return 'empty';});
                var decodeEmitTimer=0,lastDecodeEmitAt=0;
                var rafScroll=false,rafCoverage=false;
                var frameStats={activeUntil:0,raf:false,lastTs:0,samples:0,gaps:[],missedIntervals:0,missedFrames:0,maxMissingPx:0,maxPlaceholderPx:0,maxVisibleLoading:0,lastEmit:0,coalesced:0};
                var frameStatsGeneration=0;
                function bridge(name,obj){
                  try{
                    obj.href=String(location.href||'');
                    NtkBrowserBridge[name](JSON.stringify(obj));
                  }catch(_){}
                }
                function drawable(im){
                  try{return !!(im&&im.parentNode&&im.complete&&im.naturalWidth>0&&im.naturalHeight>0);}catch(_){return false;}
                }
                function scrollState(){
                  try{
                    var de=document.documentElement||document.body,bd=document.body||de;
                    var vh=innerHeight||de.clientHeight||bd.clientHeight||0;
                    var sh=Math.max(de.scrollHeight||0,bd.scrollHeight||0,de.offsetHeight||0,bd.offsetHeight||0,vh);
                    var sy=pageYOffset||de.scrollTop||bd.scrollTop||0;
                    bridge('onScroll',{scrollY:Math.max(0,Math.round(sy)),viewport:Math.max(0,Math.round(vh)),content:Math.max(0,Math.round(sh))});
                  }catch(_){}
                }
                function resetFrameStats(){
                  frameStatsGeneration++;
                  frameStats={activeUntil:0,raf:false,lastTs:0,samples:0,gaps:[],missedIntervals:0,missedFrames:0,maxMissingPx:0,maxPlaceholderPx:0,maxVisibleLoading:0,lastEmit:0,coalesced:0};
                }
                window.__mvNtkResetFrameStats=resetFrameStats;
                function emitFrameStats(reason,force){
                  try{
                    var now=Date.now();
                    if(!force&&now-frameStats.lastEmit<250)return;
                    frameStats.lastEmit=now;
                    var gaps=frameStats.gaps.slice().sort(function(a,b){return a-b;});
                    var p95=gaps.length?gaps[Math.min(gaps.length-1,Math.floor(gaps.length*0.95))]:0;
                    var max=gaps.length?gaps[gaps.length-1]:0;
                    bridge('onFrameStats',{
                      reason:String(reason||''),
                      samples:frameStats.samples,
                      missedIntervals:frameStats.missedIntervals,
                      missedFrames:frameStats.missedFrames,
                      callbackP95:p95,
                      callbackMax:max,
                      maxMissingPx:frameStats.maxMissingPx,
                      maxPlaceholderPx:frameStats.maxPlaceholderPx,
                      maxVisibleLoading:frameStats.maxVisibleLoading,
                      coalesced:frameStats.coalesced
                    });
                  }catch(_){}
                }
                window.__mvNtkPostFrameStats=function(reason){emitFrameStats(reason||'external',true);};
                function frameStatsTick(ts,gen){
                  try{
                    if(gen!==frameStatsGeneration)return;
                    if(frameStats.lastTs>0){
                      var gap=Math.max(0,ts-frameStats.lastTs);
                      frameStats.samples++;
                      frameStats.gaps.push(gap);
                      if(frameStats.gaps.length>180)frameStats.gaps.shift();
                      if(gap>24)frameStats.missedIntervals++;
                      if(gap>34)frameStats.missedFrames+=Math.max(1,Math.round(gap/16.67)-1);
                    }
                    frameStats.lastTs=ts;
                    if(Date.now()<frameStats.activeUntil){
                      requestAnimationFrame(function(next){frameStatsTick(next,gen);});
                    }else{
                      frameStats.raf=false;
                      emitFrameStats('idle',true);
                    }
                  }catch(_){frameStats.raf=false;}
                }
                function startFrameStats(reason){
                  try{
                    var now=Date.now();
                    frameStats.activeUntil=Math.max(frameStats.activeUntil,now+6500);
                    if(frameStats.raf){
                      frameStats.coalesced++;
                      emitFrameStats(reason||'coalesced',false);
                      return;
                    }
                    frameStats.raf=true;
                    frameStats.lastTs=0;
                    var gen=++frameStatsGeneration;
                    requestAnimationFrame(function(ts){frameStatsTick(ts,gen);});
                    emitFrameStats(reason||'start',false);
                  }catch(_){}
                }
                function coverage(reason){
                  try{
                    var vh=innerHeight||document.documentElement.clientHeight||document.body.clientHeight||0;
                    var drawablePx=0,missingPx=0,drawableItems=0,totalItems=0,loading=0,errors=0,samples=[];
                    for(var i=0;i<pages.length;i++){
                      var page=pages[i],im=imgs[i],r=page.getBoundingClientRect();
                      if(r.bottom<=0||r.top>=vh||r.width<=0||r.height<=0)continue;
                      var ov=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));
                      if(ov<=0)continue;
                      totalItems++;
                      if(drawable(im)){drawablePx+=ov;drawableItems++;}
                      else{
                        missingPx+=ov;
                        if(samples.length<3)samples.push(String((im&&(im.currentSrc||im.src))||'').slice(0,120));
                        if(im&&im.complete)errors++;else loading++;
                      }
                    }
                    var roundedMissing=Math.max(0,Math.round(missingPx));
                    frameStats.maxMissingPx=Math.max(frameStats.maxMissingPx,roundedMissing);
                    frameStats.maxPlaceholderPx=Math.max(frameStats.maxPlaceholderPx,roundedMissing);
                    frameStats.maxVisibleLoading=Math.max(frameStats.maxVisibleLoading,loading);
                    bridge('onCoverage',{
                      reason:String(reason||''),
                      viewport:Math.max(0,Math.round(vh)),
                      drawable:Math.max(0,Math.round(drawablePx)),
                      missing:roundedMissing,
                      drawableItems:drawableItems,
                      totalItems:totalItems,
                      loading:loading,
                      errors:errors,
                      pageCount:urls.length,
                      loadingSamples:samples
                    });
                    if(!viewportSent&&totalItems>0&&missingPx<=1&&drawablePx>=Math.max(1,vh-8)){
                      viewportSent=true;
                      bridge('onViewportReady',{strip:true,viewportOnly:true,total:urls.length,loaded:loaded,decoded:decoded,failed:failed,source:source,documentUrl:documentUrl});
                    }
                  }catch(_){}
                }
                window.__mvNtkPostCoverage=function(reason){coverage(reason||'external');};
                function distanceToViewport(index){
                  try{
                    var r=pages[index].getBoundingClientRect();
                    if(r.bottom>=0&&r.top<=(innerHeight||0))return 0;
                    if(r.top>0)return r.top;
                    return Math.abs(r.bottom);
                  }catch(_){return 9999999;}
                }
                function priorityRank(priority){
                  return priority==='high'?0:(priority==='auto'?1:2);
                }
                function enqueue(index,priority){
                  try{
                    if(index<0||index>=imgs.length)return;
                    if(states[index]==='loaded'||states[index]==='loading'||states[index]==='error')return;
                    if(states[index]==='queued'){
                      for(var q=0;q<queue.length;q++){
                        if(queue[q].index===index){
                          if(priorityRank(priority||'auto')<priorityRank(queue[q].priority||'auto'))queue[q].priority=priority||'auto';
                          return;
                        }
                      }
                    }
                    states[index]='queued';
                    queue.push({index:index,priority:priority||'auto'});
                  }catch(_){}
                }
                function pump(){
                  try{
                    queue.sort(function(a,b){
                      var pr=priorityRank(a.priority)-priorityRank(b.priority);
                      if(pr!==0)return pr;
                      return distanceToViewport(a.index)-distanceToViewport(b.index);
                    });
                    while(inflight<maxInflight&&queue.length){
                      var item=queue.shift(),i=item.index,im=imgs[i],src=(im&&((im.dataset&&im.dataset.src)||urls[i]))||'';
                      src=canonicalImageUrl(src);
                      if(!im||!src||states[i]!=='queued')continue;
                      states[i]='loading';
                      inflight++;
                      try{im.fetchPriority=item.priority||'auto';im.loading='eager';}catch(_){}
                      im.src=src;
                    }
                  }catch(_){}
                }
                window.__mvNtkPromoteTop=function(count){
                  try{
                    var limit=Math.min(imgs.length,Math.max(initialStaticImages,Number(count)||24));
                    for(var i=0;i<limit;i++)enqueue(i,'high');
                    pump();
                  }catch(_){}
                  scrollState();coverage('visible-top');
                };
                window.__mvNtkPumpPromoteAll=function(){
                  try{
                    for(var i=0;i<imgs.length;i++)enqueue(i,i<initialStaticImages?'high':'auto');
                    pump();
                  }catch(_){}
                };
                window.__mvNtkRetryFailed=function(reason){
                  try{
                    for(var i=0;i<imgs.length;i++){
                      var im=imgs[i];
                      if(im&&im.complete&&im.naturalWidth===0){
                        var src=canonicalImageUrl(urls[i]||im.src||'');
                        im.src=src+(src.indexOf('?')>=0?'&':'?')+'mv_retry='+Date.now()+'_'+i;
                      }
                    }
                    emitDecodeState('retry-'+String(reason||''));
                  }catch(_){}
                };
                function scheduleScroll(reason){
                  startFrameStats(reason||'scroll');
                  if(!rafScroll){
                    rafScroll=true;
                    requestAnimationFrame(function(){rafScroll=false;scrollState();});
                  }
                  if(!rafCoverage){
                    rafCoverage=true;
                    requestAnimationFrame(function(){rafCoverage=false;coverage(reason||'scroll');});
                  }
                }
                function promoteVisible(reason){
                  try{
                    var vh=innerHeight||document.documentElement.clientHeight||document.body.clientHeight||0;
                    var margin=Math.max(vh*4,2400);
                    for(var i=0;i<pages.length;i++){
                      var im=imgs[i],r=pages[i].getBoundingClientRect();
                      if(!im||r.bottom<-margin||r.top>vh+margin)continue;
                      enqueue(i,(r.top<=vh*1.5&&r.bottom>=-vh*0.5)?'high':'auto');
                    }
                  }catch(_){}
                  pump();
                  scheduleScroll(reason||'promote');
                }
                function queueAll(reason){
                  try{
                    for(var i=0;i<imgs.length;i++)enqueue(i,i<2?'high':'low');
                  }catch(_){}
                  pump();
                  scheduleScroll(reason||'queue-all');
                }
                function emitDecodeState(reason){
                  try{
                    var total=urls.length;
                    var required=Math.max(0,expected||0);
                    var all=total>0&&total>=required&&loaded>=total&&decoded>=total&&failed===0;
                    bridge('onDecodeState',{strip:true,reason:String(reason||''),source:source,documentUrl:documentUrl,expected:required,total:total,activated:imgs.length,loaded:loaded,decoded:decoded,failed:failed,allDecoded:all});
                    if(all&&!readySent){
                      readySent=true;
                      bridge('onAllDecoded',{strip:true,reason:String(reason||''),source:source,documentUrl:documentUrl,expected:required,total:total,activated:imgs.length,loaded:loaded,decoded:decoded,failed:failed,allDecoded:true});
                      bridge('onViewportReady',{strip:true,total:total,loaded:loaded,decoded:decoded,failed:failed,source:source,documentUrl:documentUrl});
                    }
                  }catch(_){}
                }
                function scheduleDecodeState(reason,force){
                  try{
                    var now=Date.now();
                    var total=urls.length;
                    var required=Math.max(0,expected||0);
                    var all=total>0&&total>=required&&loaded>=total&&decoded>=total&&failed===0;
                    if(force||all||failed>0||now-lastDecodeEmitAt>180){
                      lastDecodeEmitAt=now;
                      if(decodeEmitTimer){clearTimeout(decodeEmitTimer);decodeEmitTimer=0;}
                      emitDecodeState(reason);
                      return;
                    }
                    if(!decodeEmitTimer){
                      decodeEmitTimer=setTimeout(function(){
                        decodeEmitTimer=0;
                        lastDecodeEmitAt=Date.now();
                        emitDecodeState(reason||'batched');
                      },180);
                    }
                  }catch(_){emitDecodeState(reason);}
                }
                function sendFirst(im){
                  try{
                    if(firstSent||!drawable(im))return;
                    firstSent=true;
                    maxInflight=Math.min(16,Math.max(maxInflight,Number(navigator.hardwareConcurrency||8)));
                    bridge('onFirstDrawable',{src:im.currentSrc||im.src||''});
                    pump();
                    scrollState();
                    coverage('first');
                  }catch(_){}
                }
                function finishProtectedDecode(im,index,ok,reason){
                  try{
                    if(!im||im.__mvDecodeSettled)return;
                    im.__mvDecodePending=0;
                    im.__mvDecodeSettled=1;
                    if(states[index]==='loading'||states[index]==='decoding')inflight=Math.max(0,inflight-1);
                    if(ok){
                      states[index]='loaded';
                      decoded++;
                    }else{
                      states[index]='error';
                      im.__mvFailed=1;
                      failed++;
                    }
                    scheduleDecodeState(ok?'decode':'decode-error',!ok);
                    scheduleScroll(ok?'decode':'decode-error');
                    pump();
                  }catch(_){
                    if(states[index]==='loading'||states[index]==='decoding')inflight=Math.max(0,inflight-1);
                    pump();
                  }
                }
                function decodeProtectedImage(im,index,attempt){
                  try{
                    if(!im||im.__mvDecodeSettled||im.__mvDecodePending)return;
                    if(!drawable(im)){
                      finishProtectedDecode(im,index,false,'not-drawable');
                      return;
                    }
                    if(typeof im.decode!=='function'){
                      finishProtectedDecode(im,index,true,'unsupported');
                      return;
                    }
                    states[index]='decoding';
                    im.__mvDecodePending=1;
                    Promise.resolve(im.decode()).then(function(){
                      finishProtectedDecode(im,index,true,'decode');
                    }).catch(function(){
                      im.__mvDecodePending=0;
                      var next=Number(attempt||0)+1;
                      if(drawable(im)&&next<3){
                        setTimeout(function(){decodeProtectedImage(im,index,next);},Math.min(240,40*next));
                        return;
                      }
                      finishProtectedDecode(im,index,false,'decode-rejected');
                    });
                  }catch(_){
                    try{if(im)im.__mvDecodePending=0;}catch(__){}
                    finishProtectedDecode(im,index,false,'decode-exception');
                  }
                }
                imgs.forEach(function(im,i){
                  im.onload=function(){
                    if(im.__mvLoaded)return;
                    im.__mvLoaded=1;
                    loaded++;
                    sendFirst(im);
                    decodeProtectedImage(im,i,0);
                  };
                  im.onerror=function(){
                    if(im.__mvFailed)return;
                    im.__mvFailed=1;
                    if(states[i]==='loading')inflight=Math.max(0,inflight-1);
                    states[i]='error';
                    failed++;
                    scheduleDecodeState('error',true);
                    scheduleScroll('error');
                    pump();
                  };
                  if(drawable(im)){
                    setTimeout(function(){try{im.onload();}catch(_){}},0);
                  }
                });
                addEventListener('scroll',function(){startFrameStats('scroll');promoteVisible('scroll');},{passive:true});
                addEventListener('touchmove',function(){startFrameStats('touchmove');promoteVisible('touchmove');},{passive:true});
                addEventListener('resize',function(){startFrameStats('resize');promoteVisible('resize');},{passive:true});
                queueAll('install');
                setTimeout(function(){startFrameStats('install');scheduleDecodeState('install',true);promoteVisible('install');scrollState();coverage('install');},0);
                setTimeout(function(){promoteVisible('early');scrollState();coverage('early');},80);
                setTimeout(function(){promoteVisible('mid');scrollState();coverage('mid');},240);
                setTimeout(function(){promoteVisible('late');},520);
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun viewerPayloadBootstrapHtml(payload: String, documentUrl: String): String {
        val payloadJson = JSONObject.quote(payload).replace("</", "<\\/")
        val doc = escapeHtmlAttribute(documentUrl)
        val script = discoveryScript()
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                <title>viewer</title>
                <style>
                  html,body{margin:0;padding:0;background:#050505;color:#eee;overflow-x:hidden;}
                  #mv-root{min-height:100vh;}
                </style>
              </head>
              <body data-document-url="$doc">
                <div id="mv-root"></div>
                <script>
                  window.__mvNtkInjectedViewerData=$payloadJson;
                  window.__mvNtkBootstrapDocumentUrl=${JSONObject.quote(documentUrl)};
                </script>
                <script>
                  $script
                </script>
                <script>
                  try{if(window.__mvNtkScan)window.__mvNtkScan('payload-bootstrap');}catch(e){}
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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
        fun onProtectedImageBytes(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val url = normalizeImageUrl(
                    json.optString("url", ""),
                    currentDocumentUrl.ifBlank { currentBaseUrl + currentPath }
                )
                if (!isProtectedApiImageUrl(url)) return
                val body = json.optString("bodyBase64", "")
                if (body.length < 128) return
                val bytes = Base64.decode(body, Base64.NO_WRAP)
                if (bytes.size <= 1024) {
                    Log.d(
                        TAG,
                        "ntk_browser_broker_protected_image_bytes_reject path=$currentPath," +
                            "url=$url,bytes=${bytes.size}"
                    )
                    return
                }
                val ok = ReaderImageCache.cacheTrustedNtkGeneratedImageBytesForPath(
                    currentAppContext,
                    currentPath,
                    url,
                    bytes,
                    "browser-protected-api",
                    currentCacheProducerGeneration
                )
                if (ok) {
                    controlledForegroundBytes[controlledCacheKey(url)] = bytes
                    Log.d(
                        TAG,
                        "ntk_browser_broker_protected_image_bytes_cached path=$currentPath," +
                            "url=$url,bytes=${bytes.size},index=${json.optInt("index", -1)}"
                    )
                }
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_protected_image_bytes_error path=$currentPath,$e")
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
                    val viewportOnly = json.optBoolean("viewportOnly", false)
                    val total = json.optInt("total", 0)
                    val loaded = json.optInt("loaded", 0)
                    val decoded = json.optInt("decoded", loaded)
                    val failed = json.optInt("failed", 0)
                    val allDecoded = strip &&
                        total >= expected &&
                        loaded >= expected &&
                        decoded >= expected &&
                        failed == 0
                    if (!allDecoded && !viewportOnly) {
                        Log.d(
                            TAG,
                            "ntk_browser_broker_viewport_ready_deferred path=$currentPath," +
                                "expected=$expected,total=$total,loaded=$loaded," +
                                "decoded=$decoded,failed=$failed,strip=$strip"
                        )
                        return
                    }
                    if (allDecoded) allDecodedReadyPath = currentPath
                }
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_viewport_ready_parse_error path=$currentPath,$e")
                return
            }
            notifyViewportReady()
        }

        @JavascriptInterface
        fun onDecodeState(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val key = currentPath
                if (key.isEmpty()) return
                val reason = json.optString("reason", "")
                val total = json.optInt("total", 0).coerceAtLeast(0)
                val jsonExpected = json.optInt("expected", 0).coerceAtLeast(0)
                val trimmedTail = reason.contains("tail", ignoreCase = true) &&
                    total > 0 &&
                    total < currentExpectedImageCount.coerceAtLeast(0)
                val expected = if (trimmedTail) {
                    currentExpectedImageCount = total
                    total
                } else {
                    maxOf(
                        currentExpectedImageCount.coerceAtLeast(0),
                        jsonExpected
                    )
                }
                val activated = json.optInt("activated", 0).coerceAtLeast(0)
                val loaded = json.optInt("loaded", 0).coerceAtLeast(0)
                val decoded = json.optInt("decoded", 0).coerceAtLeast(0)
                val failed = json.optInt("failed", 0).coerceAtLeast(0)
                val allDecoded = json.optBoolean("allDecoded", false) &&
                    expected > 0 &&
                    total >= expected &&
                    activated >= expected &&
                    loaded >= expected &&
                    decoded >= expected &&
                    failed == 0
                decodeSnapshotsByPath[key] = DecodeSnapshot(
                    path = key,
                    expected = expected,
                    total = total,
                    activated = activated,
                    loaded = loaded,
                    decoded = decoded,
                    failed = failed,
                    allDecoded = allDecoded,
                    createdAtMs = SystemClock.elapsedRealtime()
                )
                if (allDecoded) allDecodedReadyPath = key
            } catch (e: Throwable) {
                Log.d(TAG, "ntk_browser_broker_decode_state_error path=$currentPath,$e")
            }
        }

        @JavascriptInterface
        fun onAllDecoded(value: String?) {
            onDecodeState(value)
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
        fun onFrameStats(value: String?) {
            try {
                val json = JSONObject(value.orEmpty())
                if (!callbackMatchesCurrentPath(json)) return
                val key = currentPath
                if (key.isEmpty()) return
                val samples = json.optInt("samples", 0).coerceAtLeast(0)
                if (samples <= 0) return
                val snapshot = ReaderSurfaceView.FrameStatsSnapshot(
                    samples = samples,
                    strictOverBudget = 0,
                    missedIntervals = json.optInt("missedIntervals", 0).coerceAtLeast(0),
                    missedFrames = json.optInt("missedFrames", 0).coerceAtLeast(0),
                    droppedFrames = 0,
                    droppedFrameDebt = 0,
                    callbackP95 = json.optDouble("callbackP95", 0.0).toFloat().coerceAtLeast(0f),
                    callbackMax = json.optDouble("callbackMax", 0.0).toFloat().coerceAtLeast(0f),
                    prepP95 = 0f,
                    prepMax = 0f,
                    drawP95 = 0f,
                    drawMax = 0f,
                    totalP95 = 0f,
                    totalMax = 0f,
                    maxMissingPx = json.optInt("maxMissingPx", 0).coerceAtLeast(0),
                    maxPlaceholderPx = json.optInt("maxPlaceholderPx", 0).coerceAtLeast(0),
                    maxVisibleLoading = json.optInt("maxVisibleLoading", 0).coerceAtLeast(0),
                    noCanvas = 0,
                    coalesced = json.optInt("coalesced", 0).coerceAtLeast(0)
                )
                frameStatsByPath[key] = snapshot
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
                val rawSnapshot = VisibleCoverageSnapshot(
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
                val decode = decodeSnapshotsByPath[key]
                val snapshot = if (
                    decode != null &&
                    decode.allDecoded &&
                    decode.failed == 0 &&
                    decode.total > 0 &&
                    rawSnapshot.pageCount > decode.total &&
                    (rawSnapshot.visibleErrors > 0 || rawSnapshot.visibleLoading > 0)
                ) {
                    VisibleCoverageSnapshot(
                        key,
                        rawSnapshot.viewportPx,
                        rawSnapshot.viewportPx,
                        0,
                        rawSnapshot.drawableItems.coerceAtLeast(1),
                        minOf(rawSnapshot.totalItems, decode.total).coerceAtLeast(1),
                        0,
                        0,
                        decode.total,
                        rawSnapshot.createdAtMs
                    )
                } else {
                    rawSnapshot
                }
                latestCoverageSnapshot = snapshot
                if (snapshot.visibleLoading > 0) {
                    Log.d(TAG, "ntk_browser_broker_coverage_loading path=$key,samples=${json.optJSONArray("loadingSamples")}")
                }
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
            if (recordForegroundStrictAckProof(text, "ack-state")) {
                republishProtectedSnapshotAfterStrictProof("ack-state")
            }
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
            if (recordForegroundStrictAckProof(text, "ack-proof")) {
                republishProtectedSnapshotAfterStrictProof("ack-proof")
            }
        }

        @JavascriptInterface
        fun onViewerImages(value: String?) {
            try {
                if (isKpWebtoonSlugPath(currentPath) && controlledDocumentPath == currentPath) {
                    Log.d(TAG, "ntk_foreground_viewer_images_skip_kp_controlled path=$currentPath")
                    return
                }
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
                val proofSource = json.optString("source", detail?.optString("source", "") ?: "")
                val proofTp = json.optString("tp", detail?.optString("tp", "") ?: "")
                val proof200 = json.optBoolean("proof200", false) ||
                    detail?.optBoolean("proof200", false) == true ||
                    json.optBoolean("foregroundHybridAckReady", false) &&
                    detail?.optBoolean("proof200", false) == true ||
                    source == "ack-proof" &&
                    (proofTp.isNotBlank() || proofSource.contains("ack", ignoreCase = true))
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

        private fun republishProtectedSnapshotAfterStrictProof(source: String) {
            val key = normalizePath(currentPath)
            if (key.isEmpty() || !isKpWebtoonSlugPath(key)) return
            val snapshot = snapshots[key] ?: return
            if (snapshot.images.none { isProtectedApiImageUrl(it) }) return
            val proofSnapshot = snapshot.copy(
                source = "foreground-strict-proof-$source-${snapshot.source}",
                createdAtMs = SystemClock.elapsedRealtime()
            )
            snapshots[key] = proofSnapshot
            Log.d(
                TAG,
                "ntk_foreground_ack_proof_republish_manifest path=$key," +
                    "count=${proofSnapshot.images.size},source=${proofSnapshot.source}"
            )
            currentListener?.onImages(proofSnapshot)
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
                function abs(u){
                  try{
                    var x=new URL(String(u||''),location.href);
                    x.pathname=String(x.pathname||'/').replace(/\/{2,}/g,'/');
                    return x.href;
                  }catch(_){
                    try{return String(u||'').replace(/([^:])\/{2,}/g,'$1/');}catch(__){return '';}
                  }
                }
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
                function setRootCookie(name,value,maxAge){
                  try{document.cookie=String(name||'')+'='+encodeURIComponent(String(value||''))+'; Path=/; Max-Age='+Number(maxAge||31536000)+'; SameSite=Lax; Secure';}catch(_){}
                }
                function randomHex(len){
                  try{
                    var bytes=new Uint8Array(Math.ceil(Number(len||16)/2));
                    crypto.getRandomValues(bytes);
                    var out='';
                    for(var i=0;i<bytes.length;i++)out+=('0'+bytes[i].toString(16)).slice(-2);
                    return out.slice(0,Number(len||16));
                  }catch(_){
                    var fallback='';
                    while(fallback.length<Number(len||16))fallback+=Math.floor(Math.random()*16).toString(16);
                    return fallback.slice(0,Number(len||16));
                  }
                }
                function ensureViewerIdentityCookies(){
                  try{
                    if(!cookieValue('ntk_fp')){
                      var seed=[navigator.userAgent||'',navigator.language||'',navigator.platform||'',String(screen&&screen.width||0),String(screen&&screen.height||0),String(Date.now()),randomHex(16)].join('|');
                      var hash=2166136261;
                      for(var i=0;i<seed.length;i++){hash^=seed.charCodeAt(i);hash=Math.imul(hash,16777619)>>>0;}
                      setRootCookie('ntk_fp',('00000000'+hash.toString(16)).slice(-8)+randomHex(24),31536000);
                    }
                    var pid=cookieValue('ntk_pid');
                    try{pid=pid||localStorage.getItem('ntk_pid')||'';}catch(_){}
                    if(!/^[a-f0-9]{32}$/i.test(pid))pid=randomHex(32);
                    setRootCookie('ntk_pid',pid,62208000);
                    try{localStorage.setItem('ntk_pid',pid);}catch(_){}
                    if(!cookieValue('__vsid')){
                      var x=randomHex(32);
                      setRootCookie('__vsid',x.slice(0,8)+'-'+x.slice(8,12)+'-4'+x.slice(13,16)+'-'+x.slice(16,20)+'-'+x.slice(20,32),31536000);
                    }
                  }catch(_){}
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
                function protectedApiUrlsFromViewerToken(token,count){
                  try{
                    token=String(token||'');
                    count=Math.max(0,Math.min(128,Number(count||0)));
                    if(token.length<=10||count<=0)return [];
                    var out=[];
                    for(var i=0;i<count;i++){
                      out.push(abs('/api/m/i')+'?a='+encodeURIComponent(token)+'&i='+i);
                    }
                    return out;
                  }catch(_){return [];}
                }
                function descriptorOnlyApiUrls(urls){
                  try{
                    if(!urls||!urls.length)return false;
                    for(var i=0;i<urls.length;i++){
                      var p=new URL(String(urls[i]||''),location.href).pathname.toLowerCase();
                      if(!/\.(xml|txt|json|css|js|woff|woff2)$/.test(p))return false;
                    }
                    return true;
                  }catch(_){return false;}
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
                    var earlyEndpoint=String(data.imageApiPath||'');
                    if(earlyEndpoint!=='/api/webtoon-images'&&window.NtkQuicBridge&&!window.__ntkBrowserKeyReady){
                      try{
                        var readyRaw='{}';
                        if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent){
                          readyRaw=window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(String(location.href||''),String(navigator.userAgent||''));
                        }else if(window.NtkQuicBridge.ensureViewerBrowserKey){
                          readyRaw=window.NtkQuicBridge.ensureViewerBrowserKey(String(location.href||''));
                        }
                        var readyJson={};
                        try{readyJson=JSON.parse(String(readyRaw||'{}'));}catch(_){}
                        if(readyJson&&readyJson.keyId){
                          window.__ntkBrowserKeyReady=1;
                          window.dispatchEvent(new CustomEvent('ntk-browser-key-ready'));
                        }
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-key-ready-preflight '+!!(readyJson&&readyJson.keyId),cloudflare:false}));}catch(_){}
                      }catch(keyReadyErr){
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-key-ready-error '+String(keyReadyErr).slice(0,120),cloudflare:false}));}catch(_){}
                      }
                    }
                    if(earlyEndpoint!=='/api/webtoon-images'&&!foregroundAckProofed()&&!window.__ntkBrowserKeyReady&&window.NtkQuicBridge){
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
                      path:String(data.scopePath||location.pathname||''),
                      token:imageToken,
                      nonce:nonce,
                      proof:proof
                    };
                    var endpoint=String(data.imageApiPath||'');
                    var headers={'content-type':'application/json','accept':'application/json','x-images-client':'viewer-v1','origin':String(location.origin||''),'referer':String(location.href||'')};
                    var status=0,text='',json={};
                    if(endpoint==='/api/webtoon-images'){
                      try{
                        ensureViewerIdentityCookies();
                        var unsignedBodyText=JSON.stringify(body);
                        var pureCtrl=null,pureTid=0;
                        try{pureCtrl=new AbortController();pureTid=setTimeout(function(){try{pureCtrl.abort();}catch(_){}},1200);}catch(_){}
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-webtoon-pure-start tokenLen '+imageToken.length+' bodyLen '+unsignedBodyText.length,cookie:{len:String(document.cookie||'').length,hasAdAck:!!cookieValue('ad_ack'),hasAdAckC:!!cookieValue('ad_ack_c')},cloudflare:false}));}catch(_){}
                        var pureResp=await fetch(endpoint,{
                          method:'POST',
                          credentials:'same-origin',
                          cache:'no-store',
                          headers:{'content-type':'application/json','accept':'application/json','x-images-client':'viewer-v1'},
                          body:unsignedBodyText,
                          signal:pureCtrl&&pureCtrl.signal
                        });
                        try{if(pureTid)clearTimeout(pureTid);}catch(_){}
                        var pureText=await pureResp.text().catch(function(){return '';});
                        var pureJson={};
                        try{pureJson=JSON.parse(pureText||'{}');}catch(_){}
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-webtoon-pure '+(pureResp.status||0)+' ok '+!!pureJson.ok+' count '+((pureJson.images&&pureJson.images.length)||0)+' text '+String(pureText||'').slice(0,120),cloudflare:false}));}catch(_){}
                        if((pureResp.status||0)>=200&&(pureResp.status||0)<300&&pureJson&&pureJson.ok&&pureJson.images&&pureJson.images.length){
                          status=pureResp.status||200;
                          text=pureText;
                          json=pureJson;
                        }
                      }catch(pureErr){
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-webtoon-pure-error '+String(pureErr).slice(0,120),cloudflare:false}));}catch(_){}
                      }
                    }
                    if(status<200||status>=300||!json||!json.ok)
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
                    if(endpoint==='/api/webtoon-images'&&imageToken.length>10&&json&&json.images&&json.images.length&&(unique.length===0||descriptorOnlyApiUrls(unique))){
                      var protectedUrls=protectedApiUrlsFromViewerToken(imageToken,json.images.length);
                      if(protectedUrls.length){
                        unique=protectedUrls;
                        try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-protected-manifest '+protectedUrls.length+' from '+urls.length,cloudflare:false}));}catch(_){}
                      }
                    }
                    try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api '+status+' ok '+!!json.ok+' count '+unique.length+' text '+String(text||'').slice(0,120),cloudflare:false}));}catch(_){}
                    if(status>=200&&status<300&&unique.length){
                      viewerApiDone=1;
                      post(unique,'viewer-api-'+String(reason||''));
                      renderStrip(unique,'viewer-api-'+String(reason||''),mvNtkExpected);
                    }else{
                      var apiError='';
                      try{apiError=String((json&&json.error)||'');}catch(_){}
                      if((apiError==='fingerprint_required'||apiError==='browser_key_required'||apiError==='missing_request_key')&&!window.__mvNtkViewerApiKeyRetry&&window.NtkQuicBridge){
                        window.__mvNtkViewerApiKeyRetry=1;
                        try{window.__ntkBrowserKeyReady=0;}catch(_){}
                        try{
                          var retryRaw='{}';
                          if(window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent){
                            retryRaw=window.NtkQuicBridge.ensureViewerBrowserKeyForUserAgent(String(location.href||''),String(navigator.userAgent||''));
                          }else if(window.NtkQuicBridge.ensureViewerBrowserKey){
                            retryRaw=window.NtkQuicBridge.ensureViewerBrowserKey(String(location.href||''));
                          }
                          var retryJson={};
                          try{retryJson=JSON.parse(String(retryRaw||'{}'));}catch(_){}
                          if(retryJson&&retryJson.keyId){
                            window.__ntkBrowserKeyReady=1;
                            window.dispatchEvent(new CustomEvent('ntk-browser-key-ready'));
                          }
                          try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-key-retry '+apiError+' '+!!(retryJson&&retryJson.keyId),cloudflare:false}));}catch(_){}
                        }catch(keyRetryErr){
                          try{NtkBrowserBridge.onState(JSON.stringify({href:String(location.href||''),title:String(document.title||''),body:'viewer-api-key-retry-error '+String(keyRetryErr).slice(0,120),cloudflare:false}));}catch(_){}
                        }
                        viewerApiStarted=0;
                        tryViewerApi('after-key-retry-'+apiError);
                        return;
                      }
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
                    if(/\/(cv|mx|qc|rs)\/[^/?#]+\.(xml|txt|json|css|js|woff|woff2)([?#].*)?$/.test(u))return true;
                    if(!/\.(jpg|jpeg|png|webp)([?#].*)?$/.test(u))return false;
                    if(u.indexOf('banner')>=0||u.indexOf('advert')>=0||u.indexOf('sponsor')>=0||u.indexOf('favicon')>=0||u.indexOf('logo')>=0||u.indexOf('captcha')>=0||u.indexOf('turnstile')>=0||u.indexOf('apple-touch-icon')>=0)return false;
                    if(u.indexOf('/_next/')>=0||u.indexOf('/api/')>=0)return false;
                    if(u.indexOf('/thema/')>=0||u.indexOf('/assets/img/')>=0||u.indexOf('/assets/js/')>=0||u.indexOf('/assets/plugin/')>=0)return false;
                    return true;
                  }catch(_){return false;}
                }
                function post(urls,reason){
                  try{
                    if(isCf())return;
                    if(window.__mvNtkControlledStrip)return;
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
                function isCf(){try{var body=String(document.body&&document.body.innerText||'');if(window.__mvNtkInjectedViewerData&&String(document.title||'')==='viewer')body='';var t=(String(document.title||'')+' '+body).toLowerCase();return t.indexOf('just a moment')>=0||t.indexOf('cf-browser-verification')>=0||t.indexOf('security verification')>=0||t.indexOf('verify you are human')>=0;}catch(_){return false;}}
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
                    if(!window.__mvNtkControlledStrip)addCandidates(out,document.documentElement&&document.documentElement.innerHTML);
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
                window.__mvNtkTryViewerApiFast=tryViewerApi;
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
                function checkDrawable(){try{if(window.__mvNtkControlledStrip)return;if(window.__mvNtkFirstDrawable||isCf())return;var im=drawableImg();if(im){window.__mvNtkFirstDrawable=1;NtkBrowserBridge.onFirstDrawable(JSON.stringify({href:String(location.href||''),src:im.currentSrc||im.src||''}));if(!window.__mvNtkStripMode)setTimeout(function(){wakeLazyImages('after-first-drawable');},0);}}catch(_){}}
                function viewportCoverage(){
                  var vh=innerHeight||document.documentElement.clientHeight||0;
                  var covered=0,missing=0,drawableItems=0,totalItems=0,loading=0,errors=0,pageCount=0,loadingSamples=[];
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
                        if(loadingSamples.length<3)loadingSamples.push(String(src||im.getAttribute('data-src')||'').slice(0,140)+'@'+Math.round(r.top)+'..'+Math.round(r.bottom));
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
                    pageCount:Math.max(pageCount,totalItems),
                    loadingSamples:loadingSamples
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
                    if(window.__mvNtkControlledStrip)return;
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

    private fun isStrictEpisodePath(path: String): Boolean =
        path.matches(Regex("""^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$""", RegexOption.IGNORE_CASE))

    private fun normalizePath(path: String?): String {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return if (value.startsWith("/")) value else "/$value"
    }

    private fun normalizeImageUrl(raw: String?, documentUrl: String): String {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("data:") || value.startsWith("blob:")) return ""
        if (value.startsWith("ioiocdn.org/", ignoreCase = true) ||
            Regex("^[a-z0-9-]+\\.ioiocdn\\.org/", RegexOption.IGNORE_CASE).containsMatchIn(value)
        ) {
            value = "https://$value"
        }
        val normalized = try {
            URL(URL(documentUrl.ifBlank { currentBaseUrl + currentPath }), value).toString()
        } catch (_: Throwable) {
            if (value.startsWith("//")) "https:$value" else value
        }
        val collapsed = normalized.replace(
            Regex("^(https?://[^/?#]+)(/[^?#]*)", RegexOption.IGNORE_CASE)
        ) { match ->
            match.groupValues[1] + match.groupValues[2].replace(Regex("/{2,}"), "/")
        }
        return collapsed
            .replace(
            Regex("^http://(apihost\\d*\\.com/)", RegexOption.IGNORE_CASE),
            "https://$1"
            )
            .replace(
                Regex("^http://(fifa\\.worldcup73\\.xyz/)", RegexOption.IGNORE_CASE),
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
        if (isNtkKpDescriptorImageUrl(lower)) return true
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
                    lower.matches(Regex("https?://(?:[^/]+\\.)?booktoki\\d*\\.org/manhwa/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp|gif)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?moamoabon\\.com/blacktoon/episodes/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?fifa\\.worldcup73\\.xyz/(?:black/episodes|wt/episodes)/[^/?#]+/[^/?#]+/(?:p)?\\d{1,5}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?fifa\\.worldcup73\\.xyz/webtoon_uploads/[a-z0-9_-]+\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://[^/]+/(?:webtoon_uploads|manhwa_uploads|comic_uploads)/[a-z0-9_-]+\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")) ||
                    lower.matches(Regex("https?://(?:[^/]+\\.)?booktoki\\d*\\.org/board_uploads/\\d{4}/\\d{2}/\\d{2}/[^/?#]+\\.(jpg|jpeg|png|webp|gif)(?:[?#].*)?$")) ||
                    isNtkUploadCdnImageUrl(lower)
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
            lower.contains("/board_uploads/") ||
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
