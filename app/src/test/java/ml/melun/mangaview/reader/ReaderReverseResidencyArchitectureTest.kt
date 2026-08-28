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
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()
    private val rollingNative = File(
        "src/main/cpp/ntk_rolling_surface_renderer.cpp",
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
        val edgeCheck = drag.indexOf("if (isAtInputEdgeLocked(direction))")
        val edgeReturn = drag.indexOf("return false", edgeCheck)
        assertTrue(directionRecorded >= 0)
        assertTrue(reverseRecorded > directionRecorded)
        assertTrue(edgeCheck > reverseRecorded)
        assertTrue(edgeReturn > edgeCheck)

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
        val cadenceReturn = capture.indexOf("if (!forceDispatch && busy && lastRequestedBusy)")
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
        val evidence = window.indexOf("val exactReverseFirstPage")
        val exactFloor = window.indexOf("recordStrictExactPhysicalReverseFloor(", evidence)
        val restoreGate = window.indexOf("if (\n                activeInitialRestorePage >= 0", exactFloor)
        val floor = window.indexOf("directWifiRollingForwardRequestStartPage(", exactFloor)
        val request = window.indexOf("activeSession?.requestWindowAsync(", floor)
        assertTrue(evidence >= 0)
        assertTrue(exactFloor > evidence)
        assertTrue(restoreGate > exactFloor)
        assertTrue(floor > evidence)
        assertTrue(request > floor)

        val exactFloorLatch = slice(
            session,
            "fun recordStrictExactPhysicalReverseFloor(",
            "fun directWifiRollingForwardRequestStartPage(",
        )
        assertTrue(exactFloorLatch.contains("if (!strictExactColdRolling"))
        assertTrue(exactFloorLatch.contains("val pageIndex = publishedPageIndex.get()"))
        assertTrue(!exactFloorLatch.contains("synchronized(pagesLock)"))
        assertTrue(exactFloorLatch.contains("isStrictExactLaunchPage(first)"))
        assertTrue(exactFloorLatch.contains("first.sourceIndex"))
        assertTrue(exactFloorLatch.contains("strictActiveSourceFloor.compareAndSet"))
        assertTrue(exactFloorLatch.contains("if (floorLowered) requestRetainedWindowAfterStructureChange()"))
    }

    @Test
    fun displayIndexChangingMutationsAdvanceTheStructureEpochBeforeScheduling() {
        val prepend = slice(surface, "fun prependPageCount(", "fun removePageRange(")
        val prependMutation = prepend.indexOf("addPageLocked(0,")
        val prependReset = prepend.indexOf("resetTraversalProofLocked(pages.size)")
        val prependSchedule = prepend.indexOf("scheduleFrameLocked()")
        assertTrue(prependMutation >= 0)
        assertTrue(prependReset > prependMutation)
        assertTrue(prependSchedule > prependReset)

        val remove = slice(surface, "fun removePageRange(", "fun setPageLoading(")
        val removeMutation = remove.indexOf("removePageRangeLocked(startIndex, endExclusive)")
        val removeReset = remove.indexOf("resetTraversalProofLocked(pages.size)")
        val removeSchedule = remove.indexOf("scheduleFrameLocked()")
        assertTrue(removeMutation >= 0)
        assertTrue(removeReset > removeMutation)
        assertTrue(removeSchedule > removeReset)
    }

    @Test
    fun nativeMailboxSupersessionRetiresOnlyItsProofWhileRealFailureRedrives() {
        val dropped = slice(
            surface,
            "fun onNtkRollingFrameDropped(",
            "fun onNtkRollingRendererFatal(",
        )
        val mailbox = dropped.indexOf("reason == NATIVE_FRAME_DROP_MAILBOX_SUPERSEDED")
        val retire = dropped.indexOf("pendingFrameCommits.remove(token)", mailbox)
        val mailboxReturn = dropped.indexOf("return", retire)
        val recover = dropped.indexOf("recoverDirectSurfaceSubmission(epoch, token)")
        assertTrue(mailbox >= 0)
        assertTrue(retire > mailbox)
        assertTrue(mailboxReturn > retire)
        assertTrue(recover > mailboxReturn)
        assertTrue(!dropped.substring(mailbox, mailboxReturn).contains("scheduleNoStateRetryLocked"))
        assertTrue(!dropped.substring(mailbox, mailboxReturn).contains("drawnVersion ="))
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

        val registration = slice(
            surface,
            "var hardwareCommitUnavailable = false",
            "if (fallbackCommit)",
        )
        val proofInstalled = registration.indexOf("pendingFrameCommits[work.frameToken]")
        val outcomeConsumed = registration.indexOf("earlyNativeOutcomes.remove(work.frameToken)")
        val failedOutcome = registration.indexOf("is EarlyNativeOutcome.PresentFailed")
        assertTrue(proofInstalled >= 0)
        assertTrue(outcomeConsumed > proofInstalled)
        assertTrue(failedOutcome > outcomeConsumed)
        assertTrue(registration.contains(
            "recoverDirectSurfaceSubmission(work.frameEpoch, work.frameToken)",
        ))

        val clear = slice(
            surface,
            "private fun clearFramePipeLocked(",
            "private fun shouldFinishScrollerAtInputEdgeLocked(",
        )
        assertTrue(clear.contains("earlyNativeOutcomes.clear()"))
    }

    @Test
    fun boundedNativeMailboxBackpressuresWithoutReplacingAcceptedProof() {
        val submit = slice(
            rollingNative,
            "std::int64_t submit(",
            "bool prewarm(",
        )
        assertTrue(submit.contains("return -2"))
        assertTrue(!submit.contains("frames_.pop_back()"))
        assertTrue(!submit.contains("callbackDropped(env, superseded.token"))

        val producer = slice(
            surface,
            "private fun renderDirectSurfaceFrame(",
            "private fun revealNativeSurfaceAfterPresentedFrame(",
        )
        assertTrue(producer.contains("val deferredCommandCount = deferredNativeCommandsInFlight.get()"))
        assertTrue(producer.contains("val noDeferredCommand = deferredCommandCount == 0"))
        assertTrue(producer.contains("nativeHasFrameMailboxCapacity"))
        assertTrue(producer.contains("if (!nativeMailboxReady)"))
        val backpressure = producer.substring(producer.indexOf("if (!nativeMailboxReady)"))
        assertTrue(
            backpressure.indexOf("postNativeMailboxAdmissionWake()") <
                backpressure.indexOf("return"),
        )
        assertTrue(!producer.contains("activeBandCropRunway ||"))

        val renderRegistration = slice(
            surface,
            "var hardwareCommitUnavailable = false",
            "if (fallbackCommit)",
        )
        val retireOld = renderRegistration.indexOf(
            "pendingFrameCommits.remove(timing.nativeSupersededToken)",
        )
        val installNew = renderRegistration.indexOf("pendingFrameCommits[work.frameToken]")
        assertTrue(retireOld >= 0)
        assertTrue(installNew > retireOld)

        val run = slice(rollingNative, "void run() noexcept", "JavaVM* vm_")
        assertTrue(run.contains("consecutivePresentFailures_ == 1"))
        assertTrue(run.contains("consecutivePresentFailures_ >= 2"))
        assertTrue(run.contains("fatal(env, \"surface-present-retry-exhausted\")"))
        assertTrue(run.contains("lifecycleRetiredFrames.swap(frames_)"))
        assertTrue(run.contains("(doPrepare || doAttach) && backendAttached_"))
        assertTrue(run.contains("kDropReasonLifecycleRetired"))

        val fatal = slice(
            surface,
            "fun onNtkRollingRendererFatal(",
            "private fun completeRollingNativeRecovery(",
        )
        assertTrue(fatal.contains("shouldRecreateRollingNativeRenderer("))
        assertTrue(surface.contains("MAX_NATIVE_RECOVERY_ATTEMPTS = 1"))
        assertTrue(fatal.contains("nativeSurfaceView.visibility = View.GONE"))
        assertTrue(fatal.contains("scheduleFrameLocked()"))
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, startIndex = start.coerceAtLeast(0) + 1)
        check(start >= 0 && end > start) { "missing architecture markers: $startMarker -> $endMarker" }
        return source.substring(start, end)
    }
}
