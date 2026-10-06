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
    val index = pageIndices.indexOf(manifest, pageId)
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
    val index = pageIndices.indexOf(manifest, pageId)
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

internal fun DocumentGeometry.requirementsForAnchor(): GeometryRequirements {
    val value = anchor ?: return GeometryRequirements(emptySet(), setOf(targetEpisodeId), emptySet())
    val page = page(value.pageId)
    if (page == null) return GeometryRequirements(emptySet(), setOf(value.pageId.episodeId), emptySet())
    val navigation = if (isNavigationKnown(value.pageId.episodeId)) emptySet() else {
        setOf(value.pageId.episodeId)
    }
    if (page.dimensions == null) return GeometryRequirements(setOf(value.pageId), emptySet(), navigation)
    return GeometryRequirements(emptySet(), emptySet(), navigation)
}

/**
 * A long in-place read crosses documents without a navigate. Keep only the documents around the
 * reading position (and the session's target) so the maps cannot grow with every episode
 * crossed; a pruned document is re-requested through the ordinary geometry blockers.
 */
internal fun DocumentGeometry.retainWindow(
    anchorEpisodeId: EpisodeId?,
    target: EpisodeId = targetEpisodeId,
    maximum: Int = RETAINED_DOCUMENTS,
) {
    if (manifests.size <= maximum) return
    val keep = linkedSetOf<EpisodeId>()
    if (anchorEpisodeId != null) {
        keep += anchorEpisodeId
        var cursor: EpisodeId? = anchorEpisodeId
        var steps = 0
        while (cursor != null && steps < RETAINED_BACK_STEPS) {
            cursor = manifests[cursor]?.previousEpisodeId
            if (cursor != null) keep += cursor
            steps++
        }
        cursor = anchorEpisodeId
        steps = 0
        while (cursor != null && steps < RETAINED_FORWARD_STEPS) {
            cursor = manifests[cursor]?.nextEpisodeId
            if (cursor != null) keep += cursor
            steps++
        }
    }
    keep += target
    if (manifests.keys.all { it in keep }) return
    manifests.keys.retainAll(keep)
    navigationKnown.keys.retainAll(keep)
    actualDimensions.keys.retainAll { it.episodeId in keep }
    pruneMetrics()
    prunePageIndex()
}

// Documents kept around the reading position by retainWindow: the anchor, a deep backward chain
// (a fast reverse burst swings the reader several episodes back before any request can run), two
// forward links, and the session's target.
private const val RETAINED_DOCUMENTS = 16
private const val RETAINED_BACK_STEPS = 12
private const val RETAINED_FORWARD_STEPS = 2
