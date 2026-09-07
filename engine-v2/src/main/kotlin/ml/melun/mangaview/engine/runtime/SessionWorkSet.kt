package ml.melun.mangaview.engine.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.SessionWorkOwnership
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkSubscription

internal class SessionDemand<T : Any>(
    val request: WorkRequest<T>,
    val accept: (T) -> Unit,
) {
    suspend fun subscribe(coordinator: WorkCoordinatorPort): WorkSubscription<T> = coordinator.submit(request)
}

/** Owner-thread registry for this session's subscriptions, including acknowledged retirement. */
internal class SessionWorkSet(
    private val scope: CoroutineScope,
    private val coordinator: WorkCoordinatorPort,
    private val reportFailure: (WorkKey<*>, Throwable) -> Unit,
) {
    private val owner = Thread.currentThread()
    private val entries = linkedMapOf<WorkKey<*>, Entry>()
    private var desired = emptyMap<WorkKey<*>, SessionDemand<*>>()
    private var closed = false
    private var cleanupFailure: Throwable? = null

    fun reconcile(demands: List<SessionDemand<*>>) {
        checkOwner()
        if (closed) return
        desired = demands.associateBy { it.request.key }
        entries.values.toList().forEach { entry ->
            val demand = desired[entry.key]
            if (demand == null) retire(entry)
            else entry.subscription?.promote(demand.request.priority)
        }
        desired.values.forEach(::startIfAbsent)
    }

    fun clear() {
        checkOwner()
        desired = emptyMap()
        entries.values.toList().forEach(::retire)
    }

    fun retryFailures() {
        checkOwner()
        entries.values.filter { it.failed }.forEach {
            if (it.job?.isCompleted == true) entries.remove(it.key) else it.retryRequested = true
        }
        desired.values.forEach(::startIfAbsent)
    }

    fun ownership(): SessionWorkOwnership {
        checkOwner()
        return SessionWorkOwnership(entries.values.count { !it.retiring && !it.failed },
            entries.values.count { it.ready }, entries.values.count { it.retiring },
            entries.values.count { it.failed })
    }

    suspend fun close() {
        checkOwner()
        closed = true
        clear()
        withContext(NonCancellable) {
            entries.values.toList().forEach { it.job?.join() }
        }
        cleanupFailure?.let { throw it }
    }

    private fun startIfAbsent(demand: SessionDemand<*>) {
        if (closed || !scope.isActive || entries.containsKey(demand.request.key)) return
        val entry = Entry(demand.request.key)
        entries[entry.key] = entry
        entry.job = scope.launch(start = CoroutineStart.LAZY) { run(entry, demand) }
        entry.job!!.start()
    }

    private suspend fun <T : Any> run(entry: Entry, demand: SessionDemand<T>) {
        try {
            val subscription = demand.subscribe(coordinator)
            entry.subscription = subscription
            desired[entry.key]?.let { subscription.promote(it.request.priority) }
            val result = subscription.await()
            if (entry.retiring || !desired.containsKey(entry.key) || closed) return
            entry.ready = true
            demand.accept(result)
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            entry.failed = true
            if (!entry.retiring && !closed) notifyFailure(entry.key, failure)
        } finally {
            finish(entry)
        }
    }

    private suspend fun finish(entry: Entry) = withContext(NonCancellable) {
        try { entry.subscription?.awaitReleased() } catch (failure: Throwable) {
            registerCleanupFailure(failure)
            entry.failed = true
            if (!closed) notifyFailure(entry.key, failure)
        }
        entry.subscription = null
        entry.ready = false
        if (!entry.failed || entry.retiring || entry.retryRequested || closed) {
            if (entries[entry.key] === entry) entries.remove(entry.key)
            desired[entry.key]?.let(::startIfAbsent)
        }
    }

    private fun notifyFailure(key: WorkKey<*>, failure: Throwable) {
        try { reportFailure(key, failure) } catch (observerFailure: Throwable) {
            if (observerFailure !== failure) observerFailure.addSuppressed(failure)
            registerCleanupFailure(observerFailure)
        }
    }

    private fun registerCleanupFailure(failure: Throwable) {
        val first = cleanupFailure
        if (first == null) cleanupFailure = failure else if (first !== failure) {
            first.addSuppressed(failure)
        }
    }

    private fun retire(entry: Entry) {
        if (entry.failed && entry.job?.isCompleted == true) {
            entries.remove(entry.key)
            return
        }
        entry.retiring = true
        entry.subscription?.close()
        entry.job?.cancel()
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Session work is owner-thread confined" }

    private class Entry(val key: WorkKey<*>) {
        var job: Job? = null
        var subscription: WorkSubscription<*>? = null
        var ready = false
        var retiring = false
        var failed = false
        var retryRequested = false
    }
}
