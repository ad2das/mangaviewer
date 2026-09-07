package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidence
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming
import ml.melun.mangaview.viewer.runtime.PresentationTimestampKind
import org.json.JSONArray
import org.json.JSONObject

internal class ViewerTenEpisodeAutoAppendHarness(
    private val instrumentation: Instrumentation,
    artifactPrefix: String,
    private val requiredEpisodes: Int = 10,
    private val expectedEpisodes: List<EpisodeId>? = null,
    private val checkpoint: () -> Unit = {},
    private val externalDisplay: Boolean = false,
    artifactParent: java.io.File? = null,
    private val timingPolicy: QualificationTimingPolicy = QualificationTimingPolicy(android.os.Build.FINGERPRINT),
    private val diagnosticMode: Boolean = false,
    private val runTimeoutMillis: Long = RUN_TIMEOUT_MILLIS,
) {
    init {
        require(requiredEpisodes > 0)
        require(runTimeoutMillis > 0L)
        require(expectedEpisodes == null ||
            (expectedEpisodes.size == requiredEpisodes && expectedEpisodes.distinct().size == requiredEpisodes))
    }

    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val artifacts = ViewerUxArtifacts(context, artifactPrefix, artifactParent)
    private val violations = mutableListOf<String>()
    private val caughtFailures = mutableListOf<Throwable>()
    private val sampleKey = artifactPrefix
    private val timingObservations = JSONArray()
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
    private val displayedRows = DisplayedRowCoverage()
    private val navigationRows = DisplayedRowCoverage()
    private var regionCursor = 0L
    private var collectionEndAtNanos = 0L
    private val directedGestures = mutableListOf<PresentationGestureWindow>()
    private val requestedDirections = mutableListOf<Pair<Long, TelemetryDirection>>()
    private var currentDirection = TelemetryDirection.FORWARD

    fun run(episode: LiveEpisode, uiLaunch: ViewerUiLaunch? = null) {
        val startedAtMillis = uiLaunch?.startedMillis ?: SystemClock.elapsedRealtime()
        val startedAtNanos = uiLaunch?.startedNanos ?: System.nanoTime()
        val scenario = if (uiLaunch == null) ActivityScenario.launch<ViewerActivity>(launchIntent(episode)) else null
        artifacts.directory.resolve("collection.json").writeText(JSONObject()
            .put("startedAtMillis", startedAtMillis).put("startedAtNanos", startedAtNanos)
            .put("processPid", android.os.Process.myPid()).put("packageName", context.packageName)
            .put("requiredEpisodes", requiredEpisodes)
            .put("sampleKey", sampleKey)
            .put("mode", if (diagnosticMode) "DIAGNOSTIC_NO_CORPUS_CREDIT" else "QUALIFICATION")
            .put("externalDisplayVerificationRequired", externalDisplay).toString(2))
        val observedEpisodes = mutableListOf<EpisodeId>()
        var previous: ViewerTelemetrySnapshot? = null
        var lastEpisodeComplete = false
        lateinit var activity: ViewerActivity
        try {
            check(ViewerUiConditions.waitForSurface(device, SURFACE_TIMEOUT_MILLIS)) {
                "Viewer surface did not accept immediate input"
            }
            if (uiLaunch != null) activity = uiLaunch.activity
            else requireNotNull(scenario).onActivity { activity = it }
            onMain { windowFrameRecorder = ViewerWindowFrameRecorder(activity.window) }
            val bounds = surfaceBounds()
            var gestureCount = 0
            var backfill = false
            harvestActivityEvidence(activity)
            while (!lastEpisodeComplete &&
                SystemClock.elapsedRealtime() - startedAtMillis < runTimeoutMillis &&
                gestureCount < MAX_GESTURES
            ) {
                val plannedDirection = if (backfill) gapDirection(previous) else TelemetryDirection.FORWARD
                injectSwipe(bounds, plannedDirection)
                gestureCount += 1
                harvestActivityEvidence(activity)
                checkHealth(activity)
                if (violations.isNotEmpty()) break
                val current = telemetry(activity) ?: continue
                telemetryTimeline += "$gestureCount\t${current.diagnosticSummary()}\tvisible=" +
                    current.visiblePages.joinToString { page ->
                        "${page.pageId.remoteKey}:actual=${page.coveredUnits}/" +
                            "${page.visibleUnits}:loading=${page.loadingUnits}:shown=${page.presented}"
                    }
                if (gestureCount == 1 || gestureCount % PROGRESS_INTERVAL_GESTURES == 0) {
                    logProgress(gestureCount, current)
                    checkpoint()
                }
                validateManifestChain(current)
                if (!backfill) {
                    validateForwardContinuity(previous, current)
                    observeEpisodeTransition(observedEpisodes, previous, current)
                }
                backfill = backfill || reachedLastEpisodeEnd(observedEpisodes, current) || pastLastPage(current)
                val expectedPages = expectedPages(current)
                lastEpisodeComplete = backfill && current.manifests.size >= requiredEpisodes &&
                    expectedPages.isNotEmpty() && navigationRows.firstMissing(expectedPages) == null
                previous = current
                if (violations.isNotEmpty()) break
            }
            checkHealth(activity)
            ViewerScreenshotEvidence(instrumentation, artifacts.directory)
                .capture(activity, episode, "ten-episode-before-stop")
            harvestActivityEvidence(activity)
            // A real stationary tap ends the final fling; no extra scroll is injected merely to close its evidence window.
            if (!device.click(bounds.centerX(), bounds.centerY())) violations += "Final stop tap was rejected"
            ensureDeliveredGestureEvidence(activity, bounds, gestureCount)
            val finalPages = previous?.let(::expectedPages).orEmpty()
            verifyEpisodeCount(observedEpisodes, previous, gestureCount)
            if (!lastEpisodeComplete) violations += "Final episode's last page was not fully traversed"
            verifyFirstContent(activity, startedAtMillis)
            if (!externalDisplay) {
                violations += displayedRows.violations(finalPages)
                violations += ViewerPresentationTraceVerifier.verifyDirected(presentationEvidence, directedGestures)
                verifyBoundaryPresentations()
            }
            artifacts.directory.resolve("expected-pages.json").writeText(JSONArray(finalPages.map(::pageJson)).toString(2))
            artifacts.directory.resolve("displayed-rows.tsv").writeText(displayedRows.report())
            artifacts.directory.resolve("navigation-rows-NOT-DISPLAY-PROOF.tsv").writeText(navigationRows.report())
            verifyFrameTiming(startedAtNanos)
            ViewerScreenshotEvidence(instrumentation, artifacts.directory)
                .capture(activity, episode, "ten-episode-final")
            harvestActivityEvidence(activity)
        } catch (failure: Throwable) {
            recordFailure(failure)
            runCatching { ViewerScreenshotEvidence(instrumentation, artifacts.directory)
                .capture(activity, episode, "ten-episode-failure") }
        } finally {
            artifacts.directory.resolve("collection.json").writeText(JSONObject()
                .put("startedAtMillis", startedAtMillis).put("startedAtNanos", startedAtNanos)
                .put("processPid", android.os.Process.myPid()).put("packageName", context.packageName)
                .put("completedAtNanos", collectionEndAtNanos).put("collectionEndAtNanos", collectionEndAtNanos)
                .put("refreshPeriodNanos", refreshPeriodNanos).put("requiredEpisodes", requiredEpisodes)
                .put("mode", if (diagnosticMode) "DIAGNOSTIC_NO_CORPUS_CREDIT" else "QUALIFICATION")
                .put("sampleKey", sampleKey).put("externalDisplayVerificationRequired", externalDisplay).toString(2))
            artifacts.directory.resolve("timing-observations.json").writeText(timingObservations.toString(2))
            runCatching {
                artifacts.directory.resolve("telemetry-timeline.txt")
                    .writeText(telemetryTimeline.joinToString(separator = "\n", postfix = "\n"))
            }
            runCatching {
                ViewerPresentationEvidenceArtifacts.write(
                    artifacts.directory,
                    presentationEvidence.sortedBy(NativePresentationEvidence::presentedNanos),
                    directedGestures,
                )
            }
            runCatching { onMain { windowFrameRecorder?.close() } }.onFailure(::recordFailure)
            windowFrameRecorder = null
            runCatching { scenario?.close() }.onFailure(::recordFailure)
            if (caughtFailures.isNotEmpty()) {
                artifacts.directory.resolve("failure-stacktraces.txt").writeText(
                    caughtFailures.mapIndexed { index, failure -> "Failure ${index + 1}:\n${failure.stackTraceToString()}" }
                        .joinToString("\n\n"),
                )
            }
        }
        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "Ten-episode auto-append violations: ${violations.joinToString()}; evidence=${artifacts.directory.absolutePath}",
                caughtFailures.firstOrNull(),
            ).also { combined -> caughtFailures.drop(1).forEach(combined::addSuppressed) }
        }
    }

    private fun injectForwardSwipe(bounds: Rect) = injectSwipe(bounds, TelemetryDirection.FORWARD)

    private fun injectSwipe(bounds: Rect, direction: TelemetryDirection) {
        currentDirection = direction
        requestedDirections += System.nanoTime() to direction
        val start = if (direction == TelemetryDirection.FORWARD) 4 else 2
        val end = if (direction == TelemetryDirection.FORWARD) 2 else 4
        val dispatched = device.swipe(
            bounds.centerX(),
            bounds.top + bounds.height() * start / 6,
            bounds.centerX(),
            bounds.top + bounds.height() * end / 6,
            SWIPE_STEPS,
        )
        if (!dispatched) violations += "Real forward gesture was rejected"
    }

    private fun ensureDeliveredGestureEvidence(
        activity: ViewerActivity,
        bounds: Rect,
        expectedCount: Int,
    ) {
        harvestActivityEvidence(activity)
        if (gestureWindows.size < expectedCount) {
            violations += "Only ${gestureWindows.size}/$expectedCount gestures reached the viewer"
        }
    }

    private fun validateManifestChain(current: ViewerTelemetrySnapshot) {
        val manifests = current.manifests
        expectedEpisodes?.let { expected ->
            if (manifests.take(requiredEpisodes).map { it.id } != expected.take(manifests.size)) {
                violations += "Appended episodes differ from independently discovered chain"
            }
        }
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
        CorpusEpisodeOrderContract.violations(observed, expectedEpisodes, requiredEpisodes).forEach { violation ->
            violations += if (violation.startsWith("Observed ") && violation.contains("consecutive episode boundaries")) {
                "$violation after $gestureCount gestures; ${finalEvidence?.diagnosticSummary()}"
            } else {
                violation
            }
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

    private fun reachedLastEpisodeEnd(observed: List<EpisodeId>, current: ViewerTelemetrySnapshot): Boolean {
        if (observed.isEmpty()) return false
        val lastEpisode = expectedEpisodes?.lastOrNull() ?: observed.last()
        val lastPage = current.manifests.firstOrNull { it.id == lastEpisode }?.pages?.lastOrNull()?.id
            ?: return false
        return EpisodeTraversalEnd.reached(lastPage, current.visiblePages)
    }

    private fun expectedPages(current: ViewerTelemetrySnapshot): List<PageId> = current.manifests
        .filter { expectedEpisodes?.contains(it.id) ?: (current.manifests.indexOf(it) < requiredEpisodes) }
        .flatMap { it.pages }.map { it.id }

    private fun pastLastPage(current: ViewerTelemetrySnapshot): Boolean {
        if (current.manifests.size < requiredEpisodes) return false
        val last = expectedPages(current).lastOrNull() ?: return false
        val ordinal = current.manifests.flatMap { it.pages }.indexOfFirst { it.id == last }
        return current.anchorOrdinal >= ordinal
    }

    private fun gapDirection(current: ViewerTelemetrySnapshot?): TelemetryDirection {
        current ?: return TelemetryDirection.FORWARD
        val pages = expectedPages(current)
        val missing = navigationRows.firstMissing(pages) ?: return TelemetryDirection.FORWARD
        val targetOrdinal = current.manifests.flatMap { it.pages }.indexOfFirst { it.id == missing }
        if (current.anchorOrdinal != targetOrdinal) return if (current.anchorOrdinal > targetOrdinal)
            TelemetryDirection.REVERSE else TelemetryDirection.FORWARD
        val visible = current.visiblePages.firstOrNull { it.pageId == missing }
            ?: return TelemetryDirection.REVERSE
        val height = navigationRows.sourceHeight(missing) ?: return TelemetryDirection.REVERSE
        val target = navigationRows.firstMissingRow(missing).toDouble() / height * visible.pageHeightUnits
        return if (visible.visibleOffsetInPageUnits.toDouble() > target) TelemetryDirection.REVERSE
            else TelemetryDirection.FORWARD
    }

    private fun pageJson(page: PageId): JSONObject = JSONObject()
        .put("sourceId", page.episodeId.seriesId.sourceId.value)
        .put("seriesKey", page.episodeId.seriesId.remoteKey)
        .put("episodeKey", page.episodeId.remoteKey).put("pageKey", page.remoteKey)

    private fun ViewerTelemetrySnapshot.diagnosticSummary(): String =
        "anchor=${anchor.pageId.episodeId.remoteKey}/${anchor.pageId.remoteKey} " +
            "offset=$scrollOffsetUnits/$contentHeightUnits manifests=${manifests.size} " +
            "velocity=$velocityUnitsPerSecond lanes=$activeFetchCount/$networkConcurrency " +
            "fetches=${activeFetchPageIds.joinToString { id ->
                "${id.episodeId.remoteKey}/${id.remoteKey}"
            }} decodes=$activeDecodeCount:${activeDecodePageIds.joinToString { id ->
                "${id.episodeId.remoteKey}/${id.remoteKey}"
            }} resident=${residentPageIds.joinToString { id ->
                "${id.episodeId.remoteKey}/${id.remoteKey}"
            }} " +
            "verified=${currentEpisodeProgress?.verifiedCount}/${currentEpisodeProgress?.pageCount} " +
            "appends=${episodeAppends.joinToString { append ->
                "${append.fromEpisodeId.remoteKey}->${append.targetEpisodeId?.remoteKey}:" +
                    "terminal=${append.terminal},boundary=${append.hasBoundary},retry=${append.retryReason}"
            }}"

    private fun verifyFirstContent(
        activity: ViewerActivity,
        startedAtMillis: Long,
    ) {
        val node = device.findObject(By.descStartsWith(FRAME_PREFIX))
        val timestamp = node?.contentDescription?.substringAfter(FRAME_PREFIX)?.toLongOrNull()
        if (externalDisplay) {
            artifacts.directory.resolve("first-content.txt").writeText(
                "externalDisplayVerificationRequired=true\nstartedAtMillis=$startedAtMillis\n" +
                    "firstReadableDiagnosticNanos=${presentationEvidence.firstOrNull { it.readableActualContent }?.presentedNanos}\n",
            )
            return
        }
        if (timestamp == null) {
            violations += "No real image frame was presented"
            return
        }
        val elapsed = timestamp - startedAtMillis
        val startup = startupTiming(activity)
        recordTiming("first-content-ms", elapsed.toDouble(), FIRST_CONTENT_LIMIT_MILLIS.toDouble(), true)
        artifacts.directory.resolve("first-content.txt").writeText(
            "totalMillis=$elapsed\nlimitMillis=$FIRST_CONTENT_LIMIT_MILLIS\nstartup=$startup\n",
        )
    }

    private fun startupTiming(
        activity: ViewerActivity,
    ): ViewerStartupTiming? = onMain { activity.viewerStartupTimingSnapshot() }

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
            motionFrames.sortedBy(MotionEvidence::timestampNanos).map(MotionEvidence::appliedAtNanos).toLongArray(),
            gestureWindows.map { window ->
                requireNotNull(requestedDirections.lastOrNull { it.first <= window.first }) {
                    "Observed gesture has no injected start timestamp"
                }.first
            }.toLongArray(),
        )
        artifacts.directory.resolve("frame-stats-summary.txt").writeText(
            "render=${stats.render}\n" +
                "gfx=${stats.gfx}\n" +
                "motion=${stats.motion}\n" +
                "surface=${stats.surface}\n",
        )
        recordTiming("native-render-p95-ms", stats.render.p95Millis ?: Double.NaN, 16.0)
        recordTiming("native-render-gap-ms", stats.render.maximumMillis ?: Double.NaN, 100.0)
        if (stats.gfx.sampleCount > 0) recordTiming("window-frame-gap-ms", stats.gfx.maximumMillis ?: Double.NaN, 100.0)
        recordTiming("motion-gap-ms", maxOf(stats.motion.maximumNanos ?: 0L,
            stats.motion.maximumResponseNanos ?: 0L, stats.motion.maximumTailNanos ?: 0L) / 1_000_000.0, 100.0)
        if (stats.motion.coveredInteractionWindowCount < stats.motion.interactionWindowCount) {
            violations += "Motion frames missed a real gesture window"
        }
        recordTiming("motion-missed-ratio", stats.motion.missedFrameRatio, MAXIMUM_MISSED_FRAME_RATIO)
        if (externalDisplay) return // Host verifies actual surface timestamps, gaps and display latency.
        val surface = stats.surface
        if (surface == null || surface.freezeCount != 0 || surface.responseFreezeCount != 0 || surface.tailFreezeCount != 0) {
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

    private fun recordTiming(gate: String, value: Double, limit: Double, inclusive: Boolean = false) {
        val observation = TimingObservation(gate, value, limit, inclusive, sampleKey)
        val decision = timingPolicy.evaluate(observation)
        val pending = !decision.passed && (diagnosticMode ||
            (externalDisplay && timingPolicy.canAwaitIndependentAttribution(observation)))
        timingObservations.put(JSONObject().put("gate", gate).put("value", value.takeIf(Double::isFinite))
            .put("limit", limit).put("inclusive", inclusive).put("sampleKey", sampleKey)
            .put("withinGoal", decision.passed).put("requiresIndependentAttribution", pending)
            .put("diagnosticOnly", diagnosticMode))
        if (!decision.passed && !pending) violations += decision.reason
    }

    private fun telemetry(activity: ViewerActivity): ViewerTelemetrySnapshot? =
        onMain { activity.viewerTelemetrySnapshot() }

    private fun harvestActivityEvidence(activity: ViewerActivity) {
        val evidence = activitySnapshot(activity)
        if (evidence.presentationDropped || evidence.motionDropped) {
            violations += "Evidence recorder overran before an incremental harvest"
        }
        val decoded = NativePresentationEvidencePacking.decode(evidence.presentationEvidence)
        presentationEvidence += decoded
        // The dedicated Surface renderer reports after its buffer has been queued. Unlike the old
        // View commit callback this timestamp is independent of main-thread callback jitter and
        // retains real buffer backpressure, so it is the authoritative presentation cadence.
        presentations += decoded.mapNotNull { sample -> sample.presentedNanos.takeIf { it > 0L } }
        decoded.filter { it.submittedAtNanos > 0L && it.renderLatencyNanos >= 0L }.forEach {
            renderEvidence += RenderEvidence(it.submittedAtNanos + it.renderLatencyNanos, it.renderLatencyNanos)
        }
        var motionIndex = 0
        while (motionIndex + 1 < evidence.motionFrames.size) {
            val sequence = evidence.motionFrames[motionIndex]
            val timestamp = evidence.motionFrames[motionIndex + 1]
            val appliedAt = evidence.motionApplications[motionIndex / 2]
            if (sequence > 0L && timestamp > 0L) {
                motionFrames += MotionEvidence(sequence, timestamp, appliedAt)
            }
            motionIndex += 2
        }
        evidence.gestureWindows.filter { it.first > 0L && it.last >= it.first }.forEach { window ->
            if (gestureWindows.add(window)) directedGestures += PresentationGestureWindow(window,
                requestedDirections.lastOrNull { it.first <= window.first }?.second ?: TelemetryDirection.FORWARD)
        }
        harvestRegions(activity)
        collectionEndAtNanos = System.nanoTime()
        refreshPeriodNanos = evidence.refreshPeriodNanos
    }

    private fun harvestRegions(activity: ViewerActivity) {
        val batch = onMain { activity.presentedRegionsSince(regionCursor) }
        regionCursor = batch.nextSequence
        if (batch.dropped) violations += "Displayed image region recorder overran before harvest"
        if (batch.regions.isEmpty()) return
        val lines = batch.regions.joinToString("\n", postfix = "\n") { region ->
            val actual = region.imageIdentityVerified && region.presentedNanos > 0L &&
                region.timestampKind == PresentationTimestampKind.DISPLAY_PRESENT
            displayedRows.record(region.pageId, region.sourceTopRow, region.sourceBottomRowExclusive, region.sourceHeightRows, actual)
            val mayHaveDisplayed = region.bufferFrameId > 0L && region.timestampKind !in NON_DISPLAY_TERMINALS
            navigationRows.record(region.pageId, region.sourceTopRow, region.sourceBottomRowExclusive, region.sourceHeightRows,
                actual || (externalDisplay && region.imageIdentityVerified && mayHaveDisplayed))
            pageJson(region.pageId).put("rendererIdentity", region.rendererIdentity).put("token", region.token)
                .put("generation", region.generation).put("bufferFrameId", region.bufferFrameId)
                .put("submittedAtNanos", region.submittedAtNanos).put("renderLatencyNanos", region.renderLatencyNanos)
                .put("screenTopPx", region.screenTopPx).put("screenBottomPx", region.screenBottomPx)
                .put("viewportHeightPx", region.viewportHeightPx).put("viewportWidthPx", region.viewportWidthPx)
                .put("geometryRevision", region.geometryRevision).put("userInputRevision", region.userInputRevision)
                .put("sourceTopRow", region.sourceTopRow).put("sourceBottomRowExclusive", region.sourceBottomRowExclusive)
                .put("sourceHeightRows", region.sourceHeightRows).put("presentedNanos", region.presentedNanos)
                .put("timestampKind", region.timestampKind.name).put("imageIdentityVerified", region.imageIdentityVerified).toString()
        }
        artifacts.directory.resolve("presented-regions.jsonl").appendText(lines)
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

    private fun activitySnapshot(activity: ViewerActivity): ActivityEvidence = onMain {
        val presentations = activity.presentationEvidenceSince(presentationCursor)
        val motion = activity.motionFramesSince(motionCursor)
        presentationCursor = presentations.nextSequence
        motionCursor = motion.nextSequence
        ActivityEvidence(
            presentationEvidence = presentations.packed,
            motionFrames = motion.packed,
            motionApplications = motion.applicationTimestamps,
            gestureWindows = activity.gestureWindowsSnapshot(),
            refreshPeriodNanos = activity.presentationRefreshPeriodNanos(),
            presentationDropped = presentations.dropped,
            motionDropped = motion.dropped,
        )
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun recordFailure(failure: Throwable) {
        caughtFailures += failure
        violations += failure.message ?: failure.javaClass.simpleName
    }

    private fun surfaceBounds(): Rect {
        val node = device.findObject(By.desc(SURFACE_DESCRIPTION))
            ?: device.findObject(By.descStartsWith(FRAME_PREFIX))
            ?: error("Viewer surface accessibility node disappeared")
        return Rect(node.visibleBounds)
    }

    private fun checkHealth(activity: ViewerActivity) {
        onMain { activity.viewerFailureSnapshot() }?.let { throw it }
        if (device.hasObject(By.pkg("android").res(ANDROID_FAILURE_RESOURCE))) {
            violations += "System reported an ANR or crash"
        }
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
        val motionApplications: LongArray,
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
        val appliedAtNanos: Long,
    )

    private companion object {
        const val SURFACE_DESCRIPTION = "viewer-surface"
        const val FRAME_PREFIX = "viewer-frame-presented:"
        const val SURFACE_TIMEOUT_MILLIS = 5_000L
        const val FIRST_CONTENT_LIMIT_MILLIS = 4_000L
        const val RUN_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        const val MAX_GESTURES = 4_000
        const val SWIPE_STEPS = 6
        const val PROGRESS_INTERVAL_GESTURES = 20
        const val FRAME_BUDGET_NANOS = 16_000_000L
        const val FREEZE_NANOS = 100_000_000L
        const val MAXIMUM_MISSED_FRAME_RATIO = 0.01
        val NON_DISPLAY_TERMINALS = setOf(PresentationTimestampKind.CANCELLED,
            PresentationTimestampKind.DROPPED, PresentationTimestampKind.CONTEXT_LOST)
        val ANDROID_FAILURE_RESOURCE = java.util.regex.Pattern.compile("android:id/aerr_(wait|close)")
    }
}

/** The observed transition trace is authoritative; manifests cannot stand in for missing transitions. */
internal object CorpusEpisodeOrderContract {
    fun violations(
        observed: List<EpisodeId>,
        expected: List<EpisodeId>?,
        requiredEpisodes: Int,
    ): List<String> = buildList {
        if (observed.size != requiredEpisodes) {
            add("Observed ${observed.size}/$requiredEpisodes consecutive episode boundaries")
        }
        if (expected != null && observed != expected) {
            add("Observed episode order differs from independently discovered chain")
        }
    }
}
