package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkManhwaProjectedBodyHedgePolicyTest {
    @Test
    fun stalledAcceptedBodyResumesBeforeItCanReachTheForwardViewport() {
        assertEquals(2_500L, ReaderImageCache.manhwaBodyProgressDeadlineMsForTest())
    }

    @Test
    fun onTimeBodyKeepsItsOriginalStream() {
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldResume(
                sessionElapsedMs = 1_500L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 150_000L,
                expectedLength = 341_069L,
            )
        )
    }

    @Test
    fun earlySlowSampleDoesNotAbandonAResponsiveColdStream() {
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldResume(
                sessionElapsedMs = 2_100L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 74_407L,
                expectedLength = 301_162L,
            )
        )
    }

    @Test
    fun slowSecondPageMovesOnlyItsSuffixBeforeItCanBlockTheFirstViewport() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartEntryViewportTail(
                pageIndex = 1,
                sessionElapsedMs = 2_000L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 50_000L,
                expectedLength = 180_000L,
            )
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartEntryViewportTail(
                pageIndex = 1,
                sessionElapsedMs = 2_000L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 100_000L,
                expectedLength = 180_000L,
            )
        )
    }

    @Test
    fun entryViewportRecoveryNeverExpandsIntoTheBulkWave() {
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartEntryViewportTail(
                pageIndex = 0,
                sessionElapsedMs = 2_000L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 50_000L,
                expectedLength = 180_000L,
            )
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartEntryViewportTail(
                pageIndex = 2,
                sessionElapsedMs = 2_000L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 50_000L,
                expectedLength = 180_000L,
            )
        )
    }

    @Test
    fun settledSlowDripMovesOnlyItsUntouchedSuffix() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldResume(
                sessionElapsedMs = 5_500L,
                bodyElapsedMs = 4_000L,
                deliveredBytes = 150_000L,
                expectedLength = 400_000L,
            )
        )
    }

    @Test
    fun disjointTailStartsAfterDeliveredPrefixAndLeavesUsefulSuffix() {
        val delivered = 100_000L
        val expected = 300_000L
        val split = requireNotNull(
            NtkManhwaProjectedBodyHedgePolicy.disjointTailStart(
                bodyElapsedMs = 1_000L,
                deliveredBytes = delivered,
                expectedLength = expected,
            )
        )
        assertTrue(split > delivered)
        assertTrue(split - delivered >= 24L * 1024L)
        assertTrue(split - delivered <= 64L * 1024L)
        assertTrue(expected - split >= 32L * 1024L)
    }

    @Test
    fun invalidCompleteOrTinyTailMeasurementsNeverCreateAContinuation() {
        assertFalse(NtkManhwaProjectedBodyHedgePolicy.shouldResume(0L, 1L, 1L, 2L))
        assertFalse(NtkManhwaProjectedBodyHedgePolicy.shouldResume(1L, 1L, 0L, 2L))
        assertFalse(NtkManhwaProjectedBodyHedgePolicy.shouldResume(1L, 1L, 2L, 2L))
        assertNull(NtkManhwaProjectedBodyHedgePolicy.disjointTailStart(1L, 1L, 2L))
        assertNull(NtkManhwaProjectedBodyHedgePolicy.disjointTailStart(1L, 90_000L, 100_000L))
    }

    @Test
    fun admissionClassReservesSpeculativeCapacityForLatePages() {
        assertFalse(NtkManhwaProjectedBodyHedgePolicy.isLateAdmission(1_700L, 1_000L))
        assertTrue(NtkManhwaProjectedBodyHedgePolicy.isLateAdmission(2_800L, 1_000L))
        assertFalse(NtkManhwaProjectedBodyHedgePolicy.isLateAdmission(900L, 1_000L))
    }

    @Test
    fun projectedStartsAreFiniteAcrossTheWholeEpisodeWave() {
        val starts = NtkManhwaProjectedBodyHedgePolicy.SessionStarts()
        repeat(6) {
            assertTrue(
                NtkManhwaProjectedBodyHedgePolicy.tryAcquireSessionStart(
                    starts,
                    300_000L,
                    lateAdmission = false,
                )
            )
        }
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.tryAcquireSessionStart(
                starts,
                300_000L,
                lateAdmission = false,
            )
        )
        assertEquals(6, starts.ordinary.get())
        assertEquals(0, starts.lateReserved.get())
    }

    @Test
    fun historicalBestBudgetDoesNotAddALateSeventhSuffix() {
        val starts = NtkManhwaProjectedBodyHedgePolicy.SessionStarts()
        repeat(6) {
            assertTrue(
                NtkManhwaProjectedBodyHedgePolicy.tryAcquireSessionStart(
                    starts,
                    373_699L,
                    lateAdmission = false,
                )
            )
        }
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.tryAcquireSessionStart(
                starts,
                392_545L,
                lateAdmission = true,
            )
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.tryAcquireSessionStart(
                starts,
                392_545L,
                lateAdmission = true,
            )
        )
        assertEquals(6, starts.ordinary.get())
        assertEquals(0, starts.lateReserved.get())
    }

    @Test
    fun largeProjectedTailUsesOneExhaustiveDisjointMirrorSegment() {
        val segments = NtkManhwaProjectedBodyHedgePolicy.disjointTailSegments(
            start = 124_059L,
            expectedLength = 328_546L,
        )
        assertEquals(1, segments.size)
        assertEquals(124_059L, segments.first().first)
        assertEquals(328_545L, segments.last().last)
        assertEquals(328_546L - 124_059L, segments.sumOf { it.last - it.first + 1L })
    }

    @Test
    fun smallProjectedTailStaysOneExactRange() {
        val segments = NtkManhwaProjectedBodyHedgePolicy.disjointTailSegments(
            start = 200_000L,
            expectedLength = 300_000L,
        )
        assertEquals(listOf(200_000L..299_999L), segments)
    }

    @Test
    fun projectedRecoveryDoesNotRotateAnAlreadyPageBalancedCandidateListAgain() {
        assertEquals(
            0,
            NtkManhwaProjectedBodyHedgePolicy.projectedFirstCandidateIndex(
                physicalAttempt = 1,
                candidateCount = 3,
            ),
        )
        assertEquals(
            1,
            NtkManhwaProjectedBodyHedgePolicy.projectedFirstCandidateIndex(
                physicalAttempt = 2,
                candidateCount = 3,
            ),
        )
        assertEquals(
            2,
            NtkManhwaProjectedBodyHedgePolicy.projectedFirstCandidateIndex(
                physicalAttempt = 3,
                candidateCount = 3,
            ),
        )
    }

    @Test
    fun onlyTheSingleValidatedWaveRemainderCanUseTheFinalBodyTail() {
        val wave = NtkManhwaWaveRecoveryState(
            maximumPageCount = 3,
            viewerClickAtNanos = 1L,
        )
        wave.armExactAuthority(3)
        wave.markValidatedBody(0)
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartFinalBodyTail(
                wave,
                pageIndex = 2,
                sessionElapsedMs = 2_500L,
                bodyElapsedMs = 2_000L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            ),
        )
        wave.markValidatedBody(1)
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartFinalBodyTail(
                wave,
                pageIndex = 2,
                sessionElapsedMs = 2_500L,
                bodyElapsedMs = 2_000L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            ),
        )
        assertTrue(wave.tryClaimFinalTail(2))
        assertFalse(wave.tryClaimFinalTail(2))
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartFinalBodyTail(
                wave,
                pageIndex = 0,
                sessionElapsedMs = 10_000L,
                bodyElapsedMs = 9_000L,
                deliveredBytes = 100_000L,
                expectedLength = 300_000L,
            ),
        )
    }

    @Test
    fun closedWaveCannotLaunchOrAccumulateDuplicateCompletions() {
        val wave = NtkManhwaWaveRecoveryState(2, 1L)
        wave.armExactAuthority(2)
        wave.markValidatedBody(0)
        wave.markValidatedBody(0)
        wave.close()
        assertFalse(wave.isOnlyCanonicalBodyRemaining(1))
        assertFalse(wave.tryClaimFinalTail(1))
    }

    @Test
    fun stalledSuffixContinuesAtTheFirstUnacceptedByte() {
        val next = NtkManhwaRangeResumePolicy.nextStart(
            segmentStart = 134_603L,
            receivedBytes = 136_008L,
            segmentEnd = 292_847L,
        )

        assertEquals(270_611L, next)
        assertEquals(
            22_237L,
            NtkManhwaRangeResumePolicy.remainingLength(requireNotNull(next), 292_847L),
        )
    }

    @Test
    fun completedSuffixDoesNotOpenAnotherRange() {
        assertNull(
            NtkManhwaRangeResumePolicy.nextStart(
                segmentStart = 134_603L,
                receivedBytes = 158_245L,
                segmentEnd = 292_847L,
            )
        )
    }

    @Test
    fun suffixNoProgressBudgetDoesNotResetAHealthyCongestedStream() {
        assertEquals(3_000L, NtkManhwaRangeResumePolicy.BODY_IDLE_MS)
    }

    @Test
    fun projectedEntryViewportSuffixUsesShortExactResumeBound() {
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedBodyIdleMs(0))
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedBodyIdleMs(1))
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedBodyIdleMs(2))
        assertEquals(3_000L, NtkManhwaRangeResumePolicy.projectedBodyIdleMs(3))
        assertEquals(3_000L, NtkManhwaRangeResumePolicy.projectedBodyIdleMs(194))
    }

    @Test
    fun rateProjectionNeverAbandonsAStillReadablePrimaryBody() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()

        assertFalse(source.contains("shouldForceCriticalSerialResume"))
        assertFalse(source.contains("Critical projected manhwa tail"))
    }

}
