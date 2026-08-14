package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkExpandedNativeTextureHistoryPolicyTest {
    @Test
    fun currentPreviousAndPendingForwardStayBoundedAcrossABC() {
        val a = NtkExpandedNativeTextureHistoryPolicy.configured("A")
        val withB = NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
            a,
            predecessorPath = "A",
            targetPath = "B",
            revision = 1L,
        )
        assertEquals(listOf("A", "B"), withB.authorizedPaths().toList())

        // C may be discovered while B is decoded but not physically presented. It must remain
        // metadata-only so it cannot evict the A -> B transition authority.
        val withDeferredC = NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
            withB,
            predecessorPath = "B",
            targetPath = "C",
            revision = 2L,
        )
        assertEquals(listOf("A", "B"), withDeferredC.authorizedPaths().toList())

        val b = NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
            withDeferredC,
            targetPath = "B",
            committedPath = "B",
        )
        assertEquals(listOf("B", "A", "C"), b.authorizedPaths().toList())
        val c = NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
            b,
            targetPath = "C",
            committedPath = "C",
        )
        assertEquals(listOf("C", "B"), c.authorizedPaths().toList())
    }

    @Test
    fun staleOrUncommittedAdvanceCannotReplaceCurrentAuthority() {
        val withB = NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
            NtkExpandedNativeTextureHistoryPolicy.configured("A"),
            predecessorPath = "A",
            targetPath = "B",
            revision = 5L,
        )
        assertEquals(
            withB,
            NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
                withB,
                targetPath = "B",
                committedPath = "A",
            ),
        )
        assertEquals(
            withB,
            NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
                withB,
                targetPath = "C",
                committedPath = "C",
            ),
        )
        assertEquals(
            withB,
            NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
                withB,
                predecessorPath = "A",
                targetPath = "C",
                revision = 4L,
            ),
        )
        assertEquals(
            withB,
            NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
                withB,
                predecessorPath = "A",
                targetPath = "C",
                revision = 5L,
            ),
        )
        val replaced = NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
            withB,
            predecessorPath = "A",
            targetPath = "C",
            revision = 6L,
        )
        assertEquals(listOf("A", "C"), replaced.authorizedPaths().toList())
        assertEquals(
            replaced,
            NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
                replaced,
                targetPath = "B",
                committedPath = "B",
            ),
        )
    }

    @Test
    fun reverseMoveEvidenceSurvivesAnImmediatelyCoalescedIdleUp() {
        val reverseMove = NtkReverseWindowEvidencePolicy.capture(
            busy = true,
            activeDirection = ReaderSurfaceView.DIRECTION_PREVIOUS,
            firstVisiblePage = 42,
        )
        val idleUp = NtkReverseWindowEvidencePolicy.capture(
            busy = false,
            activeDirection = 0,
            firstVisiblePage = 45,
        )
        assertEquals(
            NtkReverseWindowEvidencePolicy.Evidence(
                directionHint = ReaderSurfaceView.DIRECTION_PREVIOUS,
                reverseFirstPageHint = 42,
            ),
            NtkReverseWindowEvidencePolicy.merge(reverseMove, idleUp),
        )
    }

    @Test
    fun reverseFloorBypassRequiresLivePreviousMotion() {
        assertTrue(
            NtkExpandedNativeTextureHistoryPolicy.shouldBypassResumeFloorForReverse(
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                pointerDown = true,
                dragging = false,
                scrollbarDragging = false,
                scrollerFinished = true,
            ),
        )
        assertTrue(
            NtkExpandedNativeTextureHistoryPolicy.shouldBypassResumeFloorForReverse(
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                pointerDown = false,
                dragging = false,
                scrollbarDragging = false,
                scrollerFinished = false,
            ),
        )
        assertFalse(
            NtkExpandedNativeTextureHistoryPolicy.shouldBypassResumeFloorForReverse(
                ReaderSurfaceView.DIRECTION_PREVIOUS,
                pointerDown = false,
                dragging = false,
                scrollbarDragging = false,
                scrollerFinished = true,
            ),
        )
        assertFalse(
            NtkExpandedNativeTextureHistoryPolicy.shouldBypassResumeFloorForReverse(
                ReaderSurfaceView.DIRECTION_NEXT,
                pointerDown = true,
                dragging = true,
                scrollbarDragging = true,
                scrollerFinished = false,
            ),
        )
    }

    @Test
    fun prefixRemovalRemapsFloorAndRetiresAnExactlyRemovedResumePage() {
        assertEquals(
            12,
            NtkExpandedNativeTextureHistoryPolicy.remapFloorAfterRemoval(
                floor = 32,
                startIndex = 0,
                removedCount = 20,
                remainingPageCount = 80,
            ),
        )
        assertEquals(
            5,
            NtkExpandedNativeTextureHistoryPolicy.remapFloorAfterRemoval(
                floor = 7,
                startIndex = 5,
                removedCount = 10,
                remainingPageCount = 40,
            ),
        )
        assertEquals(
            -1,
            NtkExpandedNativeTextureHistoryPolicy.remapExactPageAfterRemoval(
                page = 7,
                startIndex = 5,
                removedCount = 10,
                remainingPageCount = 40,
            ),
        )
        assertEquals(
            12,
            NtkExpandedNativeTextureHistoryPolicy.remapExactPageAfterRemoval(
                page = 32,
                startIndex = 0,
                removedCount = 20,
                remainingPageCount = 80,
            ),
        )
    }
}
