package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictInitialWavePolicyTest {

    @Test
    fun routeAdmissionUsesTheBoundedColdProducerWindow() {
        assertEquals(40, NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS_PER_ROUTE)
        assertTrue(NtkStrictSourceSchedulerPolicy.hasRouteCapacity(0))
        assertTrue(NtkStrictSourceSchedulerPolicy.hasRouteCapacity(39))
        assertFalse(NtkStrictSourceSchedulerPolicy.hasRouteCapacity(40))
        assertEquals(40, NtkSourceLanePolicy.MAX_MANHWA_NETWORK_OPERATIONS_PER_ROUTE)
        assertTrue(NtkStrictSourceSchedulerPolicy.hasRouteCapacity(39, 40))
        assertFalse(NtkStrictSourceSchedulerPolicy.hasRouteCapacity(40, 40))
    }

    @Test
    fun forwardResumeNeverPreparesRoutesBehindTheSavedSourceFloor() {
        assertFalse(
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = 66,
                pageCount = 112,
                initialPageIndex = 67,
                adjacentPrefetch = false,
                adjacentPrefetchReleased = false,
                forwardResume = true,
            )
        )
        assertTrue(
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = 67,
                pageCount = 112,
                initialPageIndex = 67,
                adjacentPrefetch = false,
                adjacentPrefetchReleased = false,
                forwardResume = true,
            )
        )
        assertTrue(
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = 0,
                pageCount = 112,
                initialPageIndex = 67,
                adjacentPrefetch = false,
                adjacentPrefetchReleased = false,
            )
        )
    }

    @Test
    fun initialWaveProofUsesReachablePerRouteCapacity() {
        assertEquals(
            27,
            NtkStrictInitialWavePolicy.routeBoundSubmissionTarget(
                List(27) { "single-manga-origin" },
                NtkSourceLanePolicy.MAX_MANHWA_NETWORK_OPERATIONS_PER_ROUTE,
            ),
        )
        assertEquals(
            114,
            NtkStrictInitialWavePolicy.routeBoundSubmissionTarget(
                List(114) { index -> "webtoon-cdn-${index % 3}" },
                NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS_PER_ROUTE,
            ),
        )
    }

    @Test
    fun webtoonColdLeadersCoverEightBalancedPoolsPerOrigin() {
        val leaders = NtkStrictInitialWavePolicy.coldConnectionCohortLeaders(
            episodePath = "/webtoon/work/episode",
            admittedPageIndexes = (0 until 114).toSet(),
            routeBucketForPage = { page -> "cdn-${if (page <= 1) 0 else page % 3}" },
        )

        assertEquals(24, leaders.size)
        assertEquals(24, leaders.map { page ->
            "cdn-${if (page <= 1) 0 else page % 3}#" +
                NtkStrictInitialWavePolicy.webtoonHostLocalShardIndex(page, 8)
        }.toSet().size)
        assertEquals(listOf(0, 1, 2), leaders.take(3))
    }

    @Test
    fun directWifiWebtoonColdLeadersCoverSixteenBalancedPoolsPerOrigin() {
        val shardCount = NtkStrictInitialWavePolicy.webtoonConnectionShardCount(
            directWifiTransport = true,
        )
        val leaders = NtkStrictInitialWavePolicy.coldConnectionCohortLeaders(
            episodePath = "/webtoon/work/episode",
            admittedPageIndexes = (0 until 193).toSet(),
            webtoonShardCount = shardCount,
            routeBucketForPage = { page -> "cdn-${if (page <= 1) 0 else page % 3}" },
        )

        assertEquals(16, shardCount)
        assertEquals(48, leaders.size)
        assertEquals(48, leaders.map { page ->
            "cdn-${if (page <= 1) 0 else page % 3}#" +
                NtkStrictInitialWavePolicy.webtoonHostLocalShardIndex(page, shardCount)
        }.toSet().size)
        assertEquals(8, NtkStrictInitialWavePolicy.webtoonConnectionShardCount(false))
    }

    @Test
    fun cellularWebtoonOpensOneLeaderForEveryFiniteColdCohort() {
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 24,
                cellularResilientTransport = true,
            ),
        )
        assertEquals(
            3,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 24,
                cellularResilientTransport = false,
            ),
        )
        assertEquals(
            2,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 2,
                cellularResilientTransport = true,
            ),
        )
    }

    @Test
    fun largeWifiWebtoonStartsHalfOfTheFiniteConnectionCohorts() {
        assertEquals(
            12,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 24,
                cellularResilientTransport = false,
                episodePageCount = 140,
                directWifiTransport = true,
            ),
        )
        assertEquals(
            3,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 24,
                cellularResilientTransport = false,
                episodePageCount = 139,
                directWifiTransport = true,
            ),
        )
        // A non-cellular transport is not necessarily direct Wi-Fi (for example, VPN).
        assertEquals(
            3,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 24,
                cellularResilientTransport = false,
                episodePageCount = 140,
                directWifiTransport = false,
            ),
        )
        // Carrier retains the pre-existing finite-cohort wave.
        assertEquals(
            8,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 8,
                cellularResilientTransport = true,
                episodePageCount = 140,
            ),
        )
    }

    @Test
    fun directWifiAdjacentWebtoonStartsItsExactFourBodyRunwayTogether() {
        assertEquals(
            4,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 4,
                cellularResilientTransport = false,
                episodePageCount = 8,
                directWifiTransport = true,
                adjacentPrefetch = true,
            ),
        )
        // Current/ordinary direct-Wi-Fi scenes retain the measured three-body entry gate.
        assertEquals(
            3,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 4,
                cellularResilientTransport = false,
                episodePageCount = 8,
                directWifiTransport = true,
                adjacentPrefetch = false,
            ),
        )
        // Carrier/SNI retains its independent finite-cohort wave.
        assertEquals(
            4,
            NtkStrictInitialWavePolicy.webtoonPreAnchorGateOperations(
                cohortCount = 4,
                cellularResilientTransport = true,
                episodePageCount = 8,
                directWifiTransport = false,
                adjacentPrefetch = true,
            ),
        )
    }

    @Test
    fun manhwaColdLeadersCoverTwentyFourActualConnectionShards() {
        val leaders = NtkStrictInitialWavePolicy.coldConnectionCohortLeaders(
            episodePath = "/manhwa/work/episode",
            admittedPageIndexes = (0 until 88).toSet(),
            routeBucketForPage = { "single-cdn" },
        )

        assertEquals(24, leaders.size)
        assertEquals(24, leaders.map { page ->
            NtkStrictInitialWavePolicy.exactImageShardIndex(page, 24)
        }.toSet().size)
    }

    @Test
    fun multiHostManhwaColdLeadersRemainBoundedByActiveTransfers() {
        val leaders = NtkStrictInitialWavePolicy.coldConnectionCohortLeaders(
            episodePath = "/manhwa/work/episode",
            admittedPageIndexes = (0 until 35).toSet(),
            maximumLeaders = NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
            routeBucketForPage = { page -> "cdn-${page % 3}" },
        )

        val uniqueCohorts = (0 until 35).map { page ->
            NtkStrictInitialWavePolicy.coldConnectionCohortKey(
                "/manhwa/work/episode",
                page,
                "cdn-${page % 3}",
            )
        }.distinct()
        assertEquals(
            minOf(
                uniqueCohorts.size,
                NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
            ),
            leaders.size,
        )
        assertTrue(
            leaders.size <= NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS
        )
        assertEquals(0, leaders.first())
        assertEquals(leaders.sorted(), leaders)
    }

    @Test
    fun settledColdCohortsReleaseOnlyTheirBoundedLaneShare() {
        assertEquals(6, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 6, 0))
        assertEquals(15, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 6, 1))
        assertEquals(24, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 6, 2))
        assertEquals(51, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 6, 5))
        assertEquals(60, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 6, 6))
        assertEquals(18, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 18, 0))
        assertEquals(20, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 18, 1))
        assertEquals(36, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 18, 9))
        assertEquals(52, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 18, 17))
        assertEquals(60, NtkStrictInitialWavePolicy.progressiveLaneTarget(60, 18, 18))
    }

    @Test
    fun publishedAnchorReleasesTheCompleteForwardPhysicalRing() {
        assertEquals(
            6,
            NtkStrictInitialWavePolicy.forwardLaneTarget(6, 24, 2, true),
        )
        assertEquals(
            18,
            NtkStrictInitialWavePolicy.forwardLaneTarget(120, 18, 0, false),
        )
        assertEquals(
            120,
            NtkStrictInitialWavePolicy.forwardLaneTarget(120, 18, 1, true),
        )
        assertEquals(
            120,
            NtkStrictInitialWavePolicy.forwardLaneTarget(120, 18, 18, true),
        )
    }

    @Test
    fun webtoonAdmissionReservesTheAnchorPoolUntilItsBodyIsSafe() {
        assertEquals(
            43,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                false,
            ),
        )
        assertEquals(
            91,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/direct-wifi",
                120,
                false,
                webtoonConnectionShardCount = 16,
            ),
        )
        assertEquals(
            4,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
            ),
        )
        assertEquals(
            80,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
                cellularResilientTransport = true,
            ),
        )
        assertEquals(
            43,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                48,
                false,
            ),
        )
        assertEquals(
            NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                120,
                false,
            ),
        )
        assertEquals(
            12,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                12,
                true,
            ),
        )
        assertEquals(
            32,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                35,
                false,
                manhwaTransferLimit = 32,
            ),
        )
        assertEquals(
            60,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                120,
                true,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            80,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                120,
                true,
                wifiQuicBulkTransport = true,
                episodePageCount = 193,
            ),
        )
        assertEquals(
            NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/episode",
                120,
                true,
                cellularResilientTransport = true,
                wifiQuicBulkTransport = true,
                episodePageCount = 193,
            ),
        )
        assertEquals(
            4,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/adjacent",
                120,
                true,
                wifiQuicBulkTransport = true,
                episodePageCount = 193,
                adjacentPrefetch = true,
            ),
        )
        assertEquals(
            NtkClickOwnedManhwaWavePolicy.ACTIVE_BODY_TRANSFERS,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/manhwa/work/adjacent",
                120,
                true,
                cellularResilientTransport = true,
                wifiQuicBulkTransport = true,
                episodePageCount = 193,
                adjacentPrefetch = true,
            ),
        )
    }

    @Test
    fun healthyWifiAnchorReleasesOneTransferPerColdCohortWithoutChangingCarrierCapacity() {
        assertEquals(4, NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(0))
        assertEquals(
            4,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                0,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(48, NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(1))
        assertEquals(48, NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(3))
        assertEquals(48, NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(124))
        assertEquals(
            60,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                publishedBodyCount = 1,
                episodePageCount = 91,
                webtoonConnectionShardCount = 16,
            ),
        )
        assertEquals(
            64,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                publishedBodyCount = 1,
                episodePageCount = 193,
                webtoonConnectionShardCount = 16,
            ),
        )
        assertEquals(
            48,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                publishedBodyCount = 1,
                episodePageCount = 79,
                webtoonConnectionShardCount = 16,
            ),
        )
        assertEquals(
            48,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                publishedBodyCount = 1,
                episodePageCount = 193,
                webtoonConnectionShardCount = 8,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                1,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                1,
                wifiQuicBulkTransport = true,
                episodePageCount = 143,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                8,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                20,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                36,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.webtoonWifiPostAnchorBodyTransfers(
                36,
                wifiQuicBulkTransport = true,
                episodePageCount = 143,
            ),
        )
        assertEquals(
            48,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
                webtoonPublishedBodyCount = 1,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
                webtoonPublishedBodyCount = 1,
                wifiQuicBulkTransport = true,
            ),
        )
        assertEquals(
            24,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
                webtoonPublishedBodyCount = 1,
                wifiQuicBulkTransport = true,
                episodePageCount = 307,
            ),
        )
        assertEquals(
            80,
            NtkStrictInitialWavePolicy.usefulPhysicalLaneCount(
                "/webtoon/work/episode",
                120,
                true,
                cellularResilientTransport = true,
                webtoonPublishedBodyCount = 124,
                episodePageCount = 307,
            ),
        )
    }

    @Test
    fun publishedAnchorReleasesFollowersInEverySealedColdCohort() {
        assertFalse(
            NtkStrictInitialWavePolicy.cohortFollowerEligible(
                cohortsOpen = false,
                isLeader = false,
                cohortSettled = false,
                anchorBodyPublished = false,
                firstSettledAtMs = 1_000L,
                nowMs = 1_349L,
                unsettledFollowerGraceMs = 350L,
            ),
        )
        assertTrue(
            NtkStrictInitialWavePolicy.cohortFollowerEligible(
                cohortsOpen = false,
                isLeader = false,
                cohortSettled = false,
                anchorBodyPublished = true,
                firstSettledAtMs = 0L,
                nowMs = 1_000L,
                unsettledFollowerGraceMs = 350L,
            ),
        )
    }

    @Test
    fun cohortKeyMatchesThePhysicalHostAndShardIdentity() {
        val webtoonKeys = (0 until 114).map { page ->
            NtkStrictInitialWavePolicy.coldConnectionCohortKey(
                "/webtoon/work/episode",
                page,
                "cdn-${if (page <= 1) 0 else page % 3}",
            )
        }.toSet()
        val manhwaKeys = (0 until 88).map { page ->
            NtkStrictInitialWavePolicy.coldConnectionCohortKey(
                "/manhwa/work/episode",
                page,
                "single-cdn",
            )
        }.toSet()

        assertEquals(24, webtoonKeys.size)
        assertEquals(24, manhwaKeys.size)
    }

    @Test
    fun coldRollingAdmissionImmediatelyCoversTheCompleteForwardWorkset() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 20,
            initialPageIndex = 0,
            rollingAdmission = true,
        )

        assertEquals((0 until 20).toSet(), admitted)
        assertEquals(
            20,
            NtkStrictInitialWavePolicy.submissionTarget(admitted.size),
        )
    }

    @Test
    fun directWifiAdjacentAdmissionKeepsOnlyAtomicRunwayUntilViewportActivation() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 78,
            initialPageIndex = 0,
            rollingAdmission = true,
            adjacentPrefetch = true,
        )

        assertEquals((0 until 4).toSet(), admitted)
        assertEquals(4, NtkStrictInitialWavePolicy.submissionTarget(admitted.size))
    }

    @Test
    fun directWifiAdjacentRoutePreparationKeepsOnlyForwardRunwayUntilViewportRelease() {
        val held = (0 until 12).filter { pageIndex ->
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = pageIndex,
                pageCount = 12,
                initialPageIndex = 2,
                adjacentPrefetch = true,
                adjacentPrefetchReleased = false,
            )
        }
        val released = (0 until 12).filter { pageIndex ->
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = pageIndex,
                pageCount = 12,
                initialPageIndex = 2,
                adjacentPrefetch = true,
                adjacentPrefetchReleased = true,
            )
        }
        val ordinary = (0 until 12).filter { pageIndex ->
            NtkStrictInitialWavePolicy.isRoutePreparationAdmitted(
                pageIndex = pageIndex,
                pageCount = 12,
                initialPageIndex = 2,
                adjacentPrefetch = false,
                adjacentPrefetchReleased = false,
            )
        }

        assertEquals((2 until 6).toList(), held)
        assertEquals((2 until 12).toList(), released)
        assertEquals((0 until 12).toList(), ordinary)
    }

    @Test
    fun twoHundredSeventyPageEpisodeKeepsCompleteAuthorityWithBoundedConcurrency() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 270,
            initialPageIndex = 0,
            rollingAdmission = true,
        )

        assertEquals(270, admitted.size)
        assertEquals((0 until 270).toSet(), admitted)
        assertEquals(
            NtkSourceLanePolicy.MAX_NETWORK_OPERATIONS,
            NtkStrictInitialWavePolicy.submissionTarget(admitted.size),
        )
    }
    @Test
    fun rollingColdAdmissionCoversAnchorAndTwoPageRunway() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 34,
            initialPageIndex = 0,
            rollingAdmission = true
        )

        assertEquals((0 until 34).toSet(), admitted)
        assertEquals(
            34,
            NtkStrictInitialWavePolicy.submissionTarget(admitted.size)
        )
    }

    @Test
    fun onePageRollingEpisodeHasOneNonDuplicatedInitialSubmission() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 1,
            initialPageIndex = 0,
            rollingAdmission = true
        )

        assertEquals(setOf(0), admitted)
        assertEquals(1, NtkStrictInitialWavePolicy.submissionTarget(admitted.size))
    }

    @Test
    fun rollingAdmissionAtLastPageDoesNotSpendColdLanesBehindTheReader() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 34,
            initialPageIndex = 33,
            rollingAdmission = true
        )

        assertEquals(setOf(33), admitted)
        assertEquals(1, NtkStrictInitialWavePolicy.submissionTarget(admitted.size))
    }

    @Test
    fun nonRollingAdmissionStillFillsOnlyPhysicalLaneCapacity() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 34,
            initialPageIndex = 0,
            rollingAdmission = false
        )

        assertEquals((0 until 34).toSet(), admitted)
        assertEquals(
            34,
            NtkStrictInitialWavePolicy.submissionTarget(admitted.size)
        )
    }

    @Test
    fun rollingAdmissionMovesPastClickOwnedViewportBodiesWithoutDuplicatingThem() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 30,
            initialPageIndex = 0,
            rollingAdmission = true,
            alreadyPublishedPageIndexes = setOf(0, 1, 2),
        )

        assertEquals((3 until 30).toSet(), admitted)
        assertEquals(
            admitted.size,
            NtkStrictInitialWavePolicy.submissionTarget(admitted.size)
        )
    }

    @Test
    fun nonRollingAdmissionExcludesAlreadyPublishedBodiesFromItsPhysicalWave() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 10,
            initialPageIndex = 0,
            rollingAdmission = false,
            alreadyPublishedPageIndexes = setOf(0, 1),
        )

        assertEquals((2 until 10).toSet(), admitted)
        assertEquals(8, NtkStrictInitialWavePolicy.submissionTarget(admitted.size))
    }

    @Test
    fun fullyPublishedEpisodeNeedsNoDuplicatePhysicalWave() {
        val admitted = NtkStrictInitialWavePolicy.admittedPageIndexes(
            pageCount = 10,
            initialPageIndex = 0,
            rollingAdmission = true,
            alreadyPublishedPageIndexes = (0 until 10).toSet(),
        )

        assertTrue(admitted.isEmpty())
        assertEquals(0, NtkStrictInitialWavePolicy.submissionTarget(admitted.size))
        assertEquals(
            0,
            NtkStrictInitialWavePolicy.routeBoundSubmissionTarget(
                emptyList(),
                NtkSourceLanePolicy.MAX_MANHWA_NETWORK_OPERATIONS_PER_ROUTE,
            ),
        )
    }
}
