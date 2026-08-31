package ml.melun.mangaview.viewer

private data class SchedulingResult(
    val state: ViewerState,
    val commands: List<ViewerCommand>,
)

class WorkScheduler(
    private val demandPlanner: DemandPlanner,
    private val memoryPolicy: PixelMemoryPolicy = PixelMemoryPolicy(),
) {
    fun schedule(state: ViewerState): Reduction {
        if (!state.firstResponseReceived) return scheduleInitialPage(state)
        val demands = demandPlanner.plan(state)
        val activeFetches = scheduleActiveFetches(state, demands)
        val coldFetches = scheduleColdFetches(activeFetches.state)
        val decodes = scheduleDecodes(coldFetches.state, demands)
        return Reduction(
            decodes.state,
            activeFetches.commands + coldFetches.commands + decodes.commands,
        )
    }

    private fun scheduleInitialPage(initial: ViewerState): Reduction {
        val runtime = initial.pages.getValue(initial.initialTargetPageId)
        if (!canFetch(initial, runtime)) return Reduction(initial, emptyList())
        val claimed = claim(initial, runtime, WorkKind.FETCH, WorkPriority.HARD)
        return Reduction(claimed.state, listOf(ViewerCommand.FetchPage(claimed.token)))
    }

    private fun scheduleActiveFetches(initial: ViewerState, demands: List<PageDemand>): SchedulingResult {
        var state = initial
        val commands = mutableListOf<ViewerCommand>()
        for (demand in demands) {
            if (state.ownership.fetches.size >= state.networkConcurrency) break
            val runtime = state.pages.getValue(demand.pageId)
            if (!canFetch(state, runtime)) continue
            val claimed = claim(state, runtime, WorkKind.FETCH, demand.priority)
            state = claimed.state
            commands += ViewerCommand.FetchPage(claimed.token)
        }
        return SchedulingResult(state, commands)
    }

    private fun scheduleColdFetches(initial: ViewerState): SchedulingResult {
        var available = initial.networkConcurrency - initial.ownership.fetches.size
        val sweep = initial.coldFetchSweep
        if (available <= 0 || sweep.isComplete || sweep.pausedUntilNanos > initial.lastEventNanos) {
            return SchedulingResult(initial, emptyList())
        }
        val direction = if (initial.velocityUnitsPerSecond < 0L) -1 else 1
        var state = initial
        var cursor = sweep.cursor
        var inspected = 0
        var earliestRetry = Long.MAX_VALUE
        val commands = mutableListOf<ViewerCommand>()
        while (available > 0 && inspected < sweep.pendingCount) {
            val pageIndex = if (direction > 0) {
                requireNotNull(sweep.nextPendingIndex(cursor))
            } else {
                requireNotNull(sweep.previousPendingIndex(cursor))
            }
            val pageId = state.pageOrder[pageIndex]
            cursor = if (direction > 0) {
                (pageIndex + 1) % sweep.pageCount
            } else {
                if (pageIndex == 0) sweep.pageCount - 1 else pageIndex - 1
            }
            inspected += 1
            val runtime = state.pages.getValue(pageId)
            earliestRetry = minOf(earliestRetry, retryDeadline(state, runtime))
            if (!canFetch(state, runtime)) continue
            val claimed = claim(state, runtime, WorkKind.FETCH, WorkPriority.COLD)
            state = claimed.state
            commands += ViewerCommand.FetchPage(claimed.token)
            available -= 1
        }
        val pause = if (available > 0 && inspected == sweep.pendingCount) earliestRetry else 0L
        return SchedulingResult(
            state.copy(coldFetchSweep = sweep.advanced(cursor, pause)),
            commands,
        )
    }

    private fun scheduleDecodes(initial: ViewerState, demands: List<PageDemand>): SchedulingResult {
        if (initial.visibility == ViewerVisibility.BACKGROUND) return SchedulingResult(initial, emptyList())
        var state = initial
        val commands = mutableListOf<ViewerCommand>()
        val startupSlicePresented = state.startupMotionPending &&
            state.pages.values.any { it.pixel != null }
        if (!startupSlicePresented && ownedDecodeCount(state, WorkPriority.HARD) == 0) {
            val foreground = demands.firstOrNull { demand ->
                demand.distanceUnits == 0L && demand.decodeBand != null &&
                    canDecode(state, state.pages.getValue(demand.pageId), demand.decodeBand)
            }
            if (foreground != null) {
                val runtime = state.pages.getValue(foreground.pageId)
                val claimed = claim(state, runtime, WorkKind.DECODE, WorkPriority.HARD)
                state = claimed.state
                commands += ViewerCommand.DecodePage(
                    claimed.token,
                    requireNotNull(runtime.encoded),
                    requireNotNull(foreground.decodeBand),
                )
            }
        }
        var warmAvailable = if (!state.interactionActive && state.pages.values.any(PageRuntime::isPresented)) {
            1 - ownedDecodeCount(state, WorkPriority.WARM)
        } else 0
        for (demand in demands) {
            if (warmAvailable <= 0 || !decodeAdmitted(state, demand)) continue
            val band = demand.decodeBand ?: continue
            val runtime = state.pages.getValue(demand.pageId)
            if (!canDecode(state, runtime, band)) continue
            val claimed = claim(state, runtime, WorkKind.DECODE, WorkPriority.WARM)
            state = claimed.state
            commands += ViewerCommand.DecodePage(claimed.token, requireNotNull(runtime.encoded), band)
            warmAvailable -= 1
        }
        return SchedulingResult(state, commands)
    }

    private fun retryDeadline(state: ViewerState, runtime: PageRuntime): Long = when {
        runtime.encoded != null -> Long.MAX_VALUE
        state.ownership.owner(WorkKind.FETCH, runtime.spec.id) != null -> Long.MAX_VALUE
        else -> runtime.fetchRetry?.eligibleAtNanos ?: 0L
    }

    private fun decodeAdmitted(state: ViewerState, demand: PageDemand): Boolean =
        demand.distanceUnits == 0L || state.residentBytes < memoryPolicy.warmAdmissionBytes

    private fun canFetch(state: ViewerState, runtime: PageRuntime): Boolean =
        !state.isBoundaryPage(runtime.spec.id) && runtime.encoded == null &&
            state.ownership.owner(WorkKind.FETCH, runtime.spec.id) == null &&
            (runtime.fetchRetry?.eligibleAtNanos ?: 0L) <= state.lastEventNanos

    private fun canDecode(state: ViewerState, runtime: PageRuntime, band: PixelBand): Boolean =
        runtime.encoded != null && runtime.pixel?.covers(band) != true &&
            state.ownership.owner(WorkKind.DECODE, runtime.spec.id) == null &&
            (runtime.decodeRetry?.eligibleAtNanos ?: 0L) <= state.lastEventNanos

    private fun claim(
        state: ViewerState,
        runtime: PageRuntime,
        kind: WorkKind,
        priority: WorkPriority,
    ): ClaimedWork {
        val attempt = if (kind == WorkKind.FETCH) {
            (runtime.fetchRetry?.failures ?: 0) + 1
        } else {
            (runtime.decodeRetry?.failures ?: 0) + 1
        }
        val claim = state.ownership.claim(state.generation, runtime.spec.id, kind, attempt, priority)
        val milestone = if (kind == WorkKind.FETCH) PageMilestone.FETCHING else PageMilestone.DECODING
        val page = if (runtime.milestone.ordinal < milestone.ordinal) runtime.advance(milestone) else runtime
        val claimedState = state.replacePage(runtime.spec.id, page).copy(ownership = claim.ownership)
        return ClaimedWork(
            clearRetryDeadline(claimedState, RetryWorkKey.Page(runtime.spec.id, kind)),
            claim.token,
        )
    }

    private fun ownedDecodeCount(state: ViewerState, priority: WorkPriority): Int =
        state.ownership.decodes.values.count { it.priority == priority }

    private data class ClaimedWork(
        val state: ViewerState,
        val token: OperationToken,
    )

    private fun ViewerState.isBoundaryPage(pageId: ml.melun.mangaview.core.PageId): Boolean =
        episodeAppends.values.any { it.boundaryPageId == pageId }
}
