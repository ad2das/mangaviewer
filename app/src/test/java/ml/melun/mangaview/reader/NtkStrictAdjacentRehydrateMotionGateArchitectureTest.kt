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
        assertTrue(body.contains("scheduleStrictAdjacentExactRehydrate(flight, visibleIntent)"))
        assertTrue(body.contains("NTK_STRICT_ADJACENT_REHYDRATE_MOTION_RECHECK_MS"))
        assertFalse(body.contains("flight.retryCount.incrementAndGet()"))
    }
}
