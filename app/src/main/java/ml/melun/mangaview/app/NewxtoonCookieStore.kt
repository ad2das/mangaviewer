package ml.melun.mangaview.app

import android.webkit.CookieManager
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Keeps browser cookies and HTTP cookies in sync without exposing their values in logs. */
internal class NewxtoonCookieStore(private val origin: String) {
    private val originUrl = origin.toHttpUrl()
    private val host = originUrl.host
    private val jarStore = ConcurrentHashMap<String, List<Cookie>>()

    val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(jarStore) {
                val existing = jarStore[url.host].orEmpty().filterNot { stored ->
                    cookies.any { it.name == stored.name && it.path == stored.path }
                }
                jarStore[url.host] = existing + cookies
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(jarStore) {
                return jarStore[url.host].orEmpty().filter { it.expiresAt > now && it.matches(url) }
            }
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

    fun hasClearance(): Boolean {
        val header = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
            ?: return false
        return header.split(';').any { pair ->
            val name = pair.substringBefore('=').trim()
            name == CLEARANCE_COOKIE && pair.substringAfter('=', "").trim().isNotEmpty()
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


    private companion object {
        const val CLEARANCE_COOKIE = "cf_clearance"
        const val CLEARANCE_TTL_MILLIS = 30L * 60L * 1_000L
    }
}
