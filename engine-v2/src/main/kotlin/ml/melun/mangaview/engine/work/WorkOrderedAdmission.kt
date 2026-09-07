package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal class WorkOrderedAdmission(
    private val coordinator: WorkCoordinator,
    private val state: WorkRegistry,
    private val execution: WorkExecution,
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
                record.worker = coordinator.workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    execution.runRecord(record)
                }
            }
        }
    }
}
