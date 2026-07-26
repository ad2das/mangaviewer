package ml.melun.mangaview.macrobenchmark

import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
import java.util.regex.Pattern
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
            WEBTOON_ALL_IMAGES_SLA_MS
        } else {
            MANHWA_ALL_IMAGES_SLA_MS
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
        require(
            expectedAdjacentEpisodePath.isBlank() || expectedAdjacentPageCount > 0
        ) {
            "ntkExpectedAdjacentPageCount must be positive when an adjacent episode is required"
        }
        val caseId = args.getString("ntkCaseId")?.trim().orEmpty().ifBlank {
            "$workType-$workId"
        }
        val firstImageSlaMs = args.getString("ntkFirstImageSlaMs")
            ?.toLongOrNull()?.coerceIn(250L, 30_000L) ?: typeImageSlaMs
        val allImagesSlaMs = args.getString("ntkAllImagesSlaMs")
            ?.toLongOrNull()?.coerceIn(250L, 30_000L) ?: typeImageSlaMs
        val sameProcessWarmReopen = args.getString("ntkSameProcessWarmReopen")
            ?.toBooleanStrictOrNull() ?: true
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
        var adjacentLastActualDescription = ""
        var adjacentLastSourceIndex = -1
        var traceProcessingFailure: Throwable? = null

        try {
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
                navigateFromLauncherToEpisodeList(device, workType, workId, workTitle)
                val episode = findEpisodeRow(device, episodePath)

                val actualObservation = clickAndAwaitActualImage(
                    device,
                    episode,
                    episodePath,
                    firstImageSlaMs,
                    "first HWUI-committed actual work-image draw"
                )
                clickElapsedNanos = actualObservation.clickElapsedNanos
                actualElapsedNanos = actualObservation.observedElapsedNanos
                actualDescription = actualObservation.actual.contentDescription.orEmpty()
                firstImageSlaPassed = actualElapsedNanos >= clickElapsedNanos &&
                    actualElapsedNanos - clickElapsedNanos <= firstImageSlaMs * 1_000_000L
                if (episodePath.isNotBlank()) {
                    val actualIdentity = ACTUAL_IDENTITY_PATTERN.matchEntire(actualDescription)
                    check(actualIdentity != null && actualIdentity.groupValues[1] == episodePath) {
                        "HWUI-committed image belongs to a different episode: expected=$episodePath " +
                            "actual=$actualDescription"
                    }
                }
                // Model the dominant reading UX: after the first pixels, continuously advance
                // toward later pages. Qualification deliberately performs no screenshot or idle
                // between the first actual draw and the first gesture, so the forward runway must
                // keep up from a genuinely cold entry rather than benefiting from automation time.
                // No reverse-only capacity or alternating gesture is used.
                forwardTraversalStartElapsedNanos = SystemClock.elapsedRealtimeNanos()
                repeat(INITIAL_MODERATE_FORWARD_GESTURES) {
                    verticalSwipe(device, steps = MEDIUM_SWIPE_STEPS)
                    forwardTraversalGestureCount++
                }

                if (expectedAdjacentEpisodePath.isNotBlank()) {
                    // An appendable reader has no stable global bottom: chasing "bottom" first can
                    // run through several later episodes and manufacture network/decode contention
                    // beyond the one adjacent episode this scenario is meant to qualify. Traverse
                    // the launch episode and the explicitly expected next episode in one bounded
                    // forward pass, stopping as soon as that episode's final canonical page is
                    // physically committed.
                    val adjacent = driveThroughExpectedAdjacentEpisode(
                        device,
                        expectedAdjacentEpisodePath,
                        expectedAdjacentPageCount
                    )
                    adjacentTraversalGestureCount = adjacent.gestures
                    forwardTraversalGestureCount += adjacent.gestures
                    adjacentLastActualDescription = adjacent.actualDescription
                    adjacentLastSourceIndex = adjacent.sourceIndex
                } else {
                    forwardTraversalGestureCount += driveToEdge(
                        device,
                        edge = "bottom"
                    )
                }
                forwardTraversalEndElapsedNanos = SystemClock.elapsedRealtimeNanos()
                capture(device, outputDirectory, "bottom")

                val allReadyDescription = device.requireObject(
                    ALL_IMAGES_READY_SELECTOR,
                    UI_TIMEOUT_MS,
                    "all canonical images render-ready state"
                ).contentDescription.orEmpty()
                val allReadyIdentity = ALL_IMAGES_READY_PATTERN.matchEntire(allReadyDescription)
                    ?: error("Malformed all-images render-ready state: $allReadyDescription")
                allImagesReadyPageCount = allReadyIdentity.groupValues[1].toInt()
                allImagesReadyAtNanos = allReadyIdentity.groupValues[2].toLong()
                allImagesSlaPassed = allImagesReadyPageCount > 0 &&
                    allImagesReadyAtNanos >= clickElapsedNanos &&
                    allImagesReadyAtNanos - clickElapsedNanos <= allImagesSlaMs * 1_000_000L

                device.pressBack()
                check(device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "EpisodeList")), UI_TIMEOUT_MS)) {
                    "Viewer did not return to the episode screen"
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
                "All $allImagesReadyPageCount canonical images exceeded ${allImagesSlaMs}ms: " +
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
                    val warmActual = warmObservation.actual
                    warmActualDescription = warmActual.contentDescription.orEmpty()
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
            val result = JSONObject()
                .put("schema", 1)
                .put("caseId", caseId)
                .put("workType", workType)
                .put("typeImageSlaMs", typeImageSlaMs)
                .put("firstImageSlaMs", firstImageSlaMs)
                .put("allImagesSlaMs", allImagesSlaMs)
                .put("workId", workId)
                .put("episodePath", episodePath)
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
                .put("adjacentLastActualDescription", adjacentLastActualDescription)
                .put("adjacentLastSourceIndex", adjacentLastSourceIndex)
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
            Log.i(RESULT_TAG, result.toString())
        }
    }

    private fun Throwable.isTraceProcessorInfrastructureFailure(): Boolean =
        generateSequence(this) { it.cause }.any { failure ->
            failure is IllegalStateException &&
                failure.message?.contains(
                    "Failed unrecoverably while parsing in a previous Parse call",
                ) == true
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
        val sourceIndex: Int
    )

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
        val actual: UiObject2
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
        label: String
    ): ActualImageObservation {
        val clickNanos = SystemClock.elapsedRealtimeNanos()
        val expectedPath = expectedEpisodePath.takeIf { it.isNotBlank() }
        episode.click()
        val observationTimeoutMs = maxOf(timeoutMs, ACTUAL_IMAGE_OBSERVATION_TIMEOUT_MS)
        val deadline = SystemClock.elapsedRealtime() + observationTimeoutMs
        var actual: UiObject2? = null
        var observedNanos = 0L
        do {
            val candidate = device.findObject(ACTUAL_IMAGE_SELECTOR)
            val description = runCatching { candidate?.contentDescription.orEmpty() }
                .getOrNull()
                .orEmpty()
            val identity = ACTUAL_IDENTITY_PATTERN.matchEntire(description)
            if (candidate != null && identity != null &&
                (expectedPath == null || identity.groupValues[1] == expectedPath)
            ) {
                actual = candidate
                observedNanos = identity.groupValues[4].toLongOrNull()
                    ?.takeIf { it >= clickNanos }
                    ?: SystemClock.elapsedRealtimeNanos()
                break
            }
            device.throwIfTerminalImageFailure(label)
            device.waitForWindowUpdate(TARGET_PACKAGE, ACTUAL_IMAGE_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(actual != null && observedNanos >= clickNanos) {
            "Timed out waiting for $label after ${observationTimeoutMs}ms"
        }
        return ActualImageObservation(clickNanos, observedNanos, requireNotNull(actual))
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

    private fun String.safeFileComponent(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "case" }

    private companion object {
        const val TARGET_PACKAGE = "ml.melun.mangaview"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val RESULT_TAG = "NtkColdMacro"
        const val WEBTOON_ALL_IMAGES_SLA_MS = 4_000L
        const val MANHWA_ALL_IMAGES_SLA_MS = 4_000L
        // Keep evidence collection independent from the product SLA. A slow result still fails
        // against firstImageSlaMs above, but large cold sources must remain observable long enough
        // to prove whether a real HWUI image was eventually committed instead of being mislabeled
        // as a missing image at the old 15/30-second automation ceiling.
        const val ACTUAL_IMAGE_OBSERVATION_TIMEOUT_MS = 60_000L
        const val ACTUAL_IMAGE_POLL_MS = 16L
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
        // must still satisfy the unchanged four-second SLA.
        const val MAX_EDGE_GESTURES = 500
        const val MAX_EPISODE_LIST_SCROLLS = 400
        const val EPISODE_END_CONFIRM_GESTURES = 3
        const val EPISODE_SEARCH_SWIPE_STEPS = 36
        const val EPISODE_SCROLL_EVENT_TIMEOUT_MS = 1_000L
        const val EPISODE_SEARCH_SWIPE_INSET_FRACTION = 0.18f
        const val MEDIUM_SWIPE_STEPS = 90
        const val FAST_SWIPE_STEPS = 4
        const val INITIAL_MODERATE_FORWARD_GESTURES = 3
        // One accessibility observation costs ~2.5 s on the continuously rendering SurfaceView,
        // whereas sixteen 20 ms shell flings cost only ~0.3 s and cover the measured 119-page
        // manga. Long webtoons repeat the same bounded batch until their real edge is observed.
        const val FAST_TRIAGE_EDGE_GESTURE_BATCH = 16
        const val SWIPE_STEP_DURATION_MS = 5
        const val MIN_SHELL_SWIPE_DURATION_MS = 20
        val ACTUAL_IMAGE_SELECTOR: BySelector = By.desc(Pattern.compile("^actual:.*$"))
        val ALL_IMAGES_READY_SELECTOR: BySelector = By.desc(
            Pattern.compile("^actual:.*;allReady=\\d+;allReadyAtNanos=\\d+$")
        )
        val TERMINAL_IMAGE_FAILURE_SELECTOR: BySelector = By.desc(
            Pattern.compile("^viewer-terminal-image-failure:\\d+$")
        )
        val ACTUAL_IDENTITY_PATTERN =
            Regex("^actual:(.+):(\\d+):(\\d+)(?:;actualAtNanos=(\\d+))?" +
                "(?:;edge=(?:top|middle|bottom))?" +
                "(?:;allReady=\\d+;allReadyAtNanos=\\d+)?$")
        val ALL_IMAGES_READY_PATTERN =
            Regex("^actual:.+:\\d+:\\d+(?:;actualAtNanos=\\d+)?" +
                "(?:;edge=(?:top|middle|bottom))?;" +
                "allReady=(\\d+);allReadyAtNanos=(\\d+)$")
    }
}
