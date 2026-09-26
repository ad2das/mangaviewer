package ml.melun.mangaview.app

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * Optional disk boundary for the episode document, shared across viewer sessions: a document
     * the neighbour prefetch already fetched is reused instead of paying the 386KB round trip.
     */
    private val documentStore: EpisodeDocumentStore? = null,
) : EngineViewerWork {
    private val principal = "newxtoon:public"
    private val planner = NewxtoonAccessPlanner(userAgent)
    private val parser = NewxtoonHtmlParser(DEFAULT_NEWXTOON_ORIGIN)
    private val episodes = EngineEpisodeWork(principal, planner, transport, parsingDispatcher, observer = observer,
        onPlan = ::warmArtwork, documentStore = documentStore)
    private val pages = EnginePageWork(principal, planner, transport, storage) { _, _, _ ->
        error("NEWXTOON returned an unsupported access prerequisite")
    }

    /** Episodes whose artwork legs were already opened; one warm per document is enough. */
    private val warmedArtwork = HashSet<EpisodeId>()

    /**
     * The plan is the first place the chapter's artwork URLs exist, so the legs for its opening
     * pages are opened here: the first body the reader waits for then skips DNS, TCP and TLS. The
     * warm is a hint on the transport, never a body fetch, so it takes no transfer permit and
     * cannot displace a visible page. Only the head is offered — the transport opens one leg per
     * distinct artwork host, and a whole chapter would repeat the same few hosts.
     */
    private fun warmArtwork(plan: EpisodeAccessPlan) {
        if (!synchronized(warmedArtwork) { warmedArtwork.add(plan.manifest.id) }) return
        val head = plan.pages.take(ARTWORK_WARM_PAGES).flatMap { it.candidates }
        if (head.isEmpty()) return
        val started = System.nanoTime()
        transport.warmConnections(head.map(URI::toString), preferQuic = false)
        android.util.Log.i("NtkArtworkWarm", "episode=${plan.manifest.id} pages=${plan.pages.size} " +
            "urls=${head.size} ms=${(System.nanoTime() - started) / 1_000_000}")
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
                if (pagination == null) embedded else mergeChapterPages(pagination, embedded, html)
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
        seriesHtml: String,
    ): List<NewxtoonChapter> {
        val merged = LinkedHashMap<String, NewxtoonChapter>()
        embedded.forEach { merged.putIfAbsent(it.id, it) }
        val firstPage = if (embedded.isEmpty()) 1 else pagination.nextPage
        // The header advertises the total chapter count and the feed page size, so the whole feed
        // range is known and can be fetched in parallel windows instead of one round trip per page.
        val pageSize = withContext(parsingDispatcher) { parser.chapterPageSize(seriesHtml) }
        val advertised = withContext(parsingDispatcher) { parser.chapterTotal(seriesHtml) }
        val lastPage = if (pageSize != null && advertised != null && advertised >= merged.size) {
            ((advertised + pageSize - 1) / pageSize).coerceAtLeast(1).takeIf { it <= MAX_CHAPTER_PAGES }
        } else null
        if (lastPage != null && advertised != null) {
            try {
                for (window in (2..lastPage).chunked(CHAPTER_PAGE_WINDOW)) {
                    val payloads = coroutineScope {
                        window.map { page -> async { fetchChapterPage(pagination.url, page) } }.awaitAll()
                    }
                    payloads.forEach { json ->
                        withContext(parsingDispatcher) { parser.chapterPage(json) }
                            .chapters.forEach { merged.putIfAbsent(it.id, it) }
                    }
                    if (merged.size >= advertised) return merged.values.toList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // A refused window must not lose the list; the gentler discovery walk below
                // remains the authority for whatever the advertised range did not cover.
            }
        }
        var page = firstPage
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
        try {
            return response.readBytes(CHAPTER_FEED_MAX_BYTES).toString(Charsets.UTF_8)
        } finally {
            response.close()
        }
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
        try {
            val bytes = response.readBytes(16 * 1024 * 1024)
            require(length == null || length == bytes.size.toLong())
            return SourceDocument(URI(response.finalUrl), bytes)
        } finally {
            response.close()
        }
    }

    private companion object {
        const val CHAPTER_FEED_MAX_BYTES = 8 * 1024 * 1024
        const val MAX_CHAPTER_PAGES = 400
        // The advertised range is fetched in small parallel windows; a wide burst invites the
        // provider's throttle and one refused page must not cost the list.
        const val CHAPTER_PAGE_WINDOW = 6
        // Opening pages of a document whose legs are worth opening before the reader arrives.
        const val ARTWORK_WARM_PAGES = 6
    }
}
