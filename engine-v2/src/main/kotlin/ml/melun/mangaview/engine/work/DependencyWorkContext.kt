package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkMetadata
import ml.melun.mangaview.engine.runtime.EngineStageProbe

/** One execution attempt owns these edges; the registry mutex protects their entire lifetime. */
internal class DependencyWorkContext(
    private val coordinator: WorkCoordinator,
    private val parent: WorkRecord,
    override val attemptToken: Long,
    override val attempt: Int,
) : WorkContext {
    override val authEpoch = parent.authEpoch
    override val priority get() = parent.priority
    private val state get() = coordinator.registry
    private var open = true
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val subscriptions = linkedMapOf<WorkSubscriber, CoordinatorSubscription<*>>()

    override suspend fun publishMetadata(value: WorkMetadata) {
        currentCoroutineContext().ensureActive()
        state.mutex.withLock {
            check(open) { "Work execution context has finished" }
            if (state.closed || parent.cancelRequested || parent.state != WorkRecordState.RUNNING ||
                state.records[parent.key] !== parent) throw CancellationException("Work is no longer running")
            check(parent.metadata.value == null || parent.metadata.value == value) { "Conflicting immutable work metadata" }
            parent.metadata.value = value
        }
    }

    override suspend fun <T : Any> dependency(request: WorkRequest<T>): T {
        val registered = register(request, onlyIfRegistered = false) ?: error("A plain dependency is always registered")
        val (_, subscription) = registered
        try {
            return subscription.await()
        } finally {
            finishCall()
        }
    }

    override suspend fun <T : Any, R : Any> useDependency(
        request: WorkRequest<T>,
        disposeAbandoned: suspend (R) -> Unit,
        block: suspend (T) -> R,
    ): R = checkNotNull(borrow(request, disposeAbandoned, block, onlyIfRegistered = false))

    override suspend fun <T : Any, R : Any> useRegisteredDependency(
        request: WorkRequest<T>,
        disposeAbandoned: suspend (R) -> Unit,
        block: suspend (T) -> R,
    ): R? = borrow(request, disposeAbandoned, block, onlyIfRegistered = true)

    /**
     * One borrow path for both dependency flavours. [onlyIfRegistered] turns the registration into a
     * lookup: when no live record owns the key the caller gets null and runs the operation itself,
     * which is what lets a folded operation skip a child record it would have been the only user of.
     */
    private suspend fun <T : Any, R : Any> borrow(
        request: WorkRequest<T>,
        disposeAbandoned: suspend (R) -> Unit,
        block: suspend (T) -> R,
        onlyIfRegistered: Boolean,
    ): R? {
        val registered = register(request, onlyIfRegistered) ?: return null
        val (subscriber, subscription) = registered
        var result: R? = null
        var failure: Throwable? = null
        try {
            try {
                result = block(subscription.await())
            } catch (error: Throwable) {
                failure = error
            }
            withContext(NonCancellable) {
                try { release(subscriber, subscription) } catch (cleanup: Throwable) {
                    val original = failure
                    if (original == null) failure = cleanup else if (original !== cleanup) original.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
            currentCoroutineContext().ensureActive()
            return checkNotNull(result)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { result?.let { disposeAbandoned(it) } } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
            }
            throw error
        } finally {
            finishCall()
        }
    }

    /**
     * Folding a physical operation into this CONTROL record removed the record that used to carry the
     * operation's permit. Borrow the same permit for the block: limits and the background rule stay
     * those of the operation's own domain, so a speculative decode still queues behind a blocked
     * visible one. The permit is released under the registry lock, which signals the record waiters.
     */
    override suspend fun <R : Any> withDomainPermit(domain: WorkDomain, block: suspend () -> R): R {
        val claim = awaitDomainPermit(domain)
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                state.mutex.withLock {
                    // Signal even if an accounting check throws, or every waiter on this domain would
                    // sleep for a wakeup that can no longer arrive.
                    try { state.admission.release(claim) } finally { state.signalLocked() }
                }
            }
        }
    }

    private suspend fun awaitDomainPermit(domain: WorkDomain): PermitClaim {
        while (true) {
            currentCoroutineContext().ensureActive()
            var claim: PermitClaim? = null
            var notification: CompletableDeferred<Unit>? = null
            state.mutex.withLock {
                if (parent.cancelRequested || parent.state != WorkRecordState.RUNNING || state.closed) {
                    throw CancellationException("Parent work is no longer running")
                }
                claim = state.admission.tryAcquire(domain, parent.priority.value)
                if (claim == null) notification = state.wakeup
            }
            claim?.let {
                if (domain == WorkDomain.UPLOAD) parent.request.probe?.let { probe ->
                    EngineStageProbe.record(probe, EngineStageProbe.PERMIT_UPLOAD, System.nanoTime())
                }
                return it
            }
            // No registry lock is held while waiting; a release signals this exact notification.
            notification?.await()
        }
    }

    private suspend fun <T : Any> register(
        request: WorkRequest<T>,
        onlyIfRegistered: Boolean,
    ): Pair<WorkSubscriber, CoordinatorSubscription<T>>? {
        val claimed = state.mutex.withLock {
            validateLocked(request)
            // A lookup registers nothing: no claim to own the operation, so the caller runs it itself.
            if (onlyIfRegistered && !ownedLocked(request.key)) false
            else {
                pending.incrementAndGet()
                true
            }
        }
        if (!claimed) return null
        var registered = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val waitId = Any()
                var retiring: WorkRecord? = null
                val result = state.mutex.withLock {
                    validateLocked(request)
                    val previous = state.records[request.key]
                    if (previous != null && (previous.state == WorkRecordState.RETIRING || previous.cancelRequested)) {
                        retiring = previous
                        parent.retirementWaits[waitId] = previous
                        null
                    } else registerChildLocked(request)
                }
                if (result != null) {
                    registered = true
                    return result
                }
                // No registry lock or child lease is held while its prior owner finishes cleanup.
                try { checkNotNull(retiring).completion.await() }
                finally {
                    withContext(NonCancellable) { state.mutex.withLock { parent.retirementWaits.remove(waitId) } }
                }
            }
        } finally {
            if (!registered) finishCall()
        }
    }

    private fun ownedLocked(key: WorkKey<*>): Boolean {
        val existing = state.records[key] ?: return false
        return !existing.cancelRequested && existing.state != WorkRecordState.RETIRING
    }

    private fun <T : Any> registerChildLocked(request: WorkRequest<T>): Pair<WorkSubscriber, CoordinatorSubscription<T>> {
        val (child, subscriber) = coordinator.registerLocked(request)
        coordinator.promoteLocked(child, parent.priority.value)
        parent.dependencies[subscriber] = child
        val subscription = CoordinatorSubscription(coordinator, child, subscriber, request.key.resultType)
        subscriptions[subscriber] = subscription
        return subscriber to subscription
    }

    // The count is atomic and the decrement is not suspending: every finishCall and the seal run on
    // the record's own coroutine, so the registry lock added only a handoff to a hot path.
    private fun finishCall() { pending.decrementAndGet() }

    fun seal() {
        open = false
        check(pending.get() == 0) { "Dependency calls must finish before their parent execution returns" }
    }

    suspend fun disposeWithDependencies(disposeParent: suspend () -> Unit) = withContext(NonCancellable) {
        val owned = state.mutex.withLock {
            open = false
            subscriptions.toList()
        }
        var failure: Throwable? = null
        try {
            disposeParent()
        } catch (error: Throwable) {
            failure = error
        }
        // Close every child before waiting; independent children may need each other's cleanup.
        owned.forEach { it.second.close() }
        for ((subscriber, subscription) in owned) {
            try {
                release(subscriber, subscription)
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun release(subscriber: WorkSubscriber, subscription: CoordinatorSubscription<*>) {
        try {
            subscription.awaitReleased()
        } finally {
            state.mutex.withLock {
                parent.dependencies.remove(subscriber)
                subscriptions.remove(subscriber)
            }
        }
    }

    private fun validateLocked(request: WorkRequest<*>) {
        check(open) { "Work execution context has finished" }
        if (parent.cancelRequested || parent.state != WorkRecordState.RUNNING || state.closed) {
            throw CancellationException("Parent work is no longer running")
        }
        check(parent.requestDomain == WorkDomain.CONTROL) { "Only CONTROL work may await dependencies" }
        require(request.key.principal == parent.key.principal && request.authEpoch == authEpoch) {
            "Dependency principal and authentication epoch must match its parent"
        }
        require(!reachesParent(request.key)) { "Work dependency cycle: ${parent.key} -> ${request.key}" }
    }

    private fun reachesParent(key: WorkKey<*>): Boolean {
        if (key == parent.key) return true
        val first = state.records[key] ?: return false
        val pendingRecords = ArrayDeque<WorkRecord>()
        val visited = mutableSetOf<WorkRecord>()
        pendingRecords.add(first)
        while (pendingRecords.isNotEmpty()) {
            val current = pendingRecords.removeLast()
            if (current === parent) return true
            if (visited.add(current)) {
                pendingRecords.addAll(current.dependencies.values)
                pendingRecords.addAll(current.retirementWaits.values)
            }
        }
        return false
    }
}
