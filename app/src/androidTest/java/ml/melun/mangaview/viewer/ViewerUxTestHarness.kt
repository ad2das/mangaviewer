package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import kotlin.math.abs

internal data class LiveEpisode(
    val sourceId: String,
    val seriesKey: String,
    val episodeKey: String,
)

internal data class ViewerUxLimits(
    val frameTimeoutMillis: Long = 20_000L,
    val firstFrameLimitMillis: Long = 4_000L,
    val fullEpisodeStallTimeoutMillis: Long = 30_000L,
    val homeReturnTimeoutMillis: Long = 5_000L,
    val maximumUiP95Nanos: Long = 16_000_000L,
    val maximumFreezeCount: Int = 0,
)

internal class ViewerUxTestHarness(
    private val instrumentation: Instrumentation,
    artifactPrefix: String,
    private val limits: ViewerUxLimits = ViewerUxLimits(),
) {
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val artifacts = ViewerUxArtifacts(context, artifactPrefix)
    private val frameStats = ViewerFrameStats(
        instrumentation = instrumentation,
        packageName = context.packageName,
        artifactDirectory = artifacts.directory,
    )
    private val gestures = mutableListOf<GestureMeasurement>()
    private val memory = mutableListOf<MemoryMeasurement>()
    private val screenshotInspections = mutableListOf<ScreenshotInspection>()
    private val violations = mutableListOf<String>()
    private val telemetry = ViewerUxTelemetryVerifier(violations)
    private val screenshots = ViewerScreenshotVerifier(violations)
    private var runStartedAtMillis = 0L
    private var windowFrameRecorder: ViewerWindowFrameRecorder? = null

    val evidenceDirectory get() = artifacts.directory

    fun run(episode: LiveEpisode): ViewerUxResult {
        val startedAtMillis = SystemClock.elapsedRealtime()
        runStartedAtMillis = startedAtMillis
        val startedAtNanos = System.nanoTime()
        recordMemory("baseline")
        val scenario = ActivityScenario.launch<ViewerActivity>(launchIntent(episode))
        var surfaceReadyMillis = Long.MAX_VALUE
        var firstFrameMillis = Long.MAX_VALUE
        var safeBounds = Rect()
        var frameBounds = Rect()
        var homeRoundTrip: HomeRoundTripMeasurement? = null
        var capturedStats: FrameStatsSnapshot? = null
        var lastTelemetry: ViewerTelemetrySnapshot? = null
        var fullEpisodeVerifiedMillis = Long.MAX_VALUE
        var observedEpisode: LiveEpisode? = null
        var startupTiming: ViewerStartupTiming? = null
        try {
            checkSurfaceAppears(scenario)
            scenario.onActivity { activity ->
                windowFrameRecorder = ViewerWindowFrameRecorder(activity.window)
            }
            surfaceReadyMillis = SystemClock.elapsedRealtime() - startedAtMillis
            safeBounds = readSafeBounds(scenario)
            val immediateGesture = swipe("immediate-input", Direction.FORWARD, safeBounds, steps = 20)
            val frame = waitForPresentedFrame(
                minimumTimestampMillis = startedAtMillis,
                timeoutMillis = limits.frameTimeoutMillis,
                failureMessage = "A real image frame was not presented",
            )
            firstFrameMillis = frame.timestamp - startedAtMillis
            frameBounds = Rect(frame.bounds)
            check(readUserInputRevision(scenario) > 0L) {
                "The immediate cold-start gesture was not applied by the viewer"
            }
            verifySafeBounds(safeBounds, frameBounds)
            lastTelemetry = telemetry.captureAndVerify(scenario, "first frame", episode)
            verifyImmediateGesture(lastTelemetry)
            observedEpisode = lastTelemetry.toLiveEpisode()
            recordMemory("first-frame")
            val firstFrameScreenshot = artifacts.screenshot(device, "01-first-frame")
            fullEpisodeVerifiedMillis = waitForFullEpisode(scenario, episode, startedAtMillis)
            val fullyVerified = telemetry.captureAndVerify(scenario, "fully verified", episode)
            lastTelemetry = waitForGestureSettlement(scenario, fullyVerified, "immediate-input")
            telemetry.verifySnapshot(lastTelemetry, "immediate-input settled", episode)
            val immediateIdleStable = verifyIdleStability(scenario, lastTelemetry, "immediate-input")
            completeGesture(immediateGesture, null, lastTelemetry, immediateIdleStable)
            recordMemory("fully-verified")
            inspectScreenshot("01-first-frame", firstFrameScreenshot, safeBounds)

            repeat(4) { index ->
                lastTelemetry = exerciseVerifiedGesture(
                    scenario,
                    episode,
                    "fast-forward-${index + 1}",
                    Direction.FORWARD,
                    safeBounds,
                    requireNotNull(lastTelemetry),
                )
            }
            repeat(3) { index ->
                lastTelemetry = exerciseVerifiedGesture(
                    scenario,
                    episode,
                    "fast-reverse-${index + 1}",
                    Direction.REVERSE,
                    safeBounds,
                    requireNotNull(lastTelemetry),
                )
            }
            recordMemory("after-direction-changes")
            inspectScreenshot("02-after-direction-changes", safeBounds)
            val beforeHome = requireNotNull(lastTelemetry)
            homeRoundTrip = exerciseHomeRoundTrip(scenario)
            checkHealth("HOME return")
            requireSurfaceAfterHome(requireNotNull(homeRoundTrip))
            val afterHome = telemetry.captureAndVerify(scenario, "HOME return", episode)
            telemetry.verifyHomeAnchor(beforeHome, afterHome)
            verifyIdleStability(scenario, afterHome, "HOME return")
            recordMemory("after-home-return")
            inspectScreenshot("03-after-home-return", safeBounds)
            lastTelemetry = afterHome
            repeat(3) { index ->
                lastTelemetry = exerciseVerifiedGesture(
                    scenario,
                    episode,
                    "post-home-forward-${index + 1}",
                    Direction.FORWARD,
                    safeBounds,
                    requireNotNull(lastTelemetry),
                )
            }
            repeat(2) { index ->
                lastTelemetry = exerciseVerifiedGesture(
                    scenario,
                    episode,
                    "post-home-reverse-${index + 1}",
                    Direction.REVERSE,
                    safeBounds,
                    requireNotNull(lastTelemetry),
                )
            }
            recordMemory("after-post-home-gestures")
            inspectScreenshot("04-after-post-home-gestures", safeBounds)
            capturedStats = captureFrameStatsSafely(scenario, startedAtNanos)
        } catch (failure: Throwable) {
            violations += failure.message ?: failure.javaClass.simpleName
            runCatching { artifacts.screenshot(device, "failure") }
        } finally {
            capturedStats = capturedStats ?: captureFrameStatsSafely(scenario, startedAtNanos)
            runCatching {
                scenario.onActivity { activity ->
                    startupTiming = activity.viewerStartupTimingSnapshot()
                    windowFrameRecorder?.close()
                }
            }
            windowFrameRecorder = null
            scenario.close()
        }

        val frameStats = requireNotNull(capturedStats)
        verifyThresholds(firstFrameMillis, startupTiming, frameStats)
        verifyMemory()
        val result = ViewerUxResult(
            sourceId = episode.sourceId,
            seriesKey = episode.seriesKey,
            episodeKey = episode.episodeKey,
            startedAtMillis = startedAtMillis,
            surfaceReadyMillis = surfaceReadyMillis,
            firstFrameMillis = firstFrameMillis,
            fullEpisodeVerifiedMillis = fullEpisodeVerifiedMillis,
            startupTiming = startupTiming,
            observedEpisode = observedEpisode,
            safeBounds = safeBounds,
            frameBounds = frameBounds,
            gestures = gestures.toList(),
            homeRoundTrip = homeRoundTrip,
            frameStats = frameStats,
            memory = memory.toList(),
            screenshotInspections = screenshotInspections.toList(),
            violations = violations.toList(),
        )
        val report = artifacts.write(result)
        val exportedReport = runCatching { artifacts.export() }.getOrElse { failure ->
            violations += "Evidence export failed: ${failure.message}"
            report.absolutePath
        }
        check(violations.isEmpty()) {
            "Viewer UX violations: ${violations.joinToString()}; evidence=$exportedReport"
        }
        return result
    }

    private fun emptyFrameStats(): FrameStatsSnapshot {
        val raw = artifacts.directory.resolve("gfxinfo-framestats.txt").apply { writeText("") }
        return FrameStatsSnapshot(
            gfx = FrameTimingSummary("gfxinfo", 0, null, null, null, 0, 0),
            render = FrameTimingSummary("native-render", 0, null, null, null, 0, 0),
            motion = FrameTimingSummary("choreographer-motion", 0, null, null, null, 0, 0),
            surface = null,
            surfaceLayer = null,
            gfxRawFile = raw,
            surfaceRawFile = null,
        )
    }

    private fun launchIntent(episode: LiveEpisode): Intent =
        Intent(context, ViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episode.sourceId)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episode.seriesKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode.episodeKey)
        }

    private fun checkSurfaceAppears(scenario: ActivityScenario<ViewerActivity>) {
        instrumentation.waitForIdleSync()
        var ready = false
        scenario.onActivity { activity -> ready = activity.isViewerInputSurfaceReady() }
        check(ready) {
            "Viewer did not become immediately interactive"
        }
        checkHealth("viewer entry")
    }

    private fun waitForPresentedFrame(
        minimumTimestampMillis: Long,
        timeoutMillis: Long,
        failureMessage: String,
    ): PresentedFrame {
        val condition = Condition<UiDevice, PresentedFrame?> { current ->
            current.findObject(By.descStartsWith(FRAME_PREFIX))?.toPresentedFrame()
                ?.takeIf { frame -> frame.timestamp >= minimumTimestampMillis }
        }
        return device.wait(condition, timeoutMillis) ?: error(failureMessage)
    }

    private fun swipe(name: String, direction: Direction, safe: Rect, steps: Int): Int {
        check(safe.width() > 0 && safe.height() > 0) { "Safe drawing bounds are unavailable" }
        val centerX = safe.centerX()
        val upperY = safe.top + safe.height() / 4
        val lowerY = safe.top + safe.height() * 3 / 4
        val startY = if (direction == Direction.FORWARD) lowerY else upperY
        val endY = if (direction == Direction.FORWARD) upperY else lowerY
        val started = SystemClock.elapsedRealtime()
        val startedNanos = System.nanoTime()
        val requestedDistance = abs(startY - endY)
        check(requestedDistance in 1..safe.height()) {
            "Actual $name swipe distance is not nonzero and bounded: $requestedDistance"
        }
        val dispatched = device.swipe(centerX, startY, centerX, endY, steps)
        val completedNanos = System.nanoTime()
        gestures += GestureMeasurement(
            name = name,
            direction = direction.name.lowercase(),
            dispatched = dispatched,
            dispatchMillis = SystemClock.elapsedRealtime() - started,
            startedAtNanos = startedNanos,
            completedAtNanos = completedNanos,
            requestedDistancePixels = requestedDistance,
        )
        check(dispatched) { "Actual $name swipe could not be dispatched" }
        return gestures.lastIndex
    }

    private fun exerciseVerifiedGesture(
        scenario: ActivityScenario<ViewerActivity>,
        episode: LiveEpisode,
        name: String,
        direction: Direction,
        safe: Rect,
        before: ViewerTelemetrySnapshot,
    ): ViewerTelemetrySnapshot {
        val gestureIndex = swipe(name, direction, safe, steps = 10)
        checkHealth(name)
        val after = waitForGestureSettlement(scenario, before, name)
        val telemetryDirection = when (direction) {
            Direction.FORWARD -> TelemetryDirection.FORWARD
            Direction.REVERSE -> TelemetryDirection.REVERSE
        }
        telemetry.verifySnapshot(after, name, episode, before, telemetryDirection)
        val idleStable = verifyIdleStability(scenario, after, name)
        completeGesture(gestureIndex, before, after, idleStable)
        return after
    }

    private fun waitForGestureSettlement(
        scenario: ActivityScenario<ViewerActivity>,
        before: ViewerTelemetrySnapshot,
        stage: String,
    ): ViewerTelemetrySnapshot {
        var latest = before
        var lastVisualChangeAt = SystemClock.elapsedRealtime()
        val settled = device.wait(
            Condition<UiDevice, ViewerTelemetrySnapshot?> {
                val current = telemetry.snapshotOrNull(scenario) ?: return@Condition null
                if (!sameVisualPosition(latest, current)) {
                    latest = current
                    lastVisualChangeAt = SystemClock.elapsedRealtime()
                }
                current.takeIf {
                    SystemClock.elapsedRealtime() - lastVisualChangeAt >= SETTLE_DURATION_MILLIS
                }
            },
            GESTURE_SETTLE_TIMEOUT_MILLIS,
        )
        return settled ?: error("Scroll did not settle after $stage")
    }

    private fun verifyIdleStability(
        scenario: ActivityScenario<ViewerActivity>,
        settled: ViewerTelemetrySnapshot,
        stage: String,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        var drift: ViewerTelemetrySnapshot? = null
        val observed = device.wait(
            Condition<UiDevice, Boolean> {
                val current = telemetry.snapshotOrNull(scenario) ?: return@Condition false
                if (!sameVisualPosition(settled, current)) {
                    drift = current
                    true
                } else {
                    SystemClock.elapsedRealtime() - started >= IDLE_OBSERVATION_MILLIS
                }
            },
            IDLE_OBSERVATION_MILLIS + CONDITION_SLACK_MILLIS,
        )
        if (observed != true) {
            violations += "Could not complete idle-drift observation after $stage"
            return false
        }
        drift?.let { moved ->
            violations += "Scroll drifted without input after $stage: " +
                "${settled.anchor} -> ${moved.anchor}"
        }
        return drift == null
    }

    private fun sameVisualPosition(
        first: ViewerTelemetrySnapshot,
        second: ViewerTelemetrySnapshot,
    ): Boolean = first.anchor == second.anchor

    private fun completeGesture(
        index: Int,
        before: ViewerTelemetrySnapshot?,
        after: ViewerTelemetrySnapshot,
        idleStable: Boolean,
    ) {
        val recorded = gestures[index]
        gestures[index] = recorded.copy(
            beforeScrollOffsetUnits = before?.scrollOffsetUnits,
            afterScrollOffsetUnits = after.scrollOffsetUnits,
            displacementUnits = before?.let { abs(after.scrollOffsetUnits - it.scrollOffsetUnits) },
            viewportHeightUnits = after.viewportHeightUnits,
            idleStable = idleStable,
        )
    }

    private fun verifyImmediateGesture(snapshot: ViewerTelemetrySnapshot) {
        if (snapshot.userInputRevision <= 0L) {
            violations += "The immediate cold-start gesture was not reduced as real user input"
        }
        val maximumOffset = (snapshot.contentHeightUnits - snapshot.viewportHeightUnits).coerceAtLeast(0L)
        if (maximumOffset > 0L && snapshot.scrollOffsetUnits <= 0L) {
            violations += "The immediate cold-start gesture produced zero displacement"
        }
        if (snapshot.scrollOffsetUnits !in 0L..maximumOffset) {
            violations += "Immediate gesture left scroll bounds: " +
                "${snapshot.scrollOffsetUnits}/$maximumOffset"
        }
    }

    private fun exerciseHomeRoundTrip(
        scenario: ActivityScenario<ViewerActivity>,
    ): HomeRoundTripMeasurement {
        val previousFrame = requireNotNull(device.findObject(By.descStartsWith(FRAME_PREFIX))?.toPresentedFrame()) {
            "Presented frame was absent before HOME"
        }
        val homePressedAtNanos = System.nanoTime()
        check(device.pressHome()) { "HOME input could not be dispatched" }
        check(device.wait(Until.gone(By.descStartsWith(FRAME_PREFIX)), limits.homeReturnTimeoutMillis)) {
            "Viewer remained visible after HOME"
        }
        check(device.pressRecentApps()) { "Recents input could not be dispatched" }
        val restoreRequestedAtNanos = System.nanoTime()
        val restoreRequestedAtMillis = SystemClock.elapsedRealtime()
        clickRecentTask()
        val packageRestored = device.wait(
            Condition<UiDevice, Boolean> { current -> current.currentPackageName == context.packageName },
            limits.homeReturnTimeoutMillis,
        )
        check(packageRestored == true) { "Viewer task did not become foreground after HOME" }
        val surfaceReturned = device.wait(
            Condition<UiDevice, Boolean> { current -> current.hasViewerSurfaceNode() },
            limits.homeReturnTimeoutMillis,
        )
        check(surfaceReturned == true) {
            "Viewer surface did not return after HOME"
        }
        waitForPresentedFrame(
            minimumTimestampMillis = restoreRequestedAtMillis,
            timeoutMillis = limits.homeReturnTimeoutMillis,
            failureMessage = "Viewer did not latch a new Surface frame after HOME",
        )
        val presentedAtNanos = presentationSnapshot(scenario).timestamps
            .lastOrNull { it >= restoreRequestedAtNanos }
            ?: error("Viewer callback did not confirm a new frame after HOME")
        return HomeRoundTripMeasurement(
            previousDescriptionTimestampMillis = previousFrame.timestamp,
            homePressedAtNanos = homePressedAtNanos,
            restoreRequestedAtNanos = restoreRequestedAtNanos,
            presentedAtNanos = presentedAtNanos,
            surfaceLayer = "HWUI FrameTimeline",
        )
    }

    private fun waitForFullEpisode(
        scenario: ActivityScenario<ViewerActivity>,
        episode: LiveEpisode,
        startedAtMillis: Long,
    ): Long {
        var verifiedCount = -1
        while (true) {
            val progress = device.wait(
                Condition<UiDevice, EpisodeProgressTelemetry?> {
                    telemetry.snapshotOrNull(scenario)?.currentEpisodeProgress?.takeIf { current ->
                        current.episodeId.seriesId.sourceId.value == episode.sourceId &&
                            current.episodeId.seriesId.remoteKey == episode.seriesKey &&
                            current.episodeId.remoteKey == episode.episodeKey &&
                            current.pageCount > 0 &&
                            (current.verifiedCount > verifiedCount ||
                                current.verifiedCount == current.pageCount)
                    }
                },
                limits.fullEpisodeStallTimeoutMillis,
            ) ?: error(
                "Full episode verification made no progress for " +
                    "${limits.fullEpisodeStallTimeoutMillis}ms: " +
                    telemetry.snapshotOrNull(scenario)?.currentEpisodeProgress,
            )
            verifiedCount = progress.verifiedCount
            if (progress.verifiedCount == progress.pageCount) {
                return SystemClock.elapsedRealtime() - startedAtMillis
            }
        }
    }

    private fun inspectScreenshot(name: String, frame: Rect) =
        inspectScreenshot(name, artifacts.screenshot(device, name), frame)

    private fun inspectScreenshot(name: String, file: java.io.File, frame: Rect) {
        screenshotInspections += screenshots.inspect(name, file, frame)
    }

    @Suppress("DEPRECATION")
    private fun recordMemory(stage: String) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val process = manager.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName }
        val pid = process?.pid ?: android.os.Process.myPid()
        val pss = manager.getProcessMemoryInfo(intArrayOf(pid)).singleOrNull()?.totalPss
        if (pss == null || pss <= 0) {
            violations += "PSS was unavailable at $stage"
            return
        }
        memory += MemoryMeasurement(
            stage = stage,
            elapsedMillis = SystemClock.elapsedRealtime() - runStartedAtMillis,
            totalPssKib = pss,
        )
    }

    private fun verifyMemory() {
        val baseline = memory.firstOrNull { it.stage == "baseline" } ?: return
        val active = memory.filterNot { it.stage == "baseline" }
        if (active.isEmpty()) {
            violations += "No active-viewer PSS checkpoints were captured"
            return
        }
        val peakIncrease = active.maxOf { it.totalPssKib } - baseline.totalPssKib
        if (peakIncrease > MAXIMUM_PSS_INCREASE_KIB) {
            violations += "Viewer PSS increased ${peakIncrease}KiB; maximum is ${MAXIMUM_PSS_INCREASE_KIB}KiB"
        }
        val checkpointRange = active.maxOf { it.totalPssKib } - active.minOf { it.totalPssKib }
        if (checkpointRange > MAXIMUM_PSS_CHECKPOINT_RANGE_KIB) {
            violations += "Viewer PSS checkpoint range is ${checkpointRange}KiB; " +
                "maximum is ${MAXIMUM_PSS_CHECKPOINT_RANGE_KIB}KiB"
        }
        val tailIncrease = active.last().totalPssKib - active.first().totalPssKib
        if (tailIncrease > MAXIMUM_PSS_CHECKPOINT_RANGE_KIB) {
            violations += "Viewer late-session PSS increased ${tailIncrease}KiB; " +
                "maximum is ${MAXIMUM_PSS_CHECKPOINT_RANGE_KIB}KiB"
        }
    }

    private fun ViewerTelemetrySnapshot.toLiveEpisode(): LiveEpisode {
        val opened = requireNotNull(manifests.firstOrNull()).id
        return LiveEpisode(
            sourceId = opened.seriesId.sourceId.value,
            seriesKey = opened.seriesId.remoteKey,
            episodeKey = opened.remoteKey,
        )
    }

    private fun clickRecentTask() {
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        val taskSelector = By.res(PIXEL_LAUNCHER_PACKAGE, PIXEL_RECENTS_TASK_ID)
            .desc(appLabel)
        val condition = Condition<UiDevice, Boolean> { current ->
            runCatching {
                val task = current.findObject(taskSelector)?.clickableAncestor()
                if (task == null) false else {
                    task.click()
                    true
                }
            }.getOrDefault(false)
        }
        check(device.wait(condition, limits.homeReturnTimeoutMillis) == true) {
            "Viewer task '$appLabel' was not exposed in Recents"
        }
    }

    private fun requireSurfaceAfterHome(home: HomeRoundTripMeasurement) {
        check(device.hasViewerSurfaceNode()) {
            "Viewer surface disappeared after HOME return"
        }
        check(home.presentedAtNanos >= home.restoreRequestedAtNanos) {
            "HOME return reused stale Surface content"
        }
    }

    private fun captureFrameStats(
        scenario: ActivityScenario<ViewerActivity>,
        startedAtNanos: Long,
    ): FrameStatsSnapshot {
        val presentation = presentationSnapshot(scenario)
        val windows = frameTimingWindows(presentation.gestureWindows)
        val directedWindows = windows.mapIndexed { index, range ->
            PresentationGestureWindow(
                range = range,
                direction = if (gestures[index].direction == Direction.FORWARD.name.lowercase()) {
                    TelemetryDirection.FORWARD
                } else {
                    TelemetryDirection.REVERSE
                },
            )
        }
        val decodedEvidence = NativePresentationEvidencePacking.decode(
            presentation.presentationEvidence,
        )
        ViewerPresentationEvidenceArtifacts.write(
            artifacts.directory,
            decodedEvidence,
            directedWindows,
        )
        violations += ViewerPresentationTraceVerifier.verifyDirected(decodedEvidence, directedWindows)
        return frameStats.capture(
            startedAtNanos = startedAtNanos,
            interactionWindows = windows,
            presentationNanos = presentation.cadenceTimestamps,
            renderSamples = presentation.renderSamples,
            motionFrameSamples = presentation.motionFrames,
            refreshPeriodNanos = presentation.refreshPeriodNanos,
            windowFrameSamples = windowFrameRecorder?.snapshot() ?: LongArray(0),
        )
    }

    private fun frameTimingWindows(observed: List<LongRange>): List<LongRange> {
        check(observed.size >= gestures.size) {
            "Viewer observed only ${observed.size}/${gestures.size} real swipe gestures"
        }
        return observed.takeLast(gestures.size)
    }

    private fun captureFrameStatsSafely(
        scenario: ActivityScenario<ViewerActivity>,
        startedAtNanos: Long,
    ): FrameStatsSnapshot = runCatching {
        captureFrameStats(scenario, startedAtNanos)
    }.getOrElse { failure ->
        violations += "Frame statistics collection failed: ${failure.message}"
        emptyFrameStats()
    }

    private fun presentationSnapshot(
        scenario: ActivityScenario<ViewerActivity>,
    ): PresentationSnapshot {
        val result = AtomicReference<PresentationSnapshot>()
        scenario.onActivity { activity ->
            result.set(PresentationSnapshot(
                activity.presentationNanosSnapshot(),
                activity.presentationCadenceNanosSnapshot(),
                activity.presentationEvidenceSnapshot(),
                activity.renderSamplesSnapshot(),
                activity.motionFrameNanosSnapshot(),
                activity.presentationRefreshPeriodNanos(),
                activity.gestureWindowsSnapshot(),
            ))
        }
        return requireNotNull(result.get())
    }

    private fun readUserInputRevision(scenario: ActivityScenario<ViewerActivity>): Long {
        val result = java.util.concurrent.atomic.AtomicLong()
        scenario.onActivity { activity -> result.set(activity.userInputRevisionSnapshot()) }
        return result.get()
    }

    private fun checkHealth(stage: String) {
        check(!device.hasObject(By.desc(FAILURE_DESCRIPTION))) { "Source failure at $stage" }
        check(ANR_SELECTORS.none(device::hasObject)) { "System ANR/crash dialog at $stage" }
    }

    private fun verifySafeBounds(safe: Rect, frame: Rect) {
        if (safe != frame) {
            violations += "Frame $frame does not exactly fill safe drawing bounds $safe"
        }
        if (frame.top <= 0 || frame.bottom >= device.displayHeight) {
            violations += "Frame overlaps a system bar: frame=$frame display=${device.displayWidth}x${device.displayHeight}"
        }
    }

    private fun verifyThresholds(
        firstFrameMillis: Long,
        startup: ViewerStartupTiming?,
        stats: FrameStatsSnapshot,
    ) {
        if (firstFrameMillis > limits.firstFrameLimitMillis) {
            verifyExternalFirstFrameException(firstFrameMillis, startup)
        }
        val gfx = stats.gfx
        val render = stats.render
        val motion = stats.motion
        val surface = stats.surface
        if (render.sampleCount == 0 && gfx.sampleCount == 0 && motion.sampleCount == 0 &&
            (surface == null || (surface.sampleCount == 0 && surface.responseSampleCount == 0))
        ) {
            violations += "No app or Surface frame timing samples were captured during gestures"
        }
        if (render.sampleCount == 0) {
            violations += "No native render latency samples were captured during gestures"
        }
        verifyP95(render, "Native render")
        if (render.freezeCount > limits.maximumFreezeCount) {
            violations += "${render.freezeCount} native render submissions stalled for at least 100ms"
        }
        if (gfx.freezeCount > limits.maximumFreezeCount) {
            violations += "${gfx.freezeCount} app frames stalled for at least 100ms"
        }
        if (motion.coveredInteractionWindowCount < motion.interactionWindowCount) {
            violations += "Motion frames covered ${motion.coveredInteractionWindowCount}/${motion.interactionWindowCount} gesture windows"
        }
        if (motion.sampleCount == 0) {
            violations += "No consecutive Choreographer motion frames were captured during gestures"
        } else if (motion.missedFrameRatio >= MAXIMUM_MISSED_FRAME_RATIO) {
            violations += "Motion missed-frame ratio ${motion.missedFrameRatio} is not below 1%"
        }
        if (motion.freezeCount > limits.maximumFreezeCount ||
            motion.responseFreezeCount > limits.maximumFreezeCount
        ) {
            violations += "Choreographer motion stalled during a real gesture"
        }
        if (surface == null) {
            violations += "Viewer Surface presentation history was unavailable"
            return
        }
        if (surface.coveredInteractionWindowCount < surface.interactionWindowCount) {
            violations += "Surface presentation covered ${surface.coveredInteractionWindowCount}/${surface.interactionWindowCount} gesture windows"
        }
        if (surface.sampleCount == 0) {
            violations += "No consecutive Surface presentation intervals were captured during gestures"
        } else if (surface.missedFrameRatio >= MAXIMUM_MISSED_FRAME_RATIO) {
            violations += "Surface missed-frame ratio ${surface.missedFrameRatio} is not below 1%"
        }
        if (surface.freezeCount > limits.maximumFreezeCount) {
            violations += "${surface.freezeCount} Surface presentation gaps reached 100ms"
        }
        if (surface.responseFreezeCount > limits.maximumFreezeCount) {
            violations += "${surface.responseFreezeCount} gestures waited at least 100ms for their first Surface presentation"
        }
    }

    private fun verifyExternalFirstFrameException(
        firstFrameMillis: Long,
        startup: ViewerStartupTiming?,
    ) {
        ViewerFirstContentPolicy.violation(
            firstFrameMillis,
            limits.firstFrameLimitMillis,
            startup,
        )?.let(violations::add)
    }

    private fun verifyP95(summary: FrameTimingSummary, label: String) {
        summary.p95Nanos?.let { p95 ->
            if (p95 >= limits.maximumUiP95Nanos) {
                violations += "$label p95 ${summary.p95Millis}ms exceeds ${limits.maximumUiP95Nanos / 1_000_000.0}ms"
            }
        }
    }

    private fun readSafeBounds(scenario: ActivityScenario<ViewerActivity>): Rect {
        val result = AtomicReference<Rect>()
        scenario.onActivity { activity ->
            val insets = activity.window.decorView.rootWindowInsets
            val safe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                null
            }
            @Suppress("DEPRECATION")
            result.set(Rect(
                safe?.left ?: insets?.systemWindowInsetLeft ?: 0,
                safe?.top ?: insets?.systemWindowInsetTop ?: 0,
                device.displayWidth - (safe?.right ?: insets?.systemWindowInsetRight ?: 0),
                device.displayHeight - (safe?.bottom ?: insets?.systemWindowInsetBottom ?: 0),
            ))
        }
        return requireNotNull(result.get())
    }

    private data class PresentedFrame(val timestamp: Long, val bounds: Rect)

    private data class PresentationSnapshot(
        val timestamps: LongArray,
        val cadenceTimestamps: LongArray,
        val presentationEvidence: LongArray,
        val renderSamples: LongArray,
        val motionFrames: LongArray,
        val refreshPeriodNanos: Long,
        val gestureWindows: List<LongRange>,
    )

    private fun UiObject2.toPresentedFrame(): PresentedFrame? {
        val description = contentDescription ?: return null
        val timestamp = description.substringAfter(FRAME_PREFIX, missingDelimiterValue = "").toLongOrNull()
            ?: return null
        return PresentedFrame(timestamp, visibleBounds)
    }

    private fun UiObject2.clickableAncestor(): UiObject2? = generateSequence(this) { node -> node.parent }
        .firstOrNull { node -> node.isClickable }

    private fun UiDevice.hasViewerSurfaceNode(): Boolean =
        hasObject(By.desc(SURFACE_DESCRIPTION)) || hasObject(By.descStartsWith(FRAME_PREFIX))

    private enum class Direction { FORWARD, REVERSE }

    private companion object {
        const val SURFACE_DESCRIPTION = "viewer-surface"
        const val FRAME_PREFIX = "viewer-frame-presented:"
        const val FAILURE_DESCRIPTION = "viewer-failure"
        const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
        const val PIXEL_RECENTS_TASK_ID = "task"
        const val SETTLE_DURATION_MILLIS = 300L
        const val GESTURE_SETTLE_TIMEOUT_MILLIS = 4_000L
        const val IDLE_OBSERVATION_MILLIS = 500L
        const val CONDITION_SLACK_MILLIS = 1_000L
        const val MAXIMUM_PSS_INCREASE_KIB = 192 * 1_024
        const val MAXIMUM_PSS_CHECKPOINT_RANGE_KIB = 64 * 1_024
        const val MAXIMUM_MISSED_FRAME_RATIO = 0.01
        val ANR_SELECTORS = listOf(
            By.res("android", "aerr_wait"),
            By.res("android", "aerr_close"),
            By.textContains("isn't responding"),
            By.textContains("응답하지 않음"),
            By.textContains("keeps stopping"),
            By.textContains("계속 중단됨"),
        )
    }
}
