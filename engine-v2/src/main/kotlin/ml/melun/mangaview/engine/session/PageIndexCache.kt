package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId

/**
 * Per-episode PageId -> index maps keyed by manifest identity. EngineSession mutates the geometry
 * maps directly: resolveNavigation replaces a manifest with a copy, navigate clears them, and
 * retainWindow prunes them, so an entry rebuilds only when its manifest instance changes and is
 * dropped together with its episode. A cached lookup replaces the O(pages) identity scan of
 * previousPage/nextPage with one hash probe on the geometry hot path.
 */
internal class PageIndexCache {
    private class Entry(val manifest: EpisodeManifest) {
        val indices: HashMap<PageId, Int> = HashMap(manifest.pages.size)
        init {
            manifest.pages.forEachIndexed { index, page -> indices.putIfAbsent(page.id, index) }
        }
    }

    private val entries = HashMap<EpisodeId, Entry>()

    // Test-only cost evidence; production never reads these counters.
    var builds: Long = 0L
        private set
    var scans: Long = 0L
        private set
    var lookups: Long = 0L
        private set

    fun indexOf(manifest: EpisodeManifest, pageId: PageId): Int {
        val cached = entries[manifest.id]
        if (cached == null || cached.manifest !== manifest) {
            val entry = Entry(manifest)
            entries[manifest.id] = entry
            builds++
            scans += manifest.pages.size
            return entry.indices[pageId] ?: -1
        }
        lookups++
        return cached.indices[pageId] ?: -1
    }

    fun retainEpisodes(episodes: Set<EpisodeId>) {
        entries.keys.retainAll(episodes)
    }
}
