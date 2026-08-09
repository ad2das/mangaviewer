package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the benchmark-only fast stop without changing the production reader contract. */
class BenchmarkAdjacentP0SignalArchitectureTest {
    private val surfaceSource = source(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt"
    )
    private val activitySource = source(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
    )
    private val benchmarkSignalSource = source(
        "src/benchmark/java/ml/melun/mangaview/benchmark/BenchmarkAdjacentCommitSignal.java"
    )
    private val nonBenchmarkSignalSource = source(
        "src/nonBenchmark/java/ml/melun/mangaview/benchmark/BenchmarkAdjacentCommitSignal.java"
    )
    private val macroSource = source(
        "../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/" +
            "NtkColdViewerMacrobenchmark.kt"
    )
    private val resumeSeedSource = source(
        "src/benchmark/java/ml/melun/mangaview/benchmark/BenchmarkResumeSeedReceiver.java"
    )

    @Test
    fun cleanImmutableP0CandidatePublishesBeforeMainListenerQueue() {
        val completedDispatch = block("completed?.let { proof ->", surfaceSource)
        val fastHook = completedDispatch.indexOf(
            "publishBenchmarkAdjacentP0CandidateIfEligible(proof)"
        )
        val mainDispatch = completedDispatch.indexOf("mainHandler.post")
        assertTrue(fastHook >= 0)
        assertTrue(mainDispatch > fastHook)

        val candidate = block(
            "private fun publishBenchmarkAdjacentP0CandidateIfEligible(",
            surfaceSource,
        )
        assertTrue(candidate.contains("if (!BenchmarkAdjacentCommitSignal.isEnabled()) return"))
        assertTrue(candidate.contains("proof.presentedUptimeNanos"))
        assertTrue(candidate.contains("coverage.physicalViewportPx <= 0"))
        assertTrue(candidate.contains("coverage.drawablePx < coverage.physicalViewportPx"))
        assertTrue(candidate.contains("coverage.placeholderPx != 0"))
        assertTrue(candidate.contains("coverage.visibleLoading != 0"))
        assertTrue(candidate.contains("coverage.visibleErrors != 0"))
        assertTrue(candidate.contains("proof.structureEpoch == traversalStructureEpoch"))
        assertTrue(candidate.contains("identities.map { it.displayPageIndex } != visible"))
        assertTrue(candidate.contains("ViewerTelemetry.activeGeneration()"))
        assertTrue(candidate.contains("filter { it.sourcePageIndex == 0 }"))
    }

    @Test
    fun releaseUsesNoOpWhileBenchmarkRetainsExactPerSourceFiltering() {
        val gradle = source("build.gradle")
        assertTrue(gradle.contains("release {\n            java.srcDir 'src/nonBenchmark/java'"))
        assertTrue(nonBenchmarkSignalSource.contains("public static boolean isEnabled()"))
        assertTrue(nonBenchmarkSignalSource.contains("return false;"))
        assertTrue(nonBenchmarkSignalSource.contains("public static void publish("))
        assertTrue(nonBenchmarkSignalSource.contains("public static void publishSemanticCommit("))
        assertTrue(nonBenchmarkSignalSource.contains("public static void publishRunwayReady("))
        assertTrue(nonBenchmarkSignalSource.contains("public static void publishPhysicalMotionIdle("))
        assertTrue(!nonBenchmarkSignalSource.contains("sendBroadcast"))

        assertTrue(benchmarkSignalSource.contains("nonce.matches(\"[0-9a-f]{32}\")"))
        assertTrue(benchmarkSignalSource.contains("sourceIndex >= REQUIRED_PHYSICAL_PAGES"))
        assertTrue(benchmarkSignalSource.contains("current.expectedEpisodePath.equals"))
        assertTrue(benchmarkSignalSource.contains("SENT_MASK.compareAndSet"))
        assertTrue(benchmarkSignalSource.contains("1 << sourceIndex"))
        assertTrue(benchmarkSignalSource.contains(".setPackage(MACRO_PACKAGE)"))
        assertTrue(benchmarkSignalSource.contains("PHASE_SEMANTIC_COMMIT"))
        assertTrue(benchmarkSignalSource.contains("PHASE_RUNWAY_READY"))
        assertTrue(benchmarkSignalSource.contains("RUNWAY_READY_SENT.compareAndSet"))
        assertTrue(benchmarkSignalSource.contains("EXTRA_ADJACENT_WORK_STARTED_AT_NANOS"))
        assertTrue(benchmarkSignalSource.contains("adjacentWorkStartedAtNanos > readyAtNanos"))
        assertTrue(benchmarkSignalSource.contains("SEMANTIC_SENT_MASK.compareAndSet"))
        assertTrue(benchmarkSignalSource.contains("PRESENTED_AT_NANOS[sourceIndex] != presentedAtNanos"))
        assertTrue(benchmarkSignalSource.contains("PHASE_PHYSICAL_MOTION_IDLE"))
        assertTrue(benchmarkSignalSource.contains("LAST_MOTION_IDLE_AT_NANOS.compareAndSet"))
    }

