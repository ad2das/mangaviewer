package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits

internal class WorkRegistry(val limits: WorkLimits) {
    val mutex = Mutex()
    val records = LinkedHashMap<WorkKey<*>, WorkRecord>()
    val retiredAuthEpochs = HashMap<String, Long>()
    val admission = WorkAdmission(limits)
    val closeCompletion = CompletableDeferred<Unit>()
    var wakeup = CompletableDeferred<Unit>()
    var sequence = 0L
    var closed = false
    var disposalFailure: Throwable? = null

    fun signalLocked() {
        if (!wakeup.isCompleted) wakeup.complete(Unit)
        wakeup = CompletableDeferred()
    }

    fun completeCloseLocked() {
        disposalFailure?.let { closeCompletion.completeExceptionally(it) }
            ?: closeCompletion.complete(Unit)
    }

    fun registerDisposalFailureLocked(failure: Throwable) {
        disposalFailure = disposalFailure ?: failure
    }
}
