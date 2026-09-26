package ml.melun.mangaview.app

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EnginePageWork
import ml.melun.mangaview.engine.content.PageHttpException
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import ml.melun.mangaview.source.ntk.*

/** NTK document -> browser proof -> immutable page plan under the app's sole work coordinator. */
internal class EngineNtkSessionWork(
    private val userAgent: String,
    private val origin: URI,
    private val transport: SourceTransport,
    storage: EngineStoragePort,
    private val positions: EnginePositionPort,
    private val parsingDispatcher: CoroutineDispatcher,
    private val browser: NtkEngineBrowserClient,
    private val identity: NtkBrowserIdentity,
    private val loadLegacy: suspend (EpisodeId) -> ReadingPosition?,
    private val initialPosition: ReadingPosition?,
    private val observer: EpisodePlanObserver? = null,
    private val pageTransport: SourceTransport = transport,
    private val initialAnchor: SourceAnchor? = null,
    private val publishOrigin: (String) -> Unit = {},
    private val sessionStore: NtkNativeSessionStore? = null,
    private val documentCache: EpisodeDocumentDiskCache? = null,
    private val payloadCache: NtkManifestPayloadCache? = null,
) : EngineViewerWork {
    private val principal = "ntk:engine"
    private val planner = NtkAccessPlanner(userAgent, sessionCookies = { base ->
        sessionStore?.load(base) ?: emptyMap()
    })
    private val catalog = NtkEpisodeCatalogPlanner(userAgent)
    private val nativeManifest = NtkNativeManifestClient(pageTransport, userAgent, sessionStore, payloadCache)
    private val pages = EnginePageWork(principal, planner, NtkPageHeaderTransport(pageTransport), storage) { _, _, _ ->
        error("NTK page plan has an unfulfilled access prerequisite")
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

    override fun episode(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<EpisodeAccessPlan> = WorkRequest(
        WorkKey(principal, episodeId.toString(), "ntk.episode", origin.toString(), EpisodeAccessPlan::class.java),
        WorkDomain.CONTROL, priority, execute = { parent ->
            coroutineScope {
                android.util.Log.d("NtkEpisodes", "episode-enter at=" + System.nanoTime())
                // Preconnect both the document pool and the image pool for the known origin before
                // either request pays its own DNS/TCP/TLS setup on the first-image path.
                transport.warmConnections(listOf(origin.toString()), preferQuic = false)
                pageTransport.warmConnections(listOf(origin.toString()), preferQuic = true)
                pageTransport.warmConnections(listOf(origin.toString()), preferQuic = false)
                val startedAtMillis = android.os.SystemClock.elapsedRealtime()
                // The challenge and nv credential depend only on the known episode path, so they
                // run alongside the document fetch and are reused by the native manifest flight.
                val warm = async {
                    runCatching { nativeManifest.warm(origin, episodeId.remoteKey, identity) }.getOrNull()
                }
                // The native challenge/nv/HMAC flight returns the manifest without any WebView,
                // so no browser process starts for the first image. Only a refused native flight
                // binds the engine browser service, from inside the fallback capture.
                val cached = documentCache?.load(episodeId)
                if (cached != null) {
                    android.util.Log.d("NtkEpisodes", "document-cache-hit elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - startedAtMillis))
                    resolveEpisode(parent, episodeId, cached, startedAtMillis, warm)
                } else {
                    parent.useDependency(
                        racedDocumentRequest(episodeId, origin, parent.priority.value),
                    ) { source ->
                        android.util.Log.d("NtkEpisodes", "document-ready elapsedMs=" +
                            (android.os.SystemClock.elapsedRealtime() - startedAtMillis))
                        val plan = resolveEpisode(parent, episodeId, source, startedAtMillis, warm)
                        documentCache?.save(episodeId, source)
                        plan
                    }
                }
            }
        },
    )

    private fun racedDocumentRequest(
        episodeId: EpisodeId,
        origin: URI,
        priority: WorkPriority,
    ): WorkRequest<SourceDocument> = WorkRequest(
        WorkKey(principal, episodeId.toString(), "ntk.raced-document", origin.toString(), SourceDocument::class.java),
        WorkDomain.BODY, priority, execute = { child ->
            fetchDocumentFromFastestMirror(episodeId, origin, child.priority.value)
        },
    )

    /**
     * Fetches the episode document from every stable mirror at once and keeps the first usable
     * response. The provider stalls or challenges bare first requests per connection, so racing
     * the mirrors buys the fastest path instead of paying one mirror's stall end to end.
     */
    private suspend fun fetchDocumentFromFastestMirror(
        episodeId: EpisodeId,
        origin: URI,
        priority: WorkPriority,
    ): SourceDocument = coroutineScope {
        val mirrors = (listOf(origin.toString()) + NtkOriginResolver.ENTRY_POINTS)
            .distinct().take(DOCUMENT_MIRROR_LIMIT)
        // One slot per racing mirror keeps the channel bounded while leaving every sender
        // unsuspended, so documents are consumed strictly in arrival order.
        val outcomes = Channel<Result<SourceDocument>>(mirrors.size)
        val jobs = mirrors.map { mirror ->
            launch {
                outcomes.send(runCatching { fetchMirrorDocument(episodeId, URI(mirror), priority) })
            }
        }
        var lastFailure: Throwable? = null
        try {
            repeat(jobs.size) {
                val outcome = outcomes.receive()
                val document = outcome.getOrNull()
                if (document != null) return@coroutineScope document
                lastFailure = outcome.exceptionOrNull()
            }
            throw IOException("Every NTK mirror failed to serve the episode document", lastFailure)
        } finally {
            jobs.forEach { job -> job.cancel() }
            outcomes.cancel()
        }
    }

    private suspend fun fetchMirrorDocument(
        episodeId: EpisodeId,
        mirror: URI,
        priority: WorkPriority,
    ): SourceDocument {
        val startedAt = System.nanoTime()
        val response = pageTransport.execute(planner.documentRequest(episodeId, mirror, priority))
        android.util.Log.d("NtkDoc", "mirror=$mirror headersMs=${(System.nanoTime() - startedAt) / 1_000_000}")
        try {
            if (response.statusCode != 200) throw PageHttpException(response.statusCode)
            val length = response.contentLength
            val bytes = response.readBytes(DOCUMENT_LIMIT)
            require(length == null || length == bytes.size.toLong()) { "NTK document length mismatch" }
            return SourceDocument(URI(response.finalUrl), bytes, response.headers)
        } finally {
            response.close()
        }
    }

    private suspend fun resolveEpisode(
        parent: WorkContext,
        episodeId: EpisodeId,
        source: SourceDocument,
        startedAtMillis: Long,
        warm: Deferred<NtkNativeManifestClient.Warm?>,
    ): EpisodeAccessPlan {
        fun elapsed() = android.os.SystemClock.elapsedRealtime() - startedAtMillis
        val parsed = withContext(parsingDispatcher) { planner.parseDocument(episodeId, source, 0) }
        android.util.Log.d("NtkEpisodes", "parsed elapsedMs=${elapsed()} descriptor=${parsed.descriptor != null}")
        // Pin every follow-up flight to the document's own mirror so the manifest proof and the
        // document identity agree even when the probe walk settled on a different entry point.
        val documentOrigin = URI(parsed.browserDocument.origin)
        publishOrigin(parsed.browserDocument.origin)
        val completed = if (parsed.descriptor == null) withContext(parsingDispatcher) { planner.complete(parsed) }
        else parent.useDependency(WorkRequest(
            WorkKey(principal, episodeId.toString(), "ntk.browser", source.replaySha256, NtkEngineAuthorization::class.java),
            WorkDomain.BROWSER, parent.priority.value, execute = {
                try {
                    // The provider's own challenge/nv/HMAC flight returns the identical manifest
                    // without the isolated WebView; the browser capture stays the fallback.
                    val prewarmed = runCatching { warm.await() }.getOrNull()
                    nativeManifest.capture(documentOrigin, parsed, identity, prewarmed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    android.util.Log.w("EngineNtkNative", "Native NTK manifest unavailable; using browser capture", failure)
                    browser.capture(parsed)
                }
            },
        )) { proof ->
            android.util.Log.d("NtkEpisodes", "proof elapsedMs=${elapsed()}")
            withContext(parsingDispatcher) { planner.completeAuthorized(parsed, proof) }
        }
        android.util.Log.d("NtkEpisodes", "plan elapsedMs=${elapsed()} pages=${completed.pages.size}")
        require(completed.manifest.id == episodeId && completed.documentSha256 == source.sha256 &&
            completed.finalDocumentUrl == source.finalUrl)
        // Reuse the initialized Chromium HTTP/2 pool for the provider-verified CDN addresses.
        pageTransport.warmConnections(completed.pages.flatMap { it.candidates }.map(URI::toString), preferQuic = false)
        observer?.observed(episodeId, source, completed)
        android.util.Log.d("NtkEpisodes", "resolved elapsedMs=${elapsed()} at=${System.nanoTime()}")
        return completed
    }

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
            try {
                val bytes = response.readBytes(DOCUMENT_LIMIT)
                require(length == null || length == bytes.size.toLong())
                SourceDocument(URI(response.finalUrl), bytes, response.headers)
            } finally {
                response.close()
            }
        },
    )

    private class CatalogPages(val pages: List<NtkEpisodeCatalogPage>)
    private companion object {
        const val DOCUMENT_LIMIT = 16 * 1_024 * 1_024
        const val DOCUMENT_MIRROR_LIMIT = 3
    }
}