    @Test
    fun screenshotWaitsForAppOwnedPhysicalMotionIdleWithoutDroppingTheFinalFling() {
        val endTrace = block("private fun endPhysicalScrollTraceLocked()", surfaceSource)
        val idlePublish = block(
            "private val benchmarkPhysicalMotionIdleRunnable",
            surfaceSource,
        )
        val idleSchedule = block(
            "private fun scheduleBenchmarkPhysicalMotionIdleCheckLocked()",
            surfaceSource,
        )
        assertTrue(endTrace.contains("Trace.endAsyncSection(PHYSICAL_SCROLL_TRACE_NAME, cookie)"))
        assertTrue(endTrace.contains("scheduleBenchmarkPhysicalMotionIdleCheckLocked()"))
        assertTrue(!endTrace.contains("if (cookie == 0) return"))
        assertTrue(idleSchedule.contains("BENCHMARK_PHYSICAL_MOTION_IDLE_CONFIRM_MS"))
        assertTrue(idleSchedule.contains("mainHandler.removeCallbacks("))
        assertTrue(idlePublish.contains("physicalScrollTraceCookie != 0"))
        assertTrue(idlePublish.contains("!scroller.isFinished"))
        assertTrue(idlePublish.contains("desiredVersion > committedVersion"))
        assertTrue(idlePublish.contains("retryAfterPhysicalSettle"))
        assertTrue(idlePublish.contains("mainHandler.postDelayed("))
        assertTrue(idlePublish.contains("BenchmarkAdjacentCommitSignal.publishPhysicalMotionIdle("))
        assertTrue(
            surfaceSource.contains(
                "scheduleBenchmarkPhysicalMotionIdleCheckLocked()\n                    dispatch"
            )
        )

        val traversalStart = macroSource.indexOf(
            "adjacentLastActualDescription = adjacent.actualDescription"
        ).also { require(it >= 0) }
        val traversalEnd = macroSource.indexOf("capture(device, outputDirectory, \"bottom\")")
            .also { require(it > traversalStart) }
        val traversal = macroSource.substring(traversalStart, traversalEnd + 48)
        val idleWait = traversal.indexOf("awaitPhysicalMotionIdleAfter(")
        val capture = traversal.indexOf("capture(device, outputDirectory, \"bottom\")")
        assertTrue(idleWait >= 0)
        assertTrue(capture > idleWait)
        assertTrue(traversal.contains("inputMetrics.endElapsedNanos"))
    }

    @Test
    fun activitySemanticFallbackAndMacroCrossChecksRemainMandatory() {
        val activityCommit = block(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource,
        )
        val telemetry = activityCommit.indexOf("ViewerTelemetry.adjacentActualDrawCommitted(")
        val fallback = activityCommit.indexOf("BenchmarkAdjacentCommitSignal.publish(")
        assertTrue(telemetry >= 0)
        assertTrue(fallback > telemetry)
        assertTrue(activityCommit.contains("presentedUptimeNanos"))
        assertTrue(activityCommit.contains("benchmarkSemanticSourceIndexes"))
        assertTrue(
            activityCommit.contains(
                "NtkVisibleIdentityPolicy.traversalSourceIndexesForEpisode("
            )
        )
        assertTrue(activityCommit.contains("sourceIndex in 0 until 5"))
        assertTrue(activityCommit.contains("benchmarkSemanticSourceIndexes.forEach"))
        assertTrue(
            activityCommit.contains(
                "NtkVisibleIdentityPolicy.traversalSourceIndexesForEpisode("
            )
        )
        val actualState = activityCommit.indexOf(
            "ViewerTelemetry.actualImageDrawCommittedForEpisode("
        )
        val stableSemanticNodes = activityCommit.indexOf("publishStrictViewerEdge(", actualState)
        val semanticPhase = activityCommit.indexOf(
            "BenchmarkAdjacentCommitSignal.publishSemanticCommit("
        )
        assertTrue(actualState >= 0)
        assertTrue(stableSemanticNodes > actualState)
        assertTrue(semanticPhase > stableSemanticNodes)

        val telemetrySource = source(
            "src/main/java/ml/melun/mangaview/runtime/ViewerTelemetry.java"
        )
        assertTrue(telemetrySource.contains(";actualPresentedAtNanos="))
        assertTrue(telemetrySource.contains("session.latestActualPresentedAtNanos = evidenceAtNanos"))
        assertTrue(telemetrySource.contains("session.firstActualEpisodeId = physicalEpisodeId"))
        assertTrue(telemetrySource.contains("session.firstActualSourcePage = firstVisiblePage"))
        assertTrue(telemetrySource.contains(";firstActualEpisode="))
        assertTrue(telemetrySource.contains(";firstActualSourcePage="))
        assertTrue(telemetrySource.contains("BenchmarkAdjacentCommitSignal.publishRunwayReady("))
        assertTrue(telemetrySource.contains("session.adjacentWorkStartedAtNanos"))
        assertTrue(telemetrySource.contains("currentForwardBoundaryReachedAtNanos()"))
        assertTrue(
            benchmarkSignalSource.contains("EXTRA_FORWARD_BOUNDARY_REACHED_AT_NANOS")
        )
        assertTrue(
            macroSource.contains("semanticCommit.forwardBoundaryReachedAtNanos")
        )

        val semantic = block("fun requireSourceCheckpointForSemanticProof(", macroSource)
        assertTrue(semantic.contains("semanticViewerGeneration != current.viewerGeneration"))
        assertTrue(
            semantic.contains(
                "embeddedFirstAdjacentActualAtNanos == checkpoint.presentedAtNanos"
            )
        )
        assertTrue(semantic.contains("AdjacentSourceProgressPolicy.invalidReason("))
        assertTrue(semantic.contains("semanticCommitPublishedAtNanos"))
        assertTrue(semantic.contains("semanticActualPresentedAtNanos == current.presentedAtNanos"))
        assertTrue(macroSource.contains("requireCompleteCrossCheck("))
        assertTrue(macroSource.contains("throwIfSignalWithoutSemanticProof()"))
    }

