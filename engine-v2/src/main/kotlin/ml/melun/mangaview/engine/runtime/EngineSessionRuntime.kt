package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
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
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkMetadata
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
    private val session: EngineSessionPort,
    private val source: EngineSessionWork,
    initialEpisode: EpisodeId,
    private val reportUpdate: (EngineRuntimeSnapshot, List<InputReceipt>) -> Unit,
    reportFailure: (WorkKey<*>, Throwable) -> Unit,
    private val observationClock: () -> Long = System::nanoTime,
    private val awaitInitialPresentation: Boolean = false,
) {
    private val owner = Thread.currentThread()
    private val work = SessionWorkSet(scope, coordinator, reportFailure)
    // Publish new immutable maps only when their metadata changes, not on every scroll sample.
    private var plans: Map<EpisodeId, EpisodeAccessPlan> = emptyMap()
    private val retainedCachedPlans = linkedMapOf<EpisodeId, CachedPlan>()
    private val pageDemands = SessionPageDemands(source, ::acceptPageGeometry,
        { generation, id, plan, page -> if (isCurrent(generation)) acceptPage(generation, id, plan, page) },
        { id -> markPageFailure(id) })
    private var pages: Map<PageId, PageContentIdentity> = emptyMap()
    private val prepared = linkedSetOf<PageId>()
    private val earlyTransfers = EarlyOriginalTransfers()
    private val failedReadAheadPages = linkedSetOf<PageId>()
    private val failedReadAheadEpisodes = linkedSetOf<EpisodeId>()
    private val failedEpisodeRetryAt = mutableMapOf<EpisodeId, Long>()
    private val launchGeneration = session.snapshot.generation
    private val launchEpisode = initialEpisode
    private var launchManifestAcceptedAtNanos: Long? = null
    private var launchManifestContentRevision: String? = null
    private var launchManifestPageIds: List<PageId> = emptyList()
    private val launchVerifiedPages = linkedMapOf<PageId, EngineVerifiedPageObservation>()
    private var launchAllPreparedAtNanos: Long? = null
    private val receipts = mutableListOf<InputReceipt>()
    private var targetEpisode = initialEpisode
    private var positionResolved = false
    private var started = false
    private var foreground = true
    private var demandVersion = 0L
    private var lastDemandKey: DemandKey? = null
    private var lastDemands: List<SessionDemand<*>> = emptyList()
    private var closed = false
    // Owner-thread interaction hint. A drag or fling owns the frame: while it does, the bulk
    // read-ahead only queues cached body lookups behind the tiles the reader is scrolling onto.
    @Volatile private var interactionActive = false
    private var processing = false
    private var dirty = false
    private var initialPresented = !awaitInitialPresentation
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
        failedReadAheadEpisodes.clear()
        failedEpisodeRetryAt.clear()
        positionResolved = true
        targetEpisode = episodeId
        initialPresented = !awaitInitialPresentation
        lastDemandKey = null
        lastDemands = emptyList()
        process(update)
    }

    fun foreground(enabled: Boolean) {
        checkOwner()
        if (closed || foreground == enabled) return
        foreground = enabled
        if (!enabled) inputReplay.cancel()
        if (!enabled) { pages = emptyMap(); prepared.clear(); earlyTransfers.clear(); pageDemands.clear() }
        process(SessionUpdate(session.snapshot))
    }

    fun retryFailures() {
        checkOwner()
        if (!closed) {
            failedReadAheadPages.clear()
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
        return EngineSessionRuntimeDiagnosticSnapshot(snapshot, work.ownership(), EngineLaunchPreparationSnapshot(
            launchGeneration, launchEpisode, launchManifestAcceptedAtNanos,
            Collections.unmodifiableList(launchManifestPageIds.toList()),
            immutableMap(launchVerifiedPages), launchAllPreparedAtNanos), prepared.size)
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

    private fun process(update: SessionUpdate) {
        receipts += update.receipts
        dirty = true
        if (processing) return
        processing = true
        try {
            while (dirty) {
                dirty = false
                val state = session.snapshot
                val demand = when {
                    !started || closed -> emptyList()
                    foreground -> cachedDemands(state)
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

    private fun markPageFailure(id: PageId) {
        failedReadAheadPages += id
        while (failedReadAheadPages.size > MAXIMUM_FAILED_READ_AHEAD_PAGES) {
            failedReadAheadPages.remove(failedReadAheadPages.first())
        }
        process(SessionUpdate(session.snapshot))
    }

    /**
     * A long read can cross many documents in place. A plan for a document far behind is never read
     * again (its page metadata was already dropped), and a retained cached plan additionally pins the
     * episode's work record and file leases, so keep the plan set inside a window around the reading
     * position instead of letting it grow with every episode crossed.
     */
    private fun retainPlanWindow(state: EngineSessionSnapshot) {
        val dropped = planWindowToDrop(plans, state, targetEpisode, pages, RETAINED_PLAN_EPISODES)
        if (dropped.isEmpty()) return
        val retained = LinkedHashMap(plans)
        dropped.forEach(retained::remove)
        plans = immutableMap(retained)
        dropped.forEach(retainedCachedPlans::remove)
    }

    /** Demand inputs are versioned by preparation, not by input revision: a scroll that only
     * moves inside the same visible pages reuses the identical request set instead of
     * rebuilding every SessionDemand lambda and rescanning manifest pages on each sample.
     * Mutable sets are copied into the key so in-place mutation invalidates the entry. */
    private fun cachedDemands(state: EngineSessionSnapshot): List<SessionDemand<*>> {
        releaseRecoveredEpisodeFailures(failedReadAheadEpisodes, failedEpisodeRetryAt, observationClock)
        val key = DemandKey(state.generation, state.geometryRevision, positionResolved,
            initialPresented, demandVersion, state.anchor?.pageId,
            state.visibleRegions.mapTo(linkedSetOf()) { it.pageId }, state.requiredDimensions,
            state.requiredEpisodes, state.requiredNavigation, plans, pages,
            prepared.toSet(), failedReadAheadPages.toSet(), failedReadAheadEpisodes.toSet())
        if (key == lastDemandKey) return lastDemands
        val result = demands(state)
        lastDemandKey = key
        lastDemands = result
        return result
    }

    private fun demands(state: EngineSessionSnapshot): List<SessionDemand<*>> {
        retainPlanWindow(state)
        val result = mutableListOf<SessionDemand<*>>()
        val generation = state.generation
        if (!positionResolved) result += SessionDemand(source.position(targetEpisode)) { position ->
            if (isCurrent(generation)) {
                positionResolved = true
                process(session.dispatch(SessionEvent.PositionResolved(generation, position.anchor, position.legacy)))
            }
        }
        val wantedPages = pagePriorities(
            state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented, interactionActive, earlyTransfers,
        )
        pages = retainPreparedMetadata(state, wantedPages.keys, plans, pages)
        // Prepared markers only matter while their page metadata is retained; without this the
        // set keeps growing across a long read that walks past many documents.
        prepared.retainAll(pages.keys)
        val wantedEpisodes = linkedMapOf<EpisodeId, WorkPriority>()
        state.requiredEpisodes.forEach { wantedEpisodes[it] = WorkPriority.FOCUS }
        wantedPages.forEach { (id, priority) ->
            if (id.episodeId !in plans) wantedEpisodes[id.episodeId] = priority
        }
        // One forward document uses the spare control slot; its image bodies remain background work.
        adjacentPrefetch(state, positionResolved, plans, targetEpisode, prepared, initialPresented, failedReadAheadEpisodes)
            ?.let { if (it !in plans) wantedEpisodes.putIfAbsent(it, WorkPriority.INTERACTIVE) }
        wantedEpisodes.forEach { (id, priority) ->
            if (id !in plans) result += episodeDemand(generation, id, priority)
        }
        // The anchor document's adjacency is the boundary the reader is heading toward; resolving
        // it as soon as the plan exists gives a slow catalog the whole chapter of headroom instead
        // of racing the reader at the end.
        val anchorEpisode = state.anchor?.pageId?.episodeId ?: targetEpisode
        if (plans[anchorEpisode]?.navigationKnown == false && anchorEpisode !in state.requiredNavigation) {
            result += SessionDemand(source.navigation(anchorEpisode, WorkPriority.INTERACTIVE)) { navigation ->
                if (isCurrent(generation)) acceptNavigation(generation, anchorEpisode, navigation)
            }
        }
        state.requiredNavigation.forEach { id ->
            if (plans[id]?.navigationKnown == false) {
                result += SessionDemand(source.navigation(id, WorkPriority.INTERACTIVE)) { navigation ->
                    if (isCurrent(generation)) acceptNavigation(generation, id, navigation)
                }
            }
        }
        pageDemands.retain(wantedPages.keys)
        wantedPages.forEach { (id, priority) ->
            val plan = plans[id.episodeId] ?: return@forEach
            result += pageDemands.get(generation, id, plan, priority)
        }
        result += cachedPlanPins(retainedCachedPlans)
        return result
    }

    private fun acceptPageGeometry(generation: Long, id: PageId, plan: EpisodeAccessPlan, metadata: WorkMetadata) {
        if (!isCurrent(generation) || plans[id.episodeId] !== plan) return
        val geometry = metadata as? WorkMetadata.PageGeometry ?: return
        require(geometry.pageId == id && geometry.contentRevision == plan.contentRevision)
        if (matchesVerifiedGeometry(pages[id], geometry) && id in prepared && id !in failedReadAheadPages) return
        demandVersion++
        earlyTransfers.observed(id)
        process(session.dispatch(SessionEvent.DimensionsResolved(generation, id, geometry.dimensions)))
    }

    private fun episodeDemand(generation: Long, id: EpisodeId, priority: WorkPriority): SessionDemand<EpisodeAccessPlan> {
        val request = source.episode(id, priority)
        return SessionDemand(request, onFailure =
            if (priority == WorkPriority.NEXT_EPISODE || priority == WorkPriority.INTERACTIVE) ({ _: Throwable ->
                failedReadAheadEpisodes += id
                failedEpisodeRetryAt[id] = observationClock() + EPISODE_RETRY_DELAY_NANOS
                process(SessionUpdate(session.snapshot))
            }) else null) { plan ->
            if (isCurrent(generation)) {
                if (plan.localOnly) retainedCachedPlans[id] = CachedPlan(request, plan)
                acceptPlan(generation, id, plan)
            }
        }
    }

    private fun acceptPlan(generation: Long, expected: EpisodeId, plan: EpisodeAccessPlan) {
        require(plan.manifest.id == expected)
        val update = session.dispatch(SessionEvent.ManifestResolved(generation, plan.manifest, plan.navigationKnown))
        plans = withEntry(plans, expected, plan)
        if (generation == launchGeneration && expected == launchEpisode && launchManifestAcceptedAtNanos == null) {
            launchManifestPageIds = plan.manifest.pages.map { it.id }
            launchManifestContentRevision = plan.contentRevision
            launchManifestAcceptedAtNanos = observationClock().also { require(it > 0L) }
        }
        process(update)
    }

    private fun acceptPage(generation: Long, expected: PageId, plan: EpisodeAccessPlan, page: StoredPage) {
        require(page.pageId == expected && page.contentRevision == plan.contentRevision)
        val identity = PageContentIdentity(expected, page.contentRevision, page.sha256, page.dimensions, page.byteCount)
        // Reacquiring the same verified original changes subscription ownership, not visible content.
        if (pages[expected] == identity && expected in prepared && expected !in failedReadAheadPages) return
        prepared += expected
        failedReadAheadPages -= expected
        val update = session.dispatch(SessionEvent.DimensionsResolved(generation, expected, page.dimensions))
        if (pages[expected] != identity) pages = withEntry(pages, expected, identity)
        if (generation == launchGeneration && expected.episodeId == launchEpisode &&
            page.contentRevision == launchManifestContentRevision && expected in launchManifestPageIds &&
            expected !in launchVerifiedPages
        ) {
            val acceptedAt = observationClock().also { require(it > 0L) }
            launchVerifiedPages[expected] = EngineVerifiedPageObservation(identity,
                launchManifestPageIds.indexOf(expected), generation, update.snapshot.inputRevision,
                update.snapshot.geometryRevision, acceptedAt)
            if (launchAllPreparedAtNanos == null && launchManifestPageIds.isNotEmpty() &&
                launchVerifiedPages.keys.containsAll(launchManifestPageIds)
            ) launchAllPreparedAtNanos = acceptedAt
        }
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

    private fun isCurrent(generation: Long) = !closed && generation == session.snapshot.generation
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Session runtime is owner-thread confined" }
}

// The next document's first pages are a fixed horizon; near the document end they outrank the
// remaining bulk so the boundary is already readable when the reader crosses it.
private const val NEXT_EPISODE_HEAD_PAGES = 4
private const val BOUNDARY_APPROACH_PAGES = 6
/** Guaranteed next-episode pages once the boundary is within [BOUNDARY_APPROACH_PAGES]. */
private const val BOUNDARY_HEAD_PAGES = 10
// A transient neighbor failure retries on its own instead of parking the boundary for the session.
private const val EPISODE_RETRY_DELAY_NANOS = 3_000_000_000L

// A long read can cross many documents in place; plans (and cached-plan pins, which hold the
// episode's file leases) for documents far behind the reading position stay only inside this window.
private const val RETAINED_PLAN_EPISODES = 8
// Distinct failed read-ahead pages beyond this many are forgotten oldest-first; a page still on the
// horizon is retried by its next demand.
private const val MAXIMUM_FAILED_READ_AHEAD_PAGES = 256

/** Keys to drop so the plan map stays inside its window; never drops a protected episode. */
internal fun <V> planKeysToDrop(
    plans: Map<EpisodeId, V>,
    protectedEpisodes: Set<EpisodeId>,
    maximum: Int,
): List<EpisodeId> {
    val overflow = plans.size - maximum
    if (overflow <= 0) return emptyList()
    return plans.keys.filter { it !in protectedEpisodes }.take(overflow)
}

// Depth of the *page* horizon behind the leading required page. While a gesture owns the frame the
// bulk read-ahead is deferred, so without this the horizon is only two pages deep and a read-ahead
// tile is usually demanded before its page has been published. Deepening the page horizon (never
// the tile horizon) lets the publish finish ahead of the demand without queueing extra decode work
// on the lanes the visible tile shares. At rest the bulk already streams the whole tail, so a
// shallow explicit horizon is enough there.
private const val PAGES_AHEAD_WHILE_INTERACTING = 4
private const val PAGES_AHEAD_AT_REST = 2

// Read-ahead planning lives at file level: pure demand ordering over the caller's maps, so the
// session runtime stays under the size gate without giving up the prepared/failed context.
private fun addReadAhead(state: EngineSessionSnapshot, plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, interactionActive: Boolean, result: LinkedHashMap<PageId, WorkPriority>,
) {
    val anchor = readAheadAnchor(state, targetEpisode) ?: return
    val manifest = plans[anchor.episodeId]?.manifest ?: return
    val index = manifest.pages.indexOfFirst { it.id == anchor }
    if (index < 0) return
    addNearbyOriginals(state, manifest, index, plans, prepared, failedReadAheadPages, initialPresented,
        interactionActive, result)
    // Give every original needed by the opening viewport the first network window.
    // Bulk transfer starts as soon as those bytes arrive, independently of rendering.
    // While a drag or fling owns the frame the bulk waits: it only re-materialises whole cached
    // episode tails behind the tiles the reader is scrolling onto, and its hundreds of lookups
    // saturate the storage lane and the decode threads the visible tile's own path has to share.
    if (!interactionActive && (initialPresented || (state.completeViewport && state.visibleRegions.all { it.pageId in prepared })))
        addRemainingOriginals(manifest, index, prepared, failedReadAheadPages, result)
    addNextOriginals(state, manifest, index, plans, prepared, failedReadAheadPages, initialPresented,
        interactionActive, result)
}

private fun addNearbyOriginals(state: EngineSessionSnapshot, manifest: EpisodeManifest, index: Int,
    plans: Map<EpisodeId, EpisodeAccessPlan>, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, interactionActive: Boolean, result: LinkedHashMap<PageId, WorkPriority>,
) {
    // Keep a small prepared neighborhood available to the tile planner. Originals
    // elsewhere stay in disk storage; never retain an entire episode's textures.
    for (offset in listOf(1, 2, -1)) {
        val id = manifest.pages.getOrNull(index + offset)?.id ?: continue
        if (id in prepared) result.putIfAbsent(id, WorkPriority.NEXT_IMAGE)
    }
    // Start the nearby pages alongside the focus original under background permits.
    // Waiting for the first body or a complete scene serializes a multi-page viewport.
    val leadingIndex = state.requiredDimensions.fold(index) { leading, id ->
        maxOf(leading, manifest.pages.indexOfFirst { it.id == id })
    }
    // While a gesture owns the frame the bulk read-ahead is deferred, so the page horizon is only
    // two pages deep and a read-ahead tile's page is usually still being published when the tile is
    // demanded (measured: pageReady 1.8ms of a 8.5ms tile, and 6.7ms on an opening tile). Deepen
    // only the page horizon while interacting — pages, never tiles — so the publish completes ahead
    // of the demand without queueing any extra decode on the lanes the visible tile has to share.
    // Only after the opening is presented: during the opening the FOCUS/VISIBLE pages are still being
    // published, and horizon pages queued beside them take the lanes those tiles are waiting on.
    // Measured on the GPU AVD: deepening the horizon during the opening as well raised the opening
    // tiles' demand->resident to 40.9ms (gate 28.75) while the later read-ahead mass kept its gain.
    // (Gating it on initialPresented was measured worse still: wfwf d2r p50 7.94 -> 9.33ms, so the
    // opening horizon keeps the deeper value and only the measurement changes are left here.)
    val ahead = if (interactionActive) PAGES_AHEAD_WHILE_INTERACTING else PAGES_AHEAD_AT_REST
    for (offset in 1..ahead) {
        val ordinal = leadingIndex + offset
        val id = manifest.pages.getOrNull(ordinal)?.id ?: manifest.nextEpisodeId?.let { next ->
            // The same two-page horizon continues across a known document boundary.
            plans[next]?.manifest?.pages?.getOrNull(ordinal - manifest.pages.size)?.id
        } ?: continue
        val priority = if (!initialPresented && ordinal <= index + 2) WorkPriority.VISIBLE else WorkPriority.NEXT_IMAGE
        if (id !in failedReadAheadPages) result.putIfAbsent(id, priority)
    }
}

private fun addRemainingOriginals(manifest: EpisodeManifest, index: Int, prepared: Set<PageId>,
    failedReadAheadPages: Set<PageId>, result: LinkedHashMap<PageId, WorkPriority>,
) {
    // Stream originals to disk while the app reserves two BODY slots for visible work.
    val remainingSlots = (12 - result.count { (id, priority) -> priority == WorkPriority.NEXT_IMAGE && id !in prepared }).coerceAtLeast(0)
    pendingOriginalPages(manifest, index, remainingSlots, prepared, failedReadAheadPages, result)
        .forEach { result.putIfAbsent(it, WorkPriority.NEXT_IMAGE) }
}

private fun addNextOriginals(state: EngineSessionSnapshot, manifest: EpisodeManifest, index: Int,
    plans: Map<EpisodeId, EpisodeAccessPlan>, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, interactionActive: Boolean, result: LinkedHashMap<PageId, WorkPriority>,
) {
    val next = manifest.nextEpisodeId?.let { plans[it]?.manifest } ?: return
    // The opening horizon is guaranteed: by the time the reader reaches the boundary the first
    // pages wait on disk, independently of how busy the current document's tail still is. Near the
    // boundary they stream ahead of the remaining bulk, still inside the background permits.
    val nearEnd = manifest.pages.size - index <= BOUNDARY_APPROACH_PAGES
    val headPriority = if (nearEnd) WorkPriority.NEXT_IMAGE else WorkPriority.NEXT_EPISODE
    // The guaranteed head deepens once the boundary is in sight: a reader arriving there should
    // find more than the opening pages already on disk. The extra pages keep the same background
    // priority, so they still yield to every visible and interactive request.
    val head = if (nearEnd) BOUNDARY_HEAD_PAGES else NEXT_EPISODE_HEAD_PAGES
    next.pages.take(head).filter { it.id !in failedReadAheadPages }.forEach {
        result.putIfAbsent(it.id, headPriority)
    }
    // While a gesture owns the frame the bulk stays parked: these twelve slots are page transfers
    // whose lookups and publishes otherwise ride the same background lanes the reader's own
    // read-ahead has to share (measured on wfwf's fling median). The four-page head above keeps
    // the boundary warm, and the slots re-fill on the first update after the gesture ends.
    if (interactionActive) return
    // Start before GPU preparation, but leave every queued current body its background slot.
    // Filling all next slots prematurely can strand the current episode's sliding-window tail.
    val openingReady = state.completeViewport && state.visibleRegions.all { it.pageId in prepared }
    if (!initialPresented && !openingReady && manifest.pages.any { it.id !in prepared }) return
    val occupied = result.count { (id, priority) -> id !in prepared &&
        (priority == WorkPriority.NEXT_IMAGE || priority == WorkPriority.NEXT_EPISODE) }
    val available = (12 - occupied).coerceAtLeast(0)
    next.pages.asSequence().filter { it.id !in prepared && it.id !in failedReadAheadPages && it.id !in result }
        .take(available).forEach { result[it.id] = WorkPriority.NEXT_EPISODE }
}

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
private fun <K, V> withEntry(source: Map<K, V>, key: K, value: V): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source).apply { put(key, value) })

// The final original of the reading document gets one spare interactive request so a fast
// reader cannot outrun a displayable episode end. It is not visible work and never displaces
// the twelve background transfer permits. Short documents are covered by the ordinary horizon,
// and the reservation starts only after the opening viewport is presented.
private fun reserveDocumentEndOriginal(state: EngineSessionSnapshot, plans: Map<EpisodeId, EpisodeAccessPlan>,
    target: EpisodeId, prepared: Set<PageId>, failed: Set<PageId>, presented: Boolean,
    result: LinkedHashMap<PageId, WorkPriority>,
) {
    if (!presented) return
    val anchor = readAheadAnchor(state, target) ?: return
    val manifest = plans[anchor.episodeId]?.manifest ?: return
    val index = manifest.pages.indexOfFirst { it.id == anchor }
    if (index < 0 || manifest.pages.size - index <= 8) return
    val tail = manifest.pages.last().id
    if (tail !in prepared && tail !in failed) result.putIfAbsent(tail, WorkPriority.INTERACTIVE)
}

// Stream forward pages in reading order for the bulk background window. Reverse input fills
// earlier pages only once the forward phase is complete.
private fun pendingOriginalPages(manifest: EpisodeManifest, index: Int, slots: Int,
    prepared: Set<PageId>, failed: Set<PageId>, existing: Map<PageId, WorkPriority>,
): List<PageId> {
    fun pending(indices: IntProgression) = indices.asSequence().map { manifest.pages[it].id }
        .filter { it !in prepared && it !in failed && it !in existing }
    val forwardPending = (index + 1 until manifest.pages.size).any {
        manifest.pages[it].id !in prepared && manifest.pages[it].id !in failed
    }
    if (!forwardPending) return pending(index - 1 downTo 0).take(slots).toList()
    return pending(index + 1 until manifest.pages.size).take(slots).toList()
}

private fun matchesVerifiedGeometry(known: PageContentIdentity?, geometry: WorkMetadata.PageGeometry): Boolean {
    if (known == null) return false
    require(known.dimensions == geometry.dimensions) { "Conflicting dimensions for ${geometry.pageId}" }
    return known.contentRevision == geometry.contentRevision
}

// The unresolved legacy page is a known request, even before its dimensions
// can convert the saved offset into an exact source anchor.
private fun readAheadAnchor(state: EngineSessionSnapshot, target: EpisodeId): PageId? =
    state.anchor?.pageId ?: state.requiredDimensions.firstOrNull { it.episodeId == target }

// Keep the first adjacent authorization independent of legacy geometry. After the
// current originals and viewport are ready, use the control slot for one further
// document while the adjacent bodies load. This never starts that document's bodies
// or recursively walks its navigation links.
private fun nextDocumentToPrepare(manifest: EpisodeManifest, plans: Map<EpisodeId, EpisodeAccessPlan>,
    prepared: Set<PageId>, initialPresented: Boolean, failed: Set<EpisodeId>,
): EpisodeId? {
    val next = manifest.nextEpisodeId?.takeUnless { it in failed } ?: return null
    val nextPlan = plans[next] ?: return next
    if (!initialPresented || manifest.pages.any { it.id !in prepared }) return null
    return nextPlan.manifest.nextEpisodeId?.takeUnless { it in plans || it in failed }
}

private fun retainPreparedMetadata(state: EngineSessionSnapshot, wantedPages: Set<PageId>,
    plans: Map<EpisodeId, EpisodeAccessPlan>, pages: Map<PageId, PageContentIdentity>,
): Map<PageId, PageContentIdentity> {
    val episodes = wantedPages.mapTo(mutableSetOf()) { it.episodeId }
    state.anchor?.pageId?.episodeId?.let { current ->
        episodes += current
        plans[current]?.manifest?.let { manifest ->
            manifest.previousEpisodeId?.let { previous ->
                episodes += previous
                plans[previous]?.manifest?.previousEpisodeId?.let(episodes::add)
            }
            manifest.nextEpisodeId?.let(episodes::add)
        }
    }
    // Identity metadata keeps budgeted resident pixels valid during a two-document reverse.
    // It owns no file or texture lease; explicit navigation still clears the generation.
    return if (pages.keys.any { it.episodeId !in episodes })
        Collections.unmodifiableMap(pages.filterKeys { it.episodeId in episodes }) else pages
}

private class CachedPlan(val request: WorkRequest<EpisodeAccessPlan>, val plan: EpisodeAccessPlan)

private fun cachedPlanPins(retained: Map<EpisodeId, CachedPlan>): List<SessionDemand<*>> =
    retained.values.map { held ->
        // Complete snapshots pin every original until navigation or close, including background
        // suspension. The ready dependency performs no network, decoding or ongoing storage work.
        SessionDemand(held.request) { plan -> check(plan === held.plan) { "Cached plan ownership changed" } }
    }

/** A transient neighbor failure retries on its own instead of parking the boundary. */
private fun releaseRecoveredEpisodeFailures(
    failed: MutableSet<EpisodeId>,
    retryAt: MutableMap<EpisodeId, Long>,
    clock: () -> Long,
) {
    if (failed.isEmpty()) return
    val now = clock()
    val recovered = failed.filter { now >= (retryAt[it] ?: Long.MAX_VALUE) }
    if (recovered.isEmpty()) return
    recovered.forEach { failed -= it; retryAt -= it }
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

private fun pagePriorities(
    state: EngineSessionSnapshot,
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId,
    prepared: Set<PageId>,
    failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean,
    interactionActive: Boolean,
    earlyTransfers: EarlyOriginalTransfers,
): LinkedHashMap<PageId, WorkPriority> {
    val result = linkedMapOf<PageId, WorkPriority>()
    state.requiredDimensions.forEach { result[it] = WorkPriority.FOCUS }
    state.visibleRegions.forEach { region ->
        result.putIfAbsent(
            region.pageId,
            if (region.pageId == state.anchor?.pageId) WorkPriority.FOCUS else WorkPriority.VISIBLE,
        )
    }
    reserveDocumentEndOriginal(state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented, result)
    earlyTransfers.retain(result, prepared, failedReadAheadPages)
    addReadAhead(state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented,
        interactionActive, result)
    return result
}

private fun adjacentPrefetch(
    state: EngineSessionSnapshot,
    positionResolved: Boolean,
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId,
    prepared: Set<PageId>,
    initialPresented: Boolean,
    failed: Set<EpisodeId>,
): EpisodeId? {
    if (!positionResolved) return null
    val episode = state.anchor?.pageId?.episodeId ?: targetEpisode
    val plan = plans[episode] ?: return null
    return nextDocumentToPrepare(plan.manifest, plans, prepared,
        initialPresented, failed)
}
