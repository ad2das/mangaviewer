package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.RawPageCache
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PageFetchPriority

internal class PipelineRawPagePort(
    private val source: ContentSource,
    private val cache: RawPageCache,
    private val offline: OfflineEpisodeStore?,
) : RawPagePort {
    override suspend fun find(pageId: PageId): EncodedPageRef? =
        (offline?.find(pageId) ?: cache.find(pageId))?.toRef()

    override suspend fun fetch(
        pageId: PageId,
        priority: PageFetchPriority,
        responseStarted: () -> Unit,
    ): EncodedPageRef {
        val opened = source.openPage(pageId, validation = null, priority = priority)
        responseStarted()
        return try {
            cache.write(pageId, opened).toRef()
        } finally {
            opened.close()
        }
    }

    private fun CachedPage.toRef(): EncodedPageRef = EncodedPageRef(
        pageId,
        file.absolutePath,
        byteCount,
        sha256,
        dimensions,
    )
}
