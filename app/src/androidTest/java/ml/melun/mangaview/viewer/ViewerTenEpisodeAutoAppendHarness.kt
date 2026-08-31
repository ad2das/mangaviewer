package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming

internal class ViewerTenEpisodeAutoAppendHarness(
    private val instrumentation: Instrumentation,
    artifactPrefix: String,
    private val requiredEpisodes: Int = 10,
) {
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val artifacts = ViewerUxArtifacts(context, artifactPrefix)
    private val violations = mutableListOf<String>()
    private val boundaries = mutableListOf<BoundaryEvidence>()
    private val invalidPendingBoundaries = mutableSetOf<EpisodeId>()
    private val presentations = linkedSetOf<Long>()
    private val presentationEvidence = linkedSetOf<NativePresentationEvidence>()
    private val motionFrames = linkedSetOf<MotionEvidence>()
    private val renderEvidence = linkedSetOf<RenderEvidence>()
    private val gestureWindows = linkedSetOf<LongRange>()
    private val telemetryTimeline = mutableListOf<String>()
    private var refreshPeriodNanos = 0L
    private var presentationCursor = 0L
    private var motionCursor = 0L
    private var windowFrameRecorder: ViewerWindowFrameRecorder? = null

    fun run(episode: LiveEpisode) {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val startedAtNanos = System.nanoTime()
        val scenario = ActivityScenario.launch<ViewerActivity>(launchIntent(episode))
        val observedEpisodes = mutableListOf<EpisodeId>()
        var previous: ViewerTelemetrySnapshot? = null
        try {
            check(ViewerUiConditions.waitForSurface(device, SURFACE_TIMEOUT_MILLIS)) {
                "Viewer surface did not accept immediate input"
            }
            scenario.onActivity { activity ->
                windowFrameRecorder = ViewerWindowFrameRecorder(activity.window)
            }
            val bounds = surfaceBounds()
            var gestureCount = 0
            harvestActivityEvidence(scenario)
            while (observedEpisodes.size < requiredEpisodes &&
                SystemClock.elapsedRealtime() - startedAtMillis < RUN_TIMEOUT_MILLIS &&
                gestureCount < MAX_GESTURES
            ) {
                injectForwardSwipe(bounds)
                gestureCount += 1
                harvestActivityEvidence(scenario)
                checkHealth()
                val current = telemetry(scenario) ?: continue
                telemetryTimeline += "$gestureCount\t${current.diagnosticSummary()}\tvisible=" +
                    current.visiblePages.joinToString { page ->
                        "${page.pageId.remoteKey}:actual=${page.coveredUnits}/" +
                            "${page.visibleUnits}:loading=${page.loadingUnits}:shown=${page.presented}"
                    }
                if (gestureCount % PROGRESS_INTERVAL_GESTURES == 0) logProgress(gestureCount, current)
                validateManifestChain(current)
                validateForwardContinuity(previous, current)
                observeEpisodeTransition(observedEpisodes, previous, current)
                previous = current
            }
            ensureDeliveredGestureEvidence(scenario, bounds, gestureCount)
            verifyEpisodeCount(observedEpisodes, previous, gestureCount)
            verifyFirstContent(scenario, startedAtMillis)
            violations += ViewerPresentationTraceVerifier.verify(presentationEvidence, gestureWindows)
            verifyBoundaryPresentations()
            verifyFrameTiming(startedAtNanos)
            artifacts.screenshot(device, "ten-episode-final")
        } catch (failure: Throwable) {
            violations += failure.message ?: failure.javaClass.simpleName
            runCatching { artifacts.screenshot(device, "ten-episode-failure") }
        } finally {
            runCatching {
                artifacts.directory.resolve("telemetry-timeline.txt")
                    .writeText(telemetryTimeline.joinToString(separator = "\n", postfix = "\n"))
            }
            runCatching {
                ViewerPresentationEvidenceArtifacts.write(
                    artifacts.directory,
                    presentationEvidence.sortedBy(NativePresentationEvidence::presentedNanos),
                    gestureWindows.map {
                        PresentationGestureWindow(it, TelemetryDirection.FORWARD)
                    },
                )
            }
            runCatching { scenario.onActivity { windowFrameRecorder?.close() } }
            windowFrameRecorder = null
            scenario.close()
        }
        check(violations.isEmpty()) {
            "Ten-episode auto-append violations: ${violations.joinToString()}; " +
                "evidence=${artifacts.directory.absolutePath}"
        }
    }

    private fun injectForwardSwipe(bounds: Rect) {
        val dispatched = device.swipe(
            bounds.centerX(),
            bounds.top + bounds.height() * 5 / 6,
            bounds.centerX(),
            bounds.top + bounds.height() / 6,
            SWIPE_STEPS,
        )
        if (!dispatched) violations += "Real forward gesture was rejected"
    }

    private fun ensureDeliveredGestureEvidence(
        scenario: ActivityScenario<ViewerActivity>,
        bounds: Rect,
        expectedCount: Int,
    ) {
        harvestActivityEvidence(scenario)
        if (gestureWindows.size < expectedCount) {
            injectForwardSwipe(bounds)
            harvestActivityEvidence(scenario)
        }
        if (gestureWindows.size < expectedCount) {
            violations += "Only ${gestureWindows.size}/$expectedCount gestures reached the viewer"
        }
    }

    private fun validateManifestChain(current: ViewerTelemetrySnapshot) {
        val manifests = current.manifests
        if (manifests.map { it.id }.toSet().size != manifests.size) {
            violations += "Duplicate episode manifest was appended"
        }
        manifests.zipWithNext().forEach { (from, to) ->
            if (from.nextEpisodeId != to.id) {
                violations += "Manifest chain changed from ${from.id.remoteKey} to ${to.id.remoteKey}"
            }
        }
    }

    private fun validateForwardContinuity(
        previous: ViewerTelemetrySnapshot?,
        current: ViewerTelemetrySnapshot,
    ) {
        previous ?: return
        val ordinalMovedBack = current.anchorOrdinal < previous.anchorOrdinal
        val offsetMovedBack = current.anchorOrdinal == previous.anchorOrdinal &&
            current.anchor.offsetInPageUnits < previous.anchor.offsetInPageUnits
        if (ordinalMovedBack || offsetMovedBack) {
            violations += "Scroll moved backward without a reverse gesture: " +
                "${previous.anchor.pageId} -> ${current.anchor.pageId}"
        }
    }

    private fun observeEpisodeTransition(
        observed: MutableList<EpisodeId>,
        previous: ViewerTelemetrySnapshot?,
        current: ViewerTelemetrySnapshot,
    ) {
        val episodeId = current.anchor.pageId.episodeId
        if (observed.lastOrNull() == episodeId) return
        if (current.manifests.none { it.id == episodeId }) {
            val completeLoadingFrame = current.visiblePages.isNotEmpty() &&
                current.visuallyUncoveredViewportUnits == 0L &&
                current.overlappingViewportUnits == 0L &&
                current.visiblePages.all { page ->
                    page.pageId.episodeId == episodeId &&
                        page.visualCoveredUnits == page.visibleUnits &&
                        page.overlappingUnits == 0L
                }
            if (!completeLoadingFrame && invalidPendingBoundaries.add(episodeId)) {
                violations += "Pending boundary had blank or overlapping pixels for ${episodeId.remoteKey}"
            }
            return
        }
        if (episodeId in observed) {
            violations += "Viewer returned to an earlier episode ${episodeId.remoteKey}"
            return
        }
        val expected = observed.lastOrNull()?.let { prior ->
            current.manifests.firstOrNull { it.id == prior }?.nextEpisodeId
        }
        if (expected != null && expected != episodeId) {
            violations += "Episode boundary skipped ${expected.remoteKey} for ${episodeId.remoteKey}"
        }
        observed += episodeId
        if (previous != null && previous.anchor.pageId.episodeId != episodeId) {
            recordBoundary(previous, current)
        }
    }

    private fun recordBoundary(
        previous: ViewerTelemetrySnapshot,
        current: ViewerTelemetrySnapshot,
    ) {
        val declaredPages = current.manifests.flatMap { it.pages }.mapTo(mutableSetOf()) { it.id }
        val wrongPages = current.visiblePages.filterNot { it.pageId in declaredPages }
        if (wrongPages.isNotEmpty()) {
            violations += "Undeclared page appeared at boundary: ${wrongPages.map { it.pageId }}"
        }
        val visibleUnits = current.visiblePages.sumOf(VisiblePageTelemetry::visibleUnits)
        val visualCoveredUnits = current.visiblePages.sumOf(VisiblePageTelemetry::visualCoveredUnits)
        if (current.visuallyUncoveredViewportUnits != 0L || current.overlappingViewportUnits != 0L ||
            visibleUnits > current.viewportHeightUnits || visualCoveredUnits > visibleUnits ||
            current.visiblePages.any {
                it.visualCoveredUnits != it.visibleUnits || it.overlappingUnits != 0L
            }
        ) {
            violations += "Visual blank or overlapping pixels appeared at " +
                "${current.anchor.pageId.episodeId.remoteKey} boundary: " +
                "uncovered=${current.uncoveredViewportUnits}, " +
                "visualUncovered=${current.visuallyUncoveredViewportUnits}, " +
                "overlap=${current.overlappingViewportUnits}, pages=" +
                current.visiblePages.joinToString(prefix = "[", postfix = "]") { page ->
                    "${page.pageId.remoteKey}(visible=${page.visibleUnits}," +
                        "covered=${page.coveredUnits},loading=${page.loadingUnits}," +
                        "visual=${page.visualCoveredUnits},overlap=${page.overlappingUnits}," +
                        "presented=${page.presented})"
                }
        }
        boundaries += BoundaryEvidence(
            from = previous.anchor.pageId.episodeId,
            to = current.anchor.pageId.episodeId,
            interactionWindow = gestureWindows.lastOrNull(),
        )
    }

    private fun verifyEpisodeCount(
        observed: List<EpisodeId>,
        finalEvidence: ViewerTelemetrySnapshot?,
        gestureCount: Int,
    ) {
        if (observed.size != requiredEpisodes) {
            violations += "Observed ${observed.size}/$requiredEpisodes consecutive episode boundaries " +
                "after $gestureCount gestures; ${finalEvidence?.diagnosticSummary()}"
        }
        if ((finalEvidence?.manifests?.map { it.id }?.distinct()?.size ?: 0) < requiredEpisodes) {
            violations += "Fewer than $requiredEpisodes distinct manifests were automatically appended"
        }
        if (observed.map(EpisodeId::seriesId).toSet().size > 1) {
            violations += "Auto append crossed into a different series"
        }
    }

    private fun logProgress(gestureCount: Int, snapshot: ViewerTelemetrySnapshot) {
        android.util.Log.i(
            "ViewerTenEpisode",
            "gestures=$gestureCount ${snapshot.diagnosticSummary()}",
        )
    }

    private fun ViewerTelemetrySnapshot.diagnosticSummary(): String =
        "anchor=${anchor.pageId.episodeId.remoteKey}/${anchor.pageId.remoteKey} " +
            "offset=$scrollOffsetUnits/$contentHeightUnits manifests=${manifests.size} " +
            "verified=${currentEpisodeProgress?.verifiedCount}/${currentEpisodeProgress?.pageCount} " +
            "appends=${episodeAppends.joinToString { append ->
                "${append.fromEpisodeId.remoteKey}->${append.targetEpisodeId?.remoteKey}:" +
                    "terminal=${append.terminal},boundary=${append.hasBoundary},retry=${append.retryReason}"
            }}"

    private fun verifyFirstContent(
        scenario: ActivityScenario<ViewerActivity>,
        startedAtMillis: Long,
    ) {
        val node = device.findObject(By.descStartsWith(FRAME_PREFIX))
        val timestamp = node?.contentDescription?.substringAfter(FRAME_PREFIX)?.toLongOrNull()
        if (timestamp == null) {
            violations += "No real image frame was presented"
            return
        }
        val elapsed = timestamp - startedAtMillis
        val startup = startupTiming(scenario)
        ViewerFirstContentPolicy.violation(
            elapsed,
            FIRST_CONTENT_LIMIT_MILLIS,
            startup,
        )?.let(violations::add)
        artifacts.directory.resolve("first-content.txt").writeText(
            "totalMillis=$elapsed\nlimitMillis=$FIRST_CONTENT_LIMIT_MILLIS\nstartup=$startup\n",
        )
    }

    private fun startupTiming(
        scenario: ActivityScenario<ViewerActivity>,
    ): ViewerStartupTiming? {
        val result = AtomicReference<ViewerStartupTiming?>()
        scenario.onActivity { activity -> result.set(activity.viewerStartupTimingSnapshot()) }
        return result.get()
    }

    private fun verifyBoundaryPresentations() {
        val timestamps = presentations.sorted()
        boundaries.forEach { boundary ->
            val window = boundary.interactionWindow
            val duringBoundary = window?.let { observed -> timestamps.filter { it in observed } }.orEmpty()
            val stalled = duringBoundary.isEmpty() || duringBoundary.zipWithNext().any { (before, after) ->
                after - before >= FREEZE_NANOS
            }
            if (stalled) {
                violations += "Surface stalled at ${boundary.from.remoteKey} -> ${boundary.to.remoteKey}"
            }
        }
    }

    private fun verifyFrameTiming(startedAtNanos: Long) {
        windowFrameRecorder?.droppedReportCount()?.takeIf { it > 0 }?.let { dropped ->
            violations += "Window FrameMetrics dropped $dropped reports"
        }
        val stats = ViewerFrameStats(
            instrumentation,
            context.packageName,
            artifacts.directory,
        ).capture(
            startedAtNanos,
            gestureWindows.toList(),
            presentations.sorted().toLongArray(),
            packedRenderEvidence(),
            packedMotionEvidence(),
            refreshPeriodNanos,
            windowFrameRecorder?.snapshot() ?: LongArray(0),
        )
        artifacts.directory.resolve("frame-stats-summary.txt").writeText(
            "render=${stats.render}\n" +
                "gfx=${stats.gfx}\n" +
                "motion=${stats.motion}\n" +
                "surface=${stats.surface}\n",
        )
        if ((stats.render.p95Nanos ?: Long.MAX_VALUE) >= FRAME_BUDGET_NANOS) {
            violations += "Native render p95 was ${stats.render.p95Millis}ms"
        }
        if (stats.render.freezeCount != 0 || stats.gfx.freezeCount != 0 ||
            stats.motion.freezeCount != 0 || stats.motion.responseFreezeCount != 0
        ) {
            violations += "App/native rendering froze during continuous scroll"
        }
        if (stats.motion.coveredInteractionWindowCount < stats.motion.interactionWindowCount) {
            violations += "Motion frames missed a real gesture window"
        }
        if (stats.motion.missedFrameRatio >= MAXIMUM_MISSED_FRAME_RATIO) {
            violations += "Motion missed-frame ratio was ${stats.motion.missedFrameRatio}"
        }
        val surface = stats.surface
        if (surface == null || surface.freezeCount != 0 || surface.responseFreezeCount != 0) {
            violations += "Surface presentation stalled during continuous scroll"
        } else {
            if (surface.coveredInteractionWindowCount < surface.interactionWindowCount) {
                violations += "Surface presentations missed a real gesture window"
            }
            if (surface.missedFrameRatio >= MAXIMUM_MISSED_FRAME_RATIO) {
                violations += "Surface missed-frame ratio was ${surface.missedFrameRatio}"
            }
        }
    }

    private fun telemetry(scenario: ActivityScenario<ViewerActivity>): ViewerTelemetrySnapshot? {
        val result = AtomicReference<ViewerTelemetrySnapshot?>()
        scenario.onActivity { activity -> result.set(activity.viewerTelemetrySnapshot()) }
        return result.get()
    }

    private fun harvestActivityEvidence(scenario: ActivityScenario<ViewerActivity>) {
        val evidence = activitySnapshot(scenario)
        if (evidence.presentationDropped || evidence.motionDropped) {
            violations += "Evidence recorder overran before an incremental harvest"
        }
        val decoded = NativePresentationEvidencePacking.decode(evidence.presentationEvidence)
        presentationEvidence += decoded
        // Use Android's FrameTimeline cadence exactly like ViewerUxTestHarness. Commit callback
        // delivery time remains in presentationEvidence for semantic/latency verification, but its
        // main-thread callback jitter is not an actual missed display slot.
        presentations += decoded.mapNotNull { sample ->
            sample.expectedPresentationTimeNanos.takeIf {
                sample.frameTimelineVsyncId >= 0L && it > 0L
            }
        }
        decoded.filter { it.presentedNanos > 0L && it.renderLatencyNanos >= 0L }.forEach {
            renderEvidence += RenderEvidence(it.presentedNanos, it.renderLatencyNanos)
        }
        var motionIndex = 0
        while (motionIndex + 1 < evidence.motionFrames.size) {
            val sequence = evidence.motionFrames[motionIndex]
            val timestamp = evidence.motionFrames[motionIndex + 1]
            if (sequence > 0L && timestamp > 0L) {
                motionFrames += MotionEvidence(sequence, timestamp)
            }
            motionIndex += 2
        }
        gestureWindows += evidence.gestureWindows.filter { it.first > 0L && it.last >= it.first }
        refreshPeriodNanos = evidence.refreshPeriodNanos
    }

    private fun packedRenderEvidence(): LongArray {
        val ordered = renderEvidence.sortedBy(RenderEvidence::timestampNanos)
        return LongArray(ordered.size * 2).also { packed ->
            ordered.forEachIndexed { index, sample ->
                packed[index * 2] = sample.timestampNanos
                packed[index * 2 + 1] = sample.durationNanos
            }
        }
    }

    private fun packedMotionEvidence(): LongArray {
        val ordered = motionFrames.sortedBy(MotionEvidence::timestampNanos)
        return LongArray(ordered.size * 2).also { packed ->
            ordered.forEachIndexed { index, sample ->
                packed[index * 2] = sample.sequence
                packed[index * 2 + 1] = sample.timestampNanos
            }
        }
    }

    private fun activitySnapshot(scenario: ActivityScenario<ViewerActivity>): ActivityEvidence {
        val result = AtomicReference<ActivityEvidence>()
        scenario.onActivity { activity ->
            val presentations = activity.presentationEvidenceSince(presentationCursor)
            val motion = activity.motionFramesSince(motionCursor)
            presentationCursor = presentations.nextSequence
            motionCursor = motion.nextSequence
            result.set(ActivityEvidence(
                presentationEvidence = presentations.packed,
                motionFrames = motion.packed,
                gestureWindows = activity.gestureWindowsSnapshot(),
                refreshPeriodNanos = activity.presentationRefreshPeriodNanos(),
                presentationDropped = presentations.dropped,
                motionDropped = motion.dropped,
            ))
        }
        return requireNotNull(result.get())
    }

    private fun surfaceBounds(): Rect {
        val node = device.findObject(By.desc(SURFACE_DESCRIPTION))
            ?: device.findObject(By.descStartsWith(FRAME_PREFIX))
            ?: error("Viewer surface accessibility node disappeared")
        return Rect(node.visibleBounds)
    }

    private fun checkHealth() {
        if (device.hasObject(By.desc(FAILURE_DESCRIPTION))) violations += "Viewer reported a source failure"
        if (ANR_SELECTORS.any(device::hasObject)) violations += "System reported an ANR or crash"
    }

    private fun launchIntent(episode: LiveEpisode): Intent =
        Intent(context, ViewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episode.sourceId)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episode.seriesKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episode.episodeKey)
        }

    private data class BoundaryEvidence(
        val from: EpisodeId,
        val to: EpisodeId,
        val interactionWindow: LongRange?,
    )

    private data class ActivityEvidence(
        val presentationEvidence: LongArray,
        val motionFrames: LongArray,
        val gestureWindows: List<LongRange>,
        val refreshPeriodNanos: Long,
        val presentationDropped: Boolean,
        val motionDropped: Boolean,
    )

    private data class RenderEvidence(
        val timestampNanos: Long,
        val durationNanos: Long,
    )

    private data class MotionEvidence(
        val sequence: Long,
        val timestampNanos: Long,
    )

    private companion object {
        const val SURFACE_DESCRIPTION = "viewer-surface"
        const val FRAME_PREFIX = "viewer-frame-presented:"
        const val FAILURE_DESCRIPTION = "viewer-failure"
        const val SURFACE_TIMEOUT_MILLIS = 5_000L
        const val FIRST_CONTENT_LIMIT_MILLIS = 4_000L
        const val RUN_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        const val MAX_GESTURES = 4_000
        const val SWIPE_STEPS = 6
        const val PROGRESS_INTERVAL_GESTURES = 20
        const val FRAME_BUDGET_NANOS = 16_000_000L
        const val FREEZE_NANOS = 100_000_000L
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
