package ml.melun.mangaview.app

import android.content.Context
import android.webkit.CookieManager
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Keeps browser cookies and HTTP cookies in sync without exposing their values in logs. The
 * clearance is also persisted so a fresh process does not pay the managed challenge again inside
 * the cookie's lifetime.
 */
internal class NewxtoonCookieStore(
    private val origin: String,
    context: Context? = null,
) {
    private val originUrl = origin.toHttpUrl()
    private val host = originUrl.host
    private val jarStore = ConcurrentHashMap<String, List<Cookie>>()
    private val prefs = context?.applicationContext
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Cloudflare plants a pending cf_clearance while the challenge is still running, so the cookie
     * only proves anything after a solve completed or a replayed request was actually served.
     * Persistence — and the jar-side clearance check — only trust that verified state.
     */
    private val verified = java.util.concurrent.atomic.AtomicBoolean(false)

    val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(jarStore) {
                val existing = jarStore[url.host].orEmpty().filterNot { stored ->
                    cookies.any { it.name == stored.name && it.path == stored.path }
                }
                jarStore[url.host] = existing + cookies
            }
            if (verified.get()) {
                cookies.firstOrNull { it.name == CLEARANCE_COOKIE && url.host == host }?.let {
                    persistClearance(it.value, it.expiresAt)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(jarStore) {
                return jarStore[url.host].orEmpty().filter { it.expiresAt > now && it.matches(url) }
            }
        }
    }

    init {
        // A still-valid clearance from a previous process seeds the HTTP jar immediately, so the
        // first catalog request can succeed without a WebView solve at all.
        restorePersistedClearance()
    }

    /**
     * The edge refused a request from the browser that owns the clearance, so the cookie is dead
     * no matter what it claims: trust is withdrawn and the persisted copy is dropped so the next
     * process pays the challenge instead of trusting a corpse.
     */
    fun markStale() {
        verified.set(false)
        prefs?.edit()?.remove(KEY_CLEARANCE)?.remove(KEY_CLEARANCE_EXPIRES_AT)?.apply()
        synchronized(jarStore) { jarStore[host] = emptyList() }
    }

    /** Marks the current clearance as proven so it may be persisted for future processes. */
    fun markVerified() {
        verified.set(true)
        // Pull the cookie out of the browser jar first — saveFromResponse only persists while
        // verified, and the WebView is where the final cf_clearance actually lands.
        harvest()
        jarStore[host]?.firstOrNull { it.name == CLEARANCE_COOKIE }?.let {
            persistClearance(it.value, it.expiresAt)
        }
    }

    fun clearanceValue(): String? {
        val header = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
            ?: return null
        return header.split(';').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=').trim() != CLEARANCE_COOKIE) null
            else pair.substringAfter('=', "").trim().ifEmpty { null }
        }
    }

    /**
     * True only when a clearance was actually proven to serve requests — a persisted cookie or a
     * completed solve. A pending cf_clearance mid-challenge does not qualify, so transports may
     * trust this to mean the plain HTTP route will be refused by fingerprint binding.
     */
    fun hasVerifiedClearance(): Boolean {
        if (!verified.get()) return false
        if (clearanceValue() != null) return true
        return jarStore[host].orEmpty().any {
            it.name == CLEARANCE_COOKIE && it.expiresAt > System.currentTimeMillis()
        }
    }

    fun hasClearance(): Boolean {
        if (clearanceValue() != null) return true
        // The WebView cookie jar does not survive a process death; the persisted value does. The
        // jar-side check only counts when the clearance was actually verified — a pending cookie
        // harvested mid-challenge must not report a clearance that the edge will refuse.
        if (!verified.get()) return false
        return jarStore[host].orEmpty().any {
            it.name == CLEARANCE_COOKIE && it.expiresAt > System.currentTimeMillis()
        }
    }

    fun harvest() {
        val header = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
            ?: return
        val now = System.currentTimeMillis()
        val harvested = header.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null
            else Cookie.Builder()
                .name(name)
                .value(value)
                .hostOnlyDomain(host)
                .path("/")
                .expiresAt(now + CLEARANCE_TTL_MILLIS)
                .build()
        }
        if (harvested.isNotEmpty()) cookieJar.saveFromResponse(originUrl, harvested)
    }

    /**
     * Pushes the persisted clearance back into the WebView cookie jar. Called before a challenge
     * page loads so a cookie that is still valid short-circuits the interstitial entirely.
     */
    fun seedWebView() {
        if (!verified.get()) return
        val value = persistedClearance() ?: return
        runCatching {
            CookieManager.getInstance().setCookie(
                origin,
                "$CLEARANCE_COOKIE=$value; Domain=$host; Path=/; Secure",
            )
            CookieManager.getInstance().flush()
        }
    }

    private fun restorePersistedClearance() {
        val value = persistedClearance() ?: return
        verified.set(true)
        val now = System.currentTimeMillis()
        jarStore[host] = listOf(
            Cookie.Builder()
                .name(CLEARANCE_COOKIE)
                .value(value)
                .hostOnlyDomain(host)
                .path("/")
                .expiresAt(now + CLEARANCE_TTL_MILLIS)
                .build(),
        )
    }

    /** True when a previously proven clearance survives this process and is still inside its TTL. */
    fun hasPersistedClearance(): Boolean = persistedClearance() != null

    private fun persistedClearance(): String? {
        val store = prefs ?: return null
        val value = store.getString(KEY_CLEARANCE, null)?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAt = store.getLong(KEY_CLEARANCE_EXPIRES_AT, 0L)
        if (expiresAt <= System.currentTimeMillis()) return null
        return value
    }

    private fun persistClearance(value: String, expiresAt: Long) {
        prefs?.edit()
            ?.putString(KEY_CLEARANCE, value)
            ?.putLong(KEY_CLEARANCE_EXPIRES_AT, expiresAt)
            ?.apply()
    }

    private companion object {
        const val PREFS_NAME = "newxtoon_clearance"
        const val KEY_CLEARANCE = "cf_clearance"
        const val KEY_CLEARANCE_EXPIRES_AT = "cf_clearance_expires_at"
        const val CLEARANCE_COOKIE = "cf_clearance"
        const val CLEARANCE_TTL_MILLIS = 30L * 60L * 1_000L
    }
}
