package ml.melun.mangaview.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExactHardwareTilePoolCompactionPolicyTest {

    @Test
    fun displayStoragePreservesAspectRatioWithoutKeepingUndisplayableSourcePixels() {
        assertEquals(800, HostExactDisplayStorageGeometry.contentWidth(1_403))
        assertEquals(1_168, HostExactDisplayStorageGeometry.contentHeight(1_403, 2_048))
        assertEquals(1_280, HostExactDisplayStorageGeometry.capacityHeight(1_403, 2_048))
        // A common single-tile manga page must reserve its actual scaled height rather than the
        // canonical 2048-row source-tile ceiling.
        assertEquals(1_168, HostExactDisplayStorageGeometry.contentHeight(850, 1_241))
        assertEquals(1_280, HostExactDisplayStorageGeometry.capacityHeight(850, 1_241))
        assertEquals(800, HostExactDisplayStorageGeometry.capacityWidth(4_096))
        assertEquals(400, HostExactDisplayStorageGeometry.contentWidth(400))
        assertEquals(2_048, HostExactDisplayStorageGeometry.contentHeight(400, 2_048))
        assertEquals(0, HostExactDisplayStorageGeometry.contentHeight(0, 2_048))
    }

    @Test
    fun pressureRetiredPixelsCannotRepublishUntilPhysicalReentryConsumesMarker() {
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.mayPublishDecodedPixels(
                pressureRetirementPending = true,
            ),
        )
        assertTrue(
            HostExactHardwareTilePoolPressurePolicy.mayPublishDecodedPixels(
                pressureRetirementPending = false,
            ),
        )
    }

    @Test
    fun farFutureAdjacentRunwayDecodeWaitsUntilThePhysicalAnchorIsNear() {
        assertTrue(
            HostExactHardwareTilePoolPressurePolicy.shouldDeferOffscreenAdjacentRunwayDecode(
                hostGpuRuntime = true,
                directWifiStrictAdjacent = true,
                predecessorIsLaunchEpisode = false,
                viewportAnchor = 17,
                adjacentStart = 28,
                nearBoundaryPages = 4,
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.shouldDeferOffscreenAdjacentRunwayDecode(
                hostGpuRuntime = true,
                directWifiStrictAdjacent = true,
                predecessorIsLaunchEpisode = false,
                viewportAnchor = 24,
                adjacentStart = 28,
                nearBoundaryPages = 4,
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.shouldDeferOffscreenAdjacentRunwayDecode(
                hostGpuRuntime = false,
                directWifiStrictAdjacent = true,
                predecessorIsLaunchEpisode = false,
                viewportAnchor = 17,
                adjacentStart = 28,
                nearBoundaryPages = 4,
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.shouldDeferOffscreenAdjacentRunwayDecode(
                hostGpuRuntime = true,
                directWifiStrictAdjacent = true,
                predecessorIsLaunchEpisode = true,
                viewportAnchor = 0,
                adjacentStart = 13,
                nearBoundaryPages = 4,
            ),
        )
    }

    @Test
    fun adjacentChapterWidthsCollapseIntoFourReusableCapacityClasses() {
        assertArrayEquals(
            intArrayOf(1_024, 1_536, 2_048, 2_048, 4_096, 4_096, 4_096),
            intArrayOf(1_024, 1_536, 1_792, 2_048, 2_816, 3_328, 4_096)
                .map(HostExactHardwareTileCapacityPolicy::capacityWidth)
                .toIntArray(),
        )
        assertTrue(HostExactHardwareTileCapacityPolicy.capacityWidth(4_097) >= 4_097)
    }

    @Test
    fun pressureRetirementRehydratesOnlyThePhysicalViewportNotTheDecodeRunway() {
        assertTrue(
            HostExactHardwareTilePoolPressurePolicy.isPhysicalRehydrateEligible(
                pageIndex = 21,
                physicalVisibleFirst = 20,
                physicalVisibleLast = 22,
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.isPhysicalRehydrateEligible(
                pageIndex = 25,
                physicalVisibleFirst = 20,
                physicalVisibleLast = 22,
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.isPhysicalRehydrateEligible(
                pageIndex = 20,
                physicalVisibleFirst = 22,
                physicalVisibleLast = 21,
            ),
        )
    }

    @Test
    fun pressureProtectionAddsOneInputNeighbourOnBothViewportEdges() {
        assertArrayEquals(
            intArrayOf(19, 23),
            HostExactHardwareTilePoolPressurePolicy.physicalViewportWithImmediateInputFrontier(
                pageCount = 40,
                physicalVisibleFirst = 20,
                physicalVisibleLast = 22,
                requestedFirst = 18,
                requestedLast = 28,
            ),
        )
        assertArrayEquals(
            intArrayOf(19, 23),
            HostExactHardwareTilePoolPressurePolicy.physicalViewportWithImmediateInputFrontier(
                pageCount = 40,
                physicalVisibleFirst = 20,
                physicalVisibleLast = 22,
                requestedFirst = 20,
                requestedLast = 22,
            ),
        )
        assertArrayEquals(
            intArrayOf(0, 2),
            HostExactHardwareTilePoolPressurePolicy.physicalViewportWithImmediateInputFrontier(
                pageCount = 40,
                physicalVisibleFirst = 0,
                physicalVisibleLast = 1,
                requestedFirst = 0,
                requestedLast = 1,
            ),
        )
    }

    @Test
    fun blockedForwardBodyCrossesOnlyOneTransitionCardForPhysicalRehydration() {
        assertArrayEquals(
            intArrayOf(11, 14),
            HostExactHardwareTilePoolPressurePolicy
                .physicalViewportIncludingBlockedForwardBody(
                    pageCount = 30,
                    physicalVisibleFirst = 11,
                    physicalVisibleLast = 12,
                    blockedForwardPage = 14,
                ),
        )
        assertArrayEquals(
            intArrayOf(11, 12),
            HostExactHardwareTilePoolPressurePolicy
                .physicalViewportIncludingBlockedForwardBody(
                    pageCount = 30,
                    physicalVisibleFirst = 11,
                    physicalVisibleLast = 12,
                    blockedForwardPage = 18,
                ),
        )
    }

    @Test
    fun observedFragmentationReclaimsOnlyEnoughIdleStorageForTheWiderTile() {
        val mib = 1024L * 1024L

        assertArrayEquals(
            intArrayOf(0),
            HostExactHardwareTilePoolCompactionPolicy.idleVictimIndexes(
                slotBytes = longArrayOf(8L * mib, 8L * mib, 12L * mib, 22L * mib),
                slotInUse = booleanArrayOf(false, false, true, true),
                allocatedBytes = 248L * mib,
                requiredBytes = 14L * mib,
                maxBytes = 256L * mib,
            ),
        )
    }

    @Test
    fun activeSlotsAreNeverSelected() {
        val mib = 1024L * 1024L

        assertArrayEquals(
            intArrayOf(1, 2),
            HostExactHardwareTilePoolCompactionPolicy.idleVictimIndexes(
                slotBytes = longArrayOf(64L * mib, 4L * mib, 8L * mib),
                slotInUse = booleanArrayOf(true, false, false),
                allocatedBytes = 252L * mib,
                requiredBytes = 16L * mib,
                maxBytes = 256L * mib,
            ),
        )
    }

    @Test
    fun incompleteIdleCapacityWaitsInsteadOfDestroyingStorage() {
        val mib = 1024L * 1024L

        assertArrayEquals(
            IntArray(0),
            HostExactHardwareTilePoolCompactionPolicy.idleVictimIndexes(
                slotBytes = longArrayOf(8L * mib, 8L * mib),
                slotInUse = booleanArrayOf(false, true),
                allocatedBytes = 256L * mib,
                requiredBytes = 14L * mib,
                maxBytes = 256L * mib,
            ),
        )
    }

    @Test
    fun existingHeadroomNeedsNoCompaction() {
        val mib = 1024L * 1024L

        assertArrayEquals(
            IntArray(0),
            HostExactHardwareTilePoolCompactionPolicy.idleVictimIndexes(
                slotBytes = longArrayOf(8L * mib),
                slotInUse = booleanArrayOf(false),
                allocatedBytes = 220L * mib,
                requiredBytes = 14L * mib,
                maxBytes = 256L * mib,
            ),
        )
    }

    @Test
    fun pressureWindowKeepsThePhysicalPredecessorAndFourForwardPages() {
        assertArrayEquals(
            intArrayOf(6, 11),
            HostExactHardwareTilePoolPressurePolicy.retainedWindow(
                pageCount = 20,
                anchor = 7,
            ),
        )
        assertArrayEquals(
            intArrayOf(0, 4),
            HostExactHardwareTilePoolPressurePolicy.retainedWindow(
                pageCount = 20,
                anchor = 0,
            ),
        )
        assertArrayEquals(
            intArrayOf(18, 19),
            HostExactHardwareTilePoolPressurePolicy.retainedWindow(
                pageCount = 20,
                anchor = 19,
            ),
        )
    }

    @Test
    fun pressureWindowCannotDropThePhysicalViewportWhenLayoutAnchorMovesAhead() {
        assertArrayEquals(
            intArrayOf(46, 55),
            HostExactHardwareTilePoolPressurePolicy.retainedWindowIncludingPhysicalViewport(
                pageCount = 66,
                anchor = 51,
                physicalVisibleFirst = 46,
                physicalVisibleLast = 47,
            ),
        )
        assertArrayEquals(
            intArrayOf(45, 50),
            HostExactHardwareTilePoolPressurePolicy.retainedWindowIncludingPhysicalViewport(
                pageCount = 66,
                anchor = 46,
                physicalVisibleFirst = 46,
                physicalVisibleLast = 47,
            ),
        )
    }

    @Test
    fun allocationPressureRetiresOnlyOffscreenPixelsInReadingPriorityOrder() {
        assertArrayEquals(
            intArrayOf(60, 65, 64, 63),
            HostExactHardwareTilePoolPressurePolicy.retirementOrder(
                candidateIndexes = intArrayOf(60, 61, 62, 63, 64, 65, 65),
                visibleFirst = 61,
                visibleLast = 62,
                directionHint = 1,
            ),
        )
        assertArrayEquals(
            intArrayOf(65, 59, 60),
            HostExactHardwareTilePoolPressurePolicy.retirementOrder(
                candidateIndexes = intArrayOf(59, 60, 61, 62, 65),
                visibleFirst = 61,
                visibleLast = 62,
                directionHint = -1,
            ),
        )
    }

    @Test
    fun tailDecodePressureMayRetireOnlyTheAlreadyPublishedStablePrefix() {
        assertTrue(
            HostExactHardwareTilePoolPressurePolicy.canPublishStablePrefixRetirement(
                pendingStructurePublishes = 1,
                appendOnlyStablePrefixCounts = intArrayOf(46),
                targetIndexes = intArrayOf(41, 42),
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.canPublishStablePrefixRetirement(
                pendingStructurePublishes = 1,
                appendOnlyStablePrefixCounts = intArrayOf(46),
                targetIndexes = intArrayOf(46),
            ),
        )
    }

    @Test
    fun anyOverlappingNonAppendStructureOwnerKeepsPressureClearDeferred() {
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.canPublishStablePrefixRetirement(
                pendingStructurePublishes = 2,
                appendOnlyStablePrefixCounts = intArrayOf(46),
                targetIndexes = intArrayOf(41),
            ),
        )
        assertFalse(
            HostExactHardwareTilePoolPressurePolicy.canPublishStablePrefixRetirement(
                pendingStructurePublishes = 1,
                appendOnlyStablePrefixCounts = intArrayOf(46),
                targetIndexes = intArrayOf(-1),
            ),
        )
    }
}
