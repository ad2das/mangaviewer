package ml.melun.mangaview.viewer

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

fun interface ViewerWorkPort {
    fun accept(command: ViewerCommand)
}

class ViewerPipelineCoordinator(
    private val scope: CoroutineScope,
    private val reducer: ViewerReducer,
    private val framePlanner: FramePlanner,
    private val renderPort: RenderPort,
    private val workPort: ViewerWorkPort,
    private val actorDispatcher: CoroutineDispatcher? = null,
    private val isActorThread: (() -> Boolean)? = null,
) : Closeable {
    private val events = ConcurrentLinkedQueue<ViewerEvent>()
    private val eventSignal = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<ViewerState?>(null)
    private val actor: Job = launchActor { consumeEvents() }
    private var retryWakeup: Job? = null
    private var retryDeadlineNanos: Long? = null
    private var lastSubmittedFrame: FramePlan? = null
    private var processingEvents = false

    val state: StateFlow<ViewerState?> = mutableState.asStateFlow()

    suspend fun emit(event: ViewerEvent) {
        if (closed.get()) throw CancellationException("Viewer coordinator is closed")
        events.add(event)
        if (isActorThread?.invoke() == true) drainAvailableEvents() else eventSignal.send(Unit)
    }

    fun post(event: ViewerEvent): Boolean {
        if (closed.get()) return false
        if (isActorThread?.invoke() == true && event.isLatencyCriticalInput() && !processingEvents) {
            processEvent(event)
            return true
        }
        events.add(event)
        if (isActorThread?.invoke() == true) {
            drainAvailableEvents()
            return true
        }
        return eventSignal.trySend(Unit).isSuccess
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        eventSignal.close()
        retryWakeup?.cancel()
        actor.cancel()
    }

    private suspend fun consumeEvents() {
        for (ignored in eventSignal) {
            val hasMore = drainAvailableEvents()
            if (hasMore) {
                eventSignal.trySend(Unit)
                yield()
            }
        }
    }

    private fun drainAvailableEvents(): Boolean {
        if (processingEvents) return events.isNotEmpty()
        processingEvents = true
        var ordinaryPageWorkerEvents = 0
        var visiblePageWorkerEvents = 0
        try {
            var pendingWorkerState: ViewerState? = null
            while (true) {
                val event = pollNextEvent()
                if (event == null) {
                    pendingWorkerState?.let(::finishWorkerBatch)
                    break
                }
                if (event.isPageWorkerEvent()) {
                    val visibleResult = event.isTerminalPageWorkerResultFor(
                        visiblePageIds(mutableState.value),
                    )
                    val reduction = applyEvent(event)
                    if (reduction != null) pendingWorkerState = reduction.state
                    if (visibleResult) visiblePageWorkerEvents += 1 else ordinaryPageWorkerEvents += 1
                    if (ordinaryPageWorkerEvents >= MAXIMUM_PAGE_WORKER_EVENTS_PER_TURN ||
                        visiblePageWorkerEvents >= MAXIMUM_VISIBLE_PAGE_WORKER_EVENTS_PER_TURN
                    ) {
                        pendingWorkerState?.let(::finishWorkerBatch)
                        break
                    }
                } else {
                    pendingWorkerState?.let(::finishWorkerBatch)
                    pendingWorkerState = null
                    processEvent(event)
                }
            }
        } finally {
            processingEvents = false
        }
        val hasMore = events.isNotEmpty()
        if (hasMore) eventSignal.trySend(Unit)
        return hasMore
    }

    private fun processEvent(event: ViewerEvent) {
        val reduction = applyEvent(event) ?: return
        submitFrameIfVisible(reduction.state, event is ViewerEvent.UserScroll)
        scheduleRetryWakeup(reduction.state)
    }

    private fun applyEvent(event: ViewerEvent): Reduction? {
        val reduction = reducer.reduce(mutableState.value, event) ?: return null
        mutableState.value = reduction.state
        reduction.commands.forEach(workPort::accept)
        return reduction
    }

    private fun finishWorkerBatch(state: ViewerState) {
        submitFrameIfVisible(state, scrollOnly = false)
        scheduleRetryWakeup(state)
    }

    /**
     * Cached COLD completions can arrive as a large burst. A page that becomes visible while that
     * burst is queued must not wait behind the whole episode, so its terminal worker result is
     * selected first. Only result ordering changes; every event is still reduced by this actor.
     */
    private fun pollNextEvent(): ViewerEvent? {
        val visiblePages = visiblePageIds(mutableState.value)
        if (visiblePages.isNotEmpty()) {
            val visibleResult = events.firstOrNull { event ->
                event.isTerminalPageWorkerResultFor(visiblePages)
            }
            if (visibleResult != null && events.remove(visibleResult)) return visibleResult
        }
        return events.poll()
    }

    private fun visiblePageIds(state: ViewerState?): Set<ml.melun.mangaview.core.PageId> {
        state ?: return emptySet()
        val start = state.scroll.contentOffset
        val end = FixedPx(start.units + state.viewport.height.units)
        return state.layout.indicesIntersecting(start, end)
            .mapTo(mutableSetOf()) { index -> state.pageOrder[index] }
    }

    private fun submitFrameIfVisible(state: ViewerState, scrollOnly: Boolean) {
        if (state.visibility != ViewerVisibility.FOREGROUND || !state.surfaceAttached) {
            lastSubmittedFrame = null
            return
        }
        val frame = if (scrollOnly) {
            framePlanner.planScroll(state, lastSubmittedFrame)
        } else {
            framePlanner.plan(state)
        }
        if (frame == lastSubmittedFrame) return
        lastSubmittedFrame = frame
        renderPort.submit(frame)
    }

    private fun scheduleRetryWakeup(state: ViewerState) {
        val deadline = state.nextRetryDeadlineNanos?.takeIf { it > state.lastEventNanos }
        if (deadline == retryDeadlineNanos && retryWakeup?.isActive == true) return
        retryWakeup?.cancel()
        retryDeadlineNanos = deadline
        retryWakeup = deadline?.let { due ->
            launchActor {
                val remaining = (due - System.nanoTime()).coerceAtLeast(0L)
                delay((remaining + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                post(ViewerEvent.RetryWakeup(maxOf(due, System.nanoTime())))
            }
        }
    }

    private fun launchActor(block: suspend CoroutineScope.() -> Unit): Job =
        actorDispatcher?.let { scope.launch(it, block = block) }
            ?: scope.launch(block = block)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_PAGE_WORKER_EVENTS_PER_TURN = 1
        const val MAXIMUM_VISIBLE_PAGE_WORKER_EVENTS_PER_TURN = 4
    }
}

private fun ViewerEvent.isLatencyCriticalInput(): Boolean =
    this is ViewerEvent.UserScroll || this is ViewerEvent.InteractionChanged

private fun ViewerEvent.isPageWorkerEvent(): Boolean = when (this) {
    is ViewerEvent.FetchResponseStarted,
    is ViewerEvent.FetchSucceeded,
    is ViewerEvent.FetchFailed,
    is ViewerEvent.DecodeSucceeded,
    is ViewerEvent.DecodeFailed,
    -> true
    else -> false
}

private fun ViewerEvent.isTerminalPageWorkerResultFor(
    visiblePages: Set<ml.melun.mangaview.core.PageId>,
): Boolean = when (this) {
    is ViewerEvent.FetchSucceeded -> token.pageId in visiblePages
    is ViewerEvent.FetchFailed -> token.pageId in visiblePages
    is ViewerEvent.DecodeSucceeded -> token.pageId in visiblePages
    is ViewerEvent.DecodeFailed -> token.pageId in visiblePages
    else -> false
}
