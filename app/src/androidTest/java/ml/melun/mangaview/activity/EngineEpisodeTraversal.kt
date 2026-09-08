package ml.melun.mangaview.activity

import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.runtime.EngineLaunchPreparationSnapshot
import ml.melun.mangaview.viewer.runtime.EngineCapturedFrame
import ml.melun.mangaview.viewer.runtime.EngineReadbackPacket
import ml.melun.mangaview.viewer.runtime.EngineSurfaceScene
import org.json.JSONObject

internal enum class WholePreparationState { WAITING, READY, TIMED_OUT }

/** Timestamp-based gate so a readiness callback observed late still uses its actual milestone time. */
internal class WholePreparationGate(
    private val startedAtNanos: Long,
    private val clock: () -> Long,
    timeoutNanos: Long = 120_000_000_000L,
) {
    private val deadlineNanos = Math.addExact(startedAtNanos, timeoutNanos)
    var state: WholePreparationState = WholePreparationState.WAITING
        private set

    init { require(startedAtNanos > 0L && timeoutNanos > 0L) }

    fun observe(allPreparedAtNanos: Long?): WholePreparationState {
        if (state != WholePreparationState.WAITING) return state
        state = when {
            allPreparedAtNanos != null && allPreparedAtNanos in startedAtNanos..deadlineNanos -> WholePreparationState.READY
            clock() >= deadlineNanos -> WholePreparationState.TIMED_OUT
            else -> WholePreparationState.WAITING
        }
        return state
    }
}

internal class WholeProtocolDeadline(
    startedAtNanos: Long,
    private val clock: () -> Long,
    boundNanos: Long = 150_000_000_000L,
) {
    private val deadlineNanos = Math.addExact(startedAtNanos, boundNanos)
    init { require(startedAtNanos > 0L && boundNanos > 0L) }
    fun expired(): Boolean = clock() >= deadlineNanos
}

private data class ScheduledGesture(val forward: Boolean, val speed: EngineTraversalGestureSpeed)

private val EARLY_WHOLE_PREPARATION_GESTURES = listOf(
    ScheduledGesture(true, EngineTraversalGestureSpeed.NORMAL),
    ScheduledGesture(true, EngineTraversalGestureSpeed.FAST),
    ScheduledGesture(false, EngineTraversalGestureSpeed.FAST),
    ScheduledGesture(true, EngineTraversalGestureSpeed.NORMAL),
    ScheduledGesture(true, EngineTraversalGestureSpeed.FAST),
    ScheduledGesture(false, EngineTraversalGestureSpeed.NORMAL),
    ScheduledGesture(true, EngineTraversalGestureSpeed.FAST),
    ScheduledGesture(true, EngineTraversalGestureSpeed.NORMAL),
)

private val POST_PREPARATION_GESTURES = listOf(true, false, true, true, false, false, true, false)

