package ml.melun.mangaview.reader

/**
 * Admits only exact viewer control metadata for a direct-Wi-Fi manhwa neighbor while its
 * predecessor is still being read. Numeric paths may issue their bounded format HEAD probes and
 * every path may fetch its viewer document; the discovery worker retains its independent
 * full-drawable gate before image API, body, source, decode, or Surface work.
 */
internal object NtkAdjacentMetadataControlPolicy {
    private val MANHWA_VIEWER_PATH = Regex(
        "^/manhwa/\\d{1,12}/(?:\\d{1,12}|u-[a-z0-9_-]+)$",
        RegexOption.IGNORE_CASE,
    )

    fun mayOpenAtFlightAdmission(
        directWifiAdjacentBodyGate: Boolean,
        targetEpisodePath: String,
    ): Boolean {
        if (!directWifiAdjacentBodyGate) return false
        val normalized = NtkStripDigests.normalizeEpisodePath(targetEpisodePath)
        return MANHWA_VIEWER_PATH.matches(normalized)
    }
}
