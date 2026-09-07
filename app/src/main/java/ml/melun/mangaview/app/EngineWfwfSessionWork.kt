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
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import ml.melun.mangaview.source.wfwf.*

internal class EngineWfwfSessionWork(
    userAgent: String,
    private val origin: URI,
    private val transport: SourceTransport,
    storage: EngineStoragePort,
    private val positions: EnginePositionPort,
    private val parsingDispatcher: CoroutineDispatcher,
    private val loadLegacy: suspend (EpisodeId) -> ReadingPosition?,
    private val initialPosition: ReadingPosition?,
    observer: EpisodePlanObserver? = null,
) : EngineViewerWork {
    private val principal = "wfwf:public"
    private val planner = WfwfAccessPlanner(userAgent)
    private val catalog = WfwfEpisodeCatalogPlanner(userAgent)
    private val episodes = EngineEpisodeWork(principal, planner, transport, parsingDispatcher, observer = observer)
    private val pages = EnginePageWork(principal, planner, transport, storage) { _, _, _ ->
        error("WFWF returned an unsupported access prerequisite")
    }

    override fun position(episodeId: EpisodeId): WorkRequest<SessionPosition> {
        val override = initialPosition?.takeIf { it.pageId.episodeId == episodeId }
        return WorkRequest(WorkKey(principal, episodeId.toString(), "position", override?.toString() ?: "saved",
            SessionPosition::class.java), WorkDomain.STORAGE, WorkPriority.FOCUS, execute = {
            if (override != null) SessionPosition(null, override)
            else SessionPosition(positions.load(episodeId), loadLegacy(episodeId))
        })
    }

    override fun episode(episodeId: EpisodeId, priority: WorkPriority) = episodes.request(episodeId, origin, 0, priority)
    override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = pages.request(plan, pageId, priority)

    override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
        WorkKey(principal, episodeId.toString(), "catalog.navigation", origin.toString(), AdjacentEpisodes::class.java),
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
            val loaded = mutableListOf<WfwfEpisodeCatalogPage>()
            var next = 1
            var last = 1
            while (next <= last) {
                val number = next++
                val document = WorkRequest(WorkKey(principal, seriesId.toString(), "catalog.document.$number",
                    origin.toString(), SourceDocument::class.java), WorkDomain.BODY, parent.priority.value,
                    execute = { fetchCatalog(seriesId, number) })
                val parsed = parent.useDependency(document) { value ->
                    withContext(parsingDispatcher) { catalog.parse(seriesId, value) }
                }
                loaded += parsed
                last = maxOf(last, parsed.lastPage)
            }
            EngineEpisodeCatalog(seriesId, catalog.merge(loaded))
        },
    )

    private suspend fun fetchCatalog(id: SeriesId, page: Int): SourceDocument {
        val response = transport.execute(catalog.request(id, origin, page))
        val length = response.contentLength
        try {
            if (response.statusCode != 200) throw PageHttpException(response.statusCode)
            require(length == null || length <= 16 * 1024 * 1024L)
        } catch (failure: Throwable) { response.close(); throw failure }
        val bytes = response.readBytes(16 * 1024 * 1024)
        require(length == null || length == bytes.size.toLong())
        return SourceDocument(URI(response.finalUrl), bytes)
    }
}
