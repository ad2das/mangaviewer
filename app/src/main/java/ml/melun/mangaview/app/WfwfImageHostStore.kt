package ml.melun.mangaview.app

import android.content.Context

/** Persistence seam so the host memory has JVM-testable semantics without an Android context. */
internal interface WfwfImageHostPersistence {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

private class SharedPreferencesWfwfImageHostPersistence(
    context: Context,
) : WfwfImageHostPersistence {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "wfwf_image_hosts"
    }
}

/**
 * Remembers the bare page-image hosts a source last served episode pages from. Only host names
 * are stored, never signed URLs: a remembered host is used to open DNS, TCP and TLS before the
 * episode document produces fresh signed URLs, so a wrong or dead host is only an abandoned hint.
 */
internal class WfwfImageHostStore(
    private val persistence: WfwfImageHostPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesWfwfImageHostPersistence(context))

    /** Most-recent-first distinct hosts, at most [CAP]. */
    fun hosts(sourceId: String): List<String> = decode(persistence.read(key(sourceId)))

    /** Folds [observed] ahead of the stored hosts, keeping only the newest [CAP] distinct hosts. */
    fun remember(sourceId: String, observed: Iterable<String>) {
        val current = hosts(sourceId)
        val merged = merge(observed, current)
        if (merged.isNotEmpty() && merged != current) {
            persistence.write(key(sourceId), merged.joinToString(SEPARATOR))
        }
    }

    private fun key(sourceId: String): String = "pageHosts.$sourceId"

    private fun merge(observed: Iterable<String>, existing: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        observed.forEach { host -> normalize(host)?.let(merged::add) }
        existing.forEach(merged::add)
        return merged.take(CAP)
    }

    private fun normalize(host: String): String? = host.trim().lowercase()
        .takeIf { it.isNotEmpty() && it.length <= HOST_LENGTH_LIMIT && !it.contains('/') }

    private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)
        .orEmpty().mapNotNull(::normalize).distinct().take(CAP)

    private companion object {
        const val CAP = 2
        const val SEPARATOR = "\n"
        const val HOST_LENGTH_LIMIT = 253
    }
}
