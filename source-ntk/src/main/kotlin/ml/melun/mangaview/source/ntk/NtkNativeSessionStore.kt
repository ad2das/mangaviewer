package ml.melun.mangaview.source.ntk

import android.content.Context
import org.json.JSONObject

/**
 * Persists the native manifest flight's provider cookies per origin, the nv session included, so a
 * cold start can skip the ad challenge and nv issuance and go straight to the proof exchange.
 * Nothing here is trusted: a stale session simply fails the provider's proof and the caller
 * repeats the full challenge flight once.
 */
class NtkNativeSessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(origin: String): Map<String, String>? = runCatching {
        val raw = preferences.getString(origin, null) ?: return null
        val cookies = JSONObject(raw).optJSONObject(KEY_COOKIES) ?: return null
        buildMap {
            cookies.keys().forEach { name ->
                val value = cookies.optString(name)
                if (name.isNotBlank() && value.isNotBlank()) put(name, value)
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun save(origin: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        runCatching {
            preferences.edit()
                .putString(origin, JSONObject()
                    .put(KEY_COOKIES, JSONObject(cookies.toMap()))
                    .put(KEY_SAVED_AT, System.currentTimeMillis())
                    .toString())
                .apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "ntk_native_sessions"
        const val KEY_COOKIES = "cookies"
        const val KEY_SAVED_AT = "savedAtMillis"
    }
}
