package ml.melun.mangaview.reader

import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

/** Non-failure control result used to return optional pixel work to the owning event loop. */
internal class NtkPhysicalMotionDecodeDeferredException :
    RuntimeException("Physical display motion owns decode admission")

/**
 * Separates completed network chunks from the foreground reader's display-critical burst.
 *
 * Idle socket reads remain fully concurrent. During active physical motion each read first owns
 * one of two process-wide display lanes and then reserves a short start slot before entering TLS.
 * The two lanes are deliberate: one slow socket cannot stop a visible source, while independent
 * current/adjacent pipelines can no longer fill every emulator CPU with simultaneous TLS work.
 * The byte stream, request count, source ordering, and total work are unchanged.
 */
internal object NtkReaderTransferPacer {
    private const val ACTIVE_MOTION_READ_LANES = 2
    // The permits protect the display from healthy, immediately-readable TLS streams. They are
    // deliberately not an unbounded socket semaphore: two stalled HTTP/2 streams must never keep
    // every other response unread until the origin resets it. A waiter that reaches this bound
    // keeps the global start cadence but enters the blocking read without owning a permit.
    private const val MAX_ACTIVE_MOTION_READ_ADMISSION_WAIT_NANOS = 32_000_000L
    // A continuous reader does not benefit when several TLS lanes hand completed 8 KiB chunks to
    // hashing, publication and exact decode in the same millisecond. The strict idle-completion
    // wake now drains a verified current episode as soon as real motion stops, so the original
    // four-millisecond active-motion spacing no longer strands its suffix at a checkpoint. This
    // keeps foreground CPU/GPU demand bounded while leaving sustained idle throughput unchanged.
    private const val ACTIVE_MOTION_CHUNK_SPACING_NANOS = 8_000_000L
    // A swipe is a sequence of short DOWN/MOVE/UP gestures. Releasing every completed TLS chunk
    // in the small UP-to-next-DOWN gap creates a CPU/hash/decode burst just before input arrives.
    // The short grace protects latency-sensitive runway/decode admission without delaying it for
    // seconds after an ordinary gesture.
    private const val DISPLAY_IDLE_GRACE_NANOS = 250_000_000L
    private const val PHYSICAL_INPUT_IDLE_GRACE_NANOS = 750_000_000L
    // An offscreen suffix has no visible deadline. Reader gestures commonly have one-to-three
    // second gaps, so sharing the short display grace resumed TLS decrypt, hashing and publication
    // immediately before the next gesture. Keep only this optional byte lane paused until a real
    // reading idle. Compositor foreground promotion bypasses the wait immediately, so the same
    // bytes become mandatory as soon as their episode is actually displayed.
    private const val OPTIONAL_TRANSFER_IDLE_GRACE_NANOS = 5_000_000_000L

    private val lock = Any()
    private val activeMotionReadPermits = Semaphore(ACTIVE_MOTION_READ_LANES, true)
    private val nextChunkReturnNanos = AtomicLong(0L)
    @Volatile private var active = false
    @Volatile private var displayIdleGraceUntilNanos = 0L
    @Volatile private var physicalInputIdleGraceUntilNanos = 0L
    @Volatile private var optionalTransferIdleGraceUntilNanos = 0L
    private var owner: Any? = null
    @Volatile private var touchActive = false
    private var viewportMotionActive = false
    private var physicalForegroundOwner: Any? = null
    @Volatile private var physicalForegroundEpisodePath = ""

    fun noteTouch(ownerToken: Any, value: Boolean) {
        synchronized(lock) {
            if (value) adoptOwnerLocked(ownerToken)
            if (owner !== ownerToken) return
            touchActive = value
            physicalInputIdleGraceUntilNanos = if (value) {
                0L
            } else {
                System.nanoTime() + PHYSICAL_INPUT_IDLE_GRACE_NANOS
            }
            publishLocked()
        }
    }

    fun noteViewportMotion(ownerToken: Any, value: Boolean) {
        synchronized(lock) {
            if (value) adoptOwnerLocked(ownerToken)
            if (owner !== ownerToken) return
            viewportMotionActive = value
            publishLocked()
        }
    }

    fun release(ownerToken: Any) {
        synchronized(lock) {
            if (owner === ownerToken) {
                owner = null
                touchActive = false
                viewportMotionActive = false
                active = false
                displayIdleGraceUntilNanos = 0L
                physicalInputIdleGraceUntilNanos = 0L
                optionalTransferIdleGraceUntilNanos = 0L
                nextChunkReturnNanos.set(0L)
            }
            if (physicalForegroundOwner === ownerToken) {
                physicalForegroundOwner = null
                physicalForegroundEpisodePath = ""
            }
        }
    }

