package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkStrictAdjacentRehydrateMotionGateArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun onlyTheIdentityBoundPhysicalFrontierMayDecodeDuringMotion() {
        val runStart = source.indexOf("private fun runStrictAdjacentExactRehydrate(")
        val retryStart = source.indexOf(
            "private fun postStrictAdjacentExactRehydrateMotionRetry(",
            runStart,
        )
        assertTrue(runStart >= 0)
        assertTrue(retryStart > runStart)
        val body = source.substring(runStart, retryStart)

        assertTrue(
            body.contains(
                "deferDecodeWhilePhysicalMotion = hostExactNativeSurfaceStorageEnabled &&",
            ),
        )
        assertTrue(body.contains("!flight.exactAdjacentPhysicalIntent"))
        assertTrue(body.contains("!flight.hostPressurePhysicalReentry"))
        val deferredAt = body.indexOf(
            "if (failure is NtkPhysicalMotionDecodeDeferredException)",
        )
        val genericFailureAt = body.indexOf("recordIfUnexpected(failure)")
        assertTrue(deferredAt >= 0)
        assertTrue(genericFailureAt > deferredAt)
        val deferredBranch = body.substring(deferredAt, genericFailureAt)
        assertTrue(
            deferredBranch.contains("postStrictAdjacentExactRehydrateMotionRetry("),
        )
        assertTrue(deferredBranch.contains("return"))
        assertFalse(deferredBranch.contains("failedPages.add"))
    }

    @Test
    fun motionRetryKeepsOneIdentityOwnerAndDoesNotSpendRecoveryRetries() {
        val retryStart = source.indexOf(
            "private fun postStrictAdjacentExactRehydrateMotionRetry(",
        )
        val retryEnd = source.indexOf(
            "private fun handleStrictAdjacentRehydrateBodyUnavailable(",
            retryStart,
        )
        assertTrue(retryStart >= 0)
        assertTrue(retryEnd > retryStart)
        val body = source.substring(retryStart, retryEnd)

        assertTrue(body.contains("strictAdjacentRehydrateFlights[flight.identity] === flight"))
        assertTrue(body.contains("flight.ownerScheduledOrRunning.set(false)"))
        assertTrue(body.contains("flight.motionDeferred.set(true)"))
        assertTrue(body.contains("flight.parked.set(true)"))
        assertTrue(body.contains("scheduleStrictAdjacentMotionDeferredWake()"))
        assertTrue(body.contains("NTK_STRICT_ADJACENT_REHYDRATE_IDLE_WAKE_MS"))
        assertTrue(body.contains("flight.motionDeferred.compareAndSet(true, false)"))
        assertTrue(body.contains("scheduleStrictAdjacentExactRehydrate(flight, flight.visibleIntent)"))
        assertFalse(body.contains("flight.retryCount.incrementAndGet()"))
    }

    @Test
    fun physicalStrictOwnerRoutesBeforeGenericGeneratedPagePolicies() {
        val requestStart = source.indexOf("private fun requestPage(")
        val requestEnd = source.indexOf("private fun prefetchBusyPage(", requestStart)
        assertTrue(requestStart >= 0)
        assertTrue(requestEnd > requestStart)
        val body = source.substring(requestStart, requestEnd)

        val strictRoute = body.indexOf("routeStrictAdjacentExactRehydrate(index, page, visibleIntent)")
        val preparedColdHold = body.indexOf("shouldKeepPreparedRunwayDecodeColdUntilInput(index, page)")
        val generatedFallback = body.indexOf("shouldSuppressTrustedManifestGeneratedFallback(index, page")
        assertTrue(strictRoute >= 0)
        assertTrue(preparedColdHold > strictRoute)
        assertTrue(generatedFallback > strictRoute)
    }

    @Test
    fun everyScheduledOwnerExitClearsOnlyItsOwnRunningGeneration() {
        val scheduleStart = source.indexOf(
            "private fun scheduleStrictAdjacentExactRehydrate(",
        )
        val scheduleEnd = source.indexOf(
            "private fun runStrictAdjacentExactRehydrate(",
            scheduleStart,
        )
        assertTrue(scheduleStart >= 0)
        assertTrue(scheduleEnd > scheduleStart)
        val body = source.substring(scheduleStart, scheduleEnd)

        assertTrue(source.contains("val ownerAttemptVersion: AtomicLong"))
        assertTrue(body.contains("flight.ownerAttemptVersion.incrementAndGet()"))
        assertTrue(body.contains("try {\n                    runStrictAdjacentExactRehydrate("))
        assertTrue(body.contains("} finally {"))
        assertTrue(body.contains("flight.ownerAttemptVersion.get() == ownerAttemptVersion"))
        assertTrue(body.contains("ownerScheduledOrRunning.compareAndSet(true, false)"))
    }

    @Test
    fun delayedRetryNeverClearsANewerOwnerAttempt() {
        val retryStart = source.indexOf(
            "private fun postStrictAdjacentExactRehydrateRetry(",
        )
        val retryEnd = source.indexOf(
            "private fun prepareAdjacentRunwayDrawableBatch(",
            retryStart,
        )
        assertTrue(retryStart >= 0)
        assertTrue(retryEnd > retryStart)
        val body = source.substring(retryStart, retryEnd)
        val ownerReleaseAt = body.indexOf("flight.ownerScheduledOrRunning.set(false)")
        val delayedPostAt = body.indexOf("main.postDelayed")

        assertTrue(ownerReleaseAt >= 0 && delayedPostAt > ownerReleaseAt)
        assertFalse(
            body.substring(delayedPostAt).contains(
                "flight.ownerScheduledOrRunning.set(false)",
            ),
        )
        assertTrue(
            body.substring(delayedPostAt).contains(
                "scheduleStrictAdjacentExactRehydrate(flight, visibleIntent)",
            ),
        )
    }

    @Test
    fun postedExactDeliveryClaimSurvivesPrefixReindexByContentIdentity() {
        assertTrue(
            source.contains(
                "initialContinuousPostedWidthsByIdentity = ConcurrentHashMap<String, Int>()",
            ),
        )
        assertTrue(source.contains("private fun initialContinuousPostedIdentity(page: PageRef)"))
        assertTrue(source.contains("append(page.manifestDigest)"))
        assertTrue(source.contains("append(page.sourceIndex)"))
        assertTrue(source.contains("append(page.side)"))
        assertFalse(source.contains("shiftConcurrentMap(initialContinuousPostedWidths"))
        assertFalse(source.contains("shiftConcurrentMapAfterRemoval(initialContinuousPostedWidths"))
    }

}
