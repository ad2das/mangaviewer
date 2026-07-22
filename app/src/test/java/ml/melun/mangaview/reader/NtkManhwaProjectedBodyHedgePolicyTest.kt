package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkManhwaProjectedBodyHedgePolicyTest {
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
    fun measuredSlowDripMovesOnlyItsUntouchedSuffix() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldResume(
                sessionElapsedMs = 2_100L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 74_407L,
                expectedLength = 301_162L,
            )
        )
    }

    @Test
    fun lateAdmissionIsPartOfTheProjection() {
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldResume(
                sessionElapsedMs = 2_500L,
                bodyElapsedMs = 1_000L,
                deliveredBytes = 150_000L,
                expectedLength = 300_000L,
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
    fun suffixNoProgressBudgetIsBoundedBelowTheMeasuredFiveSecondTail() {
        assertEquals(1_000L, NtkManhwaRangeResumePolicy.BODY_IDLE_MS)
    }

    @Test
    fun r129CriticalSerialCutoverOnlySelectsProjectedFiveSecondTails() {
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldForceCriticalSerialResume(null)
        )
        assertFalse(
            NtkManhwaProjectedBodyHedgePolicy.shouldForceCriticalSerialResume(5_000.0)
        )
        assertTrue(
            NtkManhwaProjectedBodyHedgePolicy.shouldForceCriticalSerialResume(5_027.0)
        )
    }

}
