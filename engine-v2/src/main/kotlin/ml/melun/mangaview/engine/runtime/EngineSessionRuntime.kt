package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPort
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SessionUpdate
import ml.melun.mangaview.engine.api.SessionWorkOwnership
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkMetadata
import ml.melun.mangaview.engine.session.UnknownPageDimensionsException
import ml.melun.mangaview.source.AdjacentEpisodes

data class EngineSessionRuntimeDiagnosticSnapshot(
    val runtime: EngineRuntimeSnapshot,
    val work: SessionWorkOwnership,
    val launchPreparation: EngineLaunchPreparationSnapshot,
    val preparedPages: Int = 0,
)

data class EngineVerifiedPageObservation(
    val identity: PageContentIdentity,
    val ordinal: Int,
    val generation: Long,
    val inputRevision: Long,
    val geometryRevision: Long,
    val firstVerifiedAtNanos: Long,
)

/** Every input the demand planner reads, as a value key. Scroll-only movement keeps the same key. */
internal data class DemandKey(
    val generation: Long,
    val geometryRevision: Long,
    val positionResolved: Boolean,
    val initialPresented: Boolean,
    /** Clamped interaction horizon depth; recomputed from velocity and fetch latency. */
    val lead: Int,
    val transferVersion: Long,
    val anchorPage: PageId?,
    val visiblePages: Set<PageId>,
    val requiredDimensions: Set<PageId>,
    val requiredEpisodes: Set<EpisodeId>,
    val requiredNavigation: Set<EpisodeId>,
    val plans: Map<EpisodeId, EpisodeAccessPlan>,
    val pages: Map<PageId, PageContentIdentity>,
    val prepared: Set<PageId>,
    val failedPages: Set<PageId>,
    val unavailablePages: Set<PageId>,
    val failedEpisodes: Set<EpisodeId>,
)

/** Cumulative metadata for the episode that created this runtime; it owns no page or file lease. */
data class EngineLaunchPreparationSnapshot(
    val generation: Long,
    val episodeId: EpisodeId,
    val manifestAcceptedAtNanos: Long?,
    val manifestPageIds: List<PageId>,
    val verifiedPages: Map<PageId, EngineVerifiedPageObservation>,
    val allFirstVerifiedPreparedAtNanos: Long?,
)

