package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.SourceOption
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.library.SavedSeries
import ml.melun.mangaview.data.library.UserLibrarySnapshot
import ml.melun.mangaview.data.offline.DownloadedEpisode
import ml.melun.mangaview.data.offline.EpisodeDownloadState
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SearchField
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
    val genres: GenreContent = GenreContent.Empty,
    val selectedGenre: SourceGenre? = null,
    val genreCatalog: LibraryContent = LibraryContent.Empty,
    val searchKind: SeriesKind? = null,
    val searchField: SearchField = SearchField.TITLE,
    val detailTab: DetailTab = DetailTab.INTRO,
    val activeSeries: SourceSeries? = null,
    val lastSeries: List<SourceSeries> = emptyList(),
    val saved: UserLibrarySnapshot = UserLibrarySnapshot(),
    val offlineEpisodes: List<DownloadedEpisode> = emptyList(),
    val downloadStates: Map<EpisodeId, EpisodeDownloadState> = emptyMap(),
    val settingsVisible: Boolean = false,
    val preferencesVisible: Boolean = false,
    val seriesMenuVisible: Boolean = false,
    val downloadSelectionVisible: Boolean = false,
    val pendingOfflineRemoval: EpisodeId? = null,
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
    OFFLINE("저장됨"),
}

internal enum class DetailTab(val label: String) {
    INTRO("소개"),
    EPISODES("회차"),
    INFO("정보"),
}

internal sealed interface HomeContent {
    data object Loading : HomeContent
    data class Ready(
        val popular: List<SourceSeries>,
        val latest: List<SourceSeries>,
        val new: List<SourceSeries>,
    ) : HomeContent
    data class Failure(val message: String) : HomeContent
}

internal sealed interface GenreContent {
    data object Empty : GenreContent
    data object Loading : GenreContent
    data class Ready(val items: List<SourceGenre>) : GenreContent
    data class Failure(val message: String) : GenreContent
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
    data class GenreSelected(val value: SourceGenre) : LibraryIntent
    data class DetailTabSelected(val value: DetailTab) : LibraryIntent
    data class SearchKindSelected(val value: SeriesKind?) : LibraryIntent
    data class SearchFieldSelected(val value: SearchField) : LibraryIntent
    data object Search : LibraryIntent
    data object RetryHome : LibraryIntent
    data object ToggleSettings : LibraryIntent
    data object TogglePreferences : LibraryIntent
    data object AccountSignIn : LibraryIntent
    data object CheckForUpdate : LibraryIntent
    data object OpenLicenses : LibraryIntent
    data class StartTabChanged(val value: Int) : LibraryIntent
    data object ToggleSeriesMenu : LibraryIntent
    data object ToggleDownloadSelection : LibraryIntent
    data class OpenSeriesInBrowser(val series: SourceSeries) : LibraryIntent
    data class ShareSeries(val series: SourceSeries) : LibraryIntent
    data class SeriesSelected(val series: SourceSeries) : LibraryIntent
    data class EpisodeSelected(val episodeId: EpisodeId) : LibraryIntent
    data class SavedSeriesSelected(val series: SavedSeries) : LibraryIntent
    data class OfflineSeriesSelected(val series: SourceSeries) : LibraryIntent
    data class SavedEpisodeSelected(val position: ReadingPosition) : LibraryIntent
    data class FavoriteToggled(val series: SourceSeries) : LibraryIntent
    data class DownloadEpisode(val series: SourceSeries, val episode: SourceEpisode) : LibraryIntent
    data class DownloadEpisodes(val series: SourceSeries, val episodes: List<SourceEpisode>) : LibraryIntent
    data class RemoveOfflineEpisode(val episodeId: EpisodeId) : LibraryIntent
    data object ConfirmOfflineRemoval : LibraryIntent
    data object CancelOfflineRemoval : LibraryIntent
    data class DarkThemeChanged(val enabled: Boolean) : LibraryIntent
    data object Back : LibraryIntent
}

internal sealed interface LibraryEffect {
    data class OpenEpisode(
        val episodeId: EpisodeId,
        val position: ReadingPosition? = null,
    ) : LibraryEffect
    data class OpenUri(val value: String) : LibraryEffect
    data class ShareText(val title: String, val value: String) : LibraryEffect
    data class ShowMessage(val value: String) : LibraryEffect
}
