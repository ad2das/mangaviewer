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
    /** The demand list last built for [reconcileKey]; a key hit re-reconciles this same instance. */
    private var builtDemands: List<SessionDemand<*>>? = null

    fun clear() {
        plannedSnapshot = null
        plannedPlan = null
        retainKey = null
        retainedDemands = emptyList()
        retainedBytes = 0L
        residentOrder = emptyList()
        reconcileKey = null
        builtDemands = null
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
     * Rebuilds the demands only when the merged demands, access plans, generation, or failed
     * read-ahead set changed. A key hit still reconciles the previously built list instance so the
     * work set can restart a failed tile whose retry backoff elapsed; only the rebuild is skipped.
     */
    fun reconcile(
        plan: EngineTilePlan,
        snapshot: EngineRuntimeSnapshot,
        failed: Set<EngineTileSpec>,
        reconcileWork: (List<SessionDemand<*>>) -> Unit,
        compute: () -> List<SessionDemand<*>>,
    ) {
        val cached = reconcileKey
        val built = builtDemands
        if (cached != null && built != null &&
            cached.matches(plan.demands, snapshot.plans, snapshot.session.generation, failed)
        ) {
            reconcileWork(built)
            return
        }
        val rebuilt = compute()
        reconcileKey = ReconcileKey(plan.demands, snapshot.plans, snapshot.session.generation,
            if (failed.isEmpty()) emptyList() else failed.toList())
        builtDemands = rebuilt
        reconcileWork(rebuilt)
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
        fun matches(
            demands: List<EngineTileDemand>,
            plans: Map<EpisodeId, EpisodeAccessPlan>,
            generation: Long,
            failed: Set<EngineTileSpec>,
        ): Boolean =
            this.demands == demands && this.plans === plans && this.generation == generation &&
                this.failed.size == failed.size && failed.containsAll(this.failed)
    }
}
