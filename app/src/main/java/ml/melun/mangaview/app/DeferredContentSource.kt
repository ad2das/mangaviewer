package ml.melun.mangaview.app

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries

/** Keeps Android UI creation synchronous while an expensive provider runtime starts off-thread. */
internal class DeferredContentSource(
    override val id: SourceId,
    scope: CoroutineScope,
    start: CoroutineStart = CoroutineStart.LAZY,
    initialize: suspend () -> DeferredSourceResource,
) : ContentSource, Closeable {
    private val publicationLock = Any()
    private var published: DeferredSourceResource? = null
    private var closed = false
    private val delegate: Deferred<ContentSource> = scope.async(start = start) {
        val candidate = initialize()
        try {
            coroutineContext.ensureActive()
            require(candidate.source.id == id) { "Deferred source returned a different source id" }
            val accepted = synchronized(publicationLock) {
                if (closed) false else {
                    published = candidate
                    true
                }
            }
            if (!accepted) throw CancellationException("Deferred content source is closed")
            candidate.source
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    /** Starts provider initialization without waiting for it on the caller thread. */
    fun start(): Boolean = delegate.start()

    override fun close() {
        val resource = synchronized(publicationLock) {
            if (closed) return
            closed = true
            published.also { published = null }
        }
        delegate.cancel(CancellationException("Deferred content source is closed"))
        resource?.close()
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
        source().search(query, cursor)

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> =
        source().episodes(seriesId, cursor)

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest =
        source().manifest(episodeId)

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes =
        source().adjacent(episodeId)

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) =
        source().prepare(episodeId, intent)

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
        source().openPage(pageId, validation)

    private suspend fun source(): ContentSource = delegate.await()
}

internal class DeferredSourceResource(
    val source: ContentSource,
    private val closeResource: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeResource()
    }
}
