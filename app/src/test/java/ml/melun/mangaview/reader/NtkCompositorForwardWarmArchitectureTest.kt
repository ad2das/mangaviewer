package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkCompositorForwardWarmArchitectureTest {

    @Test
    fun passiveMotionCompletionRechecksAlreadyResidentCompositorSuccessor() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()

        val drain = body("private fun drainEnteredExactEpisodePixelCompletion(", source)
        val quietBranch = drain.substringAfter("if (quietMs > 0L) {")
            .substringBefore("postEnteredExactEpisodePixelCompletion(path, quietMs)")
        assertTrue(quietBranch.contains("requestLatestExactAdjacentCompositorForwardWarm()"))
    }
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val surface = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun cleanAdjacentFrameAdmitsOnlyItsResidentImmediateSuccessor() {
        val callback = body("fun onExactNtkAdjacentActualFramePresented(", session)
        val record = body("private fun recordExactAdjacentCompositorForwardWarm(", session)
        val request = body("private fun requestLatestExactAdjacentCompositorForwardWarm(", session)
        val worker = body("private fun runStrictAdjacentExactRehydrate(", session)
        val delivery = body("private fun prepareAdjacentRunwayDelivery(", session)
        val deliveryGate = body(
            "private fun isDeliveryInsideProtectedNumericBitmapWindow(",
            session,
        )

        assertTrue(callback.contains("recordExactAdjacentCompositorForwardWarm("))
        assertTrue(record.contains("if (direction < 0"))
        assertTrue(record.contains("val nextSourceIndex = lastVisibleSourceIndex + 1"))
        assertTrue(record.contains("candidate.sourceIndex == nextSourceIndex"))
        assertTrue(record.contains("strictAdjacentRehydrateIdentity(candidate) != null"))
        assertFalse(record.contains("startNtkEarlyViewerApiPrefetch"))
        assertFalse(record.contains("holdOrRecoverAdjacentStrictSource"))

        assertTrue(request.contains("strictAdjacentBodyDescriptor(proof.page) == null"))
        assertTrue(request.contains("strictAdjacentPublishedBody(proof.page) == null"))
        assertTrue(request.contains("compositorForwardWarmIntent = true"))
        assertFalse(request.contains("requestPage("))

        assertTrue(worker.contains("!compositorForwardWarmEligible"))
        assertTrue(worker.contains("compositorForwardWarmIntent = compositorForwardWarmEligible"))
        assertTrue(
            delivery.contains(
                "exactLaunchCompositorForwardWarmIntent = compositorForwardWarmIntent",
            ),
        )
        assertTrue(deliveryGate.contains("delivery.compositorForwardWarmIntent"))
        // runStrictAdjacentExactRehydrate captures latest-page eligibility before decode. The
        // immutable PageRef/identity checks remain in the delivery path, but a newer compositor
        // callback must not revoke that one-shot admission after native decode has begun.
        assertFalse(deliveryGate.contains("isLatestExactAdjacentCompositorForwardWarm("))
        val authoritativeInstall = body(
            "private fun hasIdentityBoundAdjacentAuthoritativeInstall(",
            session,
        )
        assertTrue(authoritativeInstall.contains("delivery.compositorForwardWarmIntent"))
        assertFalse(authoritativeInstall.contains("isLatestExactAdjacentCompositorForwardWarm("))
    }

    @Test
    fun cleanLaunchFrameWarmsOnlySourcePlusOneInsideTheExistingRetainedWindow() {
        val callback = body("fun onExactNtkPhysicalDrawPresented(", session)
        val proof = body(
            "private fun isLatestExactLaunchCompositorForwardWarm(",
            session,
        )
        val demand = body("private fun finishStrictExactColdWindowDemand(", session)
        val worker = body("private fun requestStrictExactSourcePage(", session)

        assertTrue(callback.contains("val nextSource = exactLast.sourceIndex + 1"))
        assertTrue(callback.contains("candidate.sourceIndex == nextSource"))
        assertTrue(callback.contains("if (direction >= 0)"))
        assertTrue(proof.contains("page.sourceIndex != proof.visibleLastSourceIndex + 1"))
        assertTrue(demand.contains("compositorWarmIndex in retainedFirst..retainedLast"))
        assertTrue(demand.contains("admission.admitsSource(compositorWarm.page.sourceIndex)"))
        assertTrue(demand.contains("index == exactLaunchCompositorForwardWarmPage"))
        assertFalse(demand.contains("applyStrictExactSourceDemand(compositorWarm"))
        assertTrue(worker.contains("!exactCompositorForwardWarm"))
        assertTrue(worker.contains("exactLaunchCompositorForwardWarmIntent ="))
    }

    @Test
    fun repeatedViewportRepairDoesNotBuildOrWriteAFullTraceEveryFrame() {
        val repair = body("private fun repairUnsafeDrawableViewportLocked(", surface)

        assertTrue(repair.contains("Log.isLoggable(TAG, Log.DEBUG)"))
        assertTrue(repair.contains("DRAWABLE_VIEWPORT_REPAIR_LOG_INTERVAL_MS"))
        assertTrue(repair.contains("if (shouldLogRepair) StringBuilder() else null"))
        assertTrue(repair.contains("if (shouldLogRepair) {"))
    }

    private fun body(signature: String, source: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
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
        error("Unclosed function: $signature")
    }
}
