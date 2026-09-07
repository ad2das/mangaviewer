package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.source.AdjacentEpisodes

data class SessionPosition(val anchor: SourceAnchor?, val legacy: ReadingPosition? = null)

/** Request construction has no effects. Every operation runs through the application coordinator. */
interface EngineSessionWork {
    fun position(episodeId: EpisodeId): WorkRequest<SessionPosition>
    fun episode(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<EpisodeAccessPlan>
    fun navigation(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<AdjacentEpisodes>
    fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage>
}

/** Metadata only. A renderer acquires its own page work before opening any file descriptor. */
data class PageContentIdentity(
    val pageId: PageId,
    val contentRevision: String,
    val sha256: String,
    val dimensions: PageDimensions,
    val byteCount: Long,
)

data class EngineRuntimeSnapshot(
    val session: EngineSessionSnapshot,
    val plans: Map<EpisodeId, EpisodeAccessPlan>,
    val pages: Map<PageId, PageContentIdentity>,
)

data class SessionWorkOwnership(
    val active: Int,
    val ready: Int,
    val retiring: Int,
    val failed: Int,
)
