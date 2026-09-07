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
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import ml.melun.mangaview.source.ntk.*

/** NTK document -> browser proof -> immutable page plan under the app's sole work coordinator. */
internal class EngineNtkSessionWork(
    userAgent: String,
    private val origin: URI,
    private val transport: SourceTransport,
    storage: EngineStoragePort,
    private val positions: EnginePositionPort,
    private val parsingDispatcher: CoroutineDispatcher,
    private val browser: NtkEngineBrowserClient,
    private val loadLegacy: suspend (EpisodeId) -> ReadingPosition?,
    private val initialPosition: ReadingPosition?,
    private val observer: EpisodePlanObserver? = null,
    pageTransport: SourceTransport = transport,
) : EngineViewerWork {
    private val principal = "ntk:engine"
    private val planner = NtkAccessPlanner(userAgent)
    private val catalog = NtkEpisodeCatalogPlanner(userAgent)
    private val documents = EngineEpisodeWork(principal, planner, transport, parsingDispatcher)
    private val pages = EnginePageWork(principal, planner, pageTransport, storage) { _, _, _ ->
        error("NTK page plan has an unfulfilled access prerequisite")
    }

    override fun position(episodeId: EpisodeId): WorkRequest<SessionPosition> {
        val override = initialPosition?.takeIf { it.pageId.episodeId == episodeId }
        return WorkRequest(WorkKey(principal, episodeId.toString(), "position", override?.toString() ?: "saved",
            SessionPosition::class.java), WorkDomain.STORAGE, WorkPriority.FOCUS, execute = {
            if (override != null) SessionPosition(null, override)
            else SessionPosition(positions.load(episodeId), loadLegacy(episodeId))
        })
    }

    override fun episode(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<EpisodeAccessPlan> = WorkRequest(
        WorkKey(principal, episodeId.toString(), "ntk.episode", origin.toString(), EpisodeAccessPlan::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            parent.useDependency(documents.documentRequest(episodeId, origin, 0, parent.priority.value)) { source ->
                val parsed = withContext(parsingDispatcher) { planner.parseDocument(episodeId, source, 0) }
                val completed = if (parsed.descriptor == null) withContext(parsingDispatcher) { planner.complete(parsed) }
                else parent.useDependency(WorkRequest(
                    WorkKey(principal, episodeId.toString(), "ntk.browser", source.replaySha256, NtkEngineAuthorization::class.java),
                    WorkDomain.BROWSER, parent.priority.value, execute = { browser.capture(parsed) },
                )) { proof -> withContext(parsingDispatcher) { planner.completeAuthorized(parsed, proof) } }
                require(completed.manifest.id == episodeId && completed.documentSha256 == source.sha256 &&
                    completed.finalDocumentUrl == source.finalUrl)
                observer?.observed(episodeId, source, completed)
                completed
            }
        },
    )

    override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = pages.request(plan, pageId, priority)

    override fun navigation(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<AdjacentEpisodes> = WorkRequest(
        WorkKey(principal, episodeId.toString(), "catalog.navigation", origin.toString(), AdjacentEpisodes::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            val ordered = parent.dependency(episodes(episodeId.seriesId, parent.priority.value)).episodes
            val index = ordered.indexOfFirst { it.id == episodeId }
            check(index >= 0) { "NTK episode is missing from its independent catalog" }
            AdjacentEpisodes(ordered.getOrNull(index + 1)?.id, ordered.getOrNull(index - 1)?.id)
        },
    )

    override fun episodes(seriesId: SeriesId, priority: WorkPriority) = WorkRequest(
        WorkKey(principal, seriesId.toString(), "catalog.episodes", origin.toString(), EngineEpisodeCatalog::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            val api = try {
                parent.useDependency(catalogDocument(seriesId, catalog.apiRequest(seriesId, origin), parent.priority.value)) {
                    withContext(parsingDispatcher) { catalog.parseApi(seriesId, it) }
                        ?.let { CatalogPages(listOf(it)) } ?: CatalogPages(emptyList())
                }
            } catch (failure: PageHttpException) {
                if (failure.statusCode !in setOf(404, 405, 410)) throw failure
                // The series document is the provider's alternate catalog, not a substituted work.
                android.util.Log.i("EngineNtkCatalog", "episode API unavailable status=${failure.statusCode}; reading series document")
                CatalogPages(emptyList())
            }
            val loaded = api.pages.toMutableList()
            if (loaded.isEmpty()) {
                var next = 1
                var last = 1
                while (next <= last) {
                    val request = catalog.documentRequest(seriesId, origin, next++)
                    val page = parent.useDependency(catalogDocument(seriesId, request, parent.priority.value)) {
                        withContext(parsingDispatcher) { catalog.parseDocument(seriesId, it) }
                    }
                    loaded += page
                    last = maxOf(last, page.lastPage)
                }
            }
            EngineEpisodeCatalog(seriesId, catalog.merge(loaded))
        },
    )

    private fun catalogDocument(seriesId: SeriesId, request: SourceRequest, priority: WorkPriority) = WorkRequest(
        WorkKey(principal, seriesId.toString(), "catalog.document", request.url, SourceDocument::class.java),
        WorkDomain.BODY, priority, execute = {
            val response = transport.execute(request)
            val length = response.contentLength
            try {
                if (response.statusCode != 200) throw PageHttpException(response.statusCode)
                require(length == null || length <= DOCUMENT_LIMIT)
            } catch (failure: Throwable) {
                try { response.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
            val bytes = response.readBytes(DOCUMENT_LIMIT)
            require(length == null || length == bytes.size.toLong())
            SourceDocument(URI(response.finalUrl), bytes, response.headers)
        },
    )

    private class CatalogPages(val pages: List<NtkEpisodeCatalogPage>)
    private companion object { const val DOCUMENT_LIMIT = 16 * 1_024 * 1_024 }
}
