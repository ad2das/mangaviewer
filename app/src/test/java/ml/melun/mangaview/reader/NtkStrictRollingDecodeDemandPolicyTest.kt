package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkStrictRollingDecodeDemandPolicyTest {
    @Test
    fun reverseLogicalAnchorBridgesHeldPhysicalFrameWithoutGaps() {
        assertEquals(
            5..9,
            NtkStrictRollingDecodeDemandPolicy.bridgePhysicalFrameToLogicalAnchor(
                physicalVisibleRange = 8..9,
                logicalAnchor = 5,
                pageCount = 15,
            ),
        )
    }

    @Test
    fun forwardLagAndBoundsRemainContinuous() {
        assertEquals(
            8..12,
            NtkStrictRollingDecodeDemandPolicy.bridgePhysicalFrameToLogicalAnchor(
                physicalVisibleRange = 8..9,
                logicalAnchor = 12,
                pageCount = 15,
            ),
        )
        assertEquals(
            0..14,
            NtkStrictRollingDecodeDemandPolicy.bridgePhysicalFrameToLogicalAnchor(
                physicalVisibleRange = -4..30,
                logicalAnchor = 18,
                pageCount = 15,
            ),
        )
    }

    @Test
    fun emptyPhysicalFrameStillDemandsLogicalAnchor() {
        assertEquals(
            4..4,
            NtkStrictRollingDecodeDemandPolicy.bridgePhysicalFrameToLogicalAnchor(
                physicalVisibleRange = IntRange.EMPTY,
                logicalAnchor = 4,
                pageCount = 10,
            ),
        )
        assertEquals(
            IntRange.EMPTY,
            NtkStrictRollingDecodeDemandPolicy.bridgePhysicalFrameToLogicalAnchor(
                physicalVisibleRange = 0..0,
                logicalAnchor = 0,
                pageCount = 0,
            ),
        )
    }
}
