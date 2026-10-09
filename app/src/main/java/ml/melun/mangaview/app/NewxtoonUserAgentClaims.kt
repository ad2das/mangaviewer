package ml.melun.mangaview.app

/**
 * The UA-derived browser identity of the newxtoon clearance. Every value is computed on first
 * access with publication semantics, so constructing this object never touches the engine UA and
 * therefore never waits on WebView startup. Pure Kotlin: the derivation is unit-testable off-device.
 */
internal class NewxtoonUserAgentClaims(
    private val resolveEngineUserAgent: () -> String,
    private val spoofsDeviceIdentity: Boolean,
    private val greaseBrand: String,
) {
    /** The engine's own user agent; only the emulator's identity markers are rewritten from it. */
    val engineUserAgent: String by lazy(LazyThreadSafetyMode.PUBLICATION) { resolveEngineUserAgent() }

    /**
     * The full Chrome build the engine reports. Only the high-entropy client hints carry it: the
     * user agent is reduced to the major version, and a full version there matches no real Chrome.
     */
    val engineChromeVersion: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("Chrome/([0-9.]+)").find(engineUserAgent)
            ?.groupValues?.get(1) ?: "124.0.0.0"
    }

    /**
     * On the emulator the engine's UA is replaced with the reduced user agent Chrome for Android
     * ships: the engine's own UA names the device and carries the WebView marker, and Cloudflare
     * answers that with an interactive check this browser never finishes. Everywhere else the
     * engine's UA is kept byte for byte.
     */
    val sourceUserAgent: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!spoofsDeviceIdentity) engineUserAgent else chromeUserAgent(engineChromeVersion)
    }

    /** The client hints the challenge WebView actually sends, replayed on the OkHttp route. */
    val clientHints: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val version = engineChromeVersion.substringBefore('.')
        "\"$greaseBrand\";v=\"99\", \"Google Chrome\";v=\"$version\", \"Chromium\";v=\"$version\""
    }

    /**
     * Chrome for Android freezes its user agent at Android 10 with a "K" model placeholder and
     * reduces the Chrome version to its major component, so the emulator's model, build id, and
     * engine build never appear; the full version only rides the high-entropy client hints.
     */
    private fun chromeUserAgent(fullVersion: String): String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/${fullVersion.substringBefore('.')}.0.0.0 Mobile Safari/537.36"
}
