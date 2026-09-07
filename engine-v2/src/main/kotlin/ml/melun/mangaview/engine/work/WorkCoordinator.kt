package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkLease
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkOwnershipSnapshot
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkSubscription

class WorkCoordinator(
    scope: CoroutineScope,
    limits: WorkLimits = WorkLimits(),
) : WorkCoordinatorPort {
    internal val registry = WorkRegistry(limits)
    private val mutex get() = registry.mutex
    private val records get() = registry.records
    private val closeCompletion get() = registry.closeCompletion
    private val attemptTokens = AtomicLong()
    private val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
    internal val workerScope = CoroutineScope(scope.coroutineContext + coordinatorJob)
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + cleanupJob)
    private var closed: Boolean
        get() = registry.closed
        set(value) {
            registry.closed = value
        }
    private val execution = WorkExecution(this, registry, attemptTokens)
    private val orderedAdmission = WorkOrderedAdmission(this, registry, execution)
    private val schedulerJob: Job

    init {
        schedulerJob = workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            orderedAdmission.schedulerLoop()
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(NonCancellable) { close() }
                } catch (failure: Throwable) {
                    if (failure !is CancellationException || !closeCompletion.isCompleted) {
                        recordObserverFailure(failure)
                    }
                }
            }
        }
    }

    override suspend fun <T : Any> submit(request: WorkRequest<T>): WorkSubscription<T> =
        submitInternal(request)

    override suspend fun <T : Any> acquire(request: WorkRequest<T>): WorkLease<T> {
        val subscription = submitInternal(request)
        val value = subscription.await()
        return subscription.lease(value)
    }

    private suspend fun <T : Any> submitInternal(request: WorkRequest<T>): CoordinatorSubscription<T> {
        val (record, subscriber) = mutex.withLock { registerLocked(request) }
        return CoordinatorSubscription(this, record, subscriber, request.key.resultType)
    }

    override suspend fun invalidate(principal: String, authEpoch: Long) {
        require(principal.isNotBlank() && authEpoch >= 0L)
        val (targets, actions) = mutex.withLock {
            val retired = maxOf(registry.retiredAuthEpochs[principal] ?: -1L, authEpoch)
            registry.retiredAuthEpochs[principal] = retired
            val selected = records.values.filter {
                it.key.principal == principal && it.authEpoch <= retired
            }
            selected to selected.map { execution.cancelRecordLocked(it) }
        }
        execution.applyCancelActions(actions)
        targets.forEach { it.completion.await() }
    }

    override suspend fun snapshot(): WorkOwnershipSnapshot = mutex.withLock {
        WorkOwnershipSnapshot(
            closed = closed,
            queued = records.values.count { it.state == WorkRecordState.QUEUED },
            active = records.values.count {
                it.state == WorkRecordState.RUNNING || it.state == WorkRecordState.RETRY_WAIT
            },
            retiring = records.values.count { it.state == WorkRecordState.RETIRING },
            subscribers = records.values.sumOf { it.subscribers.size },
            retainedResults = records.values.count { it.result != null },
        )
    }

    override suspend fun close() {
        val (first, actions) = mutex.withLock {
            if (closed) {
                false to emptyList<CancelActions>()
            } else {
                closed = true
                val cancellation = records.values.toList().map { execution.cancelRecordLocked(it) }
                if (records.isEmpty()) registry.completeCloseLocked()
                signalLocked()
                true to cancellation
            }
        }
        if (!first) {
            awaitCloseCompletion()
            return
        }
        schedulerJob.cancel()
        coordinatorJob.cancel()
        execution.applyCancelActions(actions)
        withContext(NonCancellable) {
            schedulerJob.join()
            try {
                awaitCloseCompletion()
            } finally {
                cleanupJob.cancel()
            }
        }
    }

    internal fun promote(record: WorkRecord, requested: WorkPriority) {
        if (record.priority.value.ordinal <= requested.ordinal) return
        if (mutex.tryLock()) {
            try {
                promoteLocked(record, requested)
            } finally {
                mutex.unlock()
            }
            return
        }
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                mutex.withLock { promoteLocked(record, requested) }
            }
        }
    }

    internal fun releaseAsync(record: WorkRecord, subscriber: WorkSubscriber) {
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    release(record, subscriber)
                } catch (failure: Throwable) {
                    recordObserverFailure(failure)
                }
            }
        }
    }

    internal fun <T : Any> registerLocked(request: WorkRequest<T>): Pair<WorkRecord, WorkSubscriber> {
        checkOpenLocked()
        val retired = registry.retiredAuthEpochs[request.key.principal]
        if (retired != null && request.authEpoch <= retired) {
            throw WorkAuthEpochRetiredException(request.authEpoch)
        }
        val existing = records[request.key]
        if (existing != null) {
            if (existing.state == WorkRecordState.RETIRING || existing.cancelRequested) {
                throw WorkRetiringException(request.key)
            }
            validateCompatible(existing, request)
            promoteLocked(existing, request.priority)
            val subscriber = WorkSubscriber().also(existing.subscribers::add)
            if (existing.state == WorkRecordState.READY) {
                subscriber.ready.complete(checkNotNull(existing.result).value)
            }
            return existing to subscriber
        }
        val pending = records.values.count { it.state == WorkRecordState.QUEUED }
        if (pending >= registry.limits.queued) throw WorkQueueFullException(registry.limits.queued)
        val record = TypedWorkRecord(request, registry.sequence++)
        val subscriber = WorkSubscriber()
        record.subscribers += subscriber
        records[request.key] = record
        signalLocked()
        return record to subscriber
    }

    private fun <T : Any> validateCompatible(record: WorkRecord, request: WorkRequest<T>) {
        if (record.requestDomain != request.domain) {
            throw WorkRequestConflictException("Work key domain changed: ${request.key}")
        }
        if (record.authEpoch != request.authEpoch) {
            throw WorkRequestConflictException("Work key auth epoch changed: ${request.key}")
        }
    }

    internal suspend fun awaitSubscription(
        record: WorkRecord,
        subscriber: WorkSubscriber,
    ): Any {
        try {
            // A resumed mutex waiter owns the lock before its dispatcher runs it. Keep
            // that ownership off the UI queue, where a traversal can stall all work.
            return withContext(workerScope.coroutineContext.minusKey(Job)) {
                mutex.withLock {
                    checkSubscriptionLive(subscriber)
                }
                val value = subscriber.ready.await()
                mutex.withLock {
                    checkSubscriptionLive(subscriber)
                    if (!subscriber.delivered) subscriber.delivered = true
                }
                value
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { detachAndAwait(record, subscriber) }
            throw failure
        }
    }

    internal suspend fun detachAndAwait(record: WorkRecord, subscriber: WorkSubscriber) {
        detach(record, subscriber)
    }

    private suspend fun detach(record: WorkRecord, subscriber: WorkSubscriber) {
        val actions = mutex.withLock { detachLocked(record, subscriber) }
        actions.job?.cancel()
        actions.disposal?.let { execution.disposeRecord(it) }
        subscriber.releaseDone.await()
    }

    private suspend fun release(record: WorkRecord, subscriber: WorkSubscriber) {
        val actions = mutex.withLock { releaseLocked(record, subscriber) }
        actions.job?.cancel()
        actions.disposal?.let { execution.disposeRecord(it) }
        subscriber.releaseDone.await()
    }

    private fun detachLocked(record: WorkRecord, subscriber: WorkSubscriber): TransitionActions {
        if (subscriber.detached || subscriber.released) return TransitionActions()
        subscriber.detached = true
        subscriber.ready.cancel(CancellationException("Work subscription was closed"))
        if (records[record.key] !== record || record.state == WorkRecordState.DONE) {
            subscriber.releaseDone.complete(Unit)
            return TransitionActions()
        }
        record.subscribers.remove(subscriber)
        if (record.subscribers.isNotEmpty()) {
            subscriber.releaseDone.complete(Unit)
            signalLocked()
            return TransitionActions()
        }
        record.cleanupSubscribers += subscriber
        val actions = transitionLastSubscriberLocked(record)
        signalLocked()
        return actions
    }

    private fun releaseLocked(record: WorkRecord, subscriber: WorkSubscriber): TransitionActions {
        if (subscriber.released) return TransitionActions()
        if (subscriber.detached || !subscriber.delivered) return detachLocked(record, subscriber)
        subscriber.released = true
        if (records[record.key] !== record || record.state == WorkRecordState.DONE) {
            subscriber.releaseDone.complete(Unit)
            return TransitionActions()
        }
        record.subscribers.remove(subscriber)
        if (record.subscribers.isNotEmpty()) {
            subscriber.releaseDone.complete(Unit)
            signalLocked()
            return TransitionActions()
        }
        record.cleanupSubscribers += subscriber
        val actions = transitionLastSubscriberLocked(record)
        signalLocked()
        return actions
    }

    private fun transitionLastSubscriberLocked(record: WorkRecord): TransitionActions {
        return when (record.state) {
            WorkRecordState.QUEUED -> {
                execution.removeRecordLocked(record)
                TransitionActions()
            }
            WorkRecordState.RUNNING,
            WorkRecordState.RETRY_WAIT,
            -> {
                record.cancelRequested = true
                TransitionActions(job = record.worker)
            }
            WorkRecordState.READY ->
                TransitionActions(disposal = execution.planDisposalLocked(record))
            WorkRecordState.RETIRING,
            WorkRecordState.DONE,
            -> TransitionActions()
        }
    }

    private fun checkSubscriptionLive(subscriber: WorkSubscriber) {
        if (subscriber.detached || subscriber.released) {
            throw CancellationException("Work subscription is closed")
        }
    }

    internal fun promoteLocked(record: WorkRecord, requested: WorkPriority) {
        if (closed || records[record.key] !== record || record.state == WorkRecordState.RETIRING ||
            record.cancelRequested
        ) return
        if (requested.ordinal < record.priority.value.ordinal) {
            record.priority.value = requested
            record.dependencies.values.forEach { promoteLocked(it, requested) }
            signalLocked()
        }
    }

    private fun checkOpenLocked() {
        if (closed || !coordinatorJob.isActive) throw WorkCoordinatorClosedException()
    }

    private fun signalLocked() {
        registry.signalLocked()
    }

    private suspend fun awaitCloseCompletion() {
        closeCompletion.await()
        val failure = mutex.withLock { registry.disposalFailure }
        failure?.let { throw it }
    }

    private suspend fun recordObserverFailure(failure: Throwable) {
        withContext(NonCancellable) {
            mutex.withLock {
                registry.registerDisposalFailureLocked(failure)
                if (closed && records.isEmpty()) registry.completeCloseLocked()
            }
        }
    }

    private data class TransitionActions(
        val job: Job? = null,
        val disposal: DisposalPlan? = null,
    )
}
