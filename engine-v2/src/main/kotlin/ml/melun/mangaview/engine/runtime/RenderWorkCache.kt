package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageContentIdentity

/**
 * Value-keyed memoization for the render runtime's owner-thread work.
 *
 * One snapshot always plans identically, so the plan is cached by snapshot identity. The retain
 * merge and the work-set reconcile change only when their inputs change — the merged demand list,
 * the verified page identities, the viewport reading mode, the resident pixel order, the access
 * plans, or the failed read-ahead set — so a segment that merely moves the viewport inside the
 * same tile bands reuses both results instead of rescanning residents and rebuilding the registry.
 * The planner's placements stay per-segment and are never cached here.
 */
internal class RenderWorkCache {
    private var plannedSnapshot: EngineRuntimeSnapshot? = null
    private var plannedPlan: EngineTilePlan? = null
    private var retainKey: RetainKey? = null
    private var retainedDemands: List<EngineTileDemand> = emptyList()
    private var retainedBytes = 0L
    private var residentOrder: List<EngineTileSpec> = emptyList()
    private var reconcileKey: ReconcileKey? = null

    fun clear() {
        plannedSnapshot = null
        plannedPlan = null
        retainKey = null
        retainedDemands = emptyList()
        retainedBytes = 0L
        residentOrder = emptyList()
        reconcileKey = null
    }

    /** The same snapshot always plans identically; a drain that repeats for it reuses the plan. */
    fun planFor(snapshot: EngineRuntimeSnapshot, compute: () -> EngineTilePlan): EngineTilePlan {
        val cached = plannedPlan
        if (cached != null && plannedSnapshot === snapshot) return cached
        val plan = compute()
        plannedSnapshot = snapshot
        plannedPlan = plan
        return plan
    }

    /**
     * Returns the merged retain plan for [plan]: the cached demand merge while the visible demands,
     * verified pages, reading mode, enabled state, and resident order are unchanged, otherwise
     * [compute], which also re-admits the merged demand set into the runtime's residency maps.
     */
    fun retainedPlan(
        plan: EngineTilePlan,
        snapshot: EngineRuntimeSnapshot,
        enabled: Boolean,
        resident: Collection<EngineTileSpec>,
        compute: () -> EngineTilePlan,
    ): EngineTilePlan {
        val key = RetainKey(
            plan.demands, plan.plannedTextureBytes, snapshot.pages,
            snapshot.session.viewport.widthPx, snapshot.session.splitMode, enabled,
            if (matchesResident(resident)) residentOrder else ArrayList(resident),
        )
        val cached = retainKey
        if (cached != null && cached.matches(key)) {
            return plan.copy(demands = retainedDemands, plannedTextureBytes = retainedBytes)
        }
        val merged = compute()
        retainKey = key
        residentOrder = key.resident
        retainedDemands = merged.demands
        retainedBytes = merged.plannedTextureBytes
        return merged
    }

    /**
     * Runs the demand rebuild and the work-set reconcile only when the merged demands, access
     * plans, generation, or failed read-ahead set changed. A failed read-ahead always recomputes:
     * its retry backoff lives inside the work set, and only a reconcile pass restarts it.
     */
    fun reconcile(
        plan: EngineTilePlan,
        snapshot: EngineRuntimeSnapshot,
        failed: Set<EngineTileSpec>,
        reconcileWork: (List<SessionDemand<*>>) -> Unit,
        compute: () -> List<SessionDemand<*>>,
    ) {
        val key = ReconcileKey(plan.demands, snapshot.plans, snapshot.session.generation, failed.toList())
        val cached = reconcileKey
        if (failed.isEmpty() && cached != null && cached.matches(key)) return
        val built = compute()
        reconcileKey = key
        reconcileWork(built)
    }

    private fun matchesResident(current: Collection<EngineTileSpec>): Boolean {
        val stored = residentOrder
        if (stored.size != current.size) return false
        val iterator = current.iterator()
        for (tile in stored) if (tile !== iterator.next()) return false
        return true
    }

    private class RetainKey(
        val demands: List<EngineTileDemand>,
        val bytes: Long,
        val pages: Map<PageId, PageContentIdentity>,
        val viewportWidthPx: Int,
        val splitMode: Boolean,
        val enabled: Boolean,
        val resident: List<EngineTileSpec>,
    ) {
        fun matches(other: RetainKey): Boolean =
            demands == other.demands && bytes == other.bytes && pages === other.pages &&
                viewportWidthPx == other.viewportWidthPx && splitMode == other.splitMode &&
                enabled == other.enabled && resident == other.resident
    }

    private class ReconcileKey(
        val demands: List<EngineTileDemand>,
        val plans: Map<EpisodeId, EpisodeAccessPlan>,
        val generation: Long,
        val failed: List<EngineTileSpec>,
    ) {
        fun matches(other: ReconcileKey): Boolean =
            demands == other.demands && plans === other.plans && generation == other.generation &&
                failed == other.failed
    }
}
