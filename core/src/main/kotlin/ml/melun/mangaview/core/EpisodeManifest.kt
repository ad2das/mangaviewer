package ml.melun.mangaview.core

data class EpisodeManifest(
    val id: EpisodeId,
    val title: String,
    val pages: List<PageSpec>,
    val previousEpisodeId: EpisodeId? = null,
    val nextEpisodeId: EpisodeId? = null,
    val revision: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Episode title must not be blank" }
        require(pages.isNotEmpty()) { "Episode must contain at least one page" }
        require(pages.map(PageSpec::id).toSet().size == pages.size) {
            "Page ids must be unique within an episode"
        }
        pages.forEachIndexed { expectedOrdinal, page ->
            require(page.id.episodeId == id) { "Page belongs to another episode" }
            require(page.ordinal == expectedOrdinal) { "Page ordinals must be contiguous" }
        }
        require(previousEpisodeId == null || previousEpisodeId.seriesId == id.seriesId) {
            "Previous episode belongs to another series"
        }
        require(nextEpisodeId == null || nextEpisodeId.seriesId == id.seriesId) {
            "Next episode belongs to another series"
        }
    }
}
