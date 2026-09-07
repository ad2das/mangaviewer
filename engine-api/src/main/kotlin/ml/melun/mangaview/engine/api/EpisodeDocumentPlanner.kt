package ml.melun.mangaview.engine.api

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.SourceRequest

/** Pure request construction/parsing. Execution, retries and authentication belong to the coordinator. */
interface EpisodeDocumentPlanner {
    val sourceId: SourceId
    fun documentRequest(episodeId: EpisodeId, origin: URI, priority: WorkPriority): SourceRequest
    fun parseEpisode(
        episodeId: EpisodeId,
        document: SourceDocument,
        authEpoch: Long,
        catalogAdjacency: AdjacentEpisodes? = null,
    ): EpisodeAccessPlan
    fun pageRequest(
        plan: EpisodeAccessPlan,
        pageId: PageId,
        candidateIndex: Int,
        priority: WorkPriority,
        validation: PageValidation? = null,
    ): SourceRequest
}
