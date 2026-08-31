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
        for (ignored in eventSignal) drainAvailableEvents()
    }

    private fun drainAvailableEvents() {
        if (processingEvents) return
        processingEvents = true
        try {
            var pendingWorkerState: ViewerState? = null
            while (true) {
                val event = events.poll()
                if (event == null) {
                    pendingWorkerState?.let(::finishWorkerBatch)
                    break
                }
                if (event.isPageWorkerEvent()) {
                    val reduction = applyEvent(event)
                    if (reduction != null) pendingWorkerState = reduction.state
                } else {
                    pendingWorkerState?.let(::finishWorkerBatch)
                    pendingWorkerState = null
                    processEvent(event)
                }
            }
        } finally {
            processingEvents = false
        }
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
    }
}

private fun ViewerEvent.isPageWorkerEvent(): Boolean = when (this) {
    is ViewerEvent.FetchResponseStarted,
    is ViewerEvent.FetchSucceeded,
    is ViewerEvent.FetchFailed,
    is ViewerEvent.DecodeSucceeded,
    is ViewerEvent.DecodeFailed,
    -> true
    else -> false
}
