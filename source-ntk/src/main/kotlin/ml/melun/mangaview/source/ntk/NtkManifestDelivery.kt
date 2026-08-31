package ml.melun.mangaview.source.ntk

/** Preserves the captured provider response verbatim and admits exactly one completion. */
internal class NtkManifestDelivery {
    private var payload: String? = null

    fun accept(candidate: String): String? {
        if (candidate.isBlank() || payload != null) return null
        payload = candidate
        return candidate
    }

    fun completedPayload(): String? = payload
}
