package ml.melun.mangaview.viewer

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId

/** Pure manifest geometry built away from the display thread before the first state install. */
class PreparedViewerEpisode internal constructor(
    internal val manifest: EpisodeManifest,
    internal val layout: LayoutLedger,
    internal val pages: PersistentMap<PageId, PageRuntime>,
)

internal fun prepareViewerEpisode(
    manifest: EpisodeManifest,
    viewportWidth: FixedPx,
): PreparedViewerEpisode {
    val layout = LayoutLedger.create(manifest.pages, viewportWidth)
    val pages = manifest.pages.associate { it.id to PageRuntime(it) }.toPersistentMap()
    return PreparedViewerEpisode(manifest, layout, pages)
}
