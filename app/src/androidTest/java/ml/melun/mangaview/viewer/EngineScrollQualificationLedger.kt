package ml.melun.mangaview.viewer

import java.io.File
import ml.melun.mangaview.activity.EngineViewerScreen
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.viewer.runtime.EngineFrameObservation
import ml.melun.mangaview.viewer.runtime.EngineInputObservation
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Frequent light in-memory drains of the production rings. Ring capacities stay at their
 * production values (frames/inputs 512, motion 8192, legacy presentations 8192) and lost-count
 * checks stay enabled; only cursors advance, nothing is disabled or enlarged.
 *
 * Moving-display evidence comes from `engineFramesSince`: actual DISPLAY_PRESENT frames with a
 * positive, unique, monotonic native timestamp and a changed scene anchor/source position.
 * Endpoint and boundary stages separate a setup region from a measured local window; the
 * measured region counts unique frames and directional transitions only after activation.
 * The packed legacy presentation ring is an optional diagnostic and is never a gating counter.
 */
internal class EngineScrollQualificationLedger(
    private val output: File,
    private val episode: EpisodeId,
    private val pageBoundsProvider: () -> Pair<PageId, PageId>?,
    private val nextEpisodeProvider: () -> EpisodeId?,
    private val initialStage: String = EngineScrollQualificationPolicy.LOADING,
) {
    data class StageBoundary(
        val stage: String,
        val startedAtNanos: Long,
        var endedAtNanos: Long = 0L,
        var gestures: Int = 0,
        var setupGestures: Int = 0,
        var movingDisplayFrames: Long = 0L,
        var completeCoverageMovingDisplayFrames: Long = 0L,
        var setupMovingDisplayFrames: Long = 0L,
        var measuredFromNanos: Long = 0L,
        var measuredMovingDisplayFrames: Long = 0L,
        var measuredDirectionChanges: Long = 0L,
        var displayPresentFrames: Long = 0L,
        var readableDisplayPresentFrames: Long = 0L,
        var firstDisplayPresentAtNanos: Long = 0L,
        var targetMovingFrames: Int = 0,
        var budgetExhausted: Boolean = false,
    )

    private val presentationChunks = mutableListOf<LongArray>()
    private val frameObservations = mutableListOf<EngineFrameObservation>()
    private val inputObservations = mutableListOf<EngineInputObservation>()
    private val motionPacked = mutableListOf<LongArray>()
    private val motionAppliedAt = mutableListOf<LongArray>()
    private val gestureWindows = linkedMapOf<Long, LongRange>()
    private val windowFramePairs = mutableListOf<LongArray>()
    private val nativeTimestamps = linkedMapOf<String, MutableSet<Long>>()
    private val lastNativeTimestamp = linkedMapOf<String, Long>()
    private val loadingAttempts = mutableListOf<JSONObject>()

    private var presentationCursor = 0L
    private var frameCursor = 0L
    private var inputCursor = 0L
    private var motionCursor = 0L
    private var windowPairsSeen = 0

    private var legacyPresentationDropped = false
    private var legacyPresentationRows = 0L
    private var integrityInputLost = 0L
    private var integrityFrameLost = 0L
    private var deferredInputStreak = 0L
    private var lastResolvedInputPendingCount = 0
    private var latestPendingInputCount = 0
    private var integrityMotionOverwritten = false
    private var integrityGestureWindowsTruncated = false
    private var integrityWindowFramesDropped = false
    private var invalidDisplayTimestamps = 0L
    private var duplicateNativeTimestamps = 0L
    private var nonMonotonicNativeTimestamps = 0L

    private val stages = mutableListOf<StageBoundary>()
    private var active: StageBoundary? = null
    private var lastMotionKey: String? = null
    private var lastMeasuredToken = 0L
    private var lastMeasuredCoordinate: Long? = null
    private var lastDirectionSign = 0

    var startToken = 0L
        private set
    var startTokenAtNanos = 0L
        private set
    var endToken = 0L
        private set
    var endTokenAtNanos = 0L
        private set
    var lastObservedToken = 0L
        private set
    var firstNextSourceToken: Long? = null
        private set
    var firstNextSourceAtNanos = 0L
        private set
    var firstNextAnchorToken: Long? = null
        private set
    var firstNextAnchorAtNanos = 0L
        private set
    var currentAndNextShareViewport = false
        private set
    var shareViewportAtNanos = 0L
        private set
    var completeNextFrameObserved = false
        private set
    var completeNextFrameAtNanos = 0L
        private set
    var boundaryFrames = 0L
        private set
    var firstCompleteDisplayPresentAtNanos = 0L
        private set
    private var latestVisibleTargetContent = false
    private var latestVisibleNextContent = false
    /**
     * Frames submitted before this floor cannot latch fresh next-boundary observations. The engine
     * delivers completed frames with their own submittedAtNanos, so frames already in flight when
     * [resetBoundaryObservations] ran would otherwise latch pre-stage submission times, and the
     * one-shot latches could then never satisfy the stage milestone's >= activeStartedAtNanos test.
     */
    private var boundaryLatchFloorNanos = 0L

    /** Latest-frame viewport state for the NEXT_BOUNDARY retreat: pre-seam is target visible without the next episode. */
    @Synchronized fun latestBoundaryVisibility(): Pair<Boolean, Boolean> =
        latestVisibleTargetContent to latestVisibleNextContent

    var stoppedAtNanos = 0L
        private set

    private val stagesJson = JSONArray()

    init {
        val planned = EngineScrollQualificationPolicy.stage(initialStage)
        active = StageBoundary(initialStage, System.nanoTime(),
            targetMovingFrames = planned.targetMovingFrames)
    }

    /** Advances every ring cursor without writing files; safe after each gesture and poll. */
    fun drain(viewer: EngineViewerScreen, windowRecorder: ViewerWindowFrameRecorder) {
        drainFrames(viewer)
        drainInputs(viewer)
        drainMotion(viewer)
        drainGestureWindows(viewer)
        drainWindowFrames(windowRecorder)
        drainLegacyPresentations(viewer)
    }

    private fun drainLegacyPresentations(viewer: EngineViewerScreen) {
        val batch = viewer.presentationEvidenceSince(presentationCursor)
        if (batch.dropped) legacyPresentationDropped = true
        if (batch.packed.isNotEmpty()) presentationChunks += batch.packed
        legacyPresentationRows += batch.packed.size / NativePresentationEvidencePacking.STRIDE
        presentationCursor = batch.nextSequence
    }

    private fun drainFrames(viewer: EngineViewerScreen) {
        val batch = viewer.engineFramesSince(frameCursor)
        integrityFrameLost += batch.lostCount
        frameObservations += batch.observations
        frameCursor = batch.latestOrdinal
        batch.observations.forEach(::observeFrame)
    }

    private fun drainInputs(viewer: EngineViewerScreen) {
        val batch = viewer.engineInputObservationsSince(inputCursor)
        integrityInputLost += batch.lostCount
        inputObservations += batch.observations
        inputCursor = batch.latestOrdinal
        for (observation in batch.observations) {
            latestPendingInputCount = observation.pendingInputCount
            if (observation.receipt.outcome == InputOutcome.DEFERRED) {
                deferredInputStreak += 1L
            } else {
                deferredInputStreak = 0L
                lastResolvedInputPendingCount = observation.pendingInputCount
            }
        }
    }

    private fun drainMotion(viewer: EngineViewerScreen) {
        val batch = viewer.motionFramesSince(motionCursor)
        if (batch.dropped) integrityMotionOverwritten = true
        if (batch.packed.isNotEmpty()) {
            motionPacked += batch.packed
            motionAppliedAt += batch.applicationTimestamps
        }
        motionCursor = batch.nextSequence
    }

    private fun drainGestureWindows(viewer: EngineViewerScreen) {
        val current = viewer.gestureWindowsSnapshot()
        val previousLast = gestureWindows.values.lastOrNull()
        if (current.size == 64 && previousLast != null && current.none { it.first == previousLast.first }) {
            integrityGestureWindowsTruncated = true
        }
        current.forEach { window -> gestureWindows.putIfAbsent(window.first, window) }
    }

    private fun drainWindowFrames(windowRecorder: ViewerWindowFrameRecorder) {
        if (windowRecorder.droppedReportCount() > 0) integrityWindowFramesDropped = true
        val snapshot = windowRecorder.snapshot()
        if (snapshot.size > windowPairsSeen * 2) {
            windowFramePairs += snapshot.copyOfRange(windowPairsSeen * 2, snapshot.size)
        }
        windowPairsSeen = snapshot.size / 2
    }

    private fun observeFrame(observation: EngineFrameObservation) {
        val frame = observation.presentation
        observeDisplayEvidence(frame)
        if (!frame.swapSucceeded) return
        lastObservedToken = frame.identity.token
        val scene = frame.scene
        val height = scene.viewport.heightPx * scene.coordinateUnitsPerPixel
        val visiblePlacements = scene.placements.filter { it.bottomPx > 0 && it.topPx < height }
        val displayPresent = frame.timestampKind == PresentationTimestampKind.DISPLAY_PRESENT &&
            frame.timestampNanos > 0L && frame.timestampNanos >= frame.submittedAtNanos
        if (displayPresent && visiblePlacements.isNotEmpty()) {
            val key = motionKey(scene.anchor, scene)
            if (key != null) {
                if (lastMotionKey != null && key != lastMotionKey) {
                    active?.movingDisplayFrames = (active?.movingDisplayFrames ?: 0L) + 1L
                    if (scene.completeCoverage) {
                        active?.completeCoverageMovingDisplayFrames =
                            (active?.completeCoverageMovingDisplayFrames ?: 0L) + 1L
                    }
                    observeMeasuredMotion(frame, scene)
                }
                lastMotionKey = key
            }
        }
        pageBoundsProvider()?.let { (first, last) ->
            for (placement in scene.placements) {
                val tile = placement.texture.tile
                if (tile.pageId == first && tile.sourceTop == 0 && placement.topPx >= 0 && placement.topPx < height) {
                    startToken = frame.identity.token
                    startTokenAtNanos = frame.submittedAtNanos
                }
                if (tile.pageId == last && tile.sourceBottom == tile.dimensions.heightPx &&
                    placement.bottomPx > 0 && placement.bottomPx <= height
                ) {
                    endToken = frame.identity.token
                    endTokenAtNanos = frame.submittedAtNanos
                }
            }
        }
        val next = nextEpisodeProvider() ?: run {
            latestVisibleTargetContent = false
            latestVisibleNextContent = false
            return
        }
        val visible = scene.placements.filter { it.bottomPx > 0 && it.topPx < height }
        val nextVisible = visible.any { it.texture.tile.pageId.episodeId == next }
        latestVisibleTargetContent = visible.any { it.texture.tile.pageId.episodeId == episode }
        latestVisibleNextContent = nextVisible
        if (nextVisible) boundaryFrames += 1
        val freshObservation = frame.submittedAtNanos >= boundaryLatchFloorNanos
        if (nextVisible && scene.completeCoverage && freshObservation) {
            if (firstNextSourceToken == null) {
                firstNextSourceToken = frame.identity.token
                firstNextSourceAtNanos = frame.submittedAtNanos
            }
            completeNextFrameObserved = true
            if (completeNextFrameAtNanos == 0L) completeNextFrameAtNanos = frame.submittedAtNanos
            if (scene.anchor?.pageId?.episodeId == next && firstNextAnchorToken == null) {
                firstNextAnchorToken = frame.identity.token
                firstNextAnchorAtNanos = frame.submittedAtNanos
            }
        }
        if (nextVisible && freshObservation && visible.any { it.texture.tile.pageId.episodeId == episode }) {
            currentAndNextShareViewport = true
            if (shareViewportAtNanos == 0L) shareViewportAtNanos = frame.submittedAtNanos
        }
    }

    private fun observeMeasuredMotion(
        frame: ml.melun.mangaview.viewer.runtime.EngineSurfacePresentation,
        scene: ml.melun.mangaview.viewer.runtime.EngineSurfaceScene,
    ) {
        val stage = active ?: return
        if (stage.measuredFromNanos == 0L) return
        if (frame.identity.token == lastMeasuredToken) return
        lastMeasuredToken = frame.identity.token
        stage.measuredMovingDisplayFrames = stage.measuredMovingDisplayFrames + 1L
        val coordinate = motionCoordinate(scene.anchor, scene) ?: return
        val previous = lastMeasuredCoordinate
        if (previous != null && coordinate != previous) {
            val sign = if (coordinate > previous) 1 else -1
            if (lastDirectionSign != 0 && sign != lastDirectionSign) {
                stage.measuredDirectionChanges = stage.measuredDirectionChanges + 1L
            }
            lastDirectionSign = sign
        }
        lastMeasuredCoordinate = coordinate
    }

    private fun observeDisplayEvidence(
        frame: ml.melun.mangaview.viewer.runtime.EngineSurfacePresentation,
    ) {
        if (frame.timestampKind != PresentationTimestampKind.DISPLAY_PRESENT) return
        if (!frame.swapSucceeded) return
        val presented = frame.timestampNanos
        if (presented <= 0L || presented < frame.submittedAtNanos) {
            invalidDisplayTimestamps += 1
            return
        }
        val scope = "${frame.rendererId}:${frame.identity.sessionId}:${frame.identity.surfaceEpoch}"
        val seen = nativeTimestamps.getOrPut(scope) { mutableSetOf() }
        val previous = lastNativeTimestamp[scope]
        if (previous != null && presented <= previous) {
            if (presented == previous) duplicateNativeTimestamps += 1 else nonMonotonicNativeTimestamps += 1
        }
        if (!seen.add(presented)) duplicateNativeTimestamps += 1
        lastNativeTimestamp[scope] = maxOf(previous ?: 0L, presented)
        active?.displayPresentFrames = (active?.displayPresentFrames ?: 0L) + 1L
        if (active?.firstDisplayPresentAtNanos == 0L) {
            active?.firstDisplayPresentAtNanos = presented
        }
        if (frame.scene.placements.isNotEmpty()) {
            active?.readableDisplayPresentFrames = (active?.readableDisplayPresentFrames ?: 0L) + 1L
            if (firstCompleteDisplayPresentAtNanos == 0L && frame.scene.completeCoverage) {
                firstCompleteDisplayPresentAtNanos = presented
            }
        }
    }

    private fun motionKey(
        anchor: ml.melun.mangaview.engine.api.SourceAnchor?,
        scene: ml.melun.mangaview.viewer.runtime.EngineSurfaceScene,
    ): String? {
        anchor?.let {
            return "${it.pageId}@${it.sourceYQ32}/${it.viewportOffsetUnits}"
        }
        val first = scene.placements.minByOrNull { it.topPx } ?: return null
        return "${first.texture.tile.pageId}:${first.texture.tile.sourceTop}:${first.topPx}"
    }

    private fun motionCoordinate(
        anchor: ml.melun.mangaview.engine.api.SourceAnchor?,
        scene: ml.melun.mangaview.viewer.runtime.EngineSurfaceScene,
    ): Long? {
        anchor?.let { return it.sourceYQ32 }
        val first = scene.placements.minByOrNull { it.topPx } ?: return null
        return first.topPx.toLong()
    }

    fun beginStage(name: String) {
        check(active == null) { "Previous qualification stage was not closed" }
        val planned = EngineScrollQualificationPolicy.stage(name)
        active = StageBoundary(name, System.nanoTime(), targetMovingFrames = planned.targetMovingFrames)
        lastMotionKey = null
        lastMeasuredToken = 0L
        lastMeasuredCoordinate = null
        lastDirectionSign = 0
        if (planned.measuredFromStart) activateMeasurement()
    }

    /** Re-arms the one-shot next-boundary latches for a deliberate fresh crossing stage. */
    fun resetBoundaryObservations() {
        firstNextSourceToken = null
        firstNextSourceAtNanos = 0L
        firstNextAnchorToken = null
        firstNextAnchorAtNanos = 0L
        currentAndNextShareViewport = false
        shareViewportAtNanos = 0L
        completeNextFrameObserved = false
        completeNextFrameAtNanos = 0L
        boundaryFrames = 0L
        boundaryLatchFloorNanos = System.nanoTime()
    }

    /** Ends setup and starts the measured local window for endpoint/boundary stages. */
    fun activateMeasurement() {
        val stage = requireNotNull(active) { "No active qualification stage" }
        if (stage.measuredFromNanos != 0L) return
        stage.setupMovingDisplayFrames = stage.movingDisplayFrames
        stage.setupGestures = stage.gestures
        stage.measuredFromNanos = System.nanoTime()
        lastMeasuredToken = 0L
        lastMeasuredCoordinate = null
        lastDirectionSign = 0
    }

    fun recordGesture() {
        active?.gestures = (active?.gestures ?: 0) + 1
    }

    fun recordLoadingAttempt(
        ordinal: Int,
        atNanos: Long,
        surfaceReady: Boolean,
        milestoneObservedAtNanos: Long?,
    ) {
        loadingAttempts += JSONObject().apply {
            put("ordinal", ordinal); put("atNanos", atNanos)
            put("surfaceReady", surfaceReady)
            put("milestoneObservedAtNanos", milestoneObservedAtNanos ?: JSONObject.NULL)
        }
    }

    fun endStage(explicitEndNanos: Long = 0L, budgetExhausted: Boolean = false) {
        val stage = requireNotNull(active) { "No active qualification stage" }
        stage.endedAtNanos = if (explicitEndNanos > 0L) explicitEndNanos else System.nanoTime()
        stage.budgetExhausted = budgetExhausted
        stages += stage
        active = null
        lastMotionKey = null
    }

    fun markStopped() {
        stoppedAtNanos = System.nanoTime()
    }

    val activeStartedAtNanos: Long get() = active?.startedAtNanos ?: 0L
    val activeMeasured: Boolean get() = (active?.measuredFromNanos ?: 0L) != 0L

    fun stageMeasuredFrames(name: String): Long =
        stages.firstOrNull { it.stage == name }?.measuredMovingDisplayFrames
            ?: active?.takeIf { it.stage == name }?.measuredMovingDisplayFrames
            ?: 0L

    fun stageMeasuredDirectionChanges(name: String): Long =
        stages.firstOrNull { it.stage == name }?.measuredDirectionChanges
            ?: active?.takeIf { it.stage == name }?.measuredDirectionChanges
            ?: 0L

    val inputLostCount: Long get() = integrityInputLost
    val frameLostCount: Long get() = integrityFrameLost
    /** Consecutive trailing DEFERRED inputs with no resolution in between; a growing value means
     *  the engine is holding input behind an unresolved content/geometry blocker. */
    val deferredInputsWithoutProgress: Long get() = deferredInputStreak
    val lastResolvedPendingInputCount: Int get() = lastResolvedInputPendingCount
    val currentPendingInputCount: Int get() = latestPendingInputCount
    val motionOverwritten: Boolean get() = integrityMotionOverwritten
    val frameCount: Long get() = frameCursor
    val inputCount: Long get() = inputCursor
    val motionCount: Long get() = motionCursor
    val firstCompleteDisplayPresent: Long get() = firstCompleteDisplayPresentAtNanos
    val loadingAttemptCount: Int get() = loadingAttempts.size

    fun close(viewer: EngineViewerScreen, windowRecorder: ViewerWindowFrameRecorder) {
        drain(viewer, windowRecorder)
        check(active == null) { "Qualification closed with an open stage" }
        if (windowRecorder.droppedReportCount() > 0) integrityWindowFramesDropped = true
        if (stoppedAtNanos == 0L) markStopped()
        writeFrames()
        writeInputs()
        writeMotion()
        writeWindowFrames()
        writeLegacyPresentations()
        writeLoadingAttempts()
        writeCloseProofs(viewer)
        writeStartupTiming(viewer)
        writeStageEvidence(viewer)
    }

    private fun writeFrames() {
        File(output, "frames.jsonl").bufferedWriter().use { writer ->
            frameObservations.forEach { observation ->
                writer.append(frameRecord(observation).toString()).append('\n')
            }
        }
    }

    private fun frameRecord(observation: EngineFrameObservation): JSONObject {
        val frame = observation.presentation
        val identity = frame.identity
        val scene = frame.scene
        val height = scene.viewport.heightPx * scene.coordinateUnitsPerPixel
        val visible = scene.placements.filter { it.bottomPx > 0 && it.topPx < height }
        val first = scene.placements.minByOrNull { it.topPx }
        return JSONObject().apply {
            put("ordinal", observation.ordinal); put("rendererId", frame.rendererId)
            put("sessionId", identity.sessionId); put("rendererEpoch", identity.rendererEpoch)
            put("surfaceEpoch", identity.surfaceEpoch); put("token", identity.token)
            put("inputRevision", identity.inputRevision); put("geometryRevision", identity.geometryRevision)
            put("movementRevision", scene.movementRevision)
            put("sceneGeneration", scene.generation); put("eglFrameId", frame.eglFrameId)
            put("submittedAtNanos", frame.submittedAtNanos)
            put("renderSubmissionDurationNanos", frame.renderLatencyNanos)
            put("swapSucceeded", frame.swapSucceeded)
            put("timestampKind", frame.timestampKind.name); put("timestampNanos", frame.timestampNanos)
            put("viewportWidth", scene.viewport.widthPx); put("viewportHeight", scene.viewport.heightPx)
            put("completeViewportCoverage", scene.completeCoverage)
            put("anchorIdentity", anchor(scene.anchor))
            put("motionKey", motionKey(scene.anchor, scene) ?: JSONObject.NULL)
            put("motionCoordinate", motionCoordinate(scene.anchor, scene) ?: JSONObject.NULL)
            put("visiblePlacementCount", visible.size)
            put("visiblePageIdentities", JSONArray(
                visible.map { it.texture.tile.pageId.toString() }.distinct()))
            put("firstVisiblePlacement", first?.let { placement ->
                JSONObject().apply {
                    put("pageIdentity", page(placement.texture.tile.pageId))
                    put("sourceTop", placement.texture.tile.sourceTop)
                    put("sourceBottom", placement.texture.tile.sourceBottom)
                    put("topPx", placement.topPx); put("bottomPx", placement.bottomPx)
                }
            } ?: JSONObject.NULL)
            put("physicalPresentationVerified", false); put("corpusCredit", 0)
        }
    }

    private fun writeInputs() {
        File(output, "inputs.jsonl").bufferedWriter().use { writer ->
            inputObservations.forEach { value ->
                val receipt = value.receipt
                val row = JSONObject().apply {
                    put("ordinal", value.ordinal); put("sessionId", value.sessionId)
                    put("generation", value.generation)
                    put("inputRevision", value.inputRevision); put("geometryRevision", value.geometryRevision)
                    put("movementRevision", value.movementRevision)
                    put("pendingInputCount", value.pendingInputCount); put("anchorIdentity", anchor(value.anchor))
                    put("sequence", receipt.sample.sequence); put("gestureId", receipt.sample.gestureId)
                    put("eventTimeNanos", receipt.sample.eventTimeNanos)
                    put("deltaScreenUnits", receipt.sample.deltaScreenUnits)
                    put("acceptedAtNanos", receipt.acceptedAtNanos)
                    put("resolvedAtNanos", receipt.resolvedAtNanos ?: JSONObject.NULL)
                    put("appliedScreenUnits", receipt.appliedScreenUnits)
                    put("outcome", receipt.outcome.name)
                    put("receiptGeometryRevision", receipt.geometryRevision)
                    put("boundary", receipt.boundary?.let { boundary ->
                        JSONObject().apply {
                            put("kind", boundary.boundary.name); put("pageIdentity", page(boundary.pageId))
                            put("geometryRevision", boundary.geometryRevision)
                        }
                    } ?: JSONObject.NULL)
                    put("rawEventTimeNanos", JSONObject.NULL)
                    put("rawInputId", JSONObject.NULL)
                    put("rawBindingAvailable", false)
                }
                writer.append(row.toString()).append('\n')
            }
        }
    }

    private fun writeMotion() {
        File(output, "motion.jsonl").bufferedWriter().use { writer ->
            var ordinal = 0L
            motionPacked.forEachIndexed { chunkIndex, chunk ->
                val applied = motionAppliedAt[chunkIndex]
                var index = 0
                while (index < chunk.size) {
                    ordinal += 1L
                    writer.append(JSONObject().apply {
                        put("ordinal", ordinal)
                        put("sequence", chunk[index])
                        put("frameTimeNanos", chunk[index + 1])
                        put("appliedAtNanos", applied.getOrElse(index / 2) { 0L })
                    }.toString()).append('\n')
                    index += 2
                }
            }
        }
        File(output, "motion-windows.jsonl").bufferedWriter().use { writer ->
            gestureWindows.values.forEachIndexed { index, window ->
                writer.append(JSONObject().apply {
                    put("ordinal", index + 1); put("startNanos", window.first); put("endNanos", window.last)
                }.toString()).append('\n')
            }
        }
    }

    private fun writeWindowFrames() {
        File(output, "window-frames.jsonl").bufferedWriter().use { writer ->
            var ordinal = 0L
            windowFramePairs.forEach { pair ->
                ordinal += 1L
                writer.append(JSONObject().apply {
                    put("ordinal", ordinal)
                    put("intendedVsyncNanos", pair[0])
                    put("totalDurationNanos", pair[1])
                }.toString()).append('\n')
            }
        }
    }

    /** Optional diagnostic only: the current runtime path never feeds this legacy ring. */
    private fun writeLegacyPresentations() {
        File(output, "presentations.jsonl").bufferedWriter().use { writer ->
            presentationChunks.forEach { chunk ->
                var index = 0
                while (index + NativePresentationEvidencePacking.STRIDE <= chunk.size) {
                    writer.append(JSONObject().apply {
                        put("legacyDiagnostic", true)
                        put("presentedNanos", chunk[index + 3])
                        put("scrollOffsetUnits", chunk[index + 5])
                        put("anchorOrdinal", chunk[index + 7])
                        put("anchorOffsetUnits", chunk[index + 8])
                        put("flags", chunk[index + 9])
                        put("geometryRevision", chunk[index + 12])
                        put("userInputRevision", chunk[index + 14])
                    }.toString()).append('\n')
                    index += NativePresentationEvidencePacking.STRIDE
                }
            }
        }
    }

    private fun writeLoadingAttempts() {
        File(output, "loading-attempts.jsonl").bufferedWriter().use { writer ->
            loadingAttempts.forEach { writer.append(it.toString()).append('\n') }
        }
    }

    private fun writeCloseProofs(viewer: EngineViewerScreen) {
        viewer.engineFrameCloseProof()?.let { proof ->
            File(output, "renderer-close.json").writeText(JSONObject().apply {
                put("rendererId", proof.rendererId)
                put("submittedFrameCount", proof.submittedFrameCount)
                put("deliveredObservationCount", proof.deliveredObservationCount)
                put("closedAtNanos", proof.closedAtNanos)
            }.toString(2))
        }
        viewer.engineInputCloseProof()?.let { proof ->
            File(output, "input-close.json").writeText(JSONObject().apply {
                put("sessionId", proof.sessionId); put("generation", proof.generation)
                put("inputRevision", proof.inputRevision)
                put("receivedInputCount", proof.receivedInputCount)
                put("observationCount", proof.observationCount)
                put("closedAtNanos", proof.closedAtNanos)
            }.toString(2))
        }
        File(output, "motion-close.json").writeText(JSONObject().apply {
            put("observationCount", motionCursor)
            put("windowCount", gestureWindows.size)
            put("closedAtNanos", stoppedAtNanos)
            put("refreshPeriodNanos", viewer.presentationRefreshPeriodNanos())
            put("historyOverwritten", integrityMotionOverwritten)
        }.toString(2))
    }

    private fun writeStartupTiming(viewer: EngineViewerScreen) {
        val startup = viewer.viewerStartupTimingSnapshot() ?: return
        File(output, "startup-timing.json").writeText(JSONObject().apply {
            put("clock", "System.nanoTime")
            put("openStartedAtNanos", startup.openStartedAtNanos)
            put("manifestReadyAtNanos", startup.manifestReadyAtNanos ?: JSONObject.NULL)
            put("firstSourceSubmittedAtNanos", startup.firstActualSubmittedAtNanos ?: JSONObject.NULL)
            put("firstCompleteViewportSubmittedAtNanos",
                startup.firstCompleteViewportSubmittedAtNanos ?: JSONObject.NULL)
            put("firstCurrentViewportObservedSubmittedAtNanos",
                startup.firstCurrentViewportObservedSubmittedAtNanos ?: JSONObject.NULL)
            put("firstSourcePresentedAtNanos", startup.firstActualPresentedAtNanos ?: JSONObject.NULL)
            put("physicalPresentationVerified", false)
        }.toString(2))
    }

    private fun writeStageEvidence(viewer: EngineViewerScreen) {
        val startup = viewer.viewerStartupTimingSnapshot()
        stages.forEach { stage ->
            stagesJson.put(JSONObject().apply {
                put("stage", stage.stage)
                put("startedAtNanos", stage.startedAtNanos); put("endedAtNanos", stage.endedAtNanos)
                put("gestures", stage.gestures)
                put("setupGestures", stage.setupGestures)
                put("movingDisplayFrames", stage.movingDisplayFrames)
                put("completeCoverageMovingDisplayFrames", stage.completeCoverageMovingDisplayFrames)
                put("setupMovingDisplayFrames", stage.setupMovingDisplayFrames)
                put("measuredFromNanos", stage.measuredFromNanos)
                put("measuredMovingDisplayFrames", stage.measuredMovingDisplayFrames)
                put("measuredDirectionChanges", stage.measuredDirectionChanges)
                put("displayPresentFrames", stage.displayPresentFrames)
                put("readableDisplayPresentFrames", stage.readableDisplayPresentFrames)
                put("firstDisplayPresentAtNanos", stage.firstDisplayPresentAtNanos)
                put("targetMovingFrames", stage.targetMovingFrames)
                put("budgetExhausted", stage.budgetExhausted)
            })
        }
        val integrity = JSONObject().apply {
            put("frameLostCount", integrityFrameLost)
            put("frameObservationCount", frameCursor)
            put("inputLostCount", integrityInputLost)
            put("inputObservationCount", inputCursor)
            put("motionObservationCount", motionCursor)
            put("motionHistoryOverwritten", integrityMotionOverwritten)
            put("gestureWindowsTruncated", integrityGestureWindowsTruncated)
            put("windowFramesDropped", integrityWindowFramesDropped)
            put("windowFrameCount", windowPairsSeen)
            put("frameInvalidDisplayPresentTimestamps", invalidDisplayTimestamps)
            put("frameDuplicateNativeTimestamps", duplicateNativeTimestamps)
            put("frameNonMonotonicNativeTimestamps", nonMonotonicNativeTimestamps)
            put("legacyPresentationDropped", legacyPresentationDropped)
            put("legacyPresentationRows", legacyPresentationRows)
            put("legacyPresentationCursor", presentationCursor)
            put("firstCompleteDisplayPresentAtNanos", firstCompleteDisplayPresentAtNanos)
        }
        val boundary = JSONObject().apply {
            put("firstNextSourceToken", firstNextSourceToken ?: JSONObject.NULL)
            put("firstNextSourceAtNanos", firstNextSourceAtNanos)
            put("firstNextAnchorToken", firstNextAnchorToken ?: JSONObject.NULL)
            put("firstNextAnchorAtNanos", firstNextAnchorAtNanos)
            put("currentAndNextShareViewport", currentAndNextShareViewport)
            put("shareViewportAtNanos", shareViewportAtNanos)
            put("completeNextFrameObserved", completeNextFrameObserved)
            put("completeNextFrameAtNanos", completeNextFrameAtNanos)
            put("boundaryFrames", boundaryFrames)
        }
        val endpoints = JSONObject().apply {
            put("firstPageStartToken", startToken); put("firstPageStartAtNanos", startTokenAtNanos)
            put("lastPageEndToken", endToken); put("lastPageEndAtNanos", endTokenAtNanos)
            put("traversedDocumentEndpoints", startToken > 0L && endToken > startToken)
        }
        File(output, "stage-evidence.json").writeText(JSONObject().apply {
            put("schemaVersion", EngineScrollQualificationPolicy.SCHEMA_VERSION)
            put("clock", "System.nanoTime")
            put("episode", episode.toString())
            put("movingEvidenceSource", "engineFramesSince DISPLAY_PRESENT frames")
            put("captureStartedAtNanos", stages.firstOrNull()?.startedAtNanos ?: 0L)
            put("stoppedAtNanos", stoppedAtNanos)
            put("stages", stagesJson)
            put("integrity", integrity)
            put("boundary", boundary)
            put("endpoints", endpoints)
            put("loading", JSONObject().apply {
                put("attemptCount", loadingAttempts.size)
                put("firstCompleteViewportSubmittedAtNanos",
                    startup?.firstCompleteViewportSubmittedAtNanos ?: JSONObject.NULL)
                put("firstCompleteDisplayPresentAtNanos", firstCompleteDisplayPresentAtNanos)
            })
            put("rawInputBinding", JSONObject().apply {
                put("available", false)
                put("note", "raw MotionEvent receive times require the input-binding extractor; " +
                    "this capture keeps them explicitly absent rather than inferred")
            })
            put("physicalPresentationVerified", false)
            put("corpusCredit", 0)
        }.toString(2))
    }

    fun integrityFailures(): List<String> = buildList {
        if (integrityInputLost > 0L) add("input observations lost: $integrityInputLost")
        if (integrityFrameLost > 0L) add("frame observations lost: $integrityFrameLost")
        if (integrityMotionOverwritten) add("motion observation ring was overwritten")
        if (integrityGestureWindowsTruncated) add("gesture window history was truncated")
        if (integrityWindowFramesDropped) add("HWUI window frame reports were dropped")
        if (invalidDisplayTimestamps > 0L) add("invalid DISPLAY_PRESENT timestamps: $invalidDisplayTimestamps")
        if (duplicateNativeTimestamps > 0L) add("duplicate native timestamps: $duplicateNativeTimestamps")
        if (nonMonotonicNativeTimestamps > 0L) {
            add("non-monotonic native timestamps: $nonMonotonicNativeTimestamps")
        }
    }

    private fun page(id: PageId): JSONObject = JSONObject().apply {
        put("sourceId", id.episodeId.seriesId.sourceId.value)
        put("seriesKey", id.episodeId.seriesId.remoteKey)
        put("episodeKey", id.episodeId.remoteKey)
        put("pageKey", id.remoteKey)
    }

    private fun anchor(value: ml.melun.mangaview.engine.api.SourceAnchor?): Any = value?.let { anchor ->
        JSONObject().apply {
            put("pageIdentity", page(anchor.pageId))
            put("sourceYQ32", anchor.sourceYQ32)
            put("viewportOffsetUnits", anchor.viewportOffsetUnits)
        }
    } ?: JSONObject.NULL
}
