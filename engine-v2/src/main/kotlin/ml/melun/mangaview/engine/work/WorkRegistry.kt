package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority

/**
 * One permit waiter. It is enrolled under the registry mutex before its continuation exists: the
 * claim is stored on the ticket and delivered only when the waiting coroutine calls [bind], so no
 * lock acquisition is ever needed from inside the non-suspending bind/grant handoff. The
 * handoff is guarded by the ticket's own monitor, which grants and binds in O(1) without touching
 * the registry mutex; the registry-side [cancelled] flag is volatile so grant walks read it safely.
 */
internal class DomainPermitWaiter(
    val parent: WorkRecord,
    val domain: WorkDomain,
    val sequence: Long,
) {
    /** Set by the continuation's cancellation handler; read under the registry mutex. */
    @Volatile
    var cancelled = false

    private var continuation: CancellableContinuation<PermitClaim>? = null
    private var claim: PermitClaim? = null

    /** Registers the waiting continuation; returns a claim granted before the bind, if any. */
    fun bind(continuation: CancellableContinuation<PermitClaim>): PermitClaim? = synchronized(this) {
        this.continuation = continuation
        claim
    }

    /** Stores the claim and returns the continuation when one is already bound. */
    fun grant(value: PermitClaim): CancellableContinuation<PermitClaim>? = synchronized(this) {
        claim = value
        continuation
    }

    /** Fails a waiter whose parent stopped running; a bound continuation is cancelled at once. */
    fun cancelDelivery(cause: CancellationException) {
        val bound = synchronized(this) {
            cancelled = true
            continuation
        }
        bound?.cancel(cause)
    }
}

/**
 * A permit handed to a continuation that is cancelled before its coroutine receives the value is
 * returned through [claimReturn] instead of leaking. The coordinator schedules that release on its
 * cleanup scope because the callback can run while the registry mutex is held (a resume of an
 * already-cancelled continuation invokes it synchronously) and it must not lock or suspend there.
 */
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

    /** Per-domain permit waiters, ordered by (live priority, sequence) at every grant walk. */
    private val permitWaiters = Array(WorkDomain.entries.size) { mutableListOf<DomainPermitWaiter>() }
    private var waiterSequence = 0L
    private var granting = false

    /** Test seam: counts how many permit waiters a grant walk actually resumed. */
    internal var grantResumeCount = 0L

    /** Installed by the coordinator; releases a claim whose continuation was cancelled first. */
    internal var claimReturn: ((PermitClaim) -> Unit)? = null

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

    /** True while an equal-or-higher priority waiter of [domain] is still eligible for a permit. */
    fun hasBlockingWaiterLocked(domain: WorkDomain, priority: WorkPriority): Boolean {
        val waiters = permitWaiters[domain.ordinal]
        if (waiters.isEmpty()) return false
        return waiters.any { !it.cancelled && it.parent.priority.value.ordinal <= priority.ordinal }
    }

    fun registerWaiterLocked(domain: WorkDomain, parent: WorkRecord): DomainPermitWaiter {
        val waiter = DomainPermitWaiter(parent, domain, waiterSequence++)
        permitWaiters[domain.ordinal] += waiter
        return waiter
    }

    /**
     * The single release path for every physical permit. Releasing and granting happen under one
     * registry critical section so a waiter admitted by this release is handed the claim before any
     * new acquisition can barge in, and so an eligibility change produced by the release (the
     * background-upload rule flipping when the last foreground decode ends) is evaluated at once.
     */
    fun releaseClaimLocked(claim: PermitClaim) {
        admission.release(claim)
        grantLocked()
    }

    /**
     * Hands out every permit that a waiter can take right now, in (live priority, sequence) order,
     * skipping waiters a domain sub-limit still blocks and continuing past them like the scheduler
     * skips unaffordable candidates. Live priority is read here, so a promotion applies without
     * re-sorting anyone. Waiters whose parent stopped running are failed here as well, which is what
     * preserves the old await guard for transitions that do not cancel the parent coroutine.
     */
    fun grantLocked() {
        if (granting) return
        granting = true
        try {
            for (domain in WorkDomain.entries) grantDomainLocked(domain)
        } finally {
            granting = false
        }
    }

    private fun grantDomainLocked(domain: WorkDomain) {
        val waiters = permitWaiters[domain.ordinal]
        if (waiters.isEmpty()) return
        waiters.removeAll { it.cancelled }
        if (waiters.isEmpty()) return
        waiters.sortWith(compareBy({ it.parent.priority.value.ordinal }, { it.sequence }))
        var index = 0
        while (index < waiters.size) {
            val waiter = waiters[index]
            if (waiter.cancelled) {
                waiters.removeAt(index)
                continue
            }
            if (closed || waiter.parent.cancelRequested || waiter.parent.state != WorkRecordState.RUNNING) {
                waiters.removeAt(index)
                waiter.cancelDelivery(CancellationException("Parent work is no longer running"))
                continue
            }
            val claim = admission.tryAcquire(domain, waiter.parent.priority.value)
            if (claim == null) {
                index++
                continue
            }
            waiters.removeAt(index)
            resumeWaiter(waiter, claim)
        }
    }

    private fun resumeWaiter(waiter: DomainPermitWaiter, claim: PermitClaim) {
        val continuation = waiter.grant(claim) ?: return
        grantResumeCount += 1
        continuation.resume(claim) { _, value, _ ->
            claimReturn?.invoke(value)
        }
    }
}
