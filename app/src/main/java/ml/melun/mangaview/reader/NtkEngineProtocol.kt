package ml.melun.mangaview.reader

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Single Kotlin-side authority for renderer admission, retirement, and destruction. */
internal enum class ProtocolPhase {
    LIVE_DETACHED,
    SURFACE_ATTACHING,
    SURFACE_READY,
    LIVE_ATTACHED,
    DETACH_CLOSING,
    RETIRED_BLOCKED,
    RETIRED_DISPATCHABLE,
    FAILED,
    CLOSING,
    CLOSED
}

internal enum class NtkProtocolAdmission {
    SURFACE_ATTACH,
    PREPARATION,
    LIVE,
    RELEASE,
    DEBUG
}

internal data class NtkPreparedOperation<T>(val value: T)

internal data class NtkSurfaceAttachKey(
    val engineGeneration: Long,
    val attachGeneration: Long,
    val surfaceEpoch: Long
)

internal data class NtkDetachTicket(
    val key: NtkSurfaceAttachKey,
    val previousPhase: ProtocolPhase
)

internal class NtkAsyncSurfaceOperation internal constructor(
    val key: NtkSurfaceAttachKey
) {
    internal var completed = false
}

/** Lock-owned state shared by production release dispatch and deterministic JVM tests. */
internal class NtkReleaseRegistration<R, A : Any>(
    val request: R,
    val completion: (A) -> Unit
) {
    var nativeCallActive = false
    var stagedAck: A? = null
    var nativeDispatchable = false
    var scheduled = false
    var running = false
    var delivered = false
}

/** Native boundary abstraction; JVM tests provide a barrier-controlled fake implementation. */
internal fun interface NtkProtocolNativeAdapter<P, N> {
    fun call(prepared: P): N
}

/** Deterministic barriers used by JVM protocol tests; production uses [None]. */
internal interface NtkProtocolDeterministicHooks {
    fun onDetachAdmissionClosed() = Unit
    fun onDetachQuiescent() = Unit
    fun afterNativeReturnBeforeBookkeeping(operation: String) = Unit

    object None : NtkProtocolDeterministicHooks
}

/**
 * Android-free coordinator used directly by the production engine and by concurrency tests.
 * The lock is never held across a native call. An admitted operation remains counted until all
 * Kotlin post-native bookkeeping has completed under the same lock.
 */
