package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineDrawQuad
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
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
    scope: CoroutineScope,
    coordinator: WorkCoordinatorPort,
    private val planner: EngineTilePlanner,
    private val tiles: EngineTileWork,
    private val uploader: EngineTextureUploader,
    private val pageRequest: (PageId, WorkPriority) -> WorkRequest<StoredPage>,
    private val submitScene: (EngineDrawScene) -> Unit,
    /** Must remove native scene references before returning, including error exits. */
    private val clearScene: suspend () -> Unit,
    reportFailure: (WorkKey<*>, Throwable) -> Unit,
) {
    private val owner = Thread.currentThread()
    private val work = SessionWorkSet(scope, coordinator, reportFailure)
    private val textures = linkedMapOf<EngineTileSpec, EngineTexture>()
    private val failedReadAhead = linkedSetOf<EngineTileSpec>()
    private val closeDone = CompletableDeferred<Unit>()
    private var current: EngineRuntimeSnapshot? = null
    private var epoch = uploader.rendererEpoch
    private var enabled = true
    private var closed = false
    private var processing = false
    private var dirty = false

    fun update(snapshot: EngineRuntimeSnapshot) {
        checkOwner()
        if (closed) return
        require(current == null || current!!.session.sessionId == snapshot.session.sessionId)
        if (current?.session?.generation != snapshot.session.generation || epoch != uploader.rendererEpoch) {
            work.clear()
            textures.clear()
            failedReadAhead.clear()
            epoch = uploader.rendererEpoch
        }
        current = snapshot
        refresh()
    }

    fun enabled(value: Boolean) {
        checkOwner()
        if (closed || enabled == value) return
        enabled = value
        refresh()
    }

    fun rendererChanged() {
        checkOwner()
        current?.let(::update)
    }

    fun retryFailures() {
        checkOwner()
        if (!closed) {
            failedReadAhead.clear()
            work.retryFailures()
            refresh()
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
            var failure: Throwable? = null
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
            failedReadAhead.clear()
            current = null
            val result = failure
            if (result == null) closeDone.complete(Unit) else closeDone.completeExceptionally(result)
        }
        closeDone.await()
    }

    private fun refresh() {
        dirty = true
        if (processing) return
        processing = true
        try {
            while (dirty && !closed) {
                dirty = false
                val snapshot = current ?: break
                val plan = if (enabled) planner.plan(snapshot) else EngineTilePlan(emptyList(), emptyList(), false, 0)
                val wanted = plan.demands.mapTo(linkedSetOf()) { it.tile }
                textures.keys.retainAll(wanted)
                submitScene(scene(snapshot, plan))
                if (!dirty) work.reconcile(plan.demands.filter {
                    it.priority != WorkPriority.NEXT_IMAGE || it.tile !in failedReadAhead
                }.map { demand(snapshot, it) })
            }
        } finally { processing = false }
    }

    private fun demand(snapshot: EngineRuntimeSnapshot, demand: EngineTileDemand): SessionDemand<EngineTexture> {
        val request = tiles.request(pageRequest(demand.tile.pageId, demand.priority), demand.tile, demand.priority)
        return SessionDemand(request, onFailure = if (demand.priority == WorkPriority.NEXT_IMAGE) ({ _: Throwable ->
            failedReadAhead += demand.tile
            refresh()
        }) else null) { texture ->
            if (!closed && current?.session?.generation == snapshot.session.generation &&
                texture.rendererEpoch == uploader.rendererEpoch) {
                require(texture.tile == demand.tile && texture.rendererId == uploader.rendererId)
                failedReadAhead -= demand.tile
                textures[demand.tile] = texture
                refresh()
            }
        }
    }

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
