package ml.melun.mangaview.reader

import java.net.URI

/** Pure ordering policy for replicas proved by one exact viewer-image API page slot. */
internal object NtkProofBackedAdjacentReplicaPolicy {
    fun orderedCandidates(
        canonicalAsset: String,
        exactCandidates: List<String>,
        pageIndex: Int,
        directWifiAdjacent: Boolean,
    ): List<String> {
        require(canonicalAsset.isNotBlank())
        require(pageIndex >= 0)
        if (!directWifiAdjacent) return listOf(canonicalAsset)
        val byOrigin = LinkedHashMap<String, String>()
        (exactCandidates + canonicalAsset)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedWith(compareBy<String>(
                { runCatching { URI(it).host.orEmpty().lowercase() }.getOrDefault("") },
                { it },
            ))
            .forEach { candidate ->
                val uri = runCatching { URI(candidate) }.getOrNull() ?: return@forEach
                val origin = "${uri.scheme.orEmpty().lowercase()}://" +
                    "${uri.host.orEmpty().lowercase()}:${if (uri.port < 0) 443 else uri.port}"
                byOrigin.putIfAbsent(origin, candidate)
            }
        if (byOrigin.isEmpty()) return listOf(canonicalAsset)
        val candidates = byOrigin.values.toList()
        val canonicalOrigin = runCatching {
            val uri = URI(canonicalAsset)
            "${uri.scheme.orEmpty().lowercase()}://" +
                "${uri.host.orEmpty().lowercase()}:${if (uri.port < 0) 443 else uri.port}"
        }.getOrDefault("")
        val canonical = byOrigin[canonicalOrigin] ?: canonicalAsset
        return listOf(canonical) + candidates.filterNot { it == canonical }
    }
}
