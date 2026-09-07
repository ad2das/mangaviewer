package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.WorkLease
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkSubscription

internal class CoordinatorSubscription<T : Any>(
    private val owner: WorkCoordinator,
    private val record: WorkRecord,
    private val subscriber: WorkSubscriber,
    private val resultType: Class<T>,
) : WorkSubscription<T> {
    private val closeRequested = AtomicBoolean(false)

    override suspend fun await(): T {
        if (closeRequested.get()) throw CancellationException("Work subscription is closed")
        return try {
            val value = owner.awaitSubscription(record, subscriber)
            resultType.cast(value)
        } catch (failure: Throwable) {
            try {
                close()
                withContext(NonCancellable) { subscriber.releaseDone.await() }
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun promote(priority: WorkPriority) {
        if (!closeRequested.get()) owner.promote(record, priority)
    }

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) owner.releaseAsync(record, subscriber)
    }

    override suspend fun awaitReleased() {
        close()
        subscriber.releaseDone.await()
    }

    internal fun lease(value: T): WorkLease<T> = CoordinatorLease(owner, value, record, subscriber)
}