    /** Compositor-proven episode identity; independent from the Activity's launch-path lease. */
    fun notePhysicalForegroundEpisode(ownerToken: Any, episodePath: String) {
        val normalized = episodePath.trim()
        if (normalized.isEmpty()) return
        synchronized(lock) {
            physicalForegroundOwner = ownerToken
            physicalForegroundEpisodePath = normalized
        }
    }

    fun isPhysicalForegroundEpisode(episodePath: String?): Boolean {
        val normalized = episodePath?.trim().orEmpty()
        return normalized.isNotEmpty() && physicalForegroundEpisodePath == normalized
    }

    fun readChunk(input: InputStream, buffer: ByteArray): Int =
        readChunk(input, buffer, 0, buffer.size)

    fun readChunk(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        return readWhileDisplayBounded {
            input.read(buffer, offset, length)
        }
    }

    fun readByte(input: InputStream): Int = readWhileDisplayBounded(input::read)

    /**
     * Reads optional offscreen bytes only after the foreground display has a real idle window.
     *
     * An adjacent episode's drawable prefix uses [readChunk] and therefore keeps its bounded
     * runway latency. Once that prefix is resident, its suffix is not needed by the current
     * viewport. Continuing TLS decrypt, file writes and EOF/SHA publication through a fling made
     * those offscreen completions contend with SurfaceFlinger. The response remains open and the
     * exact stream resumes from the same byte; no request, byte, proof or cache work is skipped.
     */
    fun readOptionalChunk(
        input: InputStream,
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size,
        shouldRemainDeferred: () -> Boolean,
        stillOwned: () -> Unit,
    ): Int {
        awaitOptionalMotionIdle(shouldRemainDeferred, stillOwned)
        return readChunk(input, buffer, offset, length)
    }

    fun readOptionalByte(
        input: InputStream,
        shouldRemainDeferred: () -> Boolean,
        stillOwned: () -> Unit,
    ): Int {
        awaitOptionalMotionIdle(shouldRemainDeferred, stillOwned)
        return readByte(input)
    }

    private fun awaitOptionalMotionIdle(
        shouldRemainDeferred: () -> Boolean,
        stillOwned: () -> Unit,
    ) {
        while (shouldRemainDeferred() && isOptionalTransferPriorityActive()) {
            stillOwned()
            LockSupport.parkNanos(4_000_000L)
        }
        stillOwned()
    }

    internal fun isActiveForTest(): Boolean = active

    /** Lock-free display-priority level for maintenance workers outside the transfer path. */
    fun isPhysicalMotionActive(): Boolean = isDisplayPriorityActive()

    /** Pointer-only priority used by offscreen native retirement during a passive fling. */
    fun isPhysicalInputPriorityActive(): Boolean {
        if (touchActive) return true
        val deadline = physicalInputIdleGraceUntilNanos
        if (deadline <= 0L) return false
        if (System.nanoTime() < deadline) return true
        if (physicalInputIdleGraceUntilNanos == deadline) {
            physicalInputIdleGraceUntilNanos = 0L
        }
        return false
    }

    /**
     * Waits only for the foreground motion level; it owns no socket, permit, or application lock.
     * Callers use this before allocation-heavy optional control work whose network response is
     * already complete. [stillOwned] is rechecked on every short park so lifecycle retirement
     * remains prompt.
     */
    fun awaitMotionIdle(stillOwned: () -> Unit) {
        while (isDisplayPriorityActive()) {
            stillOwned()
            LockSupport.parkNanos(4_000_000L)
        }
        stillOwned()
    }

    /**
     * Defers optional allocation work behind physical motion until the real Surface boundary turns
     * that work into a foreground liveness requirement.  Unlike a timeout, [requiredNow] is an
     * identity-scoped product event: ordinary scrolling retains the full display-idle protection,
     * while a reader already clamped at the end cannot wait forever for an idle gap that repeated
     * swipes deliberately never provide.
     */
    fun awaitMotionIdleUntilRequired(
        requiredNow: () -> Boolean,
        stillOwned: () -> Unit,
    ) {
        var lastDiagnosticNanos = 0L
        while (!requiredNow() && isDisplayPriorityActive()) {
            stillOwned()
            val now = System.nanoTime()
            if (now - lastDiagnosticNanos >= 2_000_000_000L) {
                lastDiagnosticNanos = now
                android.util.Log.d(
                    "ViewerPerf",
                    "ntk_transfer_motion_wait ${diagnosticState(now)}",
                )
            }
            LockSupport.parkNanos(4_000_000L)
        }
        stillOwned()
    }

