package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionPort
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SessionUpdate
import ml.melun.mangaview.engine.api.SourceAnchor
import java.util.ArrayDeque

/**
 * A page-geometry result for a page the geometry does not hold: its document was pruned while the
 * demand was in flight, or has not been delivered yet. The session never accepts geometry outside
 * its accepted manifests; the runtime treats this specific rejection as an inert late result.
 */
internal class UnknownPageDimensionsException(pageId: PageId) :
    IllegalArgumentException("Dimensions arrived for an unknown page: $pageId")

/** Main-thread-owned reducer for one reading session. */
class EngineSession(
    private val sessionId: Long,
    initialEpisodeId: EpisodeId,
    initialViewport: EngineViewport,
    private val clockNanos: () -> Long,
) : EngineSessionPort {
    private val ownerThread: Thread = Thread.currentThread()
    private val pendingInputs = ArrayDeque<PendingInput>()
    private val geometry = DocumentGeometry(initialEpisodeId, initialViewport)
    private var positionResolved = false
    private var pendingLegacyPosition: ReadingPosition? = null
    private var generationValue = 1L
    private var geometryRevisionValue = 0L
    private var inputRevisionValue = 0L
    private var lastSequence = 0L
    private var phaseValue = EngineSessionPhase.OPENING
    private var startupInputHeld = false
    private var replayYielded = false
    private val presentation = SessionViewportReadiness()
    private var publishedSnapshot: EngineSessionSnapshot? = null
    /** Last geometry blocker a pending input met, so a stall transition is logged exactly once. */
    private var lastStallBlocker: GeometryBlocker? = null

    init {
        require(sessionId > 0L) { "Session id must be positive" }
    }

    override val snapshot: EngineSessionSnapshot
        get() {
            checkOwner()
            return publishedSnapshot ?: buildSnapshot()
        }

    override val inputReplayPending: Boolean
        get() { checkOwner(); return replayYielded }

    override fun dispatch(event: SessionEvent): SessionUpdate {
        checkOwner()
        // Readers share the completed immutable state; reads during reduction stay uncached.
        publishedSnapshot = null
        val receipts = when (event) {
            is SessionEvent.PositionResolved -> positionResolved(event)
            is SessionEvent.ManifestResolved ->
                manifestResolved(event.generation, event.manifest, event.navigationKnown)
            is SessionEvent.NavigationResolved -> navigationResolved(event)
            is SessionEvent.DimensionsResolved ->
                dimensionsResolved(event.generation, event.pageId, event.dimensions, event.replacesPlaceholder)
            is SessionEvent.Input -> input(event.sample)
            is SessionEvent.ContinueInput -> if (event.generation == generationValue && replayYielded) {
                replayPending(NO_FORCED_SEQUENCE)
            } else emptyList()
            SessionEvent.ReleaseStartupInput -> releaseStartupInput()
            is SessionEvent.ViewportReady -> viewportReady(event.snapshot)
            is SessionEvent.Resize -> resize(event.viewport)
            is SessionEvent.SetSplitMode -> setSplitMode(event.enabled)
            is SessionEvent.Navigate, is SessionEvent.SeekPage -> reposition(event)
            SessionEvent.Close -> close()
        }
        return SessionUpdate(buildSnapshot().also { publishedSnapshot = it }, immutableList(receipts))
    }

    private fun positionResolved(event: SessionEvent.PositionResolved): List<InputReceipt> {
        if (event.generation != generationValue || positionResolved) return emptyList()
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        event.anchor?.let { validateAnchor(geometry, it) }
        event.legacyPosition?.let { validateLegacy(geometry, it) }
        positionResolved = true
        pendingLegacyPosition = event.legacyPosition
        geometry.anchor = event.anchor?.toState()
        if (event.anchor != null) pendingLegacyPosition = null
        resolvePositionIfPossible()
        refreshPhase()
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun manifestResolved(
        generation: Long,
        manifest: EpisodeManifest,
        navigationKnown: Boolean,
    ): List<InputReceipt> {
        if (generation != generationValue) return emptyList()
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        validateManifest(geometry, manifest, navigationKnown)
        val existing = geometry.manifests[manifest.id]
        if (existing == null) {
            geometry.addManifest(manifest, navigationKnown)
            geometryRevisionValue++
        }
        validateCurrentAnchor(geometry)
        resolvePositionIfPossible()
        refreshPhase()
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun navigationResolved(event: SessionEvent.NavigationResolved): List<InputReceipt> {
        if (event.generation != generationValue) return emptyList()
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        validateNavigationResolution(
            geometry,
            event.episodeId,
            event.previousEpisodeId,
            event.nextEpisodeId,
        )
        if (geometry.isNavigationKnown(event.episodeId)) return emptyList()
        geometry.resolveNavigation(event.episodeId, event.previousEpisodeId, event.nextEpisodeId)
        geometryRevisionValue++
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun dimensionsResolved(
        generation: Long,
        pageId: PageId,
        dimensions: PageDimensions,
        replacesPlaceholder: Boolean,
    ): List<InputReceipt> {
        if (generation != generationValue) return emptyList()
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        require(pageId.episodeId.seriesId == geometry.targetEpisodeId.seriesId) {
            "Page belongs to another source or series"
        }
        // A read-ahead demand can outlive its document: the geometry prunes a manifest once the
        // reading position leaves its window while the demand is still in flight. The session
        // keeps its invariant — only accepted manifest pages carry geometry — and signals this
        // specific rejection so the runtime can treat the late result as inert instead of
        // failing the containing work.
        if (geometry.page(pageId) == null) throw UnknownPageDimensionsException(pageId)
        if (!geometry.applyResolvedDimensions(pageId, dimensions, replacesPlaceholder)) return emptyList()
        geometryRevisionValue++
        resolvePositionIfPossible()
        refreshPhase()
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun input(sample: InputSample): List<InputReceipt> {
        require(sample.sequence > lastSequence) { "Input sequence must increase" }
        val acceptedAt = acceptedAt(sample.eventTimeNanos, clockNanos)
        lastSequence = sample.sequence
        inputRevisionValue++
        if (phaseValue == EngineSessionPhase.CLOSED) {
            return listOf(cancelledReceipt(sample, acceptedAt, BigRational.ZERO, clockNanos, geometryRevisionValue))
        }
        val pending = PendingInput(sample, acceptedAt, BigRational.of(sample.deltaScreenUnits))
        if (sample.deltaScreenUnits == 0L) {
            return listOf(appliedReceipt(sample, acceptedAt, clockNanos, geometryRevisionValue))
        }
        // A stalled geometry must not let queued movement grow without bound: the oldest
        // unapplied samples are cancelled so the newest window of input stays responsive.
        val receipts = mutableListOf<InputReceipt>()
        while (pendingInputs.size >= MAX_PENDING_INPUTS) {
            val dropped = pendingInputs.removeFirst()
            receipts += cancelledReceipt(
                dropped.sample, dropped.acceptedAt, dropped.applied, clockNanos, geometryRevisionValue,
            )
        }
        pendingInputs.addLast(pending)
        receipts += replayPending(sample.sequence)
        if (pendingInputs.any { it.sample.sequence == sample.sequence } &&
            receipts.none { it.sample.sequence == sample.sequence }
        ) {
            receipts += deferredReceipt(pending, geometryRevisionValue)
        }
        return receipts
    }

    /** Must be engaged before input; pending samples remain ordinary reducer-owned inputs. */
    fun engageStartupInputBarrier() {
        checkOwner()
        check(phaseValue == EngineSessionPhase.OPENING && lastSequence == 0L && pendingInputs.isEmpty())
        startupInputHeld = true
    }

    private fun releaseStartupInput(): List<InputReceipt> {
        if (!startupInputHeld || phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        startupInputHeld = false
        return replayPending(NO_FORCED_SEQUENCE)
    }

    /** Opt in before opening: ready viewport pixels permit the next movement. */
    fun engageViewportReadinessBarrier() {
        checkOwner()
        check(phaseValue == EngineSessionPhase.OPENING && lastSequence == 0L && pendingInputs.isEmpty())
        presentation.engage()
    }

    private fun viewportReady(presented: EngineSessionSnapshot): List<InputReceipt> {
        if (!presentation.release(presented, buildSnapshot())) return emptyList()
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun resize(viewport: EngineViewport): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        if (geometry.viewport != viewport) {
            geometry.viewport = viewport
            geometryRevisionValue++
            presentation.invalidate()
            resolvePositionIfPossible()
            return replayPending(NO_FORCED_SEQUENCE)
        }
        return emptyList()
    }

    private fun setSplitMode(enabled: Boolean): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED || geometry.splitMode == enabled) return emptyList()
        geometry.applySplitMode(enabled)
        geometryRevisionValue++
        presentation.invalidate()
        validateCurrentAnchor(geometry)
        return replayPending(NO_FORCED_SEQUENCE)
    }

    private fun navigate(episodeId: EpisodeId): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        require(episodeId.seriesId == geometry.targetEpisodeId.seriesId) {
            "Cannot navigate to another source or series"
        }
        val receipts = cancelPending()
        generationValue++
        geometryRevisionValue++
        geometry.targetEpisodeId = episodeId
        geometry.manifests.clear()
        geometry.navigationKnown.clear()
        geometry.actualDimensions.clear()
        geometry.pruneMetrics()
        geometry.prunePageIndex()
        geometry.anchor = null
        positionResolved = true
        pendingLegacyPosition = null
        phaseValue = EngineSessionPhase.OPENING
        presentation.invalidate()
        return receipts
    }

    /** Navigate replaces the document; SeekPage moves to the top of a page the geometry holds. */
    private fun reposition(event: SessionEvent): List<InputReceipt> {
        if (event is SessionEvent.Navigate) return navigate(event.episodeId)
        val pageId = (event as SessionEvent.SeekPage).pageId
        if (phaseValue != EngineSessionPhase.ACTIVE || geometry.page(pageId) == null) return emptyList()
        return cancelPending().also { geometry.anchor = AnchorState(pageId, BigRational.ZERO, 0L); presentation.jumped() }
    }

    private fun close(): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        val receipts = cancelPending()
        phaseValue = EngineSessionPhase.CLOSED
        return receipts
    }

    private fun replayPending(forceSequence: Long): List<InputReceipt> {
        replayYielded = false
        val receipts = mutableListOf<InputReceipt>()
        if (startupInputHeld || presentation.held) return receipts
        receiptsUntilReady(
            phaseValue, positionResolved, geometry.anchor, geometry,
            pendingInputs.firstOrNull(), forceSequence, geometryRevisionValue,
        )?.let { return it }
        replayYielded = replayWithinBudget(clockNanos, { pendingInputs.isNotEmpty() }) {
            advancePending(pendingInputs.first, forceSequence, receipts)
        }
        return receipts
    }

    private fun advancePending(pending: PendingInput, forceSequence: Long, receipts: MutableList<InputReceipt>): Boolean {
        val beforeApplied = pending.applied
        val beforeRemaining = pending.remaining
        if (pending.remaining.isZero()) {
            pendingInputs.removeFirst()
            receipts += appliedReceipt(pending, clockNanos, geometryRevisionValue)
            return false
        }
        val result = geometry.move(pending.remaining)
        pending.applied += result.consumed
        pending.remaining = result.remaining
        pending.blocker = result.blocker
        if (result.blocker != lastStallBlocker) {
            lastStallBlocker = result.blocker
            result.blocker?.let {
                System.err.println("SessionStall blocker=$it generation=$generationValue pending=${pendingInputs.size}")
            }
        }
        val changed = beforeApplied != pending.applied || beforeRemaining != pending.remaining
        presentation.moved(result.consumed)
        when {
            result.boundary != null && pending.remaining.signum() != 0 -> {
                pendingInputs.removeFirst()
                receipts += clampedReceipt(
                    pending,
                    clockNanos,
                    geometryRevisionValue,
                    result.boundary,
                    boundaryPage(geometry, result.boundary),
                )
            }
            pending.remaining.isZero() -> {
                pendingInputs.removeFirst()
                if (changed || pending.sample.sequence == forceSequence) {
                    receipts += appliedReceipt(pending, clockNanos, geometryRevisionValue)
                }
            }
            result.blocker != null -> {
                if (changed || pending.sample.sequence == forceSequence) {
                    receipts += deferredReceipt(pending, geometryRevisionValue)
                }
                return true
            }
            else -> return true
        }
        return presentation.held
    }

    private fun buildSnapshot(): EngineSessionSnapshot {
        if (phaseValue == EngineSessionPhase.CLOSED) {
            return closedSessionSnapshot(
                sessionId, generationValue, geometry, geometryRevisionValue, inputRevisionValue, presentation.revision,
            )
        }
        val visible = geometry.visible()
        val dimensions = visible.requirements.dimensions.toMutableSet()
        val episodes = visible.requirements.episodes.toMutableSet()
        val navigation = visible.requirements.navigation.toMutableSet()
        if (!geometry.manifests.containsKey(geometry.targetEpisodeId)) {
            episodes += geometry.targetEpisodeId
        }
        pendingLegacyPosition?.let { legacy ->
            if (geometry.actualDimensions[legacy.pageId] == null) dimensions += legacy.pageId
        }
        pendingInputs.forEach { pending ->
            when (val blocker = pending.blocker) {
                is GeometryBlocker.Dimension -> dimensions += blocker.pageId
                is GeometryBlocker.Episode -> episodes += blocker.episodeId
                is GeometryBlocker.Navigation -> navigation += blocker.episodeId
                null -> Unit
            }
        }
        val snapshot = EngineSessionSnapshot(
            sessionId = sessionId,
            generation = generationValue,
            phase = phaseValue,
            viewport = geometry.viewport,
            anchor = geometry.publicAnchor(),
            geometryRevision = geometryRevisionValue,
            inputRevision = inputRevisionValue,
            pendingInputCount = pendingInputs.size,
            visibleRegions = immutableList(visible.regions),
            requiredDimensions = immutableSet(dimensions),
            requiredEpisodes = immutableSet(episodes),
            requiredNavigation = immutableSet(navigation),
            completeViewport = visible.complete,
            anchorDimensions = geometry.anchor?.pageId?.let { geometry.actualDimensions[it] },
            movementRevision = presentation.revision,
            splitMode = geometry.splitMode,
        )
        // A long in-place read must not let geometry maps grow with every episode crossed.
        geometry.retainWindow(geometry.anchor?.pageId?.episodeId, geometry.targetEpisodeId)
        return snapshot
    }

    private fun resolvePositionIfPossible() {
        if (!positionResolved || geometry.anchor != null) return
        val legacy = pendingLegacyPosition
        if (legacy != null) {
            val dimensions = geometry.actualDimensions[legacy.pageId] ?: return
            geometry.anchor = AnchorState(
                legacy.pageId,
                screenToSourceQ32(
                    BigRational.of(legacy.offsetInPageUnits), dimensions.widthPx, geometry.viewport.widthPx,
                ),
                legacy.viewportOffsetUnits,
            )
            pendingLegacyPosition = null
            validateCurrentAnchor(geometry)
            return
        }
        val manifest = geometry.manifests[geometry.targetEpisodeId] ?: return
        val first = manifest.pages.first()
        geometry.anchor = AnchorState(first.id, BigRational.ZERO, 0L)
    }

    private fun refreshPhase() {
        if (phaseValue == EngineSessionPhase.CLOSED) return
        phaseValue = if (positionResolved && geometry.manifests.containsKey(geometry.targetEpisodeId) &&
            geometry.anchor != null
        ) EngineSessionPhase.ACTIVE else EngineSessionPhase.OPENING
    }

    private fun cancelPending(): List<InputReceipt> {
        replayYielded = false
        val receipts = mutableListOf<InputReceipt>()
        while (pendingInputs.isNotEmpty()) {
            val pending = pendingInputs.removeFirst()
            receipts += cancelledReceipt(
                pending.sample, pending.acceptedAt, pending.applied, clockNanos, geometryRevisionValue,
            )
        }
        return receipts
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "EngineSession is owned by its construction thread" }
    }
}