/** Real uninterrupted gestures, with an independent bounded natural-frame readback consumer. */
internal suspend fun traverseCapturedEpisode(
    activity: ViewerActivity,
    device: UiDevice,
    episode: EpisodeId,
    documents: EngineCapturedEpisodeDocuments,
    writeCapture: (EngineCapturedFrame) -> Unit,
    exportObservations: () -> Unit,
    captureStoppedScreen: (Int) -> Unit,
    injectGesture: (Int, Boolean, EngineTraversalGestureSpeed) -> Unit,
    readbackEnabled: Boolean = true,
    fixedGestureDirections: List<Boolean>? = null,
    maximumDurationMillis: Long = 90_000,
    maximumCaptures: Long = 512,
    wholePreparationMode: Boolean = false,
    preparationSnapshot: suspend () -> EngineLaunchPreparationSnapshot? = { null },
    recordPreparation: (EngineLaunchPreparationSnapshot) -> Unit = {},
    maximumGestures: Int = 512,
): JSONObject = coroutineScope {
    require(maximumDurationMillis in 1_000L..300_000L && maximumCaptures in 1L..1024L && maximumGestures in 1..2048)
    if (wholePreparationMode) require(!readbackEnabled) { "Whole-preparation performance phase must not use synchronous readback" }
    val startToken = AtomicLong(0)
    val endToken = AtomicLong(0)
    val lastCapturedToken = AtomicLong(0)
    val lastObservedToken = AtomicLong(0)
    val captures = AtomicLong(0)
    val deadline = SystemClock.elapsedRealtime() + maximumDurationMillis
    val reader = launch(Dispatchers.Default) {
        while (isActive) {
            val scene: EngineSurfaceScene
            val token: Long
            if (readbackEnabled) {
                val result = activity.captureNextEngineViewportFrame()
                check(result.pixels.status == EngineReadbackPacket.Status.OK) { "Natural viewport readback failed" }
                check(captures.incrementAndGet() <= maximumCaptures) { "Traversal capture capacity exceeded" }
                writeCapture(result)
                scene = result.scene
                token = result.identity.token
                lastCapturedToken.set(token)
            } else {
                val frame = activity.viewerEngineFrameSnapshot()
                if (frame == null || frame.identity.token == lastObservedToken.get()) {
                    delay(1)
                    continue
                }
                scene = frame.scene
                token = frame.identity.token
            }
            lastObservedToken.set(token)
            documents.pageBounds(episode)?.let { (first, last) ->
                val height = scene.viewport.heightPx * scene.coordinateUnitsPerPixel
                for (placement in scene.placements) {
                    val tile = placement.texture.tile
                    if (tile.pageId == first && tile.sourceTop == 0 && placement.topPx >= 0 && placement.topPx < height) {
                        startToken.set(token)
                    }
                    if (tile.pageId == last && tile.sourceBottom == tile.dimensions.heightPx &&
                        placement.bottomPx > 0 && placement.bottomPx <= height) {
                        endToken.set(token)
                    }
                }
            }
        }
    }
    var gestures = 0
    var frameCursor = 0L
    var lostFrameEvidence = 0L
    val observedSourceFrames = mutableListOf<JSONObject>()
    var firstFullSubmittedAtNanos: Long? = null
    fun observeFrames() {
        firstFullSubmittedAtNanos = firstFullSubmittedAtNanos ?:
            activity.viewerStartupTimingSnapshot()?.firstCompleteViewportSubmittedAtNanos
        val batch = activity.engineFramesSince(frameCursor)
        lostFrameEvidence = Math.addExact(lostFrameEvidence, batch.lostCount)
        batch.observations.forEach { observation ->
            val frame = observation.presentation
            if (frame.swapSucceeded) {
                observedSourceFrames += JSONObject().apply {
                    put("ordinal", observation.ordinal); put("token", frame.identity.token)
                    put("submittedAtNanos", frame.submittedAtNanos)
                    put("sourcePlacementCount", frame.scene.placements.size)
                    put("loadingOrBlank", frame.scene.placements.isEmpty())
                    put("completeViewportCoverage", frame.scene.completeCoverage)
                    put("coverageQualified", frame.scene.placements.isNotEmpty() && frame.scene.completeCoverage)
                }
            }
        }
        frameCursor = batch.latestOrdinal
    }
    fun swipe(forward: Boolean, speed: EngineTraversalGestureSpeed = EngineTraversalGestureSpeed.NORMAL) {
        check(reader.isActive) { "Capture reader stopped during traversal" }
        if (!wholePreparationMode) check(SystemClock.elapsedRealtime() < deadline) { "Whole episode traversal deadline exceeded" }
        check(gestures < maximumGestures) { "Whole episode traversal gesture capacity exceeded" }
        injectGesture(gestures, forward, speed)
        gestures++
        observeFrames()
        exportObservations()
        activity.viewerFailureSnapshot()?.let { throw AssertionError("Traversal viewer failed", it) }
    }
    var lastPreparationCount = -1
    var finalPreparation: EngineLaunchPreparationSnapshot? = null
    suspend fun observePreparation(): EngineLaunchPreparationSnapshot? {
        val value = preparationSnapshot()
        if (value != null) {
            finalPreparation = value
            if (value.verifiedPages.size != lastPreparationCount) {
                lastPreparationCount = value.verifiedPages.size
                recordPreparation(value)
            }
        }
        observeFrames()
        return value
    }
    try {
        // Start input immediately, including when resuming in the middle of an existing episode.
        if (fixedGestureDirections != null) {
            require(fixedGestureDirections.isNotEmpty())
            fixedGestureDirections.forEach { swipe(it) }
        } else if (wholePreparationMode) {
            val openedAt = requireNotNull(activity.viewerStartupTimingSnapshot()).openStartedAtNanos
            val readiness = WholePreparationGate(openedAt, System::nanoTime)
            val protocolDeadline = WholeProtocolDeadline(openedAt, System::nanoTime)
            var protocolDeadlineFail = false
            var gestureLimitFail = false
            var documentEndpointMissFail = false
            var failurePhase: String? = null
            fun wholeSwipe(forward: Boolean, speed: EngineTraversalGestureSpeed, phase: String): Boolean {
                if (protocolDeadline.expired()) {
                    protocolDeadlineFail = true
                    if (failurePhase == null) failurePhase = phase
                    return false
                }
                if (gestures >= maximumGestures) {
                    gestureLimitFail = true
                    if (failurePhase == null) failurePhase = phase
                    return false
                }
                swipe(forward, speed)
                if (protocolDeadline.expired()) {
                    protocolDeadlineFail = true
                    if (failurePhase == null) failurePhase = phase
                    return false
                }
                return true
            }
            // The first real gesture is immediate; no viewport or original-readiness polling precedes it.
            for (gesture in EARLY_WHOLE_PREPARATION_GESTURES) {
                if (!wholeSwipe(gesture.forward, gesture.speed, "EARLY_GESTURES")) break
                readiness.observe(observePreparation()?.allFirstVerifiedPreparedAtNanos)
            }
            while (!protocolDeadlineFail && !gestureLimitFail && endToken.get() == 0L &&
                readiness.state != WholePreparationState.TIMED_OUT
            ) {
                val anchorEpisode = activity.viewerEngineSnapshot()?.session?.anchor?.pageId?.episodeId
                if (anchorEpisode != null && anchorEpisode != episode) {
                    documentEndpointMissFail = true
                    if (failurePhase == null) failurePhase = "LAUNCH_END_CROSSED_WITHOUT_VISIBLE_ENDPOINT"
                    break
                }
                if (!wholeSwipe(true, EngineTraversalGestureSpeed.FAST, "FORWARD_ENDPOINT")) break
                readiness.observe(observePreparation()?.allFirstVerifiedPreparedAtNanos)
            }
            val reverseStartToken = lastObservedToken.get()
            while (!protocolDeadlineFail && !gestureLimitFail && startToken.get() <= reverseStartToken &&
                readiness.state != WholePreparationState.TIMED_OUT
            ) {
                val state = activity.viewerEngineSnapshot()
                val previousEpisode = state?.plans?.get(episode)?.manifest?.previousEpisodeId
                if (previousEpisode != null && state.session.anchor?.pageId?.episodeId == previousEpisode) {
                    documentEndpointMissFail = true
                    if (failurePhase == null) failurePhase = "LAUNCH_START_CROSSED_WITHOUT_VISIBLE_ENDPOINT"
                    break
                }
                if (!wholeSwipe(false, EngineTraversalGestureSpeed.FAST, "REVERSE_ENDPOINT")) break
                readiness.observe(observePreparation()?.allFirstVerifiedPreparedAtNanos)
            }
            while (!protocolDeadlineFail && readiness.state == WholePreparationState.WAITING) {
                if (protocolDeadline.expired()) {
                    protocolDeadlineFail = true
                    if (failurePhase == null) failurePhase = "PREPARATION_WAIT"
                    break
                }
                val preparation = observePreparation()
                readiness.observe(preparation?.allFirstVerifiedPreparedAtNanos)
                if (readiness.state == WholePreparationState.WAITING) delay(100)
            }
            var postPreparationGestures = 0
            if (!protocolDeadlineFail && !gestureLimitFail && readiness.state == WholePreparationState.READY) {
                for (forward in POST_PREPARATION_GESTURES) {
                    if (!wholeSwipe(forward, EngineTraversalGestureSpeed.FAST, "POST_PREPARATION_GESTURES")) break
                    postPreparationGestures++
                }
            }
            observePreparation()

            val launch = finalPreparation
            val missing = launch?.manifestPageIds.orEmpty().filter { it !in launch!!.verifiedPages }
            val preparationTimedOut = readiness.state == WholePreparationState.TIMED_OUT
            if (preparationTimedOut && failurePhase == null) failurePhase = "PREPARATION_TIMEOUT"
            val stoppedAt = SystemClock.elapsedRealtimeNanos()
            if (!protocolDeadline.expired()) {
                delay(2_000)
                if (!protocolDeadline.expired()) {
                    captureStoppedScreen(0)
                    delay(1_000)
                    if (!protocolDeadline.expired()) captureStoppedScreen(1)
                }
            }
            if (protocolDeadline.expired()) {
                protocolDeadlineFail = true
                if (failurePhase == null) failurePhase = "STOPPED_OBSERVATION"
            }
            observeFrames()
            exportObservations()
            val afterFirstFull = firstFullSubmittedAtNanos?.let { first ->
                observedSourceFrames.filter { it.getLong("submittedAtNanos") >= first }
            }.orEmpty()
            val finalFrame = activity.viewerEngineFrameSnapshot()
            return@coroutineScope JSONObject().apply {
                put("wholePreparationMode", true); put("wholePreparationDeadlineMillis", 120_000)
                put("protocolDeadlineMillis", 150_000); put("protocolDeadlineFail", protocolDeadlineFail)
                put("protocolTimeoutFail", protocolDeadlineFail)
                put("gestureLimitFail", gestureLimitFail); put("failurePhase", failurePhase ?: JSONObject.NULL)
                put("documentEndpointMissFail", documentEndpointMissFail)
                put("wholePreparationState", readiness.state.name)
                put("preparationTimeoutFail", preparationTimedOut)
                put("timeoutFail", preparationTimedOut || protocolDeadlineFail)
                put("missingOriginalPageIdentities", org.json.JSONArray(missing.map(PageId::toString)))
                put("manifestPageCount", launch?.manifestPageIds?.size ?: JSONObject.NULL)
                put("verifiedPageCount", launch?.verifiedPages?.size ?: JSONObject.NULL)
                put("allFirstVerifiedPreparedAtNanos", launch?.allFirstVerifiedPreparedAtNanos ?: JSONObject.NULL)
                put("earlyGestureCount", EARLY_WHOLE_PREPARATION_GESTURES.size)
                put("earlyGesturePlan", org.json.JSONArray(EARLY_WHOLE_PREPARATION_GESTURES.map { gesture ->
                    JSONObject().put("direction", if (gesture.forward) "FORWARD" else "REVERSE").put("speed", gesture.speed.name)
                }))
                put("postPreparationGestureCount", postPreparationGestures)
                put("postPreparationGesturePlan", org.json.JSONArray(POST_PREPARATION_GESTURES.map {
                    JSONObject().put("direction", if (it) "FORWARD" else "REVERSE").put("speed", EngineTraversalGestureSpeed.FAST.name)
                }))
                put("traversedDocumentEndpoints", startToken.get() > reverseStartToken && endToken.get() > 0L)
                put("firstPageStartToken", startToken.get()); put("lastPageEndToken", endToken.get())
                put("endpointClassification", if (startToken.get() > reverseStartToken && endToken.get() > 0L)
                    "EXACT_DOCUMENT_SOURCE_ENDS" else "UNEXPECTED_OR_UNOBSERVED_SOURCE_END")
                put("gestures", gestures); put("maximumGestures", maximumGestures); put("readbackEnabled", false)
                put("firstCompleteViewportSubmittedAtNanos", firstFullSubmittedAtNanos ?: JSONObject.NULL)
                put("firstCurrentViewportObservedSubmittedAtNanos",
                    activity.viewerStartupTimingSnapshot()?.firstCurrentViewportObservedSubmittedAtNanos ?: JSONObject.NULL)
                put("afterFirstFullSuccessfulSubmissions", org.json.JSONArray(afterFirstFull))
                put("afterFirstFullSourceFrames", org.json.JSONArray(afterFirstFull))
                put("incompleteAfterFirstFullCount", afterFirstFull.count { !it.getBoolean("coverageQualified") })
                put("lostFrameEvidence", lostFrameEvidence)
                put("gestureInjection", "PLATFORM_TOUCHSCREEN_WITH_FRACTIONAL_COORDINATES")
                put("lastCapturedToken", lastCapturedToken.get()); put("stoppedAtNanos", stoppedAt)
                put("stopObservedUntilNanos", SystemClock.elapsedRealtimeNanos())
                put("lastSubmittedToken", finalFrame?.identity?.token ?: JSONObject.NULL)
                put("lastSubmittedFrameCaptured", false)
                put("allSourceRowsVerified", false); put("finalStopVerified", false); put("corpusCredit", 0)
            }
        } else {
            swipe(true)
            while (startToken.get() == 0L) swipe(false)
            val forwardStartToken = lastObservedToken.get()
            while (endToken.get() <= forwardStartToken) swipe(true)
        }
        // This is the required final stopped interval, not a content-readiness gate.
        val stoppedAt = SystemClock.elapsedRealtimeNanos()
        // Fixed observation period for the ordinary fling tail, without probing content readiness.
        delay(2_000)
        captureStoppedScreen(0)
        delay(1_000)
        captureStoppedScreen(1)
        exportObservations()
        val finalFrame = activity.viewerEngineFrameSnapshot()
        JSONObject().apply {
            put("maximumDurationMillis", maximumDurationMillis); put("maximumCaptures", maximumCaptures)
            put("traversedDocumentEndpoints", startToken.get() > 0 && endToken.get() > startToken.get())
            put("gestures", gestures); put("readbackEnabled", readbackEnabled)
            put("fixedGestureMeasurement", fixedGestureDirections != null)
            put("wholePreparationMode", false)
            put("gestureInjection", "PLATFORM_TOUCHSCREEN_WITH_FRACTIONAL_COORDINATES")
            put("firstPageStartToken", startToken.get()); put("lastPageEndToken", endToken.get())
            put("lastCapturedToken", lastCapturedToken.get()); put("stoppedAtNanos", stoppedAt)
            put("stopObservedUntilNanos", SystemClock.elapsedRealtimeNanos())
            put("lastSubmittedToken", finalFrame?.identity?.token ?: JSONObject.NULL)
            put("lastSubmittedFrameCaptured", finalFrame?.identity?.token == lastCapturedToken.get())
            put("allSourceRowsVerified", false); put("finalStopVerified", false); put("corpusCredit", 0)
        }
    } finally { reader.cancelAndJoin() }
}
