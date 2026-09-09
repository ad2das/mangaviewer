package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.PageContentIdentity

/** Preparation follows verified manifest order without waiting for intervening original bodies. */
internal fun preparedPagesFrom(snapshot: EngineRuntimeSnapshot, start: PageId, direction: Int,
    includeStart: Boolean = false,
): Sequence<PageContentIdentity> = sequence {
    require(direction == 1 || direction == -1)
    var manifest = snapshot.plans[start.episodeId]?.manifest ?: return@sequence
    var index = manifest.pages.indexOfFirst { it.id == start }
    if (index < 0) return@sequence
    if (!includeStart) index += direction
    val visited = linkedSetOf(manifest.id)
    while (true) {
        while (index in manifest.pages.indices) {
            // Missing pages are neither decoded nor placed. Later verified originals can
            // still use the planner's existing distance and byte budgets during that wait.
            snapshot.pages[manifest.pages[index].id]?.let { yield(it) }
            index += direction
        }
        val next = if (direction > 0) manifest.nextEpisodeId else manifest.previousEpisodeId
        if (next == null || !visited.add(next)) break
        manifest = snapshot.plans[next]?.manifest ?: break
        index = if (direction > 0) 0 else manifest.pages.lastIndex
    }
}