/**
 * Applies one page's resolved geometry; placeholder dimensions are replaced by the recovered
 * original's, anything else must match. False when the dimensions were already known.
 */
private fun DocumentGeometry.applyResolvedDimensions(
    pageId: PageId,
    dimensions: PageDimensions,
    replacesPlaceholder: Boolean,
): Boolean {
    val old = actualDimensions[pageId]
    require(old == null || old == dimensions || replacesPlaceholder) { "Conflicting dimensions for $pageId" }
    if (old == dimensions) return false
    if (old != null) replaceDimensions(pageId, old, dimensions) else setDimensions(pageId, dimensions)
    return true
}

private fun SourceAnchor.toState(): AnchorState = AnchorState(
    pageId, BigRational.of(sourceYQ32), viewportOffsetUnits,
)

private fun acceptedAt(eventTimeNanos: Long, clockNanos: () -> Long): Long {
    val now = clockNanos()
    require(eventTimeNanos <= now) { "Input event time cannot be in the future" }
    return now
}

/**
 * The queued movement bound. A burst that the geometry can eventually drain (a held opening, a
 * slow document) keeps its full FIFO contract; beyond this only the newest window is retained.
 */
internal const val MAX_PENDING_INPUTS = 1_024

private fun closedSessionSnapshot(
    sessionId: Long, generationValue: Long, geometry: DocumentGeometry, geometryRevisionValue: Long,
    inputRevisionValue: Long, movementRevision: Long,
): EngineSessionSnapshot {
    return EngineSessionSnapshot(
        sessionId = sessionId,
        generation = generationValue,
        phase = EngineSessionPhase.CLOSED,
        viewport = geometry.viewport,
        anchor = geometry.publicAnchor(),
        geometryRevision = geometryRevisionValue,
        inputRevision = inputRevisionValue,
        pendingInputCount = 0,
        visibleRegions = immutableList(emptyList()),
        requiredDimensions = immutableSet(emptySet()),
        requiredEpisodes = immutableSet(emptySet()),
        requiredNavigation = immutableSet(emptySet()),
        completeViewport = false,
        anchorDimensions = geometry.anchor?.pageId?.let { geometry.actualDimensions[it] },
        movementRevision = movementRevision,
        splitMode = geometry.splitMode,
    )
}

private fun receiptsUntilReady(
    phase: EngineSessionPhase,
    positionResolved: Boolean,
    anchor: AnchorState?,
    geometry: DocumentGeometry,
    firstPending: PendingInput?,
    forceSequence: Long,
    geometryRevision: Long,
): List<InputReceipt>? {
    if (isReadyForInput(phase, positionResolved, anchor)) return null
    val receipts = mutableListOf<InputReceipt>()
    firstPending?.let { pending ->
        pending.blocker = readinessBlocker(positionResolved, geometry)
        if (pending.sample.sequence == forceSequence) {
            receipts += deferredReceipt(pending, geometryRevision)
        }
    }
    return receipts
}

private fun boundaryPage(geometry: DocumentGeometry, boundary: DocumentBoundary): PageId =
    checkNotNull(geometry.boundaryPage(boundary)) {
        "A clamped receipt requires a proven document boundary"
    }

/** [EngineSession.replayPending] force argument meaning no sequence needs a receipt: sequences are positive. */
private const val NO_FORCED_SEQUENCE = 0L
