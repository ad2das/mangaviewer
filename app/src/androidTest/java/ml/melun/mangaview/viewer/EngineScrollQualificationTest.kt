package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.activity.EngineCapturedEpisodeDocuments
import ml.melun.mangaview.activity.EngineTraversalGestureSpeed
import ml.melun.mangaview.activity.EngineViewerScreen
import ml.melun.mangaview.activity.injectEngineTraversalGesture
import ml.melun.mangaview.activity.withEngineCaptureViewer
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Staged viewer-scroll qualification capture.
 *
 * LOADING injects real normal alternating attempts while the loading UI owns input, records
 * attempt timestamps/ownership and receipts, and stops at the first complete viewport milestone
 * without replaying them. STREAMING then runs natural gestures to all-originals-ready. Every
 * steady/end/boundary stage afterwards accumulates 1000 measured moving display frames from
 * engine-frame evidence; endpoint/boundary stages split a setup region from a measured local
 * window activated by a fresh milestone and require genuine directional transitions.
 */
@RunWith(AndroidJUnit4::class)
class EngineScrollQualificationTest {
    private class MovingWindowDriver(private val leadingForward: Boolean) {
        private var index = 0

        fun next(): Boolean {
            val block = EngineScrollQualificationPolicy.REVERSAL_BLOCK_GESTURES
            val slot = index % (block * 2)
            index += 1
            return if (leadingForward) slot < block else slot >= block
        }
    }

    @Test fun stagedScrollQualificationCapture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val arguments = InstrumentationRegistry.getArguments()
        val episode = EpisodeId(
            SeriesId(
                SourceId(arguments.getString("captureSource") ?: "wfwf"),
                arguments.getString("captureSeries") ?: "comic:10001",
            ),
            arguments.getString("captureEpisode") ?: "1",
        )
        val kind = SeriesKind.valueOf(arguments.getString("captureKind") ?: "COMIC")
        val crossNextBoundary = arguments.getString("captureCrossNextBoundary") == "true"
        val output = File(context.getExternalFilesDir(null),
            "engine-scroll-qualification-${System.currentTimeMillis()}").apply { mkdirs() }
        val appGraph = (context.applicationContext as ViewerApplication).graph
        val graph = appGraph.engine
        val documents = EngineCapturedEpisodeDocuments()
        check(graph.episodeEvidenceObserver == null)
        graph.episodeEvidenceObserver = documents

        var viewer: EngineViewerScreen? = null
        var ledger: EngineScrollQualificationLedger? = null
        var recorder: ViewerWindowFrameRecorder? = null
        var gestures = 0
        val stageTimeouts = mutableListOf<String>()
        var firstViewportSubmittedAtNanos: Long? = null
        var allOriginalsReadyAtNanos: Long? = null
        var primaryFailure: Throwable? = null

        suspend fun inject(forward: Boolean, speed: EngineTraversalGestureSpeed) {
            injectEngineTraversalGesture(instrumentation, device, output, gestures, forward, speed)
            gestures += 1
            val activeLedger = requireNotNull(ledger)
            activeLedger.recordGesture()
            activeLedger.drain(requireNotNull(viewer), requireNotNull(recorder))
            check(activeLedger.deferredInputsWithoutProgress < 2_048L) {
                "Engine input pipeline stalled: ${activeLedger.deferredInputsWithoutProgress} " +
                    "consecutive DEFERRED inputs with no resolution " +
                    "(pending=${activeLedger.currentPendingInputCount}, gesture=$gestures)"
            }
            requireNotNull(viewer).viewerFailureSnapshot()?.let {
                throw AssertionError("Viewer failed during stage capture", it)
            }
        }

