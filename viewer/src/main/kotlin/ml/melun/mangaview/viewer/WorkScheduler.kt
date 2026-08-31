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
        val preempted = preemptForVisibleHardDemand(initial, demands)
        var state = preempted.state
        val commands = preempted.commands.toMutableList()
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

    private fun preemptForVisibleHardDemand(
        state: ViewerState,
        demands: List<PageDemand>,
    ): SchedulingResult {
        if (state.ownership.fetches.size < state.networkConcurrency) {
            return SchedulingResult(state, emptyList())
        }
        val target = demands.firstOrNull { demand ->
            demand.priority == WorkPriority.HARD &&
                canFetch(state, state.pages.getValue(demand.pageId))
        } ?: return SchedulingResult(state, emptyList())
        val demandIndices = demands.associate { it.pageId to it.index }
        val victim = state.ownership.fetches.values
            .filter { it.priority != WorkPriority.HARD }
            .maxWithOrNull(compareBy<OperationToken>(
                { it.priority.ordinal },
                { kotlin.math.abs((demandIndices[it.pageId] ?: Int.MAX_VALUE) - target.index) },
                { -it.operationSequence },
            )) ?: return SchedulingResult(state, emptyList())
        return SchedulingResult(
            state.copy(ownership = state.ownership.release(victim)),
            listOf(ViewerCommand.CancelFetch(victim)),
        )
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
        val preempted = preemptObsoleteHardDecode(initial, demands)
        var state = preempted.state
        val commands = preempted.commands.toMutableList()
        if (ownedDecodeCount(state, WorkPriority.HARD) == 0) {
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
        var warmAvailable = 1 - ownedDecodeCount(state, WorkPriority.WARM)
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

    private fun preemptObsoleteHardDecode(
        state: ViewerState,
        demands: List<PageDemand>,
    ): SchedulingResult {
        val hard = state.ownership.decodes.values.singleOrNull {
            it.priority == WorkPriority.HARD
        } ?: return SchedulingResult(state, emptyList())
        val visible = demands.asSequence()
            .filter { it.distanceUnits == 0L }
            .map(PageDemand::pageId)
            .toSet()
        val focus = demands.firstOrNull { it.priority == WorkPriority.HARD }
        if (focus?.pageId == hard.pageId) return SchedulingResult(state, emptyList())
        val focusNeedsDecode = focus?.decodeBand?.let { band ->
            canDecode(state, state.pages.getValue(focus.pageId), band)
        } == true
        if (hard.pageId in visible && !focusNeedsDecode) {
            return SchedulingResult(state, emptyList())
        }
        return SchedulingResult(
            state.copy(ownership = state.ownership.release(hard)),
            listOf(ViewerCommand.CancelDecode(hard)),
        )
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
