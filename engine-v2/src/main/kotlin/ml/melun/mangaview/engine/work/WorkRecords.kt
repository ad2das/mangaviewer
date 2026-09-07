package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest

internal enum class WorkRecordState {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    READY,
    RETIRING,
    DONE,
}

internal class WorkSubscriber {
    val ready = CompletableDeferred<Any>()
    val releaseDone = CompletableDeferred<Unit>()
    var delivered = false
    var released = false
    var detached = false
}

internal class OwnedWorkResult(
    val value: Any,
    val dispose: suspend () -> Unit,
)

internal interface WorkRecord {
    val key: WorkKey<*>
    val requestDomain: WorkDomain
    val authEpoch: Long
    val sequence: Long
    val request: WorkRequest<*>
    val priority: MutableStateFlow<WorkPriority>
    val subscribers: MutableList<WorkSubscriber>
    val cleanupSubscribers: MutableList<WorkSubscriber>
    val completion: CompletableDeferred<Unit>
    val dependencies: MutableMap<WorkSubscriber, WorkRecord>
    var state: WorkRecordState
    var worker: Job?
    var permit: PermitClaim?
    var result: OwnedWorkResult?
    var cancelRequested: Boolean
    var retryReady: Boolean
    var disposeStarted: Boolean
    var attempt: Int

    suspend fun executeAny(context: WorkContext): OwnedWorkResult
}

internal class TypedWorkRecord<T : Any>(
    override val request: WorkRequest<T>,
    override val sequence: Long,
) : WorkRecord {
    override val key: WorkKey<*> = request.key
    override val requestDomain: WorkDomain = request.domain
    override val authEpoch: Long = request.authEpoch
    override val priority = MutableStateFlow(request.priority)
    override val subscribers = mutableListOf<WorkSubscriber>()
    override val cleanupSubscribers = mutableListOf<WorkSubscriber>()
    override val completion = CompletableDeferred<Unit>()
    override val dependencies = linkedMapOf<WorkSubscriber, WorkRecord>()
    override var state = WorkRecordState.QUEUED
    override var worker: Job? = null
    override var permit: PermitClaim? = null
    override var result: OwnedWorkResult? = null
    override var cancelRequested = false
    override var retryReady = false
    override var disposeStarted = false
    override var attempt = 0

    override suspend fun executeAny(context: WorkContext): OwnedWorkResult {
        val result: T = request.execute(context)
        return OwnedWorkResult(result) { request.dispose(result) }
    }
}

internal data class DisposalPlan(
    val record: WorkRecord,
    val result: OwnedWorkResult,
)

internal data class CancelActions(
    val waiters: List<WorkSubscriber>,
    val jobs: List<Job>,
    val disposals: List<DisposalPlan>,
)

internal fun WorkRecord.isLive(): Boolean = state != WorkRecordState.DONE
