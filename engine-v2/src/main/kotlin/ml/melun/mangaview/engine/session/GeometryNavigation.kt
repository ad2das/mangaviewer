package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId

internal fun DocumentGeometry.terminalPageResult(): PageResult {
    var id = targetEpisodeId
    val seen = HashSet<EpisodeId>()
    while (true) {
        if (!seen.add(id)) return PageResult(null, GeometryBlocker.Episode(id))
        val manifest = manifests[id] ?: return PageResult(null, GeometryBlocker.Episode(id))
        val next = manifest.nextEpisodeId
        if (next == null) {
            val blocker = if (isNavigationKnown(id)) null else GeometryBlocker.Navigation(id)
            val page = manifest.pages.lastOrNull()
                ?: return PageResult(null, blocker ?: GeometryBlocker.Episode(id))
            return PageResult(PageRef(page.id, actualDimensions[page.id]), blocker)
        }
        if (!manifests.containsKey(next)) {
            val page = manifest.pages.lastOrNull()
                ?: return PageResult(null, GeometryBlocker.Episode(next))
            return PageResult(PageRef(page.id, actualDimensions[page.id]), GeometryBlocker.Episode(next))
        }
        id = next
    }
}

internal fun DocumentGeometry.nextPage(pageId: PageId): PageStep {
    val manifest = manifests[pageId.episodeId] ?: return PageStep.Missing(
        GeometryBlocker.Episode(pageId.episodeId))
    val index = manifest.pages.indexOfFirst { it.id == pageId }
    if (index < 0) return PageStep.Missing(GeometryBlocker.Episode(pageId.episodeId))
    if (index + 1 < manifest.pages.size) return PageStep.Known(manifest.pages[index + 1].id)
    val next = manifest.nextEpisodeId ?: return if (isNavigationKnown(manifest.id)) {
        PageStep.End
    } else {
        PageStep.Missing(GeometryBlocker.Navigation(manifest.id))
    }
    if (!manifests.containsKey(next)) return PageStep.Missing(GeometryBlocker.Episode(next))
    val page = manifests[next]?.pages?.firstOrNull() ?: return PageStep.Missing(
        GeometryBlocker.Episode(next))
    return PageStep.Known(page.id)
}

internal fun DocumentGeometry.previousPage(pageId: PageId): PageStep {
    val manifest = manifests[pageId.episodeId] ?: return PageStep.Missing(
        GeometryBlocker.Episode(pageId.episodeId))
    val index = manifest.pages.indexOfFirst { it.id == pageId }
    if (index < 0) return PageStep.Missing(GeometryBlocker.Episode(pageId.episodeId))
    if (index > 0) return PageStep.Known(manifest.pages[index - 1].id)
    if (pageId.episodeId == targetEpisodeId) return PageStep.End
    if (manifest.previousEpisodeId == null) return if (isNavigationKnown(manifest.id)) {
        PageStep.End
    } else {
        PageStep.Missing(GeometryBlocker.Navigation(manifest.id))
    }
    val previous = requireNotNull(manifest.previousEpisodeId)
    if (!manifests.containsKey(previous)) return PageStep.Missing(GeometryBlocker.Episode(previous))
    val page = manifests[previous]?.pages?.lastOrNull() ?: return PageStep.Missing(
        GeometryBlocker.Episode(previous))
    return PageStep.Known(page.id)
}
