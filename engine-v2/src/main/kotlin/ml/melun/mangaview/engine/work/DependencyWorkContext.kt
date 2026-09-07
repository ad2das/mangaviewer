package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkRequest

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
    private var pending = 0
    private val subscriptions = linkedMapOf<WorkSubscriber, CoordinatorSubscription<*>>()

    override suspend fun <T : Any> dependency(request: WorkRequest<T>): T {
        val (_, subscription) = register(request)
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
    ): R {
        val (subscriber, subscription) = register(request)
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

    private suspend fun <T : Any> register(request: WorkRequest<T>): Pair<WorkSubscriber, CoordinatorSubscription<T>> =
        state.mutex.withLock {
            validateLocked(request)
            val (child, subscriber) = coordinator.registerLocked(request)
            coordinator.promoteLocked(child, parent.priority.value)
            parent.dependencies[subscriber] = child
            val subscription = CoordinatorSubscription(coordinator, child, subscriber, request.key.resultType).also {
                subscriptions[subscriber] = it
                pending += 1
            }
            subscriber to subscription
        }

    private suspend fun finishCall() = withContext(NonCancellable) {
        state.mutex.withLock { pending -= 1 }
    }

    suspend fun seal() = state.mutex.withLock {
        open = false
        check(pending == 0) { "Dependency calls must finish before their parent execution returns" }
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
            if (visited.add(current)) pendingRecords.addAll(current.dependencies.values)
        }
        return false
    }
}
