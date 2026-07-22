package ml.melun.mangaview.reader

import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger

internal class NtkNativeSurfaceLease private constructor(
    val leaseId: Long,
    val surfaceEpoch: Long
) : AutoCloseable {
    private val ownership = AtomicInteger(VIEW_OWNED)

    fun transfer(): NtkNativeSurfaceLeaseTransfer? {
        if (!ownership.compareAndSet(VIEW_OWNED, ENGINE_OWNED)) return null
        return NtkNativeSurfaceLeaseTransfer(leaseId, surfaceEpoch)
    }

    override fun close() {
        if (ownership.compareAndSet(VIEW_OWNED, CLOSED)) {
            NtkStripNativeBridge.nativeReleaseSurfaceLease(leaseId, surfaceEpoch)
        }
    }

    companion object {
        private const val VIEW_OWNED = 0
        private const val ENGINE_OWNED = 1
        private const val CLOSED = 2

        fun acquire(surface: Surface, surfaceEpoch: Long): NtkNativeSurfaceLease? {
            if (surfaceEpoch <= 0L || !surface.isValid) return null
            val leaseId = NtkStripNativeBridge.nativeAcquireSurfaceLease(
                surface,
                surfaceEpoch
            )
            return leaseId.takeIf { it > 0L }?.let {
                NtkNativeSurfaceLease(it, surfaceEpoch)
            }
        }
    }
}

internal class NtkNativeSurfaceLeaseTransfer internal constructor(
    val leaseId: Long,
    val surfaceEpoch: Long
) : AutoCloseable {
    private val state = AtomicInteger(PENDING)

    internal fun markConsumed(): Boolean = state.compareAndSet(PENDING, CONSUMED)

    override fun close() {
        if (state.compareAndSet(PENDING, CLOSED)) {
            NtkStripNativeBridge.nativeReleaseSurfaceLease(leaseId, surfaceEpoch)
        }
    }

    companion object {
        private const val PENDING = 0
        private const val CONSUMED = 1
        private const val CLOSED = 2
    }
}
