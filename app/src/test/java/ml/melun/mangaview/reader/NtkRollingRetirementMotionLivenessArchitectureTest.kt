package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkRollingRetirementMotionLivenessArchitectureTest {
    private val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }
        .first { File(it, "app/src/main").isDirectory }
    private val surface = File(
        root,
        "app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()
    private val session = File(
        root,
        "app/src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val renderer = File(root, "app/src/main/cpp/ntk_rolling_surface_renderer.cpp").readText()

    @Test
    fun consumedRollingSlotsCanRetireBetweenPhysicalFramesWithoutBlockingTheProducer() {
        val drain = functionBody("private fun drainRollingAuthoritativeRecycles()", surface)
        val worker = functionBody("private fun executeRollingAuthoritativeRecycleProbe(", surface)
        val mask = functionBody("bool bitmapReferenceMask(", renderer)

        assertFalse(drain.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertFalse(worker.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(worker.contains("nativeDiscardQueuedFramesWithRetiredBitmaps("))
        assertTrue(worker.contains("nativeDiscardQueuedPrewarmBitmaps("))
        assertTrue(worker.contains("nativeBitmapReferenceMask("))
        assertTrue(mask.contains("std::try_to_lock"))
        assertTrue(mask.contains("if (!lock.owns_lock()) return false"))
    }

    @Test
    fun forcedBoundaryRevealPublishesTheScrollerIdleEdgeSynchronously() {
        val append = functionBody("fun appendPageCount(", surface)
        val prepend = functionBody("fun prependPageCount(", surface)
        val settle = functionBody(
            "private fun settleForcedStructuralMotionLocked()",
            surface,
        )

        assertTrue(append.contains("forcedMotionSettleRequest = settleForcedStructuralMotionLocked()"))
        assertTrue(append.contains("forcedMotionSettleRequest ?: windowRequestLocked(lastBusy)"))
        assertTrue(prepend.contains("val forcedMotionSettleRequest = settleForcedStructuralMotionLocked()"))
        assertTrue(prepend.contains("forcedMotionSettleRequest ?: windowRequestLocked(lastBusy)"))
        assertTrue(settle.contains("scrollerFinished = scroller.isFinished"))
        assertTrue(settle.contains("return setBusyLocked(stillMoving)"))
    }

    @Test
    fun queuedPhysicalRehydrateIntentCannotBecomeAnOffscreenLifetimePin() {
        val run = functionBody("private fun runStrictAdjacentExactRehydrate(", session)

        assertTrue(run.contains("flight.exactAdjacentPhysicalIntent.get()"))
        assertTrue(run.contains("!isStrictAdjacentPageInReportedPhysicalIntent(currentIndex, currentPage)"))
        assertTrue(run.contains("flight.exactAdjacentPhysicalIntent.compareAndSet(true, false)"))
        assertTrue(
            run.indexOf("flight.exactAdjacentPhysicalIntent.compareAndSet(true, false)") <
                run.indexOf("strictAdjacentRehydrateIdentity(currentPage) != flight.identity"),
        )
    }

    @Test
    fun offscreenMotionDeferredRehydratesWakeOnceFromTheIdleLifecycleEdge() {
        val defer = functionBody(
            "private fun postStrictAdjacentExactRehydrateMotionRetry(",
            session,
        )
        val wake = functionBody(
            "private fun wakeStrictAdjacentMotionDeferredFlights()",
            session,
        )

        assertTrue(defer.contains("flight.motionDeferred.set(true)"))
        assertTrue(defer.contains("flight.parked.set(true)"))
        assertTrue(defer.contains("scheduleStrictAdjacentMotionDeferredWake()"))
        assertFalse(defer.contains("main.postDelayed"))
        assertTrue(wake.contains("flight.motionDeferred.compareAndSet(true, false)"))
        assertTrue(wake.contains("scheduleStrictAdjacentExactRehydrate(flight, flight.visibleIntent)"))
        assertTrue(
            session.contains(
                "if (NtkReaderTransferPacer.isPhysicalMotionActive()) {\n" +
                    "            // The final idle WindowEvent",
            ),
        )
        assertTrue(session.contains("scheduleStrictAdjacentMotionDeferredWake()\n            return@Runnable"))
    }

    @Test
    fun structureRedriveCannotRestoreAStaleBusyLevelAfterSurfaceIdle() {
        val request = functionBody("fun requestWindowAsync(", session)
        val visibleRedrive = functionBody("fun requestVisibleLoadingWindowAsync(", session)
        val structureRedrive = functionBody("private fun performRetainedWindowRedrive()", session)

        assertTrue(request.contains("latestWindowIngressBusy.set(busy)"))
        assertTrue(
            request.indexOf("latestWindowIngressBusy.set(busy)") <
                request.indexOf("publishedWindowIngressGate.reserve("),
        )
        assertFalse(visibleRedrive.contains("latestWindowIngressBusy.set(busy)"))
        assertTrue(visibleRedrive.contains("val physicalBusy = latestWindowIngressBusy.get()"))
        assertTrue(visibleRedrive.contains("physicalBusy,\n            directionHint,"))
        assertTrue(visibleRedrive.contains("offerWindowAsync(first, last, anchor, physicalBusy, directionHint)"))
        assertTrue(structureRedrive.contains("offeredBusy = latestWindowIngressBusy.get()"))
        assertTrue(structureRedrive.contains("snapshot[2],\n                    offeredBusy,"))
    }

    private fun functionBody(signature: String, text: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = text.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
