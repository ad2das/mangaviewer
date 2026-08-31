package ml.melun.mangaview.viewer

/** Owns fetch/decode state transitions; scroll geometry changes still use one authority. */
internal class ViewerPageEventReducer(
    private val scrollController: ScrollController,
) {
    fun fetchSucceeded(state: ViewerState, event: ViewerEvent.FetchSucceeded): ViewerState {
        if (!owns(state, event.token, WorkKind.FETCH)) return state
        val runtime = state.pages.getValue(event.token.pageId)
        val completed = runtime.advance(PageMilestone.VERIFIED).copy(
            residency = PageResidency.VERIFIED,
            encoded = event.encoded,
            fetchRetry = null,
        )
        val concurrency = if (event.token.pageId == state.initialTargetPageId) {
            INITIAL_RESPONSE_CONCURRENCY
        } else {
            successfulFetchConcurrency(state, event.elapsedMillis)
        }
        val ledger = event.encoded.dimensions?.let {
            state.layout.resolve(event.token.pageId, it)
        } ?: state.layout
        val scroll = if (ledger === state.layout) state.scroll else {
            scrollController.preserveAnchor(ledger, state.viewport, state.scroll)
        }
        return state.replacePage(event.token.pageId, completed).copy(
            ownership = state.ownership.release(event.token),
            firstResponseReceived = true,
            networkConcurrency = concurrency,
            layout = ledger,
            scroll = scroll,
        )
    }

    fun fetchResponseStarted(
        state: ViewerState,
        event: ViewerEvent.FetchResponseStarted,
    ): ViewerState {
        // A validated prefix is not a usable page yet. Keep the visible page's body as the sole
        // network owner until it has been durably verified; opening speculative transfers here
        // divides bandwidth at the exact moment the user is waiting for the first pixels.
        if (!owns(state, event.token, WorkKind.FETCH)) return state
        return state
    }

    fun fetchFailed(state: ViewerState, event: ViewerEvent.FetchFailed): ViewerState {
        if (!owns(state, event.token, WorkKind.FETCH)) return state
        require(event.reason.isNotBlank()) { "Failure reason must not be blank" }
        require(event.retryDelayNanos >= 0L) { "Retry delay must not be negative" }
        val runtime = state.pages.getValue(event.token.pageId)
        val retry = RetryState(
            failures = event.token.attempt,
            eligibleAtNanos = deadline(event.atNanos, event.retryDelayNanos),
            reason = event.reason,
        )
        val failed = state.replacePage(event.token.pageId, runtime.copy(fetchRetry = retry)).copy(
            ownership = state.ownership.release(event.token),
            networkConcurrency = failedFetchConcurrency(state),
            coldFetchSweep = state.coldFetchSweep.resumed(),
        )
        return recordRetryDeadline(
            failed,
            RetryWorkKey.Page(event.token.pageId, WorkKind.FETCH),
            retry.eligibleAtNanos,
        )
    }

    fun decodeSucceeded(state: ViewerState, event: ViewerEvent.DecodeSucceeded): Reduction {
        if (!owns(state, event.token, WorkKind.DECODE)) return staleDecode(state, event)
        val runtime = state.pages.getValue(event.token.pageId)
        val progressed = if (runtime.milestone.ordinal < PageMilestone.RESIDENT.ordinal) {
            runtime.advance(PageMilestone.RESIDENT)
        } else {
            runtime
        }
        val merged = mergePixels(runtime.pixel, event.pixel)
        val completed = progressed.copy(
            residency = PageResidency.RESIDENT,
            pixel = merged.pixel,
            decodeRetry = null,
        )
        val ledger = state.layout.resolve(event.token.pageId, event.pixel.dimensions)
        val scroll = if (ledger === state.layout) state.scroll else {
            scrollController.preserveAnchor(ledger, state.viewport, state.scroll)
        }
        return Reduction(
            state.replacePage(event.token.pageId, completed).copy(
                ownership = state.ownership.release(event.token),
                layout = ledger,
                scroll = scroll,
            ),
            merged.replaced?.let(ViewerCommand::ReleasePixel)?.let(::listOf).orEmpty(),
        )
    }

    fun decodeFailed(state: ViewerState, event: ViewerEvent.DecodeFailed): ViewerState {
        if (!owns(state, event.token, WorkKind.DECODE)) return state
        require(event.reason.isNotBlank()) { "Failure reason must not be blank" }
        require(event.retryDelayNanos >= 0L) { "Retry delay must not be negative" }
        val runtime = state.pages.getValue(event.token.pageId)
        val retry = RetryState(
            failures = event.token.attempt,
            eligibleAtNanos = deadline(event.atNanos, event.retryDelayNanos),
            reason = event.reason,
        )
        val failed = state.replacePage(event.token.pageId, runtime.copy(decodeRetry = retry)).copy(
            ownership = state.ownership.release(event.token),
        )
        return recordRetryDeadline(
            failed,
            RetryWorkKey.Page(event.token.pageId, WorkKind.DECODE),
            retry.eligibleAtNanos,
        )
    }

    private fun staleDecode(state: ViewerState, event: ViewerEvent.DecodeSucceeded): Reduction {
        val current = state.pages[event.token.pageId]?.pixel
        val alreadyResident = event.pixel.tiles.all { incoming ->
            current?.tiles?.any { resident ->
                resident.handle == incoming.handle &&
                    resident.contentVersion == incoming.contentVersion
            } == true
        }
        val commands = if (alreadyResident) {
            emptyList()
        } else {
            listOf(ViewerCommand.ReleasePixel(event.pixel))
        }
        return Reduction(state, commands)
    }

    private fun owns(state: ViewerState, token: OperationToken, kind: WorkKind): Boolean =
        token.generation == state.generation && token.kind == kind &&
            state.ownership.owner(kind, token.pageId) == token

    private fun successfulFetchConcurrency(state: ViewerState, elapsedMillis: Long): Int {
        if (!state.firstResponseReceived) return 2
        if (state.residentPageIds.isEmpty()) return INITIAL_RESPONSE_CONCURRENCY
        val ceiling = if (state.ownership.decodes.size < 2) 6 else 4
        return if (elapsedMillis in 0L..1_500L) {
            (state.networkConcurrency + 1).coerceAtMost(ceiling)
        } else {
            state.networkConcurrency.coerceAtMost(ceiling)
        }
    }

    private fun failedFetchConcurrency(state: ViewerState): Int =
        if (state.firstResponseReceived) (state.networkConcurrency - 1).coerceAtLeast(2) else 1

    private fun deadline(nowNanos: Long, delayNanos: Long): Long =
        if (Long.MAX_VALUE - nowNanos < delayNanos) Long.MAX_VALUE else nowNanos + delayNanos

    private companion object {
        const val INITIAL_RESPONSE_CONCURRENCY = 2
    }
}
