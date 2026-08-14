package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStripHostLifecycleArchitectureTest {
    @Test
    fun homeSuppressesStripPresentationAndResumeRequestsFreshFrame() {
        val controller = File(
            "src/main/java/ml/melun/mangaview/reader/NtkInlineReaderController.kt"
        ).readText()
        val surface = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStripSurfaceView.kt"
        ).readText()

        val pauseStart = controller.indexOf("fun onHostPause()")
        val resumeStart = controller.indexOf("fun onHostResume()", pauseStart)
        val focusStart = controller.indexOf("fun onHostWindowFocusChanged", resumeStart)
        assertTrue(pauseStart >= 0 && resumeStart > pauseStart && focusStart > resumeStart)
        val pause = controller.substring(pauseStart, resumeStart)
        val resume = controller.substring(resumeStart, focusStart)
        assertTrue(pause.contains("ViewerTelemetry.physicalScrollMotionEnded()"))
        assertTrue(pause.contains("setHostPresentationEnabled(false)"))
        assertTrue(
            pause.indexOf("setHostPresentationEnabled(false)") <
                pause.indexOf("ViewerTelemetry.physicalScrollMotionEnded()")
        )
        assertTrue(resume.contains("setHostPresentationEnabled(true)"))
        val focus = controller.substring(focusStart, controller.indexOf("fun handleBackPressed", focusStart))
        assertTrue(focus.contains("setHostPresentationEnabled(hasFocus && !hostPaused)"))
        assertTrue(focus.contains("if (!hasFocus) ViewerTelemetry.physicalScrollMotionEnded()"))

        val gateStart = surface.indexOf("internal fun setHostPresentationEnabled(enabled: Boolean)")
        val frameStart = surface.indexOf("private fun onFramePresented(")
        assertTrue(gateStart >= 0 && frameStart > gateStart)
        assertTrue(surface.substring(gateStart, frameStart).contains("requestRender()"))
        val frameEnd = surface.indexOf("private fun onPreSubmitViewportGap", frameStart)
        val frameHandler = surface.substring(frameStart, frameEnd)
        assertTrue(frameHandler.contains("if (!hostPresentationGate.isEnabled) return"))
        assertTrue(frameHandler.contains("hostPresentationGate.runIfEnabled { onHostFramePresented(frame) }"))
        assertTrue(frameHandler.contains("private fun onHostFramePresented("))
        assertTrue(
            frameHandler.indexOf("if (!hostPresentationGate.isEnabled) return") <
                frameHandler.indexOf("ViewerTelemetry.actualFramePresented")
        )
        assertTrue(
            frameHandler.indexOf("hostPresentationGate.runIfEnabled") <
                frameHandler.indexOf("frameListener?.invoke(frame)")
        )
    }
}
