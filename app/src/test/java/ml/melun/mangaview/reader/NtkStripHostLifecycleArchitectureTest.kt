package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
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

    @Test
    fun holderRecreationPreservesDesiredAlphaAndSealedSessionAuthority() {
        val controller = File(
            "src/main/java/ml/melun/mangaview/reader/NtkInlineReaderController.kt"
        ).readText()
        val surface = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStripSurfaceView.kt"
        ).readText()

        val revokeStart = controller.indexOf("private fun onNtkSurfaceRevoked(")
        val lostStart = controller.indexOf("private fun onNtkSurfaceLost(", revokeStart)
        val failedStart = controller.indexOf("private fun onNtkSurfaceAttachFailed(", lostStart)
        assertTrue(revokeStart >= 0 && lostStart > revokeStart && failedStart > lostStart)
        val revoked = controller.substring(revokeStart, lostStart)
        val lost = controller.substring(lostStart, failedStart)
        assertTrue(revoked.contains("NtkSurfaceLossReason.HOLDER_DESTROYED"))
        assertTrue(revoked.contains("state in setOf(State.STAGED, State.ACTIVE)"))
        assertTrue(revoked.contains("targetReducer.revokeCurrentSurface"))
        assertTrue(revoked.contains("pendingLifecycleSurfaceReattach"))
        assertTrue(
            revoked.indexOf("pendingLifecycleSurfaceReattach = LifecycleSurfaceReattach") <
                revoked.indexOf("publishedStageTicket = null")
        )
        assertTrue(lost.contains("NtkNativeDetachDisposition.SURFACE_PRESERVED"))
        assertTrue(lost.contains("lossConfirmedWithResources = true"))

        val availableStart = controller.indexOf("private fun onNtkSurfaceAvailable(")
        val available = controller.substring(availableStart, revokeStart)
        assertTrue(available.contains("bindPipelinePresentationTarget("))
        assertTrue(available.contains("target.setHostPresentationEnabled(!hostPaused)"))
        assertTrue(available.contains("staged_surface_reattach_proof_failed"))
        assertTrue(available.contains("pendingLifecycleSurfaceReattach = null"))

        val hideStart = surface.indexOf("private fun hideCompositorForSurfaceLoss()")
        val alphaApiStart = surface.indexOf("private fun applyPublishedCompositorAlphaApi29", hideStart)
        assertTrue(hideStart >= 0 && alphaApiStart > hideStart)
        val hide = surface.substring(hideStart, alphaApiStart)
        assertTrue(hide.contains("compositorTemporarilyHiddenForSurfaceLoss = true"))
        assertTrue(hide.contains("super.setAlpha(0f)"))
        assertFalse(hide.contains("compositorAlpha = 0f"))
        assertTrue(surface.contains("waitingForExactDetach"))
        assertTrue(surface.contains("!waitingForExactDetach"))
        assertTrue(surface.contains("scheduleCompositorRevealAfterFreshFrame"))
        assertTrue(hide.contains("compositorRevealDispatchGate.clear()"))
        assertFalse(surface.contains("compositorRevealScheduled"))

        val activate = surface.indexOf("compositorRevealDispatchGate.activate(")
        val publish = surface.indexOf("publishedSurface.set(identity)", activate)
        assertTrue(activate >= 0 && publish > activate)
        val revealStart = surface.indexOf("private fun scheduleCompositorRevealAfterFreshFrame")
        val revealEnd = surface.indexOf("private fun NtkPublishedSurfaceIdentity", revealStart)
        val reveal = surface.substring(revealStart, revealEnd)
        assertTrue(reveal.contains("compositorRevealDispatchGate.offer("))
        assertTrue(reveal.contains("compositorRevealDispatchGate.take(offer.reservation)"))

        val completeStart = surface.indexOf("private fun completeSurfaceLossOnMain(")
        val terminalStart = surface.indexOf("private fun terminalSurfaceFailure(", completeStart)
        val complete = surface.substring(completeStart, terminalStart)
        assertTrue(
            complete.indexOf("engineSurfaceState = null") <
                complete.indexOf("surfaceLifecycleListener?.onSurfaceLost(lossEvent)")
        )
        assertTrue(
            complete.indexOf("surfaceLifecycleListener?.onSurfaceLost(lossEvent)") <
                complete.indexOf("else driveSurfaceHandoff()")
        )
    }

    @Test
    fun publishedResizeIsGenerationQualifiedAndRunsOnRenderOwner() {
        val native = File("src/main/cpp/ntk_strip_renderer.cpp").readText()
        val resizeStart = native.indexOf("    void resize(")
        val contextLossStart = native.indexOf("    void set_context_loss_for_testing()", resizeStart)
        assertTrue(resizeStart >= 0 && contextLossStart > resizeStart)
        val resize = native.substring(resizeStart, contextLossStart)
        assertTrue(resize.contains("PublishedResizeRequest"))
        assertTrue(resize.contains("attach_request_->generation != attach_generation"))
        assertTrue(resize.contains("attach_request_->surface_epoch != surface_epoch"))
        assertTrue(resize.contains("render_condition_.notify_one()"))
        assertFalse(resize.contains("block_input_and_presentation()"))
        assertFalse(resize.contains("authority_failed_.store"))

        val surface = File(
            "src/main/java/ml/melun/mangaview/reader/NtkStripSurfaceView.kt"
        ).readText()
        assertTrue(surface.contains("PublishedResizeInFlight"))
        assertTrue(surface.contains("predecessorBackendSurfaceSerial"))
        assertTrue(surface.contains("frame.backendSurfaceSerial <="))
        assertTrue(surface.contains("surfaceLifecycleListener?.onSurfaceAvailable(resized)"))
        assertTrue(surface.contains("publishedResizeCommitGate.reserve(inFlight)"))
        assertTrue(surface.contains("publishedResizeCommitGate.begin(inFlight, reservation)"))
        assertTrue(surface.contains("publishedResizeCommitGate.finish(inFlight, reservation)"))
        assertTrue(surface.contains("if (releasedCurrent)"))
        assertFalse(surface.contains("publishedResizeCommitScheduled"))

        val loopStart = native.indexOf("    void render_loop()")
        val fieldsStart = native.indexOf("    std::mutex bind_api_mutex_", loopStart)
        val loop = native.substring(loopStart, fieldsStart)
        assertTrue(loop.contains("ANativeWindow_acquire(published_resize_window)"))
        assertTrue(loop.contains("ANativeWindow_release(published_resize_window)"))
        assertTrue(loop.contains("attach_request_->generation == request.generation"))
        assertTrue(loop.contains("attach_request_->surface_epoch == request.surface_epoch"))
        assertTrue(loop.contains("request.surface_epoch,\n                    false"))
        assertTrue(loop.contains("input_admission_blocked_.store("))
        assertTrue(loop.contains("presentation_blocked_.store("))
        assertTrue(loop.contains("should_refresh_surface = true"))
        assertTrue(loop.contains("else if (resized)"))
        assertTrue(loop.contains("detach_window();"))

        val publishStart = native.indexOf("    bool publish_attach(")
        val lossStart = native.indexOf("    ntk::attach_generation::SurfaceLossDisposition", publishStart)
        val publish = native.substring(publishStart, lossStart)
        assertTrue(publish.contains("RendererMode::ACTIVE"))
        assertTrue(publish.contains("input_admission_blocked_.store(false"))
        assertTrue(publish.contains("surface_refresh_requested_ = true"))
    }
}
