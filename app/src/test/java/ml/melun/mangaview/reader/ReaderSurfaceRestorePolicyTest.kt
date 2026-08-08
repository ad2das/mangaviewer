package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSurfaceRestorePolicyTest {
    @Test
    fun preservesValidOffsetOnAResolvedTallPage() {
        assertEquals(
            -420,
            ReaderSurfaceView.repairForwardOnlyRestoreOffsetForTest(
                savedOffsetPx = -420,
                resolvedPageDrawHeightPx = 1_280f,
                viewportHeightPx = 2_340,
            )
        )
    }

    @Test
    fun shortResolvedPageKeepsItsStableBookmarkIdentityVisible() {
        assertEquals(
            0,
            ReaderSurfaceView.repairForwardOnlyRestoreOffsetForTest(
                savedOffsetPx = -420,
                resolvedPageDrawHeightPx = 281f,
                viewportHeightPx = 2_340,
            )
        )
    }

    @Test
    fun inconsistentTallPageOffsetRetainsOneProgressProbeOfContext() {
        assertEquals(
            -461,
            ReaderSurfaceView.repairForwardOnlyRestoreOffsetForTest(
                savedOffsetPx = -900,
                resolvedPageDrawHeightPx = 1_280f,
                viewportHeightPx = 2_340,
            )
        )
    }

    @Test
    fun positiveOffsetCannotExposeAnUnrequestedPredecessorPage() {
        assertEquals(
            0,
            ReaderSurfaceView.repairForwardOnlyRestoreOffsetForTest(
                savedOffsetPx = 160,
                resolvedPageDrawHeightPx = 3_000f,
                viewportHeightPx = 2_340,
            )
        )
    }

    @Test
    fun invalidGeometryLeavesTheBookmarkUntouchedUntilPixelsResolve() {
        assertEquals(
            -420,
            ReaderSurfaceView.repairForwardOnlyRestoreOffsetForTest(
                savedOffsetPx = -420,
                resolvedPageDrawHeightPx = 0f,
                viewportHeightPx = 2_340,
            )
        )
    }
}
