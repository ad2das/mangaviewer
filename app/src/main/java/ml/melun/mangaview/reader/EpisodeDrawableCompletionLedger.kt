package ml.melun.mangaview.reader

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-lifetime proof that a canonical episode source produced a real drawable at least once.
 *
 * Decoded pixels are an LRU resource and may be evicted while the user reads a long chapter.
 * Adjacent-episode liveness must not regress when that happens: the current viewport can rehydrate
 * an evicted source, while the one-time canonical completion proof remains valid.  Keys include
 * episode, source ordinal, split side and exact manifest identity so display-index remaps,
 * auto-cut halves and same-path manifest replacement cannot create an ABA match.
 */
internal class EpisodeDrawableCompletionLedger {
    internal data class Key(
        val normalizedEpisodePath: String,
        val sourceIndex: Int,
        val side: Int,
        val canonicalAsset: String,
        val manifestDigest: String,
        val manifestPageCount: Int,
    )

    private val completed = ConcurrentHashMap.newKeySet<Key>()

    fun mark(
        normalizedEpisodePath: String,
        sourceIndex: Int,
        side: Int,
        canonicalAsset: String,
        manifestDigest: String,
        manifestPageCount: Int,
    ): Boolean {
        val key = keyOrNull(
            normalizedEpisodePath,
            sourceIndex,
            side,
            canonicalAsset,
            manifestDigest,
            manifestPageCount,
        ) ?: return false
        return completed.add(key)
    }

    fun contains(
        normalizedEpisodePath: String,
        sourceIndex: Int,
        side: Int,
        canonicalAsset: String,
        manifestDigest: String,
        manifestPageCount: Int,
    ): Boolean {
        val key = keyOrNull(
            normalizedEpisodePath,
            sourceIndex,
            side,
            canonicalAsset,
            manifestDigest,
            manifestPageCount,
        ) ?: return false
        return completed.contains(key)
    }

    /** Retires consumed episode history without coupling the proof to display-index removal. */
    fun removeEpisodes(normalizedEpisodePaths: Set<String>) {
        if (normalizedEpisodePaths.isEmpty()) return
        completed.removeIf { it.normalizedEpisodePath in normalizedEpisodePaths }
    }

    fun size(): Int = completed.size

    private fun keyOrNull(
        normalizedEpisodePath: String,
        sourceIndex: Int,
        side: Int,
        canonicalAsset: String,
        manifestDigest: String,
        manifestPageCount: Int,
    ): Key? {
        val path = normalizedEpisodePath.trim()
        val asset = canonicalAsset.trim()
        val digest = manifestDigest.trim()
        if (path.isEmpty() || sourceIndex < 0 || side < 0 || asset.isEmpty() ||
            manifestPageCount < 0
        ) return null
        return Key(path, sourceIndex, side, asset, digest, manifestPageCount)
    }
}
