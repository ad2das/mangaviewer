package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderReverseResidencyArchitectureTest {
    private val surface = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun aReverseMoveSurvivesLatestOnlyWindowCoalescingAndOpensTheSourceFloor() {
        val drag = slice(
            surface,
            "private fun applyPhysicalDragPositionLocked(",
            "private fun applyDragOffsetLocked(",
        )
        val directionRecorded = drag.indexOf("activeInputDirection = direction")
        val reverseRecorded = drag.indexOf("pendingReverseWindowFirstPageHint =")
        val edgeReturn = drag.indexOf("if (isAtInputEdgeLocked(direction)) return false")
        assertTrue(directionRecorded >= 0)
        assertTrue(reverseRecorded > directionRecorded)
        assertTrue(edgeReturn > reverseRecorded)

        val input = slice(surface, "override fun onTouchEvent(", "override fun performClick(")
        val noMovement = input.indexOf("suppressEdgeNoMovementScrollStatsLocked(nowMs)")
        val reverseDispatch = input.indexOf("windowRequestLocked(true)", noMovement)
        assertTrue(noMovement >= 0)
        assertTrue(reverseDispatch > noMovement)

        val capture = slice(
            surface,
            "private fun windowRequestLocked(",
            "private fun dispatchWindowRequest(",
        )
        val captureIndex = capture.indexOf("pendingReverseWindowFirstPageHint =")
        val cadenceReturn = capture.indexOf("if (busy && lastRequestedBusy)")
        assertTrue(captureIndex >= 0)
        assertTrue(cadenceReturn > captureIndex)

        val dispatch = slice(
            surface,
            "private fun dispatchWindowRequest(",
            "private fun deliverWindowRequest(",
        )
        assertTrue(dispatch.contains("NtkReverseWindowEvidencePolicy.merge("))
        assertTrue(dispatch.contains("reverseFirstPageHint = evidence.reverseFirstPageHint"))

        val window = slice(
            activity,
            "override fun onWindowChanged(",
            "override fun onNearBoundary(",
        )
        val evidence = window.indexOf("val coalescedReverseFirstPage")
        val floor = window.indexOf("directWifiShortWebtoonForwardRequestStartPage(", evidence)
        val request = window.indexOf("activeSession?.requestWindowAsync(", floor)
        assertTrue(evidence >= 0)
        assertTrue(floor > evidence)
        assertTrue(request > floor)
    }

    @Test
    fun displayIndexChangingMutationsAdvanceTheStructureEpochBeforeScheduling() {
        val prepend = slice(surface, "fun prependPageCount(", "fun removePageRange(")
        val prependMutation = prepend.indexOf("pages.add(0,")
        val prependReset = prepend.indexOf("resetTraversalProofLocked(pages.size)")
        val prependSchedule = prepend.indexOf("scheduleFrameLocked()")
        assertTrue(prependMutation >= 0)
        assertTrue(prependReset > prependMutation)
        assertTrue(prependSchedule > prependReset)

        val remove = slice(surface, "fun removePageRange(", "fun setPageLoading(")
        val removeMutation = remove.indexOf("pages.subList(startIndex, endExclusive).clear()")
        val removeReset = remove.indexOf("resetTraversalProofLocked(pages.size)")
        val removeSchedule = remove.indexOf("scheduleFrameLocked()")
        assertTrue(removeMutation >= 0)
        assertTrue(removeReset > removeMutation)
        assertTrue(removeSchedule > removeReset)
    }

    @Test
    fun nativeMailboxDropsRetireCommitSlotsWithoutDependingOnTheMainQueue() {
        val dropped = slice(
            surface,
            "fun onNtkRollingFrameDropped(",
            "fun onNtkRollingRendererFatal(",
        )
        assertTrue(dropped.contains("recoverDirectSurfaceSubmission(epoch, token)"))
        assertTrue(!dropped.contains("mainHandler.post"))

        val recovery = slice(
            surface,
            "private fun recoverDirectSurfaceSubmission(",
            "private fun windowRequestLocked(",
        )
        assertTrue(recovery.contains("synchronized(stateLock)"))
        assertTrue(recovery.contains("pendingFrameCommits.remove(token)"))
        assertTrue(!recovery.contains("mainHandler.post {"))
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, startIndex = start.coerceAtLeast(0) + 1)
        check(start >= 0 && end > start) { "missing architecture markers: $startMarker -> $endMarker" }
        return source.substring(start, end)
    }
}
