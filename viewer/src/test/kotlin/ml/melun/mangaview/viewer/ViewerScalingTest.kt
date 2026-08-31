package ml.melun.mangaview.viewer

import java.util.ArrayDeque
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScalingTest {
    @Test
    fun coldFetchStartsAtTheReadingAnchorAndOnlyWrapsAfterTheForwardTail() {
        var sweep = ColdFetchSweep.create(pageCount = 10, startIndex = 7)
        val order = mutableListOf<Int>()

        repeat(10) {
            val next = requireNotNull(sweep.nextPendingIndex(sweep.cursor))
            order += next
            sweep = sweep.without(next).advanced((next + 1) % sweep.pageCount, 0L)
        }

        assertEquals(listOf(7, 8, 9, 0, 1, 2, 3, 4, 5, 6), order)
        assertTrue(sweep.isComplete)
    }

    @Test
    fun appendingAnEpisodeKeepsTheNearestExistingForwardGap() {
        var sweep = ColdFetchSweep.create(pageCount = 6, startIndex = 3)
        sweep = sweep.without(3).without(4)

        val appended = sweep.append(additionalPages = 4)

        assertEquals(3, appended.cursor)
        assertEquals(5, appended.nextPendingIndex(appended.cursor))
    }

    @Test
    fun appendingAfterCompletionMovesColdFetchToTheFirstNewPage() {
        var sweep = ColdFetchSweep.create(pageCount = 3)
        repeat(3) { index -> sweep = sweep.without(index) }

        val appended = sweep.append(additionalPages = 2)

        assertEquals(3, appended.cursor)
        assertEquals(3, appended.nextPendingIndex(appended.cursor))
    }

    @Test
    fun reverseInputMakesTheColdSweepFollowTheNowLikelyPreviousPages() {
        var sweep = ColdFetchSweep.create(pageCount = 10, startIndex = 7)
        val order = mutableListOf<Int>()
        var cursor = 7

        repeat(10) {
            val next = requireNotNull(sweep.previousPendingIndex(cursor))
            order += next
            sweep = sweep.without(next)
            cursor = if (next == 0) sweep.pageCount - 1 else next - 1
        }

        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1, 0, 9, 8), order)
        assertTrue(sweep.isComplete)
    }

    @Test
    fun demandPlanningTouchesOnlyTheRetainedGeometryWindow() {
        val state = open(500)
        val plan = DemandPlanner().plan(state)
        val window = PixelWindowPolicy().window(state)
        val retained = state.layout.indicesIntersecting(
            FixedPx(window.retainedStartUnits),
            FixedPx(window.retainedEndUnits),
        )

        assertEquals(retained.toList(), plan.map(PageDemand::index).sorted())
        assertTrue(plan.size < 20)
        assertTrue(plan.none { it.priority == WorkPriority.COLD })
    }

    @Test
    fun demandPlanningStartsAtViewportCenterThenFavorsForwardVisibleContent() {
        val base = open(20)
        val scroll = ScrollController().scrollBy(
            base.layout,
            base.viewport,
            base.scroll,
            FixedPx.fromPixels(900),
        )
        val state = base.copy(
            scroll = scroll,
            velocityUnitsPerSecond = FixedPx.fromPixels(2_000).units,
        )

        val plan = DemandPlanner().plan(state)

        assertEquals(1, plan.first().index)
        assertEquals(WorkPriority.HARD, plan.first().priority)
        assertEquals(0, plan[1].index)
        assertEquals(WorkPriority.WARM, plan[1].priority)
        assertEquals(2, plan[2].index)
    }

    @Test
    fun coldSweepEventuallyFetchesAllFiveHundredPagesExactlyOnce() {
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(90L, ViewerFixtures.manifest(500), ViewerFixtures.viewport, 1L),
        ))
        val logicalAnchor = reduction.state.scroll.anchor
        val pending = ArrayDeque<ViewerCommand.FetchPage>()
        pending.addFetches(reduction.commands)
        val fetched = linkedSetOf<PageId>()
        val sequences = linkedSetOf<Long>()
        val priorities = linkedSetOf<WorkPriority>()
        var previousSequence = 0L
        var now = 2L
        while (pending.isNotEmpty()) {
            val command = pending.removeFirst()
            assertTrue("duplicate fetch for ${command.token.pageId}", fetched.add(command.token.pageId))
            assertTrue(sequences.add(command.token.operationSequence))
            assertTrue(command.token.operationSequence > previousSequence)
            previousSequence = command.token.operationSequence
            priorities += command.token.priority
            reduction = requireNotNull(reducer.reduce(
                reduction.state,
                ViewerEvent.FetchSucceeded(
                    command.token,
                    VerifiedPageRef(
                        "cache-${fetched.size}",
                        1L,
                        "sha-${fetched.size}",
                        PageDimensions(600 + fetched.size % 13, 900 + fetched.size * 17),
                    ),
                    10L,
                    now++,
                ),
            ))
            assertEquals(logicalAnchor, reduction.state.scroll.anchor)
            pending.addFetches(reduction.commands)
        }

        assertEquals(500, fetched.size)
        assertTrue(reduction.state.coldFetchSweep.isComplete)
        assertEquals(500, reduction.state.episodeProgress.getValue(reduction.state.currentEpisodeId).verifiedCount)
        assertTrue(WorkPriority.HARD in priorities)
        assertTrue(WorkPriority.COLD in priorities)
    }

    @Test
    fun scrollingWhileFetchLanesAreFullDoesNotAdvanceTheColdSweep() {
        val reducer = ViewerFixtures.reducer()
        var state = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(91L, manifest(500), ViewerFixtures.viewport, 1L),
        )).state
        val cursor = state.coldFetchSweep.cursor

        repeat(100) { step ->
            state = requireNotNull(reducer.reduce(
                state,
                ViewerEvent.UserScroll(FixedPx.fromPixels(2), 120L, step + 2L),
            )).state
        }

        assertEquals(cursor, state.coldFetchSweep.cursor)
        assertEquals(1, state.ownership.fetches.size)
    }

    @Test
    fun forwardRawRunwayKeepsRefillingWhileTheUserIsScrolling() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(92L, manifest(30), ViewerFixtures.viewport, 1L),
        ))
        val first = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        var reduction = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchResponseStarted(first.token, 2L),
        ))
        val initialRunway = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
        assertEquals(5, initialRunway.size)

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.InteractionChanged(true, 3L),
        ))
        val completed = initialRunway.first()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                completed.token,
                VerifiedPageRef("forward", 1L, "forward-sha", PageDimensions(1_000, 1_500)),
                10L,
                4L,
            ),
        ))

        val replacements = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
        assertEquals(1, replacements.size)
        val replacement = replacements.first()
        val completedIndex = reduction.state.pageOrder.indexOf(completed.token.pageId)
        val replacementIndex = reduction.state.pageOrder.indexOf(replacement.token.pageId)
        assertTrue(replacementIndex > completedIndex)
        assertTrue(replacement.token.priority != WorkPriority.COLD ||
            replacementIndex >= completedIndex)
    }

    @Test
    fun visibleHardFetchPreemptsOnlySpeculativeWorkWhenAllLanesAreBusy() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(93L, manifest(30), ViewerFixtures.viewport, 1L),
        ))
        val initial = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        var reduction = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchResponseStarted(initial.token, 2L),
        ))
        assertEquals(6, reduction.state.ownership.fetches.size)

        val targetIndex = 10
        val targetTop = reduction.state.layout.topAt(targetIndex)
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.UserScroll(targetTop, targetTop.units, 3L),
        ))

        val cancelled = reduction.commands.filterIsInstance<ViewerCommand.CancelFetch>().single()
        val replacement = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
            .single { it.token.priority == WorkPriority.HARD }
        assertTrue(cancelled.token.priority != WorkPriority.HARD)
        assertEquals(reduction.state.pageOrder[targetIndex], replacement.token.pageId)
        assertEquals(6, reduction.state.ownership.fetches.size)
        assertTrue(initial.token in reduction.state.ownership.fetches.values)
    }

    @Test
    fun passingOneRetryDeadlineRetainsTheNextIncrementalDeadline() {
        val reducer = ViewerFixtures.reducer()
        val opened = open(20)
        val first = opened.pageOrder[0]
        val second = opened.pageOrder[1]
        val retryPages = opened
            .replacePage(first, opened.pages.getValue(first).copy(fetchRetry = RetryState(1, 100L, "one")))
            .replacePage(second, opened.pages.getValue(second).copy(fetchRetry = RetryState(1, 200L, "two")))
            .copy(
                ownership = WorkOwnership(),
                lastEventNanos = 50L,
            )
        val retries = recordRetryDeadline(
            recordRetryDeadline(retryPages, RetryWorkKey.Page(first, WorkKind.FETCH), 100L),
            RetryWorkKey.Page(second, WorkKind.FETCH),
            200L,
        )

        val advanced = requireNotNull(reducer.reduce(
            retries,
            ViewerEvent.UserScroll(FixedPx.fromPixels(1), 1L, 150L),
        )).state

        assertEquals(200L, advanced.nextRetryDeadlineNanos)
    }

    private fun open(pageCount: Int): ViewerState = requireNotNull(
        ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(89L, manifest(pageCount), ViewerFixtures.viewport, 1L),
        ),
    ).state

    private fun manifest(pageCount: Int) = ViewerFixtures.manifest(
        pageCount = pageCount,
        dimensions = { PageDimensions(1_000, 1_500) },
    )

    private fun ArrayDeque<ViewerCommand.FetchPage>.addFetches(commands: List<ViewerCommand>) {
        commands.filterIsInstance<ViewerCommand.FetchPage>().forEach(::addLast)
    }
}
