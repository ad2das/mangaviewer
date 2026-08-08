package ml.melun.mangaview.macrobenchmark

import android.app.Activity
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real-UI, one-work cold iteration. The host orchestrator launches this test once per work
 * after clearing both packages, so Macrobenchmark's process reset cannot be mistaken for an app
 * image/content-cache reset.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
class NtkColdViewerMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private var fastFunctionalTriageEnabled = false

    @Test
    fun coldViewerRandomWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instrumentation)
        val workType = args.requiredString("ntkWorkType").lowercase()
        require(workType == "webtoon" || workType == "manhwa") {
            "ntkWorkType must be webtoon or manhwa"
        }
        val typeImageSlaMs = if (workType == "webtoon") {
            WEBTOON_FIRST_IMAGE_SLA_MS
        } else {
            MANHWA_FIRST_IMAGE_SLA_MS
        }
        val workId = args.requiredString("ntkWorkId")
        val workTitle = args.requiredBase64Utf8("ntkWorkTitleBase64")
        val episodeTitle = args.optionalBase64Utf8("ntkEpisodeTitleBase64")
        val episodePath = args.getString("ntkEpisodePath").orEmpty().trim()
        val expectedAdjacentEpisodePath = args.getString("ntkExpectedAdjacentEpisodePath")
            .orEmpty()
            .trim()
        val expectedAdjacentPageCount = args.getString("ntkExpectedAdjacentPageCount")
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
        val resumePercent = args.getString("ntkResumePercent")
            ?.toIntOrNull()
        val resumeMode = resumePercent != null
        val currentPageCount = args.getString("ntkCurrentPageCount")
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
        val resumePage = args.getString("ntkResumePage")
            ?.toIntOrNull()
            ?: -1
        val resumeOffset = args.getString("ntkResumeOffset")
            ?.toIntOrNull()
            ?: ResumeTraversalPlan.resumeOffsetPx
        val siteRoot = args.getString("ntkSiteRoot").orEmpty().trim()
        require(episodePath.isNotBlank()) {
            "ntkEpisodePath must identify the measured non-latest episode"
        }
        require(expectedAdjacentEpisodePath.isNotBlank()) {
            "ntkExpectedAdjacentEpisodePath is mandatory for cold qualification"
        }
        require(expectedAdjacentEpisodePath != episodePath) {
            "The forward-adjacent episode must differ from the measured episode"
        }
        require(expectedAdjacentPageCount >= ADJACENT_REQUIRED_RUNWAY_PAGES) {
            "The selected adjacent episode must prove at least " +
                "$ADJACENT_REQUIRED_RUNWAY_PAGES canonical pages; " +
                "actual=$expectedAdjacentPageCount"
        }
        if (resumeMode) {
            require(resumePercent in ResumeTraversalPlan.supportedPercents) {
                "ntkResumePercent must be 25, 50, or 90"
            }
            require(currentPageCount > 0) {
                "ntkCurrentPageCount is mandatory for home Continue qualification"
            }
            require(resumePage == ResumeTraversalPlan.resumePage(
                currentPageCount,
                requireNotNull(resumePercent),
            )) {
                "ntkResumePage=$resumePage does not match $resumePercent% of " +
                    "$currentPageCount canonical pages"
            }
            require(resumeOffset == ResumeTraversalPlan.resumeOffsetPx) {
                "The formal home Continue offset must be ${ResumeTraversalPlan.resumeOffsetPx}"
            }
            require(siteRoot.isNotBlank()) {
                "ntkSiteRoot is mandatory for benchmark-only Continue seeding"
            }
        }
        val expectedForwardPageCount = if (resumeMode) {
            ResumeTraversalPlan.forwardPageCount(currentPageCount, resumePage)
        } else {
            0
        }
        val caseId = args.getString("ntkCaseId")?.trim().orEmpty().ifBlank {
            "$workType-$workId"
        }
        val p0SignalNonce = if (resumeMode) newP0SignalNonce() else ""
        val p0SignalAction = if (resumeMode) P0_SIGNAL_ACTION_PREFIX + p0SignalNonce else ""
        val p0SignalChannel = if (resumeMode) {
            AdjacentP0SignalChannel(
                action = p0SignalAction,
                nonce = p0SignalNonce,
                caseId = caseId,
                expectedEpisodePath = expectedAdjacentEpisodePath,
                allowTerminalResumeInitialViewport =
                    resumePage == currentPageCount - 1 &&
                        expectedForwardPageCount == 1 && resumeOffset <= 0,
            )
        } else {
            null
        }
        val firstImageSlaMs = args.getString("ntkFirstImageSlaMs")
            ?.toLongOrNull()?.coerceIn(250L, 30_000L) ?: typeImageSlaMs
        val allImagesSlaMs = args.getString("ntkAllImagesSlaMs")
            ?.toLongOrNull()?.coerceIn(250L, 30_000L) ?: ALL_IMAGES_SLA_MS
        val sameProcessWarmReopen = !resumeMode && (
            args.getString("ntkSameProcessWarmReopen")
                ?.toBooleanStrictOrNull() ?: true
            )
        // Development triage still performs the identical cold production-UI traversal and
        // identity/readiness assertions. It omits only expensive host-side metrics that cannot
        // affect the app and reuses the APK's existing AOT compilation. Canonical qualification
        // never supplies this argument and therefore retains the complete metric/evidence set.
        val fastFunctionalTriage = args.getString("ntkFastFunctionalTriage")
            ?.toBooleanStrictOrNull() ?: false
        fastFunctionalTriageEnabled = fastFunctionalTriage
        val outputDirectory = File(
            requireNotNull(instrumentation.context.getExternalFilesDir("ntk-cold")),
            caseId.safeFileComponent()
        ).apply { mkdirs() }

        var clickElapsedNanos = 0L
        var actualElapsedNanos = 0L
        var actualDescription = ""
        var firstImageSlaPassed = false
        var allImagesReadyAtNanos = 0L
        var allImagesReadyPageCount = 0
        var allImagesSlaPassed = false
        val rotationSupported: Boolean? = null
        var passed = false
        var failure: Throwable? = null
        var warmAttempted = false
        var warmPassed = false
        var warmClickElapsedNanos = 0L
        var warmActualElapsedNanos = 0L
        var warmActualDescription = ""
        var warmFailure: Throwable? = null
        var forwardTraversalStartElapsedNanos = 0L
        var forwardTraversalEndElapsedNanos = 0L
        var forwardTraversalGestureCount = 0
        var adjacentTraversalGestureCount = 0
        var adjacentP0TraversalGestureCount = 0
        var adjacentRunwayTraversalGestureCount = 0
        var adjacentLastActualDescription = ""
        var adjacentLastSourceIndex = -1
        var adjacentObservedRunwayDrawableCount = 0
        var adjacentWorkStartedAtNanos = 0L
        var adjacentRunwayReadyAtNanos = 0L
        var adjacentRunwayTargetEpisode = ""
        var adjacentRunwayPageCount = 0
        var adjacentTotalPageCount = 0
        var forwardBoundaryReachedAtNanos = 0L
        var firstAdjacentActualAtNanos = 0L
        var firstAdjacentActualEpisode = ""
        var adjacentP0SeamMs = -1.0
        var runwayReadyBeforeTail = false
        var adjacentPhysicalRunwayPassed = false
        var adjacentPhysicallyObservedSources = ""
        var adjacentSourcePresentedAtNanos = List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
        var adjacentSourceGesturesAtPresentation =
            List(ADJACENT_REQUIRED_RUNWAY_PAGES) { -1 }
        var adjacentSourceSemanticObservedAtNanos =
            List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
        var adjacentSourceGesturesAtSemanticProof =
            List(ADJACENT_REQUIRED_RUNWAY_PAGES) { -1 }
        var adjacentSourceProgressPassed = false
        var adjacentSourceProgressFailure = ""
        var homeContinueSeeded = false
        var homeContinueColdForceStopped = false
        var firstActualResumePage = -1
        var resumeFirstActualMatched = false
        var inputMetrics = ContinuousInputMetrics.empty()
        var physicalMotionIdleAtNanos = 0L
        val adjacentP0Timing = AdjacentP0TimingProbe()
        var traceProcessingFailure: Throwable? = null

        try {
            if (resumeMode) {
                requireNotNull(p0SignalChannel).register(
                    instrumentation.context,
                    instrumentation.uiAutomation,
                )
                homeContinueSeeded = seedHomeContinueState(
                    workType = workType,
                    workId = workId,
                    workTitle = workTitle,
                    episodeTitle = episodeTitle,
                    currentPath = episodePath,
                    nextPath = expectedAdjacentEpisodePath,
                    siteRoot = siteRoot,
                    currentPageCount = currentPageCount,
                    nextPageCount = expectedAdjacentPageCount,
                    resumePage = resumePage,
                    resumeOffset = resumeOffset,
                    p0SignalAction = p0SignalAction,
                    p0SignalNonce = p0SignalNonce,
                    p0SignalCaseId = caseId,
                )
                val forceStopOutput = device.executeShellCommand(
                    "am force-stop ${TARGET_PACKAGE.shellQuote()}"
                )
                check(forceStopOutput.isBlank()) {
                    "Target force-stop after Continue seed failed: $forceStopOutput"
                }
                homeContinueColdForceStopped = true
                DirectInputInjector.prepareBackend()
            }
            try {
                benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = if (fastFunctionalTriage) {
                    listOf(
                        TraceSectionMetric("ViewerOpen", TraceSectionMetric.Mode.First),
                        TraceSectionMetric("ViewerAllImagesReady", TraceSectionMetric.Mode.First),
                        TraceSectionMetric("ImageRequest", TraceSectionMetric.Mode.Count),
                        TraceSectionMetric("ImageDecode", TraceSectionMetric.Mode.Max),
                        // Fast mode is allowed to omit startup/memory evidence, never real
                        // SurfaceFlinger cadence. Keeping this query here catches renderer
                        // regressions before a broader functional replay can waste minutes.
                        ViewerScrollTraceMetric()
                    )
                } else {
                    listOf(
                        StartupTimingMetric(),
                        MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
                        MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
                        TraceSectionMetric("ViewerOpen", TraceSectionMetric.Mode.First),
                        TraceSectionMetric("ViewerAllImagesReady", TraceSectionMetric.Mode.First),
                        TraceSectionMetric("ImageRequest", TraceSectionMetric.Mode.Count),
                        TraceSectionMetric("ImageDecode", TraceSectionMetric.Mode.Max),
                        TraceSectionMetric("ViewerHwuiFrameCommit", TraceSectionMetric.Mode.Max),
                        TraceSectionMetric("ViewerSurfaceControlLatch", TraceSectionMetric.Mode.Max),
                        TraceSectionMetric(
                            "ViewerSurfaceQueueSubmission",
                            TraceSectionMetric.Mode.Max
                        ),
                        ViewerScrollTraceMetric()
                    )
                },
                // Compile APK code only. This does not launch the target, resolve DNS, open a
                // viewer, or populate any page/image cache. The measured process and all content
                // stores remain cold, while the 100+ ART JIT compilations observed during the
                // first forward fling cannot steal host-CPU time from input and RenderThread.
                compilationMode = if (fastFunctionalTriage) {
                    CompilationMode.Ignore()
                } else {
                    CompilationMode.Full()
                },
                iterations = 1,
                startupMode = StartupMode.COLD,
                setupBlock = {
                    pressHome()
                }
                ) {
                startActivityAndWait()
                val episode = if (resumeMode) {
                    findHomeContinueCard(device, workType, workTitle)
                } else {
                    navigateFromLauncherToEpisodeList(device, workType, workId, workTitle)
                    findEpisodeRow(device, episodePath)
                }

                // Arm the exact cross-process observer before the click, but keep its input gate
                // closed until the first current-episode pixels are observed. A short terminal
                // Continue can physically include next p0 in that first opaque viewport; arming
                // here preserves that one-shot compositor proof without injecting any input or
                // moving image work ahead of the click.
                val continuousInput = if (resumeMode) {
                    ContinuousForwardInput(
                        displayWidth = device.displayWidth,
                        displayHeight = device.displayHeight,
                    ).also {
                        try {
                            it.prepareAndAwait()
                            requireNotNull(p0SignalChannel).arm(it)
                        } catch (throwable: Throwable) {
                            it.requestStop()
                            throw throwable
                        }
                    }
                } else {
                    null
                }

                val actualObservation = try {
                    clickAndAwaitActualImage(
                        device,
                        episode,
                        episodePath,
                        firstImageSlaMs,
                        "first HWUI-committed actual work-image draw",
                        expectedSourcePage = resumePage.takeIf { resumeMode },
                    )
                } catch (throwable: Throwable) {
                    continuousInput?.requestStop()
                    throw throwable
                }
                clickElapsedNanos = actualObservation.clickElapsedNanos
                actualElapsedNanos = actualObservation.observedElapsedNanos
                actualDescription = actualObservation.description
                firstImageSlaPassed = actualElapsedNanos >= clickElapsedNanos &&
                    actualElapsedNanos - clickElapsedNanos <= firstImageSlaMs * 1_000_000L
                if (episodePath.isNotBlank()) {
                    val actualIdentity = ACTUAL_IDENTITY_PATTERN.matchEntire(actualDescription)
                    check(actualIdentity != null && actualIdentity.groupValues[1] == episodePath) {
                        "HWUI-committed image belongs to a different episode: expected=$episodePath " +
                            "actual=$actualDescription"
                    }
                    firstActualResumePage = if (resumeMode) {
                        check(
                            actualDescription.telemetryValue("firstActualEpisode") == episodePath
                        ) {
                            "Home Continue first commit belongs to a different episode: " +
                                "expected=$episodePath actual=$actualDescription"
                        }
                        actualDescription.telemetryNanos("firstActualSourcePage").toInt()
                    } else {
                        actualIdentity.groupValues[2].toInt()
                    }
                    resumeFirstActualMatched = !resumeMode || firstActualResumePage == resumePage
                    check(resumeFirstActualMatched) {
                        "Home Continue committed the wrong first page: expected=$resumePage " +
                            "actual=$firstActualResumePage description=$actualDescription"
                    }
                }
                if (resumeMode) {
                    ResumeTailAllImagesEvidencePolicy.fromActualDescription(
                        description = actualDescription,
                        expectedEpisodePath = episodePath,
                        expectedResumePage = resumePage,
                        expectedForwardPageCount = expectedForwardPageCount,
                        clickElapsedNanos = clickElapsedNanos,
                        observedElapsedNanos = actualElapsedNanos,
                    )?.let { evidence ->
                        // Preserve this independent current-episode proof before adjacent traversal.
                        // A later p0 IPC/semantic failure must still fail the case, but must not
                        // rewrite an already measured resume-tail completion as zero/unmeasured.
                        allImagesReadyPageCount = evidence.pageCount
                        allImagesReadyAtNanos = evidence.readyAtNanos
                        allImagesSlaPassed =
                            evidence.readyAtNanos - clickElapsedNanos <=
                                allImagesSlaMs * 1_000_000L
                    }
                }
                // Model the dominant reading UX: after the first pixels, continuously advance
                // toward later pages. Qualification deliberately performs no screenshot or idle
                // between the first actual draw and the first gesture, so the forward runway must
                // keep up from a genuinely cold entry rather than benefiting from automation time.
                // No reverse-only capacity or alternating gesture is used. Every gesture belongs
                // to the observed traversal below: an unobserved three-fling burst could cross a
                // short manga and its four-page adjacent runway before accessibility sampled it.
                forwardTraversalStartElapsedNanos = SystemClock.elapsedRealtimeNanos()
                continuousInput?.releaseSchedule()

                // An appendable reader has no stable global bottom. The exact p0 IPC supplies the
                // seam timestamp without stopping this producer. The same uninterrupted reader-rate
                // workload then physically traverses p0-p3; prepared-runway telemetry alone is not
                // accepted as proof that those pages were visible without a blank or wait.
                val adjacentProof = AdjacentEpisodeProofGate(
                    expectedEpisodePath = expectedAdjacentEpisodePath,
                    requiredRunwayPageCount = ADJACENT_REQUIRED_RUNWAY_PAGES,
                    requireSourceProgress = resumeMode,
                )
                adjacentProof.forwardEvidence.observeActualDescription(actualDescription)
                val materializeAdjacentEvidence = {
                    p0SignalChannel?.payload?.let(
                        adjacentProof.forwardEvidence::observeExactP0Ipc
                    )
                    p0SignalChannel?.exactRunwayReadyEvidence()?.let(
                        adjacentProof.forwardEvidence::observeExactRunwayReadyIpc
                    )
                    adjacentPhysicallyObservedSources =
                        adjacentProof.observedSourceIndices.joinToString(",")
                    adjacentObservedRunwayDrawableCount = adjacentProof.runwayDrawableCount
                    adjacentPhysicalRunwayPassed = adjacentProof.isComplete
                    adjacentSourcePresentedAtNanos = p0SignalChannel
                        ?.presentedTimestamps() ?: adjacentProof.presentedTimestamps
                    adjacentSourceGesturesAtPresentation = p0SignalChannel
                        ?.gesturesAtPresentation() ?: adjacentProof.gesturesAtPresentation
                    adjacentSourceSemanticObservedAtNanos = p0SignalChannel
                        ?.semanticObservedTimestamps() ?: adjacentProof.semanticObservedTimestamps
                    adjacentSourceGesturesAtSemanticProof = p0SignalChannel
                        ?.gesturesAtSemanticProof() ?: adjacentProof.gesturesAtSemanticProof
                    adjacentSourceProgressPassed = adjacentProof.sourceProgressComplete
                    adjacentSourceProgressFailure =
                        adjacentProof.sourceProgressFailure.orEmpty()
                    val evidence = adjacentProof.forwardEvidence.snapshot
                    adjacentWorkStartedAtNanos = evidence.adjacentWorkStartedAtNanos
                    adjacentRunwayReadyAtNanos = evidence.adjacentRunwayReadyAtNanos
                    adjacentRunwayTargetEpisode = evidence.adjacentRunwayTargetEpisode
                    adjacentRunwayPageCount = evidence.adjacentRunwayPageCount
                    adjacentTotalPageCount = evidence.adjacentTotalPageCount
                    forwardBoundaryReachedAtNanos = evidence.forwardBoundaryReachedAtNanos
                    if (evidence.firstAdjacentActualAtNanos > 0L) {
                        firstAdjacentActualAtNanos = evidence.firstAdjacentActualAtNanos
                    }
                    if (evidence.firstAdjacentActualEpisode.isNotBlank()) {
                        firstAdjacentActualEpisode = evidence.firstAdjacentActualEpisode
                    }
                    adjacentP0SeamMs = if (
                        forwardBoundaryReachedAtNanos > 0L &&
                        firstAdjacentActualAtNanos >= forwardBoundaryReachedAtNanos
                    ) {
                        (firstAdjacentActualAtNanos - forwardBoundaryReachedAtNanos) / 1_000_000.0
                    } else {
                        -1.0
                    }
                    runwayReadyBeforeTail = adjacentRunwayReadyAtNanos > 0L &&
                        forwardBoundaryReachedAtNanos > 0L &&
                        adjacentRunwayReadyAtNanos <= forwardBoundaryReachedAtNanos
                }
                val adjacentP0 = try {
                    driveIntoExpectedAdjacentEpisode(
                        device,
                        episodePath,
                        expectedAdjacentEpisodePath,
                        continuousInput,
                        adjacentP0Timing,
                        p0SignalChannel,
                        adjacentProof,
                    )
                } catch (failure: Throwable) {
                    try {
                        if (continuousInput != null) {
                            inputMetrics = continuousInput.stopAndAwait()
                        }
                    } finally {
                        materializeAdjacentEvidence()
                    }
                    throw failure
                }
                if (adjacentP0Timing.firstAdjacentActualAtNanos > 0L) {
                    firstAdjacentActualAtNanos = adjacentP0Timing.firstAdjacentActualAtNanos
                    firstAdjacentActualEpisode = expectedAdjacentEpisodePath
                }
                adjacentP0TraversalGestureCount = adjacentP0.gestures
                val adjacent = try {
                    driveThroughExpectedAdjacentRunway(
                        device = device,
                        launchEpisodePath = episodePath,
                        expectedEpisodePath = expectedAdjacentEpisodePath,
                        expectedViewerGeneration = adjacentP0.viewerGeneration,
                        continuousInput = continuousInput,
                        p0Signal = p0SignalChannel,
                        proof = adjacentProof,
                    )
                } finally {
                    try {
                        if (continuousInput != null) {
                            inputMetrics = continuousInput.stopAndAwait()
                        }
                    } finally {
                        materializeAdjacentEvidence()
                    }
                }
                adjacentRunwayTraversalGestureCount = if (resumeMode) {
                    (inputMetrics.gestureCount - adjacentP0TraversalGestureCount).coerceAtLeast(0)
                } else {
                    adjacent.gestures
                }
                adjacentTraversalGestureCount = if (resumeMode) {
                    inputMetrics.gestureCount
                } else {
                    adjacentP0TraversalGestureCount + adjacentRunwayTraversalGestureCount
                }
                forwardTraversalGestureCount += adjacentTraversalGestureCount
                adjacentLastActualDescription = adjacent.actualDescription
                adjacentLastSourceIndex = adjacent.sourceIndex
                adjacentObservedRunwayDrawableCount = adjacent.runwayDrawableCount
                if (resumeMode) {
                    physicalMotionIdleAtNanos = requireNotNull(p0SignalChannel)
                        .awaitPhysicalMotionIdleAfter(
                            inputMetrics.endElapsedNanos,
                            adjacent.viewerGeneration,
                            PHYSICAL_MOTION_IDLE_TIMEOUT_MS,
                        )
                }
                forwardTraversalEndElapsedNanos = SystemClock.elapsedRealtimeNanos()
                // Materialize the app-owned seam/readiness evidence before any harness-quality
                // assertion. A contaminated input producer must invalidate the run, but it must
                // not erase an already observed boundary or leave the diagnostic seam at -1.
                materializeAdjacentEvidence()
                capture(device, outputDirectory, "bottom")

                if (allImagesReadyAtNanos <= 0L || allImagesReadyPageCount <= 0) {
                    val allReadyDescription = device.requireObject(
                        ALL_IMAGES_READY_SELECTOR,
                        UI_TIMEOUT_MS,
                        if (resumeMode) {
                            "resume-to-tail images render-ready state"
                        } else {
                            "all canonical images render-ready state"
                        }
                    ).contentDescription.orEmpty()
                    val allReadyIdentity = ALL_IMAGES_READY_PATTERN.matchEntire(allReadyDescription)
                        ?: error("Malformed all-images render-ready state: $allReadyDescription")
                    allImagesReadyPageCount = allReadyIdentity.groupValues[1].toInt()
                    allImagesReadyAtNanos = allReadyIdentity.groupValues[2].toLong()
                    allImagesSlaPassed = allImagesReadyPageCount > 0 &&
                        allImagesReadyAtNanos >= clickElapsedNanos &&
                        allImagesReadyAtNanos - clickElapsedNanos <= allImagesSlaMs * 1_000_000L
                }
                if (resumeMode) {
                    check(allImagesReadyPageCount == expectedForwardPageCount) {
                        "Home Continue forward-tail count mismatch: " +
                            "expected=$expectedForwardPageCount actual=$allImagesReadyPageCount"
                    }
                    requireReaderRateInput(inputMetrics, "resume-through-adjacent-p3")
                    val gesturesAtP0Signal = requireNotNull(p0SignalChannel).gesturesAtSignal
                    check(gesturesAtP0Signal >= 0 &&
                        inputMetrics.gestureCount > gesturesAtP0Signal
                    ) {
                        "Physical input did not remain continuous after adjacent p0: " +
                            "atSignal=$gesturesAtP0Signal total=${inputMetrics.gestureCount}"
                    }
                }
                check(adjacentWorkStartedAtNanos >= allImagesReadyAtNanos) {
                    "Adjacent work competed with the current episode: " +
                        "work=$adjacentWorkStartedAtNanos allReady=$allImagesReadyAtNanos"
                }
                check(adjacentRunwayReadyAtNanos > 0L) {
                    "Adjacent p0-p3 readiness telemetry was never published"
                }
                check(adjacentRunwayTargetEpisode == expectedAdjacentEpisodePath) {
                    "Adjacent runway targeted the wrong episode: " +
                        "expected=$expectedAdjacentEpisodePath actual=$adjacentRunwayTargetEpisode"
                }
                check(adjacentTotalPageCount == expectedAdjacentPageCount) {
                    "Adjacent total page count did not match selection: " +
                        "expected=$expectedAdjacentPageCount actual=$adjacentTotalPageCount"
                }
                val requiredRunwayPages = ADJACENT_REQUIRED_RUNWAY_PAGES
                check(adjacentRunwayPageCount == requiredRunwayPages) {
                    "Adjacent p0-p3 readiness telemetry was incomplete: " +
                        "ready=$adjacentRunwayPageCount required=$requiredRunwayPages"
                }
                check(adjacentTotalPageCount >= adjacentRunwayPageCount) {
                    "Adjacent total page proof is invalid: " +
                        "total=$adjacentTotalPageCount runway=$adjacentRunwayPageCount"
                }
                check(forwardBoundaryReachedAtNanos > 0L) {
                    "Launch episode bottom was never physically committed"
                }
                check(firstAdjacentActualAtNanos >= forwardBoundaryReachedAtNanos) {
                    "Expected adjacent p0 committed before the launch bottom"
                }
                check(firstAdjacentActualEpisode == expectedAdjacentEpisodePath) {
                    "First adjacent pixels belonged to the wrong episode: " +
                        "expected=$expectedAdjacentEpisodePath actual=$firstAdjacentActualEpisode"
                }
                check(adjacentP0SeamMs <= ADJACENT_P0_SEAM_SLA_MS) {
                    "Adjacent p0 seam exceeded ${ADJACENT_P0_SEAM_SLA_MS}ms: " +
                        "${adjacentP0SeamMs}ms"
                }
                check(adjacentPhysicalRunwayPassed &&
                    adjacentObservedRunwayDrawableCount == requiredRunwayPages &&
                    adjacentLastSourceIndex == requiredRunwayPages - 1
                ) {
                    "p0-p3 were not each physically committed under reader-rate input: " +
                        "sources=$adjacentPhysicallyObservedSources " +
                        "observed=$adjacentObservedRunwayDrawableCount " +
                        "last=$adjacentLastSourceIndex required=$requiredRunwayPages"
                }
                if (resumeMode) {
                    check(adjacentSourceProgressPassed &&
                        adjacentSourcePresentedAtNanos.zipWithNext().all { (left, right) ->
                            left > 0L && right > left
                        } &&
                        adjacentSourceSemanticObservedAtNanos.all { it > 0L }
                    ) {
                        "p0-p3 source presentation/progress proof failed: " +
                            adjacentSourceProgressFailure.ifBlank { "incomplete checkpoints" }
                    }
                }
                device.pressBack()
                val returnSelector = if (resumeMode) {
                    By.res(TARGET_PACKAGE, "bottom_nav")
                } else {
                    By.res(TARGET_PACKAGE, "EpisodeList")
                }
                check(device.wait(Until.hasObject(returnSelector), UI_TIMEOUT_MS)) {
                    if (resumeMode) {
                        "Continue viewer did not return to the home screen"
                    } else {
                        "Viewer did not return to the episode screen"
                    }
                }
                }
            } catch (throwable: Throwable) {
                val scenarioCompleted = clickElapsedNanos > 0L && actualElapsedNanos > 0L &&
                    allImagesReadyAtNanos > 0L && allImagesReadyPageCount > 0 &&
                    forwardTraversalEndElapsedNanos > 0L
                if (!scenarioCompleted || !throwable.isTraceProcessorInfrastructureFailure()) {
                    throw throwable
                }
                // AndroidX 1.4.1 can leave its in-process parser in the unrecoverable state even
                // though the finalized Perfetto protobuf is sound (the same emitted trace parses
                // successfully with trace_processor_shell). Do not let that infrastructure error
                // replace the independently observed physical-draw/SLA verdict. The trace remains
                // attached for deterministic host-side processing and the parser fault is recorded
                // separately below; no viewer frame or timing sample is excluded.
                traceProcessingFailure = throwable
            }
            check(firstImageSlaPassed) {
                "First actual image exceeded ${firstImageSlaMs}ms: " +
                    "${(actualElapsedNanos - clickElapsedNanos) / 1_000_000.0}ms"
            }
            check(allImagesSlaPassed) {
                val scope = if (resumeMode) "resume-to-tail" else "canonical"
                "All $allImagesReadyPageCount $scope images exceeded ${allImagesSlaMs}ms: " +
                    "${(allImagesReadyAtNanos - clickElapsedNanos) / 1_000_000.0}ms"
            }
            passed = true

            if (sameProcessWarmReopen) {
                // Keep the diagnostic reopen in the same target process, but outside
                // measureRepeated. AndroidX Memory/Startup plus the native BufferQueue cadence
                // results above therefore describe only the cold iteration and cannot be diluted
                // by warm frames.
                warmAttempted = true
                runCatching {
                    val warmEpisode = findEpisodeRow(device, episodePath)
                    val warmObservation = clickAndAwaitActualImage(
                        device,
                        warmEpisode,
                        episodePath,
                        firstImageSlaMs,
                        "same-process warm HWUI-committed actual work-image draw"
                    )
                    warmClickElapsedNanos = warmObservation.clickElapsedNanos
                    warmActualElapsedNanos = warmObservation.observedElapsedNanos
                    warmActualDescription = warmObservation.description
                    if (episodePath.isNotBlank()) {
                        val warmIdentity = ACTUAL_IDENTITY_PATTERN.matchEntire(
                            warmActualDescription
                        )
                        check(
                            warmIdentity != null && warmIdentity.groupValues[1] == episodePath
                        ) {
                            "Warm image belongs to a different episode: " +
                                "expected=$episodePath actual=$warmActualDescription"
                        }
                    }
                    capture(device, outputDirectory, "same-process-warm-actual")
                    device.pressBack()
                    check(
                        device.wait(
                            Until.hasObject(By.res(TARGET_PACKAGE, "EpisodeList")),
                            UI_TIMEOUT_MS
                        )
                    ) { "Warm viewer did not return to the episode screen" }
                    warmPassed = true
                }.onFailure { throwable ->
                    warmFailure = throwable
                    runCatching { device.pressBack() }
                }
            }
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            p0SignalChannel?.close()
            val measurementInvalidFailure = failure.measurementInvalidCause()
            val semanticMeasurementInvalid = adjacentP0Timing.status ==
                AdjacentP0MeasurementStatus.MEASUREMENT_INVALID
            val channelMeasurementInvalidReason = p0SignalChannel?.infrastructureInvalidReason
            val measurementInvalid = measurementInvalidFailure != null ||
                semanticMeasurementInvalid || channelMeasurementInvalidReason != null
            val measurementInvalidReason = when {
                measurementInvalidFailure != null ->
                    measurementInvalidFailure.message ?: "MEASUREMENT_INVALID"
                semanticMeasurementInvalid ->
                    "MEASUREMENT_INVALID: adjacent p0 semantic observation lag=" +
                        "${adjacentP0Timing.detectionLagNanos?.div(1_000_000.0)}ms>" +
                        "${AdjacentP0TimingPolicy.maxDetectionLagMs}ms"
                channelMeasurementInvalidReason != null ->
                    "MEASUREMENT_INVALID: $channelMeasurementInvalidReason"
                else -> null
            }
            val result = JSONObject()
                .put("schema", 1)
                .put("caseId", caseId)
                .put("workType", workType)
                .put("typeImageSlaMs", typeImageSlaMs)
                .put("firstImageSlaMs", firstImageSlaMs)
                .put("allImagesSlaMs", allImagesSlaMs)
                .put("workId", workId)
                .put("episodePath", episodePath)
                .put("resumeMode", resumeMode)
                .put("resumePercent", resumePercent ?: JSONObject.NULL)
                .put("resumePercentBasis", if (resumeMode) "canonical_page_ordinal" else JSONObject.NULL)
                .put("currentPageCount", if (resumeMode) currentPageCount else JSONObject.NULL)
                .put("resumePage", if (resumeMode) resumePage else JSONObject.NULL)
                .put("resumeOffset", if (resumeMode) resumeOffset else JSONObject.NULL)
                .put(
                    "expectedForwardPageCount",
                    if (resumeMode) expectedForwardPageCount else JSONObject.NULL,
                )
                .put("homeContinueSeeded", homeContinueSeeded)
                .put("homeContinueColdForceStopped", homeContinueColdForceStopped)
                .put("firstActualResumePage", firstActualResumePage)
                .put("resumeFirstActualMatched", resumeFirstActualMatched)
                .put("passed", passed)
                .put("clickElapsedNanos", clickElapsedNanos)
                .put("actualElapsedNanos", actualElapsedNanos)
                .put("rotationSupported", rotationSupported ?: JSONObject.NULL)
                .put(
                    "firstActualMs",
                    if (clickElapsedNanos > 0L && actualElapsedNanos >= clickElapsedNanos) {
                        (actualElapsedNanos - clickElapsedNanos) / 1_000_000.0
                    } else {
                        JSONObject.NULL
                    }
                )
                .put("actualDescription", actualDescription)
                .put("firstImageSlaPassed", firstImageSlaPassed)
                .put("allImagesReadyAtNanos", allImagesReadyAtNanos)
                .put("allImagesReadyPageCount", allImagesReadyPageCount)
                .put(
                    "allImagesReadyMs",
                    if (clickElapsedNanos > 0L && allImagesReadyAtNanos >= clickElapsedNanos) {
                        (allImagesReadyAtNanos - clickElapsedNanos) / 1_000_000.0
                    } else {
                        JSONObject.NULL
                    }
                )
                .put("allImagesSlaPassed", allImagesSlaPassed)
                .put("forwardTraversalStartElapsedNanos", forwardTraversalStartElapsedNanos)
                .put("forwardTraversalEndElapsedNanos", forwardTraversalEndElapsedNanos)
                .put("forwardTraversalGestureCount", forwardTraversalGestureCount)
                .put("expectedAdjacentEpisodePath", expectedAdjacentEpisodePath)
                .put("expectedAdjacentPageCount", expectedAdjacentPageCount)
                .put("adjacentTraversalGestureCount", adjacentTraversalGestureCount)
                .put("adjacentP0TraversalGestureCount", adjacentP0TraversalGestureCount)
                .put("adjacentRunwayTraversalGestureCount", adjacentRunwayTraversalGestureCount)
                .put("adjacentLastSourceIndex", adjacentLastSourceIndex)
                .put("adjacentObservedRunwayDrawableCount", adjacentObservedRunwayDrawableCount)
                .put("adjacentPhysicallyObservedSources", adjacentPhysicallyObservedSources)
                .put("adjacentPhysicalRunwayPassed", adjacentPhysicalRunwayPassed)
                .put(
                    "adjacentSourcePresentedAtNanos",
                    JSONArray(adjacentSourcePresentedAtNanos),
                )
                .put(
                    "adjacentSourceIpcAcceptedAtNanos",
                    JSONArray(
                        p0SignalChannel?.acceptedTimestamps()
                            ?: List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
                    ),
                )
                .put(
                    "adjacentSourceGesturesAtPresentation",
                    JSONArray(adjacentSourceGesturesAtPresentation),
                )
                .put(
                    "adjacentSourceSemanticObservedAtNanos",
                    JSONArray(adjacentSourceSemanticObservedAtNanos),
                )
                .put(
                    "adjacentSourceSemanticEventPublishedAtNanos",
                    JSONArray(
                        p0SignalChannel?.semanticEventPublishedTimestamps()
                            ?: List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
                    ),
                )
                .put(
                    "adjacentSourceSemanticEventLeadMs",
                    JSONArray(
                        p0SignalChannel?.semanticEventLeadMs()
                            ?: List<Double?>(ADJACENT_REQUIRED_RUNWAY_PAGES) { null }
                    ),
                )
                .put(
                    "adjacentSourceSemanticCommitPublishedAtNanos",
                    JSONArray(
                        p0SignalChannel?.semanticCommitPublishedTimestamps()
                            ?: List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
                    ),
                )
                .put(
                    "adjacentSourceSemanticCallbackAtNanos",
                    JSONArray(
                        p0SignalChannel?.semanticCallbackTimestamps()
                            ?: List(ADJACENT_REQUIRED_RUNWAY_PAGES) { 0L }
                    ),
                )
                .put(
                    "adjacentSourceSemanticObserverModes",
                    JSONArray(
                        p0SignalChannel?.semanticObserverModes()
                            ?: List(ADJACENT_REQUIRED_RUNWAY_PAGES) { "UNMEASURED" }
                    ),
                )
                .put(
                    "adjacentSourceGesturesAtSemanticProof",
                    JSONArray(adjacentSourceGesturesAtSemanticProof),
                )
                .put("adjacentSourceProgressPassed", adjacentSourceProgressPassed)
                .put(
                    "adjacentSourceProgressFailure",
                    adjacentSourceProgressFailure.takeIf { it.isNotBlank() } ?: JSONObject.NULL,
                )
                .put("adjacentWorkStartedAtNanos", adjacentWorkStartedAtNanos)
                .put("adjacentRunwayReadyAtNanos", adjacentRunwayReadyAtNanos)
                .put("adjacentRunwayTargetEpisode", adjacentRunwayTargetEpisode)
                .put("adjacentRunwayPageCount", adjacentRunwayPageCount)
                .put("adjacentTotalPageCount", adjacentTotalPageCount)
                .put("forwardBoundaryReachedAtNanos", forwardBoundaryReachedAtNanos)
                .put("firstAdjacentActualAtNanos", firstAdjacentActualAtNanos)
                .put("firstAdjacentActualEpisode", firstAdjacentActualEpisode)
                .put(
                    "p0EmbeddedFirstAdjacentActualAtNanos",
                    adjacentP0Timing.firstAdjacentActualAtNanos,
                )
                .put("p0HarnessObservedAtNanos", adjacentP0Timing.harnessObservedAtNanos)
                .put("p0GesturesAtObservation", adjacentP0Timing.gesturesAtObservation)
                .put(
                    "p0DetectionLagMs",
                    adjacentP0Timing.detectionLagNanos?.div(1_000_000.0)
                        ?: JSONObject.NULL,
                )
                .put(
                    "p0ActualToInputEndMs",
                    if (adjacentP0Timing.firstAdjacentActualAtNanos > 0L &&
                        inputMetrics.endElapsedNanos >=
                            adjacentP0Timing.firstAdjacentActualAtNanos
                    ) {
                        (inputMetrics.endElapsedNanos -
                            adjacentP0Timing.firstAdjacentActualAtNanos) / 1_000_000.0
                    } else {
                        JSONObject.NULL
                    },
                )
                .put("p0SemanticObservationStatus", adjacentP0Timing.status.name)
                .put(
                    "p0MeasurementStatus",
                    p0SignalChannel?.measurementStatus?.name ?: "UNMEASURED",
                )
                .put("p0IpcAccepted", p0SignalChannel?.accepted ?: false)
                .put(
                    "p0IpcPresentedAtNanos",
                    p0SignalChannel?.payload?.presentedAtNanos ?: 0L,
                )
                .put(
                    "p0IpcSenderAtNanos",
                    p0SignalChannel?.payload?.senderAtNanos ?: 0L,
                )
                .put("p0IpcReceivedAtNanos", p0SignalChannel?.receivedAtNanos ?: 0L)
                .put(
                    "p0IpcAcceptedAtNanos",
                    p0SignalChannel?.acceptedAtNanos ?: 0L,
                )
                .put(
                    "p0IpcPresentedToSenderLagMs",
                    p0SignalChannel?.presentedToSenderLagNanos?.div(1_000_000.0)
                        ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcSenderToReceiverLagMs",
                    p0SignalChannel?.senderToReceiverLagNanos?.div(1_000_000.0)
                        ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcReceiverToAcceptanceLagMs",
                    p0SignalChannel?.receiverToAcceptanceLagNanos?.div(1_000_000.0)
                        ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcDeliveryLagMs",
                    p0SignalChannel?.let { channel ->
                        channel.payload?.presentedAtNanos?.let { presented ->
                            val received = channel.receivedAtNanos
                            if (received >= presented) {
                                (received - presented) / 1_000_000.0
                            } else {
                                JSONObject.NULL
                            }
                        } ?: JSONObject.NULL
                    } ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcAcceptanceLagMs",
                    p0SignalChannel?.signalAcceptanceLagNanos?.div(1_000_000.0)
                        ?: JSONObject.NULL,
                )
                .put("p0IpcGesturesAtSignal", p0SignalChannel?.gesturesAtSignal ?: -1)
                .put(
                    "p0IpcGesturesAfterSignal",
                    p0SignalChannel?.gesturesAtSignal?.takeIf { it >= 0 }?.let { atSignal ->
                        inputMetrics.gestureCount - atSignal
                    } ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcContinuousInputPreserved",
                    p0SignalChannel?.gesturesAtSignal?.takeIf { it >= 0 }?.let { atSignal ->
                        inputMetrics.gestureCount > atSignal
                    } ?: false,
                )
                .put(
                    "p0IpcEpisodePath",
                    p0SignalChannel?.payload?.episodePath ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcSourceIndex",
                    p0SignalChannel?.payload?.sourceIndex ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcViewerGeneration",
                    p0SignalChannel?.payload?.viewerGeneration ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcRejectedSignalCount",
                    p0SignalChannel?.rejectedSignalCount ?: 0,
                )
                .put(
                    "p0IpcFirstRejectReason",
                    p0SignalChannel?.firstRejectReason?.name ?: "NONE",
                )
                .put(
                    "p0IpcSemanticObservedAtNanos",
                    p0SignalChannel?.semanticObservedAtNanos ?: 0L,
                )
                .put(
                    "p0SemanticCallbackAtNanos",
                    p0SignalChannel?.semanticCallbackTimestamps()?.firstOrNull() ?: 0L,
                )
                .put(
                    "p0SemanticEventPublishedAtNanos",
                    p0SignalChannel?.semanticEventPublishedTimestamps()?.firstOrNull() ?: 0L,
                )
                .put(
                    "p0SemanticCommitPublishedAtNanos",
                    p0SignalChannel?.semanticCommitPublishedTimestamps()?.firstOrNull() ?: 0L,
                )
                .put(
                    "p0SemanticEventLeadMs",
                    p0SignalChannel?.semanticEventLeadMs()?.firstOrNull() ?: JSONObject.NULL,
                )
                .put(
                    "p0SemanticObserverMode",
                    p0SignalChannel?.semanticObserverModes()?.firstOrNull() ?: "UNMEASURED",
                )
                .put(
                    "p0SemanticCallbackSchedulerLagMs",
                    p0SignalChannel?.let { channel ->
                        val published = channel.semanticEventPublishedTimestamps()
                            .firstOrNull() ?: 0L
                        val callback = channel.semanticCallbackTimestamps().firstOrNull() ?: 0L
                        if (published > 0L && callback >= published) {
                            (callback - published) / 1_000_000.0
                        } else {
                            JSONObject.NULL
                        }
                    } ?: JSONObject.NULL,
                )
                .put(
                    "p0IpcTimestampCrossCheckPassed",
                    p0SignalChannel?.semanticTimestampCrossCheckPassed ?: false,
                )
                .put(
                    "measurementInvalid",
                    measurementInvalid,
                )
                .put(
                    "measurementInvalidReason",
                    measurementInvalidReason ?: JSONObject.NULL,
                )
                .put("adjacentP0SeamMs", adjacentP0SeamMs)
                .put("runwayReadyBeforeTail", runwayReadyBeforeTail)
                .put("inputGestureCount", inputMetrics.gestureCount)
                .put("inputSampleCount", inputMetrics.sampleCount)
                .put("inputStartElapsedNanos", inputMetrics.startElapsedNanos)
                .put("inputEndElapsedNanos", inputMetrics.endElapsedNanos)
                .put("physicalMotionIdleAtNanos", physicalMotionIdleAtNanos)
                .put("inputViewportDistance", inputMetrics.viewportDistance)
                .put("inputPlannedViewportPerSecond", inputMetrics.plannedViewportPerSecond)
                .put("inputAchievedViewportPerSecond", inputMetrics.achievedViewportPerSecond)
                .put("inputMaxScheduleLatenessMs", inputMetrics.maxScheduleLatenessMs)
                .put("inputMaxInjectionCallMs", inputMetrics.maxInjectionCallMs)
                .put("inputMaxInterGestureGapMs", inputMetrics.maxInterGestureGapMs)
                .put("sameProcessWarmAttempted", warmAttempted)
                .put("sameProcessWarmPassed", warmPassed)
                .put("fastFunctionalTriage", fastFunctionalTriage)
                .put("warmClickElapsedNanos", warmClickElapsedNanos)
                .put("warmActualElapsedNanos", warmActualElapsedNanos)
                .put(
                    "warmFirstActualMs",
                    if (warmClickElapsedNanos > 0L &&
                        warmActualElapsedNanos >= warmClickElapsedNanos
                    ) {
                        (warmActualElapsedNanos - warmClickElapsedNanos) / 1_000_000.0
                    } else {
                        JSONObject.NULL
                    }
                )
                .put("warmActualDescription", warmActualDescription)
                .put("warmFailureType", warmFailure?.javaClass?.name ?: JSONObject.NULL)
                .put("warmFailure", warmFailure?.message ?: JSONObject.NULL)
                .put(
                    "traceProcessingFailureType",
                    traceProcessingFailure?.javaClass?.name ?: JSONObject.NULL,
                )
                .put(
                    "traceProcessingFailure",
                    traceProcessingFailure?.message ?: JSONObject.NULL,
                )
                .put("screenshotDirectory", outputDirectory.absolutePath)
                .put("failureType", failure?.javaClass?.name ?: JSONObject.NULL)
                .put("failure", failure?.message ?: JSONObject.NULL)
            val resultJson = result.toString()
            try {
                writeMacroResultAtomically(outputDirectory, resultJson)
            } catch (persistenceFailure: Throwable) {
                Log.e(RESULT_STATUS_TAG, "Unable to persist exact macro result", persistenceFailure)
                throw MeasurementInvalidException(
                    "MEASUREMENT_INVALID: exact macro result persistence failed: " +
                        (persistenceFailure.message ?: persistenceFailure.javaClass.name)
                ).also { it.initCause(persistenceFailure) }
            }
            // Android log entries are capped at roughly 4 KiB. Keep logcat diagnostic-only and
            // deliberately small; the host must use the atomically persisted exact JSON above.
            Log.i(
                RESULT_STATUS_TAG,
                JSONObject()
                    .put("schema", 1)
                    .put("caseId", caseId)
                    .put("passed", passed)
                    .put("measurementInvalid", measurementInvalid)
                    .put("resultFile", MACRO_RESULT_FILE_NAME)
                    .toString(),
            )
        }
    }

    private fun writeMacroResultAtomically(outputDirectory: File, resultJson: String) {
        val destination = AtomicFile(File(outputDirectory, MACRO_RESULT_FILE_NAME))
        val output = destination.startWrite()
        try {
            output.write(resultJson.toByteArray(StandardCharsets.UTF_8))
            destination.finishWrite(output)
        } catch (failure: Throwable) {
            destination.failWrite(output)
            throw failure
        }
    }

    private fun Throwable.isTraceProcessorInfrastructureFailure(): Boolean =
        generateSequence(this) { it.cause }.any { failure ->
            failure is IllegalStateException &&
                failure.message?.contains(
                    "Failed unrecoverably while parsing in a previous Parse call",
                ) == true
        }

    private fun Throwable?.measurementInvalidCause(): MeasurementInvalidException? =
        generateSequence(this) { it.cause }
            .filterIsInstance<MeasurementInvalidException>()
            .firstOrNull()

    private fun seedHomeContinueState(
        workType: String,
        workId: String,
        workTitle: String,
        episodeTitle: String,
        currentPath: String,
        nextPath: String,
        siteRoot: String,
        currentPageCount: Int,
        nextPageCount: Int,
        resumePage: Int,
        resumeOffset: Int,
        p0SignalAction: String,
        p0SignalNonce: String,
        p0SignalCaseId: String,
    ): Boolean {
        val workTitleBase64 = workTitle.toUrlSafeBase64()
        val episodeTitleBase64 = episodeTitle.toUrlSafeBase64()
        val context = InstrumentationRegistry.getInstrumentation().context
        val completed = CountDownLatch(1)
        val receivedCode = AtomicInteger(Activity.RESULT_CANCELED)
        val resultData = AtomicReference("")
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                receivedCode.set(resultCode)
                resultData.set(getResultData().orEmpty())
                completed.countDown()
            }
        }
        val seedIntent = Intent(RESUME_SEED_ACTION).apply {
            component = ComponentName.unflattenFromString(RESUME_SEED_COMPONENT)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("workType", workType)
            putExtra("workId", workId)
            putExtra("workTitleBase64", workTitleBase64)
            putExtra("episodeTitleBase64", episodeTitleBase64)
            putExtra("currentPath", currentPath)
            putExtra("nextPath", nextPath)
            putExtra("siteRoot", siteRoot)
            putExtra("currentPageCount", currentPageCount)
            putExtra("nextPageCount", nextPageCount)
            putExtra("resumePage", resumePage)
            putExtra("resumeOffset", resumeOffset)
            putExtra("p0SignalAction", p0SignalAction)
            putExtra("p0SignalNonce", p0SignalNonce)
            putExtra("p0SignalCaseId", p0SignalCaseId)
        }
        @Suppress("DEPRECATION")
        context.sendOrderedBroadcast(
            seedIntent,
            null,
            resultReceiver,
            Handler(Looper.getMainLooper()),
            Activity.RESULT_CANCELED,
            "",
            Bundle.EMPTY,
        )
        check(completed.await(RESUME_SEED_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Benchmark Continue seed receiver timed out"
        }
        val output = resultData.get()
        check(receivedCode.get() == Activity.RESULT_OK) {
            "Benchmark Continue seed receiver failed: code=${receivedCode.get()}, data=$output"
        }
        check(output.contains("seeded:$resumePage:$currentPath")) {
            "Benchmark Continue seed receiver returned the wrong identity: $output"
        }
        check(output.contains("titleBase64=$workTitleBase64")) {
            "Benchmark Continue seed receiver did not confirm the exact UTF-8 title: $output"
        }
        check(output.contains("p0Signal=armed")) {
            "Benchmark Continue seed receiver did not arm exact p0 IPC: $output"
        }
        return true
    }

    private fun findHomeContinueCard(
        device: UiDevice,
        workType: String,
        workTitle: String,
    ): UiObject2 {
        device.requireObject(
            By.res(TARGET_PACKAGE, "bottom_nav"),
            UI_TIMEOUT_MS,
            "home bottom navigation after Continue seed",
        )
        val selector = By.res(TARGET_PACKAGE, "home_continue_title")
        val homeRecyclerSelectors = HomeContinueRecyclerPolicy.resourceNames(workType)
            .map { resourceName -> By.res(TARGET_PACKAGE, resourceName) }
        val continueThumbSelector = By.res(TARGET_PACKAGE, "home_continue_thumb")
        val sectionTitleSelector = By.res(TARGET_PACKAGE, "webtoon_section_title")
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var scrollAttempts = 0
        do {
            val exact = device.findObjects(selector).firstOrNull { node ->
                runCatching { node.text.orEmpty() == workTitle }.getOrDefault(false)
            }
            if (exact != null) return exact
            device.throwIfTerminalImageFailure("seeded home Continue card")
            // A tall production hero can leave the Continue thumbnail visible at the bottom of
            // the first viewport while clipping its title TextView. Polling that static viewport
            // can never reveal the exact seeded title, so advance only the real vertical home
            // RecyclerView and still require exact title identity before clicking.
            val continueSectionMaterialized = device.hasObject(continueThumbSelector) ||
                device.findObjects(sectionTitleSelector).any { node ->
                    runCatching { node.text.orEmpty() == "이어보기" }.getOrDefault(false)
            }
            if (continueSectionMaterialized && scrollAttempts < MAX_HOME_CONTINUE_SCROLLS) {
                val bounds = homeRecyclerSelectors.asSequence()
                    .mapNotNull { recyclerSelector ->
                        runCatching {
                            device.findObject(recyclerSelector)?.visibleBounds
                        }.getOrNull()
                    }
                    .firstOrNull { candidate ->
                        candidate.height() > 0 && candidate.width() > 0
                    }
                if (bounds != null && bounds.height() > 0 && bounds.width() > 0) {
                    val inset = (bounds.height() * HOME_CONTINUE_SCROLL_INSET_FRACTION).toInt()
                    device.swipe(
                        bounds.centerX(),
                        bounds.bottom - inset,
                        bounds.centerX(),
                        bounds.top + inset,
                        HOME_CONTINUE_SCROLL_STEPS,
                    )
                    scrollAttempts++
                    device.waitForWindowUpdate(TARGET_PACKAGE, 250L)
                    continue
                }
            }
            device.waitForWindowUpdate(TARGET_PACKAGE, 250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(
            "Timed out waiting for seeded home Continue card after " +
                "$scrollAttempts home scrolls: $workTitle"
        )
    }

    private fun navigateFromLauncherToEpisodeList(
        device: UiDevice,
        workType: String,
        workId: String,
        workTitle: String
    ) {
        device.requireObject(
            By.res(TARGET_PACKAGE, "bottom_nav"),
            UI_TIMEOUT_MS,
            "main bottom navigation"
        )
        device.selectNtkSiteThroughProductionUi()
        device.requireObject(
            By.res(TARGET_PACKAGE, "nav_search"),
            UI_TIMEOUT_MS,
            "search tab"
        ).click()

        val baseMode = device.requireObject(
            By.res(TARGET_PACKAGE, "searchBaseMode"),
            UI_TIMEOUT_MS,
            "content type selector"
        )
        baseMode.click()
        device.requireObject(
            By.text(if (workType == "webtoon") "웹툰" else "만화"),
            UI_TIMEOUT_MS,
            "content type option"
        ).click()

        // Selecting the content type replaces the search fragment's accessibility subtree.
        // Use the production #<work-id> search syntax so every catalog entry, including old
        // works omitted by the server's keyword filter, follows the same visible detail flow.
        // This resolves title metadata only and cannot start a viewer image request.
        device.requireObject(
            By.res(TARGET_PACKAGE, "searchBox"),
            UI_TIMEOUT_MS,
            "search input after content type selection"
        ).text = "#$workId"
        device.requireObject(
            By.res(TARGET_PACKAGE, "searchSubmitButton"),
            UI_TIMEOUT_MS,
            "search submit"
        ).click()

        val episodeListSelector = By.res(TARGET_PACKAGE, "EpisodeList")
        repeat(DETAIL_OPEN_ATTEMPTS) { attempt ->
            if (device.hasObject(episodeListSelector)) return
            val titleTimeout = if (attempt == 0) SEARCH_TIMEOUT_MS else UI_TIMEOUT_MS
            var exactTitle = device.wait(Until.findObject(By.desc(workTitle)), titleTimeout)
            if (exactTitle == null && device.hasObject(episodeListSelector)) return
            if (exactTitle == null) {
                exactTitle = device.wait(Until.findObject(By.text(workTitle)), titleTimeout)
            }
            if (exactTitle == null && device.hasObject(episodeListSelector)) return
            val titleNode = exactTitle
                ?: error("Exact work title disappeared before detail navigation: $workTitle")
            // Search result artwork/text nodes are not consistently accessibility-clickable even
            // though a real touch on their visible bounds opens the work. UiObject2.click() injects
            // that same center-coordinate touch and avoids depending on an accessibility ancestor.
            titleNode.click()
            if (device.wait(
                    Until.hasObject(episodeListSelector),
                    DETAIL_OPEN_ATTEMPT_TIMEOUT_MS
                )
            ) return
        }
        device.requireObject(episodeListSelector, UI_TIMEOUT_MS, "episode list")
    }

    private fun findEpisodeRow(device: UiDevice, episodePath: String): UiObject2 {
        require(episodePath.isNotBlank()) { "Exact episode path is required" }
        val selector = By.desc(
            Pattern.compile("^episode:${Pattern.quote(episodePath)}\\|.*$")
        )
        val workPrefix = episodePath.substringBeforeLast('/', missingDelimiterValue = "")
        require(workPrefix.isNotBlank()) { "Episode path must include a work prefix: $episodePath" }
        val anyEpisodeForWork = By.desc(
            Pattern.compile("^episode:${Pattern.quote("$workPrefix/")}[^|]+\\|.*$")
        )

        // EpisodeList is installed with the shell before its network result arrives. Waiting for
        // the first real row is navigation synchronization, not viewer/image warm-up; viewer
        // timing still begins immediately before the physical episode tap below.
        device.requireObject(
            anyEpisodeForWork,
            EPISODE_LIST_TIMEOUT_MS,
            "first real episode row for $workPrefix"
        )

        var unchangedViewportGestures = 0
        repeat(MAX_EPISODE_LIST_SCROLLS) {
            val row = device.findObjects(selector).firstOrNull { it.isEnabled && it.isClickable }
            if (row != null) return row
            // Re-resolve after every adapter/layout update so UiAutomator never scrolls a stale
            // accessibility node that was captured while the shell list was still empty.
            val list = device.requireObject(
                By.res(TARGET_PACKAGE, "EpisodeList"),
                EPISODE_LIST_TIMEOUT_MS,
                "episode list"
            )
            val beforeViewport = visibleEpisodeViewport(device, anyEpisodeForWork)
            val bounds = list.visibleBounds
            check(bounds.width() > 0 && bounds.height() > 0) {
                "Episode list has no visible swipe bounds: $bounds"
            }

            // UiObject2.scroll() reports false when its accessibility-event waiter times out. That
            // is not an end-of-list signal: RecyclerView may have advanced several rows before the
            // event timeout (the cold 68998127 trace advanced to visible positions 22..28 of 34).
            // Inject the same in-list physical gesture a user performs, then determine progress
            // from the real visible episode identities. This never opens the viewer directly and
            // does not request image/page content before the selected row is physically tapped.
            val horizontalCenter = bounds.centerX()
            val verticalInset = (bounds.height() * EPISODE_SEARCH_SWIPE_INSET_FRACTION).toInt()
                .coerceAtLeast(1)
            check(device.swipe(
                horizontalCenter,
                bounds.bottom - verticalInset,
                horizontalCenter,
                bounds.top + verticalInset,
                EPISODE_SEARCH_SWIPE_STEPS
            )) { "Failed to inject physical episode-list swipe: $episodePath" }
            device.waitForWindowUpdate(TARGET_PACKAGE, EPISODE_SCROLL_EVENT_TIMEOUT_MS)

            val advancedRow = device.findObjects(selector)
                .firstOrNull { it.isEnabled && it.isClickable }
            if (advancedRow != null) return advancedRow
            val afterViewport = visibleEpisodeViewport(device, anyEpisodeForWork)
            unchangedViewportGestures = if (afterViewport.isNotEmpty() &&
                afterViewport != beforeViewport
            ) {
                0
            } else {
                unchangedViewportGestures + 1
            }
            if (unchangedViewportGestures >= EPISODE_END_CONFIRM_GESTURES) {
                error("Episode row was not found before the end of the real list: $episodePath")
            }
        }
        error("Episode row search exceeded $MAX_EPISODE_LIST_SCROLLS list scrolls: $episodePath")
    }

    private fun visibleEpisodeViewport(
        device: UiDevice,
        anyEpisodeForWork: BySelector
    ): List<String> = device.findObjects(anyEpisodeForWork)
        .asSequence()
        .mapNotNull {
            runCatching {
                if (it.isEnabled) it.contentDescription.orEmpty() else ""
            }.getOrNull()
        }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()

    private fun driveToEdge(device: UiDevice, edge: String): Int {
        val selector = edgeSelector(edge)
        val deadline = SystemClock.elapsedRealtime() + EDGE_TIMEOUT_MS
        var gestures = 0
        if (fastFunctionalTriageEnabled) {
            // Accessibility-tree lookups call UiAutomation.waitForIdle and cost about 2.5 seconds
            // each while the continuously rendering SurfaceView never becomes globally idle.
            // Inject a short physical batch, then inspect the real edge/failure state once. The
            // canonical path below still observes after every gesture for frame attribution.
            while (
                SystemClock.elapsedRealtime() < deadline &&
                gestures < MAX_EDGE_GESTURES
            ) {
                repeat(
                    minOf(
                        FAST_TRIAGE_EDGE_GESTURE_BATCH,
                        MAX_EDGE_GESTURES - gestures
                    )
                ) {
                    verticalSwipe(device, FAST_SWIPE_STEPS)
                    gestures++
                }
                device.throwIfTerminalImageFailure("the real $edge scroll edge")
                if (device.hasObject(selector)) return gestures
            }
            error("Viewer did not publish its real $edge scroll edge after $gestures gestures")
        }
        while (!device.hasObject(selector) &&
            SystemClock.elapsedRealtime() < deadline &&
            gestures < MAX_EDGE_GESTURES
        ) {
            device.throwIfTerminalImageFailure("the real $edge scroll edge")
            verticalSwipe(device, FAST_SWIPE_STEPS)
            gestures++
        }
        device.throwIfTerminalImageFailure("the real $edge scroll edge")
        check(device.hasObject(selector)) {
            "Viewer did not publish its real $edge scroll edge after $gestures gestures"
        }
        return gestures
    }

    private data class AdjacentTraversalObservation(
        val gestures: Int,
        val actualDescription: String,
        val sourceIndex: Int,
        val runwayDrawableCount: Int = 0,
        val viewerGeneration: Long = 0L,
    )

    private class MeasurementInvalidException(message: String) :
        RuntimeException("MEASUREMENT_INVALID: $message")

    private data class AdjacentP0TimingProbe(
        var firstAdjacentActualAtNanos: Long = 0L,
        var harnessObservedAtNanos: Long = 0L,
        var gesturesAtObservation: Int = -1,
    ) {
        val status: AdjacentP0MeasurementStatus
            get() = AdjacentP0TimingPolicy.status(
                firstAdjacentActualAtNanos,
                harnessObservedAtNanos,
            )

        val detectionLagNanos: Long?
            get() = AdjacentP0TimingPolicy.detectionLagNanos(
                firstAdjacentActualAtNanos,
                harnessObservedAtNanos,
            )

        fun recordAppActual(atNanos: Long) {
            if (atNanos > 0L && firstAdjacentActualAtNanos == 0L) {
                firstAdjacentActualAtNanos = atNanos
            }
        }

        fun recordHarnessObservation(
            gestures: Int,
            observedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
        ) {
            if (harnessObservedAtNanos == 0L) {
                harnessObservedAtNanos = observedAtNanos
                gesturesAtObservation = gestures
            }
        }

        fun throwIfInvalid() {
            if (status != AdjacentP0MeasurementStatus.MEASUREMENT_INVALID) return
            val lagMs = requireNotNull(detectionLagNanos) / 1_000_000.0
            throw MeasurementInvalidException(
                "expected adjacent p0 harness detection lag ${lagMs}ms exceeded " +
                    "${AdjacentP0TimingPolicy.maxDetectionLagMs}ms"
            )
        }
    }

    private data class AdjacentSourceSignalCheckpoint(
        val sourceIndex: Int,
        val presentedAtNanos: Long,
        val gesturesAtSignal: Int,
        val semanticObservedAtNanos: Long,
        val gesturesAtSemanticProof: Int,
    )

    private data class AdjacentSemanticEventCheckpoint(
        val description: String,
        val episodePath: String,
        val sourceIndex: Int,
        val viewerGeneration: Long,
        /** Exact compositor presentation carried by this actual-state revision. */
        val actualPresentedAtNanos: Long,
        /** Event creation time mapped from uptime into the elapsed-realtime clock domain. */
        val publishedAtNanos: Long,
        /** Callback receipt time; diagnostic only because the instrumentation scheduler can lag. */
        val callbackAtNanos: Long,
    )

    private class AdjacentP0SignalChannel(
        private val action: String,
        private val nonce: String,
        private val caseId: String,
        private val expectedEpisodePath: String,
        private val allowTerminalResumeInitialViewport: Boolean,
    ) {
        private val lock = Any()
        private var registeredContext: Context? = null
        private var registeredUiAutomation: UiAutomation? = null
        private var receiverThread: HandlerThread? = null
        private var input: ContinuousForwardInput? = null
        private var armed = false
        private val runwayPayloads = arrayOfNulls<AdjacentP0IpcPayload>(
            ADJACENT_REQUIRED_RUNWAY_PAGES
        )
        private val runwayReceivedAtNanos = LongArray(ADJACENT_REQUIRED_RUNWAY_PAGES)
        private val runwayAcceptedAtNanos = LongArray(ADJACENT_REQUIRED_RUNWAY_PAGES)
        private val runwayGesturesAtSignal = IntArray(ADJACENT_REQUIRED_RUNWAY_PAGES) { -1 }
        private val runwaySemanticObservedAtNanos = LongArray(ADJACENT_REQUIRED_RUNWAY_PAGES)
        private val runwaySemanticEventPublishedAtNanos =
            LongArray(ADJACENT_REQUIRED_RUNWAY_PAGES)
        private val runwaySemanticCallbackAtNanos = LongArray(ADJACENT_REQUIRED_RUNWAY_PAGES)
        private val runwaySemanticObserverModes =
            Array(ADJACENT_REQUIRED_RUNWAY_PAGES) { "UNMEASURED" }
        private val runwayGesturesAtSemanticProof =
            IntArray(ADJACENT_REQUIRED_RUNWAY_PAGES) { -1 }
        private val semanticEvents = arrayOfNulls<AdjacentSemanticEventCheckpoint>(
            ADJACENT_REQUIRED_RUNWAY_PAGES
        )
        private val semanticCommitPayloads = arrayOfNulls<AdjacentSemanticCommitPayload>(
            ADJACENT_REQUIRED_RUNWAY_PAGES
        )
        private val pendingSemanticCommitPayloads =
            arrayOfNulls<Pair<AdjacentSemanticCommitPayload, Long>>(
                ADJACENT_REQUIRED_RUNWAY_PAGES
            )
        private var runwayReadyPayload: AdjacentRunwayReadyIpcPayload? = null
        @Volatile private var accessibilityElapsedOffsetNanos = 0L
        @Volatile private var runwayInvalidReason: String? = null
        @Volatile private var physicalMotionIdleAtNanos = 0L
        @Volatile private var physicalMotionIdleViewerGeneration = 0L

        @Volatile var accepted = false
            private set
        @Volatile var payload: AdjacentP0IpcPayload? = null
            private set
        @Volatile var receivedAtNanos = 0L
            private set
        @Volatile var acceptedAtNanos = 0L
            private set
        @Volatile var gesturesAtSignal = -1
            private set
        @Volatile var rejectedSignalCount = 0
            private set
        @Volatile var firstRejectReason = AdjacentP0IpcRejectReason.NONE
            private set
        @Volatile var earlySignal = false
            private set
        @Volatile var semanticObservedAtNanos = 0L
            private set
        @Volatile var semanticTimestampCrossCheckPassed = false
            private set

        val measurementStatus: AdjacentP0MeasurementStatus
            get() {
                if (earlySignal) return AdjacentP0MeasurementStatus.MEASUREMENT_INVALID
                val current = payload ?: return AdjacentP0MeasurementStatus.UNMEASURED
                return AdjacentP0TimingPolicy.status(
                    current.presentedAtNanos,
                    acceptedAtNanos,
                )
            }

        val infrastructureInvalidReason: String?
            get() = when {
                earlySignal -> "exact adjacent checkpoint arrived before input was armed"
                runwayInvalidReason != null -> runwayInvalidReason
                measurementStatus == AdjacentP0MeasurementStatus.MEASUREMENT_INVALID ->
                    "IPC p0 checkpoint timing invalid"
                else -> null
            }

        fun presentedTimestamps(): List<Long> = synchronized(lock) {
            runwayPayloads.map { it?.presentedAtNanos ?: 0L }
        }

        fun exactRunwayReadyEvidence(): AdjacentRunwayReadyIpcPayload? = synchronized(lock) {
            val runway = runwayReadyPayload ?: return@synchronized null
            val physicalP0 = payload ?: return@synchronized null
            runway.takeIf { it.viewerGeneration == physicalP0.viewerGeneration }
        }

        fun acceptedTimestamps(): List<Long> = synchronized(lock) {
            runwayAcceptedAtNanos.toList()
        }

        fun awaitPhysicalMotionIdleAfter(
            inputEndAtNanos: Long,
            expectedViewerGeneration: Long,
            timeoutMs: Long,
        ): Long {
            require(inputEndAtNanos > 0L)
            require(expectedViewerGeneration > 0L)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            do {
                val observed = physicalMotionIdleAtNanos
                if (observed >= inputEndAtNanos &&
                    physicalMotionIdleViewerGeneration == expectedViewerGeneration
                ) {
                    return observed
                }
                SystemClock.sleep(PHYSICAL_MOTION_IDLE_POLL_MS)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw MeasurementInvalidException(
                "app-owned physical motion did not become idle after final input: " +
                    "inputEnd=$inputEndAtNanos idle=$physicalMotionIdleAtNanos " +
                    "generation=$physicalMotionIdleViewerGeneration/" +
                    expectedViewerGeneration
            )
        }

        fun hasPresentedCheckpoint(sourceIndex: Int): Boolean = synchronized(lock) {
            runwayPayloads.getOrNull(sourceIndex) != null
        }

        fun shouldDeferForExactSemanticRevision(
            sourceIndex: Int,
            actualPresentedAtNanos: Long,
        ): Boolean = synchronized(lock) {
            val current = runwayPayloads.getOrNull(sourceIndex) ?: return@synchronized false
            !AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                physicalPresentedAtNanos = current.presentedAtNanos,
                semanticActualPresentedAtNanos = actualPresentedAtNanos,
            )
        }

        fun gesturesAtPresentation(): List<Int> = synchronized(lock) {
            runwayGesturesAtSignal.toList()
        }

        fun semanticObservedTimestamps(): List<Long> = synchronized(lock) {
            runwaySemanticObservedAtNanos.toList()
        }

        fun semanticEventPublishedTimestamps(): List<Long> = synchronized(lock) {
            runwaySemanticEventPublishedAtNanos.toList()
        }

        fun semanticCommitPublishedTimestamps(): List<Long> = synchronized(lock) {
            semanticCommitPayloads.map { it?.semanticPublishedAtNanos ?: 0L }
        }

        fun semanticEventLeadMs(): List<Double?> = synchronized(lock) {
            runwayPayloads.mapIndexed { source, payload ->
                val eventAt = runwaySemanticEventPublishedAtNanos[source]
                if (payload != null && eventAt > 0L) {
                    (eventAt - payload.presentedAtNanos) / 1_000_000.0
                } else {
                    null
                }
            }
        }

        fun semanticCallbackTimestamps(): List<Long> = synchronized(lock) {
            runwaySemanticCallbackAtNanos.toList()
        }

        fun semanticObserverModes(): List<String> = synchronized(lock) {
            runwaySemanticObserverModes.toList()
        }

        fun gesturesAtSemanticProof(): List<Int> = synchronized(lock) {
            runwayGesturesAtSemanticProof.toList()
        }

        val signalAcceptanceLagNanos: Long?
            get() = payload?.presentedAtNanos?.let { presented ->
                if (acceptedAtNanos > 0L) acceptedAtNanos - presented else null
            }

        private val stageLags: AdjacentP0IpcStageLags?
            get() = payload?.let { current ->
                AdjacentP0IpcTimingPolicy.stageLags(
                    current,
                    receivedAtNanos,
                    acceptedAtNanos,
                )
            }

        val presentedToSenderLagNanos: Long?
            get() = stageLags?.presentedToSenderNanos

        val senderToReceiverLagNanos: Long?
            get() = stageLags?.senderToReceiverNanos

        val receiverToAcceptanceLagNanos: Long?
            get() = stageLags?.receiverToAcceptanceNanos

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handle(intent, SystemClock.elapsedRealtimeNanos())
            }
        }

        private val accessibilityListener = UiAutomation.OnAccessibilityEventListener { event ->
            captureSemanticEvent(event)
        }

        fun register(context: Context, uiAutomation: UiAutomation) {
            check(registeredContext == null) { "p0 signal receiver already registered" }
            val appContext = context.applicationContext
            val filter = IntentFilter(action)
            val thread = HandlerThread(
                "ntk-p0-signal",
                Process.THREAD_PRIORITY_URGENT_DISPLAY,
            ).apply { start() }
            val scheduler = Handler(thread.looper)
            try {
                accessibilityElapsedOffsetNanos =
                    SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L
                uiAutomation.setOnAccessibilityEventListener(accessibilityListener)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                        receiver,
                        filter,
                        null,
                        scheduler,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter, null, scheduler)
                }
            } catch (failure: Throwable) {
                runCatching { uiAutomation.setOnAccessibilityEventListener(null) }
                thread.quitSafely()
                throw failure
            }
            receiverThread = thread
            registeredContext = appContext
            registeredUiAutomation = uiAutomation
        }

        fun arm(input: ContinuousForwardInput) {
            synchronized(lock) {
                check(!armed) { "p0 signal channel already armed" }
                // The direct backend, urgent priority and shell identity are ready while the
                // schedule gate is still closed. Marking this order under the receiver lock before
                // accepting IPC prevents a p0 checkpoint from racing ahead of the first real DOWN.
                input.markP0ChannelArmed()
                this.input = input
                armed = true
            }
        }

        fun close() {
            val context = registeredContext ?: return
            registeredContext = null
            registeredUiAutomation?.let { automation ->
                runCatching { automation.setOnAccessibilityEventListener(null) }
            }
            registeredUiAutomation = null
            runCatching { context.unregisterReceiver(receiver) }
            receiverThread?.quitSafely()
            receiverThread = null
            synchronized(lock) {
                input = null
                armed = false
            }
        }

        /**
         * Returns accessibility publications not yet consumed by the physical proof gate. The
         * event carries the same exact `actual:path:source:generation` semantics as UiAutomator,
         * but its creation timestamp is immune to a delayed instrumentation callback. IPC must
         * already have accepted the matching physical compositor checkpoint before it is exposed.
         */
        fun pendingSemanticDescriptions(observedSources: Collection<Int>): List<String> =
            synchronized(lock) {
                runwayPayloads.indices.mapNotNull { source ->
                    val physical = runwayPayloads[source]
                    if (source in observedSources || physical == null) return@mapNotNull null
                    semanticEvents[source]?.takeIf { event ->
                        AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                            physicalPresentedAtNanos = physical.presentedAtNanos,
                            semanticActualPresentedAtNanos = event.actualPresentedAtNanos,
                        )
                    }?.description
                        ?: semanticCommitPayloads[source]?.takeIf { semanticCommit ->
                            AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                                physicalPresentedAtNanos = physical.presentedAtNanos,
                                semanticActualPresentedAtNanos =
                                    semanticCommit.presentedAtNanos,
                            )
                        }?.let { semanticCommit ->
                            // The app sends this phase synchronously after publishing both stable
                            // accessibility nodes. Reconstruct the same exact identity in source
                            // order when Android coalesces that node's event and UiAutomator has
                            // already advanced to a later page.
                            AdjacentSemanticCommitDescriptionPolicy.build(
                                episodePath = semanticCommit.episodePath,
                                sourceIndex = semanticCommit.sourceIndex,
                                viewerGeneration = semanticCommit.viewerGeneration,
                                presentedAtNanos = semanticCommit.presentedAtNanos,
                                firstAdjacentPresentedAtNanos =
                                    runwayPayloads[0]?.presentedAtNanos ?: 0L,
                                forwardBoundaryReachedAtNanos =
                                    semanticCommit.forwardBoundaryReachedAtNanos,
                            )
                        }
                }
            }

        fun requireValidSignalForSemanticProof(
            semanticEpisodePath: String,
            semanticSourceIndex: Int,
            semanticViewerGeneration: Long,
            semanticActualPresentedAtNanos: Long,
            embeddedFirstAdjacentActualAtNanos: Long,
        ) {
            requireSourceCheckpointForSemanticProof(
                semanticEpisodePath = semanticEpisodePath,
                semanticSourceIndex = semanticSourceIndex,
                semanticViewerGeneration = semanticViewerGeneration,
                semanticActualPresentedAtNanos = semanticActualPresentedAtNanos,
                embeddedFirstAdjacentActualAtNanos = embeddedFirstAdjacentActualAtNanos,
            )
        }

        fun requireSourceCheckpointForSemanticProof(
            semanticEpisodePath: String,
            semanticSourceIndex: Int,
            semanticViewerGeneration: Long,
            semanticActualPresentedAtNanos: Long,
            embeddedFirstAdjacentActualAtNanos: Long = 0L,
        ): AdjacentSourceSignalCheckpoint {
            val checkpoint = synchronized(lock) {
                val target = input
                val current = runwayPayloads.getOrNull(semanticSourceIndex)
                    ?: throw MeasurementInvalidException(
                        "exact adjacent source $semanticSourceIndex semantic proof arrived " +
                            "before its presented checkpoint"
                    )
                if (semanticEpisodePath != expectedEpisodePath ||
                    semanticSourceIndex != current.sourceIndex
                ) {
                    throw MeasurementInvalidException(
                        "adjacent checkpoint identity did not match semantic source " +
                            semanticSourceIndex
                    )
                }
                if (semanticViewerGeneration <= 0L ||
                    semanticViewerGeneration != current.viewerGeneration
                ) {
                    throw MeasurementInvalidException(
                        "adjacent source $semanticSourceIndex generation mismatch: " +
                            "ipc=${current.viewerGeneration} semantic=$semanticViewerGeneration"
                    )
                }
                val semanticEvent = semanticEvents[semanticSourceIndex]?.takeIf { event ->
                    event.episodePath == semanticEpisodePath &&
                        event.sourceIndex == semanticSourceIndex &&
                        event.viewerGeneration == semanticViewerGeneration &&
                        // Source/generation can be reused across multiple actual-state revisions.
                        // Only the semantic event for this exact compositor presentation may prove
                        // the physical checkpoint; a delayed older event must remain ineligible.
                        AdjacentSemanticRevisionBindingPolicy.matchesPhysicalCheckpoint(
                            physicalPresentedAtNanos = current.presentedAtNanos,
                            semanticActualPresentedAtNanos = event.actualPresentedAtNanos,
                        )
                }
                val acceptedAt = runwayAcceptedAtNanos[semanticSourceIndex]
                if (semanticSourceIndex == 0) {
                    val firstDownAt = target?.firstDownInjectionStartedAtNanos() ?: 0L
                    val inputStartRejection = AdjacentP0AfterInputStartPolicy.rejection(
                        firstDownInjectionStartedAtNanos = firstDownAt,
                        presentedAtNanos = current.presentedAtNanos,
                        acceptedAtNanos = acceptedAt,
                        allowTerminalResumeInitialViewport =
                            allowTerminalResumeInitialViewport,
                    )
                    if (inputStartRejection != AdjacentP0IpcRejectReason.NONE) {
                        throw MeasurementInvalidException(
                            "exact adjacent p0 proof preceded first physical DOWN: " +
                                "presented=${current.presentedAtNanos} accepted=$acceptedAt " +
                                "firstDown=$firstDownAt"
                        )
                    }
                }
                val semanticCallbackAt = semanticEvent?.callbackAtNanos
                    ?: SystemClock.elapsedRealtimeNanos()
                val semanticCommit = semanticCommitPayloads[semanticSourceIndex]?.takeIf {
                    semanticActualPresentedAtNanos == current.presentedAtNanos &&
                        it.presentedAtNanos == current.presentedAtNanos
                }
                // Android may mutate/publish the semantic tree before the corresponding
                // compositor frame is presented. The pure policy preserves that negative lead
                // and selects a real callback/IPC floor instead of clamping event creation time.
                val semanticSelection = AdjacentSemanticObservationPolicy.select(
                    presentedAtNanos = current.presentedAtNanos,
                    acceptedAtNanos = acceptedAt,
                    eventPublishedAtNanos = semanticEvent?.publishedAtNanos,
                    eventCallbackAtNanos = semanticEvent?.callbackAtNanos,
                    semanticCommitPublishedAtNanos = semanticCommit?.semanticPublishedAtNanos,
                    uiObservedAtNanos = SystemClock.elapsedRealtimeNanos(),
                )
                val semanticMode = semanticSelection.mode
                val semanticAt = semanticSelection.observedAtNanos
                val semanticGestures = target?.completedGestureCountAt(semanticAt) ?: -1
                runwaySemanticEventPublishedAtNanos[semanticSourceIndex] =
                    semanticEvent?.publishedAtNanos ?: 0L
                runwaySemanticObservedAtNanos[semanticSourceIndex] = semanticAt
                runwaySemanticCallbackAtNanos[semanticSourceIndex] = semanticCallbackAt
                runwaySemanticObserverModes[semanticSourceIndex] = semanticMode
                runwayGesturesAtSemanticProof[semanticSourceIndex] = semanticGestures
                val progressInvalid = AdjacentSourceProgressPolicy.invalidReason(
                    sourceIndex = semanticSourceIndex,
                    presentedAtNanos = current.presentedAtNanos,
                    semanticObservedAtNanos = semanticAt,
                    gesturesAtSignal = runwayGesturesAtSignal[semanticSourceIndex],
                    gesturesAtSemanticProof = semanticGestures,
                )
                if (progressInvalid != null) {
                    runwayInvalidReason = progressInvalid
                    throw MeasurementInvalidException(progressInvalid)
                }
                val acceptanceLag = runwayAcceptedAtNanos[semanticSourceIndex] -
                    current.presentedAtNanos
                if (acceptanceLag !in
                    0L..AdjacentP0TimingPolicy.maxDetectionLagMs * 1_000_000L
                ) {
                    val reason = "source=$semanticSourceIndex IPC acceptance lag=" +
                        "${acceptanceLag / 1_000_000.0}ms>" +
                        "${AdjacentP0TimingPolicy.maxDetectionLagMs}ms"
                    runwayInvalidReason = reason
                    throw MeasurementInvalidException(reason)
                }
                AdjacentSourceSignalCheckpoint(
                    sourceIndex = semanticSourceIndex,
                    presentedAtNanos = current.presentedAtNanos,
                    gesturesAtSignal = runwayGesturesAtSignal[semanticSourceIndex],
                    semanticObservedAtNanos = semanticAt,
                    gesturesAtSemanticProof = semanticGestures,
                )
            }
            if (semanticSourceIndex == 0) {
                semanticObservedAtNanos = checkpoint.semanticObservedAtNanos
                if (embeddedFirstAdjacentActualAtNanos > 0L) {
                    semanticTimestampCrossCheckPassed =
                        embeddedFirstAdjacentActualAtNanos == checkpoint.presentedAtNanos
                    if (!semanticTimestampCrossCheckPassed) {
                        throw MeasurementInvalidException(
                            "IPC p0 timestamp did not match embedded firstAdjacentActualAtNanos: " +
                                "ipc=${checkpoint.presentedAtNanos} " +
                                "embedded=$embeddedFirstAdjacentActualAtNanos"
                        )
                    }
                }
            }
            return checkpoint
        }

        private fun captureSemanticEvent(event: AccessibilityEvent?) {
            if (event == null || event.eventTime <= 0L) return
            val candidates = ArrayList<String>(3)
            event.contentDescription?.toString()?.let(candidates::add)
            runCatching { event.source?.contentDescription?.toString() }
                .getOrNull()
                ?.let(candidates::add)
            event.text.mapNotNullTo(candidates) { it?.toString() }
            val parsed = candidates.asSequence().mapNotNull { description ->
                val identity = ACTUAL_IDENTITY_PATTERN.matchEntire(description)
                    ?: return@mapNotNull null
                val path = identity.groupValues[1]
                val source = identity.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val generation = identity.groupValues[3].toLongOrNull()
                    ?: return@mapNotNull null
                if (path != expectedEpisodePath ||
                    source !in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES ||
                    generation <= 0L
                ) {
                    return@mapNotNull null
                }
                val actualPresentedAtNanos = description
                    .telemetryNanos("actualPresentedAtNanos")
                if (actualPresentedAtNanos <= 0L) return@mapNotNull null
                AdjacentSemanticEventCheckpoint(
                    description = description,
                    episodePath = expectedEpisodePath,
                    sourceIndex = source,
                    viewerGeneration = generation,
                    actualPresentedAtNanos = actualPresentedAtNanos,
                    publishedAtNanos = 0L,
                    callbackAtNanos = 0L,
                )
            }.firstOrNull() ?: return
            val callbackAt = SystemClock.elapsedRealtimeNanos()
            val publishedAt = (
                event.eventTime * 1_000_000L + accessibilityElapsedOffsetNanos
            ).coerceAtMost(callbackAt)
            synchronized(lock) {
                if (!armed || input == null) return
                val source = parsed.sourceIndex
                if (semanticEvents[source] == null) {
                    semanticEvents[source] = parsed.copy(
                        publishedAtNanos = publishedAt,
                        callbackAtNanos = callbackAt,
                    )
                }
            }
        }

        fun requireCompleteCrossCheck(
            embeddedFirstAdjacentActualAtNanos: Long,
            semanticViewerGeneration: Long,
            semanticActualPresentedAtNanos: Long,
        ): AdjacentSourceSignalCheckpoint {
            val checkpoint = requireSourceCheckpointForSemanticProof(
                expectedEpisodePath,
                0,
                semanticViewerGeneration,
                semanticActualPresentedAtNanos,
                embeddedFirstAdjacentActualAtNanos,
            )
            if (!semanticTimestampCrossCheckPassed) {
                throw MeasurementInvalidException(
                    "IPC p0 signal lacked an embedded timestamp cross-check"
                )
            }
            return checkpoint
        }

        fun throwIfSignalWithoutSemanticProof() {
            if (accepted && semanticObservedAtNanos == 0L) {
                throw MeasurementInvalidException(
                    "IPC p0 was received but UiAutomator never proved exact adjacent source 0"
                )
            }
        }

        fun throwIfInvalidStateIfAvailable() {
            if (earlySignal) {
                throw MeasurementInvalidException(
                    "exact adjacent p0 IPC arrived before the first physical DOWN began"
                )
            }
            if (accepted) throwIfInvalidSignalAcceptanceLag()
            runwayInvalidReason?.let { throw MeasurementInvalidException(it) }
        }

        private fun handle(intent: Intent?, receivedAt: Long) {
            if (intent == null || intent.action != action) return
            when (intent.getStringExtra(P0_SIGNAL_EXTRA_PHASE).orEmpty()) {
                P0_SIGNAL_PHASE_PHYSICAL_COMMIT -> handlePhysicalSignal(intent, receivedAt)
                P0_SIGNAL_PHASE_SEMANTIC_COMMIT -> handleSemanticSignal(intent, receivedAt)
                P0_SIGNAL_PHASE_RUNWAY_READY -> handleRunwayReadySignal(intent, receivedAt)
                P0_SIGNAL_PHASE_PHYSICAL_MOTION_IDLE -> handlePhysicalMotionIdleSignal(intent)
                else -> synchronized(lock) {
                    recordRejection(AdjacentP0IpcRejectReason.PHASE)
                }
            }
        }

        private fun handlePhysicalMotionIdleSignal(intent: Intent) {
            val candidateNonce = intent.getStringExtra(P0_SIGNAL_EXTRA_NONCE).orEmpty()
            val candidateCaseId = intent.getStringExtra(P0_SIGNAL_EXTRA_CASE_ID).orEmpty()
            val endedAt = intent.getLongExtra(P0_SIGNAL_EXTRA_MOTION_ENDED_AT_NANOS, 0L)
            val generation = intent.getLongExtra(P0_SIGNAL_EXTRA_VIEWER_GENERATION, 0L)
            synchronized(lock) {
                if (!armed || input == null) return
                val rejection = when {
                    candidateNonce != nonce -> AdjacentP0IpcRejectReason.NONCE
                    candidateCaseId != caseId -> AdjacentP0IpcRejectReason.CASE_ID
                    endedAt <= 0L -> AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
                    generation <= 0L -> AdjacentP0IpcRejectReason.GENERATION
                    else -> AdjacentP0IpcRejectReason.NONE
                }
                if (rejection != AdjacentP0IpcRejectReason.NONE) {
                    recordRejection(rejection)
                    return
                }
                if (endedAt > physicalMotionIdleAtNanos) {
                    physicalMotionIdleAtNanos = endedAt
                    physicalMotionIdleViewerGeneration = generation
                }
            }
        }

        private fun handleRunwayReadySignal(intent: Intent, receivedAt: Long) {
            val candidate = AdjacentRunwayReadyIpcPayload(
                nonce = intent.getStringExtra(P0_SIGNAL_EXTRA_NONCE).orEmpty(),
                caseId = intent.getStringExtra(P0_SIGNAL_EXTRA_CASE_ID).orEmpty(),
                episodePath = intent.getStringExtra(P0_SIGNAL_EXTRA_EPISODE_PATH).orEmpty(),
                adjacentWorkStartedAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_ADJACENT_WORK_STARTED_AT_NANOS,
                    0L,
                ),
                readyAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_RUNWAY_READY_AT_NANOS,
                    0L,
                ),
                pageCount = intent.getIntExtra(P0_SIGNAL_EXTRA_RUNWAY_PAGE_COUNT, 0),
                totalPageCount = intent.getIntExtra(P0_SIGNAL_EXTRA_TOTAL_PAGE_COUNT, 0),
                senderAtNanos = intent.getLongExtra(P0_SIGNAL_EXTRA_SENDER_AT_NANOS, 0L),
                viewerGeneration = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_VIEWER_GENERATION,
                    0L,
                ),
            )
            synchronized(lock) {
                val rejection = if (runwayReadyPayload != null) {
                    AdjacentP0IpcRejectReason.DUPLICATE
                } else {
                    AdjacentRunwayReadyIpcSignalPolicy.rejection(
                        expectedNonce = nonce,
                        expectedCaseId = caseId,
                        expectedEpisodePath = expectedEpisodePath,
                        expectedRunwayPageCount = ADJACENT_REQUIRED_RUNWAY_PAGES,
                        expectedViewerGeneration = payload?.viewerGeneration,
                        payload = candidate,
                        receivedAtNanos = receivedAt,
                    )
                }
                if (rejection != AdjacentP0IpcRejectReason.NONE) {
                    recordRejection(rejection)
                    return
                }
                runwayReadyPayload = candidate
            }
        }

        private fun handlePhysicalSignal(intent: Intent, receivedAt: Long) {
            val candidate = AdjacentP0IpcPayload(
                nonce = intent.getStringExtra(P0_SIGNAL_EXTRA_NONCE).orEmpty(),
                caseId = intent.getStringExtra(P0_SIGNAL_EXTRA_CASE_ID).orEmpty(),
                episodePath = intent.getStringExtra(P0_SIGNAL_EXTRA_EPISODE_PATH).orEmpty(),
                sourceIndex = intent.getIntExtra(P0_SIGNAL_EXTRA_SOURCE_INDEX, -1),
                presentedAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_PRESENTED_AT_NANOS,
                    0L,
                ),
                senderAtNanos = intent.getLongExtra(P0_SIGNAL_EXTRA_SENDER_AT_NANOS, 0L),
                viewerGeneration = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_VIEWER_GENERATION,
                    0L,
                ),
            )
            synchronized(lock) {
                val target = input
                if (!armed || target == null) {
                    earlySignal = true
                    recordRejection(AdjacentP0IpcRejectReason.EARLY_SIGNAL)
                    return
                }
                val expectedSource = runwayPayloads.indexOfFirst { it == null }
                    .takeIf { it >= 0 }
                    ?: ADJACENT_REQUIRED_RUNWAY_PAGES
                val acceptedNow = SystemClock.elapsedRealtimeNanos()
                val baseRejection = if (candidate.sourceIndex == 0 && expectedSource == 0) {
                    AdjacentP0IpcSignalPolicy.rejection(
                        expectedNonce = nonce,
                        expectedCaseId = caseId,
                        expectedEpisodePath = expectedEpisodePath,
                        payload = candidate,
                        receivedAtNanos = receivedAt,
                    )
                } else if (accepted && expectedSource in 1 until ADJACENT_REQUIRED_RUNWAY_PAGES) {
                    AdjacentRunwayIpcSignalPolicy.rejection(
                        expectedNonce = nonce,
                        expectedCaseId = caseId,
                        expectedEpisodePath = expectedEpisodePath,
                        expectedViewerGeneration = requireNotNull(payload).viewerGeneration,
                        expectedSourceIndex = expectedSource,
                        payload = candidate,
                        receivedAtNanos = receivedAt,
                    )
                } else if (candidate.sourceIndex in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    runwayPayloads[candidate.sourceIndex] != null
                ) {
                    AdjacentP0IpcRejectReason.DUPLICATE
                } else {
                    AdjacentP0IpcRejectReason.SOURCE_ORDER
                }
                val rejection = if (baseRejection == AdjacentP0IpcRejectReason.NONE &&
                    candidate.sourceIndex == 0
                ) {
                    AdjacentP0AfterInputStartPolicy.rejection(
                        firstDownInjectionStartedAtNanos =
                            target.firstDownInjectionStartedAtNanos(),
                        presentedAtNanos = candidate.presentedAtNanos,
                        acceptedAtNanos = acceptedNow,
                        allowTerminalResumeInitialViewport =
                            allowTerminalResumeInitialViewport,
                    )
                } else {
                    baseRejection
                }
                if (rejection != AdjacentP0IpcRejectReason.NONE) {
                    if (rejection == AdjacentP0IpcRejectReason.EARLY_SIGNAL) earlySignal = true
                    recordRejection(rejection)
                    return
                }
                val source = candidate.sourceIndex
                runwayPayloads[source] = candidate
                runwayReceivedAtNanos[source] = receivedAt
                runwayAcceptedAtNanos[source] = acceptedNow
                // The receiver itself may be scheduled hundreds of milliseconds after the app
                // stamped the compositor presentation. Reconstruct the gesture ordinal at that
                // physical timestamp; counting at callback receipt would turn receiver scheduler
                // lag into an apparent post-presentation product advance.
                runwayGesturesAtSignal[source] =
                    target.completedGestureCountAt(candidate.presentedAtNanos)
                // Record the exact p0 checkpoint without stopping input. The same producer must
                // continue through p3 or this benchmark would hide a real boundary stall behind
                // an automation pause. No accessibility call runs on this receiver path.
                if (source == 0) {
                    payload = candidate
                    receivedAtNanos = receivedAt
                    gesturesAtSignal = runwayGesturesAtSignal[source]
                    acceptedAtNanos = acceptedNow
                    accepted = true
                    runwayReadyPayload?.takeIf {
                        it.viewerGeneration != candidate.viewerGeneration
                    }?.let { runway ->
                        runwayInvalidReason =
                            "runway-ready generation mismatch: " +
                                "ready=${runway.viewerGeneration} p0=${candidate.viewerGeneration}"
                        recordRejection(AdjacentP0IpcRejectReason.GENERATION)
                    }
                }
                pendingSemanticCommitPayloads[source]?.let { pending ->
                    pendingSemanticCommitPayloads[source] = null
                    acceptSemanticCommitLocked(pending.first, pending.second)
                }
            }
        }

        private fun handleSemanticSignal(intent: Intent, receivedAt: Long) {
            val candidate = AdjacentSemanticCommitPayload(
                nonce = intent.getStringExtra(P0_SIGNAL_EXTRA_NONCE).orEmpty(),
                caseId = intent.getStringExtra(P0_SIGNAL_EXTRA_CASE_ID).orEmpty(),
                episodePath = intent.getStringExtra(P0_SIGNAL_EXTRA_EPISODE_PATH).orEmpty(),
                sourceIndex = intent.getIntExtra(P0_SIGNAL_EXTRA_SOURCE_INDEX, -1),
                presentedAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_PRESENTED_AT_NANOS,
                    0L,
                ),
                semanticPublishedAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_SEMANTIC_PUBLISHED_AT_NANOS,
                    0L,
                ),
                forwardBoundaryReachedAtNanos = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_FORWARD_BOUNDARY_REACHED_AT_NANOS,
                    0L,
                ),
                senderAtNanos = intent.getLongExtra(P0_SIGNAL_EXTRA_SENDER_AT_NANOS, 0L),
                viewerGeneration = intent.getLongExtra(
                    P0_SIGNAL_EXTRA_VIEWER_GENERATION,
                    0L,
                ),
            )
            synchronized(lock) {
                val target = input
                if (!armed || target == null) {
                    earlySignal = true
                    recordRejection(AdjacentP0IpcRejectReason.EARLY_SIGNAL)
                    return
                }
                val source = candidate.sourceIndex
                if (source !in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES) {
                    recordRejection(AdjacentP0IpcRejectReason.SOURCE_INDEX)
                    return
                }
                if (source == 0) {
                    val earlyRejection = AdjacentP0AfterInputStartPolicy.rejection(
                        firstDownInjectionStartedAtNanos =
                            target.firstDownInjectionStartedAtNanos(),
                        presentedAtNanos = candidate.presentedAtNanos,
                        acceptedAtNanos = SystemClock.elapsedRealtimeNanos(),
                        allowTerminalResumeInitialViewport =
                            allowTerminalResumeInitialViewport,
                    )
                    if (earlyRejection != AdjacentP0IpcRejectReason.NONE) {
                        earlySignal = true
                        recordRejection(earlyRejection)
                        return
                    }
                }
                val physical = runwayPayloads[source]
                if (physical == null) {
                    val basicRejection = semanticCandidateBasicRejection(candidate, receivedAt)
                    if (basicRejection != AdjacentP0IpcRejectReason.NONE) {
                        recordRejection(basicRejection)
                    } else if (pendingSemanticCommitPayloads[source] != null) {
                        recordRejection(AdjacentP0IpcRejectReason.DUPLICATE)
                    } else {
                        // Separate render/main producers can race across the receiver scheduler.
                        // Retain the semantic phase, but expose nothing until physical IPC binds it.
                        pendingSemanticCommitPayloads[source] = candidate to receivedAt
                    }
                    return
                }
                acceptSemanticCommitLocked(candidate, receivedAt)
            }
        }

        private fun semanticCandidateBasicRejection(
            candidate: AdjacentSemanticCommitPayload,
            receivedAt: Long,
        ): AdjacentP0IpcRejectReason = when {
            candidate.nonce != nonce -> AdjacentP0IpcRejectReason.NONCE
            candidate.caseId != caseId -> AdjacentP0IpcRejectReason.CASE_ID
            candidate.episodePath != expectedEpisodePath ->
                AdjacentP0IpcRejectReason.EPISODE_PATH
            candidate.viewerGeneration <= 0L -> AdjacentP0IpcRejectReason.GENERATION
            candidate.presentedAtNanos <= 0L ->
                AdjacentP0IpcRejectReason.PRESENTED_TIMESTAMP
            candidate.semanticPublishedAtNanos < candidate.presentedAtNanos ->
                AdjacentP0IpcRejectReason.SEMANTIC_TIMESTAMP
            candidate.senderAtNanos < candidate.semanticPublishedAtNanos ->
                AdjacentP0IpcRejectReason.SENDER_TIMESTAMP
            receivedAt < candidate.senderAtNanos ->
                AdjacentP0IpcRejectReason.RECEIVER_TIMESTAMP
            else -> AdjacentP0IpcRejectReason.NONE
        }

        private fun acceptSemanticCommitLocked(
            candidate: AdjacentSemanticCommitPayload,
            receivedAt: Long,
        ) {
            val source = candidate.sourceIndex
            val physical = runwayPayloads.getOrNull(source)
            if (physical == null) {
                recordRejection(AdjacentP0IpcRejectReason.PHYSICAL_MISMATCH)
                return
            }
            val rejection = AdjacentSemanticCommitSignalPolicy.rejection(
                expectedNonce = nonce,
                expectedCaseId = caseId,
                expectedEpisodePath = expectedEpisodePath,
                physicalPayload = physical,
                semanticPayload = candidate,
                receivedAtNanos = receivedAt,
            )
            if (rejection != AdjacentP0IpcRejectReason.NONE) {
                runwayInvalidReason = "source=$source semantic commit IPC rejected: $rejection"
                recordRejection(rejection)
                return
            }
            if (semanticCommitPayloads[source] != null) {
                recordRejection(AdjacentP0IpcRejectReason.DUPLICATE)
                return
            }
            semanticCommitPayloads[source] = candidate
        }

        private fun recordRejection(reason: AdjacentP0IpcRejectReason) {
            rejectedSignalCount++
            if (firstRejectReason == AdjacentP0IpcRejectReason.NONE) {
                firstRejectReason = reason
            }
        }

        private fun throwIfInvalidSignalAcceptanceLag() {
            if (measurementStatus == AdjacentP0MeasurementStatus.VALID) return
            val lagMs = signalAcceptanceLagNanos?.div(1_000_000.0)
            throw MeasurementInvalidException(
                "IPC p0 acceptance lag was invalid: status=$measurementStatus lagMs=$lagMs " +
                    "limitMs=${AdjacentP0TimingPolicy.maxDetectionLagMs}"
            )
        }

        private fun String.telemetryNanos(field: String): Long =
            Regex("(?:^|;)${Regex.escape(field)}=(\\d+)(?:;|$)")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L
    }

    private data class ContinuousInputMetrics(
        val gestureCount: Int,
        val sampleCount: Int,
        val startElapsedNanos: Long,
        val endElapsedNanos: Long,
        val viewportDistance: Double,
        val plannedViewportPerSecond: Double,
        val achievedViewportPerSecond: Double,
        val maxScheduleLatenessMs: Long,
        val maxInjectionCallMs: Long,
        val maxInterGestureGapMs: Long,
    ) {
        companion object {
            fun empty() = ContinuousInputMetrics(
                gestureCount = 0,
                sampleCount = 0,
                startElapsedNanos = 0L,
                endElapsedNanos = 0L,
                viewportDistance = 0.0,
                plannedViewportPerSecond = ResumeTraversalPlan.plannedViewportPerSecond,
                achievedViewportPerSecond = 0.0,
                maxScheduleLatenessMs = 0L,
                maxInjectionCallMs = 0L,
                maxInterGestureGapMs = 0L,
            )
        }
    }

    private fun requireReaderRateInput(metrics: ContinuousInputMetrics, phase: String) {
        check(metrics.plannedViewportPerSecond in 2.99..3.01) {
            "$phase used an unexpected formal input rate: ${metrics.plannedViewportPerSecond}"
        }
        ContinuousInputCadencePolicy.infrastructureInvalidReason(
            gestureCount = metrics.gestureCount,
            achievedViewportPerSecond = metrics.achievedViewportPerSecond,
            maxScheduleLatenessMs = metrics.maxScheduleLatenessMs,
            maxInjectionCallMs = metrics.maxInjectionCallMs,
            maxInterGestureIdleMs = metrics.maxInterGestureGapMs,
            maxScheduleLatenessLimitMs = MAX_INPUT_SCHEDULE_LATENESS_MS,
            maxInjectionCallLimitMs = MAX_INPUT_INJECTION_CALL_MS,
            maxInterGestureIdleLimitMs = MAX_INPUT_INTER_GESTURE_GAP_MS,
        )?.let { reason ->
            // Cadence corruption is benchmark infrastructure failure. It must invalidate/retry
            // this measurement instead of being reported as a target-app UX regression.
            throw MeasurementInvalidException("$phase $reason")
        }
    }

    private class ContinuousForwardInput(
        displayWidth: Int,
        displayHeight: Int,
    ) {
        private val x = displayWidth / 2f
        private val height = displayHeight.toDouble()
        private val stopRequested = AtomicBoolean(false)
        private val completedGestures = AtomicInteger(0)
        private val completedGestureAtNanos = AtomicLongArray(MAX_EDGE_GESTURES)
        private val samples = AtomicInteger(0)
        private val maxScheduleLatenessMs = AtomicLong(0L)
        private val maxInjectionCallMs = AtomicLong(0L)
        private val maxInterGestureGapMs = AtomicLong(0L)
        private val firstDownInjectionStartedAtNanosRef = AtomicLong(0L)
        private val startElapsedNanos = AtomicLong(0L)
        private val endElapsedNanos = AtomicLong(0L)
        private val failure = AtomicReference<Throwable?>(null)
        private val producerPrepared = CountDownLatch(1)
        private val scheduleStartGate = CountDownLatch(1)
        private val startOrder = ContinuousInputStartOrder()
        private val worker = Thread(::runProducer, "ntk-resume-3vps-input").apply {
            isDaemon = true
        }

        fun prepareAndAwait() {
            worker.start()
            try {
                if (!producerPrepared.await(INPUT_PRODUCER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw MeasurementInvalidException(
                        "input producer did not prepare priority/direct injection within " +
                            "${INPUT_PRODUCER_READY_TIMEOUT_MS}ms"
                    )
                }
                failure.get()?.let { throw AssertionError("Direct input producer failed", it) }
                startOrder.markPrepared()
            } catch (throwable: Throwable) {
                requestStop()
                throw throwable
            }
        }

        fun markP0ChannelArmed() {
            startOrder.markChannelArmed()
        }

        fun releaseSchedule() {
            failure.get()?.let { throw AssertionError("Direct input producer failed", it) }
            startOrder.markScheduleReleased()
            // The producer owns the schedule baseline after this gate. Startup, priority
            // promotion, backend/shell-identity preparation and channel arming are outside the
            // physical-input clock, while no post-baseline cadence can be rebased.
            scheduleStartGate.countDown()
        }

        fun completedGestureCount(): Int = completedGestures.get()

        fun firstDownInjectionStartedAtNanos(): Long =
            firstDownInjectionStartedAtNanosRef.get()

        /** Returns the completed-gesture ordinal at a historical elapsed-realtime timestamp. */
        fun completedGestureCountAt(elapsedRealtimeNanos: Long): Int {
            val upperBound = completedGestures.get().coerceAtMost(MAX_EDGE_GESTURES)
            var count = 0
            while (count < upperBound) {
                val completedAt = completedGestureAtNanos.get(count)
                if (completedAt <= 0L || completedAt > elapsedRealtimeNanos) break
                count++
            }
            return count
        }

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("Direct input producer failed", it) }
        }

        fun exhaustedGestureBudget(): Boolean =
            !worker.isAlive &&
                !stopRequested.get() &&
                completedGestures.get() >= MAX_EDGE_GESTURES

        fun requestStop() {
            stopRequested.set(true)
            // Also release a producer which was prepared but whose schedule was never authorized.
            scheduleStartGate.countDown()
        }

        fun stopAndAwait(): ContinuousInputMetrics {
            requestStop()
            worker.join(INPUT_PRODUCER_JOIN_TIMEOUT_MS)
            check(!worker.isAlive) { "Direct input producer did not stop at a gesture boundary" }
            failure.get()?.let { throw AssertionError("Direct input producer failed", it) }
            val gestureCount = completedGestures.get()
            val start = startElapsedNanos.get()
            val end = endElapsedNanos.get()
            val distance = gestureCount * ResumeTraversalPlan.viewportDistancePerGesture
            val achieved = if (gestureCount > 0 && end > start) {
                distance * 1_000_000_000.0 / (end - start).toDouble()
            } else {
                0.0
            }
            return ContinuousInputMetrics(
                gestureCount = gestureCount,
                sampleCount = samples.get(),
                startElapsedNanos = start,
                endElapsedNanos = end,
                viewportDistance = distance,
                plannedViewportPerSecond = ResumeTraversalPlan.plannedViewportPerSecond,
                achievedViewportPerSecond = achieved,
                maxScheduleLatenessMs = maxScheduleLatenessMs.get(),
                maxInjectionCallMs = maxInjectionCallMs.get(),
                maxInterGestureGapMs = maxInterGestureGapMs.get(),
            )
        }

        private fun runProducer() {
            var preparedSignaled = false
            try {
                // The instrumentation runner and Android's real input/display threads run at
                // urgent-display priority. A plain Java Thread is reset to nice 0 and can spend
                // seconds runnable behind the host-GPU emulator's render/network work. Match the
                // system input producer before assigning any immutable event timestamp so Binder
                // priority inheritance and the producer itself cannot corrupt physical cadence.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val actualPriority = Process.getThreadPriority(Process.myTid())
                ContinuousInputCadencePolicy.producerPriorityInvalidReason(
                    requiredPriority = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                    actualPriority = actualPriority,
                )?.let { reason ->
                    throw MeasurementInvalidException(
                        "input infrastructure was not trustworthy: $reason"
                    )
                }
                val plan = ResumeTraversalPlan.gestureSamples()
                DirectInputInjector.beginTouchSequence().use { sequence ->
                    preparedSignaled = true
                    producerPrepared.countDown()
                    scheduleStartGate.await()
                    if (stopRequested.get()) return@use
                    // First DOWN is immediate. Sleeping for a nominal one-frame lead after the
                    // producer was ready allowed a saturated emulator to wake it hundreds of
                    // milliseconds late before it had emitted even one sample (v44). This value is
                    // captured exactly once and every later sample remains on that absolute clock.
                    val scheduleStartMs = ContinuousInputSchedulePolicy.initialScheduleStartMs(
                        SystemClock.uptimeMillis()
                    )
                    var previousUpCallFinishedMs = 0L
                    while (!stopRequested.get() &&
                        completedGestures.get() < MAX_EDGE_GESTURES
                    ) {
                        val gesture = completedGestures.get()
                        for (sample in plan) {
                            val plannedMs = ContinuousInputSchedulePolicy.plannedSampleTimeMs(
                                scheduleStartMs = scheduleStartMs,
                                gesture = gesture,
                                sampleOffsetMs = sample.offsetMs,
                            )
                            waitUntil(plannedMs)
                            val callStartedMs = SystemClock.uptimeMillis()
                            val scheduleLatenessMs =
                                (callStartedMs - plannedMs).coerceAtLeast(0L)
                            updateMax(maxScheduleLatenessMs, scheduleLatenessMs)
                            // Never replay a backlog of old MOVE samples. Besides being unlike a
                            // physical finger, that catch-up burst feeds more Binder work into an
                            // already late InputDispatcher and can manufacture both false smoothness
                            // and false jank. Throwing inside use closes the sequence and emits a
                            // best-effort ACTION_CANCEL for an active gesture.
                            ContinuousInputCadencePolicy.staleSampleInvalidReason(
                                scheduleLatenessMs = scheduleLatenessMs,
                                maxScheduleLatenessMs = MAX_INPUT_SCHEDULE_LATENESS_MS,
                            )?.let { reason ->
                                throw MeasurementInvalidException(
                                    "input infrastructure was not trustworthy: $reason"
                                )
                            }
                            if (sample.action == TouchAction.DOWN) {
                                ContinuousInputCadencePolicy.interGestureIdleMs(
                                    previousUpCallFinishedMs = previousUpCallFinishedMs,
                                    nextDownCallStartedMs = callStartedMs,
                                )?.let { idleMs -> updateMax(maxInterGestureGapMs, idleMs) }
                            }
                            val action = when (sample.action) {
                                TouchAction.DOWN -> MotionEvent.ACTION_DOWN
                                TouchAction.MOVE -> MotionEvent.ACTION_MOVE
                                TouchAction.UP -> MotionEvent.ACTION_UP
                            }
                            if (sample.action == TouchAction.DOWN) {
                                // Publish at the injection call boundary, after all cadence and
                                // action preparation. The p0 receiver must not treat gate release
                                // or an earlier producer bookkeeping timestamp as physical input.
                                val downInjectionStartedAtNanos =
                                    SystemClock.elapsedRealtimeNanos()
                                firstDownInjectionStartedAtNanosRef.compareAndSet(
                                    0L,
                                    downInjectionStartedAtNanos,
                                )
                                startElapsedNanos.compareAndSet(
                                    0L,
                                    downInjectionStartedAtNanos,
                                )
                            }
                            val result = sequence.injectAsync(
                                action,
                                x,
                                (height * sample.yFraction).toFloat(),
                                plannedMs,
                            )
                            check(result.isInjected) { "Direct InputManager rejected $action" }
                            updateMax(maxInjectionCallMs, result.callDurationMs)
                            ContinuousInputCadencePolicy.injectionCallInvalidReason(
                                callDurationMs = result.callDurationMs,
                                maxCallDurationMs = MAX_INPUT_INJECTION_CALL_MS,
                            )?.let { reason ->
                                // Do not reach the next absolute sample after a blocking call has
                                // already consumed more than the allowed cadence budget. Throwing
                                // inside the sequence use block emits best-effort CANCEL instead of
                                // replaying the now-overdue MOVE backlog.
                                throw MeasurementInvalidException(
                                    "input infrastructure was not trustworthy: $reason"
                                )
                            }
                            samples.incrementAndGet()
                            if (sample.action == TouchAction.UP) {
                                // The inter-gesture interval starts only after the UP injection
                                // returns. Its own binder/InputManager delay remains independently
                                // visible through maxInjectionCallMs.
                                previousUpCallFinishedMs = SystemClock.uptimeMillis()
                                endElapsedNanos.set(SystemClock.elapsedRealtimeNanos())
                            }
                        }
                        val completedIndex = completedGestures.get()
                        completedGestureAtNanos.set(
                            completedIndex,
                            SystemClock.elapsedRealtimeNanos(),
                        )
                        completedGestures.incrementAndGet()
                    }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            } finally {
                if (!preparedSignaled) producerPrepared.countDown()
            }
        }

        private fun waitUntil(plannedMs: Long) {
            while (true) {
                val remaining = plannedMs - SystemClock.uptimeMillis()
                if (remaining <= 0L) return
                SystemClock.sleep(remaining)
            }
        }

        private fun updateMax(target: AtomicLong, value: Long) {
            var current = target.get()
            while (value > current && !target.compareAndSet(current, value)) {
                current = target.get()
            }
        }
    }

    /** Isolates the launch-tail -> exact adjacent-p0 physical presentation seam. */
    private fun driveIntoExpectedAdjacentEpisode(
        device: UiDevice,
        launchEpisodePath: String,
        expectedEpisodePath: String,
        continuousInput: ContinuousForwardInput? = null,
        p0Timing: AdjacentP0TimingProbe,
        p0Signal: AdjacentP0SignalChannel?,
        proof: AdjacentEpisodeProofGate,
    ): AdjacentTraversalObservation {
        require(launchEpisodePath.isNotBlank())
        require(expectedEpisodePath.isNotBlank())
        val deadline = SystemClock.elapsedRealtime() + EDGE_TIMEOUT_MS
        var gestures = 0
        var launchReadyPageCount = 0
        var maxLaunchSource = -1
        var maxExpectedSource = -1
        var expectedDescription = ""
        var lastDescription = ""
        var inputExhaustedAtMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (continuousInput != null) {
                continuousInput.throwIfFailed()
                gestures = continuousInput.completedGestureCount()
                p0Signal?.throwIfInvalidStateIfAvailable()
            }
            // A missing-selector lookup blocked UiAutomator for 8.45 seconds in v14 while the
            // independent input producer kept scrolling. Keep the continuous hot path limited
            // to the positive actual selector; terminal failure is queried once after input has
            // stopped, immediately before reporting an unsuccessful traversal.
            if (continuousInput == null) {
                device.findObject(ALL_IMAGES_READY_SELECTOR)?.let { node ->
                    val description = runCatching { node.contentDescription.orEmpty() }
                        .getOrNull()
                        .orEmpty()
                    ALL_IMAGES_READY_PATTERN.matchEntire(description)?.let { identity ->
                        launchReadyPageCount = maxOf(
                            launchReadyPageCount,
                            identity.groupValues[1].toIntOrNull() ?: 0,
                        )
                    }
                }
            }
            val semanticDescriptions = ArrayList<String>()
            p0Signal?.pendingSemanticDescriptions(proof.observedSourceIndices)
                ?.let(semanticDescriptions::addAll)
            if (HarnessPollingPolicy.shouldPollActualImageTree(continuousInput != null)) {
                device.findObjects(ACTUAL_IMAGE_SELECTOR).mapNotNullTo(semanticDescriptions) { node ->
                    runCatching { node.contentDescription.orEmpty() }.getOrNull()
                }
            }
            for (description in semanticDescriptions.distinct()) {
                val identity = ACTUAL_IDENTITY_PATTERN.matchEntire(description) ?: continue
                val path = identity.groupValues[1]
                val source = identity.groupValues[2].toIntOrNull() ?: continue
                val semanticViewerGeneration = identity.groupValues[3].toLongOrNull() ?: continue
                val actualPresentedAtNanos = description
                    .telemetryNanos("actualPresentedAtNanos")
                proof.forwardEvidence.observeActualDescription(description)
                val adjacentTotalPageCount = description
                    .telemetryNanos("adjacentTotalPageCount")
                    .toInt()
                val adjacentRunwayPageCount = description
                    .telemetryNanos("adjacentRunwayPageCount")
                    .toInt()
                val adjacentRunwayTargetEpisode = description
                    .telemetryValue("adjacentRunwayTargetEpisode")
                val firstAdjacentActualAtNanos = description
                    .telemetryNanos("firstAdjacentActualAtNanos")
                val firstAdjacentActualEpisode = description
                    .telemetryValue("firstAdjacentActualEpisode")
                if (firstAdjacentActualEpisode == expectedEpisodePath) {
                    p0Timing.recordAppActual(firstAdjacentActualAtNanos)
                }
                if (continuousInput != null && path == expectedEpisodePath &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    source !in proof.observedSourceIndices &&
                    requireNotNull(p0Signal).hasPresentedCheckpoint(source).not()
                ) {
                    // Semantic state is allowed to lead its compositor frame. Keep continuous
                    // input running and retry only after exact physical IPC accepts this source.
                    expectedDescription = description
                    continue
                }
                if (continuousInput != null && path == expectedEpisodePath &&
                    source != 0 && !proof.boundaryEntered
                ) {
                    expectedDescription = description
                    continue
                }
                if (continuousInput != null && path == expectedEpisodePath &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    source !in proof.observedSourceIndices &&
                    requireNotNull(p0Signal).shouldDeferForExactSemanticRevision(
                        source,
                        actualPresentedAtNanos,
                    )
                ) {
                    expectedDescription = description
                    continue
                }
                val sourceCheckpoint = if (
                    continuousInput != null && path == expectedEpisodePath && source == 0 &&
                    0 !in proof.observedSourceIndices
                ) {
                    val channel = requireNotNull(p0Signal)
                    if (!channel.accepted) {
                        continuousInput.requestStop()
                        throw MeasurementInvalidException(
                            "UiAutomator proved adjacent p0 before a valid IPC checkpoint"
                        )
                    }
                    channel.requireCompleteCrossCheck(
                        firstAdjacentActualAtNanos,
                        semanticViewerGeneration,
                        actualPresentedAtNanos,
                    ).also {
                        channel.payload?.let(proof.forwardEvidence::observeExactP0Ipc)
                    }
                } else {
                    null
                }
                val proofUpdate = proof.observe(
                    actualEpisodePath = path,
                    actualSourceIndex = source,
                    adjacentTotalPageCount = adjacentTotalPageCount,
                    adjacentRunwayPageCount = adjacentRunwayPageCount,
                    adjacentRunwayTargetEpisode = adjacentRunwayTargetEpisode,
                    firstAdjacentActualAtNanos = firstAdjacentActualAtNanos,
                    firstAdjacentActualEpisode = firstAdjacentActualEpisode,
                    description = description,
                    presentedAtNanos = sourceCheckpoint?.presentedAtNanos ?: 0L,
                    gesturesAtPresentation = sourceCheckpoint?.gesturesAtSignal ?: -1,
                    semanticObservedAtNanos =
                        sourceCheckpoint?.semanticObservedAtNanos ?: 0L,
                    gesturesAtSemanticProof =
                        sourceCheckpoint?.gesturesAtSemanticProof ?: -1,
                )
                proofUpdate.sourceProgressFailure?.let { progressFailure ->
                    error("Adjacent physical source progress failed: $progressFailure")
                }
                if (proofUpdate.boundaryEnteredNow) {
                    // Page zero is the seam clock. Validate the IPC identity/timestamp without
                    // stopping the producer that must continue through the physical p0-p3 runway.
                    // The producer can finish the next gesture between this loop's count snapshot
                    // and exact semantic proof. Attribute p0 to the historical compositor-backed
                    // checkpoint, not that newer sampled count, or a faster p0 produces the
                    // impossible report "observed at gesture 1, traversed at gesture 2".
                    val boundaryGestures = sourceCheckpoint
                        ?.gesturesAtSemanticProof
                        ?.takeIf { it >= 0 }
                        ?: gestures
                    p0Timing.recordHarnessObservation(
                        gestures = boundaryGestures,
                        observedAtNanos = sourceCheckpoint?.semanticObservedAtNanos
                            ?: SystemClock.elapsedRealtimeNanos(),
                    )
                    if (continuousInput != null) {
                        check(sourceCheckpoint != null) {
                            "Adjacent p0 source checkpoint disappeared before proof"
                        }
                    }
                    return AdjacentTraversalObservation(
                        gestures = boundaryGestures,
                        actualDescription = proof.boundaryDescription,
                        sourceIndex = 0,
                        runwayDrawableCount = proof.runwayDrawableCount,
                        viewerGeneration = semanticViewerGeneration,
                    )
                }
                when {
                    path == launchEpisodePath -> {
                        maxLaunchSource = maxOf(maxLaunchSource, source)
                    }
                    path == expectedEpisodePath -> {
                        maxExpectedSource = maxOf(maxExpectedSource, source)
                        // Keep the newest suffix even if callbacks expose an older source node;
                        // timing/count one-shots may have been published after the furthest source.
                        expectedDescription = description
                    }
                    else -> error(
                        "Reader crossed into the wrong adjacent episode: " +
                            "launch=$launchEpisodePath expected=$expectedEpisodePath " +
                            "actual=$path source=$source gestures=$gestures"
                    )
                }
                lastDescription = description
            }
            if (continuousInput != null) {
                if (!proof.boundaryEntered && continuousInput.exhaustedGestureBudget()) {
                    if (inputExhaustedAtMs == 0L) {
                        inputExhaustedAtMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - inputExhaustedAtMs >=
                        INPUT_EXHAUSTION_OBSERVATION_GRACE_MS
                    ) {
                        break
                    }
                }
                SystemClock.sleep(ADJACENT_ACTUAL_IMAGE_POLL_MS)
            } else {
                if (gestures >= MAX_EDGE_GESTURES) break
                if (maxExpectedSource >= 0 ||
                    launchReadyPageCount > 0 &&
                    maxLaunchSource >= launchReadyPageCount - ADJACENT_FINE_SWIPE_RUNWAY_PAGES
                ) {
                    adjacentEpisodeForwardSwipe(device)
                } else {
                    verticalSwipe(device, FAST_SWIPE_STEPS)
                }
                gestures++
            }
        }
        continuousInput?.requestStop()
        // This can still take seconds when the failure selector is absent, but it runs only after
        // stopping the producer and therefore cannot cause post-p0 input overshoot.
        device.throwIfTerminalImageFailure("the exact adjacent episode entry")
        p0Signal?.throwIfSignalWithoutSemanticProof()
        error(
            "The expected adjacent p0 was not physically committed: " +
                "launch=$launchEpisodePath expected=$expectedEpisodePath " +
                "readyPages=$launchReadyPageCount maxLaunchSource=$maxLaunchSource " +
                "maxExpectedSource=$maxExpectedSource gestures=$gestures " +
                "actual=${expectedDescription.ifBlank { lastDescription }}"
        )
    }

    /**
     * Continues real forward input after the isolated p0 seam until p0, p1, p2 and p3 have each
     * appeared as a committed `actual:` viewport. Those semantics are emitted only for complete,
     * identity-valid, full-quality physical frames, so readiness telemetry by itself cannot pass.
     * The ViewerScroll trace remains open across this uninterrupted producer; a clamp/wait between
     * sources is therefore retained as a presentation gap and remains subject to the <1% jank SLA.
     */
    private fun driveThroughExpectedAdjacentRunway(
        device: UiDevice,
        launchEpisodePath: String,
        expectedEpisodePath: String,
        expectedViewerGeneration: Long,
        continuousInput: ContinuousForwardInput?,
        p0Signal: AdjacentP0SignalChannel?,
        proof: AdjacentEpisodeProofGate,
    ): AdjacentTraversalObservation {
        check(proof.boundaryEntered) { "Adjacent p0 must be physically proven before continuation" }
        check(expectedViewerGeneration > 0L) { "Adjacent p0 viewer generation was missing" }
        val deadline = SystemClock.elapsedRealtime() + EDGE_TIMEOUT_MS
        var gestures = 0
        var lastDescription = proof.boundaryDescription
        var lastSource = 0
        var inputExhaustedAtMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (continuousInput != null) {
                continuousInput.throwIfFailed()
                gestures = continuousInput.completedGestureCount()
            }
            val semanticDescriptions = ArrayList<String>()
            p0Signal?.pendingSemanticDescriptions(proof.observedSourceIndices)
                ?.let(semanticDescriptions::addAll)
            if (HarnessPollingPolicy.shouldPollActualImageTree(continuousInput != null)) {
                device.findObjects(ACTUAL_IMAGE_SELECTOR).mapNotNullTo(semanticDescriptions) { node ->
                    runCatching { node.contentDescription.orEmpty() }.getOrNull()
                }
            }
            for (description in semanticDescriptions.distinct()) {
                val identity = ACTUAL_IDENTITY_PATTERN.matchEntire(description) ?: continue
                val path = identity.groupValues[1]
                val source = identity.groupValues[2].toIntOrNull() ?: continue
                val viewerGeneration = identity.groupValues[3].toLongOrNull() ?: continue
                val actualPresentedAtNanos = description
                    .telemetryNanos("actualPresentedAtNanos")
                proof.forwardEvidence.observeActualDescription(description)
                if (path == launchEpisodePath) {
                    // Commit callbacks can arrive out of order across the seam. The immutable p0
                    // proof already prevents this stale launch frame from resetting progress.
                    continue
                }
                if (path != expectedEpisodePath) {
                    error(
                        "Reader crossed beyond the expected adjacent episode before p0-p3 proof: " +
                            "expected=$expectedEpisodePath actual=$path source=$source " +
                            "observed=${proof.observedSourceIndices.joinToString()}"
                    )
                }
                if (viewerGeneration != expectedViewerGeneration) {
                    error(
                        "Adjacent viewer generation changed during physical p0-p3 traversal: " +
                        "expected=$expectedViewerGeneration actual=$viewerGeneration source=$source"
                    )
                }
                if (continuousInput != null &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    source !in proof.observedSourceIndices &&
                    requireNotNull(p0Signal).hasPresentedCheckpoint(source).not()
                ) {
                    // UiAutomator/accessibility may publish source N before the benchmark-only
                    // compositor checkpoint reaches its receiver. Defer instead of turning that
                    // legal semantic lead into an infrastructure failure.
                    lastDescription = description
                    continue
                }
                if (continuousInput != null &&
                    source !in proof.observedSourceIndices &&
                    AdjacentSemanticTraversalOrderPolicy.shouldDefer(
                        sourceIndex = source,
                        observedSourceCount = proof.observedSourceIndices.size,
                    )
                ) {
                    // The receiver already enforces physical IPC order. A later page's node/event
                    // can still reach this observer first; retain it and let the next loop consume
                    // the missing exact semantic commit before advancing.
                    lastDescription = description
                    continue
                }
                if (continuousInput != null &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    source !in proof.observedSourceIndices &&
                    requireNotNull(p0Signal).shouldDeferForExactSemanticRevision(
                        source,
                        actualPresentedAtNanos,
                    )
                ) {
                    lastDescription = description
                    continue
                }
                val sourceCheckpoint = if (
                    continuousInput != null &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES &&
                    source !in proof.observedSourceIndices
                ) {
                    requireNotNull(p0Signal).requireSourceCheckpointForSemanticProof(
                        semanticEpisodePath = path,
                        semanticSourceIndex = source,
                        semanticViewerGeneration = viewerGeneration,
                        semanticActualPresentedAtNanos = actualPresentedAtNanos,
                        embeddedFirstAdjacentActualAtNanos = if (source == 0) {
                            description.telemetryNanos("firstAdjacentActualAtNanos")
                        } else {
                            0L
                        },
                    )
                } else {
                    null
                }
                val update = proof.observe(
                    actualEpisodePath = path,
                    actualSourceIndex = source,
                    adjacentTotalPageCount = description
                        .telemetryNanos("adjacentTotalPageCount").toInt(),
                    adjacentRunwayPageCount = description
                        .telemetryNanos("adjacentRunwayPageCount").toInt(),
                    adjacentRunwayTargetEpisode = description
                        .telemetryValue("adjacentRunwayTargetEpisode"),
                    firstAdjacentActualAtNanos = description
                        .telemetryNanos("firstAdjacentActualAtNanos"),
                    firstAdjacentActualEpisode = description
                        .telemetryValue("firstAdjacentActualEpisode"),
                    description = description,
                    presentedAtNanos = sourceCheckpoint?.presentedAtNanos ?: 0L,
                    gesturesAtPresentation = sourceCheckpoint?.gesturesAtSignal ?: -1,
                    semanticObservedAtNanos =
                        sourceCheckpoint?.semanticObservedAtNanos ?: 0L,
                    gesturesAtSemanticProof =
                        sourceCheckpoint?.gesturesAtSemanticProof ?: -1,
                )
                update.sourceProgressFailure?.let { progressFailure ->
                    error("Adjacent physical source progress failed: $progressFailure")
                }
                if (update.physicalSourceObservedNow &&
                    source in 0 until ADJACENT_REQUIRED_RUNWAY_PAGES
                ) {
                    lastSource = source
                    lastDescription = description
                }
                if (update.complete) {
                    check(source == ADJACENT_REQUIRED_RUNWAY_PAGES - 1) {
                        "p0-p3 callbacks were not observed in forward physical order: " +
                            "last=$source observed=${proof.observedSourceIndices.joinToString()}"
                    }
                    return AdjacentTraversalObservation(
                        gestures = gestures,
                        actualDescription = description,
                        sourceIndex = source,
                        runwayDrawableCount = proof.runwayDrawableCount,
                        viewerGeneration = viewerGeneration,
                    )
                }
                if (source >= ADJACENT_REQUIRED_RUNWAY_PAGES) {
                    throw MeasurementInvalidException(
                        "continuous input passed p3 before UiAutomator physically observed every " +
                            "required source: source=$source " +
                            "observed=${proof.observedSourceIndices.joinToString()}"
                    )
                }
            }
            if (continuousInput != null) {
                if (continuousInput.exhaustedGestureBudget()) {
                    if (inputExhaustedAtMs == 0L) {
                        inputExhaustedAtMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - inputExhaustedAtMs >=
                        INPUT_EXHAUSTION_OBSERVATION_GRACE_MS
                    ) {
                        break
                    }
                }
                SystemClock.sleep(ADJACENT_ACTUAL_IMAGE_POLL_MS)
            } else {
                if (gestures >= MAX_EDGE_GESTURES) break
                adjacentEpisodeForwardSwipe(device)
                gestures++
            }
        }
        continuousInput?.requestStop()
        device.throwIfTerminalImageFailure("physical adjacent p0-p3 continuation")
        error(
            "Expected adjacent p0-p3 were not each physically committed: " +
                "expected=$expectedEpisodePath observed=${proof.observedSourceIndices.joinToString()} " +
                "lastSource=$lastSource gestures=$gestures actual=$lastDescription"
        )
    }

    /**
     * A temporarily installed adjacent runway must not be mistaken for the work's final edge.
     * Keep performing the same forward fling a reader uses until the exact adjacent episode's
     * canonical final page is physically represented by committed `actual:` semantics.
     */
    private fun driveThroughExpectedAdjacentEpisode(
        device: UiDevice,
        expectedEpisodePath: String,
        expectedPageCount: Int
    ): AdjacentTraversalObservation {
        val requiredLastSource = expectedPageCount - 1
        val deadline = SystemClock.elapsedRealtime() + EDGE_TIMEOUT_MS
        var gestures = 0
        var lastDescription = ""
        var lastSource = -1
        var expectedEpisodeObserved = false
        while (
            SystemClock.elapsedRealtime() < deadline &&
            gestures < MAX_EDGE_GESTURES
        ) {
            device.throwIfTerminalImageFailure("the expected adjacent episode")
            for (node in device.findObjects(ACTUAL_IMAGE_SELECTOR)) {
                val description = runCatching { node.contentDescription.orEmpty() }
                    .getOrNull()
                    .orEmpty()
                val identity = ACTUAL_IDENTITY_PATTERN.matchEntire(description) ?: continue
                if (identity.groupValues[1] != expectedEpisodePath) continue
                expectedEpisodeObserved = true
                val source = identity.groupValues[2].toIntOrNull() ?: continue
                if (source >= lastSource) {
                    lastSource = source
                    lastDescription = description
                }
                if (source >= requiredLastSource) {
                    return AdjacentTraversalObservation(
                        gestures = gestures,
                        actualDescription = description,
                        sourceIndex = source
                    )
                }
            }
            if (expectedEpisodeObserved) {
                // A full-height four-step fling can jump from the penultimate manga page into the
                // following episode without ever committing the expected final page. Once the
                // requested adjacent episode is physically observed, keep moving only forward but
                // shorten the gesture so every remaining canonical page crosses the viewport.
                adjacentEpisodeForwardSwipe(device)
            } else {
                verticalSwipe(device, FAST_SWIPE_STEPS)
            }
            gestures++
        }
        error(
            "Adjacent episode did not physically render its final canonical page: " +
                "expected=$expectedEpisodePath source=$requiredLastSource " +
                "observed=$lastSource gestures=$gestures actual=$lastDescription"
        )
    }

    private fun UiDevice.throwIfTerminalImageFailure(label: String) {
        findObject(TERMINAL_IMAGE_FAILURE_SELECTOR)?.let { failure ->
            error(
                "Viewer reported a terminal image failure while waiting for $label: " +
                    failure.contentDescription.orEmpty()
            )
        }
    }

    private fun edgeSelector(edge: String): BySelector = By.desc(
        Pattern.compile(
            "^(?:viewer-edge:${Pattern.quote(edge)}|actual:.*(?:edge=${Pattern.quote(edge)}).*)$"
        )
    )

    private fun verticalSwipe(device: UiDevice, steps: Int) {
        val width = device.displayWidth
        val height = device.displayHeight
        val x = width / 2
        val upper = (height * 0.12f).toInt()
        val lower = (height * 0.88f).toInt()
        injectForwardSwipe(device, x, lower, upper, steps)
    }

    private fun adjacentEpisodeForwardSwipe(device: UiDevice) {
        val width = device.displayWidth
        val height = device.displayHeight
        val x = width / 2
        val upper = (height * 0.42f).toInt()
        val lower = (height * 0.68f).toInt()
        injectForwardSwipe(device, x, lower, upper, FAST_SWIPE_STEPS)
    }

    private fun injectForwardSwipe(
        device: UiDevice,
        x: Int,
        lower: Int,
        upper: Int,
        steps: Int
    ) {
        if (!fastFunctionalTriageEnabled) {
            device.swipe(x, lower, x, upper, steps)
            return
        }
        // UiDevice.swipe waits for global UI-idle before every gesture. On the host-GPU viewer a
        // continuously animating SurfaceView makes that automation wait roughly 2.5 seconds even
        // though the four-step touch stream itself lasts only 20 ms. Shell input injects the same
        // touchscreen DOWN/MOVE/UP stream and waits only for that stream to finish. This path is
        // diagnostic-only; canonical frame qualification keeps UiDevice.swipe and its trace.
        val durationMs = (steps * SWIPE_STEP_DURATION_MS).coerceAtLeast(
            MIN_SHELL_SWIPE_DURATION_MS
        )
        val output = device.executeShellCommand(
            "input touchscreen swipe $x $lower $x $upper $durationMs"
        )
        check(output.isBlank()) { "Physical shell swipe failed: $output" }
    }

    private fun capture(device: UiDevice, directory: File, label: String) {
        val destination = File(directory, "${label.safeFileComponent()}.png")
        check(device.takeScreenshot(destination)) { "Screenshot failed: $destination" }
    }

    private data class ActualImageObservation(
        val clickElapsedNanos: Long,
        val observedElapsedNanos: Long,
        val description: String,
    )

    /**
     * Polls the identity-valid committed node immediately after injecting the physical tap.
     *
     * Some Android builds coalesce the SurfaceView semantics mutation without emitting a matching
     * accessibility event. `executeAndWaitForEvent` then waits for its full observation timeout
     * even though the real image has already been committed. The node itself is published only by
     * the HWUI-commit callback, never by a placeholder, so polling it is the authoritative
     * state-based synchronization primitive. The observed timestamp remains conservative because
     * it is captured after UiAutomator can see the committed node.
     */
    private fun clickAndAwaitActualImage(
        device: UiDevice,
        episode: UiObject2,
        expectedEpisodePath: String,
        timeoutMs: Long,
        label: String,
        expectedSourcePage: Int? = null,
    ): ActualImageObservation {
        val clickNanos = SystemClock.elapsedRealtimeNanos()
        val expectedPath = expectedEpisodePath.takeIf { it.isNotBlank() }
        episode.click()
        val observationTimeoutMs = maxOf(timeoutMs, ACTUAL_IMAGE_OBSERVATION_TIMEOUT_MS)
        val deadline = SystemClock.elapsedRealtime() + observationTimeoutMs
        var nextTerminalFailurePollAtMs = 0L
        var actualDescription: String? = null
        var observedNanos = 0L
        do {
            val descriptions = device.findObjects(ACTUAL_IMAGE_SELECTOR).mapNotNull { candidate ->
                // UiObject2 is a mutable handle. Capture the description now: the stable root and
                // SurfaceView can advance to the next visible source between this poll and the
                // caller reading the handle, which previously turned an exact resume into +1.
                runCatching { candidate.contentDescription.orEmpty() }.getOrNull()
            }
            ActualImageCandidatePolicy.select(
                descriptions = descriptions,
                clickAtNanos = clickNanos,
                expectedEpisodePath = expectedPath,
                expectedSourcePage = expectedSourcePage,
            )?.let { evidence ->
                actualDescription = evidence.description
                observedNanos = evidence.observedAtNanos
            }
            if (actualDescription != null) break
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs >= nextTerminalFailurePollAtMs) {
                device.throwIfTerminalImageFailure(label)
                // Base the 1 Hz throttle on query completion. A saturated UiAutomator lookup can
                // itself take longer than a second; using its start time would make the next loop
                // issue another full-tree query immediately and defeat the throttle.
                nextTerminalFailurePollAtMs = HarnessPollingPolicy.nextTerminalFailurePollAtMs(
                    SystemClock.elapsedRealtime()
                )
            }
            // `actualAtNanos` above is the authoritative app-commit clock. A fixed bounded sleep
            // prevents SurfaceView accessibility events from waking this loop immediately and
            // flooding system_server while preserving that exact timestamp.
            SystemClock.sleep(HarnessPollingPolicy.actualImagePollMs)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(actualDescription != null && observedNanos >= clickNanos) {
            val expectedIdentity = expectedSourcePage?.let { " page=$it" }.orEmpty()
            "Timed out waiting for $label$expectedIdentity after ${observationTimeoutMs}ms"
        }
        return ActualImageObservation(
            clickNanos,
            observedNanos,
            requireNotNull(actualDescription),
        )
    }

    private fun UiDevice.requireObject(
        selector: BySelector,
        timeoutMs: Long,
        label: String
    ): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            findObject(selector)?.let { return it }
            throwIfTerminalImageFailure(label)
            waitForWindowUpdate(TARGET_PACKAGE, 250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for $label after ${timeoutMs}ms")
    }

    private fun UiDevice.requireDescription(
        selector: BySelector,
        expected: String,
        timeoutMs: Long,
        label: String
    ): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val current = findObject(selector)
            val description = runCatching { current?.contentDescription.orEmpty() }.getOrNull()
            if (description?.contains(expected, ignoreCase = true) == true) {
                return requireNotNull(current)
            }
            waitForWindowUpdate(TARGET_PACKAGE, 250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for $label after ${timeoutMs}ms")
    }

    private fun UiDevice.selectNtkSiteThroughProductionUi() {
        val selector = By.res(TARGET_PACKAGE, "action_site_switch")
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        var selectionClicked = false
        var settingsOverlayDismissed = false
        do {
            // Emulator SystemUI can occasionally open its data-usage activity over the app just
            // as the toolbar is tapped. Dismiss that exact Settings overlay once, then wait for
            // the target window. Repeated Back presses while WindowManager is still transitioning
            // can otherwise close MainActivity too and leave the benchmark polling the launcher.
            // This remains the production toolbar flow; no site preference is injected.
            if (currentPackageName != TARGET_PACKAGE) {
                if (currentPackageName == SETTINGS_PACKAGE && !settingsOverlayDismissed) {
                    pressBack()
                    settingsOverlayDismissed = true
                }
                waitForWindowUpdate(TARGET_PACKAGE, 500L)
                continue
            }
            settingsOverlayDismissed = false
            val toggle = findObject(selector)
            val description = runCatching { toggle?.contentDescription.orEmpty() }.getOrNull()
            if (description?.contains("NTK", ignoreCase = true) == true) return
            if (toggle != null && !selectionClicked) {
                toggle.click()
                selectionClicked = true
                waitForWindowUpdate(TARGET_PACKAGE, 500L)
            } else {
                waitForWindowUpdate(TARGET_PACKAGE, 250L)
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for NTK site selection after ${UI_TIMEOUT_MS}ms")
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        getString(key)?.trim().orEmpty().ifBlank { error("Missing instrumentation argument $key") }

    private fun android.os.Bundle.requiredBase64Utf8(key: String): String =
        optionalBase64Utf8(key).ifBlank { error("Missing instrumentation argument $key") }

    private fun android.os.Bundle.optionalBase64Utf8(key: String): String {
        val encoded = getString(key)?.trim().orEmpty()
        if (encoded.isBlank()) return ""
        return String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8)
    }

    private fun newP0SignalNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return buildString(32) {
            for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
        }
    }

    private fun String.safeFileComponent(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "case" }

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

    private fun String.toUrlSafeBase64(): String = Base64.encodeToString(
        toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP,
    )

    private fun String.telemetryNanos(field: String): Long =
        Regex("(?:^|;)${Regex.escape(field)}=(\\d+)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L

    private fun String.telemetryValue(field: String): String =
        Regex("(?:^|;)${Regex.escape(field)}=([^;]*)(?:;|$)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private companion object {
        const val TARGET_PACKAGE = "ml.melun.mangaview"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val RESULT_STATUS_TAG = "NtkColdMacroStatus"
        const val MACRO_RESULT_FILE_NAME = "macro-result.json"
        const val RESUME_SEED_ACTION = "ml.melun.mangaview.benchmark.SEED_RESUME"
        const val RESUME_SEED_COMPONENT =
            "ml.melun.mangaview/.benchmark.BenchmarkResumeSeedReceiver"
        const val P0_SIGNAL_ACTION_PREFIX =
            "ml.melun.mangaview.macrobenchmark.P0_COMMIT."
        const val P0_SIGNAL_EXTRA_NONCE = "nonce"
        const val P0_SIGNAL_EXTRA_CASE_ID = "caseId"
        const val P0_SIGNAL_EXTRA_EPISODE_PATH = "episodePath"
        const val P0_SIGNAL_EXTRA_SOURCE_INDEX = "sourceIndex"
        const val P0_SIGNAL_EXTRA_PRESENTED_AT_NANOS = "presentedAtNanos"
        const val P0_SIGNAL_EXTRA_SENDER_AT_NANOS = "senderAtNanos"
        const val P0_SIGNAL_EXTRA_VIEWER_GENERATION = "viewerGeneration"
        const val P0_SIGNAL_EXTRA_PHASE = "phase"
        const val P0_SIGNAL_EXTRA_MOTION_ENDED_AT_NANOS = "motionEndedAtNanos"
        const val P0_SIGNAL_EXTRA_RUNWAY_READY_AT_NANOS = "runwayReadyAtNanos"
        const val P0_SIGNAL_EXTRA_ADJACENT_WORK_STARTED_AT_NANOS =
            "adjacentWorkStartedAtNanos"
        const val P0_SIGNAL_EXTRA_RUNWAY_PAGE_COUNT = "runwayPageCount"
        const val P0_SIGNAL_EXTRA_TOTAL_PAGE_COUNT = "totalPageCount"
        const val P0_SIGNAL_EXTRA_SEMANTIC_PUBLISHED_AT_NANOS =
            "semanticPublishedAtNanos"
        const val P0_SIGNAL_EXTRA_FORWARD_BOUNDARY_REACHED_AT_NANOS =
            "forwardBoundaryReachedAtNanos"
        const val P0_SIGNAL_PHASE_PHYSICAL_COMMIT = "PHYSICAL_COMMIT"
        const val P0_SIGNAL_PHASE_SEMANTIC_COMMIT = "SEMANTIC_COMMIT"
        const val P0_SIGNAL_PHASE_RUNWAY_READY = "RUNWAY_READY"
        const val P0_SIGNAL_PHASE_PHYSICAL_MOTION_IDLE = "PHYSICAL_MOTION_IDLE"
        const val PHYSICAL_MOTION_IDLE_TIMEOUT_MS = 5_000L
        const val PHYSICAL_MOTION_IDLE_POLL_MS = 5L
        const val WEBTOON_FIRST_IMAGE_SLA_MS = 4_000L
        const val MANHWA_FIRST_IMAGE_SLA_MS = 4_000L
        const val ALL_IMAGES_SLA_MS = 8_000L
        // Keep evidence collection independent from the product SLA. A slow result still fails
        // against firstImageSlaMs above, but large cold sources must remain observable long enough
        // to prove whether a real HWUI image was eventually committed instead of being mislabeled
        // as a missing image at the old 15/30-second automation ceiling.
        const val ACTUAL_IMAGE_OBSERVATION_TIMEOUT_MS = 60_000L
        // The adjacent boundary loop uses embedded commit timestamps, so 64ms observation
        // cadence preserves timing precision while avoiding a continuous accessibility flood.
        const val ADJACENT_ACTUAL_IMAGE_POLL_MS = 64L
        // Navigation setup is outside the click-relative viewer SLA. Under sustained host-GPU
        // qualification, a data-cleared emulator can spend well over 30 seconds restoring system
        // UI/accessibility and resolving the content catalog. Every wait below targets a real UI
        // node; no fixed delay is introduced and image timing still starts at episode.click().
        const val UI_TIMEOUT_MS = 180_000L
        const val SEARCH_TIMEOUT_MS = 180_000L
        // Large works can expose 100+ episode rows and the cold episode API/detail pipeline is
        // intentionally outside ViewerOpen timing. Wait for the real accessibility row instead
        // of rejecting a valid selection moments before its network result arrives. This does not
        // delay the image test: clickElapsedNanos is still captured immediately before episode.click().
        const val EPISODE_LIST_TIMEOUT_MS = 180_000L
        const val DETAIL_OPEN_ATTEMPTS = 3
        const val DETAIL_OPEN_ATTEMPT_TIMEOUT_MS = 60_000L
        // A long webtoon can exceed one hundred 1600px images. The edge deadline is not a content
        // readiness allowance (allReady retains its original click-relative timestamp); it only
        // lets the physical forward traversal reach the real final pixel on such episodes.
        const val EDGE_TIMEOUT_MS = 180_000L
        // A 112-image, 179k-pixel strip reached page 96 after 140 real flings. The former fixed
        // ceiling therefore rejected valid long episodes before their physical final pixel. This
        // changes only traversal coverage; the all-images timestamp remains click-relative and
        // must still satisfy the independent eight-second completion SLA.
        const val MAX_EDGE_GESTURES = 500
        const val MAX_EPISODE_LIST_SCROLLS = 400
        const val EPISODE_END_CONFIRM_GESTURES = 3
        const val EPISODE_SEARCH_SWIPE_STEPS = 36
        // RecyclerView regularly advances without producing the window-update event. The visible
        // episode identities below are the actual progress proof, so a full one-second event wait
        // multiplied a deep 200-row selection into minutes without adding synchronization value.
        const val EPISODE_SCROLL_EVENT_TIMEOUT_MS = 250L
        // Keep overlap between consecutive real RecyclerView viewports. A 64%-height gesture was
        // observed to skip an exact row, while this 40%-height gesture retains visible overlap and
        // advances more than the former one-row 20%-height gesture. It still exercises the real
        // list and never opens or warms the viewer before the exact row is physically tapped.
        const val EPISODE_SEARCH_SWIPE_INSET_FRACTION = 0.30f
        const val MEDIUM_SWIPE_STEPS = 90
        const val FAST_SWIPE_STEPS = 4
        const val INITIAL_MODERATE_FORWARD_GESTURES = 3
        const val ADJACENT_FINE_SWIPE_RUNWAY_PAGES = 4
        const val ADJACENT_REQUIRED_RUNWAY_PAGES = 4
        // This is the only adjacent timing SLA: launch-tail presentation to exact p0 pixels.
        // p1-p3 are qualified by continued physical 3 viewport/s traversal, not by demanding that
        // their background-ready timestamp precede the launch boundary.
        const val ADJACENT_P0_SEAM_SLA_MS = 250L
        // One accessibility observation costs ~2.5 s on the continuously rendering SurfaceView,
        // whereas sixteen 20 ms shell flings cost only ~0.3 s and cover the measured 119-page
        // manga. Long webtoons repeat the same bounded batch until their real edge is observed.
        const val FAST_TRIAGE_EDGE_GESTURE_BATCH = 16
        const val SWIPE_STEP_DURATION_MS = 5
        const val MIN_SHELL_SWIPE_DURATION_MS = 20
        const val INPUT_PRODUCER_READY_TIMEOUT_MS = 5_000L
        const val INPUT_PRODUCER_JOIN_TIMEOUT_MS = 5_000L
        // The producer's final UP may commit before UiAutomator publishes the matching semantics.
        // Poll briefly without adding another gesture so gesture 500 can still prove a real p0.
        const val INPUT_EXHAUSTION_OBSERVATION_GRACE_MS = 1_000L
        const val RESUME_SEED_TIMEOUT_MS = 10_000L
        const val MAX_INPUT_SCHEDULE_LATENESS_MS = 64L
        const val MAX_INPUT_INJECTION_CALL_MS = 64L
        const val MAX_INPUT_INTER_GESTURE_GAP_MS = 64L
        const val MAX_HOME_CONTINUE_SCROLLS = 8
        const val HOME_CONTINUE_SCROLL_STEPS = 32
        const val HOME_CONTINUE_SCROLL_INSET_FRACTION = 0.18f
        val ACTUAL_IMAGE_SELECTOR: BySelector = By.desc(Pattern.compile("^actual:.*$"))
        val ALL_IMAGES_READY_SELECTOR: BySelector = By.desc(
            Pattern.compile("^actual:.*;allReady=\\d+;allReadyAtNanos=\\d+(?:;.*)?$")
        )
        val TERMINAL_IMAGE_FAILURE_SELECTOR: BySelector = By.desc(
            Pattern.compile("^viewer-terminal-image-failure:\\d+$")
        )
        val ACTUAL_IDENTITY_PATTERN =
            Regex("^actual:(.+):(\\d+):(\\d+)(?:;actualAtNanos=(\\d+))?" +
                "(?:;actualPresentedAtNanos=(\\d+))?" +
                "(?:;firstActualEpisode=[^;]+;firstActualSourcePage=\\d+)?" +
                "(?:;edge=(?:top|middle|bottom))?" +
                "(?:;allReady=\\d+;allReadyAtNanos=\\d+)?" +
                "(?:;adjacentWorkStartedAtNanos=\\d+" +
                ";adjacentRunwayReadyAtNanos=\\d+" +
                ";adjacentRunwayTargetEpisode=[^;]+" +
                ";adjacentRunwayPageCount=\\d+" +
                ";adjacentTotalPageCount=\\d+" +
                ";forwardBoundaryReachedAtNanos=\\d+" +
                ";firstAdjacentActualAtNanos=\\d+" +
                ";firstAdjacentActualEpisode=[^;]+)?$")
        val ALL_IMAGES_READY_PATTERN =
            Regex("^actual:.+:\\d+:\\d+(?:;actualAtNanos=\\d+)?" +
                "(?:;actualPresentedAtNanos=\\d+)?" +
                "(?:;firstActualEpisode=[^;]+;firstActualSourcePage=\\d+)?" +
                "(?:;edge=(?:top|middle|bottom))?;" +
                "allReady=(\\d+);allReadyAtNanos=(\\d+)" +
                "(?:;adjacentWorkStartedAtNanos=\\d+" +
                ";adjacentRunwayReadyAtNanos=\\d+" +
                ";adjacentRunwayTargetEpisode=[^;]+" +
                ";adjacentRunwayPageCount=\\d+" +
                ";adjacentTotalPageCount=\\d+" +
                ";forwardBoundaryReachedAtNanos=\\d+" +
                ";firstAdjacentActualAtNanos=\\d+" +
                ";firstAdjacentActualEpisode=[^;]+)?$")
    }
}
