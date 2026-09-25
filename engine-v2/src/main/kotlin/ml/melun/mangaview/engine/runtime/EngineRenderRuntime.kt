package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineDrawQuad
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.FrameWorkObserver
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.SessionWorkOwnership
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.content.EngineTileWork

data class EngineRenderRuntimeDiagnosticSnapshot(
    val session: ml.melun.mangaview.engine.api.EngineSessionSnapshot?,
    val enabled: Boolean,
    val completeGeometry: Boolean,
    val completeCoverage: Boolean,
    val plannedVisibleTiles: Set<EngineTileSpec>,
    val residentTextureTiles: Set<EngineTileSpec>,
    val work: SessionWorkOwnership,
)

/** Owns this renderer's subscriptions; scene replacement precedes retirement of its old textures. */
class EngineRenderRuntime(
    private val scope: CoroutineScope,
    coordinator: WorkCoordinatorPort,
    private val planner: EngineTilePlanner,
    private val tiles: EngineTileWork,
    private val uploader: EngineTextureUploader,
    private val pageRequest: (PageId, WorkPriority) -> WorkRequest<StoredPage>,
    private val submitScene: (EngineDrawScene) -> Unit,
    /** Removes native references without swapping a new buffer, including error exits. */
    private val clearScene: suspend () -> Unit,
    reportFailure: (WorkKey<*>, Throwable) -> Unit,
    private val waitForCompleteViewport: Boolean = false,
    private val reportSceneFailure: (Throwable) -> Unit = { throw it },
    private val reportViewportReady: (ml.melun.mangaview.engine.api.EngineSessionSnapshot) -> Unit = {},
    /** Trace-only scheduling provenance; never affects scene content, order, or timing. */
    private val frameWorkObserver: FrameWorkObserver? = null,
    /** Optional owner-thread refresh port; null keeps the legacy immediate drain. */
    private val refreshScheduler: EngineRefreshScheduler? = null,
    /** Optional owner-thread section timing; the default records nothing. */
    private val tracer: EngineWorkTracer = NoopEngineWorkTracer,
    /** Optional per-tile demand/residency timing; the default records nothing. */
    private val tileTimings: EngineTileTimingObserver = NoopEngineTileTimingObserver,
    /** Optional dispatch for a demand's await/accept coroutine; null keeps the scope's. */
    private val demandDispatcher: CoroutineDispatcher? = null,
) {
    private val owner = Thread.currentThread()
    private val work = SessionWorkSet(scope, coordinator, reportFailure, demandDispatcher)
    private val textures = linkedMapOf<EngineTileSpec, EngineTexture>()
    private val tileDemands = linkedMapOf<EngineTileSpec, CachedTileDemand>()
    private val failedReadAhead = linkedSetOf<EngineTileSpec>()
    private val closeDone = CompletableDeferred<Unit>()
    private var current: EngineRuntimeSnapshot? = null
    private var epoch = uploader.rendererEpoch
    private var enabled = true
    private var closed = false
    private var processing = false
    private var dirty = false
    private var displayed: EngineDrawScene? = null
    private var hasSubmittedScene = false
    private var clearingScene = false
    private var sceneClearJob: Job? = null
    private var sceneClearFailed = false
    private var scheduled = false
    /** True while a real drag or fling owns the display slots; see [refreshWorkResult]. */
    private var interactionActive = false
    /** Snapshot whose plan is already in [plannedPlan]; the planner is a pure function of it. */
    private var plannedSnapshot: EngineRuntimeSnapshot? = null
    private var plannedPlan: EngineTilePlan? = null
    /** Plan whose demand list is already in [plannedDemands]. */
    private var demandedPlan: EngineTilePlan? = null
    private var demandedFailed: Int = -1
    private var plannedDemands: List<SessionDemand<*>> = emptyList()

    /**
     * One snapshot always plans identically, so a drain that runs again for the same snapshot (a
     * work result set [dirty] mid-drain, or the queued delivery follows an inline one) reuses the
     * plan instead of re-walking every visible band and the preparation horizon on the owner thread.
     */
    private fun planFor(snapshot: EngineRuntimeSnapshot): EngineTilePlan {
        val cached = plannedPlan
        if (cached != null && plannedSnapshot === snapshot) return cached
        val plan = planner.plan(snapshot)
        plannedSnapshot = snapshot
        plannedPlan = plan
        return plan
    }

    /**
     * The same plan yields the same render demands: [demand] hands back its cached value whenever
     * the priority and access plan are unchanged. Reusing the list keeps the work set's reconcile a
     * no-op on frames that only moved the viewport. The failed read-ahead set is part of the filter,
     * so its size is part of the key.
     */
    private fun renderDemands(snapshot: EngineRuntimeSnapshot, plan: EngineTilePlan): List<SessionDemand<*>> {
        pruneFailedReadAhead(failedReadAhead, MAXIMUM_FAILED_READ_AHEAD_TILES) {
            plan.demands.mapTo(linkedSetOf()) { it.tile }
        }
        if (demandedPlan === plan && demandedFailed == failedReadAhead.size) return plannedDemands
        val list = plan.demands.filter {
            it.priority != WorkPriority.NEXT_IMAGE || it.tile !in failedReadAhead
        }.map { demand(snapshot, it) }
        demandedPlan = plan
        demandedFailed = failedReadAhead.size
        // A frame that only moved the viewport re-derives the same demand instances in the same
        // order, so keep the previous list: the work set then recognises its own identity and skips
        // rebuilding its registry instead of re-scanning every subscription on the owner thread.
        val previous = plannedDemands
        if (list.size != previous.size || list.indices.any { list[it] !== previous[it] }) plannedDemands = list
        return plannedDemands
    }

    /** Owner-thread hint that a real drag or fling owns the frame; see [EngineTilePlanner]. */
    fun interactionActive(active: Boolean) {
        checkOwner()
        if (closed) return
        planner.interactionActive(active)
        if (interactionActive == active) return
        interactionActive = active
        // Work results that landed during the gesture were only marked dirty: the gesture's own
        // frame callback drains once per display slot. A gesture that ends without another frame
        // still owes those pixels, so deliver exactly one drain through the ordinary scheduler.
        if (!active && dirty && !processing && !clearingScene && !sceneClearFailed) refresh(0)
    }

    private fun forgetPlans() {
        plannedSnapshot = null
        plannedPlan = null
        demandedPlan = null
        demandedFailed = -1
        plannedDemands = emptyList()
    }

    fun update(snapshot: EngineRuntimeSnapshot) {
        checkOwner()
        if (closed) return
        require(current == null || current!!.session.sessionId == snapshot.session.sessionId)
        if (current?.session?.generation != snapshot.session.generation || epoch != uploader.rendererEpoch) {
            if (displayed != null) submitScene(EngineDrawScene(snapshot.session, emptyList(), false))
            displayed = null
            work.clear()
            textures.clear()
            tileDemands.clear()
            failedReadAhead.clear()
            forgetPlans()
            epoch = uploader.rendererEpoch
        }
        current = snapshot
        refresh(FrameWorkObserver.SNAPSHOT_UPDATE)
    }

    fun enabled(value: Boolean) {
        checkOwner()
        if (closed || enabled == value) return
        enabled = value
        // Disabling must retire visible pixels before the surface detaches: drain inline and
                // cancel any queued delivery rather than waiting for a message that will not come.
        if (value) refresh(FrameWorkObserver.SNAPSHOT_UPDATE)
        else refreshImmediately(FrameWorkObserver.SNAPSHOT_UPDATE)
    }

    fun rendererChanged() {
        checkOwner()
        current?.let(::update)
    }

    fun retryFailures() {
        checkOwner()
        if (!closed) {
            sceneClearFailed = false
            failedReadAhead.clear()
            work.retryFailures()
            refreshWorkResult()
        }
    }

    fun ownership(): SessionWorkOwnership = work.ownership()

    /** Pull-only owner-thread evidence; planner placements exclude speculative demands. */
    fun diagnosticSnapshot(): EngineRenderRuntimeDiagnosticSnapshot {
        checkOwner()
        val snapshot = current
        val plan = snapshot?.let(planner::plan)
        val visible = immutableSet(plan?.placements?.map { it.tile }.orEmpty())
        val resident = immutableSet(textures.keys)
        return EngineRenderRuntimeDiagnosticSnapshot(snapshot?.session, enabled,
            plan?.completeGeometry == true, enabled && plan?.completeGeometry == true && resident.containsAll(visible),
            visible, resident, work.ownership())
    }

    suspend fun close() = withContext(NonCancellable) {
        checkOwner()
        if (!closed) {
            closed = true
            cancelScheduledRefresh()
            var failure: Throwable? = null
            sceneClearJob?.join()
            try { current?.let { submitScene(EngineDrawScene(it.session, emptyList(), false)) } }
            catch (error: Throwable) { failure = error }
            try { clearScene() } catch (error: Throwable) {
                val original = failure
                if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            }
            try { work.close() } catch (error: Throwable) {
                val original = failure
                if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            }
            textures.clear()
            tileDemands.clear()
            failedReadAhead.clear()
            current = null
            displayed = null
            val result = failure
            if (result == null) closeDone.complete(Unit) else closeDone.completeExceptionally(result)
        }
        closeDone.await()
    }

    /**
     * Defers the drain to the scheduler's queued delivery when a scheduler is present; a null scheduler keeps
     * the legacy immediate drain. The guard set preserves [dirty] for whichever drain runs next.
     */
    private fun refresh(kind: Int) {
        if (kind != 0) frameWorkObserver?.workScheduled(kind, System.nanoTime())
        dirty = true
        if (processing || clearingScene || sceneClearFailed || closed) return
        when {
            refreshScheduler == null -> drainImmediate()
            // While a drag or fling owns the display slots, only its own frame step drains (see
            // refreshInteractionFrame). Draining here would rebuild the scene once per finished tile
            // inside whatever message delivered the result, and every one of those messages can push
            // the next vsync past its display slot.
            interactionActive -> Unit
            else -> scheduleRefresh()
        }
    }

    /** Cancels any queued delivery and drains the newest state inline on the owner thread. */
    fun refreshNow() {
        checkOwner()
        if (closed) return
        refreshImmediately(0)
    }

    /**
     * The single drain for one applied gesture step. A drag or fling frame owns its display slot and
     * every pixel that step revealed, so the scene is rebuilt exactly once per step instead of once
     * per finished tile.
     */
    fun refreshInteractionFrame() {
        checkOwner()
        if (closed || !interactionActive) return
        refreshImmediately(0)
    }

    /**
     * A work result changed the pixels, not the frame's schedule. During a real drag or fling the
     * frame callback already drains once per display slot, so queueing a second message here would
     * both lengthen the completion continuation that owns the main looper and put a long message in
     * front of the next vsync. The result is only marked dirty then; [interactionActive] delivers it
     * if the gesture ends without another frame.
     */
    private fun refreshWorkResult() {
        frameWorkObserver?.workScheduled(FrameWorkObserver.WORK_RESULT, System.nanoTime())
        dirty = true
        if (processing || clearingScene || sceneClearFailed || closed) return
        when {
            refreshScheduler == null -> drainImmediate()
            !interactionActive -> scheduleRefresh()
        }
    }

    /** Called once per delivery; applies at most one pass and re-arms when work arrived. */
    fun refreshOnFrame() {
        checkOwner()
        if (!scheduled) return
        scheduled = false
        if (closed || processing || clearingScene || sceneClearFailed) return
        val snapshot = current ?: return
        dirty = false
        processing = true
        try {
            // A pass that leaves [dirty] set has more to do, but during a gesture the frame callback
            // is already the only drain: queueing a second message would put a full drain in front of
            // the next vsync and cost it a display slot. The next frame drains again, and
            // [interactionActive] delivers the leftovers if the gesture ends first.
            if (refreshSnapshot(snapshot) && dirty && !interactionActive) scheduleRefresh()
        } finally { processing = false }
    }

    private fun refreshImmediately(kind: Int) {
        if (kind != 0) frameWorkObserver?.workScheduled(kind, System.nanoTime())
        dirty = true
        cancelScheduledRefresh()
        if (processing || clearingScene || sceneClearFailed || closed) return
        drainImmediate()
    }

    private fun drainImmediate() {
        processing = true
        try {
            while (dirty && !closed) {
                dirty = false
                val snapshot = current ?: break
                if (!refreshSnapshot(snapshot)) break
            }
        } finally { processing = false }
    }

    private fun scheduleRefresh() {
        val scheduler = refreshScheduler ?: return
        if (scheduled) return
        scheduled = true
        scheduler.post()
    }

    private fun cancelScheduledRefresh() {
        if (!scheduled) return
        scheduled = false
        refreshScheduler?.cancel()
    }

    private fun refreshSnapshot(snapshot: EngineRuntimeSnapshot): Boolean {
        val candidatePlan = if (enabled) tracer.section("engine_plan") { planFor(snapshot) }
        else EngineTilePlan(emptyList(), emptyList(), false, 0)
        val waiting = enabled && waitForCompleteViewport && !scene(snapshot, candidatePlan).completeCoverage
        val visiblePlan = if (waiting) tracer.section("engine_retain") {
            planner.retainDisplayed(candidatePlan, displayed?.quads?.map { it.texture.tile }.orEmpty())
        } else candidatePlan
        if (visiblePlan == null) {
            releaseDisplayedReferences()
            return !clearingScene && !sceneClearFailed
        }
        // Access order favors recently displayed pixels over older, speculative residency.
        visiblePlan.placements.forEach { placement ->
            textures.remove(placement.tile)?.let { textures[placement.tile] = it }
        }
        val plan = if (enabled) tracer.section("engine_retain") {
            planner.retainReady(visiblePlan, snapshot, textures.keys.toList().asReversed())
        } else visiblePlan
        val wantedTiles = plan.demands.mapTo(linkedSetOf()) { it.tile }
        textures.keys.retainAll(wantedTiles)
        tileDemands.keys.retainAll(wantedTiles)
        if (!waiting) {
            val next = tracer.section("engine_scene") { scene(snapshot, plan) }
            // Far-away original dimensions advance geometry revision without changing the
            // viewport. Preserve input revisions and every changed pixel, but avoid that swap.
            if (shouldSubmitScene(enabled, hasSubmittedScene, displayed, next)) {
                tracer.section("engine_submit") { submitScene(next) }
                hasSubmittedScene = true
            }
            displayed = next.takeIf { enabled && it.completeCoverage }
            if (enabled && next.completeCoverage) reportViewportReady(next.session)
        }
        if (!dirty) tracer.section("engine_reconcile") {
            work.reconcile(renderDemands(snapshot, plan))
        }
        return true
    }

    private fun releaseDisplayedReferences() {
        clearingScene = true
        sceneClearJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // The compositor keeps the previously swapped image while its GL texture leases retire.
                clearScene()
                displayed = null
            } catch (failure: Throwable) {
                sceneClearFailed = true
                reportSceneFailure(failure)
                return@launch
            } finally { clearingScene = false }
            refreshWorkResult()
        }
    }

    private fun demand(snapshot: EngineRuntimeSnapshot, demand: EngineTileDemand): SessionDemand<EngineTexture> {
        val accessPlan = snapshot.plans[demand.tile.pageId.episodeId]
        tileDemands[demand.tile]?.let { cached ->
            if (cached.priority == demand.priority && cached.accessPlan === accessPlan) return cached.value
        }
        val request = tiles.request(pageRequest(demand.tile.pageId, demand.priority), demand.tile, demand.priority)
        // The cache miss is the one moment this tile was newly asked for, so it is the start of the
        // latency a reader would feel if the tile were needed on screen right now.
        tileTimings.tileDemanded(demand.tile, demand.priority, System.nanoTime())
        EngineStageProbe.record(demand.tile as Any, EngineStageProbe.DEMAND, System.nanoTime(),
            demand.tile.pageId.remoteKey, demand.priority.name)
        val generation = snapshot.session.generation
        return SessionDemand(request, onFailure = if (demand.priority == WorkPriority.NEXT_IMAGE) ({ _: Throwable ->
            failedReadAhead += demand.tile
            refreshWorkResult()
        }) else null) { texture ->
            if (!closed && current?.session?.generation == generation &&
                texture.rendererEpoch == uploader.rendererEpoch) {
                require(texture.tile == demand.tile && texture.rendererId == uploader.rendererId)
                failedReadAhead -= demand.tile
                textures[demand.tile] = texture
                tileTimings.tileResident(demand.tile, System.nanoTime())
                EngineStageProbe.record(demand.tile as Any, EngineStageProbe.RESIDENT, System.nanoTime())
                refreshWorkResult()
            }
        }.also { tileDemands[demand.tile] = CachedTileDemand(accessPlan, demand.priority, it) }
    }

    private class CachedTileDemand(val accessPlan: ml.melun.mangaview.engine.api.EpisodeAccessPlan?,
        val priority: WorkPriority, val value: SessionDemand<EngineTexture>)

    private fun scene(snapshot: EngineRuntimeSnapshot, plan: EngineTilePlan): EngineDrawScene {
        val quads = plan.placements.mapNotNull { placement ->
            textures[placement.tile]?.let { EngineDrawQuad(it, placement.topScreenUnits, placement.bottomScreenUnits) }
        }
        return EngineDrawScene(snapshot.session, quads, plan.completeGeometry && quads.size == plan.placements.size)
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Render runtime is owner-thread confined" }
    private fun <T> immutableSet(source: Collection<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(source))
}

// Failed read-ahead tiles are remembered only while they can still be demanded; beyond this many
// distinct failures the set is pruned to the current plan so it cannot grow with a long session.
private const val MAXIMUM_FAILED_READ_AHEAD_TILES = 256

/** Drops failed read-ahead tiles that are no longer wanted once the set outgrows [maximum]. */
internal fun pruneFailedReadAhead(
    failed: MutableSet<EngineTileSpec>,
    maximum: Int,
    wanted: () -> Set<EngineTileSpec>,
): Boolean {
    if (failed.size <= maximum) return false
    return failed.retainAll(wanted())
}