    private fun diagnosticState(nowNanos: Long): String = synchronized(lock) {
        "active=$active,touch=$touchActive,viewport=$viewportMotionActive," +
            "owner=${owner?.let(System::identityHashCode) ?: 0}," +
            "displayGraceMs=${((displayIdleGraceUntilNanos - nowNanos) / 1_000_000L).coerceAtLeast(0L)}," +
            "inputGraceMs=${((physicalInputIdleGraceUntilNanos - nowNanos) / 1_000_000L).coerceAtLeast(0L)}"
    }

    private fun awaitCompletedChunkSlot() {
        while (true) {
            val now = System.nanoTime()
            val observed = nextChunkReturnNanos.get()
            val slot = max(now, observed)
            if (!nextChunkReturnNanos.compareAndSet(
                    observed,
                    slot + ACTIVE_MOTION_CHUNK_SPACING_NANOS,
                )
            ) continue
            val delay = slot - now
            if (delay > 0L) LockSupport.parkNanos(delay)
            return
        }
    }

    private inline fun <T> readWhileDisplayBounded(read: () -> T): T {
        if (!isDisplayPriorityActive()) return read()
        val admissionWaitStartedAtNanos = System.nanoTime()
        while (!activeMotionReadPermits.tryAcquire(4L, TimeUnit.MILLISECONDS)) {
            // A reader queued during a fling must not remain behind sockets that were admitted
            // under the old priority after the display becomes idle. At idle all original
            // transfer concurrency is restored immediately.
            if (!isDisplayPriorityActive()) return read()
            if (System.nanoTime() - admissionWaitStartedAtNanos >=
                MAX_ACTIVE_MOTION_READ_ADMISSION_WAIT_NANOS
            ) {
                // This is a liveness escape for blocking I/O, not an unpaced CPU lane. Keeping the
                // same process-wide start cadence prevents queued pipelines from entering TLS in
                // one burst while allowing the origin's flow-control window to keep advancing.
                awaitCompletedChunkSlot()
                return read()
            }
        }
        try {
            // InputStream.read() performs TLS record decryption as well as the copy. Reserve the
            // cadence only after admission so queued readers cannot reserve timestamps that later
            // collapse into one burst when a lane becomes available.
            awaitCompletedChunkSlot()
            return read()
        } finally {
            activeMotionReadPermits.release()
        }
    }

    private fun adoptOwnerLocked(ownerToken: Any) {
        if (owner === ownerToken) return
        owner = ownerToken
        touchActive = false
        viewportMotionActive = false
        displayIdleGraceUntilNanos = 0L
        physicalInputIdleGraceUntilNanos = 0L
        optionalTransferIdleGraceUntilNanos = 0L
        nextChunkReturnNanos.set(0L)
    }

    private fun publishLocked() {
        val next = touchActive || viewportMotionActive
        active = next
        if (next) {
            displayIdleGraceUntilNanos = 0L
            optionalTransferIdleGraceUntilNanos = 0L
        } else {
            val now = System.nanoTime()
            displayIdleGraceUntilNanos = now + DISPLAY_IDLE_GRACE_NANOS
            optionalTransferIdleGraceUntilNanos = now + OPTIONAL_TRANSFER_IDLE_GRACE_NANOS
        }
    }

    private fun isDisplayPriorityActive(): Boolean {
        if (active) return true
        val deadline = displayIdleGraceUntilNanos
        if (deadline <= 0L) return false
        if (System.nanoTime() < deadline) return true
        if (displayIdleGraceUntilNanos == deadline) {
            displayIdleGraceUntilNanos = 0L
            nextChunkReturnNanos.set(0L)
        }
        return false
    }

    private fun isOptionalTransferPriorityActive(): Boolean {
        if (active) return true
        val deadline = optionalTransferIdleGraceUntilNanos
        if (deadline <= 0L) return false
        if (System.nanoTime() < deadline) return true
        if (optionalTransferIdleGraceUntilNanos == deadline) {
            optionalTransferIdleGraceUntilNanos = 0L
        }
        return false
    }
}
