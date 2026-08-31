package ml.melun.mangaview.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.db.BookmarkEntity
import ml.melun.mangaview.data.db.LibraryEntryEntity
import ml.melun.mangaview.data.db.ReadingProgressEntity
import ml.melun.mangaview.data.db.ViewerDao
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.data.settings.ViewerSettingsStore

class UserLibraryRepository(
    private val dao: ViewerDao,
    private val settingsStore: ViewerSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshot: Flow<UserLibrarySnapshot> = combine(
        dao.library(),
        dao.progressHistory(),
        dao.bookmarks(),
        settingsStore.settings,
        ::assembleSnapshot,
    )

    suspend fun recordOpened(
        seriesId: SeriesId,
        title: String,
        thumbnailKey: String?,
        episodeId: EpisodeId,
    ) {
        require(episodeId.seriesId == seriesId)
        val now = clock()
        dao.recordOpened(
            entry(seriesId, title, thumbnailKey, favorite = false, now),
            progress(episodeId, now),
        )
    }

    suspend fun setFavorite(
        seriesId: SeriesId,
        title: String,
        thumbnailKey: String?,
        favorite: Boolean,
    ) {
        dao.saveLibraryEntry(entry(seriesId, title, thumbnailKey, favorite, clock()))
    }

    suspend fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) {
        settingsStore.update(transform)
    }

    suspend fun saveProgress(pageId: PageId, offsetInPageUnits: Long) {
        require(offsetInPageUnits >= 0L)
        dao.saveProgress(
            progress(pageId.episodeId, clock()).copy(
                pageKey = pageId.remoteKey,
                offsetInPageUnits = offsetInPageUnits,
            ),
        )
    }

    suspend fun readingPosition(episodeId: EpisodeId): ReadingPosition? {
        val seriesId = episodeId.seriesId
        val saved = dao.progress(seriesId.sourceId.value, seriesId.remoteKey) ?: return null
        if (saved.episodeKey != episodeId.remoteKey) return null
        return ReadingPosition(
            pageId = PageId(episodeId, saved.pageKey),
            offsetInPageUnits = saved.offsetInPageUnits,
        )
    }

    suspend fun addBookmark(pageId: PageId, offsetInPageUnits: Long) {
        require(offsetInPageUnits >= 0L)
        dao.saveBookmark(bookmarkEntity(pageId, offsetInPageUnits, clock()))
    }

    suspend fun removeBookmark(bookmark: SavedBookmark) {
        dao.deleteBookmark(bookmarkEntity(bookmark.pageId, bookmark.offsetInPageUnits, bookmark.createdAtEpochMillis))
    }

    private fun entry(
        id: SeriesId,
        title: String,
        thumbnailKey: String?,
        favorite: Boolean,
        now: Long,
    ) = LibraryEntryEntity(
        sourceKey = id.sourceId.value,
        seriesKey = id.remoteKey,
        title = title,
        thumbnailKey = thumbnailKey,
        favorite = favorite,
        updatedAtEpochMillis = now,
    )

    private fun progress(episodeId: EpisodeId, now: Long) = ReadingProgressEntity(
        sourceKey = episodeId.seriesId.sourceId.value,
        seriesKey = episodeId.seriesId.remoteKey,
        episodeKey = episodeId.remoteKey,
        pageKey = PageId.at(episodeId, 0).remoteKey,
        offsetInPageUnits = 0L,
        updatedAtEpochMillis = now,
    )

    private fun bookmarkEntity(pageId: PageId, offset: Long, createdAt: Long) = BookmarkEntity(
        sourceKey = pageId.episodeId.seriesId.sourceId.value,
        seriesKey = pageId.episodeId.seriesId.remoteKey,
        episodeKey = pageId.episodeId.remoteKey,
        pageKey = pageId.remoteKey,
        offsetInPageUnits = offset,
        createdAtEpochMillis = createdAt,
    )
}

internal fun assembleSnapshot(
    entries: List<LibraryEntryEntity>,
    progress: List<ReadingProgressEntity>,
    bookmarks: List<BookmarkEntity>,
    settings: ViewerSettings,
): UserLibrarySnapshot {
    val seriesById = entries.associateBy { it.sourceKey to it.seriesKey }
    val favorites = entries.filter(LibraryEntryEntity::favorite).map(::savedSeries)
    val recent = progress.map { item -> recentReading(item, seriesById[item.sourceKey to item.seriesKey]) }
    val savedBookmarks = bookmarks.map { item -> bookmark(item, seriesById[item.sourceKey to item.seriesKey]) }
    return UserLibrarySnapshot(recent, favorites, savedBookmarks, settings)
}

private fun savedSeries(entity: LibraryEntryEntity) = SavedSeries(
    id = SeriesId(SourceId(entity.sourceKey), entity.seriesKey),
    title = entity.title,
    thumbnailKey = entity.thumbnailKey,
    favorite = entity.favorite,
    updatedAtEpochMillis = entity.updatedAtEpochMillis,
)

private fun recentReading(entity: ReadingProgressEntity, library: LibraryEntryEntity?): RecentReading {
    val series = library?.let(::savedSeries) ?: SavedSeries(
        SeriesId(SourceId(entity.sourceKey), entity.seriesKey),
        entity.seriesKey,
        null,
        false,
        entity.updatedAtEpochMillis,
    )
    val episode = EpisodeId(series.id, entity.episodeKey)
    return RecentReading(
        series,
        episode,
        PageId(episode, entity.pageKey),
        entity.offsetInPageUnits,
        entity.updatedAtEpochMillis,
    )
}

private fun bookmark(entity: BookmarkEntity, library: LibraryEntryEntity?): SavedBookmark {
    val series = SeriesId(SourceId(entity.sourceKey), entity.seriesKey)
    val episode = EpisodeId(series, entity.episodeKey)
    return SavedBookmark(
        seriesTitle = library?.title ?: entity.seriesKey,
        pageId = PageId(episode, entity.pageKey),
        offsetInPageUnits = entity.offsetInPageUnits,
        createdAtEpochMillis = entity.createdAtEpochMillis,
    )
}
