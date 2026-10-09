package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorkOrderedAdmission(
    private val coordinator: WorkCoordinator,
    private val state: WorkRegistry,
    private val execution: WorkExecution,
    private val cleanupScope: CoroutineScope,
) {
    suspend fun schedulerLoop() {
        while (true) {
            var stop = false
            var notification: CompletableDeferred<Unit>? = null
            state.mutex.lock()
            try {
                if (state.closed) {
                    stop = true
                } else {
                    startAvailableLocked()
                    notification = state.wakeup
                }
            } finally {
                state.mutex.unlock()
            }
            if (stop) return
            notification?.await()
        }
    }

    /**
     * One pass over the queued set: the candidate list is filtered and sorted once, then walked in
     * (priority ordinal, sequence) order, starting every record admission can take. The old loop
     * rebuilt and re-sorted the whole list for every record it started (k*n*log n under the mutex);
     * the walk order is the same because priorities cannot change under the registry mutex, and an
     * unaffordable candidate is skipped so a later candidate of a cheaper domain still starts.
     */
    private fun startAvailableLocked() {
        val candidates = state.records.values
            .filter {
                it.state == WorkRecordState.QUEUED ||
                    (it.state == WorkRecordState.RETRY_WAIT && it.retryReady)
            }
            .sortedWith(compareBy<WorkRecord> { it.priority.value.ordinal }.thenBy { it.sequence })
        for (record in candidates) {
            val permit = state.admission.tryAcquire(record.requestDomain, record.priority.value) ?: continue
            record.permit = permit
            val retryContinuation = record.state == WorkRecordState.RETRY_WAIT
            record.retryReady = false
            record.state = WorkRecordState.RUNNING
            if (retryContinuation) {
                state.signalLocked()
            } else {
                val worker = coordinator.workerScope.launch(
                    start = coordinator.startMode(record.priority.value),
                ) {
                    execution.runRecord(record)
                }
                record.worker = worker
                observeWorkerCompletion(record, worker)
            }
        }
    }

    /**
     * A dispatched worker start can be cancelled before its body ever runs; the record would then
     * stay RUNNING forever, pinning its permit and wedging awaitReleased. Watch every worker job and
     * finalize a record its body never reached. The state read is safe without the lock: a normally
     * finished body has already moved the record to READY/RETIRING/DONE on its own thread before the
     * job completes, and the cancel path that abandons a start synchronized through the registry
     * mutex, so a RUNNING/RETRY_WAIT reading here is the abandoned case.
     */
    private fun observeWorkerCompletion(record: WorkRecord, worker: Job) {
        worker.invokeOnCompletion { cause ->
            if (record.state != WorkRecordState.RUNNING && record.state != WorkRecordState.RETRY_WAIT) {
                return@invokeOnCompletion
            }
            cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        execution.finalizeAbandonedWorker(record, cause)
                    } catch (failure: Throwable) {
                        coordinator.recordObserverFailure(failure)
                    }
                }
            }
        }
    }
}
