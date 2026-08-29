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
        assertEquals(1_000L, NtkManhwaProjectedBodyHedgePolicy.BODY_RATE_SAMPLE_MS)
        assertEquals(100L, NtkManhwaProjectedBodyHedgePolicy.bodyRateSampleMs(0))
        assertEquals(1_000L, NtkManhwaProjectedBodyHedgePolicy.bodyRateSampleMs(1))
        assertEquals(
            500L,
            NtkManhwaProjectedBodyHedgePolicy.bodyProgressDeadlineMs(0, 2_500L),
        )
        assertEquals(
            2_500L,
            NtkManhwaProjectedBodyHedgePolicy.bodyProgressDeadlineMs(1, 2_500L),
        )
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
    fun entryViewportRecoveryIncludesOpeningPageButNeverExpandsIntoTheBulkWave() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartEntryViewportTail(
                pageIndex = 0,
                sessionElapsedMs = 100L,
                bodyElapsedMs = 100L,
                deliveredBytes = 2_000L,
                expectedLength = 180_000L,
            )
        )
        assertEquals(0L, NtkManhwaProjectedBodyHedgePolicy.OPENING_PAGE_MIN_SESSION_MS)
        assertEquals(12, NtkManhwaProjectedBodyHedgePolicy.OPENING_PAGE_MAX_CONTINUATIONS)
        assertEquals(6, NtkManhwaProjectedBodyHedgePolicy.ENTRY_VIEWPORT_MAX_CONTINUATIONS)
        assertEquals(12, NtkManhwaProjectedBodyHedgePolicy.entryViewportMaximumContinuations(0))
        assertEquals(6, NtkManhwaProjectedBodyHedgePolicy.entryViewportMaximumContinuations(1))
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
    fun failedEntryViewportSuffixCanRetryOnlyInsideItsFiniteContinuationBudget() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldRearmEntryViewportTailAfterFailure(
                pageIndex = 0,
                continuationCount = 1,
                maximumContinuations = 4,
            )
        )
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldRearmEntryViewportTailAfterFailure(
                pageIndex = 1,
                continuationCount = 1,
                maximumContinuations = 4,
            )
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldRearmEntryViewportTailAfterFailure(
                pageIndex = 1,
                continuationCount = 4,
                maximumContinuations = 4,
            )
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldRearmEntryViewportTailAfterFailure(
                pageIndex = 2,
                continuationCount = 1,
                maximumContinuations = 4,
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
    fun openingPageLeadCoversBufferedReadAheadWithoutCreatingATinyGapRequest() {
        val delivered = 16_384L
        val split = requireNotNull(
            NtkManhwaProjectedBodyHedgePolicy.disjointTailStart(
                bodyElapsedMs = 100L,
                deliveredBytes = delivered,
                expectedLength = 3_117_748L,
                pageIndex = 0,
            )
        )
        assertEquals(delivered + 16L * 1024L, split)
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
    fun largeEntryViewportTailUsesBoundedBalancedDisjointMirrorSegments() {
        val start = 147_402L
        val expectedLength = 2_115_344L
        val segments = NtkManhwaProjectedBodyHedgePolicy
            .disjointEntryViewportTailSegments(start, expectedLength, 3)

        assertEquals(3, segments.size)
        assertEquals(start, segments.first().first)
        assertEquals(expectedLength - 1L, segments.last().last)
        assertEquals(expectedLength - start, segments.sumOf { it.last - it.first + 1L })
        segments.zipWithNext().forEach { (left, right) ->
            assertEquals(left.last + 1L, right.first)
        }
        assertTrue(segments.maxOf { it.last - it.first + 1L } -
            segments.minOf { it.last - it.first + 1L } <= 1L)
    }

    @Test
    fun openingPageUsesTheExistingGlobalRangeWorkerCeilingForSmallPieces() {
        val start = 30_043L
        val expectedLength = 3_117_748L
        val segments = NtkManhwaProjectedBodyHedgePolicy
            .disjointEntryViewportTailSegments(
                start,
                expectedLength,
                NtkManhwaProjectedBodyHedgePolicy.entryViewportMaximumContinuations(0),
                pageIndex = 0,
            )

        assertEquals(12, segments.size)
        assertEquals(start, segments.first().first)
        assertEquals(expectedLength - 1L, segments.last().last)
        assertTrue(segments.maxOf { it.last - it.first + 1L } <= 258_000L)
        assertEquals(expectedLength - start, segments.sumOf { it.last - it.first + 1L })
    }

    @Test
    fun projectedPrefixAlwaysRetainsOnePhysicalSlotForItsExactGap() {
        assertEquals(
            11,
            NtkManhwaProjectedBodyHedgePolicy.projectedTailSegmentSlots(
                remainingContinuationSlots = 12,
                finalBodyTail = false,
                directWifiWebtoonTail = false,
            ),
        )
        assertEquals(
            12,
            NtkManhwaProjectedBodyHedgePolicy.projectedTailSegmentSlots(
                remainingContinuationSlots = 12,
                finalBodyTail = true,
                directWifiWebtoonTail = false,
            ),
        )
        assertEquals(
            12,
            NtkManhwaProjectedBodyHedgePolicy.projectedTailSegmentSlots(
                remainingContinuationSlots = 12,
                finalBodyTail = false,
                directWifiWebtoonTail = true,
            ),
        )
    }

    @Test
    fun smallEntryViewportTailKeepsOneExactRange() {
        assertEquals(
            listOf(200_000L..499_999L),
            NtkManhwaProjectedBodyHedgePolicy.disjointEntryViewportTailSegments(
                start = 200_000L,
                expectedLength = 500_000L,
                maximumSegments = 3,
            ),
        )
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
    fun physicallyBlockedBodyCanUseTheSameBoundedUntouchedSuffixHedgeBeforeWaveEnd() {
        val wave = NtkManhwaWaveRecoveryState(
            maximumPageCount = 13,
            viewerClickAtNanos = 1L,
        )
        wave.armExactAuthority(13)
        wave.markValidatedBody(0)
        wave.markPhysicalBlockedBody(12)

        assertTrue(wave.isPhysicalBlockedBody(12))
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldStartFinalBodyTail(
                wave,
                pageIndex = 12,
                sessionElapsedMs = 8_000L,
                bodyElapsedMs = 6_000L,
                deliveredBytes = 196_608L,
                expectedLength = 6_041_886L,
            ),
        )
        assertFalse(wave.tryClaimFinalTail(12))
        assertTrue(wave.tryClaimPhysicalBlockedTail(12))
        assertFalse(wave.tryClaimPhysicalBlockedTail(12))
        wave.releaseUnstartedPhysicalBlockedTailClaim(12)
        assertTrue(wave.tryClaimPhysicalBlockedTail(12))
        wave.markValidatedBody(12)
        assertFalse(wave.isPhysicalBlockedBody(12))
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
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(0))
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(1))
        assertEquals(900L, NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(2))
        assertEquals(
            3_600L,
            NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(
                0,
                bufferedResponseBody = true,
            ),
        )
        assertEquals(
            3_600L,
            NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(
                1,
                bufferedResponseBody = true,
            ),
        )
        assertEquals(3_000L, NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(3))
        assertEquals(3_000L, NtkManhwaRangeResumePolicy.projectedHeaderDeadlineMs(194))
        assertEquals(3_600L, NtkManhwaRangeResumePolicy.projectedBodyWallMs(0))
        assertEquals(3_600L, NtkManhwaRangeResumePolicy.projectedBodyWallMs(1))
        assertNull(NtkManhwaRangeResumePolicy.projectedBodyWallMs(2))
        assertNull(NtkManhwaRangeResumePolicy.projectedBodyWallMs(194))
        assertEquals(4, NtkManhwaRangeResumePolicy.maximumProgressRounds(0))
        assertEquals(4, NtkManhwaRangeResumePolicy.maximumProgressRounds(1))
        assertEquals(1, NtkManhwaRangeResumePolicy.maximumProgressRounds(2))
        assertEquals(1, NtkManhwaRangeResumePolicy.maximumProgressRounds(194))
    }

    @Test
    fun rateProjectionNeverAbandonsAStillReadablePrimaryBody() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()

        assertFalse(source.contains("shouldForceCriticalSerialResume"))
        assertFalse(source.contains("Critical projected manhwa tail"))
    }

    @Test
    fun validatedDisjointRangeDoesNotInheritTheCanonicalH2OnlyMarker() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt"
        ).readText()
        val start = source.indexOf("private fun executeManhwaRangeSegment(")
        val end = source.indexOf("private fun isLiveDirectWifiAdjacentProofRoute()", start)
        assertTrue(start >= 0)
        assertTrue(end > start)

        val rangeTransport = source.substring(start, end)
        assertTrue(rangeTransport.contains(".removeHeader(\"X-MangaViewer-No-Quic\")"))
        assertTrue(rangeTransport.contains("response.code == 206"))
        assertTrue(rangeTransport.contains("strictStrongValidator(response) == validator"))
        assertTrue(rangeTransport.contains("range.third == total"))

        val serialStart = source.indexOf("private fun continueFromNextByte(")
        val serialEnd = source.indexOf("private fun hasRequiredDirectWifiNetwork()", serialStart)
        assertTrue(serialStart >= 0)
        assertTrue(serialEnd > serialStart)
        val serialTransport = source.substring(serialStart, serialEnd)
        assertTrue(serialTransport.contains(".removeHeader(\"X-MangaViewer-No-Quic\")"))
        assertTrue(serialTransport.contains("range.third == expectedLength"))
    }

}
