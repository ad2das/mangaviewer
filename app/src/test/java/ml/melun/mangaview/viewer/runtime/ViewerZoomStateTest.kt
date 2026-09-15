package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerZoomStateTest {
    private fun zoom(width: Float = 1000f) = ViewerZoomState().apply { viewportChanged(width) }

    /** The document column under a fixed finger must not move when the scale changes. */
    private fun documentColumn(state: ViewerZoomState, focusX: Float): Float =
        (focusX - state.translationX) / state.scale

    @Test
    fun pinchKeepsDocumentColumnUnderFocus() {
        val state = zoom()
        val before = documentColumn(state, 300f)
        state.pinch(spanRatio = 1.5f, focusX = 300f, focusY = 0f)
        state.pinch(spanRatio = 1.5f, focusX = 300f, focusY = 0f)

        assertEquals(2.25f, state.scale, 0.001f)
        assertEquals(before, documentColumn(state, 300f), 0.001f)
        assertTrue(state.zoomed)
    }

    @Test
    fun pinchAnchorsScrollOnTheFocusedRow() {
        val state = zoom()
        val scroll = state.pinch(spanRatio = 2f, focusX = 0f, focusY = 500f)

        assertEquals(250.0, scroll, 0.001)
    }

    @Test
    fun zoomingOutAnchorsBackward() {
        val state = zoom()
        state.pinch(spanRatio = 2f, focusX = 0f, focusY = 500f)
        val scroll = state.pinch(spanRatio = 0.5f, focusX = 0f, focusY = 500f)

        assertEquals(-500.0, scroll, 0.001)
        assertEquals(ViewerZoomState.MIN_SCALE, state.scale, 0.001f)
    }

    @Test
    fun scaleIsClampedToItsRange() {
        val state = zoom()
        state.pinch(spanRatio = 100f, focusX = 0f, focusY = 0f)
        assertEquals(ViewerZoomState.MAX_SCALE, state.scale, 0.001f)

        state.pinch(spanRatio = 0.001f, focusX = 0f, focusY = 0f)
        assertEquals(ViewerZoomState.MIN_SCALE, state.scale, 0.001f)
        assertFalse(state.zoomed)
    }

    @Test
    fun translationIsClampedToTheContentWidth() {
        val state = zoom(1000f)
        state.pinch(spanRatio = 2f, focusX = 1000f, focusY = 0f)
        assertEquals(-1_000f, state.translationX, 0.001f)

        state.pinch(spanRatio = 2f, focusX = 2_000f, focusY = 0f)
        assertEquals(4f, state.scale, 0.001f)
        assertEquals(-3_000f, state.translationX, 0.001f)
    }

    @Test
    fun toggleZoomsAroundTheTappedPointAndResetsFlat() {
        val state = zoom()
        val scrollIn = state.toggle(focusX = 400f, focusY = 600f)

        assertEquals(ViewerZoomState.DOUBLE_TAP_SCALE, state.scale, 0.001f)
        assertEquals(-400f, state.translationX, 0.001f)
        assertEquals(300.0, scrollIn, 0.001)

        val scrollOut = state.toggle(focusX = 400f, focusY = 600f)
        assertEquals(ViewerZoomState.MIN_SCALE, state.scale, 0.001f)
        assertEquals(0f, state.translationX, 0.001f)
        assertEquals(-600.0, scrollOut, 0.001)
        assertFalse(state.zoomed)
    }

    @Test
    fun horizontalPanFollowsTheFingerAndClamps() {
        val state = zoom(1000f)
        state.toggle(focusX = 500f, focusY = 0f)

        assertTrue(state.pan(200f))
        assertEquals(-300f, state.translationX, 0.001f)

        assertTrue(state.pan(5_000f))
        assertEquals(0f, state.translationX, 0.001f)

        assertTrue(state.pan(-5_000f))
        assertEquals(-1_000f, state.translationX, 0.001f)
    }

    @Test
    fun horizontalPanIsIgnoredAtFitWidth() {
        val state = zoom()
        assertFalse(state.pan(120f))
        assertEquals(0f, state.translationX, 0.001f)
    }

    @Test
    fun viewportChangeClampsAnExistingTranslation() {
        val state = zoom(1000f)
        state.toggle(focusX = 999f, focusY = 0f)
        assertEquals(-999f, state.translationX, 0.001f)

        state.viewportChanged(500f)
        assertEquals(-500f, state.translationX, 0.001f)
    }
}
