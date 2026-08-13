package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkStrictSourceSchedulerPolicyTest {
    @Test
    fun healthyColdLeaderPrecedesWarmAffinityFollowerWithinTargetBand() {
        val candidates = listOf(
            candidate(page = 96, route = "cold-9", priority = 100),
            candidate(page = 101, route = "warm-4", priority = 100),
        )

        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            candidates,
            currentRouteBucket = "warm-4",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(96, selected?.candidate?.pageIndex)
        assertEquals("cold-9", selected?.routeBucket)
    }

    @Test
    fun urgentFollowerStillPrecedesTargetColdLeader() {
        val candidates = listOf(
            candidate(
                page = 96,
                route = "cold-9",
                priority = 100,
                lane = NtkSourceOperationLane.TARGET,
            ),
            candidate(
                page = 101,
                route = "warm-4",
                priority = 100,
                lane = NtkSourceOperationLane.URGENT,
            ),
        )

        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            candidates,
            currentRouteBucket = "cold-9",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    @Test
    fun stageFollowerStillPrecedesTargetColdLeader() {
        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            listOf(
                candidate(
                    page = 96,
                    route = "cold-9",
                    priority = 100,
                    lane = NtkSourceOperationLane.TARGET,
                ),
                candidate(
                    page = 101,
                    route = "warm-4",
                    priority = 1,
                    lane = NtkSourceOperationLane.STAGE,
                ),
            ),
            currentRouteBucket = "cold-9",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    @Test
    fun targetFollowerStillPrecedesPreferredBackgroundLeader() {
        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            listOf(
                candidate(
                    page = 96,
                    route = "cold-9",
                    priority = 100,
                    lane = NtkSourceOperationLane.BACKGROUND_PROOF,
                ),
                candidate(
                    page = 101,
                    route = "warm-4",
                    priority = 1,
                    lane = NtkSourceOperationLane.TARGET,
                ),
            ),
            currentRouteBucket = "cold-9",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    @Test
    fun preferredLeaderOutsideHighestBandDoesNotBreakAffinity() {
        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            listOf(
                candidate(
                    page = 96,
                    route = "cold-9",
                    priority = 100,
                    lane = NtkSourceOperationLane.BACKGROUND_PROOF,
                ),
                candidate(page = 101, route = "warm-4", priority = 10),
                candidate(page = 102, route = "cold-8", priority = 100),
            ),
            currentRouteBucket = "warm-4",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    @Test
    fun exhaustedLeaderPreferenceReturnsToAffinityFollower() {
        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            listOf(
                candidate(page = 101, route = "warm-4", priority = 10),
                candidate(page = 102, route = "cold-8", priority = 100),
            ),
            currentRouteBucket = "warm-4",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    @Test
    fun coldLeaderIntentionallyPrecedesHigherPriorityFollowerInsideTargetBand() {
        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            listOf(
                candidate(page = 96, route = "cold-9", priority = 1),
                candidate(page = 101, route = "warm-4", priority = 1_000),
            ),
            currentRouteBucket = "warm-4",
            preferredPageIndexes = setOf(96),
        )

        assertEquals(96, selected?.candidate?.pageIndex)
    }

    @Test
    fun emptyPreferencePreservesAffinityOrdering() {
        val candidates = listOf(
            candidate(page = 96, route = "cold-9", priority = 100),
            candidate(page = 101, route = "warm-4", priority = 100),
        )

        val selected = NtkStrictSourceSchedulerPolicy.selectPrimary(
            candidates,
            currentRouteBucket = "warm-4",
        )

        assertEquals(101, selected?.candidate?.pageIndex)
    }

    private fun candidate(
        page: Int,
        route: String,
        priority: Int,
        lane: NtkSourceOperationLane = NtkSourceOperationLane.TARGET,
    ) = NtkStrictSourceSchedulerPolicy.Candidate(
        pageIndex = page,
        routeBucket = route,
        routeKeyHash = "route-$page",
        priority = priority,
        sourceLane = lane,
    )
}
