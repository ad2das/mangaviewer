package ml.melun.mangaview.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderForegroundCommitLifecycleTest {
    @Test
    fun strictActualSemanticsRequireAnOwnedVisibleForegroundCommit() {
        val source = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
        ).readText()
        val handlerStart = source.indexOf("private fun handleStrictRollingCompletedDraw(")
        val handlerEnd = source.indexOf("private fun ", handlerStart + 1)
        assertTrue(handlerStart >= 0)
        assertTrue(handlerEnd > handlerStart)
        val handler = source.substring(handlerStart, handlerEnd)

        assertTrue(source.contains("strictTelemetryForegroundCommitArmed = false"))
        assertTrue(handler.contains("if (!strictTelemetryForegroundCommitArmed ||"))
        assertTrue(source.contains("renderView.invalidateCommittedPresentationProof(scheduleIfVisible = false)"))
        val resumeStart = source.indexOf("override fun onResume()")
        val resumeEnd = source.indexOf("override fun onActivityResult", resumeStart)
        assertTrue(resumeStart >= 0 && resumeEnd > resumeStart)
        val resume = source.substring(resumeStart, resumeEnd)
        assertTrue(resume.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(handler.contains("!strictTelemetryOwned ||"))
        assertTrue(handler.contains("if (!renderView.isShown ||"))
        assertTrue(handler.contains("renderView.windowVisibility != View.VISIBLE"))

        val focusStart = source.indexOf("override fun onWindowFocusChanged(hasFocus: Boolean)")
        val focusEnd = source.indexOf("override fun onConfigurationChanged", focusStart)
        assertTrue(focusStart >= 0)
        assertTrue(focusEnd > focusStart)
        val focusHandler = source.substring(focusStart, focusEnd)
        assertTrue(focusHandler.contains("else if (!hasFocus)"))
        assertTrue(
            focusHandler.contains(
                "invalidateReaderAfterHostBoundsChanged(resetStrictTelemetry = strictTelemetryOwned)"
            )
        )
        assertTrue(focusHandler.contains("resetStrictPhysicalPresentationCadence()"))
        assertTrue(
            focusHandler.indexOf("resetStrictPhysicalPresentationCadence()") <
                focusHandler.indexOf("strictTelemetryForegroundCommitArmed = false")
        )
    }

    @Test
    fun homeResumeRetriesTheRedrawAfterWindowVisibilityWithoutLeakingIntoBackground() {
        val source = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
        ).readText()
        val pauseStart = source.indexOf("override fun onPause()")
        val pauseEnd = source.indexOf("override fun onSaveInstanceState", pauseStart)
        val resumeStart = source.indexOf("override fun onResume()")
        val resumeEnd = source.indexOf("override fun onActivityResult", resumeStart)
        assertTrue(pauseStart >= 0 && pauseEnd > pauseStart)
        assertTrue(resumeStart >= 0 && resumeEnd > resumeStart)
        val pause = source.substring(pauseStart, pauseEnd)
        val resume = source.substring(resumeStart, resumeEnd)

        assertTrue(pause.contains("readerHostResumed = false"))
        assertTrue(pause.contains("readerHostResumeRedrawGeneration++"))
        assertTrue(resume.contains("readerHostResumed = true"))
        assertTrue(resume.contains("scheduleReaderHostResumeRedraw(resumeRedrawGeneration)"))

        val probeStart = source.indexOf("private fun scheduleReaderHostResumeRedraw(")
        val probeEnd = source.indexOf("override fun onActivityResult", probeStart)
        assertTrue(probeStart >= 0 && probeEnd > probeStart)
        val probe = source.substring(probeStart, probeEnd)
        assertTrue(probe.contains("generation != readerHostResumeRedrawGeneration"))
        assertTrue(probe.contains("!readerHostResumed"))
        assertTrue(probe.contains("destroyed || isFinishing || isDestroyed"))
        assertTrue(probe.contains("renderView.requestPendingNativeSurfaceHwuiCommit()"))
        val repulse = probe.indexOf("renderView.requestPendingNativeSurfaceHwuiCommit()")
        val budgetCheck = probe.indexOf("val pendingRetryBudgetOpen")
        assertTrue(repulse >= 0)
        assertTrue(budgetCheck > repulse)
        assertTrue(
            probe.contains("nativeRecoveryPending && pendingRetryBudgetOpen -> postProbe(")
        )
        assertTrue(probe.contains("nativeRecoveryPending -> Log.w("))
        assertTrue(probe.contains("reader_host_resume_redraw_budget_exhausted"))
        assertTrue(probe.contains("HOST_RESUME_REDRAW_PENDING_RETRY_MS"))
        assertTrue(probe.contains("HOST_RESUME_REDRAW_PENDING_BUDGET_MS"))
        assertTrue(
            probe.contains(
                "postProbe(HOST_RESUME_REDRAW_FIRST_DELAY_MS, baselineStage = 0)"
            )
        )
        assertTrue(probe.contains("baselineStage == 0"))
        assertTrue(probe.contains("baselineStage == 1"))
        assertFalse(probe.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(probe.contains("window.decorView.postInvalidateOnAnimation()"))
        assertTrue(probe.contains("HOST_RESUME_REDRAW_FINAL_DELAY_MS"))
        assertTrue(
            source.contains("HOST_RESUME_REDRAW_PENDING_BUDGET_MS = 45_000L")
        )

        val pagesReadyStart = source.indexOf("override fun onPagesReady(count: Int)")
        val pagesReadyEnd = source.indexOf(
            "private fun effectiveNtkPagesReadyCount(",
            pagesReadyStart,
        )
        assertTrue(pagesReadyStart >= 0 && pagesReadyEnd > pagesReadyStart)
        val pagesReady = source.substring(pagesReadyStart, pagesReadyEnd)
        assertTrue(pagesReady.contains("if (readerHostResumed && ::renderView.isInitialized)"))
        assertTrue(pagesReady.contains("readerHostResumeRedrawGeneration++"))
        assertTrue(
            pagesReady.contains(
                "scheduleReaderHostResumeRedraw(readerHostResumeRedrawGeneration)"
            )
        )
    }

    @Test
    fun splitScreenAndConfigurationChangesInvalidatePixelsForEveryReaderProfile() {
        val source = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
        ).readText()
        val configurationStart = source.indexOf("override fun onConfigurationChanged(")
        val multiWindowStart = source.indexOf("override fun onMultiWindowModeChanged(")
        val helperStart = source.indexOf("private fun invalidateReaderAfterHostBoundsChanged(")
        val helperEnd = source.indexOf("override fun onDestroy()", helperStart)
        assertTrue(configurationStart >= 0 && multiWindowStart > configurationStart)
        assertTrue(helperStart > multiWindowStart && helperEnd > helperStart)
        val configuration = source.substring(configurationStart, multiWindowStart)
        val multiWindow = source.substring(multiWindowStart, helperStart)
        val helper = source.substring(helperStart, helperEnd)

        assertTrue(configuration.contains("invalidateReaderAfterHostBoundsChanged("))
        assertTrue(multiWindow.contains("invalidateReaderAfterHostBoundsChanged("))
        assertTrue(helper.contains("resetStrictPhysicalPresentationCadence()"))
        assertTrue(
            helper.indexOf("resetStrictPhysicalPresentationCadence()") <
                helper.indexOf("renderView.contentDescription = null")
        )
        assertTrue(helper.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(helper.contains("renderView.requestLayout()"))
        assertTrue(helper.contains("scheduleReaderHostResumeRedraw("))
    }
}
