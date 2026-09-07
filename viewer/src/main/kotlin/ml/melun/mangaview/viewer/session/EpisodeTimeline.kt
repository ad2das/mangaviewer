package ml.melun.mangaview.viewer.session

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec

data class TimelineEpisode(
    val manifest: EpisodeManifest,
    val firstPageIndex: Int,
)

class EpisodeTimeline private constructor(
    val episodes: PersistentList<TimelineEpisode>,
    val pages: PersistentList<PageSpec>,
    private val pageIndex: Map<PageId, Int>,
    private val episodeIndex: Map<EpisodeId, Int>,
) {
    val isEmpty: Boolean get() = episodes.isEmpty()
    val lastManifest: EpisodeManifest? get() = episodes.lastOrNull()?.manifest

    fun pageIndex(pageId: PageId): Int? = pageIndex[pageId]
    fun episodeIndex(episodeId: EpisodeId): Int? = episodeIndex[episodeId]

    fun append(manifest: EpisodeManifest): EpisodeTimeline {
        require(manifest.id !in episodeIndex) { "Episode is already in the timeline" }
        val prior = lastManifest
        require(prior == null || prior.id.seriesId == manifest.id.seriesId) {
            "Timeline cannot mix series"
        }
        require(prior == null || prior.nextEpisodeId == null || prior.nextEpisodeId == manifest.id) {
            "Episode is not the declared successor"
        }
        require(manifest.pages.none { it.id in pageIndex }) { "Page is already in the timeline" }
        return rebuild(
            episodes.map(TimelineEpisode::manifest) + manifest,
        )
    }

    fun pageIdsAfter(pageId: PageId): List<PageId> {
        val index = pageIndex[pageId] ?: return emptyList()
        return pages.subList(index + 1, pages.size).map(PageSpec::id)
    }

    companion object {
        val EMPTY = EpisodeTimeline(persistentListOf(), persistentListOf(), emptyMap(), emptyMap())

        fun start(manifest: EpisodeManifest): EpisodeTimeline = rebuild(listOf(manifest))

        private fun rebuild(manifests: List<EpisodeManifest>): EpisodeTimeline {
            val episodes = ArrayList<TimelineEpisode>(manifests.size)
            val pages = ArrayList<PageSpec>()
            manifests.forEach { manifest ->
                episodes += TimelineEpisode(manifest, pages.size)
                pages += manifest.pages
            }
            return EpisodeTimeline(
                episodes.toPersistentList(),
                pages.toPersistentList(),
                pages.mapIndexed { index, page -> page.id to index }.toMap(),
                episodes.mapIndexed { index, episode -> episode.manifest.id to index }.toMap(),
            )
        }
    }
}
