package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SessionUpdate

// Plan-window retention, provider re-delivery and failure bookkeeping for EngineSessionRuntime.
// They live at file level so the runtime class stays inside the architecture size gate; each one
// is bookkeeping over the caller's maps and sets and changes no scheduling policy.

// A long read can cross many documents in place; plans (and cached-plan pins, which hold the
// episode's file leases) for documents far behind the reading position stay only inside this window.
private const val RETAINED_PLAN_EPISODES = 8
// Distinct failed read-ahead pages beyond this many are forgotten oldest-first; a page still on the
// horizon is retried by its next demand.
private const val MAXIMUM_FAILED_READ_AHEAD_PAGES = 256

/** Applies the plan-window retention and reports the plan map the caller should publish. */
internal fun applyPlanWindow(
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    retainedCachedPlans: MutableMap<EpisodeId, CachedPlan>,
    state: EngineSessionSnapshot,
    targetEpisode: EpisodeId,
    pages: Map<PageId, PageContentIdentity>,
): Map<EpisodeId, EpisodeAccessPlan> {
    val dropped = planWindowToDrop(plans, state, targetEpisode, pages, RETAINED_PLAN_EPISODES)
    if (dropped.isEmpty()) return plans
    val retained = LinkedHashMap(plans)
    dropped.forEach(retained::remove)
    dropped.forEach(retainedCachedPlans::remove)
    return immutableMap(retained)
}

/**
 * Re-dispatches the held manifest of every episode the geometry still requires. A rejected
 * re-delivery falls back to the demand path, whose failure handling owns the retry.
 */
internal fun redeliverRetainedManifests(
    state: EngineSessionSnapshot,
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    redelivered: MutableSet<EpisodeId>,
    dispatch: (SessionEvent) -> SessionUpdate,
    onDelivered: (SessionUpdate) -> Unit,
) {
    if (state.requiredEpisodes.isEmpty()) {
        redelivered.clear()
        return
    }
    redelivered.retainAll(state.requiredEpisodes)
    state.requiredEpisodes.forEach { id ->
        val plan = plans[id] ?: return@forEach
        // One re-delivery per required bout: a document that stays required for another reason
        // must not re-dispatch the same manifest on every pass of the update loop.
        if (!redelivered.add(id)) return@forEach
        val update = try {
            dispatch(SessionEvent.ManifestResolved(state.generation, plan.manifest, plan.navigationKnown))
        } catch (failure: Throwable) {
            System.err.println("EngineWork redeliver-failed id=$id error=${failure::class.java.simpleName}: ${failure.message}")
            return@forEach
        }
        onDelivered(update)
    }
}

/**
 * Drops acceptance markers for a page whose document the geometry no longer holds, so a later
 * delivery of that document re-accepts the page's geometry instead of trusting a stale marker.
 */
internal fun forgetAcceptedPage(
    id: PageId,
    prepared: MutableSet<PageId>,
    pages: Map<PageId, PageContentIdentity>,
    publish: (Map<PageId, PageContentIdentity>) -> Unit,
) {
    prepared -= id
    if (pages.containsKey(id)) publish(withoutEntry(pages, id))
}

/** Marks a read-ahead page failed and forgets the oldest markers beyond the retention bound. */
internal fun markPageFailure(id: PageId, failed: MutableSet<PageId>, notify: () -> Unit) {
    failed += id
    while (failed.size > MAXIMUM_FAILED_READ_AHEAD_PAGES) {
        failed.remove(failed.first())
    }
    notify()
}

/**
 * A long read can cross many documents in place. A plan for a document far behind is never read
 * again (its page metadata was already dropped), and a retained cached plan additionally pins the
 * episode's work record and file leases, so keep the plan set inside a window around the reading
 * position instead of letting it grow with every episode crossed.
 */
private fun planWindowToDrop(
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    state: EngineSessionSnapshot,
    targetEpisode: EpisodeId,
    pages: Map<PageId, PageContentIdentity>,
    maximum: Int,
): List<EpisodeId> {
    if (plans.size <= maximum) return emptyList()
    val anchor = state.anchor?.pageId?.episodeId ?: targetEpisode
    val protectedEpisodes = mutableSetOf(anchor, targetEpisode)
    plans[anchor]?.manifest?.let { manifest ->
        manifest.previousEpisodeId?.let(protectedEpisodes::add)
        manifest.nextEpisodeId?.let(protectedEpisodes::add)
    }
    protectedEpisodes += pages.keys.mapTo(mutableSetOf()) { it.episodeId }
    protectedEpisodes += state.requiredEpisodes
    protectedEpisodes += state.requiredNavigation
    return planKeysToDrop(plans, protectedEpisodes, maximum)
}
