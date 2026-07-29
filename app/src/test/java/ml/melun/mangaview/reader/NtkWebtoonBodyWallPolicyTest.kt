package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkWebtoonBodyWallPolicyTest {
    @Test
    fun responsiveBodyKeepsItsOriginalStream() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS - 1L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun wallBoundBodyMovesOnlyAUsefulUntouchedSuffix() {
        assertTrue(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun nearlyCompleteBodyGetsTailGraceInsteadOfAReplicaRestart() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 300_000L - NtkWebtoonBodyWallPolicy.TAIL_GRACE_BYTES,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun noDeliveredPrefixCannotBecomeARangeContinuation() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
                deliveredBytes = 0L,
                expectedLength = 300_000L,
            )
        )
    }

    @Test
    fun onlyEntryViewportPagesUseTheShortExactSuffixWall() {
        assertEquals(
            NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(0),
        )
        assertEquals(
            NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(
                NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_LAST_PAGE
            ),
        )
        assertEquals(
            NtkWebtoonBodyWallPolicy.INITIAL_SEGMENT_WALL_MS,
            NtkWebtoonBodyWallPolicy.segmentWallMs(
                NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_LAST_PAGE + 1
            ),
        )
    }

    @Test
    fun entryViewportWallMovesOnlyTheExactUntouchedSuffix() {
        assertFalse(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS - 1L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
                segmentWallMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            )
        )
        assertTrue(
            NtkWebtoonBodyWallPolicy.shouldResume(
                elapsedMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
                segmentWallMs = NtkWebtoonBodyWallPolicy.ENTRY_VIEWPORT_SEGMENT_WALL_MS,
            )
        )
    }
}
