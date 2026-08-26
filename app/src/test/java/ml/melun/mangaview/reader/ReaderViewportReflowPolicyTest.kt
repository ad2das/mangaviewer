package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderViewportReflowPolicyTest {
    @Test
    fun widthReflowKeepsTheSameFractionOfTheSamePageAtTheViewportProbe() {
        val anchor = ReaderViewportReflowAnchor(
            page = 7,
            pageFraction = 0.4f,
            viewportProbeFraction = 0.35f,
        )

        val restored = ReaderViewportReflowPolicy.restoredScrollOffset(
            pageTopPx = 2_000f,
            pageHeightPx = 500f,
            viewportHeightPx = 1_000,
            anchor = anchor,
        )

        assertEquals(1_850f, restored, 0.001f)
        val restoredProbe = restored + 1_000f * 0.35f
        assertEquals(0.4f, (restoredProbe - 2_000f) / 500f, 0.001f)
    }

    @Test
    fun malformedFractionsAreBoundedBeforeRestoration() {
        assertEquals(
            300f,
            ReaderViewportReflowPolicy.restoredScrollOffset(
                pageTopPx = 100f,
                pageHeightPx = 200f,
                viewportHeightPx = 500,
                anchor = ReaderViewportReflowAnchor(0, 2f, -1f),
            ),
            0.001f,
        )
    }
}
