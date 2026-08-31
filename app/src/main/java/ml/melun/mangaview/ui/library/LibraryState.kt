package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.SourceOption
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.library.UserLibrarySnapshot
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal data class LibraryState(
    val query: String,
    val sources: List<SourceOption>,
    val selectedSourceId: SourceId,
    val destination: MainDestination = MainDestination.HOME,
    val homeKind: SeriesKind = SeriesKind.WEBTOON,
    val homeTab: HomeTab = HomeTab.HOME,
    val libraryTab: SavedTab = SavedTab.ALL,
    val content: LibraryContent = LibraryContent.Empty,
    val home: HomeContent = HomeContent.Loading,
    val activeSeries: SourceSeries? = null,
    val lastSeries: List<SourceSeries> = emptyList(),
    val saved: UserLibrarySnapshot = UserLibrarySnapshot(),
    val settingsVisible: Boolean = false,
)

internal enum class MainDestination(val label: String) {
    HOME("홈"),
    SEARCH("검색"),
    LIBRARY("보관함"),
    ;

    companion object {
        fun fromStored(value: Int): MainDestination = entries.getOrElse(value) { HOME }
    }
}

internal enum class HomeTab(val label: String) {
    HOME("홈"),
    POPULAR("인기"),
    NEW("신작"),
    GENRES("장르"),
}

internal enum class SavedTab(val label: String) {
    ALL("전체"),
    RECENT("최근"),
    FAVORITES("좋아요"),
    BOOKMARKS("저장됨"),
}

internal sealed interface HomeContent {
    data object Loading : HomeContent
    data class Ready(
        val popular: List<SourceSeries>,
        val latest: List<SourceSeries>,
        val new: List<SourceSeries>,
        val genre: String? = null,
    ) : HomeContent
    data class Failure(val message: String) : HomeContent
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
    data class DestinationSelected(val value: MainDestination) : LibraryIntent
    data class SourceSelected(val sourceId: SourceId) : LibraryIntent
    data class HomeKindSelected(val value: SeriesKind) : LibraryIntent
    data class HomeTabSelected(val value: HomeTab) : LibraryIntent
    data class SavedTabSelected(val value: SavedTab) : LibraryIntent
    data class GenreSelected(val value: String) : LibraryIntent
    data object Search : LibraryIntent
    data object RetryHome : LibraryIntent
    data object ToggleSettings : LibraryIntent
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