/** Main-thread session effects. The reducer alone changes position; the coordinator alone executes work. */
class EngineSessionRuntime(
    scope: CoroutineScope,
    coordinator: WorkCoordinatorPort,
    internal val session: EngineSessionPort,
    private val source: EngineSessionWork,
    initialEpisode: EpisodeId,
    private val reportUpdate: (EngineRuntimeSnapshot, List<InputReceipt>) -> Unit,
    reportFailure: (WorkKey<*>, Throwable) -> Unit,
    internal val observationClock: () -> Long = System::nanoTime,
    private val awaitInitialPresentation: Boolean = false,
    /**
     * Backoff for a failed demand that stays desired. The work set retries it in place so a
     * transient provider failure cannot wedge the boundary. Injectable so tests can drive the
     * retry clock instead of sleeping the production second.
     */
    private val workRetryDelayNanos: Long = 1_000_000_000L,
    private val workClock: () -> Long = System::nanoTime,
) {
    private val owner = Thread.currentThread()
    private val work = SessionWorkSet(scope, coordinator, reportFailure,
        retryDelayNanos = workRetryDelayNanos, clock = workClock)
    // Publish new immutable maps only when their metadata changes, not on every scroll sample.
    internal var plans: Map<EpisodeId, EpisodeAccessPlan> = emptyMap()
        private set
    private val retainedCachedPlans = linkedMapOf<EpisodeId, CachedPlan>()
    private val pageDemands = SessionPageDemands(source, this::acceptPageGeometry,
        { generation, id, plan, page -> if (isCurrent(generation)) acceptPage(generation, id, plan, page) },
        { id -> handlePageFailure(id) })
    internal var pages: Map<PageId, PageContentIdentity> = emptyMap()
    internal val prepared = linkedSetOf<PageId>()
    internal val earlyTransfers = EarlyOriginalTransfers()
    internal val horizon = SessionFlingHorizon { observationClock() } // velocity + fetch latency
    internal val failedReadAheadPages = linkedSetOf<PageId>()
    /** Required pages declared unavailable for this session once the failure bound was reached. */
    internal val unavailablePages = linkedSetOf<PageId>()
    /** Distinct failed fetch attempts per page; a demand that keeps bouncing must not reset it. */
    internal val pageFailureCounts = mutableMapOf<PageId, Int>()
    private val failedReadAheadEpisodes = linkedSetOf<EpisodeId>()
    /** Required documents whose held plan was already re-delivered in this bout. */
    private val redeliveredManifests = linkedSetOf<EpisodeId>()
    private val failedEpisodeRetryAt = mutableMapOf<EpisodeId, Long>()
    internal val launch = LaunchPreparationRecorder(session.snapshot.generation, initialEpisode, observationClock)
    private val receipts = mutableListOf<InputReceipt>()
    private var targetEpisode = initialEpisode
    private var positionResolved = false
    private var started = false
    private var foreground = true
    internal var demandVersion = 0L
    /** Keyed demand cache; its rebuild count proves the lead hysteresis keeps the cache stable. */
    internal val demandCache = DemandCache()
    private var closed = false
    // Owner-thread interaction hint. A drag or fling owns the frame: while it does, the bulk
    // read-ahead only queues cached body lookups behind the tiles the reader is scrolling onto.
    @Volatile private var interactionActive = false
    private var processing = false
    private var dirty = false
    private var initialPresented = !awaitInitialPresentation
    private val retryWake = SessionRetryWake(scope) {
        if (started && !closed && foreground) process(SessionUpdate(session.snapshot))
    }
    private val inputReplay = SessionInputReplay(scope, { session.snapshot.generation },
        { started && !closed && foreground && session.inputReplayPending },
        { generation -> process(session.dispatch(SessionEvent.ContinueInput(generation))) })

    val snapshot: EngineRuntimeSnapshot get() {
        checkOwner()
        return EngineRuntimeSnapshot(session.snapshot, plans, pages)
    }

    fun open() {
        checkOwner()
        if (closed || started) return
        started = true
        process(SessionUpdate(session.snapshot))
    }

    /**
     * Owner-thread interaction hint. Only the bulk read-ahead reacts to it: the nearby horizon and
     * the guaranteed boundary head stay, so a fling still finds the page it is about to reveal,
     * while the whole-episode refill waits for the gesture to end.
     */
    fun interactionActive(active: Boolean) {
        checkOwner()
        if (closed || interactionActive == active) return
        interactionActive = active
        // Rebuild once across the boundary so the deferred bulk resumes when the gesture ends even
        // if no other input or geometry change follows it.
        demandVersion++
    }

    fun input(sample: InputSample): SessionUpdate {
        checkOwner()
        return session.dispatch(SessionEvent.Input(sample)).also(::process)
    }

    fun resize(viewport: EngineViewport) {
        checkOwner()
        if (!closed) process(session.dispatch(SessionEvent.Resize(viewport)))
    }

    /** Session-only split reading; the caller re-projects visible regions and tiles in place. */
    fun setSplitMode(enabled: Boolean) {
        checkOwner()
        if (closed || session.snapshot.splitMode == enabled) return
        process(session.dispatch(SessionEvent.SetSplitMode(enabled)))
    }

    /** Page-scrubber jump; a queued drag replay is dropped together with the movement it carried. */
    fun seekPage(pageId: PageId) {
        checkOwner()
        if (!closed) { inputReplay.cancel(); process(session.dispatch(SessionEvent.SeekPage(pageId))) }
    }

    fun navigate(episodeId: EpisodeId) {
        checkOwner()
        if (closed) return
        inputReplay.cancel()
        val update = session.dispatch(SessionEvent.Navigate(episodeId))
        work.clear()
        retainedCachedPlans.clear()
        pageDemands.clear()
        plans = emptyMap()
        pages = emptyMap()
        prepared.clear()
        earlyTransfers.clear()
        failedReadAheadPages.clear()
        unavailablePages.clear()
        pageFailureCounts.clear()
        failedReadAheadEpisodes.clear()
        failedEpisodeRetryAt.clear()
        horizon.reset()
        positionResolved = true
        targetEpisode = episodeId
        initialPresented = !awaitInitialPresentation
        demandCache.clear()
        process(update)
    }

    fun foreground(enabled: Boolean) {
        checkOwner()
        if (closed || foreground == enabled) return
        foreground = enabled
        if (!enabled) inputReplay.cancel()
        if (!enabled) { pages = emptyMap(); prepared.clear(); earlyTransfers.clear(); pageDemands.clear(); horizon.reset() }
        process(SessionUpdate(session.snapshot))
    }

    fun retryFailures() {
        checkOwner()
        if (!closed) {
            failedReadAheadPages.clear()
            unavailablePages.clear()
            pageFailureCounts.clear()
            failedReadAheadEpisodes.clear()
            failedEpisodeRetryAt.clear()
            work.retryFailures()
            process(SessionUpdate(session.snapshot))
        }
    }

    fun pageRequest(pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage> {
        checkOwner()
        check(!closed)
        return source.page(requireNotNull(plans[pageId.episodeId]), pageId, priority)
    }

    fun ownership(): SessionWorkOwnership = work.ownership()

    /** Pull-only owner-thread evidence; never collected from the update or frame path. */
    fun diagnosticSnapshot(): EngineSessionRuntimeDiagnosticSnapshot {
        checkOwner()
        return EngineSessionRuntimeDiagnosticSnapshot(snapshot, work.ownership(), launch.snapshot(), prepared.size)
    }

    fun releaseStartupInput() {
        checkOwner()
        if (closed) return
        val before = session.snapshot
        val update = session.dispatch(SessionEvent.ReleaseStartupInput)
        if (update.receipts.isNotEmpty() || update.snapshot != before) process(update)
    }

    fun viewportReady(presented: EngineSessionSnapshot) {
        checkOwner()
        if (closed) return
        val before = session.snapshot
        val update = session.dispatch(SessionEvent.ViewportReady(presented))
        if (update.receipts.isNotEmpty() || update.snapshot != before) process(update)
    }

    /** A successful complete native submission ends the initial viewport's priority boost. */
    fun initialViewportSubmitted(generation: Long) {
        checkOwner()
        if (!isCurrent(generation) || initialPresented) return
        initialPresented = true
        process(SessionUpdate(session.snapshot))
    }

    suspend fun close() {
        checkOwner()
        inputReplay.close()
        retryWake.cancel()
        if (!closed) {
            closed = true
            pages = emptyMap()
            prepared.clear()
            earlyTransfers.clear()
            pageDemands.clear()
            process(session.dispatch(SessionEvent.Close))
        }
        work.close()
        retainedCachedPlans.clear()
        plans = emptyMap()
    }

    internal fun process(update: SessionUpdate) {
        receipts += update.receipts
        dirty = true
        if (processing) return
        processing = true
        try {
            while (dirty) {
                dirty = false
                val state = session.snapshot
                horizon.sample(state, plans)
                val demand = when {
                    !started || closed -> emptyList()
                    foreground -> {
                        redeliverRetainedManifests(state, plans, redeliveredManifests, { session.dispatch(it) }) {
                            receipts += it.receipts
                            dirty = true
                        }
                        cachedDemands(state)
                    }
                    else -> cachedPlanPins(retainedCachedPlans)
                }
                val batch = receipts.toList()
                receipts.clear()
                reportUpdate(snapshot, batch)
                if (!dirty) work.reconcile(demand)
            }
        } finally {
            processing = false
        }
        inputReplay.schedule()
    }

    /** Demand inputs are versioned by preparation, not by input revision: a scroll that only
     * moves inside the same visible pages reuses the identical request set instead of
     * rebuilding every SessionDemand lambda and rescanning manifest pages on each sample.
     * Mutable sets are copied into the key so in-place mutation invalidates the entry. */
    private fun cachedDemands(state: EngineSessionSnapshot): List<SessionDemand<*>> {
        releaseRecoveredEpisodeFailures(failedReadAheadEpisodes, failedEpisodeRetryAt, observationClock)
        val lead = horizon.lead(interactionActive)
        val key = DemandKey(state.generation, state.geometryRevision, positionResolved,
            initialPresented, lead, demandVersion, state.anchor?.pageId,
            state.visibleRegions.mapTo(linkedSetOf()) { it.pageId }, state.requiredDimensions,
            state.requiredEpisodes, state.requiredNavigation, plans, pages,
            prepared.toSet(), failedReadAheadPages.toSet(), unavailablePages.toSet(), failedReadAheadEpisodes.toSet())
        return demandCache.get(key) { demands(state, lead) }
    }

    private fun demands(state: EngineSessionSnapshot, lead: Int): List<SessionDemand<*>> {
        plans = applyPlanWindow(plans, retainedCachedPlans, state, targetEpisode, pages)
        val result = mutableListOf<SessionDemand<*>>()
        val generation = state.generation
        if (!positionResolved) result += SessionDemand(source.position(targetEpisode)) { position ->
            if (isCurrent(generation)) {
                positionResolved = true
                process(session.dispatch(SessionEvent.PositionResolved(generation, position.anchor, position.legacy)))
            }
        }
        val wantedPages = pagePriorities(
            state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented, interactionActive,
            lead, earlyTransfers,
        )
        // A page declared unavailable keeps the layout walkable through its placeholder geometry.
        // Off screen it owns no demand; on screen it keeps retrying on the work set's backoff, so a
        // provider outage that ends restores the original instead of leaving a blank page for the
        // rest of the session.
        wantedPages.keys.removeAll { it in unavailablePages && state.visibleRegions.none { region -> region.pageId == it } }
        pages = retainPreparedMetadata(state, wantedPages.keys, plans, pages)
        // Prepared markers only matter while their page metadata is retained; without this the
        // set keeps growing across a long read that walks past many documents.
        prepared.retainAll(pages.keys)
        horizon.track(wantedPages, prepared)
        val wantedEpisodes = linkedMapOf<EpisodeId, WorkPriority>()
        state.requiredEpisodes.forEach { wantedEpisodes[it] = WorkPriority.FOCUS }
        wantedPages.forEach { (id, priority) ->
            if (id.episodeId !in plans) wantedEpisodes[id.episodeId] = priority
        }
        // One forward document uses the spare control slot; its image bodies remain background work.
        adjacentPrefetch(state, positionResolved, plans, targetEpisode, prepared, initialPresented, failedReadAheadEpisodes)
            ?.let { if (it !in plans) wantedEpisodes.putIfAbsent(it, WorkPriority.INTERACTIVE) }
        wantedEpisodes.forEach { (id, priority) ->
            if (id !in plans) {
                result += episodeDemand(generation, id, priority)
            } else if (id in state.requiredEpisodes) {
                // The session prunes its own manifest window around the reading position, but a
                // document the geometry still requires can be gone from it while this runtime keeps
                // the plan. The plan map then suppresses the episode demand and the boundary waits
                // forever. Accepting the held plan again re-dispatches only its manifest.
                result += SessionDemand(source.episode(id, WorkPriority.FOCUS),
                    onFailure = { _: Throwable -> markEpisodeFailure(id) }) { plan ->
                    if (isCurrent(generation)) acceptPlan(generation, id, plan)
                }
            }
        }
        navigationDemands(state, generation, result)
        pageDemands.retain(wantedPages.keys)
        wantedPages.forEach { (id, priority) ->
            val plan = plans[id.episodeId] ?: return@forEach
            result += pageDemands.get(generation, id, plan, priority)
        }
        result += cachedPlanPins(retainedCachedPlans)
        return result
    }

    /**
     * Navigation documents whose plans lack adjacency: first the anchor document's, the boundary
     * the reader is heading toward (resolving it as soon as the plan exists gives a slow catalog
     * the whole chapter of headroom instead of racing the reader at the end), then every boundary
     * the geometry still requires.
     */
    private fun navigationDemands(
        state: EngineSessionSnapshot,
        generation: Long,
        result: MutableList<SessionDemand<*>>,
    ) {
        val anchorEpisode = state.anchor?.pageId?.episodeId ?: targetEpisode
        val wanted = state.requiredNavigation.toMutableList()
        if (anchorEpisode !in state.requiredNavigation) wanted.add(0, anchorEpisode)
        wanted.forEach { id ->
            if (plans[id]?.navigationKnown == false) {
                result += SessionDemand(source.navigation(id, WorkPriority.INTERACTIVE),
                    onFailure = { _: Throwable -> }) { navigation ->
                    if (isCurrent(generation)) acceptNavigation(generation, id, navigation)
                }
            }
        }
    }

    private fun episodeDemand(generation: Long, id: EpisodeId, priority: WorkPriority): SessionDemand<EpisodeAccessPlan> {
        val request = source.episode(id, priority)
        // A plan failure never fails the session, whatever its priority: the demand stays desired
        // while the geometry needs the document, so the work set retries it on its own backoff.
        return SessionDemand(request, onFailure = { _: Throwable -> markEpisodeFailure(id) }) { plan ->
            if (isCurrent(generation)) {
                if (plan.localOnly) retainedCachedPlans[id] = CachedPlan(request, plan)
                acceptPlan(generation, id, plan)
            }
        }
    }

    /** Marks a failed document fetch and schedules its read-ahead release, mirroring page failures. */
    private fun markEpisodeFailure(id: EpisodeId) {
        failedReadAheadEpisodes += id
        failedEpisodeRetryAt[id] = observationClock() + EPISODE_RETRY_DELAY_NANOS
        process(SessionUpdate(session.snapshot))
    }

    private fun acceptPlan(generation: Long, expected: EpisodeId, plan: EpisodeAccessPlan) {
        require(plan.manifest.id == expected)
        val update = session.dispatch(SessionEvent.ManifestResolved(generation, plan.manifest, plan.navigationKnown))
        plans = withEntry(plans, expected, plan)
        launch.onManifestAccepted(expected, generation, plan)
        process(update)
    }

    private fun acceptNavigation(generation: Long, id: EpisodeId, navigation: AdjacentEpisodes) {
        val previous = requireNotNull(plans[id])
        val update = session.dispatch(SessionEvent.NavigationResolved(generation, id, navigation.previous, navigation.next))
        plans = withEntry(plans, id, EpisodeAccessPlan(previous.manifest.copy(previousEpisodeId = navigation.previous,
            nextEpisodeId = navigation.next), previous.contentRevision, previous.documentSha256,
            previous.finalDocumentUrl, previous.authEpoch, previous.pages, previous.prerequisites,
            navigationKnown = true, localOnly = previous.localOnly))
        process(update)
    }

    /**
     * A failed page wakes the idle runtime for its retry with doubling backoff, up to a bound.
     * Kept on the runtime so the wake and its delay stay private; page acceptance calls this.
     */
    internal fun armPageRetryWake(failures: Int) {
        if (failures <= PAGE_WAKE_FAILURES) {
            retryWake.schedule(workRetryDelayNanos shl minOf(failures - 1, PAGE_WAKE_MAX_SHIFT))
        }
    }

    internal fun isCurrent(generation: Long) = !closed && generation == session.snapshot.generation

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Session runtime is owner-thread confined" }
}

/**
 * Value-keyed demand cache. [rebuilds] counts how often the planner actually ran; the lead
 * hysteresis test reads it to prove an oscillating horizon no longer invalidates the plan.
 */
internal class DemandCache {
    private var lastKey: DemandKey? = null
    private var lastDemands: List<SessionDemand<*>> = emptyList()
    var rebuilds = 0L
        private set

    fun get(key: DemandKey, build: () -> List<SessionDemand<*>>): List<SessionDemand<*>> {
        if (key == lastKey) return lastDemands
        val result = build()
        rebuilds++
        lastKey = key
        lastDemands = result
        return result
    }

    fun clear() {
        lastKey = null
        lastDemands = emptyList()
    }
}

// A failed page wakes the idle runtime for its retry with doubling backoff, up to this many times.
private const val PAGE_WAKE_FAILURES = 12
private const val PAGE_WAKE_MAX_SHIFT = 5
