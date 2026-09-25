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

    private fun startAvailableLocked() {
        while (true) {
            val candidates = state.records.values
                .asSequence()
                .filter {
                    it.state == WorkRecordState.QUEUED ||
                        (it.state == WorkRecordState.RETRY_WAIT && it.retryReady)
                }
                .sortedWith(compareBy<WorkRecord> { it.priority.value.ordinal }.thenBy { it.sequence })
                .toList()
            var selected: WorkRecord? = null
            var permit: PermitClaim? = null
            for (candidate in candidates) {
                val candidatePermit = state.admission.tryAcquire(candidate.requestDomain, candidate.priority.value)
                if (candidatePermit != null) {
                    selected = candidate
                    permit = candidatePermit
                    break
                }
            }
            val record = selected ?: return
            record.permit = checkNotNull(permit)
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
