package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
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
import ml.melun.mangaview.source.AdjacentEpisodes

data class EngineSessionRuntimeDiagnosticSnapshot(
    val runtime: EngineRuntimeSnapshot,
    val work: SessionWorkOwnership,
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
    private val requireVisualReadiness: Boolean = false,
) {
    private val owner = Thread.currentThread()
    private val work = SessionWorkSet(scope, coordinator, reportFailure)
    private val plans = linkedMapOf<EpisodeId, EpisodeAccessPlan>()
    private val pages = linkedMapOf<PageId, PageContentIdentity>()
    private val prepared = linkedSetOf<PageId>()
    private val failedReadAheadPages = linkedSetOf<PageId>()
    private val failedReadAheadEpisodes = linkedSetOf<EpisodeId>()
    private val receipts = mutableListOf<InputReceipt>()
    private var targetEpisode = initialEpisode
    private var positionResolved = false
    private var started = false
    private var foreground = true
    private var closed = false
    private var processing = false
    private var dirty = false
    private var visualReadyState: EngineSessionSnapshot? = null

    val snapshot: EngineRuntimeSnapshot get() {
        checkOwner()
        return EngineRuntimeSnapshot(session.snapshot, immutableMap(plans), immutableMap(pages))
    }

    fun open() {
        checkOwner()
        if (closed || started) return
        started = true
        process(SessionUpdate(session.snapshot))
    }

    fun input(sample: InputSample): SessionUpdate {
        checkOwner()
        return session.dispatch(SessionEvent.Input(sample)).also(::process)
    }

    fun resize(viewport: EngineViewport) {
        checkOwner()
        if (!closed) process(session.dispatch(SessionEvent.Resize(viewport)))
    }

    fun navigate(episodeId: EpisodeId) {
        checkOwner()
        if (closed) return
        val update = session.dispatch(SessionEvent.Navigate(episodeId))
        work.clear()
        plans.clear()
        pages.clear()
        prepared.clear()
        failedReadAheadPages.clear()
        failedReadAheadEpisodes.clear()
        positionResolved = true
        targetEpisode = episodeId
        process(update)
    }

    fun foreground(enabled: Boolean) {
        checkOwner()
        if (closed || foreground == enabled) return
        foreground = enabled
        if (!enabled) { pages.clear(); prepared.clear(); visualReadyState = null }
        process(SessionUpdate(session.snapshot))
    }

    fun visualReady(state: EngineSessionSnapshot, ready: Boolean) {
        checkOwner()
        val current = session.snapshot
        if (closed || state.generation != current.generation || state.inputRevision != current.inputRevision ||
            state.geometryRevision != current.geometryRevision) return
        if (ready == isVisualReady(current)) return
        visualReadyState = if (ready) state else null
        if (started) process(SessionUpdate(current))
    }

    private fun isVisualReady(state: EngineSessionSnapshot): Boolean = visualReadyState?.let {
        it.generation == state.generation && it.inputRevision == state.inputRevision &&
            it.geometryRevision == state.geometryRevision
    } == true

    fun retryFailures() {
        checkOwner()
        if (!closed) {
            failedReadAheadPages.clear()
            failedReadAheadEpisodes.clear()
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
        return EngineSessionRuntimeDiagnosticSnapshot(snapshot, work.ownership())
    }

    fun releaseStartupInput() {
        checkOwner()
        if (closed) return
        val before = session.snapshot
        val update = session.dispatch(SessionEvent.ReleaseStartupInput)
        if (update.receipts.isNotEmpty() || update.snapshot != before) process(update)
    }

    suspend fun close() {
        checkOwner()
        if (!closed) {
            closed = true
            pages.clear()
            prepared.clear()
            process(session.dispatch(SessionEvent.Close))
        }
        work.close()
        plans.clear()
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
                val demand = if (started && foreground && !closed) demands(state) else emptyList()
                val batch = receipts.toList()
                receipts.clear()
                reportUpdate(snapshot, batch)
                if (!dirty) work.reconcile(demand)
            }
        } finally {
            processing = false
        }
    }

    private fun demands(state: EngineSessionSnapshot): List<SessionDemand<*>> {
        val result = mutableListOf<SessionDemand<*>>()
        val generation = state.generation
        if (!positionResolved) result += SessionDemand(source.position(targetEpisode)) { position ->
            if (isCurrent(generation)) {
                positionResolved = true
                process(session.dispatch(SessionEvent.PositionResolved(generation, position.anchor, position.legacy)))
            }
        }
        val wantedPages = pagePriorities(state)
        pages.keys.retainAll(wantedPages.keys)
        val wantedEpisodes = linkedMapOf<EpisodeId, WorkPriority>()
        state.requiredEpisodes.forEach { wantedEpisodes[it] = WorkPriority.FOCUS }
        wantedPages.forEach { (id, priority) ->
            if (id.episodeId !in plans) wantedEpisodes[id.episodeId] = priority
        }
        adjacentPrefetch(state)?.let { if (it !in plans) wantedEpisodes.putIfAbsent(it, WorkPriority.NEXT_EPISODE) }
        wantedEpisodes.forEach { (id, priority) ->
            if (id !in plans) result += SessionDemand(source.episode(id, priority), onFailure =
                if (priority == WorkPriority.NEXT_EPISODE) ({ _: Throwable ->
                    failedReadAheadEpisodes += id
                    process(SessionUpdate(session.snapshot))
                }) else null) { plan ->
                if (isCurrent(generation)) acceptPlan(generation, id, plan)
            }
        }
        state.requiredNavigation.forEach { id ->
            if (plans[id]?.navigationKnown == false) {
                result += SessionDemand(source.navigation(id, WorkPriority.INTERACTIVE)) { navigation ->
                    if (isCurrent(generation)) acceptNavigation(generation, id, navigation)
                }
            }
        }
        wantedPages.forEach { (id, priority) ->
            val plan = plans[id.episodeId] ?: return@forEach
            result += SessionDemand(source.page(plan, id, priority), onFailure =
                if (priority == WorkPriority.NEXT_IMAGE || priority == WorkPriority.NEXT_EPISODE) ({ _: Throwable ->
                    failedReadAheadPages += id
                    process(SessionUpdate(session.snapshot))
                }) else null) { page ->
                if (isCurrent(generation)) acceptPage(generation, id, plan, page)
            }
        }
        return result
    }

    private fun acceptPlan(generation: Long, expected: EpisodeId, plan: EpisodeAccessPlan) {
        require(plan.manifest.id == expected)
        val update = session.dispatch(SessionEvent.ManifestResolved(generation, plan.manifest, plan.navigationKnown))
        plans[expected] = plan
        process(update)
    }

    private fun acceptPage(generation: Long, expected: PageId, plan: EpisodeAccessPlan, page: StoredPage) {
        require(page.pageId == expected && page.contentRevision == plan.contentRevision)
        prepared += expected
        failedReadAheadPages -= expected
        val update = session.dispatch(SessionEvent.DimensionsResolved(generation, expected, page.dimensions))
        pages[expected] = PageContentIdentity(expected, page.contentRevision, page.sha256, page.dimensions, page.byteCount)
        process(update)
    }

    private fun acceptNavigation(generation: Long, id: EpisodeId, navigation: AdjacentEpisodes) {
        val previous = requireNotNull(plans[id])
        val update = session.dispatch(SessionEvent.NavigationResolved(generation, id, navigation.previous, navigation.next))
        plans[id] = EpisodeAccessPlan(previous.manifest.copy(previousEpisodeId = navigation.previous,
            nextEpisodeId = navigation.next), previous.contentRevision, previous.documentSha256,
            previous.finalDocumentUrl, previous.authEpoch, previous.pages, previous.prerequisites, navigationKnown = true)
        process(update)
    }

    private fun pagePriorities(state: EngineSessionSnapshot): LinkedHashMap<PageId, WorkPriority> {
        val result = linkedMapOf<PageId, WorkPriority>()
        state.requiredDimensions.forEach { result[it] = WorkPriority.FOCUS }
        state.visibleRegions.forEach { region ->
            result.putIfAbsent(region.pageId, if (region.pageId == state.anchor?.pageId) WorkPriority.FOCUS else WorkPriority.VISIBLE)
        }
        addReadAhead(state, result)
        return result
    }

    private fun addReadAhead(state: EngineSessionSnapshot, result: LinkedHashMap<PageId, WorkPriority>) {
        val anchor = state.anchor?.pageId ?: return
        val manifest = plans[anchor.episodeId]?.manifest ?: return
        val index = manifest.pages.indexOfFirst { it.id == anchor }
        if (index < 0) return
        // Keep a small prepared neighborhood available to the tile planner. Originals
        // elsewhere stay in disk storage; never retain an entire episode's textures.
        for (offset in listOf(1, 2, -1)) {
            val id = manifest.pages.getOrNull(index + offset)?.id ?: continue
            if (id in prepared) result.putIfAbsent(id, WorkPriority.NEXT_IMAGE)
        }
        if (!state.completeViewport || (requireVisualReadiness && !isVisualReady(state)) ||
            result.keys.any { it !in pages }) return
        // One speculative original at a time, forward first. Previously viewed pages
        // are filled only after the forward pages; reverse input remains foreground.
        val pending = ((index + 1 until manifest.pages.size).asSequence() +
            (index - 1 downTo 0).asSequence()).map { manifest.pages[it].id }.firstOrNull { it !in prepared && it !in failedReadAheadPages }
        if (pending != null) {
            result.putIfAbsent(pending, WorkPriority.NEXT_IMAGE)
            return
        }
        if (manifest.pages.any { it.id !in prepared }) return
        val next = manifest.nextEpisodeId?.let { plans[it]?.manifest } ?: return
        next.pages.take(2).filter { it.id in prepared }.forEach {
            result.putIfAbsent(it.id, WorkPriority.NEXT_EPISODE)
        }
        next.pages.firstOrNull { it.id !in prepared && it.id !in failedReadAheadPages }?.id?.let {
            result.putIfAbsent(it, WorkPriority.NEXT_EPISODE)
        }
    }

    private fun adjacentPrefetch(state: EngineSessionSnapshot): EpisodeId? {
        val episode = state.anchor?.pageId?.episodeId ?: return null
        val plan = plans[episode] ?: return null
        if (!state.completeViewport || (requireVisualReadiness && !isVisualReady(state)) ||
            plan.manifest.pages.any { it.id !in prepared }) return null
        // Resolve and attach the next episode before reaching its boundary, only
        // after every current-episode original has completed preparation.
        return plan.manifest.nextEpisodeId?.takeUnless { it in failedReadAheadEpisodes }
    }

    private fun isCurrent(generation: Long) = !closed && generation == session.snapshot.generation
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Session runtime is owner-thread confined" }
    private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
}
