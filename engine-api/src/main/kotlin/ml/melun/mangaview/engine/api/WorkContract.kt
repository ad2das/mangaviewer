package ml.melun.mangaview.engine.api

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

enum class WorkPriority {
    FOCUS, VISIBLE, INTERACTIVE, NEXT_IMAGE, NEXT_EPISODE, ARTWORK, OFFLINE;

    /** Every speculative request is below the current visible/explicit interactive demand. */
    val background: Boolean get() = ordinal >= NEXT_IMAGE.ordinal
}

/** CONTROL parents may wait for children, but never consume a physical-work permit. */
enum class WorkDomain { CONTROL, NETWORK, BODY, DECODE, STORAGE, UPLOAD, BROWSER }

data class WorkKey<T : Any>(
    val principal: String,
    val resource: String,
    val operation: String,
    val contentRevision: String,
    val resultType: Class<T>,
) {
    init {
        require(listOf(principal, resource, operation, contentRevision).all(String::isNotBlank))
    }
}

data class WorkLimits(
    val network: Int = 6,
    val bodies: Int = 4,
    val backgroundNetwork: Int = 2,
    val decodes: Int = 2,
    val storage: Int = 1,
    val uploads: Int = 1,
    val queued: Int = 256,
) {
    init {
        require(network > 0 && bodies in 1..network && backgroundNetwork in 1..network)
        require(decodes > 0 && storage > 0 && uploads == 1 && queued > 0)
    }
}

interface WorkContext {
    val authEpoch: Long
    val attemptToken: Long
    val attempt: Int
    val priority: StateFlow<WorkPriority>

    /**
     * CONTROL executions only. Retains the dependency through disposal of this attempt's
     * result, or releases it before a failed attempt retries. The returned value is borrowed
     * for that lifetime. Calls must finish before execute returns; contexts cannot be reused.
     * Dependencies must share the parent's principal and authentication epoch.
     */
    suspend fun <T : Any> dependency(request: WorkRequest<T>): T

    /**
     * Borrows a dependency only for this block, awaiting release on every exit. The block
     * must finish all use of the borrowed value; its result must own independent resources.
     * Supply disposeAbandoned when the block creates a resource: cleanup failure or cancellation
     * after creation invokes it before throwing. Immutable values need no disposer.
     */
    suspend fun <T : Any, R : Any> useDependency(
        request: WorkRequest<T>,
        disposeAbandoned: suspend (R) -> Unit = {},
        block: suspend (T) -> R,
    ): R
}

/** Only immutable results or reference-counted immutable-file handles may be shared. */
class WorkRequest<T : Any>(
    val key: WorkKey<T>,
    val domain: WorkDomain,
    val priority: WorkPriority,
    val authEpoch: Long = 0L,
    retryDelaysMillis: List<Long> = emptyList(),
    val retryable: (Throwable) -> Boolean = { false },
    val execute: suspend (WorkContext) -> T,
    val dispose: suspend (T) -> Unit = {},
) {
    val retryDelaysMillis: List<Long> = retryDelaysMillis.toList()

    init {
        require(authEpoch >= 0L && retryDelaysMillis.all { it >= 0L })
    }
}

interface WorkLease<T : Any> : Closeable {
    val value: T
    fun promote(priority: WorkPriority)
    /** close requests release; this returns after this subscriber no longer owns the result. */
    suspend fun awaitReleased()
}

/** Owns one subscription immediately, including while execution is queued or running. */
interface WorkSubscription<T : Any> : Closeable {
    /** Repeated waits share the same subscriber and do not create additional ownership. */
    suspend fun await(): T
    fun promote(priority: WorkPriority)
    /** Releases this subscription; the final subscriber waits for actual execution/disposal. */
    suspend fun awaitReleased()
}

data class WorkOwnershipSnapshot(
    val closed: Boolean,
    val queued: Int,
    val active: Int,
    val retiring: Int,
    val subscribers: Int,
    val retainedResults: Int,
)

interface WorkCoordinatorPort {
    /** Registers ownership without waiting for the physical operation to complete. */
    suspend fun <T : Any> submit(request: WorkRequest<T>): WorkSubscription<T>
    suspend fun <T : Any> acquire(request: WorkRequest<T>): WorkLease<T>
    /** Permanently rejects this and older principal epochs; awaits their execution/disposal. */
    suspend fun invalidate(principal: String, authEpoch: Long)
    suspend fun snapshot(): WorkOwnershipSnapshot
    /** Idempotent: every caller waits for the same actual resource completion. */
    suspend fun close()
}
