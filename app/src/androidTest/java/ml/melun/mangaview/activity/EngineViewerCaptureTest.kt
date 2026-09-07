package ml.melun.mangaview.activity

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.viewer.runtime.EngineReadbackPacket
import ml.melun.mangaview.viewer.runtime.EngineCapturedFrame
import ml.melun.mangaview.viewer.runtime.EngineFrameObservation
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.viewer.QualificationMemory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineViewerCaptureTest {
    @Test fun immediateGestureCaptureUsesTheNormalViewersOwnSubmittedFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val arguments = InstrumentationRegistry.getArguments()
        val episode = EpisodeId(SeriesId(SourceId(arguments.getString("captureSource") ?: "wfwf"),
            arguments.getString("captureSeries") ?: "comic:10001"), arguments.getString("captureEpisode") ?: "1")
        val kind = SeriesKind.valueOf(arguments.getString("captureKind") ?: "COMIC")
        val output = File(context.getExternalFilesDir(null), "engine-capture-${System.currentTimeMillis()}").apply { mkdirs() }
        val appGraph = (context.applicationContext as ViewerApplication).graph
        val graph = appGraph.engine
        val documents = EngineCapturedEpisodeDocuments()
        val exchanges = EngineCapturedHttpExchanges()
        val ntkAuthorizations = EngineCapturedNtkAuthorizations()
        val memory = if (arguments.getString("captureMemory") == "true")
            QualificationMemory(instrumentation, File(output, "memory").apply { check(mkdir()) }) else null
        var viewer: ViewerActivity? = null
        var inputCursor = 0L
        var frameCursor = 0L
        fun exportFrames() {
            val batch = requireNotNull(viewer).engineFramesSince(frameCursor)
            File(output, "frames.jsonl").appendText(batch.observations.joinToString("") { frameRecord(it).toString() + "\n" })
            frameCursor = batch.latestOrdinal
            assertEquals("Frame observation evidence was overwritten", 0L, batch.lostCount)
        }
        fun exportInputs() {
            val batch = requireNotNull(viewer).engineInputObservationsSince(inputCursor)
            File(output, "inputs.jsonl").appendText(batch.observations.joinToString(separator = "", transform = { value ->
                val receipt = value.receipt
                JSONObject().apply {
                    put("ordinal", value.ordinal); put("sessionId", value.sessionId); put("generation", value.generation)
                    put("inputRevision", value.inputRevision); put("geometryRevision", value.geometryRevision)
                    put("pendingInputCount", value.pendingInputCount); put("anchorIdentity", anchor(value.anchor))
                    put("sequence", receipt.sample.sequence); put("gestureId", receipt.sample.gestureId)
                    put("eventTimeNanos", receipt.sample.eventTimeNanos); put("deltaScreenUnits", receipt.sample.deltaScreenUnits)
                    put("acceptedAtNanos", receipt.acceptedAtNanos); put("resolvedAtNanos", receipt.resolvedAtNanos ?: JSONObject.NULL)
                    put("appliedScreenUnits", receipt.appliedScreenUnits); put("outcome", receipt.outcome.name)
                    put("receiptGeometryRevision", receipt.geometryRevision)
                    put("boundary", receipt.boundary?.let { boundary -> JSONObject().apply {
                        put("kind", boundary.boundary.name); put("pageIdentity", page(boundary.pageId))
                        put("geometryRevision", boundary.geometryRevision)
                    } } ?: JSONObject.NULL)
                }.toString() + "\n"
            }))
            inputCursor = batch.latestOrdinal
            assertEquals("Input observation evidence was overwritten", 0L, batch.lostCount)
        }
        suspend fun exportClosedViewer() {
            withTimeout(30_000) { requireNotNull(viewer).awaitEngineClosed() }
            instrumentation.runOnMainSync { }
            exportInputs()
            exportFrames()
            val frameClose = requireNotNull(requireNotNull(viewer).engineFrameCloseProof())
            File(output, "renderer-close.json").writeText(JSONObject().apply {
                put("rendererId", frameClose.rendererId); put("submittedFrameCount", frameClose.submittedFrameCount)
                put("deliveredObservationCount", frameClose.deliveredObservationCount); put("closedAtNanos", frameClose.closedAtNanos)
            }.toString(2))
            val inputClose = requireNotNull(requireNotNull(viewer).engineInputCloseProof())
            File(output, "input-close.json").writeText(JSONObject().apply {
                put("sessionId", inputClose.sessionId); put("generation", inputClose.generation)
                put("inputRevision", inputClose.inputRevision); put("receivedInputCount", inputClose.receivedInputCount)
                put("observationCount", inputClose.observationCount); put("closedAtNanos", inputClose.closedAtNanos)
            }.toString(2))
            val work = graph.coordinator.snapshot()
            val storage = graph.storageOwnership()
            val decodeWorkersTerminated = requireNotNull(viewer).engineDecodeWorkersTerminated()
            File(output, "ownership.json").writeText(JSONObject().apply {
                put("work", work.toString()); put("fileLeases", storage.fileLeases)
                put("queued", work.queued); put("active", work.active); put("retiring", work.retiring)
                put("subscribers", work.subscribers); put("retainedResults", work.retainedResults)
                put("preparedPages", storage.preparedPages); put("pendingPublications", storage.pendingPublications)
                put("decodeWorkersTerminated", decodeWorkersTerminated)
            }.toString(2))
            assertEquals(0, work.active + work.retiring + work.queued + work.subscribers + work.retainedResults)
            assertEquals(0, storage.fileLeases + storage.preparedPages + storage.pendingPublications)
            assertTrue("Viewer decode workers did not terminate", decodeWorkersTerminated)
        }
        check(graph.episodeEvidenceObserver == null)
        check(appGraph.networkEvidenceObserver == null)
        check(graph.ntkAuthorizationEvidenceObserver == null)
        graph.episodeEvidenceObserver = documents
        appGraph.networkEvidenceObserver = exchanges
        graph.ntkAuthorizationEvidenceObserver = ntkAuthorizations.observer
        var primaryFailure: Throwable? = null
        try {
        memory?.capture("before-catalog")
        withEngineCaptureViewer(instrumentation, output, episode, kind, arguments.getString("catalogUi") == "true",
            beforeViewerOpen = { memory?.capture("before-viewer") },
            afterViewerClosed = { activity ->
                if (memory != null) {
                    withTimeout(30_000) { activity.awaitEngineClosed() }
                    memory.capture("after-viewer")
                }
            }) { activity ->
            viewer = activity
            try {
            if (arguments.getString("traverseEpisode") == "true") {
                var number = 0
                val readback = arguments.getString("captureReadback") != "false"
                val directions = arguments.getString("captureGesturePlanBase64")?.let { encoded ->
                    val values = org.json.JSONArray(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                        .toString(Charsets.UTF_8))
                    require(values.length() in 1..512)
                    (0 until values.length()).map { index ->
                        check(values.get(index) is Boolean)
                        values.getBoolean(index)
                    }
                }
                val report = traverseCapturedEpisode(activity, device,
                    episode, documents,
                    { writeCapture(output, number++, it) }, { exportInputs(); exportFrames(); memory?.capture("active") },
                    { captureEngineStoppedScreen(instrumentation, activity, output, it) },
                    { gesture, forward -> injectEngineTraversalGesture(instrumentation, device, output, gesture, forward) },
                    readbackEnabled = readback, fixedGestureDirections = directions,
                    maximumDurationMillis = (arguments.getString("captureTraversalSeconds") ?: "90").toLong() * 1_000,
                    maximumCaptures = (arguments.getString("captureMaximumFrames") ?: "512").toLong())
                File(output, "summary.json").writeText(report.put("capturedFrames", number)
                    .put("fullViewportCapture", readback).put("physicalPresentationVerified", false).toString(2))
                activity.viewerStartupTimingSnapshot()?.let { startup ->
                    File(output, "startup-timing.json").writeText(JSONObject().apply {
                        put("clock", "System.nanoTime")
                        put("openStartedAtNanos", startup.openStartedAtNanos)
                        put("manifestReadyAtNanos", startup.manifestReadyAtNanos ?: JSONObject.NULL)
                        put("firstSourceSubmittedAtNanos", startup.firstActualSubmittedAtNanos ?: JSONObject.NULL)
                        put("firstSourcePresentedAtNanos", startup.firstActualPresentedAtNanos ?: JSONObject.NULL)
                        put("physicalPresentationVerified", false)
                    }.toString(2))
                }
            } else {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var index = 0
            var sourceRegionAfterInput: Boolean
            do {
                check(SystemClock.elapsedRealtime() < deadline) { "No captured content frame after input" }
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                val capture = async(Dispatchers.Default) { withTimeout(remaining) { activity.captureNextEngineViewportFrame() } }
                // No document/image readiness wait before the real input gesture.
                assertTrue(device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 4, 30))
                val result = capture.await()
                val packet = result.pixels
                writeCapture(output, index++, result)
                exportInputs()
                exportFrames()
                memory?.capture("active")
                assertEquals(EngineReadbackPacket.Status.OK, packet.status)
                assertFalse(packet.physicalPresentationVerified)
                assertEquals(0L, packet.top)
                assertEquals(result.scene.viewport.heightPx.toLong(), packet.bottom)
                assertEquals(packet.width * packet.bottom * 4, packet.rgbaByteCount)
                activity.viewerFailureSnapshot()?.let { throw AssertionError("Capture viewer failed", it) }
                sourceRegionAfterInput = result.identity.inputRevision > 0 && result.scene.placements.any {
                    it.bottomPx > 0 && it.topPx < result.scene.viewport.heightPx * result.scene.coordinateUnitsPerPixel
                }
            } while (!sourceRegionAfterInput)
            File(output, "summary.json").writeText(JSONObject().apply {
                put("capturedFrames", index); put("sourceRegionAfterInput", true)
                put("fullViewportCapture", true)
                put("physicalPresentationVerified", false); put("corpusCredit", 0)
            }.toString(2))
            }
            } finally { memory?.beginViewerClose() }
        }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var failure = primaryFailure
            if (viewer != null) try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { exportClosedViewer() }
            } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) failure = cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
            if (graph.episodeEvidenceObserver === documents) graph.episodeEvidenceObserver = null
            if (appGraph.networkEvidenceObserver === exchanges) appGraph.networkEvidenceObserver = null
            if (graph.ntkAuthorizationEvidenceObserver === ntkAuthorizations.observer) graph.ntkAuthorizationEvidenceObserver = null
            for (export in listOf({ documents.exportAndClear(output) }, { exchanges.exportAndClear(output) },
                { ntkAuthorizations.exportAndClear(output) })) {
                try { export() } catch (cleanup: Throwable) {
                    val primary = failure
                    if (primary == null) failure = cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
            if (memory != null) {
                for (action in listOf(
                    {
                        val violations = memory.finish()
                        File(output, "memory-policy.json").writeText(JSONObject().apply {
                            put("sampledPssPolicyPassed", violations.isEmpty())
                            put("violations", org.json.JSONArray(violations))
                            put("likeForLikeCacheStateVerified", false)
                            put("allNativeGlAllocationsVerified", false)
                            put("memoryQualified", false); put("corpusCredit", 0)
                        }.toString(2))
                        assertTrue("Owned-process PSS policy violations: $violations", violations.isEmpty())
                    },
                )) {
                    try { action() } catch (cleanup: Throwable) {
                        val primary = failure
                        if (primary == null) failure = cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
                    }
                }
            }
            if (primaryFailure == null) failure?.let { throw it }
        }
    }

    private fun writeCapture(output: File, index: Int, result: EngineCapturedFrame) {
        val packet = result.pixels
        val nativeBytes = packet.nativePacketBytes()
        File(output, "native-$index.packet.gz").outputStream().use { stream ->
            object : GZIPOutputStream(stream, 64 * 1024) {
                init { def.setLevel(Deflater.BEST_SPEED) }
            }.use { it.write(nativeBytes) }
        }
        val nativeSha256 = MessageDigest.getInstance("SHA-256").digest(nativeBytes).joinToString("") { "%02x".format(it) }
        File(output, "frame-$index.json").writeText(JSONObject().apply {
                put("nativePacketSha256", nativeSha256)
                put("rendererId", result.rendererId); put("frameIdentity", result.identity.toString())
                put("processId", android.os.Process.myPid()); put("processUid", android.os.Process.myUid())
                put("sessionId", packet.sessionId); put("rendererEpoch", packet.rendererEpoch)
                put("surfaceEpoch", packet.surfaceEpoch); put("token", packet.token); put("eglFrameId", packet.eglFrameId)
                put("inputRevision", result.identity.inputRevision); put("geometryRevision", result.identity.geometryRevision)
                put("width", packet.width); put("top", packet.top); put("bottom", packet.bottom)
                val raster = requireNotNull(result.rasterizationInfo)
                put("rasterizationInfo", JSONObject().put("subpixelBits", raster.subpixelBits)
                    .put("sampleBuffers", raster.sampleBuffers).put("samples", raster.samples))
                writeEngineSceneEvidence(this, result.scene)
                put("status", packet.status.name); put("rgbaBytes", packet.rgbaByteCount)
                put("issuedMonotonicNs", packet.captureIssuedMonotonicNs); put("readyMonotonicNs", packet.captureReadyMonotonicNs)
                put("swapCompletedMonotonicNs", packet.swapCompletedMonotonicNs)
                put("forcedScene", false); put("physicalPresentationVerified", false); put("corpusCredit", 0)
        }.toString(2))
    }

    private fun frameRecord(value: EngineFrameObservation) = JSONObject().apply {
        val frame = value.presentation
        val id = frame.identity
        put("ordinal", value.ordinal); put("rendererId", frame.rendererId)
        put("sessionId", id.sessionId); put("rendererEpoch", id.rendererEpoch); put("surfaceEpoch", id.surfaceEpoch)
        put("token", id.token); put("inputRevision", id.inputRevision); put("geometryRevision", id.geometryRevision)
        put("sceneGeneration", frame.scene.generation); put("eglFrameId", frame.eglFrameId)
        put("submittedAtNanos", frame.submittedAtNanos); put("renderSubmissionDurationNanos", frame.renderLatencyNanos)
        put("swapSucceeded", frame.swapSucceeded); put("timestampKind", frame.timestampKind.name); put("timestampNanos", frame.timestampNanos)
        put("viewportWidth", frame.scene.viewport.widthPx); put("viewportHeight", frame.scene.viewport.heightPx)
        put("completeViewportCoverage", frame.scene.completeCoverage); put("anchorIdentity", anchor(frame.scene.anchor))
        put("visiblePlacementCount", frame.scene.placements.count {
            it.bottomPx > 0 && it.topPx < frame.scene.viewport.heightPx * frame.scene.coordinateUnitsPerPixel
        })
        put("physicalPresentationVerified", false); put("corpusCredit", 0)
    }

    private fun page(id: PageId) = JSONObject().apply {
        put("sourceId", id.episodeId.seriesId.sourceId.value); put("seriesKey", id.episodeId.seriesId.remoteKey)
        put("episodeKey", id.episodeId.remoteKey); put("pageKey", id.remoteKey)
    }

    private fun anchor(value: SourceAnchor?): Any = value?.let { JSONObject().apply {
        put("pageIdentity", page(it.pageId)); put("sourceYQ32", it.sourceYQ32)
        put("viewportOffsetUnits", it.viewportOffsetUnits)
    } } ?: JSONObject.NULL
}
