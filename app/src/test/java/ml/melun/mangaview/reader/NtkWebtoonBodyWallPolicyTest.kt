package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkWebtoonBodyWallPolicyTest {
    @Test
    fun ordinaryReplicaRingStaysFastOnBothNetworks() {
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false
            ),
        )
        assertEquals(
            2_500L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false,
                pageIndex = 0,
            ),
        )
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = true,
                pageIndex = 0,
            ),
        )
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = false,
                pageIndex = NtkWebtoonReplicaHeaderPolicy.WIFI_ENTRY_LAST_PAGE + 1,
            ),
        )
        assertEquals(
            1_000L,
            NtkWebtoonReplicaHeaderPolicy.headerFailoverMs(
                cellularResilientTransport = true
            ),
        )
    }

    @Test
    fun wifiUsesOneFastRingThenBoundedFocusedRecoveryOnly() {
        assertEquals(
            1,
            NtkWebtoonReplicaHeaderPolicy.replicaCycles(
                cellularResilientTransport = false
            ),
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = false,
                webtoonReplica = true,
            )
        )
        assertEquals(2_500L, NtkWebtoonReplicaHeaderPolicy.WIFI_FOCUSED_RECOVERY_HEADER_MS)
        assertEquals(3, NtkWebtoonReplicaHeaderPolicy.WIFI_FOCUSED_RECOVERY_ATTEMPTS)
    }

    @Test
    fun cellularRetainsFourRingsAndNeverUsesWifiFocusedRecovery() {
        assertEquals(
            4,
            NtkWebtoonReplicaHeaderPolicy.replicaCycles(
                cellularResilientTransport = true
            ),
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = true,
                webtoonReplica = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAppendFocusedRecovery(
                cellularResilientTransport = false,
                webtoonReplica = false,
            )
        )
        assertTrue(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptExhaustedExactQuicRecovery(
                cellularResilientTransport = false,
                webtoonReplica = true,
            )
        )
        assertFalse(
            NtkWebtoonReplicaHeaderPolicy.shouldAttemptExhaustedExactQuicRecovery(
                cellularResilientTransport = true,
                webtoonReplica = true,
            )
        )
    }

    @Test
    fun wifiReplicaPreferenceRequiresTwoUsableHeadersBeforeReordering() {
        val preference = NtkWebtoonReplicaPreference()
        val originalOrder = listOf("xiaomichina.com", "f1spard.site", "shaomoi.org")

        assertFalse(preference.recordUsableHeader("f1spard.site"))
        assertEquals(originalOrder, preference.orderHosts(originalOrder))
        assertTrue(preference.recordUsableHeader("F1SPARD.SITE"))
        assertEquals(
            listOf("f1spard.site", "xiaomichina.com", "shaomoi.org"),
            preference.orderHosts(originalOrder),
        )
    }

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
