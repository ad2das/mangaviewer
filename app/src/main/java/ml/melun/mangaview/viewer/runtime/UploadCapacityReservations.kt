package ml.melun.mangaview.viewer.runtime

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.engine.api.WorkPriority

/**
 * Capacity reservations for GPU texture uploads.
 *
 * The native owner's `used` bytes only move on its own thread (upload, release acknowledgement,
 * retirement), so this ledger mirrors them under a private lock: [synchronize] pushes a fresh count,
 * and reservers check `used + reserved + bytes <= limit` without a GL thread hop. A reservation
 * granted here is guaranteed to fit when the upload later runs, which is what lets the caller wait
 * for capacity before taking the single UPLOAD permit.
 *
 * Every waiter reads its live [WorkPriority] at each grant pass, so a speculative tile the reading
 * position promotes is served as foreground work immediately. Waiters whose live priority is not
 * background are granted first, then background ones, each class in arrival order; only the
 * background class must leave [backgroundReserveBytes] free for foreground work and retirement lag.
 * A pass costs one iteration per waiter and no allocation; only waiters that actually fit complete.
 * [close] fails queued waiters with [IllegalStateException], because a renderer teardown is a
 * failure, not the cancellation of the waiting tile.
 */
internal class UploadCapacityReservations(
    private val allocationLimit: Long,
    private val backgroundReserveBytes: Long,
) {
    /** A granted reservation; exactly one of [commit] or [release] must settle it. */
    internal class Reservation internal constructor(
        val bytes: Long,
        private val owner: UploadCapacityReservations,
    ) {
        private var settled = false

        /** The upload landed: native `used` now counts these bytes, so they stop being reserved. */
        fun commit() { settle() }

        /** The upload will not happen: failure, cancellation or close returns the bytes. */
        fun release() { settle() }

        private fun settle() {
            if (settled) return
            settled = true
            owner.settled(bytes)
        }
    }

    private class Waiter(val priority: () -> WorkPriority, val bytes: Long) {
        val grant = CompletableDeferred<Unit>()
        var granted = false
    }

    private val lock = Any()
    private val waiters = ArrayDeque<Waiter>()
    private var used = 0L
    private var reserved = 0L
    private var closed = false

    /** Mirrors the owner's native texture bytes; every waiter that now fits is granted. */
    fun synchronize(usedBytes: Long) {
        synchronized(lock) {
            used = usedBytes.coerceAtLeast(0L)
            if (!closed) grantLocked()
        }
    }

    suspend fun reserve(priority: () -> WorkPriority, bytes: Long): Reservation {
        require(bytes in 1..allocationLimit) { "A reservation must fit the allocation limit" }
        val waiter = Waiter(priority, bytes)
        val immediate = synchronized(lock) {
            if (closed) throw IllegalStateException("Upload capacity is closed")
            if (fitsLocked(priority().background, bytes)) {
                reserved += bytes
                true
            } else {
                waiters.addLast(waiter)
                false
            }
        }
        if (immediate) return Reservation(bytes, this)
        try {
            waiter.grant.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (waiter.granted) reserved -= bytes else waiters.remove(waiter)
                grantLocked()
            }
            throw cancelled
        }
        return Reservation(bytes, this)
    }

    /** Fails every waiter once the owner is gone; in-flight reservations settle through their upload. */
    fun close() {
        val failed: List<Waiter>
        synchronized(lock) {
            if (closed) return
            closed = true
            failed = waiters.toList()
            waiters.clear()
        }
        failed.forEach { it.grant.completeExceptionally(IllegalStateException("Upload capacity is closed")) }
    }

    internal fun reservedBytes(): Long = synchronized(lock) { reserved }
    internal fun usedBytes(): Long = synchronized(lock) { used }
    internal fun waitingCount(): Int = synchronized(lock) { waiters.size }

    private fun settled(bytes: Long) {
        synchronized(lock) {
            reserved -= bytes
            grantLocked()
        }
    }

    private fun grantLocked() {
        serveLocked(background = false)
        serveLocked(background = true)
    }

    /** One arrival-order pass serving only waiters whose live priority matches [background]. */
    private fun serveLocked(background: Boolean) {
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.priority().background != background) continue
            if (!fitsLocked(background, waiter.bytes)) continue
            iterator.remove()
            waiter.granted = true
            reserved += waiter.bytes
            waiter.grant.complete(Unit)
        }
    }

    private fun fitsLocked(background: Boolean, bytes: Long): Boolean {
        val limit = if (background) allocationLimit - backgroundReserveBytes else allocationLimit
        return used + reserved + bytes <= limit
    }
}
