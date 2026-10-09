package ml.melun.mangaview.app

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat

/**
 * Lock-free cache for the engine's WebView user agent. Resolving the agent can wait for WebView
 * startup, which must run on the main thread, so [get] never holds a lock while [resolve] runs: a
 * main-thread caller runs the startup itself and unblocks any background caller instead of waiting
 * behind it. Racing callers may both resolve; the first published value wins, the same contract as
 * [LazyThreadSafetyMode.PUBLICATION].
 */
internal class EngineUserAgentSource(private val resolve: () -> String) {
    @Volatile
    private var cached: String? = null

    fun get(): String {
        cached?.let { return it }
        val resolved = resolve()
        if (cached == null) cached = resolved
        return cached ?: resolved
    }
}

/** The production resolver: the engine's own user agent, with the legacy fallback when it fails. */
internal fun defaultEngineUserAgentSource(context: Context): EngineUserAgentSource {
    val appContext = context.applicationContext
    return EngineUserAgentSource {
        runCatching { WebSettings.getDefaultUserAgent(appContext) }
            .getOrElse { fallbackEngineUserAgent(appContext) }
    }
}

private const val DEVICE_MODEL = "SM-S918N"
private const val DEVICE_BUILD = "UP1A.231005.007"

/**
 * The fallback UA used only when the engine reports none: the installed WebView package's version
 * dressed as a phone WebView, matching the legacy `fallbackUserAgent` byte for byte.
 */
private fun fallbackEngineUserAgent(appContext: Context): String {
    val version = runCatching {
        WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName
    }.getOrNull() ?: "124.0.0.0"
    return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
        "$DEVICE_MODEL Build/$DEVICE_BUILD; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/$version Mobile Safari/537.36"
}
