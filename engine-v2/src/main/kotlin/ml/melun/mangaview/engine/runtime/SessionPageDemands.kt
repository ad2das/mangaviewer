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
    private val failedReadAhead: (PageId) -> Unit,
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
        return SessionDemand(source.page(plan, id, priority), onFailure =
            if (priority == WorkPriority.NEXT_IMAGE || priority == WorkPriority.NEXT_EPISODE)
                ({ _: Throwable -> failedReadAhead(id) }) else null,
            onMetadata = { value -> metadata(generation, id, plan, value) },
        ) { page -> accept(generation, id, plan, page) }.also {
            entries[id] = Cached(generation, plan, priority, it)
        }
    }
}
