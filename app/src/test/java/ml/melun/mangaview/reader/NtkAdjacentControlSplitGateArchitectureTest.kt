package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentControlSplitGateArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    private fun block(start: String, source: String): String {
        val from = source.indexOf(start)
        require(from >= 0) { "Missing $start" }
        val next = source.indexOf("\n    private fun ", from + start.length)
            .takeIf { it >= 0 } ?: source.length
        return source.substring(from, next)
    }

    @Test
    fun documentAndChallengeMayOverlapButApiWaitsForFullDrawableGate() {
        val flight = block("private fun runFlight(", coordinator)
        val control = flight.indexOf("awaitAdjacentControlReady(flight)")
        val document = flight.indexOf("client.fetchExactNtkEpisodeDocument(")
        val full = flight.indexOf("awaitAdjacentPredecessorComplete(flight)")
        val seed = flight.indexOf("streamedRequestSeed =")
        val api = flight.indexOf("client.executeUnsignedExactNtkWebtoonImageApi(")

        assertTrue(control in 0 until document)
        assertTrue(document in (control + 1) until full)
        assertTrue(full in (document + 1) until seed)
        assertTrue(seed in (full + 1) until api)
    }

    @Test
    fun earlyReleaseIsExactTargetScopedAndNeverOpensMobileOrManhwa() {
        val release = block(
            "fun releaseAdjacentControlAfterPredecessorBodiesResident(",
            coordinator,
        )
        assertTrue(release.contains("targetKey.startsWith(\"/webtoon/\")"))
        assertTrue(release.contains("NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime("))
        assertTrue(release.contains("flight.directWifiAdjacentBodyGate"))
        assertTrue(release.contains("flight.episodePath == targetKey"))
        assertTrue(coordinator.contains("data class AdjacentGateKey("))
        assertFalse(release.contains("/manhwa/"))
    }

    @Test
    fun currentBodiesMustAllBeResidentAndCompletionReconcilesTheExactTarget() {
        val bodyEdge = block(
            "private fun maybeStartForwardAdjacentControlAfterCurrentBodiesResident(",
            session,
        )
        val completion = block(
            "fun prepareForwardAdjacentAfterCurrentComplete(",
            session,
        )
        assertTrue(bodyEdge.contains("strictForwardSourceFloor <= 0"))
        assertTrue(bodyEdge.contains("hostGpuEmulatorRuntime"))
        assertTrue(bodyEdge.contains("!isDirectWifiStrictAdjacentTransportActive()"))
        assertTrue(bodyEdge.contains("strictExactBodyDescriptors::containsKey"))
        assertTrue(bodyEdge.contains("persistedForwardAdjacentControlCandidate("))
        assertTrue(bodyEdge.contains("controlOnly = true"))
        assertTrue(completion.contains("val exactTargetPath ="))
        assertTrue(completion.contains("releaseAdjacentBodiesAfterPredecessorComplete("))
        assertTrue(completion.contains("exactTargetPath,"))
    }

    @Test
    fun fullyDecodedEpisodeStartsExactRunwayBeforeNativeTextureCompletion() {
        val warm = block("private fun maybeWarmCompletedForwardEpisode(", session)
        val exact = warm.indexOf("startForwardAdjacentExactDiscoveryAtCompletion(")
        val release = warm.indexOf("releaseAdjacentBodiesAfterPredecessorComplete(")
        val preappend = warm.indexOf("maybeStartInitialTailAdjacentPreappend(")

        assertTrue(warm.contains("NtkCompletedForwardEpisodePolicy.isComplete("))
        assertTrue(exact >= 0)
        assertTrue(release > exact)
        assertTrue(preappend > release)
        assertTrue(warm.contains("exactTargetPath"))
    }

    @Test
    fun sessionGenerationSurvivesTheMainHandlerHop() {
        val listener = block("private fun activeReaderSessionListener(", activity)
        val post = block("private fun postAdjacentExactManifestForGeneration(", activity)
        assertTrue(listener.contains("postAdjacentExactManifestForGeneration("))
        assertTrue(listener.contains("generation,"))
        assertTrue(post.contains("generation != activeReaderSessionGeneration.get()"))
        assertTrue(post.contains("capturedSession.canPrepareForwardAdjacentNow("))
    }

    @Test
    fun reconciledTargetLinearizesLateFlightAdmissionAndBroadRelease() {
        val start = block("private fun startInternal(", coordinator)
        val release = block(
            "fun releaseAdjacentBodiesAfterPredecessorComplete(",
            coordinator,
        )
        val gateLock = "flightLifecycleLock(\"adjacent-gate:\$predecessorPath\")"
        assertTrue(start.contains(gateLock))
        assertTrue(start.contains("predecessorReconciled"))
        assertTrue(start.contains("ntk_strict_adjacent_stale_target_suppressed"))
        assertTrue(start.contains("Adjacent target was replaced before discovery worker admission"))

        val publish = release.indexOf(
            "completedAdjacentTargets[adjacentGateKey(key, expectedTarget)] = generation",
        )
        val staleSweep = release.indexOf("val staleMarkerTargets =")
        assertTrue(publish >= 0)
        assertTrue(staleSweep > publish)
        assertTrue(release.contains("flightLifecycleLock(\"adjacent-gate:\$key\")"))
        assertTrue(release.contains("if (reconciledTargets.isEmpty())"))
        assertTrue(release.contains("flight.episodePath in reconciledTargets"))
    }

    @Test
    fun zeroWaveAndHostEmulatorAdjacentMayUseActorOrderedSameTickProof() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
        ).readText()
        val activation = block("private fun activateExactPublicationActor(", source)
        assertTrue(activation.contains(
            "sameMillisecondSeededExactAllowed = initialWaveCount == 0 ||",
        ))
        assertTrue(activation.contains("(adjacentPrefetch && directWifiTransport &&"))
        assertTrue(activation.contains("!cellularResilientTransport && hostGpuEmulatorRuntime"))
        assertTrue(activation.contains("hostGpuEmulatorRuntime"))
    }
}
