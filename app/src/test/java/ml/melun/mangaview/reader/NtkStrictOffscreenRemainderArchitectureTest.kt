package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictOffscreenRemainderArchitectureTest {
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()
    private val connectedRepro = File(
        "src/androidTest/java/ml/melun/mangaview/mangaview/" +
            "NtkOnePiecePreviousScrollReproTest.java",
    ).readText()

    @Test
    fun recoverableStrictOffscreenOwnerParksBeforeTheLegacyTimerBranch() {
        val append = function("private fun appendRemainingAdjacentRunwayRefs(", session)
        val strictPark = append.indexOf("parkStrictOwnedOffscreenAdjacentRemainder(")
        val legacyDefer = append.indexOf("shouldDeferRemainingAdjacentRunwayForActiveInput(")
        assertTrue(strictPark >= 0)
        assertTrue(strictPark < legacyDefer)

        val park = function("private fun parkStrictOwnedOffscreenAdjacentRemainder(", session)
        val register = park.indexOf("parkedAdjacentRemainderAppends.put(path, parked)")
        val cancelRetry = park.indexOf("scheduledRemainingAdjacentRunwayRetries.cancelPath(path)")
        val postRegistrationStateCheck = park.indexOf("if (isStructurePublishPending())")
        val viewport = park.indexOf("val viewportInsideEpisode = isViewportInsideEpisode(target)")
        val snapshot = park.indexOf("strictAdjacentPreparedRunwaySnapshot(")
        assertTrue(viewport >= 0)
        assertTrue(snapshot > viewport)
        assertTrue(register >= 0)
        assertTrue(cancelRetry > register)
        assertTrue(postRegistrationStateCheck > register)
        assertTrue(park.contains("NTK_STRICT_OFFSCREEN_PREPARED_RUNWAY_PAGES"))
        assertFalse(park.contains("requiredInitialAdjacentRunwayPages(target)"))
        assertTrue(park.contains("val preparedStillCurrent = strictAdjacentPreparedRunwaySnapshot("))
        assertTrue(park.contains("main.removeCallbacks(scheduled.token)"))
        assertFalse(park.contains("scheduleRemainingAdjacentRunwayAppend("))
        assertFalse(park.contains("postDelayed"))
    }

    @Test
    fun preparedProofReopensAnExactlyBoundBodyAndNeverUsesGenericCacheMetadata() {
        val snapshot = function("private fun strictAdjacentPreparedRunwaySnapshot(", session)
        val reopen = function("private fun canReopenStrictAdjacentBody(", session)
        val testSeam = function("fun hasPreparedEpisodeRunwayForTest(", session)

        assertTrue(snapshot.contains("claim.episode.value == seal.revision"))
        assertTrue(snapshot.contains("claim.manifestDigest == seal.digestSha256"))
        assertTrue(snapshot.contains("runwaySources.zipWithNext()"))
        assertTrue(snapshot.contains("runwaySources.distinct() == expectedSources"))
        assertTrue(snapshot.contains("page.sourceIndex"))
        assertTrue(snapshot.contains("page.canonicalAsset != canonical"))
        assertTrue(snapshot.contains("canReopenStrictAdjacentBody("))
        assertTrue(snapshot.contains("if (!descriptorRecoverable) return null"))
        assertFalse(snapshot.contains("val descriptor = if (live)"))
        assertTrue(reopen.contains("descriptor.openLease()"))
        assertTrue(reopen.contains("lease.sourceKey == descriptor.sourceKey"))
        assertTrue(reopen.contains("lease.metadata == descriptor.metadata"))
        assertTrue(reopen.contains("lease.proof == descriptor.proof"))
        assertTrue(reopen.contains("lease.file.isFile"))
        assertTrue(reopen.contains("lease.file.length() == lease.proof.encodedLength"))
        assertFalse(snapshot.contains("ReaderImageCache.cachedFile"))
        assertFalse(reopen.contains("ReaderImageCache.cachedFile"))
        assertTrue(testSeam.contains("NTK_STRICT_OFFSCREEN_PREPARED_RUNWAY_PAGES"))
    }

    @Test
    fun everyLifecycleEdgeConvergesOnTheAtomicOneOwnerWake() {
        val bodyReady = function("private fun ensureAdjacentStrictSourceClaim(", session)
        val viewport = function("private fun requestWindow(", session)
        val nearViewport = function("private fun redriveParkedAdjacentRemaindersNearViewport(", session)
        val stable = function("private fun finishStructurePublish()", session)
        val resume = function("fun onHostResumed()", session)
        val wake = function("private fun wakeStrictRemainingAdjacentAppend(", session)
        val latch = classBlock("internal class NtkPathEventWakeLatch", session)

        assertTrue(bodyReady.contains("wakeStrictRemainingAdjacentAppend(path)"))
        assertTrue(viewport.contains("resumeParkedAdjacentRemainder"))
        assertTrue(viewport.contains("redriveParkedAdjacentRemaindersNearViewport"))
        assertTrue(nearViewport.contains("wakeStrictRemainingAdjacentAppend(path)"))
        assertTrue(stable.contains("redriveParkedAdjacentRemaindersAfterEvent()"))
        assertTrue(resume.contains("redriveParkedAdjacentRemaindersAfterEvent()"))
        assertTrue(activity.contains("session?.onHostResumed()"))
        assertTrue(wake.contains("strictRemainingAdjacentWakeLatch.tryAcquire(path)"))
        assertTrue(wake.contains("strictRemainingAdjacentWakeLatch.release(path)"))
        assertTrue(latch.contains("if (inFlight.add(path))"))
        assertTrue(latch.contains("pending.add(path)"))
        assertTrue(latch.contains("inFlight.remove(path)"))
        assertTrue(latch.contains("pending.remove(path)"))
        assertFalse(session.contains("strictRemainingAdjacentWakeInFlight"))
        assertFalse(session.contains("strictRemainingAdjacentWakePending"))
    }

    @Test
    fun delayedRetryReplacementIsIdentityOwnedAndAbaSafe() {
        val schedule = function("private fun scheduleRemainingAdjacentRunwayAppend(", session)
        val registry = classBlock("internal class NtkPathScheduledRetryRegistry", session)
        val register = schedule.indexOf("scheduledRemainingAdjacentRunwayRetries.register(")
        val post = schedule.indexOf("main.postDelayed(")

        assertTrue(schedule.contains("scheduledRemainingAdjacentRunwayRetries.claim(retryOwner)"))
        assertTrue(register >= 0)
        assertTrue(post > register)
        assertTrue(schedule.contains("main.removeCallbacks(replaced.token)"))
        assertTrue(schedule.contains("scheduledRemainingAdjacentRunwayRetries.cancel(retryOwner)"))
        assertTrue(registry.contains("if (current?.retryKey != owner.retryKey)"))
        assertTrue(registry.contains("retryKeys.remove(replaced.retryKey)"))
        assertTrue(registry.contains("if (current !== owner)"))
        assertFalse(session.contains("scheduledRemainingAdjacentRunwayRetryKeys"))
        assertFalse(session.contains("ScheduledAdjacentRemainderRetry"))
    }

    @Test
    fun splitOracleSeparatesPreparedRunwayFromPostBoundaryLivePixels() {
        val split = function(
            "public void onePieceSplitScreenKeepsThePreparedNextEpisodeScrollable()",
            connectedRepro,
        )
        val prepared = split.lastIndexOf("testHasPreparedEpisodeRunway(following, 4)")
        val transition = split.indexOf("String landscapeTransition =")
        val physicalP0 = split.indexOf(
            "testHasPhysicallyPresentedEpisodeSource(following, 0)",
            transition,
        )
        val postBoundaryLive = split.indexOf(
            "long followingLiveDeadline =",
            physicalP0,
        )
        val cleanFrame = split.indexOf("UiObject2 followingStrip =", postBoundaryLive)
        val finalLiveProof = split.lastIndexOf("testHasReadyEpisodeRunway(following, 4)")

        assertTrue(prepared >= 0)
        assertTrue(transition > prepared)
        assertTrue(physicalP0 > transition)
        assertTrue(postBoundaryLive > physicalP0)
        assertTrue(cleanFrame > postBoundaryLive)
        assertTrue(finalLiveProof > cleanFrame)
        assertTrue(split.contains("followingCoverage.getMissingPx()"))
        assertTrue(split.contains("followingCoverage.getPlaceholderPx()"))
        assertTrue(split.contains("followingCoverage.getVisibleCards()"))
        assertTrue(activity.contains("fun testHasPhysicallyPresentedEpisodeSource("))
    }

    @Test
    fun physicalP0TestProofIsOneBoundedScalarAndAllocatesOnlyOnIdentityChange() {
        val committed = function("private fun handleStrictRollingCompletedDraw(", activity)
        val wrapper = function("fun testHasPhysicallyPresentedEpisodeSource(", activity)

        assertTrue(
            activity.contains(
                "AtomicReference<StrictPhysicalP0ProofForTest?>(null)",
            ),
        )
        assertTrue(activity.contains("identity.sourcePageIndex == 0"))
        assertTrue(activity.contains("physicallyPresentedStrictP0ProofForTest.get()"))
        assertTrue(activity.contains("presentedUptimeNanos > previous.presentedUptimeNanos"))
        assertTrue(activity.contains("previous.normalizedEpisodePath != p0Path"))
        assertTrue(activity.contains("StrictPhysicalP0ProofForTest("))
        assertTrue(wrapper.contains("sourcePageIndex != 0"))
        assertTrue(wrapper.contains("physicallyPresentedStrictP0ProofForTest.get()"))
        assertTrue(wrapper.contains("proof.telemetryGeneration == strictTelemetryGeneration"))
        assertFalse(activity.contains("physicallyPresentedStrictSourcesForTest"))
        assertFalse(committed.contains("newKeySet"))
        assertFalse(wrapper.contains("newKeySet"))
    }

    private fun function(signature: String, source: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        return balancedBlock(start, source)
    }

    private fun classBlock(signature: String, source: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing class: $signature" }
        return balancedBlock(start, source)
    }

    private fun balancedBlock(start: Int, source: String): String {
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed source block")
    }
}
