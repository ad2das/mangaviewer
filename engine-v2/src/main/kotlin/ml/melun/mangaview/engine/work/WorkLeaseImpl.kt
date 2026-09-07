package ml.melun.mangaview.engine.work

import java.util.concurrent.atomic.AtomicBoolean
import ml.melun.mangaview.engine.api.WorkLease
import ml.melun.mangaview.engine.api.WorkPriority

internal class CoordinatorLease<T : Any>(
    private val owner: WorkCoordinator,
    override val value: T,
    private val record: WorkRecord,
    private val subscriber: WorkSubscriber,
) : WorkLease<T> {
    private val closeRequested = AtomicBoolean(false)

    override fun promote(priority: WorkPriority) {
        owner.promote(record, priority)
    }

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) {
            owner.releaseAsync(record, subscriber)
        }
    }

    override suspend fun awaitReleased() {
        close()
        subscriber.releaseDone.await()
    }
}
