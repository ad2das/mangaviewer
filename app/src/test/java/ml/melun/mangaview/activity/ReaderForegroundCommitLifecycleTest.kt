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
        assertTrue(focusHandler.contains("resetStrictPhysicalPresentationCadence()"))
        assertTrue(
            focusHandler.indexOf("resetStrictPhysicalPresentationCadence()") <
                focusHandler.indexOf("strictTelemetryForegroundCommitArmed = false")
        )
    }
}