internal class NtkEngineProtocolCoordinator(
    private val hooks: NtkProtocolDeterministicHooks = NtkProtocolDeterministicHooks.None,
    initialPhase: ProtocolPhase = ProtocolPhase.LIVE_DETACHED
) {
    private val protocolLock = ReentrantLock()
    private val protocolChanged = protocolLock.newCondition()
    private var phase = initialPhase
    private var activeOperations = 0
    private var surfaceKey: NtkSurfaceAttachKey? = null

    internal fun <T> withProtocolLock(block: () -> T): T = protocolLock.withLock(block)

    internal fun phaseLocked(): ProtocolPhase {
        check(protocolLock.isHeldByCurrentThread)
        return phase
    }

    internal fun phaseSnapshot(): ProtocolPhase = protocolLock.withLock { phase }

    internal fun setPhaseLocked(next: ProtocolPhase) {
        check(protocolLock.isHeldByCurrentThread)
        phase = next
        protocolChanged.signalAll()
    }

    internal fun failLocked() {
        check(protocolLock.isHeldByCurrentThread)
        if (phase != ProtocolPhase.CLOSED) phase = ProtocolPhase.FAILED
        protocolChanged.signalAll()
    }

    internal fun signalChangedLocked() {
        check(protocolLock.isHeldByCurrentThread)
        protocolChanged.signalAll()
    }

    internal fun awaitChangedUninterruptiblyLocked(predicate: () -> Boolean) {
        check(protocolLock.isHeldByCurrentThread)
        while (!predicate()) protocolChanged.awaitUninterruptibly()
    }

    internal fun activeOperationsLocked(): Int {
        check(protocolLock.isHeldByCurrentThread)
        return activeOperations
    }

    internal fun currentSurfaceKeyLocked(): NtkSurfaceAttachKey? {
        check(protocolLock.isHeldByCurrentThread)
        return surfaceKey
    }

    internal fun beginSurfaceAttach(
        key: NtkSurfaceAttachKey
    ): NtkAsyncSurfaceOperation? = protocolLock.withLock {
        if (phase != ProtocolPhase.LIVE_DETACHED || surfaceKey != null ||
            key.engineGeneration <= 0L || key.attachGeneration <= 0L ||
            key.surfaceEpoch <= 0L
        ) return null
        surfaceKey = key
        phase = ProtocolPhase.SURFACE_ATTACHING
        activeOperations++
        protocolChanged.signalAll()
        NtkAsyncSurfaceOperation(key)
    }

    internal fun completeSurfaceAttachReady(
        operation: NtkAsyncSurfaceOperation
    ): Boolean = protocolLock.withLock {
        if (operation.completed || surfaceKey != operation.key) return false
        operation.completed = true
        check(activeOperations > 0)
        activeOperations--
        val publishable = phase == ProtocolPhase.SURFACE_ATTACHING
        if (publishable) phase = ProtocolPhase.SURFACE_READY
        if (activeOperations == 0) protocolChanged.signalAll()
        protocolChanged.signalAll()
        publishable
    }

    internal fun completeSurfaceAttachCancelled(
        operation: NtkAsyncSurfaceOperation
    ): Boolean = protocolLock.withLock {
        if (operation.completed || surfaceKey != operation.key) return false
        operation.completed = true
        check(activeOperations > 0)
        activeOperations--
        val detached = phase == ProtocolPhase.SURFACE_ATTACHING
        if (detached) {
            phase = ProtocolPhase.LIVE_DETACHED
            surfaceKey = null
        }
        if (activeOperations == 0) protocolChanged.signalAll()
        protocolChanged.signalAll()
        detached
    }

    internal fun completeSurfaceAttachFailed(
        operation: NtkAsyncSurfaceOperation
    ): Boolean = protocolLock.withLock {
        if (operation.completed || surfaceKey != operation.key) return false
        operation.completed = true
        check(activeOperations > 0)
        activeOperations--
        phase = ProtocolPhase.FAILED
        if (activeOperations == 0) protocolChanged.signalAll()
        protocolChanged.signalAll()
        true
    }

    internal fun publishSurface(key: NtkSurfaceAttachKey): Boolean =
        protocolLock.withLock {
            if (phase != ProtocolPhase.SURFACE_READY || surfaceKey != key) {
                return false
            }
            phase = ProtocolPhase.LIVE_ATTACHED
            protocolChanged.signalAll()
            true
        }

    internal fun closeSurfaceAdmission(
        key: NtkSurfaceAttachKey,
        onAdmissionClosedLocked: () -> Unit
    ): NtkDetachTicket? = protocolLock.withLock {
        if (surfaceKey != key || phase !in setOf(
                ProtocolPhase.SURFACE_ATTACHING,
                ProtocolPhase.SURFACE_READY,
                ProtocolPhase.LIVE_ATTACHED
            )
        ) return null
        val previous = phase
        phase = ProtocolPhase.DETACH_CLOSING
        try {
            onAdmissionClosedLocked()
        } catch (_: Throwable) {
            failLocked()
            return null
        }
        protocolChanged.signalAll()
        NtkDetachTicket(key, previous)
    }

    internal fun <T> awaitDetachQuiescenceAndPrepare(
        ticket: NtkDetachTicket,
        prepareQuiescentLocked: () -> T
    ): NtkPreparedOperation<T>? {
        try {
            hooks.onDetachAdmissionClosed()
        } catch (_: Throwable) {
            protocolLock.withLock { failLocked() }
            return null
        }
        val prepared = protocolLock.withLock {
            if (phase != ProtocolPhase.DETACH_CLOSING ||
                surfaceKey != ticket.key
            ) return null
            while (activeOperations != 0) {
                try {
                    protocolChanged.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failLocked()
                    return null
                }
            }
            try {
                prepareQuiescentLocked()
            } catch (_: Throwable) {
                failLocked()
                return null
            }
        }
        try {
            hooks.onDetachQuiescent()
        } catch (_: Throwable) {
            protocolLock.withLock { failLocked() }
            return null
        }
        return NtkPreparedOperation(prepared)
    }

    internal fun completeSurfaceDetach(
        ticket: NtkDetachTicket,
        nextPhase: ProtocolPhase
    ): Boolean = protocolLock.withLock {
        if (phase != ProtocolPhase.DETACH_CLOSING ||
            surfaceKey != ticket.key ||
            activeOperations != 0 ||
            nextPhase !in setOf(
                ProtocolPhase.LIVE_DETACHED,
                ProtocolPhase.RETIRED_BLOCKED,
                ProtocolPhase.FAILED
            )
        ) return false
        phase = nextPhase
        if (nextPhase == ProtocolPhase.LIVE_DETACHED ||
            nextPhase == ProtocolPhase.FAILED
        ) {
            surfaceKey = null
        }
        protocolChanged.signalAll()
        true
    }

    internal fun externalCompletionDispatchAllowedLocked(): Boolean {
        check(protocolLock.isHeldByCurrentThread)
        return phase != ProtocolPhase.DETACH_CLOSING &&
            phase != ProtocolPhase.RETIRED_BLOCKED
    }

    internal fun <P, N, R> runOperation(
        operation: String,
        admission: NtkProtocolAdmission,
        rejected: R,
        prepareLocked: () -> NtkPreparedOperation<P>?,
        nativeCall: NtkProtocolNativeAdapter<P, N>,
        completeLocked: (P, Result<N>) -> R
    ): R {
        val prepared = protocolLock.withLock {
            if (admission == NtkProtocolAdmission.RELEASE) {
                while (phase == ProtocolPhase.DETACH_CLOSING) {
                    try {
                        protocolChanged.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return rejected
                    }
                }
            }
            if (!allowsLocked(admission)) return rejected
            val value = prepareLocked() ?: return rejected
            activeOperations++
            value.value
        }

        var nativeResult = runCatching { nativeCall.call(prepared) }
        nativeResult = try {
            hooks.afterNativeReturnBeforeBookkeeping(operation)
            nativeResult
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

        return protocolLock.withLock {
            try {
                completeLocked(prepared, nativeResult)
            } catch (_: Throwable) {
                failLocked()
                rejected
            } finally {
                check(activeOperations > 0)
                activeOperations--
                if (activeOperations == 0) protocolChanged.signalAll()
            }
        }
    }

    internal fun beginCloseAndAwaitQuiescence(allowed: Set<ProtocolPhase>): ProtocolPhase? {
        return protocolLock.withLock {
            if (phase !in allowed) return null
            val previous = phase
            phase = ProtocolPhase.CLOSING
            protocolChanged.signalAll()
            while (activeOperations != 0) {
                // Once CLOSING is published, interruption cannot revoke the sole native-handle
                // owner and reopen admission. Preserve the interrupt status but finish draining.
                protocolChanged.awaitUninterruptibly()
            }
            previous
        }
    }

    private fun allowsLocked(admission: NtkProtocolAdmission): Boolean = when (admission) {
        NtkProtocolAdmission.SURFACE_ATTACH -> phase == ProtocolPhase.LIVE_DETACHED
        NtkProtocolAdmission.PREPARATION -> phase == ProtocolPhase.LIVE_DETACHED ||
            phase == ProtocolPhase.SURFACE_ATTACHING ||
            phase == ProtocolPhase.SURFACE_READY ||
            phase == ProtocolPhase.LIVE_ATTACHED
        NtkProtocolAdmission.LIVE -> phase == ProtocolPhase.LIVE_ATTACHED
        NtkProtocolAdmission.RELEASE -> phase == ProtocolPhase.LIVE_DETACHED ||
            phase == ProtocolPhase.SURFACE_ATTACHING ||
            phase == ProtocolPhase.SURFACE_READY ||
            phase == ProtocolPhase.LIVE_ATTACHED ||
            phase == ProtocolPhase.RETIRED_BLOCKED ||
            phase == ProtocolPhase.RETIRED_DISPATCHABLE
        NtkProtocolAdmission.DEBUG -> phase != ProtocolPhase.CLOSED
    }
}

/**
 * The one production algorithm for handing a terminal native release to external code.
 * Callers must hold [protocol]'s lock. The queued task rechecks phase and registration identity;
 * only the completion's finally block is allowed to remove the registration.
 */
internal fun <K, R, A : Any> scheduleNtkReleaseCompletionLocked(
    protocol: NtkEngineProtocolCoordinator,
    registrations: MutableMap<K, NtkReleaseRegistration<R, A>>,
    key: K,
    registration: NtkReleaseRegistration<R, A>
) {
    protocol.phaseLocked()
    if (registration.stagedAck == null || !registration.nativeDispatchable ||
        registration.nativeCallActive || !protocol.externalCompletionDispatchAllowedLocked() ||
        registration.scheduled
    ) return
    registration.scheduled = true
    NtkReleaseCompletion.dispatch {
        val invocation = protocol.withProtocolLock {
            when {
                registrations[key] !== registration || registration.delivered ||
                    registration.running -> null
                !protocol.externalCompletionDispatchAllowedLocked() -> {
                    registration.scheduled = false
                    protocol.signalChangedLocked()
                    null
                }
                else -> {
                    registration.running = true
                    registration.stagedAck?.let { ack -> registration.completion to ack }
                }
            }
        } ?: return@dispatch
        try {
            invocation.first(invocation.second)
        } catch (_: Throwable) {
            // User completion failure is isolated from native and proof lifecycle state.
        } finally {
            protocol.withProtocolLock {
                registration.running = false
                registration.delivered = true
                registrations.remove(key, registration)
                protocol.signalChangedLocked()
            }
        }
    }
}
