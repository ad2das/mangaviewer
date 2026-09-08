package ml.melun.mangaview.app

import android.content.Context
import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.ntk.NtkOriginResolver
import ml.melun.mangaview.source.wfwf.WfwfOriginResolver

/** Catalogs, artwork and the native viewer share the same verified, persisted origin. */
internal class ProviderOriginDirectory(context: Context, private val io: CoroutineDispatcher, private val userAgent: String) {
    private val preferences by lazy { context.getSharedPreferences("verified_source_origins", Context.MODE_PRIVATE) }
    private val known = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    private val loadLock = Mutex()
    private val locks = mapOf("ntk" to Mutex(), "wfwf" to Mutex())

    fun provider(url: String): String? {
        val host = URI(url).host ?: return null
        return when {
            host in NTK_HOSTS || Regex("toki[0-9]+\\.com").matches(host) -> "ntk"
            Regex("wfwf[0-9]+\\.com").matches(host) -> "wfwf"
            else -> known.entries.firstOrNull { URI(it.value).host == host }?.key
        }
    }

    suspend fun current(provider: String, fallback: String): String {
        if (!loaded) withContext(io) {
            loadLock.withLock {
                if (!loaded) {
                    locks.keys.forEach { key -> preferences.getString(key, null)?.takeIf(::validOrigin)?.let { known[key] = it } }
                    loaded = true
                }
            }
        }
        return known[provider] ?: fallback
    }

    private fun remember(provider: String, origin: String) {
        known[provider] = origin
        preferences.edit().putString(provider, origin).apply()
    }

    suspend fun recover(provider: String, failed: String, transport: SourceTransport): String? = withContext(io) {
        locks.getValue(provider).withLock {
            val published = current(provider, failed)
            if (published != failed) return@withLock published
            val resolved = when (provider) {
                "ntk" -> NtkOriginResolver(transport, userAgent).resolve(failed)
                "wfwf" -> WfwfOriginResolver(transport, userAgent).resolve(failed)
                else -> null
            }?.takeIf(::validOrigin)
            if (resolved != null) remember(provider, resolved)
            resolved
        }
    }

    suspend fun observeRedirect(provider: String, finalOrigin: String, transport: SourceTransport) {
        if (!validOrigin(finalOrigin)) return
        withContext(io) {
            locks.getValue(provider).withLock {
                if (known[provider] == finalOrigin) return@withLock
                val verified = when (provider) {
                    "ntk" -> NtkOriginResolver(transport, userAgent).resolve(finalOrigin)
                    "wfwf" -> WfwfOriginResolver(transport, userAgent).resolve(finalOrigin)
                    else -> null
                }
                if (verified == finalOrigin) remember(provider, finalOrigin)
            }
        }
    }

    private fun validOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()
    }.getOrDefault(false)

    private companion object { val NTK_HOSTS = setOf("sbxh9.com", "newtoki1.org") }
}
