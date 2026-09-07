package ml.melun.mangaview.data.engine

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.EnginePageEntity
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.engine.api.StoredPage

internal fun StoredPage.entity(path: String, time: Long) = EnginePageEntity(
    PageCacheKey.of(pageId), contentRevision, pageId.episodeId.seriesId.sourceId.value,
    pageId.episodeId.seriesId.remoteKey, pageId.episodeId.remoteKey, pageId.remoteKey,
    path, byteCount, sha256, mediaType, dimensions.widthPx, dimensions.heightPx, time, time,
)

internal fun EnginePageEntity.stored(files: EnginePageFiles): StoredPage {
    val id = PageId(EpisodeId(SeriesId(SourceId(sourceKey), seriesKey), episodeKey), pageKey)
    require(cacheKey == PageCacheKey.of(id)) { "Stored page identity mismatch" }
    val page = StoredPage(id, contentRevision, files.resolve(relativePath, "pages"), byteCount,
        sha256, PageDimensions(widthPx, heightPx), mediaType)
    require(relativePath == files.destination(page)) { "Stored page path mismatch" }
    return page
}

internal fun EnginePageEntity.journal(id: String, stagingName: String) = EnginePublicationEntity(
    id, cacheKey, contentRevision, sourceKey, seriesKey, episodeKey, pageKey,
    "staging/$stagingName", relativePath, byteCount, sha256, mediaType, widthPx, heightPx, createdAtEpochMillis,
)

internal fun EnginePublicationEntity.entity() = EnginePageEntity(
    cacheKey, contentRevision, sourceKey, seriesKey, episodeKey, pageKey,
    destinationRelativePath, byteCount, sha256, mediaType, widthPx, heightPx,
    createdAtEpochMillis, createdAtEpochMillis,
)

internal fun StoredPage.sameBody(other: StoredPage): Boolean = copy(file = other.file) == other
