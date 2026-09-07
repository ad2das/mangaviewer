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

    init {
        require(sessionId > 0L) { "Session id must be positive" }
    }

    override val snapshot: EngineSessionSnapshot
        get() {
            checkOwner()
            return buildSnapshot()
        }

    override fun dispatch(event: SessionEvent): SessionUpdate {
        checkOwner()
        val receipts = when (event) {
            is SessionEvent.PositionResolved -> positionResolved(event)
            is SessionEvent.ManifestResolved ->
                manifestResolved(event.generation, event.manifest, event.navigationKnown)
            is SessionEvent.NavigationResolved -> navigationResolved(event)
            is SessionEvent.DimensionsResolved -> dimensionsResolved(event.generation, event.pageId, event.dimensions)
            is SessionEvent.Input -> input(event.sample)
            is SessionEvent.Resize -> resize(event.viewport)
            is SessionEvent.Navigate -> navigate(event.episodeId)
            SessionEvent.Close -> close()
        }
        return SessionUpdate(buildSnapshot(), immutableList(receipts))
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
        return replayPending(emptySet())
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
        return replayPending(emptySet())
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
        return replayPending(emptySet())
    }

    private fun dimensionsResolved(
        generation: Long,
        pageId: PageId,
        dimensions: PageDimensions,
    ): List<InputReceipt> {
        if (generation != generationValue) return emptyList()
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        require(pageId.episodeId.seriesId == geometry.targetEpisodeId.seriesId) {
            "Page belongs to another source or series"
        }
        require(geometry.page(pageId) != null) { "Dimensions arrived for an unknown page: $pageId" }
        val old = geometry.actualDimensions[pageId]
        require(old == null || old == dimensions) { "Conflicting dimensions for $pageId" }
        if (old == dimensions) return emptyList()
        geometry.setDimensions(pageId, dimensions)
        geometryRevisionValue++
        resolvePositionIfPossible()
        refreshPhase()
        return replayPending(emptySet())
    }

    private fun input(sample: InputSample): List<InputReceipt> {
        require(sample.sequence > lastSequence) { "Input sequence must increase" }
        val acceptedAt = acceptedAt(sample.eventTimeNanos)
        lastSequence = sample.sequence
        inputRevisionValue++
        if (phaseValue == EngineSessionPhase.CLOSED) {
            return listOf(cancelledReceipt(sample, acceptedAt, BigRational.ZERO, clockNanos, geometryRevisionValue))
        }
        val pending = PendingInput(sample, acceptedAt, BigRational.of(sample.deltaScreenUnits))
        if (sample.deltaScreenUnits == 0L) {
            return listOf(appliedReceipt(sample, acceptedAt, clockNanos, geometryRevisionValue))
        }
        pendingInputs.addLast(pending)
        val receipts = replayPending(setOf(sample.sequence)).toMutableList()
        if (pendingInputs.any { it.sample.sequence == sample.sequence } &&
            receipts.none { it.sample.sequence == sample.sequence }
        ) {
            receipts += deferredReceipt(pending, geometryRevisionValue)
        }
        return receipts
    }

    private fun resize(viewport: EngineViewport): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        if (geometry.viewport != viewport) {
            geometry.viewport = viewport
            geometryRevisionValue++
            resolvePositionIfPossible()
            return replayPending(emptySet())
        }
        return emptyList()
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
        geometry.anchor = null
        positionResolved = true
        pendingLegacyPosition = null
        phaseValue = EngineSessionPhase.OPENING
        return receipts
    }

    private fun close(): List<InputReceipt> {
        if (phaseValue == EngineSessionPhase.CLOSED) return emptyList()
        val receipts = cancelPending()
        phaseValue = EngineSessionPhase.CLOSED
        return receipts
    }

    private fun replayPending(forceSequences: Set<Long>): List<InputReceipt> {
        val receipts = mutableListOf<InputReceipt>()
        if (!isReadyForInput()) {
            pendingInputs.firstOrNull()?.let { pending ->
                pending.blocker = readinessBlocker()
                if (forceSequences.contains(pending.sample.sequence)) {
                    receipts += deferredReceipt(pending, geometryRevisionValue)
                }
            }
            return receipts
        }
        while (pendingInputs.isNotEmpty()) {
            val pending = pendingInputs.first
            val beforeApplied = pending.applied
            val beforeRemaining = pending.remaining
            if (pending.remaining.isZero()) {
                pendingInputs.removeFirst()
                receipts += appliedReceipt(pending, clockNanos, geometryRevisionValue)
                continue
            }
            val result = geometry.move(pending.remaining)
            pending.applied += result.consumed
            pending.remaining = result.remaining
            pending.blocker = result.blocker
            val changed = beforeApplied != pending.applied || beforeRemaining != pending.remaining
            when {
                result.boundary != null && pending.remaining.signum() != 0 -> {
                    pendingInputs.removeFirst()
                    receipts += clampedReceipt(
                        pending,
                        clockNanos,
                        geometryRevisionValue,
                        result.boundary,
                        boundaryPage(result.boundary),
                    )
                }
                pending.remaining.isZero() -> {
                    pendingInputs.removeFirst()
                    if (changed || forceSequences.contains(pending.sample.sequence)) {
                        receipts += appliedReceipt(pending, clockNanos, geometryRevisionValue)
                    }
                }
                result.blocker != null -> {
                    if (changed || forceSequences.contains(pending.sample.sequence)) {
                        receipts += deferredReceipt(pending, geometryRevisionValue)
                    }
                    return receipts
                }
                else -> return receipts
            }
        }
        return receipts
    }

    private fun isReadyForInput(): Boolean = phaseValue == EngineSessionPhase.ACTIVE &&
        positionResolved && geometry.anchor != null

    private fun readinessBlocker(): GeometryBlocker? {
        if (!positionResolved) return null
        if (!geometry.manifests.containsKey(geometry.targetEpisodeId)) {
            return GeometryBlocker.Episode(geometry.targetEpisodeId)
        }
        return geometry.requirementsForAnchor().let { requirements ->
            requirements.dimensions.firstOrNull()?.let(GeometryBlocker::Dimension)
                ?: requirements.episodes.firstOrNull()?.let(GeometryBlocker::Episode)
        }
    }

    private fun buildSnapshot(): EngineSessionSnapshot {
        if (phaseValue == EngineSessionPhase.CLOSED) {
            return EngineSessionSnapshot(
                sessionId = sessionId,
                generation = generationValue,
                phase = phaseValue,
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
        return EngineSessionSnapshot(
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
            completeViewport = visible.complete && dimensions.isEmpty() && episodes.isEmpty(),
            anchorDimensions = geometry.anchor?.pageId?.let { geometry.actualDimensions[it] },
        )
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
        val receipts = mutableListOf<InputReceipt>()
        while (pendingInputs.isNotEmpty()) {
            val pending = pendingInputs.removeFirst()
            receipts += cancelledReceipt(
                pending.sample, pending.acceptedAt, pending.applied, clockNanos, geometryRevisionValue,
            )
        }
        return receipts
    }

    private fun boundaryPage(boundary: DocumentBoundary): PageId {
        return checkNotNull(geometry.boundaryPage(boundary)) {
            "A clamped receipt requires a proven document boundary"
        }
    }

    private fun acceptedAt(eventTimeNanos: Long): Long {
        val now = clockNanos()
        require(eventTimeNanos <= now) { "Input event time cannot be in the future" }
        return now
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "EngineSession is owned by its construction thread" }
    }

    private fun SourceAnchor.toState(): AnchorState = AnchorState(
        pageId, BigRational.of(sourceYQ32), viewportOffsetUnits,
    )

}
