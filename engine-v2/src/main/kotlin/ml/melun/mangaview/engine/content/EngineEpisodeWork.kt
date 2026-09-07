package ml.melun.mangaview.engine.content

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.EpisodePlanObserver
import ml.melun.mangaview.engine.api.EpisodeDocumentPlanner
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes

/** Episode documents are shared immutable dependencies; provider parsing runs off the caller thread. */
class EngineEpisodeWork(
    private val principal: String,
    private val planner: EpisodeDocumentPlanner,
    private val transport: SourceTransport,
    private val parsingDispatcher: CoroutineDispatcher,
    private val maxDocumentBytes: Int = 16 * 1_024 * 1_024,
    private val observer: EpisodePlanObserver? = null,
) {
    init { require(principal.isNotBlank() && maxDocumentBytes > 0) }

    fun request(
        episodeId: EpisodeId,
        origin: URI,
        authEpoch: Long,
        priority: WorkPriority,
        catalogAdjacency: AdjacentEpisodes? = null,
    ): WorkRequest<EpisodeAccessPlan> {
        require(episodeId.seriesId.sourceId == planner.sourceId && authEpoch >= 0)
        val planKey = key(episodeId, origin, authEpoch, "episode", EpisodeAccessPlan::class.java,
            catalogAdjacency)
        return WorkRequest(planKey, WorkDomain.CONTROL, priority, authEpoch = authEpoch, execute = { parent ->
            parent.useDependency(documentRequest(episodeId, origin, authEpoch, parent.priority.value)) { document ->
                withContext(parsingDispatcher) {
                    planner.parseEpisode(episodeId, document, authEpoch, catalogAdjacency).also { plan ->
                        require(plan.manifest.id == episodeId && plan.authEpoch == authEpoch)
                        require(plan.documentSha256 == document.sha256 && plan.finalDocumentUrl == document.finalUrl)
                        observer?.observed(episodeId, document, plan)
                    }
                }
            }
        })
    }

    /** Shared document boundary for sources whose plan also depends on browser authorization. */
    fun documentRequest(episodeId: EpisodeId, origin: URI, authEpoch: Long, priority: WorkPriority): WorkRequest<SourceDocument> {
        require(episodeId.seriesId.sourceId == planner.sourceId && authEpoch >= 0)
        return WorkRequest(key(episodeId, origin, authEpoch, "document", SourceDocument::class.java),
            WorkDomain.BODY, priority, authEpoch = authEpoch, execute = { child ->
                fetch(episodeId, origin, child.priority.value)
            })
    }

    private suspend fun fetch(episodeId: EpisodeId, origin: URI, priority: WorkPriority): SourceDocument {
        val response = transport.execute(planner.documentRequest(episodeId, origin, priority))
        val length = response.contentLength
        try {
            if (response.statusCode != 200) throw PageHttpException(response.statusCode)
            require(length == null || length <= maxDocumentBytes.toLong()) {
                "Episode document exceeds its byte limit"
            }
        } catch (failure: Throwable) {
            try { response.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
        // readBytes owns closure, including failures. SourceDocument receives complete immutable bytes.
        val bytes = response.readBytes(maxDocumentBytes)
        require(length == null || length == bytes.size.toLong()) {
            "Episode document body length mismatch"
        }
        return SourceDocument(URI(response.finalUrl), bytes, response.headers)
    }

    private fun <T : Any> key(
        episode: EpisodeId,
        origin: URI,
        epoch: Long,
        operation: String,
        type: Class<T>,
        adjacency: AdjacentEpisodes? = null,
    ): WorkKey<T> {
        val fields = listOf(episode.seriesId.sourceId.value, episode.seriesId.remoteKey, episode.remoteKey,
            origin.toString(), adjacency?.toString() ?: "unknown")
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        val resource = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return WorkKey(principal, resource, "content.$operation", epoch.toString(), type)
    }
}
