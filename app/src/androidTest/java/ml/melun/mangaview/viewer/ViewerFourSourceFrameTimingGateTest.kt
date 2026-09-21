package ml.melun.mangaview.viewer

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.viewer.runtime.EngineFrameObservation
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Strict per-source frame-timing gate.
 *
 * Drives the shipped engine with platform touchscreen gestures and scores the app's own
 * presentation evidence plus the platform's Choreographer cadence.
 *
 * Evidence sources and what each one is allowed to prove:
 *
 *  - `engineFramesSince`: the engine's DISPLAY_PRESENT buffer fences.
 *      * `renderLatencyNanos` (present fence minus submission) is the app's own render-to-present
 *        cost per frame, so p95 and >=100 ms freezes are strictly app-attributable.
 *      * The fence timestamps themselves are NOT vsync-quantized on this emulator: recorded
 *        intervals reach 10-13 ms on a 60 Hz display whose only supported rate is 60.000004 Hz
 *        (`dumpsys display`), which is impossible for a true display-present time. They are
 *        therefore used only for coarse, robust properties: per-gesture coverage, response and
 *        tail freezes, and >=100 ms gaps. No vsync-quantized missed-frame ratio is asserted on
 *        them, because such a ratio would be measuring the emulator compositor, not the app.
 *  - `motionFramesSince`: the app's Choreographer cadence on the real scroll path. During a real
 *    drag the cadence is the input device's, not the app's, so cadence is asserted only over the
 *    app-driven fling tail (release .. gesture end), where the app alone decides the schedule.
 *  - A HWUI `OnFrameMetricsAvailableListener` is deliberately NOT used: the viewer renders only
 *    through its own EGL SurfaceView, so the window never produces HWUI frame metrics.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFourSourceFrameTimingGateTest {
    @Test fun ntk() = gate("ntk", "/webtoon/16972", "/webtoon/16972/1445960")
    @Test fun wfwf() = gate("wfwf", "comic:10007", "28")
    @Test fun newxtoon() = gate("newxtoon", "1876", "139976")
    @Test fun goodtoon() = gate("goodtoon", "gt-17070", "803716")

    private data class InjectedGesture(val startedAtNanos: Long, val releasedAtNanos: Long, val forward: Boolean)

    private class Captured(
        val stats: FrameStatsSnapshot,
        val fling: FrameStatsSnapshot,
        val flingWindows: Int,
        val observedGestureWindows: Int,
        val displayPresentFrames: Int,
        val contentPresentFrames: Int,
        val contentCoveredGestureWindows: Int,
        val nonDisplayPresentFrames: Int,
        val invalidDisplayTimestamps: Int,
        val lostFrameObservations: Long,
    )

    /**
     * Continuous drain of the engine's bounded 512-entry frame ring. One gesture produces a few
     * dozen frames, so the test drains between gestures; production capacity is never enlarged,
     * disabled or replaced.
     */
    private class DisplayEvidence {
        private val presented = LinkedHashSet<Long>()
        private val content = LinkedHashSet<Long>()
        private val latencyAtNanos = LinkedHashMap<Long, Long>()
        private val rows = mutableListOf<EngineFrameObservation>()
        private var cursor = 0L
        var nonDisplayPresent = 0
            private set
        var invalidTimestamps = 0
            private set
        var lost = 0L
            private set

        fun drain(activity: ViewerActivity) {
            val batch = activity.engineFramesSince(cursor)
            cursor = batch.latestOrdinal
            lost += batch.lostCount
            batch.observations.forEach { observation ->
                rows += observation
                val frame = observation.presentation
                if (frame.timestampKind != PresentationTimestampKind.DISPLAY_PRESENT) {
                    nonDisplayPresent += 1
                    return@forEach
                }
                val timestamp = frame.timestampNanos
                if (timestamp <= 0L || timestamp == Long.MAX_VALUE) {
                    invalidTimestamps += 1
                    return@forEach
                }
                presented += timestamp
                latencyAtNanos[timestamp] = frame.renderLatencyNanos
                if (frame.scene.placements.isNotEmpty()) content += timestamp
            }
        }

        fun presentedNanos(): LongArray = presented.sorted().toLongArray()

        fun contentNanos(): LongArray = content.sorted().toLongArray()

        /** Packed (presentedNanos, renderLatencyNanos) pairs for frames the app measured a latency for. */
        fun renderSamples(): LongArray {
            val usable = presented.sorted().mapNotNull { timestamp ->
                latencyAtNanos[timestamp]?.takeIf { it >= 0L }?.let { latency -> timestamp to latency }
            }
            return LongArray(usable.size * 2).also { packed ->
                usable.forEachIndexed { index, (timestamp, latency) ->
                    packed[index * 2] = timestamp
                    packed[index * 2 + 1] = latency
                }
            }
        }

        fun write(run: File) {
            run.resolve("engine-display-frames.tsv").writeText(buildString {
                appendLine(
                    "ordinal\tswapSucceeded\ttimestampKind\ttimestampNanos\tsubmittedAtNanos\t" +
                        "renderLatencyNanos\tgeneration\tinputRevision\tgeometryRevision\teglFrameId\t" +
                        "rendererId\tplacements\tcompleteCoverage\tscrollAnchorYQ32",
                )
                rows.forEach { observation ->
                    val frame = observation.presentation
                    val scene = frame.scene
                    append(observation.ordinal).append('\t').append(frame.swapSucceeded).append('\t')
                        .append(frame.timestampKind).append('\t').append(frame.timestampNanos).append('\t')
                        .append(frame.submittedAtNanos).append('\t').append(frame.renderLatencyNanos).append('\t')
                        .append(scene.generation).append('\t').append(scene.inputRevision).append('\t')
                        .append(scene.geometryRevision).append('\t').append(frame.eglFrameId).append('\t')
                        .append(frame.rendererId).append('\t').append(scene.placements.size).append('\t')
                        .append(scene.completeCoverage).append('\t')
                        .append(scene.anchor?.sourceYQ32 ?: 0L).appendLine()
                }
            })
        }
    }

    private fun gate(sourceId: String, seriesKey: String, episodeKey: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val output = File(context.getExternalFilesDir(null), "frame-timing-gate").apply { mkdirs() }
        val run = File(output, "$sourceId-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val startedAtNanos = System.nanoTime()
        val startedAtMillis = SystemClock.elapsedRealtime()
        val violations = mutableListOf<String>()
        var firstCompleteViewportMillis: Long? = null
        var firstActualPresentedMillis: Long? = null
        var captured: Captured? = null

        val intent = Intent(context, ViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, sourceId)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, seriesKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episodeKey)
        }
        try {
            ActivityScenario.launch<ViewerActivity>(intent).use { scenario ->
                awaitSurfaceReady(scenario, violations)
                val safe = readSafeBounds(scenario, device)
                val timing = awaitFirstCompleteViewport(scenario, violations)
                timing?.firstCompleteViewportSubmittedAtNanos?.let {
                    firstCompleteViewportMillis = (it - startedAtNanos) / MILLIS_NANOS
                }
                timing?.firstActualPresentedAtNanos?.let {
                    firstActualPresentedMillis = (it - startedAtNanos) / MILLIS_NANOS
                }
                awaitViewerFocus(device, context.packageName, violations)
                instrumentation.waitForIdleSync()
                // Cold-start latency is asserted separately (firstCompleteViewportMillis). Scroll
                // smoothness is a steady-state property, so let the engine finish its initial
                // neighbour prefetch instead of folding startup work into the scroll cadence.
                SystemClock.sleep(STARTUP_SETTLE_MILLIS)

                val evidence = DisplayEvidence()
                val injected = mutableListOf<InjectedGesture>()
                repeat(FORWARD_GESTURES) { index ->
                    injected += injectGesture(safe, forward = true, ordinal = index, run = run)
                    scenario.onActivity { evidence.drain(it) }
                }
                repeat(REVERSE_GESTURES) { index ->
                    injected += injectGesture(safe, forward = false, ordinal = index + FORWARD_GESTURES, run = run)
                    scenario.onActivity { evidence.drain(it) }
                }
                SystemClock.sleep(SETTLE_MILLIS)
                scenario.onActivity { evidence.drain(it) }

                captured = capture(
                    scenario, instrumentation, context.packageName, run, startedAtNanos, injected, evidence,
                )
                verify(captured, injected.size, violations)
            }
        } catch (failure: Throwable) {
            violations += failure.message ?: failure.javaClass.simpleName
        } finally {
            val result = captured
            run.resolve("summary.json").writeText(JSONObject().apply {
                put("scope", "FOUR_SOURCE_FRAME_TIMING_GATE")
                put("sourceId", sourceId)
                put("seriesKey", seriesKey)
                put("episodeKey", episodeKey)
                put("startedAtMillis", startedAtMillis)
                put("firstActualPresentedMillis", firstActualPresentedMillis ?: JSONObject.NULL)
                put("firstCompleteViewportMillis", firstCompleteViewportMillis ?: JSONObject.NULL)
                put("injectedGestures", FORWARD_GESTURES + REVERSE_GESTURES)
                put("observedGestureWindows", result?.observedGestureWindows ?: JSONObject.NULL)
                put("flingGestureWindows", result?.flingWindows ?: JSONObject.NULL)
                put("displayPresentFrames", result?.displayPresentFrames ?: JSONObject.NULL)
                put("contentPresentFrames", result?.contentPresentFrames ?: JSONObject.NULL)
                put("contentCoveredGestureWindows", result?.contentCoveredGestureWindows ?: JSONObject.NULL)
                put("nonDisplayPresentFrames", result?.nonDisplayPresentFrames ?: JSONObject.NULL)
                put("invalidDisplayTimestamps", result?.invalidDisplayTimestamps ?: JSONObject.NULL)
                put("lostFrameObservations", result?.lostFrameObservations ?: JSONObject.NULL)
                put("render", result?.stats?.render?.toJson() ?: JSONObject.NULL)
                put("motion", result?.stats?.motion?.toJson() ?: JSONObject.NULL)
                put("surface", result?.stats?.surface?.toJson() ?: JSONObject.NULL)
                put("flingMotion", result?.fling?.motion?.toJson() ?: JSONObject.NULL)
                put("flingSurface", result?.fling?.surface?.toJson() ?: JSONObject.NULL)
                put("violations", JSONArray(violations))
                put("passed", violations.isEmpty())
            }.toString(2))
        }
        check(violations.isEmpty()) {
            "Frame timing gate failed for $sourceId: ${violations.joinToString()}; evidence=${run.absolutePath}"
        }
    }

    private fun capture(
        scenario: ActivityScenario<ViewerActivity>,
        instrumentation: android.app.Instrumentation,
        packageName: String,
        run: File,
        startedAtNanos: Long,
        injected: List<InjectedGesture>,
        evidence: DisplayEvidence,
    ): Captured {
        val result = AtomicReference<Captured>()
        scenario.onActivity { activity ->
            val observed = activity.gestureWindowsSnapshot()
            val count = minOf(observed.size, injected.size)
            val windows = observed.takeLast(count)
            val gestures = injected.takeLast(count)
            val motion = activity.motionFramesSince(0L)
            val refreshPeriod = activity.presentationRefreshPeriodNanos()
            val presented = evidence.presentedNanos()
            val content = evidence.contentNanos()
            val renderSamples = evidence.renderSamples()
            val motionFrameSamples = motion.packed
            val motionApplicationTimestamps = motion.applicationTimestamps
            val windowStarts = gestures.map(InjectedGesture::startedAtNanos).toLongArray()

            ViewerPresentationEvidenceArtifacts.write(run, emptyList(), windows.mapIndexed { index, range ->
                PresentationGestureWindow(
                    range = range,
                    direction = if (gestures[index].forward) TelemetryDirection.FORWARD
                    else TelemetryDirection.REVERSE,
                )
            })
            evidence.write(run)

            fun snapshot(directory: File, interactionWindows: List<LongRange>, starts: LongArray) =
                ViewerFrameStats(instrumentation, packageName, directory).capture(
                    startedAtNanos = startedAtNanos,
                    interactionWindows = interactionWindows,
                    presentationNanos = presented,
                    renderSamples = renderSamples,
                    motionFrameSamples = motionFrameSamples,
                    refreshPeriodNanos = refreshPeriod,
                    windowFrameSamples = LongArray(0),
                    motionApplicationTimestamps = motionApplicationTimestamps,
                    injectedGestureStarts = starts,
                )

            val stats = snapshot(run, windows, windowStarts)

            // The app-driven tail: after the release the engine alone decides when to draw, so its
            // cadence there is fully attributable to the app.
            val flingPairs = windows.mapIndexedNotNull { index, range ->
                val release = gestures[index].releasedAtNanos
                if (release in range.first until range.last) release..range.last else null
            }
            val flingStarts = flingPairs.map(LongRange::first).toLongArray()
            val flingDir = File(run, "fling").apply { mkdirs() }
            val fling = snapshot(flingDir, flingPairs, flingStarts)

            result.set(Captured(
                stats = stats,
                fling = fling,
                flingWindows = flingPairs.size,
                observedGestureWindows = observed.size,
                displayPresentFrames = presented.size,
                contentPresentFrames = content.size,
                contentCoveredGestureWindows = windows.count { window -> content.any { it in window } },
                nonDisplayPresentFrames = evidence.nonDisplayPresent,
                invalidDisplayTimestamps = evidence.invalidTimestamps,
                lostFrameObservations = evidence.lost,
            ))
        }
        return requireNotNull(result.get())
    }

    private fun verify(captured: Captured, gestureCount: Int, violations: MutableList<String>) {
        val stats = captured.stats
        if (captured.observedGestureWindows < gestureCount) {
            violations += "Viewer observed ${captured.observedGestureWindows}/$gestureCount real touchscreen gestures"
        }
        if (captured.lostFrameObservations > 0L) {
            violations += "${captured.lostFrameObservations} engine frame observations were overwritten before capture"
        }

        // 1. The app's own render-to-present cost per frame.
        val render = stats.render
        if (render.sampleCount == 0) {
            violations += "No native render latency samples were captured"
        } else {
            val p95 = render.p95Nanos
            if (p95 == null) {
                violations += "Native render p95 was unavailable"
            } else if (p95 >= MAXIMUM_P95_NANOS) {
                violations += "Native render p95 ${p95 / NANOS_PER_MILLISECOND}ms exceeds 16ms"
            }
            if (render.freezeCount > 0) {
                violations += "${render.freezeCount} native render submissions stalled >=100ms"
            }
        }

        // 2. App main-thread health over the real gestures.
        val motion = stats.motion
        if (motion.sampleCount == 0) {
            violations += "No consecutive Choreographer motion frames were captured"
        } else {
            if (motion.freezeCount > 0) violations += "Choreographer motion stalled >=100ms during a real gesture"
            if (motion.coveredInteractionWindowCount < motion.interactionWindowCount) {
                violations += "Motion covered ${motion.coveredInteractionWindowCount}/" +
                    "${motion.interactionWindowCount} gesture windows"
            }
        }

        // 3. The app-driven fling tail must be frame-perfect: the engine alone paces it.
        val flingMotion = captured.fling.motion
        if (captured.flingWindows == 0) {
            violations += "No gesture produced an app-driven fling tail"
        } else if (flingMotion.sampleCount == 0) {
            violations += "No app-driven fling frames were captured"
        } else {
            if (flingMotion.missedFrameCount > 0) {
                violations += "${flingMotion.missedFrameCount} app-driven fling frames missed a display slot " +
                    "(ratio ${flingMotion.missedFrameRatio})"
            }
            if (flingMotion.freezeCount > 0) violations += "${flingMotion.freezeCount} app-driven fling frames stalled >=100ms"
            if (flingMotion.coveredInteractionWindowCount < flingMotion.interactionWindowCount) {
                violations += "Fling covered ${flingMotion.coveredInteractionWindowCount}/" +
                    "${flingMotion.interactionWindowCount} gesture tails"
            }
        }

        // 4. Displayed frames: coverage, response and tail freezes, and >=100ms gaps only.
        val surface = stats.surface
        if (surface == null) {
            violations += "Viewer Surface presentation history was unavailable"
        } else if (surface.sampleCount == 0) {
            violations += "No consecutive Surface presentation intervals were captured"
        } else {
            if (surface.freezeCount > 0) violations += "${surface.freezeCount} Surface presentation gaps reached 100ms"
            if (surface.responseFreezeCount > 0) {
                violations += "${surface.responseFreezeCount} gestures waited >=100ms for their first Surface presentation"
            }
            if (surface.tailFreezeCount > 0) {
                violations += "${surface.tailFreezeCount} gesture tails froze after their last Surface presentation"
            }
            if (surface.coveredInteractionWindowCount < surface.interactionWindowCount) {
                violations += "Surface covered ${surface.coveredInteractionWindowCount}/" +
                    "${surface.interactionWindowCount} gesture windows"
            }
        }

        // 5. Content must be on screen for every gesture, not just empty frames.
        if (captured.contentPresentFrames == 0) {
            violations += "No presented frame carried page content"
        } else if (captured.contentCoveredGestureWindows < captured.observedGestureWindows) {
            violations += "Page content was on screen for ${captured.contentCoveredGestureWindows}/" +
                "${captured.observedGestureWindows} gestures"
        }
    }

    private fun awaitSurfaceReady(scenario: ActivityScenario<ViewerActivity>, violations: MutableList<String>) {
        val deadline = SystemClock.elapsedRealtime() + SURFACE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = it.isViewerInputSurfaceReady() }
            if (ready) return
            SystemClock.sleep(POLL_MILLIS)
        }
        violations += "Viewer input surface never became ready"
    }

    private fun awaitFirstCompleteViewport(
        scenario: ActivityScenario<ViewerActivity>,
        violations: MutableList<String>,
    ): ViewerStartupTiming? {
        val deadline = SystemClock.elapsedRealtime() + VIEWPORT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = AtomicReference<ViewerStartupTiming?>()
            scenario.onActivity { result.set(it.viewerStartupTimingSnapshot()) }
            val timing = result.get()
            if (timing?.firstCompleteViewportSubmittedAtNanos != null) return timing
            SystemClock.sleep(POLL_MILLIS)
        }
        violations += "Viewer never submitted a first complete viewport"
        return null
    }

    private fun awaitViewerFocus(device: UiDevice, packageName: String, violations: MutableList<String>) {
        val deadline = SystemClock.elapsedRealtime() + FOCUS_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.currentPackageName == packageName) return
            SystemClock.sleep(POLL_MILLIS)
        }
        violations += "Viewer window never became the focused package"
    }

    private fun injectGesture(safe: Rect, forward: Boolean, ordinal: Int, run: File): InjectedGesture {
        val centerX = safe.centerX().toFloat()
        val upperY = (safe.top + safe.height() / 4).toFloat()
        val lowerY = (safe.top + safe.height() * 3 / 4).toFloat()
        val startY = if (forward) lowerY else upperY
        val endY = if (forward) upperY else lowerY
        val requested = abs(startY - endY)
        check(requested in 1f..safe.height().toFloat()) { "Gesture distance is not nonzero and bounded: $requested" }
        val startedAtNanos = System.nanoTime()
        val releasedAtNanos = injectTouchscreenSwipe(centerX, startY, centerX, endY, GESTURE_STEPS, run, forward, ordinal)
        return InjectedGesture(startedAtNanos, releasedAtNanos, forward)
    }

    /** Returns the wall time of the accepted ACTION_UP, which is where the app-driven tail begins. */
    private fun injectTouchscreenSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        steps: Int,
        run: File,
        forward: Boolean,
        ordinal: Int,
    ): Long {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val records = StringBuilder()
        fun send(action: Int, x: Float, y: Float): Long? {
            var attempt = 0
            while (true) {
                val eventTime = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val accepted = try {
                    automation.injectInputEvent(event, true)
                } finally {
                    event.recycle()
                }
                val atNanos = System.nanoTime()
                records.append("{\"ordinal\":").append(ordinal).append(",\"forward\":").append(forward)
                    .append(",\"action\":").append(action).append(",\"x\":").append(x).append(",\"y\":").append(y)
                    .append(",\"attempt\":").append(attempt).append(",\"accepted\":").append(accepted)
                    .append(",\"atNanos\":").append(atNanos).append("}\n")
                if (accepted) return atNanos
                if (attempt >= INJECTION_RETRY_LIMIT) return null
                attempt += 1
                SystemClock.sleep(INJECTION_RETRY_DELAY_MILLIS)
            }
        }
        val total = steps.coerceAtLeast(1)
        var released: Long? = null
        var dispatched = send(MotionEvent.ACTION_DOWN, startX, startY) != null
        if (dispatched) {
            for (step in 1..total) {
                SystemClock.sleep(SWIPE_SAMPLE_DELAY_MILLIS)
                val fraction = step / total.toFloat()
                val moved = send(
                    MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * fraction,
                    startY + (endY - startY) * fraction,
                )
                if (moved == null) {
                    dispatched = false
                    break
                }
            }
        }
        if (dispatched) {
            released = send(MotionEvent.ACTION_UP, endX, endY)
            dispatched = released != null
        } else {
            runCatching { send(MotionEvent.ACTION_CANCEL, endX, endY) }
        }
        runCatching { run.resolve("injected-touch.jsonl").appendText(records.toString()) }
        check(dispatched) { "Platform touchscreen injection was rejected" }
        SystemClock.sleep(INTER_GESTURE_MILLIS)
        return checkNotNull(released)
    }

    private fun readSafeBounds(scenario: ActivityScenario<ViewerActivity>, device: UiDevice): Rect {
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

    private fun FrameTimingSummary.toJson(): JSONObject = JSONObject()
        .put("sampleCount", sampleCount)
        .put("p95Nanos", p95Nanos ?: JSONObject.NULL)
        .put("p95Millis", p95Millis ?: JSONObject.NULL)
        .put("maximumMillis", maximumMillis ?: JSONObject.NULL)
        .put("missedFrameCount", missedFrameCount)
        .put("missedFrameRatio", missedFrameRatio)
        .put("freezeCount", freezeCount)
        .put("interactionWindowCount", interactionWindowCount)
        .put("coveredInteractionWindowCount", coveredInteractionWindowCount)
        .put("responseFreezeCount", responseFreezeCount)
        .put("tailFreezeCount", tailFreezeCount)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val MILLIS_NANOS = 1_000_000L
        const val FORWARD_GESTURES = 8
        const val REVERSE_GESTURES = 6
        const val GESTURE_STEPS = 10
        const val SWIPE_SAMPLE_DELAY_MILLIS = 8L
        const val INTER_GESTURE_MILLIS = 220L
        const val SETTLE_MILLIS = 1_200L
        const val STARTUP_SETTLE_MILLIS = 1_500L
        const val INJECTION_RETRY_LIMIT = 8
        const val INJECTION_RETRY_DELAY_MILLIS = 25L
        const val POLL_MILLIS = 20L
        const val SURFACE_TIMEOUT_MILLIS = 20_000L
        const val VIEWPORT_TIMEOUT_MILLIS = 45_000L
        const val FOCUS_TIMEOUT_MILLIS = 10_000L
        const val MAXIMUM_P95_NANOS = 16_000_000L
    }
}