        suspend fun driveStage(
            plan: EngineScrollQualificationPolicy.StagePlan,
            speed: EngineTraversalGestureSpeed,
            direction: () -> Boolean,
            done: suspend () -> Boolean,
        ) {
            val activeLedger = requireNotNull(ledger)
            activeLedger.beginStage(plan.name)
            val deadline = SystemClock.elapsedRealtime() + plan.budgetMillis
            var index = 0
            var timedOut = false
            while (!done() && index < plan.maxGestures) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    timedOut = true
                    break
                }
                inject(direction(), speed)
                index += 1
            }
            activeLedger.endStage(budgetExhausted = timedOut || !done())
            if (timedOut || !done()) stageTimeouts += plan.name
        }

        /** Setup region until a fresh milestone, then a measured local window with transitions. */
        suspend fun runLocalWindow(
            plan: EngineScrollQualificationPolicy.StagePlan,
            setupSpeed: EngineTraversalGestureSpeed,
            setupDirection: () -> Boolean,
            milestone: () -> Boolean,
            oscillatorLeadingForward: Boolean,
            resetBoundaryOnBegin: Boolean = false,
        ) {
            val activeLedger = requireNotNull(ledger)
            activeLedger.beginStage(plan.name)
            // h9: clear the one-shot boundary latches AFTER beginStage, so frames arriving
            // between a pre-stage reset and the stage start cannot latch tokens the milestone
            // then rejects as predating the stage.
            if (resetBoundaryOnBegin) activeLedger.resetBoundaryObservations()
            var index = 0
            var timedOut = true
            val setupDeadline = SystemClock.elapsedRealtime() + plan.budgetMillis
            while (index < plan.maxGestures && SystemClock.elapsedRealtime() < setupDeadline) {
                if (milestone()) {
                    timedOut = false
                    activeLedger.activateMeasurement()
                    break
                }
                inject(setupDirection(), setupSpeed)
                index += 1
            }
            if (timedOut) {
                activeLedger.endStage(budgetExhausted = true)
                stageTimeouts += plan.name
                return
            }
            val driver = MovingWindowDriver(oscillatorLeadingForward)
            val windowDeadline = SystemClock.elapsedRealtime() + plan.budgetMillis
            val target = plan.targetMovingFrames.toLong()
            val minimumTransitions = EngineScrollQualificationPolicy.MIN_DIRECTIONAL_TRANSITIONS.toLong()
            while (SystemClock.elapsedRealtime() < windowDeadline &&
                (activeLedger.stageMeasuredFrames(plan.name) < target ||
                    activeLedger.stageMeasuredDirectionChanges(plan.name) < minimumTransitions)
            ) {
                inject(driver.next(), EngineTraversalGestureSpeed.FAST)
            }
            val complete = activeLedger.stageMeasuredFrames(plan.name) >= target &&
                activeLedger.stageMeasuredDirectionChanges(plan.name) >= minimumTransitions
            activeLedger.endStage(budgetExhausted = !complete)
            if (!complete) stageTimeouts += plan.name
        }

        try {
            withEngineCaptureViewer(
                instrumentation, output, episode, kind,
                arguments.getString("catalogUi") == "true",
                afterViewerClosed = { activity ->
                    withTimeout(30_000) { activity.awaitEngineClosed() }
                    instrumentation.runOnMainSync { }
                    val activeRecorder = requireNotNull(recorder)
                    val activeLedger = requireNotNull(ledger)
                    activeLedger.close(activity, activeRecorder)
                    activeRecorder.close()
                    val integrity = activeLedger.integrityFailures()
                    check(integrity.isEmpty()) {
                        "Qualification evidence integrity failures: $integrity"
                    }
                    File(output, "summary.json").writeText(JSONObject().apply {
                        put("scope", "ENGINE_SCROLL_QUALIFICATION_STAGED")
                        put("schemaVersion", EngineScrollQualificationPolicy.SCHEMA_VERSION)
                        put("gestures", gestures)
                        put("crossNextBoundary", crossNextBoundary)
                        put("stageTimeouts", JSONArray(stageTimeouts))
                        put("stoppedAtNanos", activeLedger.stoppedAtNanos)
                        put("loadingAttemptCount", activeLedger.loadingAttemptCount)
                        put("firstCompleteViewportSubmittedAtNanos",
                            firstViewportSubmittedAtNanos ?: JSONObject.NULL)
                        put("allOriginalsReadyAtNanos", allOriginalsReadyAtNanos ?: JSONObject.NULL)
                        put("firstCompleteDisplayPresentAtNanos",
                            activeLedger.firstCompleteDisplayPresent)
                        put("lostFrameEvidence", activeLedger.frameLostCount)
                        put("inputLostEvidence", activeLedger.inputLostCount)
                        put("motionHistoryOverwritten", activeLedger.motionOverwritten)
                        put("physicalPresentationVerified", false)
                        put("rawInputBindingAvailable", false)
                        put("corpusCredit", 0)
                    }.toString(2))
                },
            ) { activity ->
                viewer = activity
                // Diagnostic-only: raise the input journal bound before the first viewer input so
                // whole-traversal evidence is never overwritten between drains.
                activity.reserveWholeTraversalInputEvidence()
                recorder = ViewerWindowFrameRecorder(activity.window)
                // A plan served from the persisted store replays without an observation event, so the
                // ledger's page bounds fall back to the engine snapshot's plan for the episode.
                var cachedPageBounds: Pair<PageId, PageId>? = null
                val activeLedger = EngineScrollQualificationLedger(
                    output, episode,
                    pageBoundsProvider = {
                        documents.pageBounds(episode) ?: cachedPageBounds ?: run {
                            val pages = activity.viewerEngineSnapshot()?.plans?.get(episode)?.pages
                            if (pages.isNullOrEmpty()) {
                                null
                            } else {
                                (pages.first().pageId to pages.last().pageId).also {
                                    cachedPageBounds = it
                                }
                            }
                        }
                    },
                    nextEpisodeProvider = {
                        activity.viewerEngineSnapshot()?.plans?.get(episode)?.manifest?.nextEpisodeId
                    },
                )
                ledger = activeLedger

                // LOADING: real normal alternating attempts while the loading UI owns input.
                // The ledger constructor pre-opens the initial LOADING stage; do not reopen it.
                val loading = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.LOADING)
                val loadingDeadline = SystemClock.elapsedRealtime() + loading.budgetMillis
                var loadingTimedOut = true
                var attempt = 0
                while (attempt < loading.maxGestures && SystemClock.elapsedRealtime() < loadingDeadline) {
                    val attemptAtNanos = System.nanoTime()
                    val surfaceReady = activity.isViewerInputSurfaceReady()
                    inject(attempt % 2 == 0, EngineTraversalGestureSpeed.NORMAL)
                    attempt += 1
                    val milestone = activity.viewerStartupTimingSnapshot()
                        ?.firstCompleteViewportSubmittedAtNanos
                    activeLedger.recordLoadingAttempt(attempt, attemptAtNanos, surfaceReady, milestone)
                    if (milestone != null) {
                        firstViewportSubmittedAtNanos = milestone
                        loadingTimedOut = false
                        break
                    }
                }
                activeLedger.endStage(budgetExhausted = loadingTimedOut)
                if (loadingTimedOut) stageTimeouts += EngineScrollQualificationPolicy.LOADING

                // STREAMING: natural gestures from the first viewport until all originals ready.
                val streaming = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.STREAMING)
                var streamingIndex = 0
                driveStage(streaming, EngineTraversalGestureSpeed.NORMAL,
                    direction = {
                        val forward = streamingIndex % 2 == 0
                        streamingIndex += 1
                        forward
                    },
                    done = {
                        val preparation = activity.viewerEngineDiagnosticSnapshot()
                            ?.content?.launchPreparation
                        val ready = preparation?.allFirstVerifiedPreparedAtNanos
                        if (ready != null) {
                            allOriginalsReadyAtNanos = ready
                            true
                        } else {
                            false
                        }
                    })

                // READY_ROUND_TRIP: 1000 measured moving display frames.
                val roundTrip = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.READY_ROUND_TRIP)
                var roundTripIndex = 0
                driveStage(roundTrip, EngineTraversalGestureSpeed.FAST,
                    direction = {
                        val forward = roundTripIndex % 2 == 0
                        roundTripIndex += 1
                        forward
                    },
                    done = {
                        activeLedger.stageMeasuredFrames(roundTrip.name) >=
                            roundTrip.targetMovingFrames.toLong()
                    })

                // FAST_REVERSE: deliberate reversal blocks with genuine direction transitions.
                val reverse = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.FAST_REVERSE)
                val reverseDriver = MovingWindowDriver(leadingForward = false)
                driveStage(reverse, EngineTraversalGestureSpeed.FAST,
                    direction = { reverseDriver.next() },
                    done = {
                        activeLedger.stageMeasuredFrames(reverse.name) >=
                            reverse.targetMovingFrames.toLong() &&
                            activeLedger.stageMeasuredDirectionChanges(reverse.name) >=
                            EngineScrollQualificationPolicy.MIN_DIRECTIONAL_TRANSITIONS.toLong()
                    })

                // ENDPOINT_END: setup to a fresh end milestone, then measured local window.
                val endpointEnd = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.ENDPOINT_END)
                runLocalWindow(endpointEnd, EngineTraversalGestureSpeed.FAST,
                    setupDirection = { true },
                    milestone = { activeLedger.endTokenAtNanos >= activeLedger.activeStartedAtNanos &&
                        activeLedger.endTokenAtNanos > 0L },
                    oscillatorLeadingForward = true)

                // NEXT_BOUNDARY: fresh crossing, then measured local window around the boundary.
                // Push forward until the next episode is visible, then keep pushing until every
                // boundary latch is fresh. w2-probe (v1): the setup hovered the moment the next
                // source latch fired, but the anchor latch (scene anchor inside the next episode)
                // needs a longer forward push; the hover reversed before the anchor crossed the
                // seam and the milestone never completed (boundaryFrames 3261, exhausted). The
                // retreat first sampled the failure where the stage starts already past the fixed
                // next episode; retreating into the sampled episode keeps the crossing inside the
                // stage window.
                if (crossNextBoundary) {
                    val boundary = EngineScrollQualificationPolicy.stage(
                        EngineScrollQualificationPolicy.NEXT_BOUNDARY)
                    var boundaryRetreating = true
                    runLocalWindow(boundary, EngineTraversalGestureSpeed.NORMAL,
                        setupDirection = {
                            if (boundaryRetreating) {
                                val (targetVisible, nextVisibleNow) = activeLedger.latestBoundaryVisibility()
                                if (targetVisible && !nextVisibleNow) {
                                    boundaryRetreating = false
                                    true
                                } else {
                                    false
                                }
                            } else {
                                true
                            }
                        },
                        milestone = {
                            activeLedger.firstNextSourceAtNanos >= activeLedger.activeStartedAtNanos &&
                                activeLedger.firstNextAnchorAtNanos >= activeLedger.activeStartedAtNanos &&
                                activeLedger.completeNextFrameAtNanos >= activeLedger.activeStartedAtNanos &&
                                activeLedger.shareViewportAtNanos >= activeLedger.activeStartedAtNanos
                        },
                        oscillatorLeadingForward = true,
                        resetBoundaryOnBegin = true)
                }

                // ENDPOINT_START: setup to a fresh start milestone, then measured local window.
                val endpointStart = EngineScrollQualificationPolicy.stage(
                    EngineScrollQualificationPolicy.ENDPOINT_START)
                runLocalWindow(endpointStart, EngineTraversalGestureSpeed.FAST,
                    setupDirection = { false },
                    milestone = { activeLedger.startTokenAtNanos >= activeLedger.activeStartedAtNanos &&
                        activeLedger.startTokenAtNanos > 0L },
                    oscillatorLeadingForward = false)

                delay(EngineScrollQualificationPolicy.STOPPED_SETTLE_MILLIS)
                activeLedger.drain(activity, requireNotNull(recorder))
                activeLedger.markStopped()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (graph.episodeEvidenceObserver === documents) graph.episodeEvidenceObserver = null
            try {
                documents.exportAndClear(output)
            } catch (cleanup: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    /**
     * Long continuous very-fast fling stress. Every gesture is a 4-step/1 ms platform swipe
     * (~5 ms over half the viewport) chained with a short pause, so the reader crosses episode
     * boundaries under sustained maximum-velocity flings. The ledger's integrity counters and the
     * exported frame/input/motion artifacts are the evidence; the host-side gap analysis must find
     * zero moving-presentation gaps >= 100 ms and zero lost frame/input observations.
     */
    @Test fun continuousFastFlingStress() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val arguments = InstrumentationRegistry.getArguments()
        val episode = EpisodeId(
            SeriesId(
                SourceId(arguments.getString("captureSource") ?: "ntk"),
                arguments.getString("captureSeries") ?: "/webtoon/16972",
            ),
            arguments.getString("captureEpisode") ?: "/webtoon/16972/1445960",
        )
        val kind = SeriesKind.valueOf(arguments.getString("captureKind") ?: "COMIC")
        val durationMillis = arguments.getString("flingDurationMillis")?.toLongOrNull() ?: 120_000L
        val interGestureMillis = arguments.getString("flingInterGestureMillis")?.toLongOrNull() ?: 60L
        val output = File(context.getExternalFilesDir(null),
            "engine-fast-fling-${System.currentTimeMillis()}").apply { mkdirs() }

        var recorder: ViewerWindowFrameRecorder? = null
        var ledger: EngineScrollQualificationLedger? = null
        var gestures = 0
        var forcedReverseBursts = 0
        var firstViewportSubmittedAtNanos: Long? = null
        val startedAtMillis = SystemClock.elapsedRealtime()

        withEngineCaptureViewer(
            instrumentation, output, episode, kind,
            false,
            afterViewerClosed = { activity ->
                withTimeout(30_000) { activity.awaitEngineClosed() }
                instrumentation.runOnMainSync { }
                val activeRecorder = requireNotNull(recorder)
                val activeLedger = requireNotNull(ledger)
                activeLedger.close(activity, activeRecorder)
                activeRecorder.close()
                val integrity = activeLedger.integrityFailures()
                // The window's own HWUI frame metrics stay empty (the viewer renders through its
                // EGL surface), so the intended-vsync accounting comes from the recorded
                // Choreographer motion frames: each refreshed frame carries its intended vsync.
                var motionFrames = 0L
                var intendedVsyncMissed = 0L
                var maxMotionGapNanos = 0L
                runCatching {
                    val times = ArrayList<Long>(16_384)
                    File(output, "motion.jsonl").forEachLine { line ->
                        if (line.isNotBlank()) {
                            val at = JSONObject(line).optLong("frameTimeNanos", 0L)
                            if (at > 0L) times.add(at)
                        }
                    }
                    times.sort()
                    motionFrames = times.size.toLong()
                    for (position in 1 until times.size) {
                        val gap = times[position] - times[position - 1]
                        if (gap > maxMotionGapNanos) maxMotionGapNanos = gap
                        if (gap > INTENDED_VSYNC_NANOS) {
                            intendedVsyncMissed +=
                                (gap + INTENDED_VSYNC_NANOS / 2) / INTENDED_VSYNC_NANOS - 1
                        }
                    }
                }
                File(output, "summary.json").writeText(JSONObject().apply {
                    put("scope", "ENGINE_FAST_FLING_STRESS")
                    put("schemaVersion", EngineScrollQualificationPolicy.SCHEMA_VERSION)
                    put("gestures", gestures)
                    put("durationMillis", durationMillis)
                    put("interGestureMillis", interGestureMillis)
                    put("startedAtMillis", startedAtMillis)
                    put("firstCompleteViewportSubmittedAtNanos",
                        firstViewportSubmittedAtNanos ?: JSONObject.NULL)
                    put("lostFrameEvidence", activeLedger.frameLostCount)
                    put("inputLostEvidence", activeLedger.inputLostCount)
                    put("motionHistoryOverwritten", activeLedger.motionOverwritten)
                    put("motionFrameCount", motionFrames)
                    put("intendedVsyncMissedFrames", intendedVsyncMissed)
                    put("maxMotionGapMillis", maxMotionGapNanos / 1_000_000.0)
                    put("forcedReverseBursts", forcedReverseBursts)
                    put("integrityFailures", JSONArray(integrity))
                    put("passed", integrity.isEmpty())
                }.toString(2))
                check(integrity.isEmpty()) {
                    "Fast fling integrity failures: $integrity; evidence=${output.absolutePath}"
                }
            },
        ) { activity ->
            activity.reserveWholeTraversalInputEvidence()
            recorder = ViewerWindowFrameRecorder(activity.window)
            var cachedPageBounds: Pair<PageId, PageId>? = null
            val activeLedger = EngineScrollQualificationLedger(
                output, episode,
                pageBoundsProvider = {
                    cachedPageBounds ?: run {
                        val pages = activity.viewerEngineSnapshot()?.plans?.get(episode)?.pages
                        if (pages.isNullOrEmpty()) {
                            null
                        } else {
                            (pages.first().pageId to pages.last().pageId).also {
                                cachedPageBounds = it
                            }
                        }
                    }
                },
                nextEpisodeProvider = {
                    activity.viewerEngineSnapshot()?.plans?.get(episode)?.manifest?.nextEpisodeId
                },
            )
            ledger = activeLedger

            val viewportDeadline = SystemClock.elapsedRealtime() + 45_000L
            while (SystemClock.elapsedRealtime() < viewportDeadline) {
                val timing = activity.viewerStartupTimingSnapshot()
                if (timing?.firstCompleteViewportSubmittedAtNanos != null) {
                    firstViewportSubmittedAtNanos = timing.firstCompleteViewportSubmittedAtNanos
                    break
                }
                SystemClock.sleep(20)
            }
            check(firstViewportSubmittedAtNanos != null) { "Fast fling stress never saw a viewport" }
            activeLedger.endStage(budgetExhausted = false)
            activeLedger.beginStage(EngineScrollQualificationPolicy.STREAMING)
            activeLedger.activateMeasurement()
            SystemClock.sleep(1_500L)

            val deadline = SystemClock.elapsedRealtime() + durationMillis
            var index = 0
            var stalledGestures = 0
            var forcedReverseGestures = 0
            var movingBefore = activeLedger.stageMeasuredFrames(EngineScrollQualificationPolicy.STREAMING)
            while (SystemClock.elapsedRealtime() < deadline) {
                val forward = if (forcedReverseGestures > 0) false else index % 30 < 27
                injectEngineTraversalGesture(
                    instrumentation, device, output, index, forward,
                    EngineTraversalGestureSpeed.FLING,
                )
                if (forcedReverseGestures > 0) forcedReverseGestures -= 1
                index += 1
                gestures = index
                activeLedger.recordGesture()
                activeLedger.drain(activity, requireNotNull(recorder))
                check(activeLedger.deferredInputsWithoutProgress < 2_048L) {
                    "Fast fling input pipeline stalled: ${activeLedger.deferredInputsWithoutProgress} " +
                        "consecutive DEFERRED inputs with no resolution (gesture=$index)"
                }
                activity.viewerFailureSnapshot()?.let {
                    throw AssertionError("Viewer failed during fast fling stress", it)
                }
                // A chain of forward flings eventually clamps against the episode end (or the start
                // after a reverse burst) and the scene stops moving; a stalled run would spend the
                // rest of the soak on a static scene, so the chain turns around when it happens.
                val movingNow = activeLedger.stageMeasuredFrames(EngineScrollQualificationPolicy.STREAMING)
                if (movingNow > movingBefore) {
                    stalledGestures = 0
                } else {
                    stalledGestures += 1
                    if (stalledGestures >= FLING_STALL_GESTURES) {
                        forcedReverseGestures = FLING_REVERSE_BURST
                        forcedReverseBursts += 1
                        stalledGestures = 0
                    }
                }
                movingBefore = movingNow
                SystemClock.sleep(interGestureMillis)
            }
            SystemClock.sleep(1_200L)
            activeLedger.drain(activity, requireNotNull(recorder))
            activeLedger.endStage(budgetExhausted = false)
            activeLedger.markStopped()
        }
    }
}

/** Gestures without a newly measured moving frame before the chain turns around. */
private const val FLING_STALL_GESTURES = 10

/** Reverse gestures injected after a stalled chain so the soak keeps covering content. */
private const val FLING_REVERSE_BURST = 12

/** The emulator display's only supported rate is 60.000004 Hz; intended-vsync arithmetic uses 60 Hz. */
private const val INTENDED_VSYNC_NANOS = 16_666_666L