    @Test
    fun receiverUsesDedicatedSchedulerAndAlwaysOwnsItsCleanup() {
        val register = block(
            "fun register(context: Context, uiAutomation: UiAutomation)",
            macroSource,
        )
        val close = block("fun close()", macroSource)
        assertTrue(register.contains("HandlerThread("))
        assertTrue(register.contains("\"ntk-p0-signal\""))
        assertTrue(register.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(register.contains("Handler(thread.looper)"))
        assertTrue(register.contains("setOnAccessibilityEventListener(accessibilityListener)"))
        assertTrue(register.contains("registerReceiver("))
        assertTrue(register.contains("scheduler"))
        assertTrue(register.contains("thread.quitSafely()"))
        assertTrue(close.contains("setOnAccessibilityEventListener(null)"))
        assertTrue(close.contains("unregisterReceiver(receiver)"))
        assertTrue(close.contains("receiverThread?.quitSafely()"))
        assertTrue(close.contains("receiverThread = null"))
    }

    @Test
    fun benchmarkResumeSeedUsesTheProductionDurableNextIdentity() {
        assertTrue(resumeSeedSource.contains("title.setResumeNtkNextEpisodeIdentity(next)"))
        assertTrue(resumeSeedSource.contains("preference.addRecent(title)"))
    }

    @Test
    fun adjacentEvidenceIsMaterializedBeforeInputInfrastructureCanInvalidateTheRun() {
        val traversal = macroSource.substring(
            macroSource.indexOf("adjacentLastActualDescription = adjacent.actualDescription")
                .also { require(it >= 0) },
            macroSource.indexOf("device.pressBack()", macroSource.indexOf(
                "adjacentLastActualDescription = adjacent.actualDescription"
            )).also { require(it >= 0) },
        )
        val cadenceAssertion = traversal.indexOf("requireReaderRateInput(")
        val accumulatorMaterialization = traversal.indexOf("materializeAdjacentEvidence()")
        assertTrue(accumulatorMaterialization >= 0)
        assertTrue(cadenceAssertion > accumulatorMaterialization)
        assertTrue(macroSource.contains("forwardEvidence.observeActualDescription(description)"))
        assertTrue(macroSource.contains("observeExactP0Ipc"))
        assertTrue(macroSource.contains("observeExactRunwayReadyIpc"))
        assertTrue(macroSource.contains("P0_SIGNAL_PHASE_RUNWAY_READY"))
        assertTrue(macroSource.contains("handleRunwayReadySignal(intent, receivedAt)"))
        assertTrue(macroSource.contains("adjacentSourceProgressPassed"))
    }

    @Test
    fun inputCadenceSeparatesCompletedUpIdleFromInfrastructureBlocking() {
        val producer = block("private fun runProducer()", macroSource)
        assertTrue(producer.contains("previousUpCallFinishedMs"))
        assertTrue(!producer.contains("previousUpCallStartedMs"))
        assertTrue(producer.contains("ContinuousInputCadencePolicy.interGestureIdleMs("))
        assertTrue(
            producer.indexOf("val result = sequence.injectAsync(") <
                producer.indexOf("previousUpCallFinishedMs = SystemClock.uptimeMillis()")
        )

        val cadence = block("private fun requireReaderRateInput(", macroSource)
        assertTrue(cadence.contains("ContinuousInputCadencePolicy.infrastructureInvalidReason("))
        assertTrue(cadence.contains("throw MeasurementInvalidException("))
        assertTrue(cadence.contains("maxScheduleLatenessMs"))
        assertTrue(cadence.contains("maxInjectionCallMs"))
        assertTrue(cadence.contains("maxInterGestureIdleMs"))
    }

    private fun source(path: String): String = File(path).readText()

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
