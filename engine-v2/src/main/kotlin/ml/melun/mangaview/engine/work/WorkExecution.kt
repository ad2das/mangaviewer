package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkContext

internal class WorkExecution(
    private val coordinator: WorkCoordinator,
    private val state: WorkRegistry,
    private val attemptTokens: AtomicLong,
) {
    suspend fun runRecord(record: WorkRecord) {
        var succeeded = false
        var result: OwnedWorkResult? = null
        var failure: Throwable? = null
        try {
            result = executeWithRetries(record)
            succeeded = true
        } catch (throwable: Throwable) {
            failure = throwable
        }
        withContext(NonCancellable) {
            if (succeeded) {
                finishSuccess(record, checkNotNull(result))
            } else {
                finishFailure(record, checkNotNull(failure))
            }
        }
    }

    suspend fun applyCancelActions(actions: List<CancelActions>) {
        val waiters = actions.flatMap { it.waiters }
        val jobs = actions.flatMap { it.jobs }
        val disposals = actions.flatMap { it.disposals }
        waiters.forEach { it.ready.cancel(CancellationException("Work was cancelled")) }
        jobs.forEach { it.cancel() }
        disposals.forEach { disposeRecord(it) }
    }

    fun cancelRecordLocked(record: WorkRecord): CancelActions {
        if (record.state == WorkRecordState.DONE) return CancelActions(emptyList(), emptyList(), emptyList())
        val waiters = record.subscribers.filterNot { it.delivered }
        waiters.forEach { it.detached = true }
        record.subscribers.removeAll(waiters.toSet())
        if (record.subscribers.isEmpty()) {
            record.cleanupSubscribers += waiters
        } else {
            waiters.forEach { it.releaseDone.complete(Unit) }
        }
        val jobs = mutableListOf<Job>()
        val disposals = mutableListOf<DisposalPlan>()
        when (record.state) {
            WorkRecordState.QUEUED -> removeRecordLocked(record)
            WorkRecordState.RUNNING,
            WorkRecordState.RETRY_WAIT,
            -> {
                record.cancelRequested = true
                record.worker?.let(jobs::add)
            }
            WorkRecordState.READY -> {
                record.cancelRequested = true
                if (record.subscribers.isEmpty()) planDisposalLocked(record)?.let(disposals::add)
            }
            WorkRecordState.RETIRING,
            WorkRecordState.DONE,
            -> Unit
        }
        state.signalLocked()
        return CancelActions(waiters, jobs, disposals)
    }

    fun planDisposalLocked(record: WorkRecord): DisposalPlan? {
        if (record.state != WorkRecordState.READY || record.disposeStarted) return null
        val result = record.result ?: return null
        record.state = WorkRecordState.RETIRING
        record.disposeStarted = true
        return DisposalPlan(record, result)
    }

    suspend fun disposeRecord(plan: DisposalPlan) {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                plan.result.dispose()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                state.mutex.withLock {
                    if (state.records[plan.record.key] === plan.record) {
                        releasePermitLocked(plan.record)
                        plan.record.result = null
                        removeRecordLocked(plan.record, failure)
                    }
                }
            }
        }
    }

    fun releasePermitLocked(record: WorkRecord) {
        record.permit?.let {
            state.admission.release(it)
            record.permit = null
        }
    }

    fun removeRecordLocked(record: WorkRecord, failure: Throwable? = null) {
        if (state.records[record.key] !== record) return
        releasePermitLocked(record)
        record.state = WorkRecordState.DONE
        state.records.remove(record.key)
        if (failure == null) record.completion.complete(Unit) else {
            state.registerDisposalFailureLocked(failure)
            record.completion.completeExceptionally(failure)
        }
        val cleanupFailure = failure
        record.cleanupSubscribers.forEach { subscriber ->
            if (cleanupFailure == null) subscriber.releaseDone.complete(Unit)
            else subscriber.releaseDone.completeExceptionally(cleanupFailure)
        }
        record.cleanupSubscribers.clear()
        state.signalLocked()
        if (state.closed && state.records.isEmpty()) state.completeCloseLocked()
    }

    private suspend fun executeWithRetries(record: WorkRecord): OwnedWorkResult {
        var attempt = 0
        while (true) {
            val token = beginAttempt(record, attempt)
            try {
                return executeAttempt(record, token, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val delayMillis = record.request.retryDelaysMillis.getOrNull(attempt) ?: throw failure
                val retry = shouldRetry(record, failure)
                if (!retry || !prepareRetry(record)) throw failure
                attempt += 1
                delay(delayMillis)
                if (!awaitRetryPermit(record)) throw CancellationException("Work retry was cancelled")
            }
        }
    }

    private suspend fun executeAttempt(record: WorkRecord, token: Long, attempt: Int): OwnedWorkResult {
        val context = DependencyWorkContext(coordinator, record, token, attempt)
        var result: OwnedWorkResult? = null
        try {
            result = record.executeAny(context)
            context.seal()
            val owned = result
            return OwnedWorkResult(owned.value) { context.disposeWithDependencies(owned.dispose) }
        } catch (failure: Throwable) {
            try {
                context.disposeWithDependencies { result?.dispose?.invoke() }
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private suspend fun shouldRetry(record: WorkRecord, failure: Throwable): Boolean = try {
        record.request.retryable(failure)
    } catch (retryFailure: Throwable) {
        retryFailure.addSuppressed(failure)
        throw retryFailure
    }

    private suspend fun beginAttempt(record: WorkRecord, attempt: Int): Long {
        return state.mutex.withLock {
            if (record.cancelRequested || state.closed || record.permit == null) {
                throw CancellationException("Work attempt was cancelled")
            }
            record.attempt = attempt
            attemptTokens.incrementAndGet()
        }
    }

    private suspend fun prepareRetry(record: WorkRecord): Boolean {
        return state.mutex.withLock {
            if (record.cancelRequested || state.closed || record.subscribers.isEmpty()) return@withLock false
            val permit = record.permit ?: return@withLock false
            state.admission.release(permit)
            record.permit = null
            record.retryReady = false
            record.state = WorkRecordState.RETRY_WAIT
            state.signalLocked()
            true
        }
    }

    private suspend fun awaitRetryPermit(record: WorkRecord): Boolean {
        while (true) {
            var acquired = false
            var stopped = false
            var notification: CompletableDeferred<Unit>? = null
            state.mutex.withLock {
                if (record.cancelRequested || state.closed || record.subscribers.isEmpty()) {
                    stopped = true
                } else if (record.state == WorkRecordState.RUNNING && record.permit != null) {
                    acquired = true
                } else {
                    record.retryReady = true
                    state.signalLocked()
                    notification = state.wakeup
                }
            }
            if (acquired) return true
            if (stopped) return false
            notification?.await()
        }
    }

    private suspend fun finishSuccess(record: WorkRecord, result: OwnedWorkResult) {
        var waiters = emptyList<WorkSubscriber>()
        var disposal: DisposalPlan? = null
        var mismatch: Throwable? = null
        state.mutex.withLock {
            if (state.records[record.key] !== record || record.state == WorkRecordState.DONE) return@withLock
            val value = result.value
            val valid = record.key.resultType.isInstance(value)
            if (!valid) {
                waiters = record.subscribers.toList()
                record.subscribers.forEach { it.detached = true }
                record.cleanupSubscribers += waiters
                record.subscribers.clear()
                mismatch = WorkResultTypeMismatchException(record.key.resultType, value.javaClass)
                record.result = result
                record.state = WorkRecordState.RETIRING
                record.disposeStarted = true
                disposal = DisposalPlan(record, result)
            } else if (record.cancelRequested || record.subscribers.isEmpty()) {
                record.result = result
                record.state = WorkRecordState.RETIRING
                record.disposeStarted = true
                disposal = DisposalPlan(record, result)
            } else {
                record.result = result
                record.state = WorkRecordState.READY
                releasePermitLocked(record)
                waiters = record.subscribers.toList()
                state.signalLocked()
            }
        }
        mismatch?.let { error -> waiters.forEach { it.ready.completeExceptionally(error) } }
        disposal?.let { disposeRecord(it) }
        if (mismatch == null) waiters.forEach { it.ready.complete(result.value) }
    }

    private suspend fun finishFailure(record: WorkRecord, failure: Throwable) {
        var waiters = emptyList<WorkSubscriber>()
        state.mutex.withLock {
            if (state.records[record.key] !== record || record.state == WorkRecordState.DONE) return@withLock
            waiters = record.subscribers.toList()
            record.subscribers.forEach { it.detached = true }
            record.cleanupSubscribers += waiters
            record.subscribers.clear()
            releasePermitLocked(record)
            removeRecordLocked(record)
        }
        waiters.forEach { it.ready.completeExceptionally(failure) }
    }
}
