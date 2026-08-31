package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.SourceOption
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.library.UserLibrarySnapshot
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal data class LibraryState(
    val query: String,
    val sources: List<SourceOption>,
    val selectedSourceId: SourceId,
    val selectedTab: LibraryTab = LibraryTab.SEARCH,
    val content: LibraryContent,
    val lastSeries: List<SourceSeries> = emptyList(),
    val saved: UserLibrarySnapshot = UserLibrarySnapshot(),
)

internal enum class LibraryTab(val label: String) {
    SEARCH("검색"),
    RECENT("최근"),
    FAVORITES("즐겨찾기"),
    BOOKMARKS("책갈피"),
    SETTINGS("설정"),
    ;

    companion object {
        fun fromStored(value: Int): LibraryTab = entries.getOrElse(value) { SEARCH }
    }
}

internal sealed interface LibraryContent {
    data object Empty : LibraryContent
    data object Loading : LibraryContent
    data class Series(val items: List<SourceSeries>) : LibraryContent
    data class Episodes(val series: SourceSeries, val items: List<SourceEpisode>) : LibraryContent
    data class Failure(val message: String) : LibraryContent
}

internal sealed interface LibraryIntent {
    data class QueryChanged(val value: String) : LibraryIntent
    data class TabSelected(val tab: LibraryTab) : LibraryIntent
    data class SourceSelected(val sourceId: SourceId) : LibraryIntent
    data object Search : LibraryIntent
    data class SeriesSelected(val series: SourceSeries) : LibraryIntent
    data class EpisodeSelected(val episodeId: EpisodeId) : LibraryIntent
    data class SavedSeriesSelected(val series: SavedSeries) : LibraryIntent
    data class SavedEpisodeSelected(val position: ReadingPosition) : LibraryIntent
    data class FavoriteToggled(val series: SourceSeries) : LibraryIntent
    data class DarkThemeChanged(val enabled: Boolean) : LibraryIntent
    data object Back : LibraryIntent
}

internal sealed interface LibraryEffect {
    data class OpenEpisode(
        val episodeId: EpisodeId,
        val position: ReadingPosition? = null,
    ) : LibraryEffect
}
