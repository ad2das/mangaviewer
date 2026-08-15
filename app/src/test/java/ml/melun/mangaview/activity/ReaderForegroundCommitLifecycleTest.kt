package ml.melun.mangaview.activity

import java.io.File
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
        assertTrue(probe.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(probe.contains("window.decorView.postInvalidateOnAnimation()"))
        assertTrue(probe.contains("HOST_RESUME_REDRAW_FINAL_DELAY_MS"))
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
        assertTrue(helper.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(helper.contains("renderView.requestLayout()"))
        assertTrue(helper.contains("scheduleReaderHostResumeRedraw("))
    }
}
