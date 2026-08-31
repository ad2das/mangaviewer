package ml.melun.mangaview.data.library

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.data.settings.ViewerSettings

data class SavedSeries(
    val id: SeriesId,
    val title: String,
    val thumbnailKey: String?,
    val favorite: Boolean,
    val updatedAtEpochMillis: Long,
)

data class RecentReading(
    val series: SavedSeries,
    val episodeId: EpisodeId,
    val pageId: PageId,
    val offsetInPageUnits: Long,
    val updatedAtEpochMillis: Long,
)

data class SavedBookmark(
    val seriesTitle: String,
    val pageId: PageId,
    val offsetInPageUnits: Long,
    val createdAtEpochMillis: Long,
)

data class UserLibrarySnapshot(
    val recent: List<RecentReading> = emptyList(),
    val favorites: List<SavedSeries> = emptyList(),
    val bookmarks: List<SavedBookmark> = emptyList(),
    val settings: ViewerSettings = ViewerSettings(),
)
