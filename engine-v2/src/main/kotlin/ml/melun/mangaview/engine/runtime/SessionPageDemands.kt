package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkMetadata
import ml.melun.mangaview.engine.api.WorkPriority

/** Reuses current requests and their hashed identities; owns no file or work subscription. */
internal class SessionPageDemands(
    private val source: EngineSessionWork,
    private val metadata: (Long, PageId, EpisodeAccessPlan, WorkMetadata) -> Unit,
    private val accept: (Long, PageId, EpisodeAccessPlan, StoredPage) -> Unit,
    private val failed: (PageId, Throwable) -> Unit,
) {
    private class Cached(val generation: Long, val plan: EpisodeAccessPlan,
        val priority: WorkPriority, val demand: SessionDemand<StoredPage>)
    private val entries = linkedMapOf<PageId, Cached>()

    fun clear() = entries.clear()
    fun retain(ids: Set<PageId>) { entries.keys.retainAll(ids) }

    fun get(generation: Long, id: PageId, plan: EpisodeAccessPlan, priority: WorkPriority): SessionDemand<StoredPage> {
        entries[id]?.let { cached ->
            if (cached.generation == generation && cached.plan === plan && cached.priority == priority) return cached.demand
        }
        // A page failure never fails the session, whatever its priority: the page is marked failed
        // (which parks speculative read-ahead) and the work set retries it on its own backoff while
        // the geometry still needs it. A visible page whose candidates are all transiently
        // unavailable is a provider outage, not a session error.
        return SessionDemand(source.page(plan, id, priority),
            onFailure = { cause: Throwable -> failed(id, cause) },
            onMetadata = { value -> metadata(generation, id, plan, value) },
        ) { page -> accept(generation, id, plan, page) }.also {
            entries[id] = Cached(generation, plan, priority, it)
        }
    }
}
