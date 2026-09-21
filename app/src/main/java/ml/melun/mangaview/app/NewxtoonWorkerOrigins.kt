package ml.melun.mangaview.app

import android.content.Context
import android.util.Log
import java.net.URI

/**
 * Ordered worker relays for newxtoon documents.
 *
 * A Cloudflare Worker subrequest is not handed the zone challenge, which is why documents open
 * without any clearance cookie at all. The list is user-owned: whatever is configured in
 * preferences is tried first, and the public relay stays last so the app still opens documents
 * before a private deployment exists.
 */
internal object NewxtoonWorkerOrigins {
    private const val TAG = "NewxtoonClearance"
    private const val PREFERENCES = "newxtoon_worker"
    private const val KEY = "origins"

    /** The relay that subrequests the origin from Cloudflare's own network. */
    const val PUBLIC_WORKER = "https://newxtoon-relay.ad2das.workers.dev"

    @Volatile private var application: Context? = null
    @Volatile private var cached: List<String> = listOf(PUBLIC_WORKER)

    /** Called once from application start; keeps the transport free of a Context parameter. */
    fun attach(context: Context) {
        application = context.applicationContext
        cached = read(context.applicationContext)
    }

    fun current(): List<String> = cached

    fun read(context: Context): List<String> {
        val configured = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val origins = (parse(configured) + PUBLIC_WORKER).distinct()
        Log.i(TAG, "worker origins=${origins.joinToString(",")}")
        return origins
    }

    fun store(context: Context, raw: String?) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw.orEmpty()).apply()
        attach(context)
    }

    /** Accepts newline, comma or whitespace separated origins and keeps only https roots. */
    fun parse(raw: String?): List<String> = raw.orEmpty()
        .split('\n', ',', ' ', '\t')
        .mapNotNull { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val normalized = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return@mapNotNull null
            val authority = uri.authority
            if (uri.scheme != "https" || authority.isNullOrBlank()) return@mapNotNull null
            "https://$authority${uri.path.orEmpty().trimEnd('/')}"
        }
        .distinct()
}
