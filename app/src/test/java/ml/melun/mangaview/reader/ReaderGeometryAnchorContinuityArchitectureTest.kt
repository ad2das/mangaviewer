package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGeometryAnchorContinuityArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun geometryReflowPreservesContentDuringTouchAndFling() {
        val restoreStart = source.indexOf("private fun restoreViewportAnchorLocked(")
        val restoreEnd = source.indexOf(
            "private fun restoreLifecycleViewportAnchorAfterGeometryLocked(",
            restoreStart,
        )
        val restore = source.substring(restoreStart, restoreEnd)
        assertTrue(restore.contains("setStructuralScrollOffsetLocked(desired)"))
        assertFalse(restore.contains("reader_viewport_anchor_restore_skip_backstep"))
        assertFalse(restore.contains("shouldRestoreAnchorAfterPendingResolves("))

        val structuralStart = source.indexOf("private fun setStructuralScrollOffsetLocked(")
        val structuralEnd = source.indexOf("private fun setScrollOffsetLocked(", structuralStart)
        val structural = source.substring(structuralStart, structuralEnd)
        assertTrue(structural.contains("dragOriginScrollOffset += appliedDelta"))
        assertTrue(structural.contains("activeScrollerOffsetShift += appliedDelta"))
        assertTrue(structural.contains("blockedForwardIntentTarget += appliedDelta"))
        assertTrue(structural.contains("blockedForwardIntentGestureCarryTarget += appliedDelta"))
    }

    @Test
    fun geometryReflowKeepsFractionalViewportCoordinate() {
        assertTrue(source.contains("val pageTopInViewportPx: Float"))

        val captureStart = source.indexOf("private fun viewportAnchorLocked()")
        val captureEnd = source.indexOf("private fun progressPositionLocked()", captureStart)
        val capture = source.substring(captureStart, captureEnd)
        assertTrue(capture.contains("pageTopOrElseLocked(page, 0f) - scrollOffset"))
        assertFalse(capture.contains("pageOffsetLocked(page)"))
        assertFalse(capture.contains(".toInt()"))

        val restoreStart = source.indexOf("private fun restoreViewportAnchorLocked(")
        val restoreEnd = source.indexOf(
            "private fun restoreLifecycleViewportAnchorAfterGeometryLocked(",
            restoreStart,
        )
        val restore = source.substring(restoreStart, restoreEnd)
        assertTrue(restore.contains("anchor: ViewportAnchor?"))
        assertTrue(restore.contains("anchor.pageTopInViewportPx"))
        assertFalse(restore.contains("anchor.offset"))

        val originalPageTop = 28_175f
        val originalScroll = 29_001.52344f
        val pageTopInViewport = originalPageTop - originalScroll
        val shiftedPageTop = originalPageTop + 938f
        val restoredScroll = shiftedPageTop - pageTopInViewport
        assertTrue(kotlin.math.abs(pageTopInViewport - -826.52344f) < 0.001f)
        assertTrue(kotlin.math.abs(restoredScroll - (originalScroll + 938f)) < 0.001f)
    }
}
