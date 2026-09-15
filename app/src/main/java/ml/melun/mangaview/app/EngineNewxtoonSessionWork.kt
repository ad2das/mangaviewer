package ml.melun.mangaview.app

import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineEpisodeWork
import ml.melun.mangaview.engine.content.EnginePageWork
import ml.melun.mangaview.engine.content.PageHttpException
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN
import ml.melun.mangaview.source.newxtoon.NewxtoonAccessPlanner
import ml.melun.mangaview.source.newxtoon.NewxtoonChapter
import ml.melun.mangaview.source.newxtoon.NewxtoonChapterPagination
import ml.melun.mangaview.source.newxtoon.NewxtoonHtmlParser
import ml.melun.mangaview.source.readBytes

internal class EngineNewxtoonSessionWork(
    userAgent: String,
    private val origin: URI,
    private val transport: SourceTransport,
    storage: EngineStoragePort,
    private val positions: EnginePositionPort,
    private val parsingDispatcher: CoroutineDispatcher,
    private val loadLegacy: suspend (EpisodeId) -> ReadingPosition?,
    private val initialPosition: ReadingPosition?,
    observer: EpisodePlanObserver? = null,
    private val initialAnchor: SourceAnchor? = null,
) : EngineViewerWork {
    private val principal = "newxtoon:public"
    private val planner = NewxtoonAccessPlanner(userAgent)
    private val parser = NewxtoonHtmlParser(DEFAULT_NEWXTOON_ORIGIN)
    private val episodes = EngineEpisodeWork(principal, planner, transport, parsingDispatcher, observer = observer)
    private val pages = EnginePageWork(principal, planner, transport, storage) { _, _, _ ->
        error("NEWXTOON returned an unsupported access prerequisite")
    }

    override fun position(episodeId: EpisodeId): WorkRequest<SessionPosition> {
        val override = initialPosition?.takeIf { it.pageId.episodeId == episodeId }
        val exact = initialAnchor?.takeIf { it.pageId.episodeId == episodeId }
        return WorkRequest(WorkKey(principal, episodeId.toString(), "position", exact?.toString() ?: override?.toString() ?: "saved",
            SessionPosition::class.java), WorkDomain.STORAGE, WorkPriority.FOCUS, execute = {
            if (exact != null) SessionPosition(exact)
            else if (override != null) SessionPosition(null, override)
            else SessionPosition(positions.load(episodeId), loadLegacy(episodeId))
        })
    }

    override fun episode(episodeId: EpisodeId, priority: WorkPriority) =
        episodes.request(episodeId, origin, 0, priority)

    override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) =
        pages.request(plan, pageId, priority)

    override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
        WorkKey(principal, episodeId.toString(), "catalog.navigation", "resolved", AdjacentEpisodes::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            val ordered = parent.dependency(episodes(episodeId.seriesId, parent.priority.value)).episodes
            val index = ordered.indexOfFirst { it.id == episodeId }
            check(index >= 0) { "Episode is missing from its independent catalog" }
            AdjacentEpisodes(ordered.getOrNull(index + 1)?.id, ordered.getOrNull(index - 1)?.id)
        },
    )

    override fun episodes(seriesId: SeriesId, priority: WorkPriority) = WorkRequest(
        WorkKey(principal, seriesId.toString(), "catalog.episodes", origin.toString(), EngineEpisodeCatalog::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            val document = WorkRequest(WorkKey(principal, seriesId.toString(), "catalog.document",
                origin.toString(), SourceDocument::class.java), WorkDomain.BODY, parent.priority.value,
                execute = { fetchSeries(seriesId) })
            val chapters = parent.useDependency(document) { value ->
                val html = value.openBody().use { it.readBytes().toString(Charsets.UTF_8) }
                val embedded = withContext(parsingDispatcher) { parser.chapters(html) }
                val pagination = withContext(parsingDispatcher) { parser.chapterPagination(html) }
                if (pagination == null) embedded else mergeChapterPages(pagination, embedded)
            }
            // Chapters arrive newest-first, so positional sequence numbers count down and the
            // first chapter ends up with the smallest number.
            EngineEpisodeCatalog(seriesId, chapters.mapIndexed { index, chapter ->
                SourceEpisode(EpisodeId(seriesId, chapter.id), chapter.title,
                    sequenceNumber = (chapters.size - index).toDouble())
            })
        },
    )

    /** The series document renders the first chapter page; the feed serves every later page. */
    private suspend fun mergeChapterPages(
        pagination: NewxtoonChapterPagination,
        embedded: List<NewxtoonChapter>,
    ): List<NewxtoonChapter> {
        val merged = LinkedHashMap<String, NewxtoonChapter>()
        embedded.forEach { merged.putIfAbsent(it.id, it) }
        var page = if (embedded.isEmpty()) 1 else pagination.nextPage
        val visited = mutableSetOf<Int>()
        while (page != null && visited.size < MAX_CHAPTER_PAGES && visited.add(page)) {
            val json = fetchChapterPage(pagination.url, page)
            val payload = withContext(parsingDispatcher) { parser.chapterPage(json) }
            payload.chapters.forEach { merged.putIfAbsent(it.id, it) }
            page = payload.nextPage
        }
        return merged.values.toList()
    }

    private suspend fun fetchChapterPage(feedUrl: String, page: Int): String {
        val separator = if (feedUrl.contains('?')) "&" else "?"
        val response = transport.execute(SourceRequest(
            url = origin.resolve(feedUrl + separator + "page=" + page).toString(),
            headers = planner.documentHeaders() + mapOf("Accept" to "application/json"),
            priority = PageFetchPriority.NORMAL,
        ))
        if (response.statusCode != 200) {
            response.close()
            throw PageHttpException(response.statusCode)
        }
        return response.readBytes(CHAPTER_FEED_MAX_BYTES).toString(Charsets.UTF_8)
    }

    private suspend fun fetchSeries(seriesId: SeriesId): SourceDocument {
        val response = transport.execute(SourceRequest(
            url = origin.resolve("/comics/${seriesId.remoteKey}").toString(),
            headers = planner.documentHeaders(),
            priority = PageFetchPriority.NORMAL,
        ))
        val length = response.contentLength
        try {
            if (response.statusCode != 200) throw PageHttpException(response.statusCode)
            require(length == null || length <= 16 * 1024 * 1024L)
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
        val bytes = response.readBytes(16 * 1024 * 1024)
        require(length == null || length == bytes.size.toLong())
        return SourceDocument(URI(response.finalUrl), bytes)
    }

    private companion object {
        const val CHAPTER_FEED_MAX_BYTES = 8 * 1024 * 1024
        const val MAX_CHAPTER_PAGES = 400
    }
}
