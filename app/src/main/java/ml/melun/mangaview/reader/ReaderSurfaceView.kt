package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.annotation.Keep
import ml.melun.mangaview.benchmark.BenchmarkAdjacentCommitSignal
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import ml.melun.mangaview.runtime.ViewerTelemetry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.ArrayDeque
import java.util.Locale

internal object NtkExpandedNativeTextureTransitionPolicy {
    fun mayCrossCard(
        cardText: String?,
        errorText: String?,
        nextCardText: String?,
        nextErrorText: String?,
        nextEpisodePath: String?,
        authorizedEpisodePaths: Set<String>,
    ): Boolean =
        cardText != null &&
            errorText == null &&
            nextCardText == null &&
            nextErrorText == null &&
            !nextEpisodePath.isNullOrEmpty() &&
            nextEpisodePath in authorizedEpisodePaths
}

/**
 * Bounded episode authority for native texture residency during continuous reading.
 *
 * The current episode, its immediate predecessor, and one not-yet-presented forward target are
 * the only paths that may own GPU uploads.  Keeping the forward target separate is important:
 * an old callback must not replace the predecessor after an A -> B -> A authority cycle, while a
 * user who scrolls upward in B must still be able to re-upload A until C is physically adopted.
 */
internal object NtkExpandedNativeTextureHistoryPolicy {
    data class State(
        val currentPath: String = "",
        val previousPath: String = "",
        val pendingForwardPath: String = "",
        val pendingForwardPredecessorPath: String = "",
        val pendingForwardRevision: Long = 0L,
        val deferredForwardPath: String = "",
        val deferredForwardPredecessorPath: String = "",
        val deferredForwardRevision: Long = 0L,
    ) {
        fun authorizedPaths(): LinkedHashSet<String> = linkedSetOf<String>().apply {
            if (currentPath.isNotEmpty()) add(currentPath)
            if (previousPath.isNotEmpty()) add(previousPath)
            if (pendingForwardPath.isNotEmpty()) add(pendingForwardPath)
        }
    }

    fun configured(currentPath: String): State =
        State(currentPath = currentPath.takeIf(String::isNotEmpty).orEmpty())

    fun authorizeForward(
        state: State,
        predecessorPath: String,
        targetPath: String,
        revision: Long,
    ): State {
        if (predecessorPath.isEmpty() || targetPath.isEmpty() || revision <= 0L ||
            targetPath == predecessorPath || targetPath == state.previousPath
        ) return state
        if (predecessorPath == state.currentPath) {
            if (targetPath == state.currentPath) return state
            if (state.pendingForwardPath.isNotEmpty() &&
                revision <= state.pendingForwardRevision
            ) return state
            val keepDeferred = state.deferredForwardPredecessorPath == targetPath
            return state.copy(
                pendingForwardPath = targetPath,
                pendingForwardPredecessorPath = predecessorPath,
                pendingForwardRevision = revision,
                deferredForwardPath = state.deferredForwardPath.takeIf { keepDeferred }.orEmpty(),
                deferredForwardPredecessorPath =
                    state.deferredForwardPredecessorPath.takeIf { keepDeferred }.orEmpty(),
                deferredForwardRevision = state.deferredForwardRevision.takeIf { keepDeferred }
                    ?: 0L,
            )
        }
        // A completed B can resolve C before B is physically presented. Keep C as metadata only;
        // authorizing its GPU uploads now would evict B's A->B transition authority.
        if (predecessorPath == state.pendingForwardPath) {
            if (targetPath == state.currentPath || targetPath == state.pendingForwardPath ||
                (state.deferredForwardPath.isNotEmpty() &&
                    revision <= state.deferredForwardRevision)
            ) return state
            return state.copy(
                deferredForwardPath = targetPath,
                deferredForwardPredecessorPath = predecessorPath,
                deferredForwardRevision = revision,
            )
        }
        return state
    }

    fun advanceAfterPhysicalCommit(
        state: State,
        targetPath: String,
        committedPath: String,
    ): State {
        if (targetPath.isEmpty() || committedPath != targetPath) return state
        if (targetPath == state.currentPath) return state
        if (targetPath != state.pendingForwardPath) return state
        val promoteDeferred = state.deferredForwardPredecessorPath == targetPath &&
            state.deferredForwardPath.isNotEmpty() &&
            state.deferredForwardPath != targetPath &&
            state.deferredForwardPath != state.currentPath
        return State(
            currentPath = targetPath,
            previousPath = state.currentPath,
            pendingForwardPath = state.deferredForwardPath.takeIf { promoteDeferred }.orEmpty(),
            pendingForwardPredecessorPath =
                state.deferredForwardPredecessorPath.takeIf { promoteDeferred }.orEmpty(),
            pendingForwardRevision = state.deferredForwardRevision.takeIf { promoteDeferred }
                ?: 0L,
        )
    }

    fun shouldBypassResumeFloorForReverse(
        activeDirection: Int,
        pointerDown: Boolean,
        dragging: Boolean,
        scrollbarDragging: Boolean,
        scrollerFinished: Boolean,
    ): Boolean = activeDirection == ReaderSurfaceView.DIRECTION_PREVIOUS &&
        (pointerDown || dragging || scrollbarDragging || !scrollerFinished)

    fun remapFloorAfterRemoval(
        floor: Int,
        startIndex: Int,
        removedCount: Int,
        remainingPageCount: Int,
    ): Int {
        if (remainingPageCount <= 0) return 0
        val endExclusive = startIndex.toLong() + removedCount.toLong()
        val shifted = when {
            floor < startIndex -> floor
            floor.toLong() >= endExclusive -> floor - removedCount
            else -> startIndex
        }
        return shifted.coerceIn(0, remainingPageCount - 1)
    }

    fun remapExactPageAfterRemoval(
        page: Int,
        startIndex: Int,
        removedCount: Int,
        remainingPageCount: Int,
    ): Int {
        if (page < 0 || remainingPageCount <= 0) return -1
        val endExclusive = startIndex.toLong() + removedCount.toLong()
        if (page.toLong() >= startIndex.toLong() && page.toLong() < endExclusive) {
            return -1
        }
        val shifted = if (page.toLong() >= endExclusive) page - removedCount else page
        return shifted.takeIf { it in 0 until remainingPageCount } ?: -1
    }
}

/** Preserves a real reverse MOVE when the latest-only UI window mailbox receives UP immediately. */
internal object NtkReverseWindowEvidencePolicy {
    data class Evidence(
        val directionHint: Int = 0,
        val reverseFirstPageHint: Int = -1,
    )

    fun capture(busy: Boolean, activeDirection: Int, firstVisiblePage: Int): Evidence = when {
        busy && activeDirection < 0 && firstVisiblePage >= 0 ->
            Evidence(directionHint = -1, reverseFirstPageHint = firstVisiblePage)
        busy && activeDirection > 0 -> Evidence(directionHint = 1)
        else -> Evidence()
    }

    fun merge(previous: Evidence?, latest: Evidence): Evidence {
        val previousReverse = previous?.reverseFirstPageHint?.takeIf { it >= 0 }
        val latestReverse = latest.reverseFirstPageHint.takeIf { it >= 0 }
        val reverseFirst = listOfNotNull(previousReverse, latestReverse).minOrNull() ?: -1
        return if (reverseFirst >= 0) {
            Evidence(directionHint = -1, reverseFirstPageHint = reverseFirst)
        } else {
            latest
        }
    }
}

internal object NtkNativeSurfaceFrameRatePolicy {
    fun isEmulatorRuntime(
        fingerprint: String,
        model: String,
        hardware: String,
        product: String,
    ): Boolean {
        return fingerprint.startsWith("generic", ignoreCase = true) ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            product.contains("sdk_gphone", ignoreCase = true)
    }

    fun shouldApplyLayerFrameRateVote(
        emulatorRuntime: Boolean,
        directWifiRendererProfile: Boolean,
    ): Boolean = emulatorRuntime && directWifiRendererProfile
}

internal object NtkDisplayTimingPolicy {
    private const val DEFAULT_REFRESH_RATE_HZ = 60f

    fun normalizedRefreshRate(refreshRate: Float?): Float =
        refreshRate?.takeIf { it.isFinite() && it > 1f } ?: DEFAULT_REFRESH_RATE_HZ

    fun frameBudgetMs(refreshRate: Float): Float =
        1000f / normalizedRefreshRate(refreshRate)
}

internal object NtkDirectCadenceWatchdogPolicy {
    fun delayMs(frameBudgetMs: Float, emulatorRuntime: Boolean): Long {
        val nominalPeriodMs = ceil(frameBudgetMs.toDouble()).toLong().coerceAtLeast(1L)
        // gfxstream's producer Choreographer often arrives just after one nominal period. An
        // 18-ms watchdog races that healthy callback, creating five extra queueBuffer submissions
        // per second and mailbox drops. Give the host two periods; physical devices retain the
        // established one-period grace. A genuine 60-100 ms host callback outage is still bounded.
        return if (emulatorRuntime) nominalPeriodMs * 2L + 1L else nominalPeriodMs + 1L
    }
}

/**
 * Replaces, rather than adds to, the one producer-vsync callback that already owns an exact
 * adjacent-p0 content mutation. This is intentionally narrower than input catch-up: it requires
 * the host-emulator direct-Wi-Fi runway and an admitted immutable frame token.
 */
internal object NtkAdjacentExactP0FrameCatchupPolicy {
    fun shouldPost(
        emulatorRuntime: Boolean,
        directWifiRunway: Boolean,
        authorizedEpisode: Boolean,
        renderRunning: Boolean,
        directSurfaceReady: Boolean,
        frameSchedulingSuppressed: Boolean,
        callbackPosted: Boolean,
        contentCatchupPosted: Boolean,
        inputCatchupPosted: Boolean,
        hasAdmittedFrame: Boolean,
        nativeAttached: Boolean,
        surfaceValid: Boolean,
    ): Boolean = emulatorRuntime && directWifiRunway && authorizedEpisode && renderRunning &&
        directSurfaceReady && !frameSchedulingSuppressed && callbackPosted &&
        !contentCatchupPosted && !inputCatchupPosted && hasAdmittedFrame && nativeAttached &&
        surfaceValid
}

/**
 * Keeps a resumed host-emulator viewport private until it is either physically continuous or is
 * the exact, naturally short terminal source. Other renderer profiles retain progressive
 * first-pixel reveal.
 */
internal object NtkDeferredSurfaceRevealPolicy {
    fun shouldReveal(
        emulatorRuntime: Boolean,
        directWifiForwardOnlyResume: Boolean,
        visibleActualPixels: Boolean,
        continuousActualViewport: Boolean,
        exactTerminalTailActual: Boolean,
    ): Boolean {
        if (!visibleActualPixels) return false
        return if (emulatorRuntime && directWifiForwardOnlyResume) {
            continuousActualViewport || exactTerminalTailActual
        } else {
            true
        }
    }
}

/**
 * Native exact pixels may use sparse source indexes (for example auto-cut pages), but their
 * structural display-page indexes must remain contiguous. A missing structural page represents
 * an incomplete geometry snapshot, not a valid sparse viewport.
 */
internal object NtkDirectWifiExactVisibleStructurePolicy {
    fun shouldEnforce(
        emulatorRuntime: Boolean,
        realPixelsOnly: Boolean,
    ): Boolean = emulatorRuntime && realPixelsOnly

    fun isContiguous(displayPageIndexes: IntArray): Boolean {
        for (index in 1 until displayPageIndexes.size) {
            if (displayPageIndexes[index] != displayPageIndexes[index - 1] + 1) return false
        }
        return true
    }
}

/**
 * Bounded hand-off between the native presentation producer and the main-thread reader listener.
 *
 * The producer can run at 90 Hz while the listener performs identity validation, telemetry and
 * accessibility publication. Posting one main-thread Runnable per native frame therefore turns a
 * short burst into an ever-growing UI queue. This mailbox keeps exactly one scheduled/running
 * owner, coalesces an unchanged semantic viewport to its latest proof, and retains ordered
 * identity transitions. The first proof is itself a replaceable activation slot: a burst of
 * identical proofs before main gets CPU time still activates from the newest physical frame.
 */
internal class CompletedDrawDispatchQueue(
    private val maxSemanticTransitions: Int,
    private val ordinaryCadenceNanos: Long,
) {
    init {
        require(maxSemanticTransitions > 0)
        require(ordinaryCadenceNanos > 0L)
    }

    data class Delivery(
        val proof: ReaderSurfaceView.CompletedDrawProof,
        val revealNativeSurface: Boolean,
    )

    data class OfferResult(
        val shouldPost: Boolean,
        val delayNanos: Long,
        val criticalOverflowed: Boolean,
    )

    data class Repost(
        val shouldPost: Boolean,
        val delayNanos: Long,
    )

    data class Snapshot(
        val offers: Long,
        val scheduleRequests: Long,
        val selected: Long,
        val delivered: Long,
        val staleDropped: Long,
        val sameSemanticCoalesced: Long,
        val ordinarySuperseded: Long,
        val overflowCoalesced: Long,
        val criticalOverflowFailures: Long,
        val postRejected: Long,
        val clears: Long,
        val maxQueueDepth: Int,
        val pendingSemanticTransitions: Int,
        val ordinaryPending: Boolean,
        val runnerScheduled: Boolean,
        val runnerRunning: Boolean,
        val maxConcurrentRunnerOwners: Int,
    )

    private data class Entry(
        val delivery: Delivery,
        val protectedTransition: Boolean,
    )

    private val semanticTransitions = ArrayDeque<Entry>()
    private var ordinary: Entry? = null
    private var lastSelectedProof: ReaderSurfaceView.CompletedDrawProof? = null
    private var lastSelectedAtNanos = Long.MIN_VALUE
    private var runnerScheduled = false
    private var runnerRunning = false

    private var offers = 0L
    private var scheduleRequests = 0L
    private var selected = 0L
    private var delivered = 0L
    private var staleDropped = 0L
    private var sameSemanticCoalesced = 0L
    private var ordinarySuperseded = 0L
    private var overflowCoalesced = 0L
    private var criticalOverflowFailures = 0L
    private var postRejected = 0L
    private var clears = 0L
    private var maxQueueDepth = 0
    private var maxConcurrentRunnerOwners = 0

    @Synchronized
    fun offer(
        proof: ReaderSurfaceView.CompletedDrawProof,
        revealNativeSurface: Boolean,
        nowNanos: Long,
    ): OfferResult {
        offers++
        val next = Delivery(proof, revealNativeSurface)
        val tail = semanticTransitions.peekLast()
        var criticalOverflowed = false
        when {
            tail != null && sameSemanticViewport(tail.delivery.proof, proof) -> {
                semanticTransitions.removeLast()
                semanticTransitions.addLast(
                    tail.copy(
                        delivery = next.copy(
                            revealNativeSurface =
                                tail.delivery.revealNativeSurface || revealNativeSurface,
                        ),
                    ),
                )
                sameSemanticCoalesced++
            }
            tail != null -> {
                val result = appendSemanticTransition(next, tail.delivery.proof)
                criticalOverflowed = result
            }
            ordinary != null && sameSemanticViewport(ordinary!!.delivery.proof, proof) -> {
                val previous = ordinary!!
                ordinary = previous.copy(
                    delivery = next.copy(
                        revealNativeSurface =
                            previous.delivery.revealNativeSurface || revealNativeSurface,
                    ),
                )
                sameSemanticCoalesced++
            }
            ordinary != null -> {
                val previous = ordinary!!.delivery.proof
                ordinary = null
                ordinarySuperseded++
                criticalOverflowed = appendSemanticTransition(next, previous)
            }
            lastSelectedProof != null && sameSemanticViewport(lastSelectedProof!!, proof) -> {
                ordinary = Entry(next, protectedTransition = false)
            }
            else -> {
                criticalOverflowed = appendSemanticTransition(next, lastSelectedProof)
            }
        }
        maxQueueDepth = maxOf(maxQueueDepth, pendingDepthLocked())
        val reservation = reserveRunnerIfNeededLocked(nowNanos)
        return OfferResult(
            reservation.shouldPost,
            reservation.delayNanos,
            criticalOverflowed,
        )
    }

    /** Moves the single scheduled owner to running. A cancelled/duplicate callback is inert. */
    @Synchronized
    fun beginRun(): Boolean {
        if (runnerRunning || !runnerScheduled) return false
        runnerScheduled = false
        runnerRunning = true
        noteRunnerOwnersLocked()
        return true
    }

    /** Returns one due proof. Semantic transitions are never held behind ordinary cadence. */
    @Synchronized
    fun pollReady(nowNanos: Long): Delivery? {
        check(runnerRunning) { "completed-draw runner is not active" }
        semanticTransitions.pollFirst()?.let { entry ->
            selectLocked(entry.delivery, nowNanos)
            return entry.delivery
        }
        val pending = ordinary ?: return null
        val dueAt = cadenceDueAtLocked()
        if (nowNanos < dueAt) return null
        ordinary = null
        selectLocked(pending.delivery, nowNanos)
        return pending.delivery
    }

    /**
     * Atomically releases the running owner or reserves its only successor. Offers racing this
     * method either see runnerRunning=true and are covered by this repost, or reserve their own
     * wakeup after both flags become false; there is no lost-wakeup gap.
     */
    @Synchronized
    fun finishRun(nowNanos: Long): Repost {
        check(runnerRunning) { "completed-draw runner is not active" }
        runnerRunning = false
        if (pendingDepthLocked() == 0) {
            runnerScheduled = false
            return Repost(false, 0L)
        }
        runnerScheduled = true
        scheduleRequests++
        noteRunnerOwnersLocked()
        val delay = if (semanticTransitions.isNotEmpty()) {
            0L
        } else {
            (cadenceDueAtLocked() - nowNanos).coerceAtLeast(0L)
        }
        return Repost(true, delay)
    }

    @Synchronized
    fun recordDeliveryResult(accepted: Boolean) {
        if (accepted) delivered++ else staleDropped++
    }

    /** Releases a Handler reservation when post/postDelayed rejects during Looper teardown. */
    @Synchronized
    fun onPostRejected() {
        postRejected++
        runnerScheduled = false
    }

    /**
     * Clears lifecycle-scoped proofs. A callback already executing keeps ownership so a new offer
     * is consumed by that runner; a merely scheduled callback is removed by the caller first.
     */
    @Synchronized
    fun clearAfterScheduledCallbackRemoval() {
        semanticTransitions.clear()
        ordinary = null
        lastSelectedProof = null
        lastSelectedAtNanos = Long.MIN_VALUE
        runnerScheduled = false
        clears++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        offers = offers,
        scheduleRequests = scheduleRequests,
        selected = selected,
        delivered = delivered,
        staleDropped = staleDropped,
        sameSemanticCoalesced = sameSemanticCoalesced,
        ordinarySuperseded = ordinarySuperseded,
        overflowCoalesced = overflowCoalesced,
        criticalOverflowFailures = criticalOverflowFailures,
        postRejected = postRejected,
        clears = clears,
        maxQueueDepth = maxQueueDepth,
        pendingSemanticTransitions = semanticTransitions.size,
        ordinaryPending = ordinary != null,
        runnerScheduled = runnerScheduled,
        runnerRunning = runnerRunning,
        maxConcurrentRunnerOwners = maxConcurrentRunnerOwners,
    )

    private fun appendSemanticTransition(
        delivery: Delivery,
        previousProof: ReaderSurfaceView.CompletedDrawProof?,
    ): Boolean {
        val proof = delivery.proof
        val protected = previousProof == null ||
            !sameVisibleEpisodes(previousProof, proof) ||
            proof.visiblePageIdentities.any { it.sourcePageIndex in 0 until 5 }
        var protectedEntryEvicted = false
        if (semanticTransitions.size >= maxSemanticTransitions) {
            val iterator = semanticTransitions.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (!iterator.next().protectedTransition) {
                    iterator.remove()
                    overflowCoalesced++
                    removed = true
                    break
                }
            }
            if (!removed) {
                // Every queued entry is a protected activation/episode/p0-p4 transition. Keep the
                // fixed bound but retire the oldest proof so the newest physical viewport cannot
                // be stranded forever if the producer stops here. The hard counter/log remains a
                // fail-closed signal that an earlier semantic proof was not delivered.
                semanticTransitions.removeFirst()
                criticalOverflowFailures++
                protectedEntryEvicted = true
            }
        }
        semanticTransitions.addLast(Entry(delivery, protected))
        return protectedEntryEvicted
    }

    private fun reserveRunnerIfNeededLocked(nowNanos: Long): Repost {
        if (pendingDepthLocked() == 0 || runnerScheduled || runnerRunning) {
            return Repost(false, 0L)
        }
        runnerScheduled = true
        scheduleRequests++
        noteRunnerOwnersLocked()
        val delay = if (semanticTransitions.isNotEmpty()) {
            0L
        } else {
            (cadenceDueAtLocked() - nowNanos).coerceAtLeast(0L)
        }
        return Repost(true, delay)
    }

    private fun selectLocked(delivery: Delivery, nowNanos: Long) {
        lastSelectedProof = delivery.proof
        lastSelectedAtNanos = nowNanos
        selected++
    }

    private fun cadenceDueAtLocked(): Long {
        if (lastSelectedAtNanos == Long.MIN_VALUE) return Long.MIN_VALUE
        val maximumBase = Long.MAX_VALUE - ordinaryCadenceNanos
        return lastSelectedAtNanos.coerceAtMost(maximumBase) + ordinaryCadenceNanos
    }

    private fun pendingDepthLocked(): Int = semanticTransitions.size + if (ordinary != null) 1 else 0

    private fun noteRunnerOwnersLocked() {
        val owners = (if (runnerScheduled) 1 else 0) + (if (runnerRunning) 1 else 0)
        maxConcurrentRunnerOwners = maxOf(maxConcurrentRunnerOwners, owners)
    }

    private fun sameVisibleEpisodes(
        first: ReaderSurfaceView.CompletedDrawProof,
        second: ReaderSurfaceView.CompletedDrawProof,
    ): Boolean {
        val firstPaths = first.visiblePageIdentities.asSequence()
            .map { it.normalizedEpisodePath }
            .distinct()
            .toList()
        val secondPaths = second.visiblePageIdentities.asSequence()
            .map { it.normalizedEpisodePath }
            .distinct()
            .toList()
        return firstPaths == secondPaths
    }

    private fun sameSemanticViewport(
        first: ReaderSurfaceView.CompletedDrawProof,
        second: ReaderSurfaceView.CompletedDrawProof,
    ): Boolean {
        if (first.surfaceLifecycleEpoch != second.surfaceLifecycleEpoch ||
            first.structureEpoch != second.structureEpoch ||
            !first.visiblePageIndexes.contentEquals(second.visiblePageIndexes) ||
            !samePresentationQualificationClass(first, second)
        ) return false
        val firstIdentities = first.visiblePageIdentities
        val secondIdentities = second.visiblePageIdentities
        if (firstIdentities.size != secondIdentities.size) return false
        return firstIdentities.indices.all { index ->
            firstIdentities[index] == secondIdentities[index]
        }
    }

    /**
     * A newer frame with the same page identity is not interchangeable when its physical proof
     * or opaque-viewport class changed.  In particular, replacing an undelivered clean p0/episode
     * proof with a later missing/placeholder frame makes the listener reject the only clean proof.
     * Keep those frames as ordered transitions while still coalescing ordinary clean motion.
     */
    private fun samePresentationQualificationClass(
        first: ReaderSurfaceView.CompletedDrawProof,
        second: ReaderSurfaceView.CompletedDrawProof,
    ): Boolean {
        if (first.hardwareAccelerated != second.hardwareAccelerated ||
            first.registeredHwuiFrameCommitCallbackObserved !=
                second.registeredHwuiFrameCommitCallbackObserved ||
            first.surfaceQueueSubmissionObserved != second.surfaceQueueSubmissionObserved ||
            first.surfaceControlLatchObserved != second.surfaceControlLatchObserved ||
            first.runwayDefect != second.runwayDefect
        ) return false
        return viewportQualificationClass(first.coverage) ==
            viewportQualificationClass(second.coverage)
    }

    private fun viewportQualificationClass(coverage: ReaderSurfaceView.VisibleCoverageSnapshot): Int {
        var result = 0
        if (coverage.physicalViewportPx <= 0) result = result or (1 shl 0)
        if (coverage.viewportPx < coverage.physicalViewportPx) result = result or (1 shl 1)
        if (coverage.drawablePx < coverage.physicalViewportPx) result = result or (1 shl 2)
        if (coverage.missingPx != 0) result = result or (1 shl 3)
        if (coverage.placeholderPx != 0) result = result or (1 shl 4)
        if (coverage.visibleLoading != 0) result = result or (1 shl 5)
        if (coverage.visibleErrors != 0) result = result or (1 shl 6)
        if (coverage.visibleCards != 0) result = result or (1 shl 7)
        if (coverage.widthFillFailures != 0) result = result or (1 shl 8)
        if (coverage.lowResolutionItems != 0) result = result or (1 shl 9)
        if (coverage.directWifiForwardOnlyInitialResume) result = result or (1 shl 10)
        if (coverage.directWifiForwardOnlyTerminalTailActual) result = result or (1 shl 11)
        return result
    }
}

class ReaderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback2 {
    data class ProgressPosition(
        val page: Int,
        val offset: Int
    )

    data class PrepareStats @JvmOverloads constructor(
        val bitmapCount: Int,
        val bytes: Long,
        val firstPage: Int,
        val lastPage: Int,
        val elapsedMicros: Long,
        val targetCount: Int = bitmapCount,
        val successCount: Int = bitmapCount,
        val preparedThroughY: Int = -1,
        val visualGeneration: Long = Long.MIN_VALUE,
        val stillValid: Boolean = false
    )

    data class ScrollPositionSnapshot(
        val page: Int,
        val offset: Int,
        val scrollOffset: Int,
        val contentHeight: Int,
        val maxScroll: Int,
        val busy: Boolean
    )

    data class VisibleCoverageSnapshot(
        val viewportPx: Int,
        val drawablePx: Int,
        val missingPx: Int,
        val placeholderPx: Int,
        val drawableItems: Int,
        val totalItems: Int,
        val visibleLoading: Int,
        val visibleErrors: Int,
        val visibleCards: Int,
        val busy: Boolean,
        val pageCount: Int,
        val widthFillFailures: Int = 0,
        val lowResolutionItems: Int = 0,
        val minDrawableSourceWidth: Int = 0,
        val physicalViewportPx: Int = 0,
        /**
         * The cold direct-Wi-Fi resume viewport starts at the exact terminal source page and may
         * contain its one structural transition card before the already-resident next p0. This is
         * still a fully opaque, identity-qualified forward viewport; the flag lets strict
         * telemetry distinguish that card from a placeholder-first launch.
         */
        val directWifiForwardOnlyInitialResume: Boolean = false,
        /**
         * Exact current-episode pixels at a naturally short terminal image while the forward
         * runway is still private. It does not claim full-viewport coverage; it proves that the
         * visible image itself is the complete terminal source so the natural uncovered area
         * below it is not mislabeled as a blank, placeholder, or identity failure.
         */
        val directWifiForwardOnlyTerminalTailActual: Boolean = false
    )

    data class PageReadinessSnapshot(
        val pageCount: Int,
        val drawablePages: Int,
        val loadingPages: Int,
        val errorPages: Int,
        val cardPages: Int,
        val unresolvedPages: Int,
        val loadingIndexes: String = "",
        val unresolvedIndexes: String = ""
    )

    data class ForwardRunwaySnapshot(
        val requiredAheadPx: Int,
        val availableAheadPx: Int,
        val drawableAheadPx: Int,
        val missingAheadPx: Int,
        val lowResolutionItems: Int,
        val firstMissingPage: Int,
        val contentExhausted: Boolean
    )

    /**
     * Read-only proof of the episode pixels that actually traversed the HWUI frame pipe.
     * Page residency is deliberately not part of this contract: rolling eviction may remove a
     * page after its full-quality pixels were committed without invalidating what the user saw.
     */
    data class TraversalSnapshot(
        val structureEpoch: Long,
        val expectedPageCount: Int,
        val committedPageCount: Int,
        val committedPageIndexes: String,
        val missingPageIndexes: String,
        val submittedFrames: Long,
        val committedFrames: Long,
        val submittedViewportDefectFrames: Long,
        val submittedRunwayDefectFrames: Long,
        val committedViewportDefectFrames: Long,
        val committedRunwayDefectFrames: Long
    )

    data class CommittedPageIdentity(
        val displayPageIndex: Int,
        val normalizedEpisodePath: String,
        val sourcePageIndex: Int,
        val canonicalAsset: String,
        val manifestDigest: String,
        val manifestPageCount: Int
    )

    data class CompletedDrawProof @JvmOverloads constructor(
        val sequence: Long,
        val completedUptimeNanos: Long,
        val hardwareAccelerated: Boolean,
        val coverage: VisibleCoverageSnapshot,
        /** HWUI submission token; this is a frame-commit proof, not a display-present fence. */
        val frameToken: Long = 0L,
        val desiredVersion: Long = 0L,
        val drawnVersion: Long = 0L,
        val committedVersion: Long = 0L,
        /** Generic rolling-reader structure identity captured by the committed draw. */
        val structureEpoch: Long = 0L,
        /** Display indexes physically intersecting the viewport in this committed draw. */
        val visiblePageIndexes: IntArray = IntArray(0),
        /** Page identities captured with the exact submitted pixels, before any index mutation. */
        val visiblePageIdentities: List<CommittedPageIdentity> = emptyList(),
        /** Immutable forward-runway verdict captured by this exact submitted draw state. */
        val runwayDefect: Boolean = false,
        /** True only when this draw completed through a registered HWUI frame-commit callback. */
        val registeredHwuiFrameCommitCallbackObserved: Boolean = false,
        /** True only after the dedicated Surface buffer was queued with unlockCanvasAndPost. */
        val surfaceQueueSubmissionObserved: Boolean = false,
        /** True only after the exact AHardwareBuffer SurfaceControl transaction latched. */
        val surfaceControlLatchObserved: Boolean = false,
        /** Surface lifecycle that owned both submission and the observed commit callback. */
        val surfaceLifecycleEpoch: Long = 0L,
        /**
         * Exact native SurfaceControl latch or successful BufferQueue submission converted onto
         * elapsedRealtimeNanos' time base. This is intentionally distinct from
         * completedUptimeNanos, which may include unrelated callback-consumer work.
         */
        val presentedUptimeNanos: Long = 0L,
        /** Scroll coordinate captured by this exact immutable submitted draw. */
        val scrollOffsetPx: Float = Float.NaN
    )

    /** Read-only health counters for the native-present to main-listener hand-off. */
    data class CompletedDrawDispatchTelemetrySnapshot(
        val offers: Long,
        val scheduleRequests: Long,
        val selected: Long,
        val delivered: Long,
        val staleDropped: Long,
        val sameSemanticCoalesced: Long,
        val ordinarySuperseded: Long,
        val overflowCoalesced: Long,
        val criticalOverflowFailures: Long,
        val postRejected: Long,
        val clears: Long,
        val maxQueueDepth: Int,
        val pendingSemanticTransitions: Int,
        val ordinaryPending: Boolean,
        val runnerScheduled: Boolean,
        val runnerRunning: Boolean,
        val maxConcurrentRunnerOwners: Int,
    )

    data class FrameStatsSnapshot(
        val samples: Int,
        val strictOverBudget: Int,
        val missedIntervals: Int,
        val missedFrames: Int,
        val droppedFrames: Int,
        val droppedFrameDebt: Int,
        val callbackP95: Float,
        val callbackMax: Float,
        val prepP95: Float,
        val prepMax: Float,
        val drawP95: Float,
        val drawMax: Float,
        val totalP95: Float,
        val totalMax: Float,
        val maxMissingPx: Int,
        val maxPlaceholderPx: Int,
        val maxVisibleLoading: Int,
        val noCanvas: Int,
        val coalesced: Int,
        val inputFrames: Int = 0,
        val inputOldestToPostP95: Float = 0f,
        val inputOldestToPostMax: Float = 0f,
        val inputNewestToPostP95: Float = 0f,
        val inputNewestToPostMax: Float = 0f,
        val mutationFrames: Int = 0,
        val mutationCallbackOverBudget: Int = 0,
        val mutationCallbackMax: Float = 0f,
        val mutationPostOverBudget: Int = 0,
        val mutationPostMax: Float = 0f,
        val mutationNewestCallbackMax: Float = 0f,
        val mutationNewestPostMax: Float = 0f,
        val presentationUnsupportedFrames: Int = 0,
        val invalidSwapIntervalFrames: Int = 0,
        val pipelineFrames: Int = 0,
        val nonPipelineFrames: Int = 0,
        val unknownPipelineFrames: Int = 0,
        val unpacedFrames: Int = 0,
        val eventToMainIngressP95: Float = 0f,
        val eventToMainIngressMax: Float = 0f,
        val mainIngressToReceiptMax: Float = 0f,
        val eventToReceiptP95: Float = 0f,
        val eventToReceiptMax: Float = 0f,
        val receiptToMutationP95: Float = 0f,
        val receiptToMutationMax: Float = 0f,
        val mutationToQueueMax: Float = 0f,
        /** Exact private gfxstream natural-fence signal to AUTO queue timestamp. */
        val backendCompletionToQueueMax: Float = 0f,
        val queueToCompositionMax: Float = 0f,
        val compositionToPresentMax: Float = 0f,
        val readyTileFrames: Int = 0,
        val readyTileMissedIntervals: Int = 0,
        val cleanMissedIntervals: Int = 0,
        val readyTilePacingMax: Float = 0f,
        val cleanPacingMax: Float = 0f,
        val surfaceEpoch: Long = 0L,
        val frameId: Long = 0L,
        val latchProofState: Int = 0,
        val logicalUnlatchedSubmissions: Int = 0,
        val maxLogicalUnlatchedSubmissions: Int = 0,
        val oldestUnlatchedAgeNanos: Long = 0L,
        val latchQueryError: Int = 0,
        val latchEvidenceDeadlineNanos: Long = 0L,
        val cadenceQualificationFailed: Boolean = false,
        /** Missing, mismatched, or out-of-order ready/queue/latch identities. */
        val causalTimestampIdentityOrOrderInvalidFrames: Int = 0,
        /** Exact queue-to-latch tuples strictly beyond H=floor(3T/2). */
        val causalLatchHorizonViolationFrames: Int = 0,
        val latchLostFrames: Int = 0,
        val latchInvalidStateFrames: Int = 0,
        val latchQueryErrorFrames: Int = 0,
        /** Same-gesture native swap-submission cadence; independent of retrospective latch ACK. */
        val functionalSubmissionSamples: Int = 0,
        val functionalSubmissionP95: Float = 0f,
        val functionalSubmissionP99: Float = 0f,
        val functionalSubmissionMax: Float = 0f,
        val functionalSubmissionMissedFrames: Int = 0,
        val functionalSubmissionDroppedFrames: Int = 0,
        val functionalSubmissionPauseFrames: Int = 0,
        val functionalSubmissionMaxOverBudgetStreak: Int = 0,
        val functionalSubmissionEligiblePairs: Int = 0,
        val functionalSubmissionInvalidPairs: Int = 0,
        val functionalInputGestures: Int = 0,
        val functionalGesturesWithValidPair: Int = 0,
        /** Legacy wire name: backend-inclusive drawBegin-to-swap-return diagnostic. */
        val functionalCpuSubmitWorkSamples: Int = 0,
        val functionalCpuSubmitWorkEligibleFrames: Int = 0,
        val functionalCpuSubmitWorkInvalidFrames: Int = 0,
        val functionalCpuSubmitWorkP95: Float = 0f,
        val functionalCpuSubmitWorkMax: Float = 0f,
        val functionalDrawIssueSamples: Int = 0,
        val functionalDrawIssueEligibleFrames: Int = 0,
        val functionalDrawIssueInvalidFrames: Int = 0,
        val functionalDrawIssueP95: Float = 0f,
        val functionalDrawIssueMax: Float = 0f,
        val functionalSwapCallSamples: Int = 0,
        val functionalSwapCallEligibleFrames: Int = 0,
        val functionalSwapCallInvalidFrames: Int = 0,
        val functionalSwapCallP95: Float = 0f,
        val functionalSwapCallP99: Float = 0f,
        val functionalSwapCallMax: Float = 0f,
        val functionalSwapCallPauseFrames: Int = 0,
        val functionalRendererReadyToQueueSamples: Int = 0,
        val functionalRendererReadyToQueueEligiblePairs: Int = 0,
        val functionalRendererReadyToQueueInvalidPairs: Int = 0,
        val functionalRendererReadyToQueueP95: Float = 0f,
        val functionalRendererReadyToQueueMax: Float = 0f,
        /** Valid renderer-ready samples exceeding the pinned 90 Hz period. */
        val functionalRendererReadyToQueueMissedFrames: Int = 0,
        /** Pinned 90 Hz interval debt; invalid pairs remain in the separate invalid fields. */
        val functionalRendererReadyToQueueDroppedFrames: Int = 0,
        val functionalPhaseDecompositionInvalidPairs: Int = 0,
        val functionalNextWorkStartDelayMax: Float = 0f,
        val functionalBackendPreparationP95: Float = 0f,
        val functionalBackendPreparationMax: Float = 0f,
        val functionalResidualPriorTargetGateP95: Float = 0f,
        val functionalResidualPriorTargetGateMax: Float = 0f,
        val functionalPhaseAdmissionAfterBothReadyP95: Float = 0f,
        val functionalPhaseAdmissionAfterBothReadyMax: Float = 0f,
        val functionalPreparationOverlapSamples: Int = 0,
        val functionalPreparationOverlapP95: Float = 0f,
        val functionalPreparationOverlapMax: Float = 0f,
        val functionalTargetRetirementSamples: Int = 0,
        val functionalTargetRetirementP95: Float = 0f,
        val functionalTargetRetirementMax: Float = 0f,
        /** Sealed-scene/resource invariant failures, excluding hard latch qualification. */
        val functionalGpuInvariantFailedFrames: Int = 0,
        val fixedPhaseTelemetryInvalidFrames: Int = 0,
        val fixedPhaseHardPostFailureFrames: Int = 0,
        val fixedPhaseUnexpectedFatalFrames: Int = 0,
        val physicalTargetWaitP95: Float = 0f,
        val physicalTargetWaitMax: Float = 0f,
        val retirementPublicationP95: Float = 0f,
        val retirementPublicationMax: Float = 0f,
        val opportunityReceiptToDecisionP95: Float = 0f,
        val opportunityReceiptToDecisionMax: Float = 0f,
        val fixedDemandConservationInvalidFrames: Int = 0,
        val fixedOpportunityIdentityInvalidFrames: Int = 0,
        val fixedOpportunityWakeLostFrames: Int = 0,
        val fixedRetirementClockInvalidFrames: Int = 0,
        val fixedCallbackAuthorityInvalidFrames: Int = 0,
        val fixedSupersededBeforeClaimCount: Long = 0L,
        val fixedCase1Frames: Int = 0,
        val fixedCase2Frames: Int = 0,
        val readyCommitPriorityViolationFrames: Long = 0L,
        val preCommitRetirementObservationFrames: Long = 0L,
        val postSwapCriticalP95: Float = 0f,
        val postSwapCriticalMax: Float = 0f,
        val postSwapToNextReservationP95: Float = 0f,
        val postSwapToNextReservationMax: Float = 0f,
        val retainedQueryRequiredCount: Long = 0L,
        val retainedQueryExecutedCount: Long = 0L,
        val retainedQueryWrongSelectionCount: Long = 0L,
        val commitBeforeRetainedQueryCount: Long = 0L,
        val callbackArrivedDuringQueryCount: Long = 0L,
        val pureDrawIssueP95: Float = 0f,
        val pureDrawIssueMax: Float = 0f,
        val frameIdReservationP95: Float = 0f,
        val frameIdReservationMax: Float = 0f,
        val backendPrepareToSignalP95: Float = 0f,
        val backendPrepareToSignalMax: Float = 0f,
        val backendSignalToReturnP95: Float = 0f,
        val backendSignalToReturnMax: Float = 0f,
        val commonCallbackTransactionP95: Float = 0f,
        val commonCallbackTransactionMax: Float = 0f,
        val wakeDispatchToRendererCallbackP95: Float = 0f,
        val wakeDispatchToRendererCallbackMax: Float = 0f,
        val rendererCallbackToCommitEntryP95: Float = 0f,
        val rendererCallbackToCommitEntryMax: Float = 0f,
        val commonCommitEntryToClaimP95: Float = 0f,
        val commonCommitEntryToClaimMax: Float = 0f,
        val backendPhasePartitionInvalidFrames: Int = 0,
        val evidenceCapsuleMaxDepth: Int = 0,
        val evidenceCapsuleInvalidFrames: Long = 0L,
        val schema10EvidenceFrames: Int = 0,
        val schema10IdentityInvalidFrames: Int = 0,
        val fixedBackendConservationInvalidFrames: Int = 0,
        val surfaceControlLatchInvalidFrames: Int = 0,
        val externalSubmissionInvalidFrames: Int = 0,
        val hardwareBufferIdentityInvalidFrames: Int = 0
    )

    interface WindowListener {
        fun onWindowChanged(
            firstPage: Int,
            lastPage: Int,
            anchorPage: Int,
            progressPage: Int,
            progressOffset: Int,
            busy: Boolean,
            directionHint: Int,
            reverseFirstPageHint: Int,
        )
        fun onNearBoundary(direction: Int, anchorPage: Int)
        fun onBoundaryReached(direction: Int, anchorPage: Int)
        fun onTap()
        /** Starts a new physical motion interval before any MOVE from that gesture is rendered. */
        fun onPhysicalScrollGestureStarted() {}
        /** A Surface/lifecycle boundary interrupted the current drag or inertial motion. */
        fun onPhysicalScrollMotionEnded() {}
        fun onVisibleCoverageChanged(snapshot: VisibleCoverageSnapshot) {}
        fun onCompletedDraw(proof: CompletedDrawProof) {}
        fun shouldReportVisibleStats(): Boolean = true
    }

    private data class Page(
        var bitmap: Bitmap? = null,
        /** Renderer-only pixels for a structural card; never a work-image/provenance bitmap. */
        var cardBitmap: Bitmap? = null,
        var tiles: List<ReaderTile> = emptyList(),
        var width: Int = 0,
        var height: Int = 0,
        var originalProof: ReaderPreparedStore.PreparedOriginalProof? = null,
        var loading: Boolean = false,
        var cardText: String? = null,
        var errorText: String? = null,
        var pendingResolveType: Int = PENDING_NONE,
        var pendingBitmap: Bitmap? = null,
        var pendingTiles: List<ReaderTile> = emptyList(),
        var pendingWidth: Int = 0,
        var pendingHeight: Int = 0,
        var placeholderRatio: Float = DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO,
        var stripAuthority: Long = 0L,
        var stripEpisode: Long = 0L,
        var stripAsset: String? = null,
        var stripSlots: List<ReaderTile?> = emptyList(),
        var adjacentExactOwner: NtkAdjacentExactP0Owner? = null,
        var adjacentExactPlan: NtkPreGeometryPagePlan? = null,
        var adjacentExactSlots: List<ReaderTile?> = emptyList(),
        var adjacentExactCycles: Map<Int, AdjacentExactResidentCycle> = emptyMap(),
        var committedIdentity: CommittedPageIdentity? = null
    )

    private data class DrawItem(
        val index: Int,
        val bitmap: Bitmap?,
        val cardBitmap: Bitmap?,
        val tiles: List<ReaderTile>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val originalProof: ReaderPreparedStore.PreparedOriginalProof?,
        val stripAuthoritative: Boolean,
        val adjacentExactAuthoritative: Boolean,
        val stripAsset: String?,
        val committedIdentity: CommittedPageIdentity?,
        val loading: Boolean,
        val cardText: String?,
        val errorText: String?,
        val top: Float,
        val pageHeight: Float
    )

    private data class SoftwarePrepareTarget(
        val page: Int,
        val bitmap: Bitmap
    )

    private data class SoftwarePreparePlan(
        val targets: List<SoftwarePrepareTarget>,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val scrollOffset: Float,
        val anchorPage: Int,
        val prepareThroughY: Int,
        val visualGeneration: Long
    )

    private data class DrawState(
        val width: Int,
        val height: Int,
        val busy: Boolean,
        val empty: Boolean,
        val visibleLoading: Int,
        val hasDrawableContent: Boolean,
        val scrollOffset: Float,
        val contentHeight: Float,
        val pageCount: Int,
        val items: List<DrawItem>,
        val visibleContentPxOverride: Int = -1,
        val preparedScene: PreparedRenderScene? = null,
        val visualGeneration: Long = 0L,
        val directPreparedBitmapBatch: Boolean = false,
        val realPixelsOnly: Boolean = false,
        val traversalEpoch: Long = 0L,
        val forwardRunway: ForwardRunwaySnapshot? = null,
        val directWifiForwardOnlyInitialResume: Boolean = false,
        val directWifiForwardOnlyTerminalTailActual: Boolean = false,
        val enforceContiguousExactStructure: Boolean = false,
    )

    private enum class BitmapSubmissionMode {
        PAGE_RENDER_NODE,
        DIRECT_VISIBLE_CROP
    }

    private data class RetainedPageNode(
        val node: RenderNode,
        val bitmap: Bitmap?,
        val tileBitmaps: List<Bitmap>,
        val width: Int,
        val height: Int
    )

    private data class PreparedRenderChunk(
        val node: RenderNode,
        val top: Float,
        val bottom: Float
    )

    private data class PreparedRenderScene(
        val chunks: List<PreparedRenderChunk>,
        val pageCount: Int,
        val width: Int,
        val height: Float,
        val opaque: Boolean,
        var generation: Long = 0L
    )

    private data class CoverageStats(
        val drawablePx: Int,
        val missingPx: Int,
        val placeholderPx: Int,
        val drawableItems: Int,
        val totalItems: Int,
        val lowResolutionItems: Int,
        val minDrawableSourceWidth: Int
    )

    private data class WindowRequest(
        val firstPage: Int,
        val lastPage: Int,
        val anchorPage: Int,
        val progressPage: Int,
        val progressOffset: Int,
        val busy: Boolean,
        val nearStart: Boolean,
        val nearEnd: Boolean,
        val notifyNearStart: Boolean,
        val notifyNearEnd: Boolean,
        val directionHint: Int = 0,
        val reverseFirstPageHint: Int = -1,
    )

    private data class BoundaryRequest(
        val direction: Int,
        val anchorPage: Int
    )

    private data class DrawTiming(
        val frameTimeNs: Long,
        val callbackStartNs: Long,
        val lockWaitMs: Float,
        val drawMs: Float,
        val postMs: Float,
        val totalMs: Float,
        val postEndNs: Long,
        val posted: Boolean,
        val mutationWatermark: Long = 0L,
        val mutationOldestToCallbackMs: Float = 0f,
        val mutationNewestToCallbackMs: Float = 0f,
        val mutationOldestToPostMs: Float = 0f,
        val mutationNewestToPostMs: Float = 0f,
        val invalidationToCallbackMs: Float = 0f,
        val invalidationToPostMs: Float = 0f,
        /** Physical native Surface epoch that accepted this exact buffer, or zero for HWUI. */
        val nativeSurfaceEpoch: Long = 0L
    )

    private data class PixelMutationTiming(
        val watermark: Long,
        val oldestNs: Long,
        val newestNs: Long,
        val reasons: Int,
        val invalidationPostedNs: Long
    )

    private data class PendingInput(
        val oldestNs: Long,
        val newestNs: Long,
        val events: Int,
        val history: Int
    )

    private data class RenderWork(
        val request: WindowRequest?,
        val boundary: BoundaryRequest?,
        val state: DrawState?,
        val frameToken: Long = 0L,
        val frameEpoch: Long = 0L,
        val drawnVersion: Long = 0L,
        val mutation: PixelMutationTiming? = null
    )

    private enum class FramePipe {
        IDLE,
        INVALIDATION_POSTED
    }

    private data class PendingCommittedDraw(
        val frameToken: Long,
        val drawnVersion: Long,
        val hardwareAccelerated: Boolean,
        val coverage: VisibleCoverageSnapshot,
        val traversal: FrameTraversalProof?,
        val structureEpoch: Long,
        val visiblePageIndexes: IntArray,
        val visiblePageIdentities: List<CommittedPageIdentity>,
        val scrollOffsetPx: Float
    )

    /**
     * One immutable HWUI submission waiting for its registered frame-commit callback.
     *
     * The old renderer kept this state in the sole invalidation token and therefore refused to
     * submit the next vsync until RenderThread/GPU commit completed.  Host-GPU commit latency can
     * legitimately span several display intervals, so that serialization capped presentation at
     * roughly 20 fps.  Submission admission and commit proof are deliberately separate here: one
     * invalidation may be recorded at a time, while a small bounded set of already-recorded frames
     * can wait for their individual fail-closed HWUI proof.
     */
    private data class PendingFrameCommit(
        val frameEpoch: Long,
        val nativeSurfaceEpoch: Long,
        val callback: Runnable,
        val callbackRegistered: Boolean,
        val surfaceQueueSubmission: Boolean,
        val surfaceControlSubmission: Boolean,
        val proof: PendingCommittedDraw
    )

    /** A native queueBuffer proof that won the JNI-return / Kotlin-registration race. */
    private data class EarlyNativePresentation(
        val presentedUptimeNanos: Long,
        val presentationKind: Int,
    )

    private data class FrameTraversalProof(
        val structureEpoch: Long,
        val visiblePageIndexes: IntArray,
        val visiblePageIdentities: List<CommittedPageIdentity>,
        val viewportDefect: Boolean,
        val runwayDefect: Boolean
    )

    private val stateLock = Object()
    private val retainedNodeLock = Any()
    private val retainedPageNodes = HashMap<Int, RetainedPageNode>()
    @Volatile private var preparedRenderScene: PreparedRenderScene? = null
    private var visualGeneration = 0L
    private var directPreparedBitmapGeneration = Long.MIN_VALUE
    private var directPreparedBitmapWidth = 0
    private var directPreparedBitmapOpaque = false
    private val pages = ArrayList<Page>()
    private var stripAuthorityToken = 0L
    private var stripGeometry: NtkStripGeometry? = null
    private val stripResidentCoverage = NtkStripIntervalSet()
    private data class StripResidentCycle(
        val resourceRevision: Long,
        val installLease: Long,
        val rgbaBytes: Long
    )
    private data class AdjacentExactResidentCycle(
        val resourceRevision: Long,
        val installLease: Long,
        val rgbaBytes: Long,
    )
    private val stripResidentCycles = HashMap<NtkStripTileKey, StripResidentCycle>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 190, 190)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    private val src = Rect()
    private val dstInt = Rect()
    private val dst = RectF()
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var pageGapPx = DEFAULT_PAGE_GAP_PX
    private var placeholderPageHeightRatio = DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO
    private val mainHandler = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Handler.createAsync(Looper.getMainLooper())
    } else {
        Handler(Looper.getMainLooper())
    }
    private val completedDrawDispatchQueue = CompletedDrawDispatchQueue(
        MAX_COMPLETED_DRAW_SEMANTIC_TRANSITIONS,
        COMPLETED_DRAW_ORDINARY_CADENCE_NANOS,
    )
    private val completedDrawDispatchRunnable = Runnable { drainCompletedDrawDispatch() }
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    @Volatile private var cachedDisplayRefreshRate =
        NtkDisplayTimingPolicy.normalizedRefreshRate(null)
    private var displayListenerRegistered = false
    private val displayTimingListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val currentDisplay = display ?: return
            if (currentDisplay.displayId == displayId) refreshCachedDisplayTiming()
        }
    }
    private val benchmarkPhysicalMotionIdleRunnable = object : Runnable {
        override fun run() {
            var retryAfterPhysicalSettle = false
            val idleProof = synchronized(stateLock) {
                val enabled = BenchmarkAdjacentCommitSignal.isEnabled()
                val physicalMotionActive = physicalScrollTraceCookie != 0 || dragging ||
                    !scroller.isFinished
                val pixelsStillPending =
                    benchmarkPhysicalMotionIdleRequiredVersion > committedVersion
                // Freeze the gesture-owned version when the check is armed. An adjacent image can
                // advance desiredVersion during this 96 ms confirmation window, but that is new
                // content work rather than unfinished physical motion. A zero-pixel final edge
                // gesture can also leave no trace cookie. After UP, poll without changing either
                // the scroller or pixels; a new DOWN cancels this callback and its own UP rearms it.
                retryAfterPhysicalSettle = enabled && !pointerDown &&
                    (physicalMotionActive || pixelsStillPending)
                if (!enabled || pointerDown || physicalMotionActive || pixelsStillPending) {
                    null
                } else {
                    SystemClock.elapsedRealtimeNanos() to ViewerTelemetry.activeGeneration()
                }
            }
            when {
                idleProof != null -> {
                    val (endedAtNanos, generation) = idleProof
                    BenchmarkAdjacentCommitSignal.publishPhysicalMotionIdle(
                        endedAtNanos,
                        generation,
                    )
                }
                retryAfterPhysicalSettle -> mainHandler.postDelayed(
                    this,
                    BENCHMARK_PHYSICAL_MOTION_IDLE_CONFIRM_MS,
                )
            }
        }
    }

    private var velocityTracker: VelocityTracker? = null
    private var renderRunning = false
    private var renderRequested = false
    private var framePipe = FramePipe.IDLE
    private var lifecycleEpoch = 1L
    private var nextFrameToken = 1L
    private var inFlightToken = 0L
    private var inFlightEpoch = 0L
    private var inFlightCommitCallback: Runnable? = null
    private var inFlightCommitCallbackRegistered = false
    private var inFlightInvalidationPostedNs = 0L
    private val pendingFrameCommits = LinkedHashMap<Long, PendingFrameCommit>()
    private val earlyNativePresentations = LinkedHashMap<Long, EarlyNativePresentation>()
    private var directSurfaceReady = false
    private val nativeSurfaceView = SurfaceView(context)
    /**
     * Instrumentation-only A/B switch. The production default remains the rolling native
     * producer; a test process can force the already-supported HWUI path before constructing the
     * reader without changing content admission, scroll coordinates, or adjacent loading policy.
     */
    private val rollingNativePresentationEnabled =
        !java.lang.Boolean.getBoolean(TEST_FORCE_HWUI_SYSTEM_PROPERTY)
    private val emulatorNativeSurfaceRuntime = NtkNativeSurfaceFrameRatePolicy.isEmulatorRuntime(
        Build.FINGERPRINT,
        Build.MODEL,
        Build.HARDWARE,
        Build.PRODUCT,
    )
    private var rollingTextureSurface: Surface? = null
    private var rollingNativeHandle = 0L
    private var rollingNativeCreatePending = false
    private var rollingNativeCreateGeneration = 0L
    /** Monotonic identity of physical Surface attachment requests; independent of frame state. */
    private var rollingNativeSurfaceEpochCounter = 0L
    private var rollingNativeAttachEpoch = 0L
    private var rollingNativeSurfaceIdentity = 0
    private var rollingNativeWidth = 0
    private var rollingNativeHeight = 0
    private var rollingNativeViewportWidth = 0
    private var rollingNativeViewportHeight = 0
    private var rollingNativePreparedWidth = 0
    private var rollingNativePreparedHeight = 0
    /**
     * Pure geometry captured at the first measured viewport, before decoded page storage can turn
     * an intended source-native target back into a full-window host-GPU buffer. No native object,
     * Surface or pixel resource is created at this point.
     */
    private var rollingNativeFrozenViewportWidth = 0
    private var rollingNativeFrozenViewportHeight = 0
    private var rollingNativeFrozenTargetWidth = 0
    private var rollingNativeFrozenTargetHeight = 0
    private var rollingNativeFatal = false
    private var nativePresentationVisible = false
    /** Content identity of the exact native buffer currently retained by SurfaceFlinger. */
    private var nativePresentedStructureEpoch = 0L
    private var rollingNativeRecoveryPending = false
    private var nativeTexturePrewarmDirty = false
    private var nativeTexturePrewarmFlushPosted = false
    private var nativeTexturePrewarmAnchorPage = -1
    private val nativeTexturePrewarmPendingPages = LinkedHashSet<Int>()
    private var nativeTexturePrewarmPaused = false
    private var lastNativeTextureRunwayRejectLogMs = 0L
    private var lastNativeSubmitDiagnosticLogMs = 0L
    private var unmatchedNativePresentationCount = 0L
    private var firstNativeSubmitAccepted = false
    private var directRenderThread: HandlerThread? = null
    private var directRenderHandler: Handler? = null
    private var directChoreographer: Choreographer? = null
    private var directFrameCallbackPosted = false
    private var directLateInputCatchupPosted = false
    private var directAdjacentExactP0CatchupPosted = false
    private var directAdjacentExactP0CatchupEpoch = 0L
    private var directAdjacentExactP0CatchupToken = 0L
    private var directAdjacentExactP0CatchupAttachEpoch = 0L
    private var directAdjacentExactP0CatchupOwner: NtkAdjacentExactP0Owner? = null
    private var dragTargetRevision = 0L
    private var directCallbackObservedDragTargetRevision = 0L
    private var physicalGestureRevision = 0L
    private var directCallbackObservedPhysicalGestureRevision = 0L
    private var directCallbackObservedAtNanos = 0L
    private var directCallbackHadAdmission = false
    private var noStateRetryPosted = false
    private var desiredVersion = 0L
    private var drawnVersion = 0L
    private var committedVersion = 0L
    /**
     * Compositor watermark captured when the physical-input settle check is armed.
     * Later adjacent-body installs may advance desiredVersion, but they do not extend the gesture.
     */
    private var benchmarkPhysicalMotionIdleRequiredVersion = 0L
    private var pendingPixelReasons = 0
    private var pixelMutationWatermark = 0L
    private var pendingPixelMutationWatermark = 0L
    private var pendingPixelMutationOldestNs = 0L
    private var pendingPixelMutationNewestNs = 0L
    private var pendingPixelMutationReasons = 0
    private var downX = 0f
    private var downY = 0f
    private var dragOriginY = 0f
    private var dragOriginScrollOffset = 0f
    private var lastVelocitySampleMs = 0L
    private var lastScrollInteractionMs = 0L
    private var pointerDown = false
    private var dragging = false
    private var scrollbarDragging = false
    private var scrollbarDragOffset = 0f
    private var scrollbarVisible = false
    private var activeInputDirection = 0
    private var scrollOffset = 0f
    private var activeScrollerOffsetShift = 0f
    private var lockedRestorePage = -1
    private var lockedRestoreOffset = 0
    private var lockedRestoreUntilMs = 0L
    private var pendingPreparedStartPage = -1
    private var pendingPreparedStartOffset = 0
    private var pendingPreparedViewportWidth = 0
    private var pendingPreparedViewportHeight = 0
    private var structuralScrollAdjustUntilMs = 0L
    private var pendingResolveRetryPosted = false
    private var prependedRevealHoldPage = -1
    private var prependedReadyHoldInserted = 0
    private var prependedReadyHoldRequired = 0
    private var initialRenderHoldPage = -1
    private var initialRenderHoldUntilMs = 0L
    private var initialViewportHoldUntilMs = 0L
    private var visibleLoadingHoldRetryPosted = false
    private var deferInitialEmptyDraw = false
    private var listener: WindowListener? = null
    private var lastAnchor = -1
    private var lastNearStart = false
    private var lastNearEnd = false
    private var lastBusy = false
    private var lastRequestedBusy = false
    private var lastBusyWindowDispatchMs = 0L
    private var lastBusyNearDispatchMs = 0L
    private var layoutDirty = true
    private var pageTops = FloatArrayList(0)
    private var pageTopDeltas = RangeAddPointQuery(0)
    private var contentHeight = 0f
    private var statsActive = false
    private var activeScrollTraceCookie = 0
    private var nextActiveScrollTraceCookie = 1
    private var physicalScrollTraceCookie = 0
    private var nextPhysicalScrollTraceCookie = 1
    /**
     * Latest compositor version caused by an actual physical offset change. Content arriving
     * after the finger reaches a drawable edge must not extend the preceding motion interval;
     * the first later MOVE that can advance again starts a new interval with its own watermark.
     */
    private var physicalMotionRequiredVersion = 0L
    private var statsAwaitingFirstInput = false
    private var programmaticScrollStatsUntilMs = 0L
    private var edgeNoMovementStatsSuppressedUntilMs = 0L
    private var statsLastCallbackStartNs = 0L
    private var statsLastPostEndNs = 0L
    private var statsLastScrollOffset = Float.NaN
    private var statsMotionStarted = false
    private var lastPostedFrameEndNs = 0L
    private var lastSlowFrameLogMs = 0L
    private var lastPixelMutationGapLogMs = 0L
    private var suppressedPixelMutationGapLogs = 0L
    private var statsCoalescedRequests = 0
    private var statsNoCanvasFrames = 0
    private var statsMaxMissingPx = 0
    private var statsMaxPlaceholderPx = 0
    private var statsMaxVisibleLoading = 0
    private var hasDrawnContentFrame = false
    private var lastVisibleLoading = -1
    private val frameStatsFinalizeRunnable = Runnable {
        val snapshot = synchronized(stateLock) {
            finalizeActiveFrameStatsLocked(log = true)
        } ?: return@Runnable
        logFrameStatsSnapshot(snapshot)
    }
    private val statsCallbackSpacingMs = ArrayList<Float>(240)
    private val statsPostSpacingMs = ArrayList<Float>(240)
    private val statsLockWaitMs = ArrayList<Float>(240)
    private val statsDrawMs = ArrayList<Float>(240)
    private val statsPostMs = ArrayList<Float>(240)
    private val statsTotalMs = ArrayList<Float>(240)
    private val statsInputOldestMs = ArrayList<Float>(240)
    private val statsInputNewestMs = ArrayList<Float>(240)
    private val statsMutationOldestToCallbackMs = ArrayList<Float>(240)
    private val statsMutationNewestToCallbackMs = ArrayList<Float>(240)
    private val statsMutationOldestToPostMs = ArrayList<Float>(240)
    private val statsMutationNewestToPostMs = ArrayList<Float>(240)
    private var pendingOldestInputNs = 0L
    private var pendingNewestInputNs = 0L
    private var pendingInputEvents = 0
    private var pendingHistorySamples = 0
    private var pendingWindowRequest: WindowRequest? = null
    private var windowDispatchPosted = false
    /** Reverse MOVE evidence captured even when the window cadence suppresses that MOVE request. */
    private var pendingReverseWindowFirstPageHint = -1
    private var pendingBlockedForwardRequest: WindowRequest? = null
    private var blockedForwardDispatchPosted = false
    private var lastBlockedForwardRequestAtMs = 0L
    private var lastBlockedForwardPage = -1
    private var boundaryArmedDirection = 0
    private var boundaryDispatchInFlight = false
    private var nextBoundaryAppendInFlight = false
    private var nextBoundaryHoldUntilMs = 0L
    private var nextBoundaryHoldMinScroll = 0f
    private var nextBoundaryHoldAnchorPage = -1
    private var lastCoverageLog: CoverageStats? = null
    private var lastCoverageLogMs = 0L
    private var lastTestScrollDiagnosticLogMs = 0L
    private var lastDirectionClampLogMs = 0L
    private var lastForwardCapLogMs = 0L
    private var lastExactStructureHoldLogMs = 0L
    private var lastVisibleCoverageSnapshot: VisibleCoverageSnapshot? = null
    private var completedDrawSequence = 0L
    private var traversalStructureEpoch = 0L
    private var traversalExpectedPageCount = 0
    private var traversalCommittedPages = BooleanArray(0)
    private var traversalSubmittedFrames = 0L
    private var traversalCommittedFrames = 0L
    private var traversalSubmittedViewportDefectFrames = 0L
    private var traversalSubmittedRunwayDefectFrames = 0L
    private var traversalCommittedViewportDefectFrames = 0L
    private var traversalCommittedRunwayDefectFrames = 0L
    private var lastFrameStatsSnapshot: FrameStatsSnapshot? = null
    private var limitScrollToDrawablePrefix = false
    private var inlineRealPixelsOnly = false
    private var forwardNativeTexturePrewarmEnabled = false
    private var directWifiExpandedNativeTextureRunway = false
    private var directWifiExpandedNativeTextureEpisodeHistory =
        NtkExpandedNativeTextureHistoryPolicy.State()
    private val directWifiExpandedNativeTextureEpisodePaths = linkedSetOf<String>()
    private var directWifiExpandedNativeTextureMinimumPage = 0
    private var directWifiShortWebtoonPixelWindowPrewarm = false
    private var directWifiForwardOnlyInitialResumeEnabled = false
    private var directWifiForwardOnlyInitialResumePage = -1
    private var directWifiForwardOnlyInitialResumeOffset = 0
    private var directWifiForwardOnlyInitialResumeRevealQualified = false
    private var directWifiForwardOnlyTerminalTailRevealQualified = false
    private var sourceNativeWebtoonCompositingEnabled = false
    private val hwuiPreparedBitmapKeys = HashSet<Long>()
    private var frameSchedulingSuppressed = false
    /**
     * The strict reader starts on the ordinary HWUI root. Its first exact visible image is allowed
     * to draw progressively; the native child is created only after one complete real-pixel HWUI
     * viewport has committed, so cold EGL/AHB work cannot block the Activity's first frame.
     */
    private var surfaceAttachmentDeferredUntilActualPixels = false
    private var surfaceRevealPosted = false
    private var nativeSurfaceRevealAfterFirstHwuiCommitPending = false
    private var nativeSurfaceRevealAfterPrepareEpoch = 0L
    private var deferredSurfaceIdentityActivated = false
    private var lastSurfaceRevealProbeMs = 0L

    init {
        // SurfaceControl requires a real SurfaceView parent. Keep it transparent and above the
        // window so the already-rendered HWUI image remains visible until the first exact native
        // buffer latches; the child SurfaceControl becomes opaque only with that real buffer.
        nativeSurfaceView.setZOrderOnTop(true)
        nativeSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        nativeSurfaceView.holder.addCallback(this)
        nativeSurfaceView.isClickable = false
        nativeSurfaceView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        nativeSurfaceView.visibility = View.GONE
        addView(
            nativeSurfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true
    }

    /** Must be called with [stateLock] held. */
    private fun pageHasCompleteActualPixelsLocked(page: Page): Boolean {
        val bitmap = page.bitmap
        return when {
            page.cardText != null || page.errorText != null -> false
            bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0 -> true
            usableAuthoritativeOriginalTilePage(
                page.width,
                page.height,
                page.tiles,
                page.originalProof
            ) -> true
            hasCompleteFullQualityTilePixelsLocked(page) -> true
            else -> false
        }
    }

    /** Must be called with [stateLock] held. */
    private fun currentViewportDrawableOpaqueLocked(): Boolean {
        if (pages.isEmpty() || width <= 0 || height <= 0) return false
        rebuildLayoutLocked()
        val viewportTop = scrollOffset
        val viewportBottom = viewportTop + height
        var coveredUntil = viewportTop
        var sawVisiblePage = false
        for (index in pages.indices) {
            val page = pages[index]
            val top = pageTopOrElseLocked(index, 0f)
            val bottom = top + pageDrawHeightLocked(page)
            if (bottom <= viewportTop) continue
            if (top >= viewportBottom) break
            val visibleTop = max(viewportTop, top)
            val visibleBottom = min(viewportBottom, bottom)
            if (visibleBottom <= visibleTop) continue
            if (visibleTop > coveredUntil + DRAW_COVERAGE_EPSILON_PX) return false
            // The producer surface itself is RGBX and clears every frame. A decoded JPEG often
            // remains tagged hasAlpha=true simply because BitmapFactory returned ARGB_8888; that
            // metadata does not mean the viewport is unresolved. Conversely, loading/error cards
            // must never qualify as real work-image pixels. Gate attachment on a complete live
            // image resource, including a provenance-free legacy tile page whose full 0..height
            // geometry is independently validated by AdoptedDrawableIdentity.
            if (!pageHasCompleteActualPixelsLocked(page)) return false
            sawVisiblePage = true
            coveredUntil = max(coveredUntil, visibleBottom)
            if (coveredUntil >= viewportBottom - DRAW_COVERAGE_EPSILON_PX) return true
        }
        return sawVisiblePage &&
            coveredUntil >= viewportBottom - DRAW_COVERAGE_EPSILON_PX
    }

    /**
     * A forward-only resume can legitimately start inside a short final image. Earlier images from
     * the same episode may not be resident yet, so the ordinary content max would pin that image to
     * the bottom of a viewport full of unresolved predecessors. Once current completion has
     * admitted the exact next p0, preserve the bookmark offset and accept exactly: current tail,
     * one opaque transition card, then source-qualified next-episode pixels. This is intentionally
     * impossible outside the frozen direct-Wi-Fi renderer profile.
     */
    private fun directWifiForwardOnlyInitialResumeViewportOpaqueLocked(): Boolean {
        val target = directWifiForwardOnlyInitialResumePage
        if (!directWifiForwardOnlyInitialResumeEnabled || target !in pages.indices ||
            width <= 0 || height <= 0 || target >= pages.lastIndex
        ) return false
        rebuildLayoutLocked()
        val firstIdentity = pages[target].committedIdentity ?: return false
        val currentEpisodePath = firstIdentity.normalizedEpisodePath
        val currentManifestDigest = firstIdentity.manifestDigest
        val currentManifestPageCount = firstIdentity.manifestPageCount
        if (currentEpisodePath !in directWifiExpandedNativeTextureEpisodePaths ||
            currentManifestPageCount <= 0 || currentManifestDigest.length != 64 ||
            !currentManifestDigest.all { it in '0'..'9' || it in 'a'..'f' }
        ) return false
        val targetTop = pageTopOrElseLocked(target, Float.NaN)
        val expectedScroll = targetTop - directWifiForwardOnlyInitialResumeOffset
        if (!targetTop.isFinite() ||
            abs(scrollOffset - expectedScroll) > RESTORE_POSITION_EPSILON_PX
        ) {
            return false
        }
        val viewportTop = scrollOffset
        val viewportBottom = viewportTop + height
        var coveredUntil = viewportTop
        var sawTarget = false
        var sawTransition = false
        var sawAdjacentActual = false
        var previousCurrentSource = -1
        var previousCurrentCanonicalAsset = ""
        var currentTailLastIndex = -1
        var adjacentEpisodePath: String? = null
        for (index in target until pages.size) {
            val page = pages[index]
            val top = pageTopOrElseLocked(index, Float.NaN)
            if (!top.isFinite()) return false
            val bottom = top + pageDrawHeightLocked(page)
            if (bottom <= viewportTop) continue
            if (top >= viewportBottom) break
            val visibleTop = max(viewportTop, top)
            val visibleBottom = min(viewportBottom, bottom)
            if (visibleBottom <= visibleTop) continue
            if (visibleTop > coveredUntil + DRAW_COVERAGE_EPSILON_PX) return false
            when {
                page.cardText != null -> {
                    if (!sawTarget || sawTransition || sawAdjacentActual ||
                        previousCurrentSource != currentManifestPageCount - 1 ||
                        index != currentTailLastIndex + 1 || page.errorText != null
                    ) return false
                    sawTransition = true
                }
                !sawTransition -> {
                    val identity = page.committedIdentity ?: return false
                    if (!pageHasCompleteActualPixelsLocked(page) || page.loading ||
                        page.errorText != null ||
                        identity.displayPageIndex != index ||
                        identity.normalizedEpisodePath != currentEpisodePath ||
                        identity.manifestDigest != currentManifestDigest ||
                        identity.manifestPageCount != currentManifestPageCount ||
                        identity.sourcePageIndex !in 0 until currentManifestPageCount ||
                        identity.canonicalAsset.isBlank()
                    ) {
                        return false
                    }
                    if (previousCurrentSource >= 0 &&
                        (identity.sourcePageIndex < previousCurrentSource ||
                            identity.sourcePageIndex > previousCurrentSource + 1 ||
                            (identity.sourcePageIndex == previousCurrentSource &&
                                identity.canonicalAsset != previousCurrentCanonicalAsset))
                    ) {
                        return false
                    }
                    sawTarget = true
                    previousCurrentSource = identity.sourcePageIndex
                    previousCurrentCanonicalAsset = identity.canonicalAsset
                    currentTailLastIndex = index
                }
                else -> {
                    val identity = page.committedIdentity ?: return false
                    if (!sawTransition || !pageHasCompleteActualPixelsLocked(page) ||
                        page.loading || page.errorText != null ||
                        identity.displayPageIndex != index ||
                        identity.normalizedEpisodePath == currentEpisodePath ||
                        identity.normalizedEpisodePath !in directWifiExpandedNativeTextureEpisodePaths ||
                        identity.sourcePageIndex !in 0 until identity.manifestPageCount ||
                        identity.canonicalAsset.isBlank() || identity.manifestDigest.length != 64 ||
                        !identity.manifestDigest.all { it in '0'..'9' || it in 'a'..'f' } ||
                        (adjacentEpisodePath != null &&
                            identity.normalizedEpisodePath != adjacentEpisodePath)
                    ) {
                        return false
                    }
                    adjacentEpisodePath = identity.normalizedEpisodePath
                    sawAdjacentActual = true
                }
            }
            coveredUntil = max(coveredUntil, visibleBottom)
            if (coveredUntil >= viewportBottom - DRAW_COVERAGE_EPSILON_PX) {
                return sawTarget && sawTransition && sawAdjacentActual
            }
        }
        return false
    }

    /**
     * Classifies only the real current-episode tail that precedes the combined transition
     * viewport. A resume can cover more than one short canonical image (for example p18+p19), so
     * validate the complete exact tail rather than requiring the restored display page itself to
     * be the final display slot. The tail must occupy the restored bookmark coordinate and end
     * naturally before the physical viewport. No card, inferred geometry, unresolved resource,
     * skipped source, or adjacent structure can satisfy this predicate.
     */
    private fun directWifiForwardOnlyTerminalTailActualLocked(): Boolean {
        val target = directWifiForwardOnlyInitialResumePage
        if (!emulatorNativeSurfaceRuntime || !directWifiForwardOnlyInitialResumeEnabled ||
            target !in pages.indices || width <= 0 || height <= 0 ||
            target < directWifiExpandedNativeTextureMinimumPage
        ) return false
        rebuildLayoutLocked()
        val firstIdentity = pages[target].committedIdentity ?: return false
        val episodePath = firstIdentity.normalizedEpisodePath
        val manifestDigest = firstIdentity.manifestDigest
        val manifestPageCount = firstIdentity.manifestPageCount
        if (episodePath !in directWifiExpandedNativeTextureEpisodePaths ||
            manifestPageCount <= 0 || manifestDigest.length != 64 ||
            !manifestDigest.all { it in '0'..'9' || it in 'a'..'f' }
        ) return false
        val targetTop = pageTopOrElseLocked(target, Float.NaN)
        val expectedScroll = targetTop - directWifiForwardOnlyInitialResumeOffset
        if (!targetTop.isFinite() ||
            abs(scrollOffset - expectedScroll) > RESTORE_POSITION_EPSILON_PX
        ) return false
        val viewportTop = scrollOffset
        val viewportBottom = viewportTop + height
        var coveredUntil = viewportTop
        var previousSource = -1
        var previousCanonicalAsset = ""
        var terminalBottom = Float.NaN
        for (index in target until pages.size) {
            val page = pages[index]
            val identity = page.committedIdentity ?: return false
            if (!pageHasCompleteActualPixelsLocked(page) ||
                page.pendingResolveType != PENDING_NONE || page.loading ||
                page.cardText != null || page.errorText != null ||
                identity.displayPageIndex != index ||
                identity.normalizedEpisodePath != episodePath ||
                identity.manifestDigest != manifestDigest ||
                identity.manifestPageCount != manifestPageCount ||
                identity.sourcePageIndex !in 0 until manifestPageCount ||
                identity.canonicalAsset.isBlank()
            ) return false
            if (previousSource >= 0) {
                if (identity.sourcePageIndex < previousSource ||
                    identity.sourcePageIndex > previousSource + 1 ||
                    (identity.sourcePageIndex == previousSource &&
                        identity.canonicalAsset != previousCanonicalAsset)
                ) return false
            }
            val top = pageTopOrElseLocked(index, Float.NaN)
            if (!top.isFinite()) return false
            val bottom = top + pageDrawHeightLocked(page)
            val visibleTop = max(viewportTop, top)
            val visibleBottom = min(viewportBottom, bottom)
            if (visibleBottom <= visibleTop ||
                visibleTop > coveredUntil + DRAW_COVERAGE_EPSILON_PX
            ) return false
            coveredUntil = max(coveredUntil, visibleBottom)
            terminalBottom = bottom
            previousSource = identity.sourcePageIndex
            previousCanonicalAsset = identity.canonicalAsset
        }
        return previousSource == manifestPageCount - 1 &&
            terminalBottom.isFinite() &&
            terminalBottom < viewportBottom - DRAW_COVERAGE_EPSILON_PX
    }

    /**
     * Re-checks the live exact tail after a physical proof's structure/identity epoch has already
     * been accepted. A tile can complete between DrawState capture and the compositor callback;
     * the immutable proof then has correct pixels but the previous tail-qualified bit. The
     * captured identity fallback is intentionally narrower than the general visible-identity
     * policy: it accepts only the contiguous exact current tail ending at the manifest's final
     * source. ReaderV2Activity separately proves the physical frame and allows only the natural
     * viewportShort/drawableShort shape.
     */
    fun hasExactForwardOnlyTerminalTailActual(
        capturedIdentities: List<CommittedPageIdentity> = emptyList(),
    ): Boolean = synchronized(stateLock) {
        val qualified = directWifiForwardOnlyTerminalTailRevealQualified ||
            directWifiForwardOnlyTerminalTailActualLocked() ||
            capturedForwardOnlyTerminalTailIdentitiesLocked(capturedIdentities)
        if (qualified) directWifiForwardOnlyTerminalTailRevealQualified = true
        qualified
    }

    /** Must be called with [stateLock] held after the proof's structure epoch was accepted. */
    private fun capturedForwardOnlyTerminalTailIdentitiesLocked(
        identities: List<CommittedPageIdentity>,
    ): Boolean {
        if (!emulatorNativeSurfaceRuntime || !directWifiForwardOnlyInitialResumeEnabled ||
            directWifiExpandedNativeTextureMinimumPage <= 0 || identities.isEmpty()
        ) return false
        // The mutable restore-page lock is intentionally absent here: it is consumed as soon as
        // the bookmark geometry settles, which can precede the compositor callback for the same
        // exact pixels. The first path in this insertion-ordered set is the frozen current episode;
        // later entries are only authorized forward episodes and must not qualify this current-tail
        // exception.
        val episodePath = directWifiExpandedNativeTextureEpisodePaths.firstOrNull()
            ?: return false
        val firstIdentity = identities.first()
        val manifestDigest = firstIdentity.manifestDigest
        val manifestPageCount = firstIdentity.manifestPageCount
        if (firstIdentity.normalizedEpisodePath != episodePath ||
            manifestPageCount <= 0 || manifestDigest.length != 64
        ) return false
        var previousDisplay = -1
        var previousSource = -1
        var previousCanonicalAsset = ""
        for (identity in identities) {
            if ((previousDisplay >= 0 && identity.displayPageIndex != previousDisplay + 1) ||
                identity.normalizedEpisodePath != episodePath ||
                identity.manifestDigest != manifestDigest ||
                identity.manifestPageCount != manifestPageCount ||
                identity.sourcePageIndex !in directWifiExpandedNativeTextureMinimumPage until
                    manifestPageCount ||
                identity.canonicalAsset.isBlank()
            ) return false
            if (previousSource >= 0 &&
                (identity.sourcePageIndex < previousSource ||
                    identity.sourcePageIndex > previousSource + 1 ||
                    (identity.sourcePageIndex == previousSource &&
                        identity.canonicalAsset != previousCanonicalAsset))
            ) return false
            previousDisplay = identity.displayPageIndex
            previousSource = identity.sourcePageIndex
            previousCanonicalAsset = identity.canonicalAsset
        }
        return previousSource == manifestPageCount - 1
    }

    /** Must be called with [stateLock] held after exact adjacent pixels change. */
    private fun qualifyDirectWifiForwardOnlyInitialResumeRevealLocked(): Boolean {
        val qualified = directWifiForwardOnlyInitialResumeViewportOpaqueLocked()
        if (qualified && !directWifiForwardOnlyInitialResumeRevealQualified) {
            directWifiForwardOnlyInitialResumeRevealQualified = true
            Log.d(
                TAG,
                "reader_surface_forward_only_resume_ready " +
                    "page=$directWifiForwardOnlyInitialResumePage " +
                    "scroll=${scrollOffset.toInt()},viewport=${width}x$height",
            )
        }
        return qualified
    }

    fun setWindowListener(listener: WindowListener?) {
        this.listener = listener
    }

    fun setScrollbarVisible(visible: Boolean) {
        synchronized(stateLock) {
            if (scrollbarVisible == visible) return
            scrollbarVisible = visible
            if (!visible) {
                scrollbarDragging = false
                scrollbarDragOffset = 0f
            }
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
        requestRender()
    }

    fun setLimitScrollToDrawablePrefix(enabled: Boolean) {
        var request: WindowRequest? = null
        synchronized(stateLock) {
            val effectiveEnabled = effectiveDrawablePrefixScrollLimit(
                enabled,
                inlineRealPixelsOnly
            )
            if (limitScrollToDrawablePrefix == effectiveEnabled) return
            limitScrollToDrawablePrefix = effectiveEnabled
            renderRequested = pages.isNotEmpty()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            request = if (renderRequested) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
        requestRender()
    }

    /** Read-only production invariant used by strict physical-input acceptance. */
    fun isReadinessScrollLimitEnabled(): Boolean = synchronized(stateLock) {
        limitScrollToDrawablePrefix
    }

    /**
     * Keeps an inline reader honest: unresolved pages remain missing coverage instead of being
     * painted as loading, error, or placeholder content. A separately requested drawable-prefix
     * guard may retain the last complete real-pixel viewport while its next page is still in
     * flight; real-pixels-only mode must not silently disable that production UX policy.
     */
    fun setInlineRealPixelsOnly(enabled: Boolean) {
        var request: WindowRequest? = null
        synchronized(stateLock) {
            if (inlineRealPixelsOnly == enabled) return
            inlineRealPixelsOnly = enabled
            if (enabled) {
                pageGapPx = 0
            }
            nativeTexturePrewarmAnchorPage = -1
            if (enabled) requestResidentNativeTexturePrewarmLocked()
            layoutDirty = true
            lastVisibleCoverageSnapshot = null
            renderRequested = pages.isNotEmpty()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            request = if (renderRequested) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
    }

    /**
     * Enables post-click GPU residency for decoded current/forward pages without changing the
     * page's drawable-identity rules. The direct Activity receives legacy full-quality tiles
     * whose geometry is validated at install time but which do not carry the inline strip's
     * separate original-proof object; coupling this switch to [inlineRealPixelsOnly] therefore
     * turned valid work pixels into placeholders and prevented the first native submission.
     */
    fun setForwardNativeTexturePrewarmEnabled(
        enabled: Boolean,
        directWifiExpandedRunway: Boolean = false,
        expandedEpisodePath: String = "",
        expandedMinimumPage: Int = 0,
    ) {
        synchronized(stateLock) {
            val expanded = enabled && directWifiExpandedRunway
            val normalizedExpandedPath = if (expanded) {
                NtkStripDigests.normalizeEpisodePath(expandedEpisodePath)
            } else {
                ""
            }
            val minimumPage = if (expanded) max(0, expandedMinimumPage) else 0
            val configuredHistory = if (expanded) {
                NtkExpandedNativeTextureHistoryPolicy.configured(normalizedExpandedPath)
            } else {
                NtkExpandedNativeTextureHistoryPolicy.State()
            }
            val sameExpandedPaths =
                directWifiExpandedNativeTextureEpisodeHistory == configuredHistory
            if (forwardNativeTexturePrewarmEnabled == enabled &&
                directWifiExpandedNativeTextureRunway == expanded &&
                sameExpandedPaths &&
                directWifiExpandedNativeTextureMinimumPage == minimumPage
            ) return
            forwardNativeTexturePrewarmEnabled = enabled
            directWifiExpandedNativeTextureRunway = expanded
            directWifiForwardOnlyInitialResumeEnabled = expanded
            if (!expanded) {
                directWifiForwardOnlyInitialResumePage = -1
                directWifiForwardOnlyInitialResumeOffset = 0
                directWifiForwardOnlyInitialResumeRevealQualified = false
                directWifiForwardOnlyTerminalTailRevealQualified = false
            } else if (surfaceAttachmentDeferredUntilActualPixels && !hasDrawnContentFrame &&
                lockedRestorePage >= 0
            ) {
                if (lockedRestorePage in pages.indices) {
                    directWifiForwardOnlyInitialResumePage = lockedRestorePage
                    lockedRestoreOffset = min(0, lockedRestoreOffset)
                    directWifiForwardOnlyInitialResumeOffset = lockedRestoreOffset
                }
                applyLockedRestorePositionLocked()
            }
            directWifiExpandedNativeTextureEpisodeHistory = configuredHistory
            replaceDirectWifiExpandedNativeTextureEpisodePathsLocked()
            directWifiExpandedNativeTextureMinimumPage = minimumPage
            directWifiShortWebtoonPixelWindowPrewarm = false
            if (rollingNativeHandle != 0L) {
                NtkRollingNativeBridge.nativeSetDirectWifiTextureProfile(
                    rollingNativeHandle,
                    expanded,
                    expanded && emulatorNativeSurfaceRuntime,
                )
            }
            nativeTexturePrewarmAnchorPage = -1
            nativeTexturePrewarmDirty = enabled
            if (enabled) requestResidentNativeTexturePrewarmLocked()
        }
    }

    fun setDirectWifiShortWebtoonPixelWindowPrewarmEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            val qualified = enabled && directWifiExpandedNativeTextureRunway &&
                directWifiExpandedNativeTextureEpisodePaths.isNotEmpty()
            if (directWifiShortWebtoonPixelWindowPrewarm == qualified) return
            directWifiShortWebtoonPixelWindowPrewarm = qualified
            nativeTexturePrewarmAnchorPage = -1
            nativeTexturePrewarmDirty = true
            requestResidentNativeTexturePrewarmLocked()
        }
    }

    /**
     * Extends an already source-qualified direct-Wi-Fi runway to the exact next episode. The
     * Activity calls this only after every required current-episode drawable has completed, so
     * next work cannot compete with the current episode and no previous episode is admitted.
     */
    fun authorizeCompletedForwardNativeTextureEpisode(
        predecessorEpisodePath: String,
        episodePath: String,
        claimRevision: Long,
    ) {
        val normalizedPredecessor = NtkStripDigests.normalizeEpisodePath(
            predecessorEpisodePath,
        )
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
        if (normalizedPredecessor.isEmpty() || normalizedPath.isEmpty() || claimRevision <= 0L) {
            return
        }
        synchronized(stateLock) {
            if (!directWifiExpandedNativeTextureRunway) return
            val updated = NtkExpandedNativeTextureHistoryPolicy.authorizeForward(
                directWifiExpandedNativeTextureEpisodeHistory,
                normalizedPredecessor,
                normalizedPath,
                claimRevision,
            )
            if (updated == directWifiExpandedNativeTextureEpisodeHistory) return
            directWifiExpandedNativeTextureEpisodeHistory = updated
            replaceDirectWifiExpandedNativeTextureEpisodePathsLocked()
            nativeTexturePrewarmAnchorPage = -1
            nativeTexturePrewarmDirty = true
            requestResidentNativeTexturePrewarmLocked()
            reevaluateDeferredSurfaceRevealLocked(
                "forward_episode_authorized",
                directWifiForwardOnlyInitialResumePage,
            )
        }
    }

    /** Retains one consumed predecessor after exact physical adoption for bounded reverse use. */
    fun advanceCompletedForwardNativeTextureEpisode(
        episodePath: String,
        firstDisplayPage: Int,
    ) {
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(episodePath)
        if (normalizedPath.isEmpty()) return
        synchronized(stateLock) {
            if (!directWifiExpandedNativeTextureRunway) return
            val committedPath = pages.getOrNull(firstDisplayPage)
                ?.committedIdentity
                ?.normalizedEpisodePath
                .orEmpty()
            val updated = NtkExpandedNativeTextureHistoryPolicy.advanceAfterPhysicalCommit(
                directWifiExpandedNativeTextureEpisodeHistory,
                normalizedPath,
                committedPath,
            )
            if (updated == directWifiExpandedNativeTextureEpisodeHistory) return
            directWifiExpandedNativeTextureEpisodeHistory = updated
            replaceDirectWifiExpandedNativeTextureEpisodePathsLocked()
            directWifiExpandedNativeTextureMinimumPage = max(0, firstDisplayPage)
            nativeTexturePrewarmAnchorPage = -1
            nativeTexturePrewarmDirty = true
            requestResidentNativeTexturePrewarmLocked()
        }
    }

    /** Must be called with [stateLock] held. */
    private fun replaceDirectWifiExpandedNativeTextureEpisodePathsLocked() {
        directWifiExpandedNativeTextureEpisodePaths.clear()
        directWifiExpandedNativeTextureEpisodePaths.addAll(
            directWifiExpandedNativeTextureEpisodeHistory.authorizedPaths(),
        )
    }

    /**
     * Keeps vertical webtoon composition at or above the encoded source width instead of
     * manufacturing a display-width intermediate. SurfaceFlinger performs the final display
     * scale, so this removes redundant host-GPU pixels without changing page identity, order,
     * aspect ratio, or the decoded source asset.
     */
    fun setSourceNativeWebtoonCompositingEnabled(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(stateLock) {
            check(rollingNativeAttachEpoch == 0L) {
                "source-native compositing must be selected before native attachment"
            }
            sourceNativeWebtoonCompositingEnabled = enabled
            if (rollingNativeAttachEpoch == 0L) {
                rollingNativeFrozenViewportWidth = 0
                rollingNativeFrozenViewportHeight = 0
                rollingNativeFrozenTargetWidth = 0
                rollingNativeFrozenTargetHeight = 0
                captureNativeRenderTargetGeometryLocked(width, height)
            }
        }
    }

    /**
     * Stops this view from enqueueing invalidations while its same-root reader is staged. State
     * mutations are retained and the first unsuppressed request renders the latest state.
     */
    fun setFrameSchedulingSuppressed(suppressed: Boolean) {
        synchronized(stateLock) {
            if (frameSchedulingSuppressed == suppressed) return
            frameSchedulingSuppressed = suppressed
            if (suppressed) clearFramePipeLocked(preserveDirty = true)
            if (!suppressed && pages.isNotEmpty() && !shouldBlockInitialEmptyFrameLocked()) {
                renderRequested = true
                scheduleFrameLocked()
            }
            stateLock.notifyAll()
        }
    }

    /**
     * Makes the just-installed exact p0 visible without waiting an extra producer callback. The
     * pending callback and its immutable token already exist; the producer-thread runnable below
     * removes that callback and consumes the same token once, so this cannot manufacture a frame.
     */
    fun requestDirectWifiAdjacentExactP0ContentCatchup(owner: NtkAdjacentExactP0Owner): Boolean {
        val normalizedPath = NtkStripDigests.normalizeEpisodePath(owner.normalizedEpisodePath)
        if (normalizedPath.isEmpty()) return false
        synchronized(stateLock) {
            val authorizedEpisode = normalizedPath in directWifiExpandedNativeTextureEpisodePaths
            val hasAdmittedFrame =
                framePipe == FramePipe.INVALIDATION_POSTED && inFlightToken != 0L
            val nativeAttached = rollingNativeHandle != 0L && rollingNativeAttachEpoch > 0L &&
                !rollingNativeFatal
            if (!NtkAdjacentExactP0FrameCatchupPolicy.shouldPost(
                    emulatorRuntime = emulatorNativeSurfaceRuntime,
                    directWifiRunway = directWifiExpandedNativeTextureRunway,
                    authorizedEpisode = authorizedEpisode,
                    renderRunning = renderRunning,
                    directSurfaceReady = directSurfaceReady,
                    frameSchedulingSuppressed = frameSchedulingSuppressed,
                    callbackPosted = directFrameCallbackPosted,
                    contentCatchupPosted = directAdjacentExactP0CatchupPosted,
                    inputCatchupPosted = directLateInputCatchupPosted,
                    hasAdmittedFrame = hasAdmittedFrame,
                    nativeAttached = nativeAttached,
                    surfaceValid = rollingTextureSurface?.isValid == true,
                )
            ) return false
            val handler = directRenderHandler ?: return false
            directAdjacentExactP0CatchupPosted = true
            directAdjacentExactP0CatchupEpoch = inFlightEpoch
            directAdjacentExactP0CatchupToken = inFlightToken
            directAdjacentExactP0CatchupAttachEpoch = rollingNativeAttachEpoch
            directAdjacentExactP0CatchupOwner = owner
            if (!handler.postAtFrontOfQueue(directAdjacentExactP0Catchup)) {
                directAdjacentExactP0CatchupPosted = false
                directAdjacentExactP0CatchupEpoch = 0L
                directAdjacentExactP0CatchupToken = 0L
                directAdjacentExactP0CatchupAttachEpoch = 0L
                directAdjacentExactP0CatchupOwner = null
                return false
            }
            return true
        }
    }

    /** Must be enabled on the main thread before this view is attached to its reader root. */
    fun setSurfaceAttachmentDeferredUntilActualPixels(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(stateLock) {
            surfaceAttachmentDeferredUntilActualPixels = enabled
            surfaceRevealPosted = false
            nativeSurfaceRevealAfterFirstHwuiCommitPending = false
            nativeSurfaceRevealAfterPrepareEpoch = 0L
            deferredSurfaceIdentityActivated = !enabled
            directWifiForwardOnlyInitialResumeRevealQualified = false
            if (enabled) directWifiForwardOnlyTerminalTailRevealQualified = false
            // Revealing the native child ends attachment deferral, not the forward-only resume
            // lifecycle. Retain the restored tail identity until the renderer profile or page
            // table is retired so the first physical proof can still classify its natural
            // viewport shortfall and the following exact adjacent pixels.
            if (!enabled && !directWifiForwardOnlyInitialResumeEnabled) {
                directWifiForwardOnlyInitialResumePage = -1
                directWifiForwardOnlyInitialResumeOffset = 0
            }
            if (enabled) clearFramePipeLocked(preserveDirty = true)
        }
        // Keep the native child absent from composition during progressive HWUI display. This is
        // not a reveal gate for the parent reader: the first exact page can draw immediately.
        nativeSurfaceView.visibility =
            if (enabled || !rollingNativePresentationEnabled) View.GONE else View.VISIBLE
        nativeSurfaceView.alpha = 1f
    }

    /**
     * Activates progressive HWUI drawing only after the exact click-owned session exists. Empty
     * frames remain blocked, but the first visible exact page no longer waits for a full viewport.
     */
    fun activateDeferredSurfaceProducer() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val deferred = synchronized(stateLock) {
            if (!surfaceAttachmentDeferredUntilActualPixels) {
                false
            } else {
                deferredSurfaceIdentityActivated = true
                if (renderRunning && pages.isNotEmpty()) {
                    renderRequested = true
                    if (hasRevealableActualPixelsLocked()) {
                        postSurfaceRevealLocked()
                    }
                    stateLock.notifyAll()
                }
                true
            }
        }
        if (!deferred) return
        // Do not make the SurfaceView producer visible here. Repeated host-GPU sessions have
        // shown that cold BufferQueue/EGL attachment can occasionally stop the entire HWUI lane
        // for tens of seconds. Keep the real-image fallback as the only producer until its first
        // registered HWUI frame-commit proof has been delivered.
        nativeSurfaceView.alpha = 1f
        invalidate()
        (parent as? View)?.invalidate()
        postInvalidateOnAnimation()
        Log.d(TAG, "reader_deferred_surface_producer_armed")
    }

    fun setPageGapPx(gapPx: Int) {
        var request: WindowRequest? = null
        synchronized(stateLock) {
            val next = if (inlineRealPixelsOnly) 0 else max(0, gapPx)
            if (pageGapPx == next) return
            invalidatePreparedRenderSceneStateLocked()
            pageGapPx = next
            layoutDirty = true
            clampScrollLocked()
            renderRequested = pages.isNotEmpty()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            request = if (renderRequested) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
    }

    @JvmOverloads
    fun setPageCount(count: Int, deferInitialEmptyDraw: Boolean = false) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) {
                Log.e(TAG, "reader_strip_reject_legacy_page_count authority=$stripAuthorityToken count=$count")
                return@synchronized null
            }
            clearRetainedPageNodesStateLocked()
            val nextCount = max(0, count)
            if (pages.isNotEmpty() && nextCount == pages.size) {
                this.deferInitialEmptyDraw = deferInitialEmptyDraw
                // A generic rolling page table is also eligible for strict HWUI presentation
                // proof.  Unlike bindAuthoritativeStrip(), the legacy setPageCount path used to
                // leave its structure epoch at zero, so a real committed bitmap frame could never
                // pass ReaderV2Activity's identity/structure validation.
                if (traversalStructureEpoch <= 0L && nextCount > 0) {
                    resetTraversalProofLocked(nextCount)
                }
                layoutDirty = true
                renderRequested = !this.deferInitialEmptyDraw
                if (renderRequested) scheduleFrameLocked()
                stateLock.notifyAll()
                return@synchronized if (renderRequested) windowRequestLocked(lastBusy) else null
            }
            if (pages.isNotEmpty() && nextCount > 0) {
                rebuildLayoutLocked()
                val viewportAnchor = progressPositionLocked()
                val oldCount = pages.size
                this.deferInitialEmptyDraw = deferInitialEmptyDraw
                when {
                    nextCount > oldCount -> {
                        appendEmptyPagesLocked(nextCount - oldCount)
                        extendTraversalProofLocked(nextCount)
                    }
                    nextCount < oldCount -> {
                        repeat(oldCount - nextCount) {
                            pages.removeAt(pages.lastIndex)
                        }
                        if (lockedRestorePage >= nextCount) clearLockedRestorePositionLocked()
                        if (prependedRevealHoldPage >= nextCount) {
                            prependedRevealHoldPage = -1
                            clearPrependedReadyHoldLocked()
                        }
                        lastAnchor = lastAnchor.coerceIn(0, pages.lastIndex)
                        // Display indexes can now identify different content. Retire all earlier
                        // traversal claims instead of carrying them across the rebuilt suffix.
                        resetTraversalProofLocked(nextCount)
                    }
                }
                layoutDirty = true
                rebuildLayoutLocked()
                if (viewportAnchor != null && viewportAnchor.page in pages.indices) {
                    structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                    setScrollOffsetLocked(pageTopOrElseLocked(viewportAnchor.page, 0f) - viewportAnchor.offset)
                }
                clampScrollLocked()
                boundaryDispatchInFlight = false
                renderRequested = !this.deferInitialEmptyDraw
                if (renderRequested) scheduleFrameLocked()
                stateLock.notifyAll()
                Log.d(
                    TAG,
                    "reader_set_page_count_resize_preserve old=$oldCount new=$nextCount " +
                        "scroll=${scrollOffset.toInt()} defer=$deferInitialEmptyDraw"
                )
                return@synchronized if (renderRequested) windowRequestLocked(lastBusy) else null
            }
            scroller.forceFinished(true)
            activeScrollerOffsetShift = 0f
            clearInputStateLocked()
            lastBusy = false
            lastRequestedBusy = false
            pendingWindowRequest = null
            windowDispatchPosted = false
            pendingReverseWindowFirstPageHint = -1
            pendingBlockedForwardRequest = null
            blockedForwardDispatchPosted = false
            lastBlockedForwardPage = -1
            lastBlockedForwardRequestAtMs = 0L
            val preservedPages = pages.toList()
            pages.clear()
            prependedRevealHoldPage = -1
            clearPrependedReadyHoldLocked()
            repeat(nextCount) { index ->
                val preserved = preservedPages.getOrNull(index)
                pages.add(
                    preserved?.copy(
                        pendingTiles = preserved.pendingTiles.toList()
                    ) ?: newPageLocked()
                )
            }
            // setPageCount is the production page-table owner for the rolling ReaderSession path.
            // Initialize its non-zero identity before any bitmap can reach an HWUI commit.
            resetTraversalProofLocked(nextCount)
            if (preservedPages.isNotEmpty() && scrollOffset > height) {
                Log.w(
                    TAG,
                    "reader_set_page_count_reset_scroll old=${preservedPages.size} new=$nextCount " +
                        "from=${scrollOffset.toInt()} defer=$deferInitialEmptyDraw"
                )
            }
            setScrollOffsetLocked(0f)
            clearLockedRestorePositionLocked()
            directWifiForwardOnlyInitialResumePage = -1
            directWifiForwardOnlyInitialResumeOffset = 0
            directWifiForwardOnlyInitialResumeRevealQualified = false
            directWifiForwardOnlyTerminalTailRevealQualified = false
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            lastAnchor = -1
            lastNearStart = false
            lastNearEnd = false
            hasDrawnContentFrame = false
            this.deferInitialEmptyDraw = deferInitialEmptyDraw
            initialViewportHoldUntilMs = 0L
            structuralScrollAdjustUntilMs = 0L
            lastVisibleCoverageSnapshot = null
            layoutDirty = true
            renderRequested = !this.deferInitialEmptyDraw
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    fun appendPageCount(count: Int, revealAppendedBoundary: Boolean = false) {
        val request = synchronized(stateLock) {
            if (count <= pages.size) return
            invalidatePreparedRenderSceneStateLocked()
            rebuildLayoutLocked()
            val oldMaxScroll = max(0f, contentHeight - height).toInt()
            val shouldExtendActiveFling = !scroller.isFinished &&
                boundaryArmedDirection == DIRECTION_NEXT &&
                scroller.finalY >= oldMaxScroll - BOUNDARY_FLING_EXTEND_EPSILON_PX
            val firstAppendedPage = pages.size
            appendEmptyPagesLocked(count - pages.size)
            extendTraversalProofLocked(count)
            val newMaxScroll = max(0f, contentHeight - height).toInt()
            if (revealAppendedBoundary && newMaxScroll > oldMaxScroll) {
                lockedRestorePage = firstAppendedPage
                lockedRestoreOffset = 0
                lockedRestoreUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                applyLockedRestorePositionLocked()
                if (!scroller.isFinished) scroller.forceFinished(true)
            } else if (shouldExtendActiveFling && newMaxScroll > oldMaxScroll) {
                val velocity = scroller.currVelocity
                    .coerceAtLeast(minVelocity.toFloat() * BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER)
                    .coerceAtMost(maxVelocity.toFloat())
                    .toInt()
                scroller.fling(0, scrollOffset.toInt(), 0, velocity, 0, 0, 0, newMaxScroll)
            }
            boundaryDispatchInFlight = false
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun finishBoundaryDispatch() {
        synchronized(stateLock) {
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            nextBoundaryAppendInFlight = false
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            stateLock.notifyAll()
        }
    }

    fun beginNextBoundaryAppendHold(anchorPage: Int) {
        synchronized(stateLock) {
            if (pages.isEmpty()) return
            armNextBoundaryGeometryHoldLocked(anchorPage)
            stateLock.notifyAll()
        }
    }

    fun prependPageCount(
        count: Int,
        insertedCount: Int,
        revealPrependedBoundary: Boolean = false,
        holdUntilReadyCount: Int = 0
    ) {
        val request = synchronized(stateLock) {
            if (insertedCount <= 0 || count <= pages.size) return
            clearRetainedPageNodesStateLocked()
            rebuildLayoutLocked()
            materializeLayoutDeltasLocked()
            val oldFirstTop = pageTopOrElseLocked(0, 0f)
            val insertedPlaceholderRatio = representativeResolvedPageRatioLocked()
            repeat(insertedCount) { pages.add(0, newPageLocked(insertedPlaceholderRatio)) }
            if (revealPrependedBoundary) {
                pages.getOrNull(insertedCount - 1)?.let { page ->
                    val cardWidth = max(1, width)
                    val cardHeight = TRANSITION_CARD_PAGE_HEIGHT_PX.roundToInt()
                    page.bitmap = null
                    page.cardBitmap = createTransitionCardBitmap(cardWidth, "")
                    page.tiles = emptyList()
                    page.originalProof = null
                    page.width = cardWidth
                    page.height = cardHeight
                    page.loading = false
                    page.cardText = ""
                    page.errorText = null
                    clearPendingResolveLocked(page)
                }
            }
            if (lockedRestorePage >= 0) lockedRestorePage += insertedCount
            if (revealPrependedBoundary) {
                clearPrependedReadyHoldLocked()
                prependedRevealHoldPage = -1
            } else if (holdUntilReadyCount > 0) {
                prependedReadyHoldInserted = insertedCount
                prependedReadyHoldRequired = holdUntilReadyCount
                prependedRevealHoldPage = insertedCount.coerceIn(0, pages.lastIndex)
            }
            layoutDirty = true
            rebuildLayoutLocked()
            val shiftedFirstTop = pageTopOrElseLocked(insertedCount, 0f)
            if (lockedRestorePage >= 0 && SystemClock.uptimeMillis() <= lockedRestoreUntilMs) {
                applyLockedRestorePositionLocked()
            } else if (revealPrependedBoundary) {
                val boundaryCardTop = pageTopOrElseLocked(insertedCount - 1, shiftedFirstTop)
                structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                setScrollOffsetLocked(max(0f, boundaryCardTop))
            } else {
                structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                setScrollOffsetLocked(scrollOffset + shiftedFirstTop - oldFirstTop)
            }
            if (!scroller.isFinished) scroller.forceFinished(true)
            activeScrollerOffsetShift = 0f
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            clampScrollLocked()
            // Every pre-existing display index now names different content. Advance the
            // structural epoch after all index remapping, but before a frame or prewarm request
            // can reuse an old (epoch,index) texture/proof.
            resetTraversalProofLocked(pages.size)
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun removePageRange(startIndex: Int, removedCount: Int, immediate: Boolean = false) {
        if (removedCount <= 0) return
        val quietDelayMs = synchronized(stateLock) {
            val now = SystemClock.uptimeMillis()
            if (!immediate && lastScrollInteractionMs > 0L) {
                (REMOVE_PAGE_RANGE_SCROLL_QUIET_MS - (now - lastScrollInteractionMs)).coerceAtLeast(0L)
            } else {
                0L
            }
        }
        if (quietDelayMs > 0L) {
            mainHandler.postDelayed({ removePageRange(startIndex, removedCount, immediate) }, quietDelayMs)
            Log.d(
                TAG,
                "reader_remove_range_defer_scroll_quiet start=$startIndex removed=$removedCount delayMs=$quietDelayMs"
            )
            return
        }
        val result = synchronized(stateLock) {
            if (startIndex !in pages.indices) return
            val endExclusive = min(pages.size, startIndex + removedCount)
            if (endExclusive <= startIndex) return
            clearRetainedPageNodesStateLocked()
            val wasAtEnd = height > 0 &&
                scrollOffset >= maxScrollLocked() - BOUNDARY_EPSILON_PX
            rebuildLayoutLocked()
            val viewportAnchor = progressPositionLocked()
            pages.subList(startIndex, endExclusive).clear()
            val removed = endExclusive - startIndex
            directWifiExpandedNativeTextureMinimumPage =
                NtkExpandedNativeTextureHistoryPolicy.remapFloorAfterRemoval(
                    directWifiExpandedNativeTextureMinimumPage,
                    startIndex,
                    removed,
                    pages.size,
                )
            val remappedResumePage =
                NtkExpandedNativeTextureHistoryPolicy.remapExactPageAfterRemoval(
                    directWifiForwardOnlyInitialResumePage,
                    startIndex,
                    removed,
                    pages.size,
                )
            if (directWifiForwardOnlyInitialResumePage >= 0 && remappedResumePage < 0) {
                directWifiForwardOnlyInitialResumeOffset = 0
                directWifiForwardOnlyInitialResumeRevealQualified = false
                directWifiForwardOnlyTerminalTailRevealQualified = false
            }
            directWifiForwardOnlyInitialResumePage = remappedResumePage
            nativeTexturePrewarmAnchorPage = -1
            nativeTexturePrewarmDirty = true
            pageTopDeltas.clear()
            layoutDirty = true
            rebuildLayoutLocked()
            if (pages.isEmpty()) {
                setScrollOffsetLocked(0f)
                lastAnchor = -1
                prependedRevealHoldPage = -1
                clearPrependedReadyHoldLocked()
            } else {
                if (prependedRevealHoldPage >= endExclusive) {
                    prependedRevealHoldPage -= endExclusive - startIndex
                } else if (prependedRevealHoldPage >= startIndex) {
                    prependedRevealHoldPage = -1
                }
                if (lockedRestorePage >= endExclusive) {
                    lockedRestorePage -= endExclusive - startIndex
                } else if (lockedRestorePage >= startIndex) {
                    lockedRestorePage = -1
                    lockedRestoreOffset = 0
                }
                lastAnchor = lastAnchor.coerceIn(0, pages.lastIndex)
                val adjustedAnchor = when {
                    viewportAnchor == null -> null
                    viewportAnchor.page >= endExclusive -> {
                        viewportAnchor.copy(page = viewportAnchor.page - (endExclusive - startIndex))
                    }
                    viewportAnchor.page >= startIndex -> {
                        viewportAnchor.copy(page = startIndex.coerceAtMost(pages.lastIndex), offset = 0)
                    }
                    else -> viewportAnchor
                }
                structuralScrollAdjustUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
                if (adjustedAnchor != null && adjustedAnchor.page in pages.indices) {
                    val before = scrollOffset
                    setScrollOffsetLocked(pageTopOrElseLocked(adjustedAnchor.page, 0f) - adjustedAnchor.offset)
                    Log.d(
                        TAG,
                        "reader_remove_anchor_restore start=$startIndex removed=${endExclusive - startIndex} " +
                            "anchor=${viewportAnchor?.page}:${viewportAnchor?.offset} " +
                            "adjusted=${adjustedAnchor.page}:${adjustedAnchor.offset} " +
                            "from=${before.toInt()} to=${scrollOffset.toInt()}"
                    )
                }
                clampScrollLocked()
            }
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            // Prefix/range removal renumbers every following display page. Retire queued native
            // uploads and completed-draw proofs after all index remapping, before those numeric
            // keys can bind new content.
            resetTraversalProofLocked(pages.size)
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            val window = windowRequestLocked(lastBusy)
            val boundary = if (
                wasAtEnd &&
                pages.isNotEmpty() &&
                height > 0 &&
                SystemClock.uptimeMillis() - lastScrollInteractionMs <= BOUNDARY_REMOVE_RECENT_SCROLL_MS &&
                scrollOffset >= maxScrollLocked() - BOUNDARY_EPSILON_PX
            ) {
                boundaryArmedDirection = DIRECTION_NEXT
                boundaryRequestLocked()
            } else {
                null
            }
            window to boundary
        }
        dispatchWindowRequest(result.first)
        dispatchBoundaryRequest(result.second)
    }

    fun setPageLoading(index: Int) {
        synchronized(stateLock) {
            pages.getOrNull(index)?.let {
                if (it.cardText == null && it.bitmap == null && it.tiles.isEmpty()) {
                    if (inlineRealPixelsOnly) {
                        // Loading metadata must not become a visual state in the inline surface.
                        it.loading = false
                        it.errorText = null
                        return@let
                    }
                    val changesVisualState = !it.loading || it.errorText != null ||
                        it.pendingResolveType != PENDING_NONE
                    if (changesVisualState) invalidatePreparedRenderSceneStateLocked()
                    it.errorText = null
                    it.loading = true
                    clearPendingResolveLocked(it)
                }
            }
            if (shouldSuppressInitialEmptyRenderLocked() || shouldDeferInitialEmptyDrawLocked()) return
            if (shouldRenderPageResolveNowLocked(index, 1)) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }
    }

    private fun hasSameBitmapIdentity(page: Page, bitmap: Bitmap): Boolean {
        return page.bitmap === bitmap && page.tiles.isEmpty()
    }

    private fun hasSameTilesIdentity(page: Page, tiles: List<ReaderTile>): Boolean {
        return page.bitmap == null && sameTileIdentity(page.tiles, tiles)
    }

    private fun isSettledBitmapDeliveryNoOpLocked(
        page: Page,
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        return hasSameBitmapIdentity(page, bitmap) &&
            page.width == targetWidth &&
            page.height == targetHeight &&
            page.pendingResolveType == PENDING_NONE &&
            !page.loading &&
            page.cardText == null &&
            page.errorText == null &&
            !deferInitialEmptyDraw
    }

    private fun isSettledTilesDeliveryNoOpLocked(
        page: Page,
        tiles: List<ReaderTile>,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        return hasSameTilesIdentity(page, tiles) &&
            page.width == targetWidth &&
            page.height == targetHeight &&
            page.pendingResolveType == PENDING_NONE &&
            !page.loading &&
            page.cardText == null &&
            page.errorText == null &&
            !deferInitialEmptyDraw
    }

    private fun isProofBitmapDeliveryNoOpLocked(
        page: Page,
        bitmap: Bitmap,
        proofWidth: Int,
        proofHeight: Int,
        pendingWidth: Int,
        pendingHeight: Int
    ): Boolean {
        return hasSameBitmapIdentity(page, bitmap) &&
            page.width == proofWidth &&
            page.height == proofHeight &&
            page.pendingResolveType == PENDING_SIZE &&
            page.pendingBitmap == null &&
            page.pendingTiles.isEmpty() &&
            page.pendingWidth == pendingWidth &&
            page.pendingHeight == pendingHeight &&
            !page.loading &&
            page.cardText == null &&
            page.errorText == null &&
            !deferInitialEmptyDraw
    }

    private fun isProofTilesDeliveryNoOpLocked(
        page: Page,
        tiles: List<ReaderTile>,
        proofWidth: Int,
        proofHeight: Int,
        pendingWidth: Int,
        pendingHeight: Int
    ): Boolean {
        return hasSameTilesIdentity(page, tiles) &&
            page.width == proofWidth &&
            page.height == proofHeight &&
            page.pendingResolveType == PENDING_SIZE &&
            page.pendingBitmap == null &&
            page.pendingTiles.isEmpty() &&
            page.pendingWidth == pendingWidth &&
            page.pendingHeight == pendingHeight &&
            !page.loading &&
            page.cardText == null &&
            page.errorText == null &&
            !deferInitialEmptyDraw
    }

    private fun sameTileIdentity(first: List<ReaderTile>, second: List<ReaderTile>): Boolean {
        if (first.size != second.size) return false
        for (index in first.indices) {
            val a = first[index]
            val b = second[index]
            if (a.bitmap !== b.bitmap ||
                a.sourceTop != b.sourceTop ||
                a.sourceBottom != b.sourceBottom ||
                a.sourceWidth != b.sourceWidth ||
                a.sourceHeight != b.sourceHeight
            ) {
                return false
            }
        }
        return true
    }

    private fun invalidateRetainedPageNodeIfBitmapChanged(index: Int, page: Page, bitmap: Bitmap) {
        if (!hasSameBitmapIdentity(page, bitmap)) {
            page.originalProof = null
            invalidateRetainedPageNodeStateLocked(index)
        } else {
            // Geometry or pending-state changes can make a prepared scene stale even when the
            // producer redelivers the same bitmap identity.
            invalidatePreparedRenderSceneStateLocked()
        }
        // Delivery can reach this method after a proof path has already installed the same JVM
        // identity. Native texture residency is independent from retained-node identity, so every
        // accepted delivery gets one deduplicated prewarm opportunity.
        postNativeBitmapTexturePrewarmLocked(index, bitmap)
    }

    private fun invalidateRetainedPageNodeIfTilesChanged(
        index: Int,
        page: Page,
        tiles: List<ReaderTile>
    ) {
        if (!hasSameTilesIdentity(page, tiles)) {
            page.originalProof = null
            invalidateRetainedPageNodeStateLocked(index)
        } else {
            invalidatePreparedRenderSceneStateLocked()
        }
        postNativeTexturePrewarmLocked(index, tiles)
    }

    /**
     * Sends only already-decoded pixels to the native renderer's non-presenting texture queue.
     * This method is called under [stateLock] after strict source admission has completed. It
     * cannot request, decode, attach a Surface, or submit a frame.
     */
    private fun postNativeTexturePrewarmLocked(pageIndex: Int, tiles: List<ReaderTile>) {
        if (tiles.isEmpty()) return
        if (tiles.none { !it.bitmap.isRecycled }) return
        if (pageIndex in pages.indices) nativeTexturePrewarmPendingPages.add(pageIndex)
        requestResidentNativeTexturePrewarmLocked()
    }

    private fun postNativeBitmapTexturePrewarmLocked(pageIndex: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        if (pageIndex in pages.indices) nativeTexturePrewarmPendingPages.add(pageIndex)
        requestResidentNativeTexturePrewarmLocked()
    }

    /**
     * Strict strips can install one tile at a time, so filtering a sparse slot list would renumber
     * later slots and poison the native cache key. Preserve the authority-validated strip key
     * exactly as it will appear in a physical frame.
     */
    private fun postNativeStripTexturePrewarmLocked(
        key: NtkStripTileKey,
        tile: ReaderTile
    ) {
        val bitmap = tile.bitmap
        if (bitmap.isRecycled || tile.sourceWidth <= 0 ||
            tile.sourceBottom <= tile.sourceTop || tile.sourceHeight < tile.sourceBottom
        ) return
        if (key.pageIndex in pages.indices) nativeTexturePrewarmPendingPages.add(key.pageIndex)
        requestResidentNativeTexturePrewarmLocked()
    }

    private data class NativeTexturePrewarmSnapshot(
        val handle: Long,
        val structureEpoch: Long,
        val lifecycleEpoch: Long,
        val creationGeneration: Long,
        val firstPage: Int,
        val lastPage: Int,
        val requestedPages: IntArray,
        val tileData: IntArray,
        val bitmaps: Array<Bitmap>
    )

    /**
     * Verifies that every authoritative original is installed, then queues only the current
     * forward runway for native upload. The complete decoded scene remains owned by the View, so
     * a later viewport can upload any page without network or decode work. Uploading the entire
     * episode here made 100+ page works contend with physical scroll frames and retained hundreds
     * of MiB of GPU textures even though only a few pages can be visible.
     */
    fun queueAllAuthoritativeOriginalTextures(
        canonicalPageCount: Int,
        onQueued: Runnable
    ): Boolean {
        val hwuiBitmaps = synchronized(stateLock) {
            if (canonicalPageCount <= 0 || pages.size < canonicalPageCount ||
                !renderRunning || traversalStructureEpoch <= 0L ||
                pages.take(canonicalPageCount).any { page ->
                    !usableAuthoritativeOriginalTilePage(
                        page.width,
                        page.height,
                        page.tiles,
                        page.originalProof
                    )
                }
            ) {
                null
            } else if (rollingNativeHandle == 0L) {
                rebuildLayoutLocked()
                collectUnpreparedHwuiRunwayLocked(HWUI_FORWARD_PREPARE_VIEWPORTS)
            } else {
                // A non-zero native handle is not an empty HWUI fallback. Returning an empty
                // non-null list here made the branch below publish all-images-ready immediately
                // and made the real native full-scene path permanently unreachable.
                null
            }
        }
        if (hwuiBitmaps != null) {
            // All encoded bodies were already fetched and decoded by the click-owned session.
            // Keep GPU preparation bounded to the current forward runway; asking RenderThread to
            // upload a 500 MiB episode at once competes with the first real scroll frames.
            for (bitmap in hwuiBitmaps) {
                if (!bitmap.isRecycled && !bitmap.isHardwareConfigCompat()) {
                    runCatching { bitmap.prepareToDraw() }
                }
            }
            // HWUI records these immutable software bitmaps directly from this View. There is no
            // separate native ownership queue to wait for: successful exact installation above is
            // the renderer-ready boundary and prepareToDraw() is only a bounded upload hint. Run
            // the callback in this same main-loop turn so a concurrent fling cannot postpone the
            // readiness timestamp (or make an already-prepared runway retry forever).
            onQueued.run()
            return true
        }
        val requested = synchronized(stateLock) {
            if ((!inlineRealPixelsOnly && !forwardNativeTexturePrewarmEnabled) ||
                !renderRunning || rollingNativeFatal || rollingNativeHandle == 0L ||
                traversalStructureEpoch <= 0L || canonicalPageCount <= 0 ||
                pages.size < canonicalPageCount ||
                pages.take(canonicalPageCount).any { page ->
                    !usableAuthoritativeOriginalTilePage(
                        page.width,
                        page.height,
                        page.tiles,
                        page.originalProof
                    )
                }
            ) {
                return@synchronized false
            }
            nativeTexturePrewarmDirty = true
            requestResidentNativeTexturePrewarmLocked()
            true
        }
        if (requested) {
            // Queue the current runway in this turn so completion cannot be starved by a fling.
            // "All images ready" is qualified by the exhaustive authoritative-page validation
            // above; it never requires every offscreen page to occupy GPU memory simultaneously.
            flushResidentNativeTexturePrewarm()
            onQueued.run()
        } else {
            val now = SystemClock.uptimeMillis()
            val rejection = synchronized(stateLock) {
                if (now - lastNativeTextureRunwayRejectLogMs < 1_000L) {
                    null
                } else {
                    lastNativeTextureRunwayRejectLogMs = now
                    val invalidPage = pages.take(canonicalPageCount.coerceAtLeast(0))
                        .indexOfFirst { page ->
                        !usableAuthoritativeOriginalTilePage(
                            page.width,
                            page.height,
                            page.tiles,
                            page.originalProof
                        )
                    }
                    val invalid = pages.getOrNull(invalidPage)
                    val recycledTiles = invalid?.tiles?.count { it.bitmap.isRecycled } ?: -1
                    "reader_native_texture_runway_rejected renderRunning=$renderRunning," +
                        "fatal=$rollingNativeFatal,handle=${rollingNativeHandle != 0L}," +
                        "epoch=$traversalStructureEpoch,pages=${pages.size},invalidPage=$invalidPage," +
                        "invalidSize=${invalid?.width ?: -1}x${invalid?.height ?: -1}," +
                        "invalidTiles=${invalid?.tiles?.size ?: -1},recycledTiles=$recycledTiles," +
                        "proof=${invalid?.originalProof != null},loading=${invalid?.loading}," +
                        "inline=$inlineRealPixelsOnly,forwardPrewarm=$forwardNativeTexturePrewarmEnabled"
                }
            }
            if (rejection != null) Log.d(TAG, rejection)
        }
        return requested
    }

    /**
     * Queues only the currently visible exact runway for an oversized scene whose far historical
     * originals may already have been evicted. Every canonical page has crossed authoritative
     * installation before this is called; unlike [queueAllAuthoritativeOriginalTextures], current
     * simultaneous residency is intentionally not an invariant.
     */
    fun queueResidentAuthoritativeTextureRunway(
        minimumAuthoritativePage: Int,
        onQueued: Runnable,
    ): Boolean {
        val hwuiBitmaps = synchronized(stateLock) {
            if (!renderRunning || traversalStructureEpoch <= 0L || pages.isEmpty()) return false
            rebuildLayoutLocked()
            val first = if (width > 0 && height > 0) {
                firstVisiblePageLocked(scrollOffset).coerceIn(0, pages.lastIndex)
            } else {
                0
            }
            val last = if (width > 0 && height > 0) {
                val probe = min(
                    max(0f, contentHeight - 1f),
                    scrollOffset + max(1, height) - 1f,
                )
                firstVisiblePageLocked(probe).coerceIn(first, pages.lastIndex)
            } else {
                first
            }
            // A restored viewport can expose a few pixels of the preceding page above the saved
            // anchor. Forward-resume deliberately owns only [minimumAuthoritativePage, tail]; an
            // unowned historical placeholder must not keep the renderer-ready callback retrying
            // forever. Still require every visible page at or after the saved anchor.
            val requiredFirst = max(first, minimumAuthoritativePage.coerceIn(0, pages.lastIndex))
            val requiredLast = max(requiredFirst, last)
            if ((requiredFirst..requiredLast).any { index ->
                    val page = pages[index]
                    !usableAuthoritativeOriginalTilePage(
                        page.width,
                        page.height,
                        page.tiles,
                        page.originalProof,
                    )
                }
            ) return false
            if (rollingNativeHandle == 0L) {
                collectUnpreparedHwuiRunwayLocked(HWUI_FORWARD_PREPARE_VIEWPORTS)
            } else {
                nativeTexturePrewarmDirty = true
                requestResidentNativeTexturePrewarmLocked()
                null
            }
        }
        if (hwuiBitmaps != null) {
            for (bitmap in hwuiBitmaps) {
                if (!bitmap.isRecycled && !bitmap.isHardwareConfigCompat()) {
                    runCatching { bitmap.prepareToDraw() }
                }
            }
        } else {
            flushResidentNativeTexturePrewarm()
        }
        onQueued.run()
        return true
    }

    /**
     * Coalesces decoded-pixel deliveries into a viewport-sized forward runway. Offscreen decoded
     * pages remain immediately available from the immutable View scene but are not allowed to
     * monopolize the renderer queue or GPU residency.
     */
    private fun requestResidentNativeTexturePrewarmLocked() {
        nativeTexturePrewarmDirty = true
        if ((!inlineRealPixelsOnly && !forwardNativeTexturePrewarmEnabled) ||
            !renderRunning || rollingNativeFatal ||
            rollingNativeHandle == 0L || traversalStructureEpoch <= 0L || pages.isEmpty() ||
            nativeTexturePrewarmFlushPosted
        ) return
        val handler = directRenderHandler ?: return
        nativeTexturePrewarmFlushPosted = true
        if (!handler.post(::flushResidentNativeTexturePrewarm)) {
            nativeTexturePrewarmFlushPosted = false
        }
    }

    private fun effectiveNativeTexturePrewarmAnchorLocked(offset: Float): Int {
        if (pages.isEmpty()) return 0
        val visible = if (width > 0 && height > 0) {
            firstVisiblePageLocked(offset).coerceIn(0, pages.lastIndex)
        } else {
            0
        }
        val activeReverse = NtkExpandedNativeTextureHistoryPolicy
            .shouldBypassResumeFloorForReverse(
                activeInputDirection,
                pointerDown,
                dragging,
                scrollbarDragging,
                scroller.isFinished,
            )
        return if (directWifiExpandedNativeTextureRunway &&
            directWifiExpandedNativeTextureEpisodePaths.isNotEmpty() && !activeReverse
        ) {
            max(visible, directWifiExpandedNativeTextureMinimumPage)
                .coerceIn(0, pages.lastIndex)
        } else {
            visible
        }
    }

    private fun flushResidentNativeTexturePrewarm() {
        val snapshot = synchronized(stateLock) {
            nativeTexturePrewarmFlushPosted = false
            if (!nativeTexturePrewarmDirty ||
                (!inlineRealPixelsOnly && !forwardNativeTexturePrewarmEnabled) || !renderRunning ||
                rollingNativeFatal || rollingNativeHandle == 0L ||
                traversalStructureEpoch <= 0L || pages.isEmpty()
            ) return@synchronized null

            rebuildLayoutLocked()
            val visibleFirst = effectiveNativeTexturePrewarmAnchorLocked(scrollOffset)
            val expandedDirectWifiRunway = directWifiExpandedNativeTextureRunway &&
                directWifiExpandedNativeTextureEpisodePaths.isNotEmpty()
            val shortWebtoonPixelWindow = expandedDirectWifiRunway &&
                directWifiShortWebtoonPixelWindowPrewarm && width > 0 && height > 0
            val first = visibleFirst
            val directWifiAheadPages = if (expandedDirectWifiRunway &&
                emulatorNativeSurfaceRuntime
            ) {
                HOST_GPU_DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES
            } else {
                DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES
            }
            val runwayEnd = if (shortWebtoonPixelWindow) {
                val endExclusive = min(
                    contentHeight,
                    scrollOffset + height.toFloat() *
                        (1f + DIRECT_WIFI_SHORT_WEBTOON_PREWARM_AHEAD_VIEWPORTS)
                )
                val probe = min(
                    max(0f, contentHeight - FORWARD_REQUEST_END_EPSILON_PX),
                    max(0f, endExclusive - FORWARD_REQUEST_END_EPSILON_PX)
                )
                firstVisiblePageLocked(probe).coerceIn(first, pages.lastIndex)
            } else if (expandedDirectWifiRunway) {
                min(pages.lastIndex, first + directWifiAheadPages - 1)
            } else if (width > 0 && height > 0) {
                val endExclusive = min(
                    contentHeight,
                    scrollOffset + height.toFloat() *
                        (1f + NATIVE_PREWARM_AHEAD_VIEWPORTS)
                )
                val probe = min(
                    max(0f, contentHeight - FORWARD_REQUEST_END_EPSILON_PX),
                    max(0f, endExclusive - FORWARD_REQUEST_END_EPSILON_PX)
                )
                firstVisiblePageLocked(probe).coerceIn(first, pages.lastIndex)
            } else {
                min(pages.lastIndex, first + NATIVE_PREWARM_FALLBACK_AHEAD_PAGES)
            }
            val requestedPages = if (expandedDirectWifiRunway) {
                buildList {
                    for (pageIndex in first..runwayEnd) {
                        val page = pages[pageIndex]
                        if (page.errorText != null) break
                        if (page.cardText != null) {
                            val nextPage = pages.getOrNull(pageIndex + 1)
                            if (NtkExpandedNativeTextureTransitionPolicy.mayCrossCard(
                                    cardText = page.cardText,
                                    errorText = page.errorText,
                                    nextCardText = nextPage?.cardText,
                                    nextErrorText = nextPage?.errorText,
                                    nextEpisodePath = nextPage?.committedIdentity
                                        ?.normalizedEpisodePath,
                                    authorizedEpisodePaths =
                                        directWifiExpandedNativeTextureEpisodePaths,
                                )
                            ) {
                                continue
                            }
                            break
                        }
                        val identity = page.committedIdentity ?: break
                        if (
                            identity.normalizedEpisodePath !in
                            directWifiExpandedNativeTextureEpisodePaths
                        ) break
                        add(pageIndex)
                    }
                }
            } else {
                (first..runwayEnd).toList()
            }
            if (requestedPages.isEmpty()) return@synchronized null
            val maxTiles = if (expandedDirectWifiRunway) {
                DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES
            } else {
                NATIVE_PREWARM_MAX_TILES
            }
            val maxBytes = if (expandedDirectWifiRunway) {
                DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES
            } else {
                Long.MAX_VALUE
            }
            val tileIntegers = ArrayList<Int>(maxTiles * 7)
            val bitmapList = ArrayList<Bitmap>(maxTiles)
            val selectedPages = ArrayList<Int>(requestedPages.size)
            var selectedBytes = 0L

            fun validatedTextureBytes(tile: ReaderTile): Long? {
                val bitmap = tile.bitmap
                if (bitmap.isRecycled ||
                    tile.sourceWidth <= 0 || tile.sourceBottom <= tile.sourceTop ||
                    tile.sourceHeight < tile.sourceBottom
                ) return null
                val textureBytes = tile.sourceWidth.toLong() *
                    (tile.sourceBottom - tile.sourceTop).toLong() * 4L
                return textureBytes.takeIf { it > 0L }
            }

            fun appendTile(pageIndex: Int, slotIndex: Int, tile: ReaderTile, bytes: Long) {
                tileIntegers += pageIndex
                tileIntegers += slotIndex
                tileIntegers += tile.sourceTop
                tileIntegers += tile.sourceBottom
                tileIntegers += tile.sourceWidth
                tileIntegers += tile.sourceHeight
                tileIntegers += System.identityHashCode(tile.bitmap)
                bitmapList += tile.bitmap
                selectedBytes += bytes
            }

            if (shortWebtoonPixelWindow) {
                val pixelWindowTop = scrollOffset
                val pixelWindowBottom = min(
                    contentHeight,
                    scrollOffset + height.toFloat() *
                        (1f + DIRECT_WIFI_SHORT_WEBTOON_PREWARM_AHEAD_VIEWPORTS)
                )
                fun appendPixelWindowTile(
                    pageIndex: Int,
                    slotIndex: Int,
                    tile: ReaderTile,
                ): Boolean {
                    if (bitmapList.size >= maxTiles) return false
                    val bytes = validatedTextureBytes(tile) ?: return true
                    val pageTop = pageTopOrElseLocked(pageIndex, Float.MAX_VALUE)
                    val pageHeight = pageDrawHeightLocked(pages[pageIndex])
                    val sourceHeight = tile.sourceHeight.coerceAtLeast(1).toFloat()
                    val tileTop = pageTop + pageHeight * (tile.sourceTop / sourceHeight)
                    val tileBottom = pageTop + pageHeight * (tile.sourceBottom / sourceHeight)
                    if (tileBottom <= pixelWindowTop || tileTop >= pixelWindowBottom) return true
                    if (bytes > maxBytes - selectedBytes) return false
                    appendTile(pageIndex, slotIndex, tile, bytes)
                    if (selectedPages.lastOrNull() != pageIndex) selectedPages += pageIndex
                    return true
                }
                pixelPages@ for (pageIndex in requestedPages) {
                    if (bitmapList.size >= maxTiles) break
                    val page = pages[pageIndex]
                    val bitmap = page.bitmap
                    when {
                        bitmap != null && !bitmap.isRecycled -> {
                            if (!appendPixelWindowTile(
                                    pageIndex,
                                    0,
                                    ReaderTile(0, bitmap.height, bitmap.width, bitmap.height, bitmap),
                                )
                            ) break@pixelPages
                        }
                        page.stripSlots.isNotEmpty() -> {
                            for ((slot, tile) in page.stripSlots.withIndex()) {
                                if (tile != null &&
                                    !appendPixelWindowTile(pageIndex, slot, tile)
                                ) break@pixelPages
                            }
                        }
                        page.tiles.isNotEmpty() -> {
                            for ((slot, tile) in page.tiles.withIndex()) {
                                if (!appendPixelWindowTile(pageIndex, slot, tile)) {
                                    break@pixelPages
                                }
                            }
                        }
                    }
                }
            } else if (expandedDirectWifiRunway) {
                for (pageIndex in requestedPages) {
                    if (bitmapList.size >= maxTiles) break
                    val page = pages[pageIndex]
                    val bitmap = page.bitmap
                    val pageTiles = ArrayList<Pair<Int, ReaderTile>>()
                    when {
                        bitmap != null && !bitmap.isRecycled -> pageTiles += 0 to ReaderTile(
                            0,
                            bitmap.height,
                            bitmap.width,
                            bitmap.height,
                            bitmap,
                        )
                        page.stripSlots.isNotEmpty() -> {
                            if (page.stripSlots.any { it == null }) break
                            page.stripSlots.forEachIndexed { slot, tile ->
                                if (tile != null) pageTiles += slot to tile
                            }
                        }
                        page.tiles.isNotEmpty() -> page.tiles.forEachIndexed { slot, tile ->
                            pageTiles += slot to tile
                        }
                    }
                    if (pageTiles.isEmpty()) break
                    val pageByteSizes = pageTiles.map { (_, tile) ->
                        validatedTextureBytes(tile)
                    }
                    if (pageByteSizes.any { it == null }) break
                    val pageBytes = pageByteSizes.sumOf { checkNotNull(it) }
                    if (bitmapList.size + pageTiles.size > maxTiles ||
                        pageBytes > maxBytes - selectedBytes
                    ) break
                    pageTiles.zip(pageByteSizes).forEach { (indexedTile, bytes) ->
                        appendTile(
                            pageIndex,
                            indexedTile.first,
                            indexedTile.second,
                            checkNotNull(bytes),
                        )
                    }
                    selectedPages += pageIndex
                }
            } else {
                // Preserve the ordinary mobile/SNI resident policy byte-for-byte in behavior:
                // consume the first twelve valid tiles even when that ends part-way through one
                // unusually tall page. Page-atomic and byte-budget rules belong only to the
                // source-qualified direct-Wi-Fi profile above.
                fun appendOrdinaryTile(pageIndex: Int, slotIndex: Int, tile: ReaderTile) {
                    if (bitmapList.size >= maxTiles) return
                    val bytes = validatedTextureBytes(tile) ?: return
                    appendTile(pageIndex, slotIndex, tile, bytes)
                }
                for (pageIndex in requestedPages) {
                    if (bitmapList.size >= maxTiles) break
                    val page = pages[pageIndex]
                    val bitmap = page.bitmap
                    when {
                        bitmap != null && !bitmap.isRecycled -> appendOrdinaryTile(
                            pageIndex,
                            0,
                            ReaderTile(0, bitmap.height, bitmap.width, bitmap.height, bitmap),
                        )
                        page.stripSlots.isNotEmpty() ->
                            page.stripSlots.forEachIndexed { slot, tile ->
                                if (tile != null) appendOrdinaryTile(pageIndex, slot, tile)
                            }
                        page.tiles.isNotEmpty() -> page.tiles.forEachIndexed { slot, tile ->
                            appendOrdinaryTile(pageIndex, slot, tile)
                        }
                    }
                }
            }
            if (bitmapList.isEmpty()) return@synchronized null
            val snapshotPages = if (expandedDirectWifiRunway) selectedPages else requestedPages
            if (snapshotPages.isEmpty()) return@synchronized null
            val firstPage = snapshotPages.first()
            val lastPage = snapshotPages.last()
            nativeTexturePrewarmDirty = false
            nativeTexturePrewarmAnchorPage = firstPage
            nativeTexturePrewarmPendingPages.clear()
            NativeTexturePrewarmSnapshot(
                handle = rollingNativeHandle,
                structureEpoch = traversalStructureEpoch,
                lifecycleEpoch = lifecycleEpoch,
                creationGeneration = rollingNativeCreateGeneration,
                firstPage = firstPage,
                lastPage = lastPage,
                requestedPages = snapshotPages.toIntArray(),
                tileData = tileIntegers.toIntArray(),
                bitmaps = bitmapList.toTypedArray()
            )
        } ?: return

        val stillCurrent = synchronized(stateLock) {
            renderRunning && !rollingNativeFatal && rollingNativeHandle == snapshot.handle &&
                traversalStructureEpoch == snapshot.structureEpoch &&
                lifecycleEpoch == snapshot.lifecycleEpoch &&
                rollingNativeCreateGeneration == snapshot.creationGeneration
        }
        if (!stillCurrent) {
            synchronized(stateLock) {
                nativeTexturePrewarmDirty = true
                snapshot.requestedPages.forEach(nativeTexturePrewarmPendingPages::add)
            }
            return
        }
        val accepted = runCatching {
            NtkRollingNativeBridge.nativePrewarm(
                snapshot.handle,
                snapshot.structureEpoch,
                snapshot.tileData,
                snapshot.bitmaps,
                false,
            )
        }.getOrDefault(false)
        synchronized(stateLock) {
            if (!accepted) {
                nativeTexturePrewarmDirty = true
                snapshot.requestedPages.forEach(nativeTexturePrewarmPendingPages::add)
            }
        }
        if (!accepted) {
            Log.d(
                TAG,
                "reader_native_texture_prewarm_${if (accepted) "queued" else "rejected"} " +
                    "epoch=${snapshot.structureEpoch} pages=${snapshot.firstPage}-${snapshot.lastPage} " +
                    "tiles=${snapshot.bitmaps.size}"
            )
        }
        synchronized(stateLock) {
            if (nativeTexturePrewarmDirty && !nativeTexturePrewarmFlushPosted) {
                requestResidentNativeTexturePrewarmLocked()
            }
        }
    }

    private fun postResidentNativeTexturePrewarmLocked() {
        requestResidentNativeTexturePrewarmLocked()
    }

    /** Must be called with [stateLock] held. */
    private fun setNativeTexturePrewarmPausedLocked(paused: Boolean) {
        if (nativeTexturePrewarmPaused == paused) return
        nativeTexturePrewarmPaused = paused
        val handle = rollingNativeHandle
        if (handle != 0L && !rollingNativeFatal) {
            NtkRollingNativeBridge.nativeSetPrewarmPaused(handle, paused)
        }
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap) {
        setPageBitmap(index, bitmap, forceImmediateGeometry = false)
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap, forceImmediateGeometry: Boolean) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return@synchronized null
            val page = pages.getOrNull(index) ?: return
            // Queue decoded pixels even when a later identity/geometry check makes this delivery
            // a state no-op. Native presentation keeps the cold first frame ahead of this queue.
            postNativeBitmapTexturePrewarmLocked(index, bitmap)
            val bitmapWidth = max(1, bitmap.width)
            val bitmapHeight = max(1, bitmap.height)
            val layoutBounds = layoutBoundsForDrawableLocked(page, bitmapWidth, bitmapHeight)
            val layoutWidth = layoutBounds.first
            val layoutHeight = layoutBounds.second
            if (isSettledBitmapDeliveryNoOpLocked(page, bitmap, layoutWidth, layoutHeight)) {
                return@synchronized null
            }
            val hasCurrentDrawableBeforeLayout = page.bitmap != null || page.tiles.isNotEmpty()
            if (!forceImmediateGeometry &&
                hasCurrentDrawableBeforeLayout &&
                shouldDeferDrawableReplacementLocked()
            ) {
                page.pendingResolveType = PENDING_BITMAP
                page.pendingBitmap = bitmap
                page.pendingTiles = emptyList()
                page.pendingWidth = layoutWidth
                page.pendingHeight = layoutHeight
                page.loading = false
                page.errorText = null
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(layoutWidth, layoutHeight)
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (!forceImmediateGeometry &&
                shouldFreezeGeometryForNextBoundaryLocked(index, oldTop, oldHeight, newHeight)
            ) {
                invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
                page.bitmap = bitmap
                page.tiles = emptyList()
                page.width = max(1, width)
                page.height = max(1, oldHeight.toInt())
                page.pendingResolveType = PENDING_SIZE
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = layoutWidth
                page.pendingHeight = layoutHeight
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                maybeReleasePrependedReadyHoldLocked(index)
                val renderNow = shouldRenderPageResolveNowLocked(index)
                if (renderNow) {
                    refreshVisibleCoverageAfterDrawableLocked(index)
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                    return@synchronized windowRequestLocked(lastBusy)
                }
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                shouldDeferInitialDrawableSizeLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable)
            ) {
                invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
                page.bitmap = bitmap
                page.tiles = emptyList()
                page.width = max(1, width)
                page.height = max(1, oldHeight.toInt())
                page.pendingResolveType = PENDING_SIZE
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = layoutWidth
                page.pendingHeight = layoutHeight
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                maybeReleasePrependedReadyHoldLocked(index)
                val renderNow = shouldRenderPageResolveNowLocked(index)
                if (renderNow) {
                    refreshVisibleCoverageAfterDrawableLocked(index)
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                    return@synchronized windowRequestLocked(lastBusy)
                }
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                hasCurrentDrawable &&
                shouldDeferDrawableReplacementLocked()
            ) {
                page.pendingResolveType = PENDING_BITMAP
                page.pendingBitmap = bitmap
                page.pendingTiles = emptyList()
                page.pendingWidth = layoutWidth
                page.pendingHeight = layoutHeight
                page.loading = false
                page.errorText = null
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                shouldDeferHeightChangingResolveLocked(
                    oldTop,
                    oldHeight,
                    newHeight,
                    hasCurrentDrawable
                )
            ) {
                if (hasCurrentDrawable) {
                    page.pendingResolveType = PENDING_BITMAP
                    page.pendingBitmap = bitmap
                    page.loading = false
                    page.errorText = null
                } else {
                    page.pendingResolveType = PENDING_BITMAP
                    page.pendingBitmap = bitmap
                }
                page.pendingTiles = emptyList()
                page.pendingWidth = layoutWidth
                page.pendingHeight = layoutHeight
                deferInitialEmptyDraw = false
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
            page.bitmap = bitmap
            page.tiles = emptyList()
            page.width = layoutWidth
            page.height = layoutHeight
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            markBlockedDrawableResolvedLocked(index)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_bitmap", index, oldHeight, newHeight)
            maybeReleasePrependedReadyHoldLocked(index)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            val renderNow = shouldRenderPageResolveNowLocked(index)
            if (renderNow) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("bitmap", index)
        }
        dispatchWindowRequest(request)
    }

    data class StripInstallResult(
        val installed: Set<NtkStripTileKey>,
        val rejected: Set<NtkStripTileKey>
    )

    data class StripResidentCoverageSnapshot(
        val authority: Long,
        val episode: NtkEpisodeToken,
        val viewportTopPx: Long,
        val viewportBottomPx: Long,
        val viewportContinuousEndPx: Long,
        val residentContinuousEndPx: Long,
        val firstViewportGapPx: Long?,
        val residentIntervals: List<NtkStripIntervalSet.Interval>
    )

    /**
     * Establishes the only pixel-writer authority for one NTK episode. Geometry and empty slot
     * tables are created during staging; activation therefore performs no page enumeration.
     */
    fun bindAuthoritativeStrip(authority: Long, geometry: NtkStripGeometry): Boolean {
        if (authority <= 0L || geometry.pages.isEmpty()) return false
        val request = synchronized(stateLock) {
            if (width > 0 && geometry.viewportWidthPx != width) {
                Log.e(
                    TAG,
                    "reader_strip_bind_width_reject expected=${geometry.viewportWidthPx} actual=$width"
                )
                return@synchronized null
            }
            clearRetainedPageNodesStateLocked()
            scroller.forceFinished(true)
            clearInputStateLocked()
            pages.clear()
            for (pageGeometry in geometry.pages) {
                val asset = pageGeometry.asset
                pages += Page(
                    width = asset.sourceWidth,
                    height = asset.sourceHeight,
                    loading = false,
                    stripAuthority = authority,
                    stripEpisode = geometry.episode.value,
                    stripAsset = asset.canonicalAsset,
                    stripSlots = List(pageGeometry.tiles.size) { null }
                )
            }
            stripAuthorityToken = authority
            stripGeometry = geometry
            stripResidentCoverage.clear()
            stripResidentCycles.clear()
            pageGapPx = 0
            inlineRealPixelsOnly = true
            setScrollOffsetLocked(0f)
            layoutDirty = true
            lastVisibleCoverageSnapshot = null
            resetTraversalProofLocked(pages.size)
            deferInitialEmptyDraw = true
            renderRequested = false
            clearFramePipeLocked(preserveDirty = true)
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
        return synchronized(stateLock) {
            stripAuthorityToken == authority && stripGeometry === geometry
        }
    }

    /** Appends newly measured tail pages without changing any already-published strip geometry. */
    fun extendAuthoritativeStrip(authority: Long, geometry: NtkStripGeometry): Boolean {
        if (authority <= 0L || geometry.pages.isEmpty()) return false
        val request = synchronized(stateLock) {
            val previous = stripGeometry ?: return@synchronized null
            if (stripAuthorityToken != authority || previous.episode != geometry.episode ||
                previous.viewportWidthPx != geometry.viewportWidthPx ||
                geometry.pages.size < previous.pages.size ||
                previous.pages.indices.any { previous.pages[it] != geometry.pages[it] }
            ) return@synchronized null
            if (geometry.pages.size == previous.pages.size) {
                stripGeometry = geometry
                return@synchronized null
            }
            for (pageGeometry in geometry.pages.drop(previous.pages.size)) {
                val asset = pageGeometry.asset
                pages += Page(
                    width = asset.sourceWidth,
                    height = asset.sourceHeight,
                    loading = false,
                    stripAuthority = authority,
                    stripEpisode = geometry.episode.value,
                    stripAsset = asset.canonicalAsset,
                    stripSlots = List(pageGeometry.tiles.size) { null }
                )
            }
            stripGeometry = geometry
            layoutDirty = true
            lastVisibleCoverageSnapshot = null
            extendTraversalProofLocked(pages.size)
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
        return synchronized(stateLock) {
            stripAuthorityToken == authority && stripGeometry === geometry
        }
    }

    /** Installs immutable original tiles as slot deltas with one lock and one invalidate. */
    fun installAuthoritativeStripTileDelta(commands: List<NtkStripTileInstall>): StripInstallResult {
        if (commands.isEmpty()) return StripInstallResult(emptySet(), emptySet())
        val installed = LinkedHashSet<NtkStripTileKey>()
        val rejected = LinkedHashSet<NtkStripTileKey>()
        val request = synchronized(stateLock) {
            val geometry = stripGeometry
            val workingSlots = LinkedHashMap<Int, MutableList<ReaderTile?>>()
            val changedProofs = LinkedHashMap<Int, ReaderPreparedStore.PreparedOriginalProof>()
            for (command in commands) {
                val tileGeometry = geometry?.tile(command.key)
                val pageGeometry = geometry?.pages?.getOrNull(command.key.pageIndex)
                val page = pages.getOrNull(command.key.pageIndex)
                val tile = command.tile
                val valid = command.authority == stripAuthorityToken &&
                    command.authority > 0L && command.key.episode == geometry?.episode &&
                    page != null && pageGeometry != null && tileGeometry != null &&
                    page.stripAuthority == command.authority &&
                    page.stripAsset == pageGeometry.asset.canonicalAsset &&
                    ReaderPreparedStore.isCanonicalOriginalProof(
                        command.proof,
                        pageGeometry.asset.canonicalAsset,
                        pageGeometry.asset.sourceWidth,
                        pageGeometry.asset.sourceHeight
                    ) &&
                    tile.sourceTop == tileGeometry.sourceTop &&
                    tile.sourceBottom == tileGeometry.sourceBottom &&
                    tile.sourceWidth == pageGeometry.asset.sourceWidth &&
                    tile.sourceHeight == pageGeometry.asset.sourceHeight &&
                    !tile.bitmap.isRecycled && tile.bitmap.config == Bitmap.Config.ARGB_8888 &&
                    !tile.bitmap.isMutable && tile.bitmap.width == tile.sourceWidth &&
                    tile.bitmap.height == tile.sourceBottom - tile.sourceTop &&
                    command.rgbaBytes == tile.bitmap.width.toLong() * tile.bitmap.height * 4L
                if (!valid) {
                    rejected += command.key
                    continue
                }
                // This is the authoritative strict-strip delivery edge used in production.
                // Queue the exact page/slot identity whether the state mutation below is new or
                // an idempotent redelivery; native deduplication keeps only one resident texture.
                postNativeStripTexturePrewarmLocked(command.key, tile)
                val slots = workingSlots.getOrPut(command.key.pageIndex) {
                    page!!.stripSlots.toMutableList()
                }
                val existing = slots[command.key.slotIndex]
                if (existing != null) {
                    val cycle = stripResidentCycles[command.key]
                    if (existing.bitmap === tile.bitmap &&
                        cycle?.resourceRevision == command.resourceRevision &&
                        cycle.installLease == command.installLease &&
                        cycle.rgbaBytes == command.rgbaBytes
                    ) installed += command.key else rejected += command.key
                    continue
                }
                slots[command.key.slotIndex] = tile
                stripResidentCycles[command.key] = StripResidentCycle(
                    command.resourceRevision,
                    command.installLease,
                    command.rgbaBytes
                )
                changedProofs[command.key.pageIndex] = command.proof
                stripResidentCoverage.add(tileGeometry.contentTopPx, tileGeometry.contentBottomPx)
                installed += command.key
            }
            for ((pageIndex, proof) in changedProofs) {
                val page = pages[pageIndex]
                val nextSlots = checkNotNull(workingSlots[pageIndex])
                page.stripSlots = nextSlots.toList()
                page.tiles = nextSlots.filterNotNull()
                page.bitmap = null
                page.originalProof = proof
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
                invalidateRetainedPageNodeStateLocked(pageIndex)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    page.stripSlots.isNotEmpty() && page.stripSlots.all { it != null }
                ) {
                    // Record the immutable page display list at install time. Creating it on the
                    // first frame that reaches this page moves several tile commands into the
                    // input-critical draw turn and produces a repeatable page-boundary hitch.
                    retainedPageNode(
                        DrawItem(
                            index = pageIndex,
                            bitmap = null,
                            cardBitmap = null,
                            tiles = page.tiles,
                            sourceWidth = page.width,
                            sourceHeight = page.height,
                            originalProof = page.originalProof,
                            stripAuthoritative = true,
                            adjacentExactAuthoritative = false,
                            stripAsset = page.stripAsset,
                            committedIdentity = page.committedIdentity,
                            loading = false,
                            cardText = null,
                            errorText = null,
                            top = 0f,
                            pageHeight = pageDrawHeightLocked(page)
                        ),
                        max(1, width)
                    )
                }
            }
            if (installed.isNotEmpty()) {
                deferInitialEmptyDraw = false
                rebuildLayoutLocked()
                val viewportTop = scrollOffset
                val viewportBottom = viewportTop + height
                val affectsViewport = changedProofs.keys.any { pageIndex ->
                    val page = pages.getOrNull(pageIndex) ?: return@any false
                    val top = pageTopOrElseLocked(pageIndex, 0f)
                    val bottom = top + pageDrawHeightLocked(page)
                    bottom > viewportTop && top < viewportBottom
                }
                if (affectsViewport) {
                    lastVisibleCoverageSnapshot = null
                    renderRequested = true
                    scheduleFrameLocked()
                }
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        dispatchWindowRequest(request)
        return StripInstallResult(installed, rejected)
    }

    data class AdjacentExactP0InstallResult(
        val accepted: Boolean,
        val complete: Boolean,
        val displayPageIndex: Int,
        val firstInstall: Boolean,
    )

    /**
     * Installs p0 source-row resources without granting whole-page readiness. The immutable
     * committed identity is resolved under the same lock as the slot mutation, so a card prune,
     * lifecycle replacement, or duplicate append cannot redirect a late tail to another page.
     */
    fun installAdjacentExactP0Delta(
        delta: NtkAdjacentExactP0Delta,
    ): AdjacentExactP0InstallResult {
        var outcome = AdjacentExactP0InstallResult(false, false, -1, false)
        val request = synchronized(stateLock) {
            if (delta.owner.normalizedEpisodePath.startsWith("/webtoon/").not()) {
                return@synchronized null
            }
            val pageIndex = pages.indexOfFirst { page ->
                val identity = page.committedIdentity ?: return@indexOfFirst false
                identity.normalizedEpisodePath == delta.owner.normalizedEpisodePath &&
                    identity.sourcePageIndex == 0 &&
                    identity.canonicalAsset == delta.owner.canonicalAsset &&
                    identity.manifestDigest == delta.owner.manifestDigest &&
                    identity.manifestPageCount == delta.owner.manifestPageCount
            }
            val page = pages.getOrNull(pageIndex) ?: return@synchronized null
            val plan = delta.plan
            val owner = delta.owner
            val proofValid = ReaderPreparedStore.isCanonicalOriginalProof(
                delta.proof,
                owner.canonicalAsset,
                plan.sourceWidth,
                plan.sourceHeight,
            )
            val firstInstall = page.adjacentExactOwner == null
            if (!proofValid || plan.sourceKey.pageIndex != 0 ||
                page.stripAuthority != 0L || page.stripSlots.isNotEmpty() ||
                (firstInstall && (page.bitmap != null || page.tiles.isNotEmpty() ||
                    page.cardBitmap != null || page.cardText != null || page.errorText != null)) ||
                (!firstInstall && (page.adjacentExactOwner != owner ||
                    page.adjacentExactPlan != plan))
            ) return@synchronized null
            val expectedHeadSlots = (0 until min(2, plan.tiles.size)).toList()
            if (firstInstall && delta.installs.map { it.slotIndex }.sorted() != expectedHeadSlots) {
                return@synchronized null
            }
            val workingSlots = if (firstInstall) {
                MutableList<ReaderTile?>(plan.tiles.size) { null }
            } else {
                page.adjacentExactSlots.toMutableList()
            }
            if (workingSlots.size != plan.tiles.size) return@synchronized null
            val workingCycles = page.adjacentExactCycles.toMutableMap()
            for (install in delta.installs) {
                val tilePlan = plan.tiles.getOrNull(install.slotIndex) ?: return@synchronized null
                val tile = install.tile
                val valid = tilePlan.key.episode == owner.episode &&
                    tilePlan.key.pageIndex == 0 && tilePlan.key.slotIndex == install.slotIndex &&
                    tile.sourceTop == tilePlan.sourceTop &&
                    tile.sourceBottom == tilePlan.sourceBottom &&
                    tile.sourceWidth == plan.sourceWidth && tile.sourceHeight == plan.sourceHeight &&
                    !tile.bitmap.isRecycled && tile.bitmap.config == Bitmap.Config.ARGB_8888 &&
                    !tile.bitmap.isMutable && tile.bitmap.width == plan.sourceWidth &&
                    tile.bitmap.height == tile.sourceBottom - tile.sourceTop &&
                    install.rgbaBytes == tilePlan.rgbaBytes &&
                    install.rgbaBytes == tile.bitmap.width.toLong() * tile.bitmap.height * 4L
                if (!valid) return@synchronized null
                val existing = workingSlots[install.slotIndex]
                if (existing != null) {
                    val cycle = workingCycles[install.slotIndex]
                    if (cycle == null || existing.bitmap !== tile.bitmap ||
                        cycle.resourceRevision != install.resourceRevision ||
                        cycle.installLease != install.installLease ||
                        cycle.rgbaBytes != install.rgbaBytes
                    ) return@synchronized null
                } else {
                    workingSlots[install.slotIndex] = tile
                    workingCycles[install.slotIndex] = AdjacentExactResidentCycle(
                        install.resourceRevision,
                        install.installLease,
                        install.rgbaBytes,
                    )
                }
            }
            val full = workingSlots.all { it != null }
            if (delta.complete != full) return@synchronized null
            if (!full && contiguousAdjacentExactSourceBottom(plan, workingSlots) <= 0) {
                return@synchronized null
            }

            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(pageIndex, 0f)
            val viewportAnchor = progressPositionLocked()
            page.adjacentExactOwner = owner
            page.adjacentExactPlan = plan
            page.adjacentExactSlots = workingSlots.toList()
            page.adjacentExactCycles = workingCycles.toMap()
            page.bitmap = null
            page.tiles = workingSlots.filterNotNull()
            page.width = plan.sourceWidth
            page.height = plan.sourceHeight
            page.originalProof = delta.proof
            page.loading = false
            page.cardText = null
            page.errorText = null
            clearPendingResolveLocked(page)
            invalidateRetainedPageNodeStateLocked(pageIndex)
            postNativeTexturePrewarmLocked(pageIndex, delta.installs.map { it.tile })
            noteResolvedPageAspectLocked(page.width, page.height)
            val newHeight = resolvedPageDrawHeightLocked(page.width, page.height)
            applyPageHeightChangeLocked(pageIndex, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(
                viewportAnchor,
                "adjacent_exact_p0_${if (full) "tail" else "head"}",
                pageIndex,
                oldHeight,
                newHeight,
            )
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            deferInitialEmptyDraw = false
            lastVisibleCoverageSnapshot = null
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            outcome = AdjacentExactP0InstallResult(true, full, pageIndex, firstInstall)
            windowRequestLocked(lastBusy)
        }
        synchronized(stateLock) {
            if (outcome.accepted) {
                reevaluateDeferredSurfaceRevealLocked(
                    "adjacent_exact_p0",
                    outcome.displayPageIndex,
                )
            }
        }
        dispatchWindowRequest(request)
        return outcome
    }

    data class AdjacentExactRunwayBatchInstallResult(
        val accepted: Boolean,
        val installedPages: Set<Int>,
    )

    /**
     * Installs a contiguous adjacent forward batch without weakening the launch strip authority.
     * Every page is identity/proof checked before the first mutation, so failure leaves the just
     * appended Surface tail empty and safely rollbackable.
     */
    fun installAdjacentExactRunwayBatch(
        publication: NtkAdjacentExactRunwayBatchPublication,
    ): AdjacentExactRunwayBatchInstallResult {
        var outcome = AdjacentExactRunwayBatchInstallResult(false, emptySet())
        val request = synchronized(stateLock) {
            if (pages.size != publication.totalPageCount) {
                Log.e(
                    TAG,
                    "reader_adjacent_exact_runway_reject reason=count " +
                        "surface=${pages.size} expected=${publication.totalPageCount}",
                )
                return@synchronized null
            }
            val ordered = publication.pages.sortedBy { it.displayPageIndex }
            val resolved = ArrayList<Pair<NtkAdjacentExactRunwayTilePage, Page>>(ordered.size)
            for (command in ordered) {
                val page = pages.getOrNull(command.displayPageIndex)
                val identity = page?.committedIdentity
                val identityMatches = identity != null &&
                    identity.displayPageIndex == command.displayPageIndex &&
                    identity.normalizedEpisodePath == command.normalizedEpisodePath &&
                    identity.sourcePageIndex == command.sourcePageIndex &&
                    identity.canonicalAsset == command.canonicalAsset &&
                    identity.manifestDigest == command.manifestDigest &&
                    identity.manifestPageCount == command.manifestPageCount
                val emptyOwner = page != null && page.stripAuthority == 0L &&
                    page.stripSlots.isEmpty() && page.adjacentExactOwner == null &&
                    page.adjacentExactPlan == null && page.adjacentExactSlots.isEmpty() &&
                    page.bitmap == null && page.cardBitmap == null && page.tiles.isEmpty() &&
                    page.cardText == null && page.errorText == null
                val proofValid = usableAuthoritativeOriginalTilePage(
                    command.pageWidth,
                    command.pageHeight,
                    command.tiles,
                    command.proof,
                ) && ReaderPreparedStore.isCanonicalOriginalProof(
                    command.proof,
                    command.canonicalAsset,
                    command.pageWidth,
                    command.pageHeight,
                )
                if (page == null || !identityMatches || !emptyOwner || !proofValid) {
                    Log.e(
                        TAG,
                        "reader_adjacent_exact_runway_reject page=${command.displayPageIndex} " +
                            "source=${command.sourcePageIndex} identity=$identityMatches " +
                            "empty=$emptyOwner proof=$proofValid strip=${page?.stripAuthority ?: -1L}",
                    )
                    return@synchronized null
                }
                resolved += command to page
            }

            invalidatePreparedRenderSceneStateLocked()
            rebuildLayoutLocked()
            val viewportAnchor = progressPositionLocked()
            var firstChangedIndex = -1
            var firstOldHeight = 0f
            var firstNewHeight = 0f
            val installed = LinkedHashSet<Int>(resolved.size)
            for ((command, page) in resolved) {
                val index = command.displayPageIndex
                val oldHeight = pageDrawHeightLocked(page)
                val oldTop = pageTopOrElseLocked(index, 0f)
                val targetWidth = max(1, command.pageWidth)
                val targetHeight = max(1, command.pageHeight)
                val newHeight = resolvedPageDrawHeightLocked(targetWidth, targetHeight)
                if (firstChangedIndex < 0) {
                    firstChangedIndex = index
                    firstOldHeight = oldHeight
                    firstNewHeight = newHeight
                }
                postNativeTexturePrewarmLocked(index, command.tiles)
                invalidateRetainedPageNodeIfTilesChanged(index, page, command.tiles)
                page.bitmap = null
                page.cardBitmap = null
                page.tiles = command.tiles
                page.width = targetWidth
                page.height = targetHeight
                page.originalProof = command.proof
                clearPendingResolveLocked(page)
                noteResolvedPageAspectLocked(page.width, page.height)
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
                maybeReleasePrependedReadyHoldLocked(index)
                installed += index
            }
            restoreViewportAnchorLocked(
                viewportAnchor,
                "adjacent_exact_runway_batch",
                firstChangedIndex,
                firstOldHeight,
                firstNewHeight,
            )
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            lastVisibleCoverageSnapshot = null
            refreshVisibleCoverageAfterDrawableLocked(installed.last())
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            outcome = AdjacentExactRunwayBatchInstallResult(true, installed)
            windowRequestLocked(lastBusy)
        }
        synchronized(stateLock) {
            if (outcome.accepted) {
                outcome.installedPages.lastOrNull()?.let { lastInstalled ->
                    reevaluateDeferredSurfaceRevealLocked(
                        "adjacent_exact_runway_batch",
                        lastInstalled,
                    )
                }
            }
        }
        dispatchWindowRequest(request)
        return outcome
    }

    /** Rolls back only an identity-only adjacent tail that the exact batch rejected pre-mutation. */
    fun rollbackAdjacentExactAppendedTail(previousPageCount: Int, totalPageCount: Int): Boolean {
        if (previousPageCount < 0 || totalPageCount <= previousPageCount) return false
        var rolledBack = false
        val request = synchronized(stateLock) {
            if (pages.size != totalPageCount) return@synchronized null
            val tail = pages.subList(previousPageCount, totalPageCount)
            val emptyTail = tail.all { page ->
                page.stripAuthority == 0L && page.stripSlots.isEmpty() &&
                    page.adjacentExactOwner == null && page.adjacentExactPlan == null &&
                    page.adjacentExactSlots.isEmpty() && page.bitmap == null &&
                    page.cardBitmap == null && page.tiles.isEmpty() &&
                    page.cardText == null && page.errorText == null
            }
            if (!emptyTail) return@synchronized null
            rebuildLayoutLocked()
            val viewportAnchor = progressPositionLocked()
            repeat(totalPageCount - previousPageCount) { pages.removeAt(pages.lastIndex) }
            resetTraversalProofLocked(previousPageCount)
            layoutDirty = true
            rebuildLayoutLocked()
            if (viewportAnchor != null && viewportAnchor.page in pages.indices) {
                setScrollOffsetLocked(
                    pageTopOrElseLocked(viewportAnchor.page, 0f) - viewportAnchor.offset,
                )
            }
            clampScrollLocked()
            boundaryDispatchInFlight = false
            lastVisibleCoverageSnapshot = null
            renderRequested = pages.isNotEmpty()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            rolledBack = true
            if (renderRequested) windowRequestLocked(lastBusy) else null
        }
        dispatchWindowRequest(request)
        return rolledBack
    }

    private fun contiguousAdjacentExactSourceBottom(
        plan: NtkPreGeometryPagePlan,
        slots: List<ReaderTile?>,
    ): Int {
        if (slots.size != plan.tiles.size) return 0
        var bottom = 0
        for (slot in plan.tiles.indices) {
            val tile = slots[slot] ?: break
            val tilePlan = plan.tiles[slot]
            if (tile.sourceTop != bottom || tile.sourceTop != tilePlan.sourceTop ||
                tile.sourceBottom != tilePlan.sourceBottom || tile.bitmap.isRecycled
            ) return 0
            bottom = tile.sourceBottom
        }
        return bottom
    }

    fun authoritativeStripCoverageSnapshot(): StripResidentCoverageSnapshot? = synchronized(stateLock) {
        val geometry = stripGeometry ?: return@synchronized null
        if (stripAuthorityToken <= 0L || width <= 0 || height <= 0) return@synchronized null
        val top = max(0L, kotlin.math.floor(scrollOffset.toDouble()).toLong())
        val bottom = minOf(geometry.contentHeightPx, top + height.toLong())
        StripResidentCoverageSnapshot(
            authority = stripAuthorityToken,
            episode = geometry.episode,
            viewportTopPx = top,
            viewportBottomPx = bottom,
            viewportContinuousEndPx = stripResidentCoverage.continuousEndFrom(top),
            residentContinuousEndPx = stripResidentCoverage.continuousEndFrom(bottom),
            firstViewportGapPx = stripResidentCoverage.firstGap(top, bottom),
            residentIntervals = stripResidentCoverage.snapshot()
        )
    }

    fun releaseAuthoritativeStrip(authority: Long): Boolean = synchronized(stateLock) {
        if (authority <= 0L || authority != stripAuthorityToken) return@synchronized false
        stripAuthorityToken = 0L
        stripGeometry = null
        stripResidentCoverage.clear()
        stripResidentCycles.clear()
        true
    }

    /**
     * Atomically adopts a complete, already-decoded reader episode.
     *
     * The bitmaps remain owned by their producer. This view only retains their identities and
     * never recycles them. A pre-layout adoption uses the expected viewport for its initial
     * geometry, then reapplies the requested anchor when the real view size becomes available.
     */
    fun adoptPreparedBitmapBatch(
        pageCount: Int,
        bitmaps: Map<Int, Bitmap>,
        startPage: Int,
        startOffset: Int,
        expectedViewportWidth: Int,
        expectedViewportHeight: Int
    ): Boolean {
        return adoptPreparedDrawableBatch(
            pageCount,
            bitmaps,
            emptyMap(),
            startPage,
            startOffset,
            expectedViewportWidth,
            expectedViewportHeight
        )
    }

    fun adoptPreparedDrawableBatch(
        pageCount: Int,
        bitmaps: Map<Int, Bitmap>,
        tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>,
        startPage: Int,
        startOffset: Int,
        expectedViewportWidth: Int,
        expectedViewportHeight: Int
    ): Boolean {
        if (pageCount <= 0 ||
            bitmaps.size + tilePages.size != pageCount ||
            bitmaps.keys.any { tilePages.containsKey(it) } ||
            startPage !in 0 until pageCount ||
            expectedViewportWidth <= 0 ||
            expectedViewportHeight <= 0
        ) {
            return false
        }
        val preparedBitmaps = ArrayList<Bitmap>(pageCount)
        var directHardwareBatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tilePages.isEmpty()
        for (index in 0 until pageCount) {
            val bitmap = bitmaps[index]
            val tilePage = tilePages[index]
            if ((bitmap == null) == (tilePage == null)) return false
            if (bitmap != null) {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
                directHardwareBatch = directHardwareBatch && bitmap.isHardwareConfigCompat()
                preparedBitmaps.add(bitmap)
            } else if (!usablePreparedTilePage(tilePage)) {
                return false
            }
        }
        val viewportCompatible = synchronized(stateLock) {
            (width <= 0 || width == expectedViewportWidth) &&
                (height <= 0 || height == expectedViewportHeight)
        }
        if (!viewportCompatible) {
            Log.d(
                TAG,
                "reader_prepared_batch_reject_viewport expected=${expectedViewportWidth}x$expectedViewportHeight " +
                    "actual=${width}x$height pages=$pageCount"
            )
            return false
        }
        val strictDirectPixels = synchronized(stateLock) { inlineRealPixelsOnly }
        val directPreparedBitmaps = strictDirectPixels || BITMAP_SUBMISSION_MODE ==
            BitmapSubmissionMode.DIRECT_VISIBLE_CROP || directHardwareBatch
        val preparedScene = if (directPreparedBitmaps || tilePages.isNotEmpty()) {
            null
        } else {
            buildPreparedBitmapScene(preparedBitmaps, expectedViewportWidth)
        }
        val preparedBitmapsOpaque = preparedBitmaps.all { !it.hasAlpha() } &&
            tilePages.values.all { page -> page.tiles.all { !it.bitmap.hasAlpha() } }

        // State is authoritative. Publish the fully built scene only while holding state, then
        // take the retained-node lock in the one global order used by every mutation.
        val adopted = synchronized(stateLock) state@{
                for (index in 0 until pageCount) {
                    val bitmap = bitmaps[index]
                    val tilePage = tilePages[index]
                    if ((bitmap != null && (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0)) ||
                        (bitmap == null && !usablePreparedTilePage(tilePage))
                    ) {
                        return@state false
                    }
                }
                if ((width > 0 && width != expectedViewportWidth) ||
                    (height > 0 && height != expectedViewportHeight)
                ) {
                    Log.d(
                        TAG,
                        "reader_prepared_batch_reject_viewport expected=${expectedViewportWidth}x$expectedViewportHeight " +
                            "actual=${width}x$height pages=$pageCount"
                    )
                    return@state false
                }

                scroller.forceFinished(true)
                activeScrollerOffsetShift = 0f
                clearInputStateLocked()
                activeInputDirection = 0
                lastBusy = false
                lastRequestedBusy = false
                pendingWindowRequest = null
                windowDispatchPosted = false
                pendingReverseWindowFirstPageHint = -1
                pendingBlockedForwardRequest = null
                blockedForwardDispatchPosted = false
                lastBlockedForwardPage = -1
                lastBlockedForwardRequestAtMs = 0L
                boundaryArmedDirection = 0
                boundaryDispatchInFlight = false
                nextBoundaryAppendInFlight = false
                nextBoundaryHoldUntilMs = 0L
                nextBoundaryHoldMinScroll = 0f
                nextBoundaryHoldAnchorPage = -1
                prependedRevealHoldPage = -1
                clearPrependedReadyHoldLocked()
                clearLockedRestorePositionLocked()
                clearInitialRenderHoldLocked()

                pendingPreparedStartPage = startPage
                pendingPreparedStartOffset = startOffset
                pendingPreparedViewportWidth = expectedViewportWidth
                pendingPreparedViewportHeight = expectedViewportHeight
                resetTraversalProofLocked(pageCount)
                pages.clear()
                for (index in 0 until pageCount) {
                    val bitmap = bitmaps[index]
                    val tilePage = tilePages[index]
                    pages.add(
                        Page(
                            bitmap = bitmap,
                            tiles = tilePage?.tiles ?: emptyList(),
                            width = bitmap?.width ?: tilePage!!.pageWidth,
                            height = bitmap?.height ?: tilePage!!.pageHeight,
                            originalProof = tilePage?.originalProof,
                            loading = false,
                            cardText = null,
                            errorText = null,
                            pendingResolveType = PENDING_NONE,
                            pendingBitmap = null,
                            pendingTiles = emptyList(),
                            pendingWidth = 0,
                            pendingHeight = 0
                        )
                    )
                }
                scrollOffset = 0f
                deferInitialEmptyDraw = false
                initialViewportHoldUntilMs = 0L
                structuralScrollAdjustUntilMs = 0L
                lastAnchor = -1
                lastNearStart = false
                lastNearEnd = false
                hasDrawnContentFrame = false
                lastVisibleCoverageSnapshot = null
                lastVisibleLoading = -1
                layoutDirty = true
                rebuildLayoutLocked()
                applyPreparedStartAnchorLocked(clearPending = width > 0 && height > 0)
                val adoptedGeneration = advanceVisualGenerationLocked()
                directPreparedBitmapGeneration = if (directPreparedBitmaps) {
                    adoptedGeneration
                } else {
                    Long.MIN_VALUE
                }
                directPreparedBitmapWidth = if (directPreparedBitmaps) expectedViewportWidth else 0
                directPreparedBitmapOpaque = directPreparedBitmaps && preparedBitmapsOpaque
                synchronized(retainedNodeLock) {
                    discardRetainedPageNodesLocked()
                    if (preparedScene != null) preparedScene.generation = adoptedGeneration
                    preparedRenderScene = preparedScene
                }
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                Log.d(
                    TAG,
                    "reader_prepared_batch_adopted pages=$pageCount start=$startPage:$startOffset " +
                        "expected=${expectedViewportWidth}x$expectedViewportHeight actual=${width}x$height " +
                        "scene=${if (preparedScene == null) 0 else 1} " +
                        "directPrepared=${if (directPreparedBitmaps) 1 else 0} " +
                        "directHardware=${if (directHardwareBatch) 1 else 0} " +
                        "tilePages=${tilePages.size},tiles=${tilePages.values.sumOf { it.tiles.size }}"
                )
                true
        }
        if (!adopted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            preparedScene?.chunks?.forEach { it.node.discardDisplayList() }
        return adopted
    }

    /**
     * Atomically adopts a strict, contiguous launch subset while retaining the authoritative
     * episode page count. Pages outside the subset remain unresolved slots; they are never
     * promoted to loading/error/placeholder pixels and never constrain physical scrolling.
     */
    fun adoptPreparedDrawableSubset(
        totalPageCount: Int,
        bitmaps: Map<Int, Bitmap>,
        tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>,
        startPage: Int,
        startOffset: Int,
        expectedViewportWidth: Int,
        expectedViewportHeight: Int,
        scheduleFrame: Boolean
    ): Boolean {
        // The strict inline hand-off is tile-only because whole bitmap entries do not carry the
        // immutable authoritative-original provenance required for activation.
        if (bitmaps.isNotEmpty()) return false
        val drawableIndexes = preparedSubsetDrawableIndexes(
            totalPageCount,
            bitmaps.keys,
            tilePages.keys,
            startPage
        ) ?: return false
        if (startOffset < 0 || expectedViewportWidth <= 0 || expectedViewportHeight <= 0) {
            return false
        }
        // A partial hand-off is only safe after the attached host has its exact final geometry.
        if (width != expectedViewportWidth || height != expectedViewportHeight) return false

        for ((_, page) in tilePages) {
            if (!usablePreparedSubsetTilePage(page)) return false
        }
        val startDrawHeight = preparedSubsetPageDrawHeight(
            startPage,
            bitmaps,
            tilePages,
            expectedViewportWidth
        )
        if (startDrawHeight <= 0f || startOffset >= ceil(startDrawHeight).toInt()) return false
        var coveredHeight = -startOffset.toFloat()
        for (index in drawableIndexes) {
            coveredHeight += preparedSubsetPageDrawHeight(
                index,
                bitmaps,
                tilePages,
                expectedViewportWidth
            )
        }
        if (coveredHeight + HEIGHT_CHANGE_EPSILON_PX < expectedViewportHeight.toFloat()) return false

        return synchronized(stateLock) state@{
            if (width != expectedViewportWidth || height != expectedViewportHeight) {
                return@state false
            }
            if (preparedSubsetDrawableIndexes(
                    totalPageCount,
                    bitmaps.keys,
                    tilePages.keys,
                    startPage
                ) == null
            ) {
                return@state false
            }
            for ((_, page) in tilePages) {
                if (!usablePreparedSubsetTilePage(page)) return@state false
            }

            scroller.forceFinished(true)
            activeScrollerOffsetShift = 0f
            clearInputStateLocked()
            activeInputDirection = 0
            lastBusy = false
            lastRequestedBusy = false
            pendingWindowRequest = null
            windowDispatchPosted = false
            pendingReverseWindowFirstPageHint = -1
            pendingBlockedForwardRequest = null
            blockedForwardDispatchPosted = false
            lastBlockedForwardPage = -1
            lastBlockedForwardRequestAtMs = 0L
            boundaryArmedDirection = 0
            boundaryDispatchInFlight = false
            nextBoundaryAppendInFlight = false
            nextBoundaryHoldUntilMs = 0L
            nextBoundaryHoldMinScroll = 0f
            nextBoundaryHoldAnchorPage = -1
            prependedRevealHoldPage = -1
            clearPrependedReadyHoldLocked()
            clearLockedRestorePositionLocked()
            clearInitialRenderHoldLocked()

            // Partial adoption must never inherit either optional prefix gating or a visual gap.
            limitScrollToDrawablePrefix = false
            pageGapPx = 0
            pendingPreparedStartPage = startPage
            pendingPreparedStartOffset = startOffset
            pendingPreparedViewportWidth = expectedViewportWidth
            pendingPreparedViewportHeight = expectedViewportHeight
            resetTraversalProofLocked(totalPageCount)
            pages.clear()
            repeat(totalPageCount) { index ->
                val bitmap = bitmaps[index]
                val tilePage = tilePages[index]
                pages.add(
                    when {
                        bitmap != null -> Page(
                            bitmap = bitmap,
                            width = bitmap.width,
                            height = bitmap.height
                        )
                        tilePage != null -> Page(
                            tiles = tilePage.tiles,
                            width = tilePage.pageWidth,
                            height = tilePage.pageHeight,
                            originalProof = tilePage.originalProof
                        )
                        else -> newPageLocked()
                    }
                )
            }
            scrollOffset = 0f
            deferInitialEmptyDraw = false
            initialViewportHoldUntilMs = 0L
            structuralScrollAdjustUntilMs = 0L
            lastAnchor = -1
            lastNearStart = false
            lastNearEnd = false
            hasDrawnContentFrame = false
            lastVisibleCoverageSnapshot = null
            lastVisibleLoading = -1
            layoutDirty = true
            rebuildLayoutLocked()
            applyPreparedStartAnchorLocked(clearPending = true)
            clearRetainedPageNodesStateLocked()
            renderRequested = scheduleFrame
            clearFramePipeLocked(preserveDirty = scheduleFrame)
            if (scheduleFrame) scheduleFrameLocked()
            stateLock.notifyAll()
            Log.d(
                TAG,
                "reader_prepared_subset_adopted total=$totalPageCount,prepared=${drawableIndexes.size}," +
                    "start=$startPage:$startOffset,viewport=${expectedViewportWidth}x$expectedViewportHeight," +
                    "scheduleFrame=$scheduleFrame,tiles=${tilePages.values.sumOf { it.tiles.size }}"
            )
            true
        }
    }

    private fun preparedSubsetDrawableIndexes(
        totalPageCount: Int,
        bitmapIndexes: Collection<Int>,
        tileIndexes: Collection<Int>,
        startPage: Int
    ): List<Int>? {
        if (totalPageCount <= 0 || startPage !in 0 until totalPageCount) return null
        if (bitmapIndexes.any { it in tileIndexes }) return null
        val indexes = (bitmapIndexes + tileIndexes).sorted()
        if (indexes.isEmpty() || indexes.first() != startPage) return null
        if (indexes.any { it !in 0 until totalPageCount }) return null
        for (offset in indexes.indices) {
            if (indexes[offset] != startPage + offset) return null
        }
        return indexes
    }

    private fun usablePreparedSubsetTilePage(
        page: ReaderPreparedStore.PreparedTilePage
    ): Boolean {
        if (!usableAuthoritativeOriginalTilePage(
                page.pageWidth,
                page.pageHeight,
                page.tiles,
                page.originalProof
            )
        ) {
            return false
        }
        return true
    }

    private fun usableAuthoritativeOriginalTilePage(
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        proof: ReaderPreparedStore.PreparedOriginalProof?
    ): Boolean {
        val originalProof = proof ?: return false
        if (!authoritativeOriginalProofMetadataAccepted(pageWidth, pageHeight, originalProof) ||
            tiles.isEmpty()
        ) {
            return false
        }
        var expectedTop = 0
        for (tile in tiles) {
            val sourceSpan = tile.sourceBottom - tile.sourceTop
            val tail = tile.sourceBottom == originalProof.originalHeight
            if (tile.sourceWidth != originalProof.originalWidth ||
                tile.sourceHeight != originalProof.originalHeight ||
                tile.sourceTop != expectedTop || sourceSpan <= 0 ||
                tile.sourceBottom > originalProof.originalHeight ||
                (!tail && sourceSpan != PREPARED_SUBSET_TILE_SOURCE_HEIGHT) ||
                (tail && sourceSpan > PREPARED_SUBSET_TILE_SOURCE_HEIGHT) ||
                tile.bitmap.isRecycled || tile.bitmap.config != Bitmap.Config.ARGB_8888 ||
                tile.bitmap.isMutable || !tile.hasExactSourcePixelStorage()
            ) {
                return false
            }
            expectedTop = tile.sourceBottom
        }
        return expectedTop == originalProof.originalHeight
    }

    private fun preparedSubsetPageDrawHeight(
        index: Int,
        bitmaps: Map<Int, Bitmap>,
        tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>,
        viewportWidth: Int
    ): Float {
        val bitmap = bitmaps[index]
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            return viewportWidth * (bitmap.height / bitmap.width.toFloat())
        }
        val tilePage = tilePages[index] ?: return 0f
        if (tilePage.pageWidth <= 0 || tilePage.pageHeight <= 0) return 0f
        return viewportWidth * (tilePage.pageHeight / tilePage.pageWidth.toFloat())
    }

    @Suppress("UNUSED_PARAMETER")
    private fun minimumReadableSourceWidth(viewportWidth: Int, realPixelsOnly: Boolean): Int {
        // Full quality is relative to the encoded original, never to the physical View width.
        // A 740px source remains the full-quality source after a 1080px portrait View rotates to
        // a 2204px landscape View. Treating that honest source as a low-resolution placeholder
        // made the native compositor reject every post-rotation frame. The strict tile path still
        // proves inSampleSize=1 through exact source-width/source-row checks, while the legacy full
        // bitmap path receives the immutable original decode from the same exact encoded body.
        return if (viewportWidth > 0) 1 else Int.MAX_VALUE
    }

    private fun usablePreparedTilePage(page: ReaderPreparedStore.PreparedTilePage?): Boolean {
        if (page == null || page.pageWidth <= 0 || page.pageHeight <= 0 || page.tiles.isEmpty()) {
            return false
        }
        var expectedTop = 0
        for (tile in page.tiles) {
            if (tile.sourceWidth != page.pageWidth || tile.sourceHeight != page.pageHeight ||
                tile.sourceTop != expectedTop || tile.sourceBottom <= tile.sourceTop ||
                tile.sourceBottom > page.pageHeight || tile.bitmap.isRecycled ||
                !tile.hasExactSourcePixelStorage() ||
                tile.bitmap.config != Bitmap.Config.ARGB_8888 || tile.bitmap.isMutable
            ) {
                return false
            }
            expectedTop = tile.sourceBottom
        }
        return expectedTop == page.pageHeight
    }

    /**
     * Starts GPU preparation only for the real initial viewport plus the required 1.5-viewport
     * forward runway (2.5 physical viewports total from the current scroll position).
     * Every remaining page is already decoded and drawable in [pages]; this method neither draws
     * offscreen content nor waits for upload completion.
     */
    fun prepareInitialSoftwareRunway(): PrepareStats {
        val plan = synchronized(stateLock) {
            if (pages.isEmpty() || width <= 0 || height <= 0 ||
                !isAttachedToWindow || !isHardwareAccelerated
            ) {
                return PrepareStats(0, 0L, -1, -1, 0L)
            }
            rebuildLayoutLocked()
            val prepareTop = scrollOffset
            val requiredAheadPx = ceil(
                INITIAL_SOFTWARE_PREPARE_AHEAD_VIEWPORTS * height.toFloat()
            ).toInt()
            val prepareBottom = min(
                contentHeight,
                prepareTop + height.toFloat() + requiredAheadPx.toFloat()
            )
            SoftwarePreparePlan(
                targets = softwarePrepareTargetsLocked(prepareTop, prepareBottom),
                viewportWidth = width,
                viewportHeight = height,
                scrollOffset = prepareTop,
                anchorPage = firstVisiblePageLocked(prepareTop),
                prepareThroughY = ceil(prepareBottom).toInt(),
                visualGeneration = visualGeneration
            )
        }

        val startedAt = SystemClock.elapsedRealtimeNanos()
        var bytes = 0L
        var successCount = 0
        for (target in plan.targets) {
            val bitmap = target.bitmap
            if (bitmap.isRecycled || bitmap.isHardwareConfigCompat()) continue
            try {
                bitmap.prepareToDraw()
                bytes += try {
                    bitmap.allocationByteCount.toLong()
                } catch (_: Throwable) {
                    bitmap.byteCount.toLong()
                }
                successCount++
            } catch (_: Throwable) {
                // The caller receives stillValid=false; this is a hint, never an upload fence.
            }
        }
        val elapsedMicros = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000L
        val stillValid = successCount == plan.targets.size && plan.targets.isNotEmpty() &&
            synchronized(stateLock) { softwarePreparePlanStillValidLocked(plan) }
        return PrepareStats(
            bitmapCount = successCount,
            bytes = bytes,
            firstPage = plan.targets.firstOrNull()?.page ?: -1,
            lastPage = plan.targets.lastOrNull()?.page ?: -1,
            elapsedMicros = elapsedMicros,
            targetCount = plan.targets.size,
            successCount = successCount,
            preparedThroughY = if (stillValid) plan.prepareThroughY else -1,
            visualGeneration = plan.visualGeneration,
            stillValid = stillValid
        )
    }

    /** Must be called with [stateLock] held after layout has been rebuilt. */
    private fun softwarePrepareTargetsLocked(
        prepareTop: Float,
        prepareBottom: Float
    ): List<SoftwarePrepareTarget> {
        val selected = ArrayList<SoftwarePrepareTarget>(12)
        fun addIdentityDeduped(page: Int, bitmap: Bitmap) {
            if (bitmap.isRecycled || bitmap.isHardwareConfigCompat()) return
            if (selected.any { it.bitmap === bitmap }) return
            selected.add(SoftwarePrepareTarget(page, bitmap))
        }

        var index = firstVisiblePageLocked(prepareTop)
        while (index > 0 && pageTopOrElseLocked(index, 0f) > prepareTop) index--
        while (index < pages.size) {
            val page = pages[index]
            val top = pageTopOrElseLocked(index, 0f)
            val bottom = top + pageDrawHeightLocked(page)
            if (top >= prepareBottom) break
            val bitmap = page.bitmap
            if (bottom > prepareTop && bitmap != null) {
                addIdentityDeduped(index, bitmap)
            } else if (bottom > prepareTop && page.tiles.isNotEmpty() && page.height > 0) {
                val pageScale = pageDrawHeightLocked(page) / page.height.toFloat()
                if (pageScale > 0f) {
                    val sourceTop = ((max(prepareTop, top) - top) / pageScale)
                        .coerceAtLeast(0f)
                    val sourceBottom = ((min(prepareBottom, bottom) - top) / pageScale)
                        .coerceAtMost(page.height.toFloat())
                    for (tile in page.tiles) {
                        if (tile.sourceBottom <= sourceTop) continue
                        if (tile.sourceTop >= sourceBottom) break
                        addIdentityDeduped(index, tile.bitmap)
                    }
                }
            }
            index++
        }
        return selected
    }

    /** Must be called with [stateLock] held after layout has been rebuilt. */
    private fun collectUnpreparedHwuiRunwayLocked(aheadViewports: Float): List<Bitmap> {
        if (rollingNativeHandle != 0L || width <= 0 || height <= 0 || pages.isEmpty()) {
            return emptyList()
        }
        val prepareBottom = min(
            contentHeight,
            scrollOffset + height.toFloat() * (1f + aheadViewports)
        )
        val fresh = ArrayList<Bitmap>(12)
        for (target in softwarePrepareTargetsLocked(scrollOffset, prepareBottom)) {
            val bitmap = target.bitmap
            val key = (traversalStructureEpoch shl 32) xor
                (System.identityHashCode(bitmap).toLong() and 0xffff_ffffL)
            if (hwuiPreparedBitmapKeys.add(key)) fresh.add(bitmap)
        }
        return fresh
    }

    /** A hint only: it never gates input, performs I/O, or substitutes a lower-quality asset. */
    private fun prepareHwuiForwardRunwayLocked() {
        if (rollingNativeHandle != 0L) return
        rebuildLayoutLocked()
        for (bitmap in collectUnpreparedHwuiRunwayLocked(HWUI_FORWARD_PREPARE_VIEWPORTS)) {
            if (!bitmap.isRecycled && !bitmap.isHardwareConfigCompat()) {
                runCatching { bitmap.prepareToDraw() }
            }
        }
    }

    /** Must be called with [stateLock] held. */
    private fun softwarePreparePlanStillValidLocked(plan: SoftwarePreparePlan): Boolean {
        if (visualGeneration != plan.visualGeneration || width != plan.viewportWidth ||
            height != plan.viewportHeight || scrollOffset.toBits() != plan.scrollOffset.toBits()
        ) {
            return false
        }
        rebuildLayoutLocked()
        if (firstVisiblePageLocked(scrollOffset) != plan.anchorPage) return false
        val requiredAheadPx = ceil(
            INITIAL_SOFTWARE_PREPARE_AHEAD_VIEWPORTS * height.toFloat()
        ).toInt()
        val currentBottom = min(
            contentHeight,
            scrollOffset + height.toFloat() + requiredAheadPx.toFloat()
        )
        if (ceil(currentBottom).toInt() != plan.prepareThroughY) return false
        val currentTargets = softwarePrepareTargetsLocked(scrollOffset, currentBottom)
        if (currentTargets.size != plan.targets.size) return false
        for (index in currentTargets.indices) {
            val expected = plan.targets[index]
            val current = currentTargets[index]
            if (expected.page != current.page || expected.bitmap !== current.bitmap) return false
        }
        return true
    }

    fun hasPageDrawable(index: Int): Boolean {
        return synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return@synchronized false
            pageHasDrawableForReadinessLocked(page)
        }
    }

    /**
     * Returns current, generation-owned original-pixel authority for one page. A generic
     * drawable check is deliberately insufficient here: a recycled tile, a proof-less preview,
     * or a drawable left by an older manifest must not suppress the canonical source retry.
     */
    fun hasAuthoritativeOriginalPage(index: Int): Boolean {
        return synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return@synchronized false
            usableAuthoritativeOriginalTilePage(
                page.width,
                page.height,
                page.tiles,
                page.originalProof
            )
        }
    }

    fun hasAuthoritativeOriginalTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>
    ): Boolean {
        return synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return@synchronized false
            page.width == pageWidth && page.height == pageHeight &&
                hasSameTilesIdentity(page, tiles) &&
                usableAuthoritativeOriginalTilePage(
                    page.width,
                    page.height,
                    page.tiles,
                    page.originalProof
                )
        }
    }

    /** Current-state proof used by strict source demand; historical readiness is insufficient. */
    fun hasCompleteAuthoritativeOriginalScene(expectedPageCount: Int): Boolean {
        if (expectedPageCount <= 0) return false
        return synchronized(stateLock) {
            pages.size == expectedPageCount && pages.all { page ->
                usableAuthoritativeOriginalTilePage(
                    page.width,
                    page.height,
                    page.tiles,
                    page.originalProof
                )
            }
        }
    }

    /** Must be called with [stateLock] held. */
    private fun pageHasDrawableForReadinessLocked(page: Page): Boolean {
        return if (inlineRealPixelsOnly) {
            completeFullQualityDrawableSourceWidth(page, requireOriginalProof = true) > 0
        } else {
            page.bitmap?.let { !it.isRecycled } == true ||
                page.tiles.any { !it.bitmap.isRecycled }
        }
    }

    fun setPageProofBitmap(index: Int, bitmap: Bitmap) {
        synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (bitmap.isRecycled) return
            val oldHeight = pageDrawHeightLocked(page)
            val proofWidth = max(1, width)
            val proofHeight = max(1, oldHeight.toInt())
            val pendingWidth = max(1, bitmap.width)
            val pendingHeight = max(1, bitmap.height)
            if (isProofBitmapDeliveryNoOpLocked(
                    page,
                    bitmap,
                    proofWidth,
                    proofHeight,
                    pendingWidth,
                    pendingHeight
                )
            ) {
                return
            }
            invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
            page.bitmap = bitmap
            page.tiles = emptyList()
            page.width = proofWidth
            page.height = proofHeight
            page.pendingResolveType = PENDING_SIZE
            page.pendingBitmap = null
            page.pendingTiles = emptyList()
            page.pendingWidth = pendingWidth
            page.pendingHeight = pendingHeight
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            maybeReleasePrependedReadyHoldLocked(index)
            markBlockedDrawableResolvedLocked(index)
            schedulePendingResolveRetryLocked(markLayoutDirty = false)
            if (shouldRenderPageResolveNowLocked(index)) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("proof_bitmap", index)
        }
    }

    fun setInitialContinuousPageBitmap(index: Int, bitmap: Bitmap) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (bitmap.isRecycled) return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val layoutBounds = layoutBoundsForDrawableLocked(
                page,
                max(1, bitmap.width),
                max(1, bitmap.height)
            )
            if (isSettledBitmapDeliveryNoOpLocked(
                    page,
                    bitmap,
                    layoutBounds.first,
                    layoutBounds.second
                )
            ) {
                return@synchronized null
            }
            invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
            page.bitmap = bitmap
            page.tiles = emptyList()
            page.width = layoutBounds.first
            page.height = layoutBounds.second
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            maybeReleasePrependedReadyHoldLocked(index)
            markBlockedDrawableResolvedLocked(index)
            val newHeight = pageDrawHeightLocked(page)
            updatePageHeightDeltaLocked(index, newHeight - oldHeight)
            if (shouldRenderPageResolveNowLocked(index)) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("initial_bitmap", index)
        }
        dispatchWindowRequest(request)
    }

    fun setPageProofTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return
            val page = pages.getOrNull(index) ?: return
            if (tiles.isEmpty() || tiles.any { it.bitmap.isRecycled }) return
            val oldHeight = pageDrawHeightLocked(page)
            val proofWidth = max(1, width)
            val proofHeight = max(1, oldHeight.toInt())
            val pendingWidth = max(1, pageWidth)
            val pendingHeight = max(1, pageHeight)
            if (isProofTilesDeliveryNoOpLocked(
                    page,
                    tiles,
                    proofWidth,
                    proofHeight,
                    pendingWidth,
                    pendingHeight
                )
            ) {
                return
            }
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (hasCurrentDrawable && shouldDeferDrawableReplacementLocked()) {
                page.pendingResolveType = PENDING_TILES
                page.pendingBitmap = null
                page.pendingTiles = tiles
                page.pendingWidth = pendingWidth
                page.pendingHeight = pendingHeight
                page.loading = false
                page.errorText = null
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return
            }
            invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
            page.bitmap = null
            page.tiles = tiles
            page.width = proofWidth
            page.height = proofHeight
            page.pendingResolveType = PENDING_SIZE
            page.pendingBitmap = null
            page.pendingTiles = emptyList()
            page.pendingWidth = pendingWidth
            page.pendingHeight = pendingHeight
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            maybeReleasePrependedReadyHoldLocked(index)
            markBlockedDrawableResolvedLocked(index)
            schedulePendingResolveRetryLocked(markLayoutDirty = false)
            if (shouldRenderPageResolveNowLocked(index)) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("proof_tiles", index)
        }
    }

    fun setInitialContinuousPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return
            val page = pages.getOrNull(index) ?: return
            if (tiles.isEmpty() || tiles.any { it.bitmap.isRecycled }) return
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val layoutBounds = layoutBoundsForDrawableLocked(
                page,
                max(1, pageWidth),
                max(1, pageHeight)
            )
            if (isSettledTilesDeliveryNoOpLocked(
                    page,
                    tiles,
                    layoutBounds.first,
                    layoutBounds.second
                )
            ) {
                return@synchronized null
            }
            invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
            page.bitmap = null
            page.tiles = tiles
            page.width = layoutBounds.first
            page.height = layoutBounds.second
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            maybeReleasePrependedReadyHoldLocked(index)
            markBlockedDrawableResolvedLocked(index)
            val newHeight = pageDrawHeightLocked(page)
            updatePageHeightDeltaLocked(index, newHeight - oldHeight)
            if (shouldRenderPageResolveNowLocked(index)) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("initial_tiles", index)
        }
        dispatchWindowRequest(request)
    }

    /**
     * Atomically installs a real authoritative-original tile page delivered after partial
     * adoption. Unlike the legacy setters, this carries immutable provenance into strict runway
     * and visible-coverage accounting and applies the proven source geometry immediately.
     */
    fun setPageAuthoritativeOriginalTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        proof: ReaderPreparedStore.PreparedOriginalProof
    ): Boolean {
        if (!usableAuthoritativeOriginalTilePage(pageWidth, pageHeight, tiles, proof)) {
            Log.e(
                TAG,
                "reader_authoritative_tiles_reject page=$index reason=unusable," +
                    "pageSize=${pageWidth}x$pageHeight,proofSize=${proof.originalWidth}x${proof.originalHeight}," +
                    "tiles=${tiles.size}"
            )
            return false
        }
        var installed = false
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) {
                Log.e(TAG, "reader_authoritative_tiles_reject page=$index reason=strip_authority")
                return false
            }
            val page = pages.getOrNull(index)
            if (page == null) {
                Log.e(TAG, "reader_authoritative_tiles_reject page=$index reason=page_missing,count=${pages.size}")
                return false
            }
            if (usableAuthoritativeOriginalTilePage(
                    page.width,
                    page.height,
                    page.tiles,
                    page.originalProof
                ) && !hasSameTilesIdentity(page, tiles)
            ) {
                Log.d(
                    TAG,
                    "reader_authoritative_tiles_reject page=$index reason=late_duplicate_original"
                )
                return false
            }
            if (!usableAuthoritativeOriginalTilePage(pageWidth, pageHeight, tiles, proof)) {
                return@synchronized null
            }
            val targetWidth = max(1, pageWidth)
            val targetHeight = max(1, pageHeight)
            postNativeTexturePrewarmLocked(index, tiles)
            if (isSettledTilesDeliveryNoOpLocked(page, tiles, targetWidth, targetHeight)) {
                page.originalProof = proof
                lastVisibleCoverageSnapshot = null
                installed = true
                return@synchronized null
            }

            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(targetWidth, targetHeight)
            invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
            page.bitmap = null
            page.tiles = tiles
            page.width = targetWidth
            page.height = targetHeight
            page.originalProof = proof
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            markBlockedDrawableResolvedLocked(index)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(
                viewportAnchor,
                "page_authoritative_original_tiles",
                index,
                oldHeight,
                newHeight
            )
            maybeReleasePrependedReadyHoldLocked(index)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            installed = true
            if (shouldRenderPageResolveNowLocked(index)) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            if (installed) {
                reevaluateDeferredSurfaceRevealLocked("authoritative_tiles", index)
            }
        }
        dispatchWindowRequest(request)
        return installed
    }

    data class AuthoritativeTileInstall(
        val index: Int,
        val pageWidth: Int,
        val pageHeight: Int,
        val tiles: List<ReaderTile>,
        val proof: ReaderPreparedStore.PreparedOriginalProof
    )

    data class InstallBatchResult(val installedPages: Set<Int>, val rejectedPages: Set<Int>)

    /** Installs one contiguous physical batch with one state lock and one frame request. */
    fun installAuthoritativeTileBatch(
        commands: List<AuthoritativeTileInstall>
    ): InstallBatchResult {
        if (commands.isEmpty()) return InstallBatchResult(emptySet(), emptySet())
        val installed = LinkedHashSet<Int>()
        val rejected = LinkedHashSet<Int>()
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) {
                commands.forEach { rejected.add(it.index) }
                return@synchronized null
            }
            rebuildLayoutLocked()
            val viewportAnchor = progressPositionLocked()
            var firstChangedIndex = -1
            var firstOldHeight = 0f
            var firstNewHeight = 0f
            for (command in commands.sortedBy { it.index }) {
                if (!usableAuthoritativeOriginalTilePage(
                        command.pageWidth,
                        command.pageHeight,
                        command.tiles,
                        command.proof
                    )
                ) {
                    rejected.add(command.index)
                    continue
                }
                val page = pages.getOrNull(command.index)
                if (page == null) {
                    rejected.add(command.index)
                    continue
                }
                if (usableAuthoritativeOriginalTilePage(
                        page.width,
                        page.height,
                        page.tiles,
                        page.originalProof
                    ) && !hasSameTilesIdentity(page, command.tiles)
                ) {
                    Log.d(
                        TAG,
                        "reader_authoritative_tiles_reject page=${command.index} " +
                            "reason=late_duplicate_original_batch"
                    )
                    rejected.add(command.index)
                    continue
                }
                val targetWidth = max(1, command.pageWidth)
                val targetHeight = max(1, command.pageHeight)
                postNativeTexturePrewarmLocked(command.index, command.tiles)
                if (isSettledTilesDeliveryNoOpLocked(page, command.tiles, targetWidth, targetHeight)) {
                    page.originalProof = command.proof
                    installed.add(command.index)
                    continue
                }
                val oldHeight = pageDrawHeightLocked(page)
                val oldTop = pageTopOrElseLocked(command.index, 0f)
                val newHeight = resolvedPageDrawHeightLocked(targetWidth, targetHeight)
                if (firstChangedIndex < 0) {
                    firstChangedIndex = command.index
                    firstOldHeight = oldHeight
                    firstNewHeight = newHeight
                }
                invalidateRetainedPageNodeIfTilesChanged(command.index, page, command.tiles)
                page.bitmap = null
                page.tiles = command.tiles
                page.width = targetWidth
                page.height = targetHeight
                page.originalProof = command.proof
                clearPendingResolveLocked(page)
                noteResolvedPageAspectLocked(page.width, page.height)
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(command.index)
                applyPageHeightChangeLocked(command.index, oldTop, oldHeight, newHeight - oldHeight)
                maybeReleasePrependedReadyHoldLocked(command.index)
                installed.add(command.index)
            }
            if (firstChangedIndex >= 0) {
                restoreViewportAnchorLocked(
                    viewportAnchor,
                    "authoritative_tile_batch",
                    firstChangedIndex,
                    firstOldHeight,
                    firstNewHeight
                )
                applyLockedRestorePositionLocked()
                clampScrollLocked()
            }
            if (installed.isNotEmpty()) {
                refreshVisibleCoverageAfterDrawableLocked(installed.last())
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            installed.lastOrNull()?.let { lastInstalled ->
                reevaluateDeferredSurfaceRevealLocked(
                    "authoritative_tile_batch",
                    lastInstalled,
                )
            }
        }
        dispatchWindowRequest(request)
        return InstallBatchResult(installed, rejected)
    }

    fun setPageTiles(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>) {
        setPageTiles(index, pageWidth, pageHeight, tiles, forceImmediateGeometry = false)
    }

    fun setPageTiles(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        forceImmediateGeometry: Boolean
    ) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return@synchronized null
            val page = pages.getOrNull(index) ?: return
            // This legacy API carries no provenance. Callers that possess an original proof must
            // use setPageAuthoritativeOriginalTiles so an old proof can never bless new pixels.
            if (page.originalProof != null) {
                page.originalProof = null
                lastVisibleCoverageSnapshot = null
            }
            val targetWidth = max(1, pageWidth)
            val targetHeight = max(1, pageHeight)
            postNativeTexturePrewarmLocked(index, tiles)
            if (isSettledTilesDeliveryNoOpLocked(page, tiles, targetWidth, targetHeight)) {
                return@synchronized null
            }
            val hasCurrentDrawableBeforeLayout = page.bitmap != null || page.tiles.isNotEmpty()
            if (!forceImmediateGeometry && hasCurrentDrawableBeforeLayout && shouldDeferDrawableReplacementLocked()) {
                page.pendingResolveType = PENDING_TILES
                page.pendingBitmap = null
                page.pendingTiles = tiles
                page.pendingWidth = targetWidth
                page.pendingHeight = targetHeight
                page.loading = false
                page.errorText = null
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(pageWidth, pageHeight)
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (!forceImmediateGeometry && !hasCurrentDrawable &&
                shouldFreezeOffscreenDrawableInstallLocked(index, oldTop, oldHeight)
            ) {
                invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
                page.bitmap = null
                page.tiles = tiles
                page.width = max(1, width)
                page.height = max(1, oldHeight.toInt())
                page.pendingResolveType = PENDING_SIZE
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                maybeReleasePrependedReadyHoldLocked(index)
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                shouldFreezeGeometryForNextBoundaryLocked(index, oldTop, oldHeight, newHeight)
            ) {
                invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
                page.bitmap = null
                page.tiles = tiles
                page.width = max(1, width)
                page.height = max(1, oldHeight.toInt())
                page.pendingResolveType = PENDING_SIZE
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                maybeReleasePrependedReadyHoldLocked(index)
                val renderNow = shouldRenderPageResolveNowLocked(index)
                if (renderNow) {
                    refreshVisibleCoverageAfterDrawableLocked(index)
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                    return@synchronized windowRequestLocked(lastBusy)
                }
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                shouldDeferInitialDrawableSizeLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable)
            ) {
                invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
                page.bitmap = null
                page.tiles = tiles
                page.width = max(1, width)
                page.height = max(1, oldHeight.toInt())
                page.pendingResolveType = PENDING_SIZE
                page.pendingBitmap = null
                page.pendingTiles = emptyList()
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                page.loading = false
                page.cardText = null
                page.errorText = null
                deferInitialEmptyDraw = false
                markBlockedDrawableResolvedLocked(index)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                maybeReleasePrependedReadyHoldLocked(index)
                val renderNow = shouldRenderPageResolveNowLocked(index)
                if (renderNow) {
                    refreshVisibleCoverageAfterDrawableLocked(index)
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                    return@synchronized windowRequestLocked(lastBusy)
                }
                return@synchronized null
            }
            if (!forceImmediateGeometry && hasCurrentDrawable && shouldDeferDrawableReplacementLocked()) {
                page.pendingResolveType = PENDING_TILES
                page.pendingBitmap = null
                page.pendingTiles = tiles
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                page.loading = false
                page.errorText = null
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            if (!forceImmediateGeometry &&
                shouldDeferHeightChangingResolveLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable)
            ) {
                if (hasCurrentDrawable) {
                    page.pendingResolveType = PENDING_TILES
                    page.pendingTiles = tiles
                    page.loading = false
                    page.errorText = null
                } else {
                    page.pendingResolveType = PENDING_TILES
                    page.pendingTiles = tiles
                }
                page.pendingBitmap = null
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                deferInitialEmptyDraw = false
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
            page.bitmap = null
            page.tiles = tiles
            page.width = targetWidth
            page.height = targetHeight
            clearPendingResolveLocked(page)
            noteResolvedPageAspectLocked(page.width, page.height)
            page.loading = false
            page.cardText = null
            page.errorText = null
            deferInitialEmptyDraw = false
            markBlockedDrawableResolvedLocked(index)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_tiles", index, oldHeight, newHeight)
            maybeReleasePrependedReadyHoldLocked(index)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            val renderNow = shouldRenderPageResolveNowLocked(index)
            if (renderNow) {
                refreshVisibleCoverageAfterDrawableLocked(index)
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("tiles", index)
        }
        dispatchWindowRequest(request)
    }

    fun clearPageBitmap(index: Int) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return@synchronized null
            val page = pages.getOrNull(index) ?: return
            invalidateRetainedPageNodeStateLocked(index)
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            page.bitmap = null
            page.tiles = emptyList()
            page.loading = false
            page.cardText = null
            page.errorText = null
            clearPendingResolveLocked(page)
            val newHeight = pageDrawHeightLocked(page)
            val heightDelta = newHeight - oldHeight
            if (abs(heightDelta) > HEIGHT_CHANGE_EPSILON_PX) {
                applyPageHeightChangeLocked(index, oldTop, oldHeight, heightDelta)
                restoreViewportAnchorLocked(viewportAnchor, "page_clear", index, oldHeight, newHeight)
                applyLockedRestorePositionLocked()
                clampScrollLocked()
            }
            if (shouldRenderPageResolveNowLocked(index)) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                if (abs(heightDelta) > HEIGHT_CHANGE_EPSILON_PX) {
                    Log.d(
                        TAG,
                        "reader_page_clear_height index=$index old=${oldHeight.toInt()} new=${newHeight.toInt()} " +
                            "offset=${scrollOffset.toInt()} lastBusy=$lastBusy"
                    )
                }
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        dispatchWindowRequest(request)
    }

    fun clearAllPages() {
        synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return
            clearRetainedPageNodesStateLocked()
            clearPreparedStartAnchorLocked()
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
            }
            hasDrawnContentFrame = false
            pendingBlockedForwardRequest = null
            blockedForwardDispatchPosted = false
            lastBlockedForwardPage = -1
            lastBlockedForwardRequestAtMs = 0L
            layoutDirty = true
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun stopRenderingAndClearPages() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return
            noStateRetryPosted = false
            clearRetainedPageNodesStateLocked()
            clearPreparedStartAnchorLocked()
            renderRunning = false
            renderRequested = false
            clearFramePipeLocked(preserveDirty = false)
            clearInputStateLocked()
            resetActiveFrameStatsLocked()
            for (page in pages) {
                page.bitmap = null
                page.tiles = emptyList()
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
            }
            hasDrawnContentFrame = false
            pendingBlockedForwardRequest = null
            blockedForwardDispatchPosted = false
            lastBlockedForwardPage = -1
            lastBlockedForwardRequestAtMs = 0L
            layoutDirty = true
            stopRenderThreadLocked()
            stateLock.notifyAll()
        }
    }

    fun setCommittedPageIdentities(
        startIndex: Int,
        identities: List<CommittedPageIdentity?>
    ) {
        if (startIndex < 0 || identities.isEmpty()) return
        synchronized(stateLock) {
            identities.forEachIndexed { offset, identity ->
                val index = startIndex + offset
                val page = pages.getOrNull(index) ?: return@forEachIndexed
                page.committedIdentity = identity?.copy(displayPageIndex = index)
            }
            if (directWifiExpandedNativeTextureRunway) {
                nativeTexturePrewarmDirty = true
                requestResidentNativeTexturePrewarmLocked()
            }
            // Exact pixels and their immutable identity arrive on independent actor callbacks.
            // Re-evaluate here as well as at pixel installation so either arrival order can
            // release a fully proven resumed viewport.
            reevaluateDeferredSurfaceRevealLocked(
                "identity",
                startIndex + identities.lastIndex,
            )
        }
    }

    fun setPageBounds(index: Int, pageWidth: Int, pageHeight: Int) {
        val request = synchronized(stateLock) {
            if (stripAuthorityToken != 0L) return@synchronized null
            val page = pages.getOrNull(index) ?: return
            if (page.cardText != null || page.errorText != null || pageWidth <= 0 || pageHeight <= 0) return
            invalidatePreparedRenderSceneStateLocked()
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val newHeight = resolvedPageDrawHeightLocked(pageWidth, pageHeight)
            val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
            if (hasCurrentDrawable && abs(newHeight - oldHeight) <= ACTIVE_DRAWABLE_BOUNDS_DELTA_SUPPRESS_PX) {
                if (page.pendingResolveType == PENDING_BOUNDS || page.pendingResolveType == PENDING_SIZE) {
                    clearPendingResolveLocked(page)
                }
                return@synchronized null
            }
            if (!hasCurrentDrawable && shouldDeferOffscreenBoundsOnlyResolveLocked(oldTop)) {
                if (page.pendingResolveType != PENDING_BITMAP && page.pendingResolveType != PENDING_TILES) {
                    page.pendingResolveType = PENDING_BOUNDS
                    page.pendingBitmap = null
                    page.pendingTiles = emptyList()
                }
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            if (
                shouldFreezeGeometryForNextBoundaryLocked(index, oldTop, oldHeight, newHeight) ||
                shouldDeferHeightChangingResolveLocked(oldTop, oldHeight, newHeight, hasCurrentDrawable) ||
                (!hasCurrentDrawable && shouldDeferInitialDrawableSizeLocked(oldTop, oldHeight, newHeight, false))
            ) {
                if (page.pendingResolveType != PENDING_BITMAP && page.pendingResolveType != PENDING_TILES) {
                    page.pendingResolveType = PENDING_BOUNDS
                    page.pendingBitmap = null
                    page.pendingTiles = emptyList()
                }
                page.pendingWidth = max(1, pageWidth)
                page.pendingHeight = max(1, pageHeight)
                schedulePendingResolveRetryLocked(markLayoutDirty = false)
                return@synchronized null
            }
            page.width = max(1, pageWidth)
            page.height = max(1, pageHeight)
            if (page.pendingResolveType == PENDING_BOUNDS || page.pendingResolveType == PENDING_SIZE) {
                clearPendingResolveLocked(page)
            } else if (page.pendingResolveType == PENDING_BITMAP || page.pendingResolveType == PENDING_TILES) {
                page.pendingWidth = page.width
                page.pendingHeight = page.height
            }
            noteResolvedPageAspectLocked(page.width, page.height)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_bounds", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            if ((hasCurrentDrawable || (!shouldSuppressInitialEmptyRenderLocked()
                    && !shouldDeferInitialEmptyDrawLocked()))
                && shouldRenderPageResolveNowLocked(index)
            ) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
                windowRequestLocked(lastBusy)
            } else {
                null
            }
        }
        dispatchWindowRequest(request)
    }

    fun setPageCard(index: Int, title: String) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            invalidateRetainedPageNodeStateLocked(index)
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            val cardWidth = max(1, width)
            val cardHeight = TRANSITION_CARD_PAGE_HEIGHT_PX.roundToInt()
            // The rolling native Surface renderer submits only Bitmap-backed items. Keep that
            // renderer backing separate from the real image slot: lifecycle/provenance checks
            // must never mistake a UI card for an original work image.
            page.bitmap = null
            page.cardBitmap = createTransitionCardBitmap(cardWidth, title)
            page.tiles = emptyList()
            page.originalProof = null
            page.width = cardWidth
            page.height = cardHeight
            page.loading = false
            page.cardText = title
            page.errorText = null
            clearPendingResolveLocked(page)
            deferInitialEmptyDraw = false
            val newHeight = pageDrawHeightLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_card", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        synchronized(stateLock) {
            reevaluateDeferredSurfaceRevealLocked("card", index)
        }
        dispatchWindowRequest(request)
    }

    private fun createTransitionCardBitmap(cardWidth: Int, title: String): Bitmap {
        val safeWidth = max(1, cardWidth)
        val safeHeight = TRANSITION_CARD_PAGE_HEIGHT_PX.roundToInt()
        return Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            drawTransitionCardText(
                canvas = canvas,
                width = safeWidth,
                centerY = safeHeight / 2f,
                title = title,
                labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }
    }

    private fun drawTransitionCardText(
        canvas: Canvas,
        width: Int,
        centerY: Float,
        title: String,
        labelPaint: Paint
    ) {
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 38f
        labelPaint.color = Color.rgb(190, 190, 190)
        canvas.drawText("회차 전환", width / 2f, centerY - 28f, labelPaint)
        if (title.isNotEmpty()) {
            labelPaint.textSize = 54f
            labelPaint.color = Color.WHITE
            canvas.drawText(title, width / 2f, centerY + 40f, labelPaint)
        }
        labelPaint.textSize = 34f
        labelPaint.color = Color.rgb(190, 190, 190)
    }

    internal fun pageCardBackingBitmapForTest(index: Int): Bitmap? =
        synchronized(stateLock) {
            pages.getOrNull(index)?.takeIf { it.cardText != null }?.cardBitmap
        }

    internal fun pageImageBitmapPresentForTest(index: Int): Boolean =
        synchronized(stateLock) {
            pages.getOrNull(index)?.bitmap != null
        }

    fun setPageError(index: Int, message: String) {
        val request = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            if (page.bitmap != null || page.tiles.isNotEmpty() || page.pendingBitmap != null || page.pendingTiles.isNotEmpty()) {
                Log.d(TAG, "reader_page_error_skip_drawable index=$index message=$message")
                return@synchronized null
            }
            if (inlineRealPixelsOnly) {
                // Keep the unresolved slot and its geometry honest. Error UI belongs outside the
                // pixel surface and must never cover missing source pixels.
                page.loading = false
                page.cardText = null
                page.errorText = null
                clearPendingResolveLocked(page)
                if (shouldRenderPageResolveNowLocked(index)) {
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                    return@synchronized windowRequestLocked(lastBusy)
                }
                return@synchronized null
            }
            invalidateRetainedPageNodeStateLocked(index)
            rebuildLayoutLocked()
            val oldHeight = pageDrawHeightLocked(page)
            val oldTop = pageTopOrElseLocked(index, 0f)
            val viewportAnchor = progressPositionLocked()
            page.bitmap = null
            page.tiles = emptyList()
            page.width = width
            page.height = max(1, (height * 0.38f).toInt())
            page.loading = false
            page.cardText = null
            page.errorText = message
            clearPendingResolveLocked(page)
            deferInitialEmptyDraw = false
            val newHeight = pageDrawHeightLocked(page)
            applyPageHeightChangeLocked(index, oldTop, oldHeight, newHeight - oldHeight)
            restoreViewportAnchorLocked(viewportAnchor, "page_error", index, oldHeight, newHeight)
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(lastBusy)
        }
        dispatchWindowRequest(request)
    }

    fun scrollToPage(index: Int) {
        scrollToPage(index, 0)
    }

    fun scrollToPage(index: Int, offset: Int) {
        val request = synchronized(stateLock) {
            val target = index.coerceIn(0, pages.lastIndex)
            rebuildLayoutLocked()
            activeScrollerOffsetShift = 0f
            setScrollOffsetLocked(pageTopOrElseLocked(target, 0f) - offset)
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
            windowRequestLocked(false)
        }
        dispatchWindowRequest(request)
    }

    fun lockRestoredPageOffset(index: Int, offset: Int) {
        val request = synchronized(stateLock) {
            if (index !in 0 until pages.size) return
            val forwardOnlyResume = directWifiForwardOnlyInitialResumeEnabled &&
                surfaceAttachmentDeferredUntilActualPixels && !hasDrawnContentFrame
            if (forwardOnlyResume) {
                val nextOffset = min(0, offset)
                val resumeIdentityChanged =
                    directWifiForwardOnlyInitialResumePage != index ||
                        directWifiForwardOnlyInitialResumeOffset != nextOffset
                directWifiForwardOnlyInitialResumePage = index
                directWifiForwardOnlyInitialResumeOffset = nextOffset
                directWifiForwardOnlyInitialResumeRevealQualified = false
                if (resumeIdentityChanged) {
                    directWifiForwardOnlyTerminalTailRevealQualified = false
                }
            }
            lockedRestorePage = index
            // A positive offset exposes predecessor pixels above this page. The forward-only UX
            // deliberately did not request those pages, so start the exact resumed tail at the
            // top instead of manufacturing a placeholder viewport. The restored page can be the
            // first of several exact tail pages; requiring pages.lastIndex would strand that tail.
            lockedRestoreOffset = if (forwardOnlyResume) {
                directWifiForwardOnlyInitialResumeOffset
            } else {
                offset
            }
            lockedRestoreUntilMs = SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
            applyLockedRestorePositionLocked()
            clampScrollLocked()
            lastAnchor = -1
            renderRequested = !shouldSuppressInitialEmptyRenderLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            if (renderRequested) windowRequestLocked(false) else null
        }
        dispatchWindowRequest(request)
    }

    fun holdInitialRestoreRender(index: Int) {
        synchronized(stateLock) {
            if (index !in 0 until pages.size) return
            clearInitialRenderHoldLocked()
            renderRequested = true
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    fun currentProgressPosition(): ProgressPosition? {
        return synchronized(stateLock) {
            progressPositionLocked()
        }
    }

    fun currentScrollPositionSnapshot(): ScrollPositionSnapshot? {
        return synchronized(stateLock) {
            val progress = progressPositionLocked() ?: return@synchronized null
            val busy = lastBusy || pointerDown || dragging || !scroller.isFinished
            val maxScroll = maxScrollLocked().toInt()
            ScrollPositionSnapshot(progress.page, progress.offset, scrollOffset.toInt(), contentHeight.toInt(), maxScroll, busy)
        }
    }

    /**
     * Returns true only when the supplied committed-frame scroll coordinate exposed the physical
     * bottom of [displayPageIndex]. A continuously appended reader cannot use the surface's global
     * max scroll as an episode boundary, and merely seeing the final page is insufficient for a
     * tall webtoon image whose bottom may still be several viewports away.
     */
    fun isPageBottomReachedAtScroll(
        displayPageIndex: Int,
        committedScrollOffsetPx: Float,
    ): Boolean {
        return synchronized(stateLock) {
            if (!committedScrollOffsetPx.isFinite() || height <= 0 || displayPageIndex !in pages.indices) {
                return@synchronized false
            }
            rebuildLayoutLocked()
            val page = pages[displayPageIndex]
            val pageHeight = pageDrawHeightLocked(page)
            if (pageHeight <= 0f) return@synchronized false
            val pageBottom = pageTopOrElseLocked(displayPageIndex, Float.MAX_VALUE) + pageHeight
            val committedViewportBottom = committedScrollOffsetPx + height.toFloat()
            committedViewportBottom + BOUNDARY_EPSILON_PX >= pageBottom
        }
    }

    fun testScrollByPixels(deltaPx: Float) {
        val request = synchronized(stateLock) {
            statsAwaitingFirstInput = false
            rebuildLayoutLocked()
            val maxScroll = maxScrollLocked().coerceAtLeast(0f)
            lastScrollInteractionMs = SystemClock.uptimeMillis()
            activateScrollStatsLocked(lastScrollInteractionMs)
            setScrollOffsetLocked((scrollOffset + deltaPx).coerceIn(0f, maxScroll))
            scroller.forceFinished(true)
            renderRequested = true
            scheduleFrameLocked()
            Log.d(
                TAG,
                "reader_test_scroll delta=${fmt(deltaPx)} scroll=${fmt(scrollOffset)} max=${fmt(maxScroll)} " +
                    "pages=${pages.size} framePipe=$framePipe renderRunning=$renderRunning"
            )
            stateLock.notifyAll()
            windowRequestLocked(true)
        }
        dispatchWindowRequest(request)
    }

    /**
     * Oversized strict scenes transfer bitmap ownership to this View.  Clearing only the page
     * reference leaves immutable native pixel storage waiting for a later ART collection, which
     * lets a 168-page decode wave grow to several GiB. Retire those exact pixels after the native
     * scene has had several frames to consume the clear, while protecting any identity that was
     * reinstalled in the meantime.
     */
    fun clearRollingAuthoritativePage(index: Int) {
        val retired = synchronized(stateLock) {
            val page = pages.getOrNull(index) ?: return
            LinkedHashSet<Bitmap>().apply {
                page.bitmap?.takeUnless(Bitmap::isRecycled)?.let(::add)
                page.tiles.forEach { tile ->
                    tile.bitmap.takeUnless(Bitmap::isRecycled)?.let(::add)
                }
            }.toList()
        }
        clearPageBitmap(index)
        if (retired.isEmpty()) return
        mainHandler.postDelayed(
            {
                val current = synchronized(stateLock) {
                    val page = pages.getOrNull(index)
                    LinkedHashSet<Bitmap>().apply {
                        page?.bitmap?.takeUnless(Bitmap::isRecycled)?.let(::add)
                        page?.tiles?.forEach { tile ->
                            tile.bitmap.takeUnless(Bitmap::isRecycled)?.let(::add)
                        }
                    }
                }
                retired.forEach { bitmap ->
                    if (bitmap !in current && !bitmap.isRecycled) bitmap.recycle()
                }
            },
            ROLLING_AUTHORITATIVE_RECYCLE_DELAY_MS,
        )
    }

    /** Bounded qualification diagnostic; contains pipeline state only, never image data. */
    fun renderPipelineDiagnosticSnapshot(): String = synchronized(stateLock) {
        "pipe=$framePipe,inFlight=$inFlightToken,pending=${pendingFrameCommits.size}," +
            "callbackPosted=$directFrameCallbackPosted,handler=${directRenderHandler != null}," +
            "choreographer=${directChoreographer != null},running=$renderRunning," +
            "directReady=$directSurfaceReady,surfaceValid=${rollingTextureSurface?.isValid == true}," +
            "native=${rollingNativeHandle != 0L},attach=$rollingNativeAttachEpoch," +
            "suppressed=$frameSchedulingSuppressed,requested=$renderRequested," +
            "versions=$desiredVersion/$drawnVersion/$committedVersion," +
            "pointer=$pointerDown,dragging=$dragging,scrolling=${!scroller.isFinished}"
    }

    fun visibleCoverageSnapshot(): VisibleCoverageSnapshot? {
        return synchronized(stateLock) {
            lastVisibleCoverageSnapshot
        }
    }

    /**
     * Retires every pending commit token without clearing installed drawables. Used across
     * background/configuration boundaries so stale `actual:` semantics cannot survive without a
     * new draw from the new lifecycle epoch.
     */
    @JvmOverloads
    fun invalidateCommittedPresentationProof(scheduleIfVisible: Boolean = true) {
        synchronized(stateLock) {
            clearFramePipeLocked(preserveDirty = true)
            resetTraversalProofLocked(pages.size)
            advanceDesiredVersionLocked()
            renderRequested = true
            if (scheduleIfVisible && renderRunning && pages.isNotEmpty() && isShown &&
                windowVisibility == View.VISIBLE
            ) {
                scheduleFrameLocked()
            }
            stateLock.notifyAll()
        }
    }

    fun refreshVisibleCoverageSnapshot(): VisibleCoverageSnapshot? {
        return synchronized(stateLock) {
            refreshVisibleCoverageSnapshotLocked()
        }
    }

    private fun refreshVisibleCoverageSnapshotLocked(): VisibleCoverageSnapshot? {
        val state = buildDrawStateLocked(lastBusy, allowVisibleLoadingState = false)
            ?: return lastVisibleCoverageSnapshot
        val snapshot = visibleCoverageSnapshotFromState(state)
        lastVisibleCoverageSnapshot = snapshot
        return snapshot
    }

    private fun refreshVisibleCoverageAfterDrawableLocked(index: Int) {
        // The scheduled frame computes and publishes the same coverage snapshot. Rebuilding the
        // complete draw state synchronously for every page arriving under the finger duplicates
        // that work while holding stateLock and can delay the next input event. The drawable is
        // already installed and the frame is still scheduled immediately.
        if (lastBusy || pointerDown || dragging || !scroller.isFinished) return
        if (isNearVisibleLocked(index, BUSY_RESOLVE_RENDER_EXTRA_PAGES)) {
            refreshVisibleCoverageSnapshotLocked()
        }
    }

    private fun shouldRenderPageResolveNowLocked(
        index: Int,
        idleExtraPages: Int = BUSY_RESOLVE_RENDER_EXTRA_PAGES
    ): Boolean {
        if (!lastBusy) return isNearVisibleLocked(index, idleExtraPages)
        if (index !in pages.indices || width <= 0 || height <= 0) return false
        rebuildLayoutLocked()
        val pageTop = pageTopOrElseLocked(index, Float.MAX_VALUE)
        val pageBottom = pageTop + pageDrawHeightLocked(pages[index])
        val visibleTop = scrollOffset
        val visibleBottom = scrollOffset + height
        return pageBottom >= visibleTop - ACTIVE_SCROLL_RESOLVE_RENDER_MARGIN_PX &&
            pageTop <= visibleBottom + ACTIVE_SCROLL_RESOLVE_RENDER_MARGIN_PX
    }

    private fun shouldFreezeOffscreenDrawableInstallLocked(
        index: Int,
        pageTop: Float,
        pageHeight: Float
    ): Boolean {
        if (!isRecentGeometrySensitiveScrollLocked()) return false
        if (index !in pages.indices || width <= 0 || height <= 0) return false
        val pageBottom = pageTop + pageHeight
        val visibleTop = scrollOffset - ACTIVE_SCROLL_RESOLVE_RENDER_MARGIN_PX
        val visibleBottom = scrollOffset + height + ACTIVE_SCROLL_RESOLVE_RENDER_MARGIN_PX
        return pageBottom < visibleTop || pageTop > visibleBottom
    }

    fun pageReadinessSnapshot(): PageReadinessSnapshot {
        return synchronized(stateLock) {
            var drawable = 0
            var loading = 0
            var errors = 0
            var cards = 0
            var unresolved = 0
            val loadingIndexes = ArrayList<Int>()
            val unresolvedIndexes = ArrayList<Int>()
            for ((index, page) in pages.withIndex()) {
                val drawablePage = pageHasDrawableForReadinessLocked(page)
                when {
                    page.errorText != null -> errors++
                    page.cardText != null -> {
                        cards++
                        if (!inlineRealPixelsOnly) {
                            drawable++
                        } else {
                            unresolved++
                            unresolvedIndexes.add(index)
                        }
                    }
                    drawablePage -> drawable++
                    page.loading -> {
                        loading++
                        unresolved++
                        loadingIndexes.add(index)
                        unresolvedIndexes.add(index)
                    }
                    else -> {
                        unresolved++
                        unresolvedIndexes.add(index)
                    }
                }
            }
            PageReadinessSnapshot(
                pageCount = pages.size,
                drawablePages = drawable,
                loadingPages = loading,
                errorPages = errors,
                cardPages = cards,
                unresolvedPages = unresolved,
                loadingIndexes = loadingIndexes.joinToString("|"),
                unresolvedIndexes = unresolvedIndexes.joinToString("|")
            )
        }
    }

    fun traversalSnapshot(): TraversalSnapshot {
        return synchronized(stateLock) {
            val committed = ArrayList<Int>(traversalCommittedPages.size)
            val missing = ArrayList<Int>(traversalCommittedPages.size)
            traversalCommittedPages.forEachIndexed { index, seen ->
                if (seen) committed.add(index) else missing.add(index)
            }
            TraversalSnapshot(
                structureEpoch = traversalStructureEpoch,
                expectedPageCount = traversalExpectedPageCount,
                committedPageCount = committed.size,
                committedPageIndexes = committed.joinToString("|"),
                missingPageIndexes = missing.joinToString("|"),
                submittedFrames = traversalSubmittedFrames,
                committedFrames = traversalCommittedFrames,
                submittedViewportDefectFrames = traversalSubmittedViewportDefectFrames,
                submittedRunwayDefectFrames = traversalSubmittedRunwayDefectFrames,
                committedViewportDefectFrames = traversalCommittedViewportDefectFrames,
                committedRunwayDefectFrames = traversalCommittedRunwayDefectFrames
            )
        }
    }

    /** O(1) structure identity for commit validation; traversalSnapshot() remains the full audit. */
    fun currentTraversalStructureEpoch(): Long = synchronized(stateLock) {
        traversalStructureEpoch
    }

    fun completedDrawDispatchTelemetrySnapshot(): CompletedDrawDispatchTelemetrySnapshot {
        val snapshot = completedDrawDispatchQueue.snapshot()
        return CompletedDrawDispatchTelemetrySnapshot(
            offers = snapshot.offers,
            scheduleRequests = snapshot.scheduleRequests,
            selected = snapshot.selected,
            delivered = snapshot.delivered,
            staleDropped = snapshot.staleDropped,
            sameSemanticCoalesced = snapshot.sameSemanticCoalesced,
            ordinarySuperseded = snapshot.ordinarySuperseded,
            overflowCoalesced = snapshot.overflowCoalesced,
            criticalOverflowFailures = snapshot.criticalOverflowFailures,
            postRejected = snapshot.postRejected,
            clears = snapshot.clears,
            maxQueueDepth = snapshot.maxQueueDepth,
            pendingSemanticTransitions = snapshot.pendingSemanticTransitions,
            ordinaryPending = snapshot.ordinaryPending,
            runnerScheduled = snapshot.runnerScheduled,
            runnerRunning = snapshot.runnerRunning,
            maxConcurrentRunnerOwners = snapshot.maxConcurrentRunnerOwners,
        )
    }

    private fun resetTraversalProofLocked(expectedPageCount: Int) {
        traversalStructureEpoch++
        if (traversalStructureEpoch <= 0L) traversalStructureEpoch = 1L
        traversalExpectedPageCount = max(0, expectedPageCount)
        traversalCommittedPages = BooleanArray(traversalExpectedPageCount)
        traversalSubmittedFrames = 0L
        traversalCommittedFrames = 0L
        traversalSubmittedViewportDefectFrames = 0L
        traversalSubmittedRunwayDefectFrames = 0L
        traversalCommittedViewportDefectFrames = 0L
        traversalCommittedRunwayDefectFrames = 0L
        nativeTexturePrewarmAnchorPage = -1
        nativeTexturePrewarmPendingPages.clear()
        requestResidentNativeTexturePrewarmLocked()
    }

    /** Append-only strip geometry does not invalidate pixels already committed for its prefix. */
    private fun extendTraversalProofLocked(expectedPageCount: Int) {
        val nextCount = max(0, expectedPageCount)
        if (nextCount <= traversalExpectedPageCount) return
        if (traversalStructureEpoch <= 0L) {
            resetTraversalProofLocked(nextCount)
            return
        }
        traversalCommittedPages = traversalCommittedPages.copyOf(nextCount)
        traversalExpectedPageCount = nextCount
    }

    /** Returns the final page intersecting the physical viewport plus the requested runway. */
    fun forwardRequestEndPage(aheadViewports: Float): Int {
        return synchronized(stateLock) {
            if (pages.isEmpty() || width <= 0 || height <= 0) return@synchronized -1
            rebuildLayoutLocked()
            val runwayPx = max(0f, aheadViewports) * height.toFloat()
            val endExclusive = min(contentHeight, scrollOffset + height.toFloat() + runwayPx)
            if (endExclusive <= 0f) return@synchronized 0
            val probe = min(
                max(0f, contentHeight - FORWARD_REQUEST_END_EPSILON_PX),
                max(0f, endExclusive - FORWARD_REQUEST_END_EPSILON_PX)
            )
            firstVisiblePageLocked(probe).coerceIn(0, pages.lastIndex)
        }
    }

    /** Returns the first page intersecting the physical viewport. */
    fun forwardRequestStartPage(): Int {
        return synchronized(stateLock) {
            if (pages.isEmpty() || width <= 0 || height <= 0) return@synchronized -1
            rebuildLayoutLocked()
            firstVisiblePageLocked(scrollOffset).coerceIn(0, pages.lastIndex)
        }
    }

    /**
     * Measures real, full-quality drawable pixels immediately beyond the physical viewport.
     * This reads the immutable bitmap/tile pages used by Canvas; it does not trigger work,
     * synthesize content, or treat loading/error cards as drawable runway.
     */
    fun forwardRunwaySnapshot(
        aheadViewports: Float = DEFAULT_FORWARD_RUNWAY_AHEAD_VIEWPORTS
    ): ForwardRunwaySnapshot? {
        return synchronized(stateLock) {
            forwardRunwaySnapshotLocked(aheadViewports)
        }
    }

    private fun forwardRunwaySnapshotLocked(aheadViewports: Float): ForwardRunwaySnapshot? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        rebuildLayoutLocked()
        val required = ceil(max(0f, aheadViewports) * height.toFloat()).toInt()
        val start = scrollOffset + height.toFloat()
        val end = min(contentHeight, start + required.toFloat())
        val available = ceil(max(0f, end - start)).toInt()
        if (stripAuthorityToken != 0L && stripGeometry != null) {
            val startPx = max(0L, floor(start.toDouble()).toLong())
            val endPx = minOf(stripGeometry!!.contentHeightPx, ceil(end.toDouble()).toLong())
            // Authoritative strip residency is an integer half-open interval. Measuring its
            // available tail with the legacy accumulated Float layout can differ by several
            // pixels after dozens of pages and report a gap even when no interval gap exists.
            val stripAvailable = (endPx - startPx).coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val drawable = stripResidentCoverage.coveredLength(startPx, endPx)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val gap = stripResidentCoverage.firstGap(startPx, endPx)
            val missingPage = gap?.let { gapPx ->
                stripGeometry!!.pages.indexOfFirst { page -> gapPx < page.contentBottomPx }
                    .let { position -> if (position >= 0) position else pages.lastIndex }
            } ?: -1
            return ForwardRunwaySnapshot(
                requiredAheadPx = required,
                availableAheadPx = stripAvailable,
                drawableAheadPx = drawable,
                missingAheadPx = max(0, stripAvailable - drawable),
                lowResolutionItems = 0,
                firstMissingPage = missingPage,
                contentExhausted = endPx >= stripGeometry!!.contentHeightPx
            )
        }
        val minimumSourceWidth = minimumReadableSourceWidth(width, inlineRealPixelsOnly)
        var drawable = 0
        var lowResolution = 0
        var firstMissing = -1
        var index = firstVisiblePageLocked(start).coerceIn(0, pages.lastIndex)
        while (index > 0 && pageTopOrElseLocked(index, 0f) > start) index--
        while (index < pages.size) {
            val page = pages[index]
            val pageTop = pageTopOrElseLocked(index, 0f)
            val pageBottom = pageTop + pageDrawHeightLocked(page)
            if (pageTop >= end) break
            val overlapTop = max(start, pageTop)
            val overlapBottom = min(end, pageBottom)
            if (overlapBottom > overlapTop) {
                val adjacentPrefixBottom = adjacentExactPrefixBottomLocked(index)
                if (adjacentPrefixBottom != null) {
                    val exactBottom = min(overlapBottom, adjacentPrefixBottom)
                    if (exactBottom > overlapTop) {
                        drawable += ceil(exactBottom - overlapTop).toInt()
                    }
                    if (exactBottom < overlapBottom && firstMissing < 0) firstMissing = index
                    index++
                    continue
                }
                val sourceWidth = completeFullQualityDrawableSourceWidth(
                    page,
                    requireOriginalProof = inlineRealPixelsOnly
                )
                if (sourceWidth > 0) {
                    drawable += ceil(overlapBottom - overlapTop).toInt()
                    if (sourceWidth < minimumSourceWidth) lowResolution++
                } else {
                    if (page.bitmap != null || page.tiles.any { !it.bitmap.isRecycled }) {
                        lowResolution++
                    }
                    if (firstMissing < 0) firstMissing = index
                }
            }
            index++
        }
        val missing = max(0, available - drawable)
        return ForwardRunwaySnapshot(
            requiredAheadPx = required,
            availableAheadPx = available,
            drawableAheadPx = drawable,
            missingAheadPx = missing,
            lowResolutionItems = lowResolution,
            firstMissingPage = firstMissing,
            contentExhausted = end >= contentHeight
        )
    }

    private fun completeFullQualityDrawableSourceWidth(
        page: Page,
        requireOriginalProof: Boolean
    ): Int {
        if (page.loading || page.errorText != null || page.cardText != null) return 0
        if (requireOriginalProof) {
            return if (usableAuthoritativeOriginalTilePage(
                    page.width,
                    page.height,
                    page.tiles,
                    page.originalProof
                )
            ) {
                page.originalProof!!.originalWidth
            } else {
                0
            }
        }
        val bitmap = page.bitmap
        if (bitmap != null && !bitmap.isRecycled &&
            bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.isMutable
        ) {
            return bitmap.width
        }
        if (page.tiles.isEmpty()) return 0
        var expectedTop = 0
        var sourceWidth = 0
        var sourceHeight = 0
        for (tile in page.tiles) {
            val tileBitmap = tile.bitmap
            val sourceSpan = tile.sourceBottom - tile.sourceTop
            if (tile.sourceTop != expectedTop || sourceSpan <= 0 ||
                tile.sourceWidth <= 0 || tile.sourceHeight <= 0 ||
                (sourceWidth > 0 && tile.sourceWidth != sourceWidth) ||
                (sourceHeight > 0 && tile.sourceHeight != sourceHeight) ||
                tileBitmap.isRecycled || tileBitmap.config != Bitmap.Config.ARGB_8888 ||
                tileBitmap.isMutable || !tile.hasExactSourcePixelStorage()
            ) {
                return 0
            }
            sourceWidth = tile.sourceWidth
            sourceHeight = tile.sourceHeight
            expectedTop = tile.sourceBottom
        }
        return if (expectedTop == sourceHeight) sourceWidth else 0
    }

    fun requestVisibleCoverageFrame() {
        synchronized(stateLock) {
            if (pages.isNotEmpty()) {
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }
    }

    fun programmaticScrollActiveRemainingMs(nowMs: Long = SystemClock.uptimeMillis()): Long {
        return synchronized(stateLock) {
            (programmaticScrollStatsUntilMs - nowMs).coerceAtLeast(0L)
        }
    }

    fun frameStatsSnapshot(): FrameStatsSnapshot? {
        val snapshot = synchronized(stateLock) {
            if (lastFrameStatsSnapshot == null && statsActive) {
                finalizeActiveFrameStatsLocked(log = false)
            }
            lastFrameStatsSnapshot
        }
        return snapshot
    }

    /**
     * Atomically drains both the previously completed segment and the currently active segment.
     * Unlike [resetFrameStatsSnapshot], this does not arm a new-input boundary, so fling/tail
     * frames that arrive after the drain start a fresh segment and remain available to the next
     * drain instead of being discarded.
     */
    fun takeFrameStatsSnapshots(): List<FrameStatsSnapshot> {
        return synchronized(stateLock) {
            val completed = lastFrameStatsSnapshot
            lastFrameStatsSnapshot = null
            val active = if (statsActive) finalizeActiveFrameStatsLocked(log = false) else null
            val snapshots = ArrayList<FrameStatsSnapshot>(2)
            if (completed != null) snapshots.add(completed)
            if (active != null && active !== completed) snapshots.add(active)
            lastFrameStatsSnapshot = null
            snapshots
        }
    }

    fun resetFrameStatsSnapshot() {
        synchronized(stateLock) {
            lastFrameStatsSnapshot = null
            resetActiveFrameStatsLocked()
            statsAwaitingFirstInput = true
            pendingOldestInputNs = 0L
            pendingNewestInputNs = 0L
            pendingInputEvents = 0
            pendingHistorySamples = 0
            stateLock.notifyAll()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshCachedDisplayTiming()
        if (!displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayTimingListener, mainHandler)
            displayListenerRegistered = displayManager != null
        }
        synchronized(stateLock) {
            renderRunning = true
            if (rollingNativePresentationEnabled) {
                startRenderThreadLocked()
            }
            renderRequested = pages.isNotEmpty() && !shouldBlockInitialEmptyFrameLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        var physicalMotionEnded = false
        val request = synchronized(stateLock) {
            if (width != oldWidth || height != oldHeight) {
                if (oldWidth > 0 && oldHeight > 0) {
                    physicalMotionEnded = interruptPhysicalScrollForLifecycleLocked()
                }
                materializeLayoutDeltasLocked()
                layoutDirty = true
            }
            val scene = preparedRenderScene
            val directWidthMismatch = directPreparedBitmapGeneration == visualGeneration &&
                directPreparedBitmapWidth != width
            if (width > 0 && ((scene != null && scene.width != width) || directWidthMismatch)) {
                invalidatePreparedRenderSceneStateLocked()
            }
            val hasPendingPreparedAnchor = pendingPreparedStartPage in pages.indices
            if (hasPendingPreparedAnchor && width > 0 && height > 0) {
                val expectedWidth = pendingPreparedViewportWidth
                val expectedHeight = pendingPreparedViewportHeight
                if (width != expectedWidth || height != expectedHeight) {
                    Log.d(
                        TAG,
                        "reader_prepared_batch_viewport_changed expected=${expectedWidth}x$expectedHeight " +
                            "actual=${width}x$height pages=${pages.size}"
                    )
                }
                rebuildLayoutLocked()
                applyPreparedStartAnchorLocked(clearPending = true)
            } else {
                clampScrollLocked()
            }
            if (width > 0 && height > 0) {
                // Freeze only arithmetic here. EGL/AHB creation remains forbidden until the first
                // complete exact HWUI commit, but the later renderer must retain the same compact
                // target that the former pre-pixel preparation path selected.
                captureNativeRenderTargetGeometryLocked(width, height)
                prepareRollingNativeRenderTargetsLocked(width, height)
            }
            lastAnchor = -1
            val hasPages = pages.isNotEmpty()
            renderRequested = hasPages && !shouldBlockInitialEmptyFrameLocked()
            if (renderRequested) scheduleFrameLocked()
            stateLock.notifyAll()
            if (hasPages) windowRequestLocked(lastBusy) else null
        }
        if (physicalMotionEnded) listener?.onPhysicalScrollMotionEnded()
        dispatchWindowRequest(request)
    }

    override fun onDetachedFromWindow() {
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayTimingListener)
            displayListenerRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        var nativeHandleToDestroy = 0L
        var nativeDestroyPosted = false
        val retiringThread = synchronized(stateLock) {
            deferredSurfaceIdentityActivated = false
            nativeSurfaceRevealAfterFirstHwuiCommitPending = false
            nativeSurfaceRevealAfterPrepareEpoch = 0L
            noStateRetryPosted = false
            clearRetainedPageNodesStateLocked()
            clearPreparedStartAnchorLocked()
            renderRunning = false
            renderRequested = false
            rollingNativeCreateGeneration += 1L
            rollingNativeCreatePending = false
            clearFramePipeLocked(preserveDirty = false)
            clearInputStateLocked()
            resetActiveFrameStatsLocked()
            nativeHandleToDestroy = rollingNativeHandle
            rollingNativeHandle = 0L
            advanceRollingNativeSurfaceEpochLocked()
            rollingNativeAttachEpoch = 0L
            rollingNativeSurfaceIdentity = 0
            rollingNativeWidth = 0
            rollingNativeHeight = 0
            rollingNativeViewportWidth = 0
            rollingNativeViewportHeight = 0
            rollingNativePreparedWidth = 0
            rollingNativePreparedHeight = 0
            rollingNativeFrozenViewportWidth = 0
            rollingNativeFrozenViewportHeight = 0
            rollingNativeFrozenTargetWidth = 0
            rollingNativeFrozenTargetHeight = 0
            nativePresentationVisible = false
            nativePresentedStructureEpoch = 0L
            rollingTextureSurface = null
            directSurfaceReady = false
            if (nativeHandleToDestroy != 0L) {
                nativeDestroyPosted = directRenderHandler?.post {
                    NtkRollingNativeBridge.nativeDestroy(nativeHandleToDestroy)
                } == true
            }
            val thread = stopRenderThreadLocked()
            stateLock.notifyAll()
            thread
        }
        if (nativeHandleToDestroy != 0L && !nativeDestroyPosted) {
            NtkRollingNativeBridge.nativeDestroy(nativeHandleToDestroy)
        }
        retiringThread?.quitSafely()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!rollingNativePresentationEnabled) return
        val surface = holder.surface
        val surfaceWidth = nativeSurfaceView.width
        val surfaceHeight = nativeSurfaceView.height
        synchronized(stateLock) {
            rollingTextureSurface = surface
            directSurfaceReady = surface.isValid
            if (directSurfaceReady && renderRunning && pages.isNotEmpty()) renderRequested = true
            stateLock.notifyAll()
        }
        val refreshRate = refreshCachedDisplayTiming()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
                refreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(
                refreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }
        applyQualifiedNativeSurfaceFrameRateVote(refreshRate)
        attachRollingNativeSurface(surface, surfaceWidth, surfaceHeight)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (!rollingNativePresentationEnabled) return
        if (width <= 0 || height <= 0) return
        val surface = synchronized(stateLock) {
            val current = holder.surface
            rollingTextureSurface = current
            directSurfaceReady = current.isValid
            if (directSurfaceReady && renderRunning && pages.isNotEmpty()) renderRequested = true
            stateLock.notifyAll()
            current
        }
        val refreshRate = refreshCachedDisplayTiming()
        applyQualifiedNativeSurfaceFrameRateVote(refreshRate)
        attachRollingNativeSurface(surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var physicalMotionEnded = false
        val detach = synchronized(stateLock) {
            physicalMotionEnded = interruptPhysicalScrollForLifecycleLocked()
            directSurfaceReady = false
            rollingTextureSurface = null
            // A producer-thread Choreographer callback can remain reserved when the window stops
            // delivering vsync. If that reservation survives this Surface, the replacement
            // attachment mistakes it for live work and strands its first admitted frame forever.
            // Retire every Surface-scoped callback while the old attachment is fenced so resume
            // always starts with an empty scheduling lane.
            retireDirectSurfaceSchedulingLocked()
            val value = rollingNativeHandle to maxOf(
                rollingNativeAttachEpoch,
                rollingNativeSurfaceEpochCounter
            )
            advanceRollingNativeSurfaceEpochLocked()
            rollingNativeAttachEpoch = 0L
            rollingNativeSurfaceIdentity = 0
            rollingNativeWidth = 0
            rollingNativeHeight = 0
            rollingNativeViewportWidth = 0
            rollingNativeViewportHeight = 0
            rollingNativePreparedWidth = 0
            rollingNativePreparedHeight = 0
            nativePresentationVisible = false
            nativePresentedStructureEpoch = 0L
            clearFramePipeLocked(preserveDirty = true)
            stateLock.notifyAll()
            value
        }
        if (physicalMotionEnded) listener?.onPhysicalScrollMotionEnded()
        if (detach.first != 0L && detach.second > 0L) {
            NtkRollingNativeBridge.nativeDetach(detach.first, detach.second)
        }
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        synchronized(stateLock) {
            if (directSurfaceReady && pages.isNotEmpty()) {
                renderRequested = true
                scheduleFrameLocked()
            }
        }
    }

    /** Stops a real gesture/fling when its display surface is no longer user-interactable. */
    fun interruptPhysicalScrollForLifecycle() {
        val ended = synchronized(stateLock) {
            interruptPhysicalScrollForLifecycleLocked()
        }
        if (ended) listener?.onPhysicalScrollMotionEnded()
    }

    /** Returns a non-zero identity for the next physical Surface attachment request. */
    private fun advanceRollingNativeSurfaceEpochLocked(): Long {
        rollingNativeSurfaceEpochCounter += 1L
        if (rollingNativeSurfaceEpochCounter <= 0L) rollingNativeSurfaceEpochCounter = 1L
        return rollingNativeSurfaceEpochCounter
    }

    /**
     * The host-gpu emulator's child SurfaceControl did not inherit the rate vote placed on the
     * Java Surface/ANativeWindow and latched every second 60 Hz buffer. Vote on the actual child
     * layer as well. This is deliberately limited to the emulator direct-Wi-Fi benchmark profile;
     * physical devices and mobile/SNI sessions retain their existing composition policy.
     */
    private fun applyQualifiedNativeSurfaceFrameRateVote(refreshRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !NtkNativeSurfaceFrameRatePolicy.shouldApplyLayerFrameRateVote(
                emulatorRuntime = emulatorNativeSurfaceRuntime,
                directWifiRendererProfile = synchronized(stateLock) {
                    directWifiExpandedNativeTextureRunway
                },
            )
        ) return
        val surfaceControl = nativeSurfaceView.surfaceControl
        if (!surfaceControl.isValid) return
        SurfaceControl.Transaction().use { transaction ->
            transaction.setFrameRate(
                surfaceControl,
                refreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS,
            ).apply()
        }
        Log.i(TAG, "reader_native_surface_control_frame_rate rate=$refreshRate")
    }

    private fun attachRollingNativeSurface(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int
    ) {
        if (!rollingNativePresentationEnabled) return
        if (!surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val surfaceIdentity = System.identityHashCode(surface)
        val request = synchronized(stateLock) {
            if (rollingNativeFatal || !renderRunning || !directSurfaceReady) return@synchronized null
            val target = nativeRenderTargetSizeLocked(surfaceWidth, surfaceHeight)
            // A strict cold reader may already be creating the EGL renderer on its dedicated
            // producer thread. Never race that work with a second main-thread nativeCreate.
            // Completion posts another attachment attempt when this Surface is still valid.
            if (rollingNativeHandle == 0L && rollingNativeCreatePending) {
                return@synchronized null
            }
            if (rollingNativeHandle == 0L) {
                rollingNativeHandle = NtkRollingNativeBridge.nativeCreate(this)
                if (rollingNativeHandle != 0L) {
                    NtkRollingNativeBridge.nativeSetDirectWifiTextureProfile(
                        rollingNativeHandle,
                        directWifiExpandedNativeTextureRunway,
                        directWifiExpandedNativeTextureRunway &&
                            emulatorNativeSurfaceRuntime,
                    )
                    if (nativeTexturePrewarmPaused) {
                        NtkRollingNativeBridge.nativeSetPrewarmPaused(
                            rollingNativeHandle,
                            true
                        )
                    }
                }
            }
            val handle = rollingNativeHandle
            if (handle == 0L) {
                rollingNativeFatal = true
                return@synchronized null
            }
            if (rollingNativeAttachEpoch > 0L &&
                rollingNativeSurfaceIdentity == surfaceIdentity &&
                rollingNativeWidth == target.first &&
                rollingNativeHeight == target.second &&
                rollingNativeViewportWidth == surfaceWidth &&
                rollingNativeViewportHeight == surfaceHeight
            ) return@synchronized null
            // Retire a fallback HWUI admission before replacing the physical native backend. The
            // attachment receives its own monotonic epoch: frame/content resets are deliberately
            // allowed to advance lifecycleEpoch without invalidating this still-live Surface.
            clearFramePipeLocked(preserveDirty = true)
            val surfaceEpoch = advanceRollingNativeSurfaceEpochLocked()
            longArrayOf(handle, surfaceEpoch, target.first.toLong(), target.second.toLong())
        } ?: return
        val refreshRate = cachedDisplayRefreshRate
        val refreshPeriodNanos = (1_000_000_000.0 / refreshRate.toDouble()).toLong()
        val accepted = NtkRollingNativeBridge.nativeAttach(
            request[0],
            surface,
            request[2].toInt(),
            request[3].toInt(),
            request[1],
            refreshPeriodNanos
        )
        synchronized(stateLock) {
            if (accepted && rollingNativeHandle == request[0] &&
                rollingNativeSurfaceEpochCounter == request[1] && directSurfaceReady &&
                rollingTextureSurface === surface && surface.isValid &&
                System.identityHashCode(surface) == surfaceIdentity
            ) {
                // A decoded-page install can race nativeAttach() while that blocking native call
                // is outside stateLock. In that interval scheduleFrameLocked() legitimately
                // admits an HWUI token because rollingNativeAttachEpoch is still zero. Once the
                // attach completes, onDraw() sees nativePresentationVisible and skips the HWUI
                // renderer, while the native producer cannot consume the already-admitted HWUI
                // token. Retire only that posted admission (without advancing lifecycleEpoch or
                // discarding older submitted proofs) and issue a fresh producer-thread token.
                // This closes the prepared=0 cold race observed as zero native submissions for an
                // otherwise valid 112-page session.
                val racedHwuiAdmission = framePipe == FramePipe.INVALIDATION_POSTED &&
                    inFlightToken != 0L
                if (racedHwuiAdmission) {
                    val callback = inFlightCommitCallback
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        callback != null && inFlightCommitCallbackRegistered
                    ) {
                        val observer = viewTreeObserver
                        if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
                    }
                    releasePostedAdmissionLocked(preserveDirty = true)
                }
                rollingNativeAttachEpoch = request[1]
                rollingNativeSurfaceIdentity = surfaceIdentity
                rollingNativeWidth = request[2].toInt()
                rollingNativeHeight = request[3].toInt()
                rollingNativeViewportWidth = surfaceWidth
                rollingNativeViewportHeight = surfaceHeight
                nativePresentationVisible = false
                nativePresentedStructureEpoch = 0L
                Log.i(
                    TAG,
                    "reader_native_surface_attached render=${rollingNativeWidth}x$rollingNativeHeight " +
                        "viewport=${surfaceWidth}x$surfaceHeight sourceNative=" +
                        "$sourceNativeWebtoonCompositingEnabled"
                )
                if (pages.isNotEmpty()) {
                    renderRequested = true
                    scheduleFrameLocked()
                }
            } else if (!accepted && rollingNativeHandle == request[0]) {
                renderRequested = pages.isNotEmpty()
                scheduleNoStateRetryLocked()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEmpty()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // A stationary UP-to-DOWN gap is not an active-scroll frame interval. Publish the
                // physical boundary before this gesture can commit its first moving frame.
                listener?.onPhysicalScrollGestureStarted()
                val scrollbarRequest = synchronized(stateLock) {
                    // The host-GPU path serializes texture uploads with the compositor even when
                    // the renderer has no changed frame queued. Freeze opportunistic uploads for
                    // the physical gesture and let visible frames retain the entire GPU budget.
                    setNativeTexturePrewarmPausedLocked(true)
                    if (startScrollbarDragLocked(event.x, event.y)) {
                        noteInputLocked(event)
                        lastScrollInteractionMs = event.eventTime
                        activateScrollStatsLocked(event.eventTime)
                        startPhysicalGestureFrameCadenceLocked()
                        scroller.forceFinished(true)
                        activeScrollerOffsetShift = 0f
                        activeInputDirection = 0
                        pointerDown = true
                        dragging = true
                        postDirectFrameCallbackLocked()
                        if (!lastBusy) {
                            lastBusy = true
                        }
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                        windowRequestLocked(true)
                    } else {
                        null
                    }
                }
            if (scrollbarDragging) {
                velocityTracker?.recycle()
                velocityTracker = null
                dispatchWindowRequest(scrollbarRequest, fromInput = true)
                return true
            }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                val downRequest = synchronized(stateLock) {
                    lastScrollInteractionMs = event.eventTime
                    activateScrollStatsLocked(event.eventTime)
                    startPhysicalGestureFrameCadenceLocked()
                    scroller.forceFinished(true)
                    activeScrollerOffsetShift = 0f
                    activeInputDirection = 0
                    downX = event.x
                    downY = event.y
                    if (physicalGestureRevision == Long.MAX_VALUE) {
                        physicalGestureRevision = 1L
                        directCallbackObservedPhysicalGestureRevision = 0L
                    } else {
                        physicalGestureRevision++
                    }
                    resetDragTrackingLocked(event.y)
                    lastVelocitySampleMs = event.eventTime
                    pointerDown = true
                    dragging = false
                    // Keep one vsync callback armed while the finger is down. A MOVE can then use
                    // that already-requested callback instead of requesting after the current
                    // vsync deadline and falling into an every-other-vsync cadence.
                    postDirectFrameCallbackLocked()
                    boundaryArmedDirection = 0
                    if (!lastBusy) {
                        lastBusy = true
                    }
                    // DOWN changes no pixels.  Touch delivery is measured at the Reader dispatch
                    // boundary, while frame input latency starts with the first MOVE that actually
                    // changes the viewport.  Invalidating here submits an unchanged full-screen
                    // frame ahead of that MOVE and can make the real scrolling frame wait behind
                    // RenderThread/GPU work.
                    stateLock.notifyAll()
                    windowRequestLocked(true)
                }
                dispatchWindowRequest(downRequest, fromInput = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrollbarDragging) {
                    val request = synchronized(stateLock) {
                        noteInputLocked(event)
                        val request = moveScrollbarDragLocked(event.y)
                        renderRequested = true
                        scheduleFrameLocked()
                        request
                    }
                    dispatchWindowRequest(request, fromInput = true)
                    return true
                }
                var sampleVelocity = false
                val request = synchronized(stateLock) {
                    val shouldSampleVelocity = event.eventTime - lastVelocitySampleMs >= MOVE_VELOCITY_SAMPLE_MS
                    if (shouldSampleVelocity) {
                        lastVelocitySampleMs = event.eventTime
                        sampleVelocity = true
                    }
                    noteInputLocked(event)
                    val moved = applyPhysicalDragPositionLocked(
                        event.y,
                        event.eventTime * NANOS_PER_MILLISECOND
                    )
                    if (moved) {
                        // The physical MOVE has already updated the viewport one-to-one. Admit only
                        // that real position; do not manufacture intermediate scroll positions.
                        val postedFreshCallback = postDirectFrameCallbackLocked()
                        // The HWUI View path has no producer-thread Choreographer. Admit its
                        // display-list frame from the same physical MOVE instead.
                        renderRequested = true
                        scheduleFrameLocked()
                        if (!postedFreshCallback) {
                            // A callback that already observed the prior exact target may be
                            // replaced only inside the bounded same-vsync race policy below.
                            postLateDirectInputCatchupLocked()
                        }
                        stateLock.notifyAll()
                        sampleVelocity = true
                        windowRequestLocked(true)
                    } else {
                        val nowMs = SystemClock.uptimeMillis()
                        suppressEdgeNoMovementScrollStatsLocked(nowMs)
                        null
                    }
                }
                if (sampleVelocity) velocityTracker?.addMovement(event)
                dispatchWindowRequest(request, fromInput = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrollbarDragging) {
                    val request = synchronized(stateLock) {
                        noteInputLocked(event)
                        val moveRequest = moveScrollbarDragLocked(event.y)
                        scrollbarDragging = false
                        pointerDown = false
                        dragging = false
                        activeInputDirection = 0
                        endPhysicalScrollTraceLocked()
                        val busyRequest = setBusyLocked(false)
                        // Releasing the pointer changes interaction state, not manga pixels. Any
                        // final position change above has already entered through setScrollOffsetLocked.
                        markPixelsDirtyLocked(DIRTY_SCROLLBAR, recordCausalMutation = false)
                        busyRequest ?: moveRequest
                    }
                    dispatchWindowRequest(request, fromInput = true)
                    return true
                }
                val tracker = velocityTracker
                var velocityY = 0
                var tap = false
                if (tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, maxVelocity.toFloat())
                    velocityY = (-tracker.yVelocity)
                        .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                        .toInt()
                    tracker.recycle()
                }
                velocityTracker = null
                val result = synchronized(stateLock) {
                    noteInputLocked(event)
                    val upMoved = applyPhysicalDragPositionLocked(
                        event.y,
                        event.eventTime * NANOS_PER_MILLISECOND
                    )
                    val wasReleased = event.actionMasked == MotionEvent.ACTION_UP
                    val wasTap = isTapGesture(
                        wasReleased,
                        event.x - downX,
                        event.y - downY,
                        touchSlop
                    )
                    // A real finger commonly produces sub-slop MOVE samples. The drag follower
                    // may already have consumed those samples, but Android still defines the
                    // gesture as a tap. Restore the exact DOWN viewport so toggling chrome never
                    // nudges the page by a few pixels.
                    if (wasTap && abs(scrollOffset - dragOriginScrollOffset) > SCROLL_OFFSET_EPSILON_PX) {
                        setScrollOffsetLocked(dragOriginScrollOffset)
                        scroller.forceFinished(true)
                        activeScrollerOffsetShift = 0f
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                    }
                    tap = wasTap
                    pointerDown = false
                    dragging = false
                    val dragDistance = abs(event.y - downY)
                    val cancelledBoundaryDrag = event.actionMasked == MotionEvent.ACTION_CANCEL &&
                        shouldDispatchCancelledBoundaryLocked(dragDistance)
                    val canFling = shouldStartFling(dragDistance, velocityY, minVelocity, touchSlop)
                    val releaseDirection = directionForDelta(velocityY.toFloat()).let { direction ->
                        if (direction != 0) direction else activeInputDirection
                    }
                    val terminalMaxScroll = maxScrollLocked()
                    val terminalRemaining = terminalMaxScroll - scrollOffset
                    val snappedToTerminalEdge = wasReleased && !wasTap &&
                        releaseDirection == DIRECTION_NEXT &&
                        terminalRemaining > SCROLL_OFFSET_EPSILON_PX &&
                        terminalRemaining <= height / 6f
                    if (snappedToTerminalEdge) {
                        activeInputDirection = DIRECTION_NEXT
                        boundaryArmedDirection = DIRECTION_NEXT
                        setScrollOffsetLocked(terminalMaxScroll)
                        scroller.forceFinished(true)
                        activeScrollerOffsetShift = 0f
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                    }
                    val dispatch = if (wasTap) {
                        boundaryArmedDirection = 0
                        setBusyLocked(false) to null
                    } else if (snappedToTerminalEdge) {
                        val request = setBusyLocked(false)
                        (request ?: windowRequestLocked(false)) to
                            boundaryRequestLocked(clearDirection = false)
                    } else if (wasReleased && canFling && abs(velocityY) > minVelocity) {
                        val flingVelocity = (velocityY * FLING_SCROLL_MULTIPLIER)
                            .coerceIn(-maxVelocity.toFloat(), maxVelocity.toFloat())
                            .toInt()
                        val flingDirection = directionForDelta(flingVelocity.toFloat())
                        boundaryArmedDirection = flingDirection
                        activeInputDirection = boundaryArmedDirection
                        if (boundaryArmedDirection != 0) lastScrollInteractionMs = event.eventTime
                        if (isAtInputEdgeLocked(flingDirection)) {
                            suppressEdgeNoMovementScrollStatsLocked(SystemClock.uptimeMillis())
                            val request = setBusyLocked(false)
                            (request ?: windowRequestLocked(false)) to boundaryRequestLocked()
                        } else {
                        val busyRequest = setBusyLocked(true)
                        activeScrollerOffsetShift = 0f
                        scroller.fling(
                            0,
                            scrollOffset.toInt(),
                            0,
                            flingVelocity,
                            0,
                            0,
                            0,
                            maxScrollLocked().toInt()
                        )
                        renderRequested = true
                        scheduleFrameLocked()
                        stateLock.notifyAll()
                        (busyRequest ?: windowRequestLocked(true)) to boundaryRequestLocked()
                        }
                    } else {
                        if (!upMoved && !canFling) {
                            suppressEdgeNoMovementScrollStatsLocked(SystemClock.uptimeMillis())
                        }
                        val request = setBusyLocked(false)
                        val shouldDispatchBoundary = wasReleased || cancelledBoundaryDrag
                        val boundary = if (shouldDispatchBoundary) boundaryRequestLocked() else null
                        if (!shouldDispatchBoundary) boundaryArmedDirection = 0
                        if (!canFling) activeInputDirection = 0
                        request to boundary
                    }
                    if (!pointerDown && scroller.isFinished && !upMoved) {
                        endPhysicalScrollTraceLocked()
                        setNativeTexturePrewarmPausedLocked(false)
                    }
                    // Always arm the benchmark-only observer on UP/CANCEL. At the terminal edge a
                    // valid gesture may move zero pixels, so there may be no trace cookie whose
                    // later close could otherwise produce the final-idle proof.
                    scheduleBenchmarkPhysicalMotionIdleCheckLocked()
                    dispatch
                }
                dispatchWindowRequest(result.first, fromInput = true)
                dispatchBoundaryRequest(result.second)
                if (tap) mainHandler.post { performClick() }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nativeSurfaceOwnsFrame = synchronized(stateLock) {
            directSurfaceReady && rollingNativeHandle != 0L &&
                rollingNativeAttachEpoch > 0L && !rollingNativeFatal &&
                nativePresentationVisible &&
                nativePresentedStructureEpoch == traversalStructureEpoch
        }
        if (!nativeSurfaceOwnsFrame) {
            renderFrame(System.nanoTime(), canvas, directSurface = false)
        }
    }

    private fun renderFrame(
        frameTimeNanos: Long,
        canvas: Canvas?,
        directSurface: Boolean
    ): Boolean {
        val callbackStartNs = System.nanoTime()
        val work = synchronized(stateLock) {
            if (!renderRunning || frameSchedulingSuppressed ||
                framePipe != FramePipe.INVALIDATION_POSTED || inFlightToken == 0L
            ) return false
            val admittedToken = inFlightToken
            val admittedEpoch = inFlightEpoch
            var request: WindowRequest? = null
            var boundary: BoundaryRequest? = null
            var scrolling = try {
                scroller.computeScrollOffset()
            } catch (_: ArrayIndexOutOfBoundsException) {
                scroller.forceFinished(true)
                false
            }
            if (scrolling) {
                lastScrollInteractionMs = SystemClock.uptimeMillis()
                val beforeScroll = scrollOffset
                val rawNext = scroller.currY.toFloat() + activeScrollerOffsetShift
                val rawDirection = directionForDelta(rawNext - scrollOffset)
                val direction = if (rawDirection != 0) rawDirection else activeInputDirection
                if (rawDirection != 0) {
                    activeInputDirection = rawDirection
                    boundaryArmedDirection = rawDirection
                }
                val boundedNext = capForwardInputScrollLocked(rawNext, direction)
                setScrollOffsetLocked(boundedNext)
                if (!(limitScrollToDrawablePrefix && direction == DIRECTION_NEXT)) {
                    clampForwardScrollLocked()
                } else if (boundedNext < rawNext - SCROLL_OFFSET_EPSILON_PX) {
                    scroller.forceFinished(true)
                    activeScrollerOffsetShift = 0f
                }
                val movedByFrame = abs(scrollOffset - beforeScroll) > SCROLL_OFFSET_EPSILON_PX
                // A scroller tick mutates reader pixels just like a MOVE.  Preserve the
                // commit-aware version invariant even when no newer input/content version is
                // already pending; the frame built below must carry this exact scroll state.
                if (shouldAdvanceDesiredVersionForScrollerFrame(
                        movedByFrame,
                        desiredVersion,
                        drawnVersion
                    )
                ) {
                    advanceDesiredVersionLocked()
                }
                if (movedByFrame) {
                    beginPhysicalScrollTraceLocked()
                    pendingPixelReasons = pendingPixelReasons or DIRTY_ANIMATION
                }
                if (
                    shouldFinishScrollerAtInputEdgeLocked(direction, rawNext) ||
                    (!movedByFrame && isAtInputEdgeLocked(direction))
                ) {
                    scroller.forceFinished(true)
                    activeScrollerOffsetShift = 0f
                    scrolling = false
                    if (!movedByFrame) {
                        suppressEdgeNoMovementScrollStatsLocked(SystemClock.uptimeMillis())
                    }
                }
                renderRequested = true
                request = windowRequestLocked(true)
                boundary = boundaryRequestLocked(clearDirection = false)
            } else if (scroller.isFinished) {
                val maxScroll = maxScrollLocked()
                val remaining = maxScroll - scrollOffset
                val snapToTerminalEdge = !pointerDown &&
                    activeInputDirection == DIRECTION_NEXT &&
                    remaining > SCROLL_OFFSET_EPSILON_PX &&
                    remaining <= height / 6f
                if (snapToTerminalEdge) {
                    setScrollOffsetLocked(maxScroll)
                    advanceDesiredVersionLocked()
                    pendingPixelReasons = pendingPixelReasons or DIRTY_ANIMATION
                    renderRequested = true
                    request = windowRequestLocked(true)
                    boundary = boundaryRequestLocked(clearDirection = false)
                }
                activeScrollerOffsetShift = 0f
                activeInputDirection = 0
            }
            val wasBusy = lastBusy
            val busyNow = pointerDown || dragging || scrolling || !scroller.isFinished
            setNativeTexturePrewarmPausedLocked(busyNow)
            if (busyNow != lastBusy) {
                request = setBusyLocked(busyNow) ?: request
            } else if (busyNow) {
                request = windowRequestLocked(true) ?: request
            }
            if (wasBusy && !busyNow) boundary = boundaryRequestLocked() ?: boundary
            if (!busyNow) {
                val nowMs = SystemClock.uptimeMillis()
                val recentScrollStats = nowMs <= programmaticScrollStatsUntilMs ||
                    (lastScrollInteractionMs > 0L &&
                        nowMs - lastScrollInteractionMs <= PROGRAMMATIC_SCROLL_STATS_RECENT_MS)
                if (recentScrollStats) {
                    if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
                } else {
                    applyPendingPageResolvesLocked()
                }
            }
            if (limitScrollToDrawablePrefix) {
                applyVisiblePendingDrawableResolvesLocked()
            }
            // Every original is already decoded. Keep only a bounded forward HWUI upload runway
            // moving with the latest downward viewport so page-boundary frames never pay first-
            // use bitmap preparation and the RenderThread is never flooded by the whole episode.
            prepareHwuiForwardRunwayLocked()
            // A drag advances only when a real MOVE changes scrollOffset; that MOVE already sets
            // renderRequested and schedules its presentation frame. Treating the mere
            // `dragging` state as an animation submits unchanged full-screen frames between input
            // samples and can block the next MOVE/UP behind RenderThread backpressure.
            val animateScroll = scrolling || !scroller.isFinished
            val shouldDraw = (renderRequested || animateScroll) && pages.isNotEmpty()
            val deferInitialEmptyDraw = shouldDeferInitialEmptyDrawLocked()
            var state = if (shouldDraw && !deferInitialEmptyDraw) buildDrawStateLocked(busyNow) else null
            val exactStructureHeld = state?.let(::hasNonContiguousExactNativeStructure) == true
            if (exactStructureHeld) {
                val heldState = checkNotNull(state)
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastExactStructureHoldLogMs >= EXACT_STRUCTURE_HOLD_LOG_INTERVAL_MS) {
                    lastExactStructureHoldLogMs = nowMs
                    Log.d(
                        TAG,
                        "reader_native_exact_structure_hold visible=" +
                            heldState.items.joinToString("|") { it.index.toString() } +
                            ",scroll=${fmt(heldState.scrollOffset)},busy=$busyNow",
                    )
                }
                boundary?.let { heldBoundary ->
                    boundaryDispatchInFlight = false
                    boundaryArmedDirection = heldBoundary.direction
                    boundary = null
                }
                state = null
                renderRequested = true
            }
            if (state == null && shouldLogProgrammaticScrollDiagnosticLocked()) {
                Log.d(
                    TAG,
                    "reader_test_scroll_state_null shouldDraw=$shouldDraw renderRequested=$renderRequested " +
                        "animateScroll=$animateScroll pages=${pages.size} defer=$deferInitialEmptyDraw " +
                        "busy=$busyNow scroll=${fmt(scrollOffset)}"
                )
            }
            if (renderRequested && pages.isEmpty()) renderRequested = false
            val version = if (state != null) desiredVersion else 0L
            val mutation = if (shouldConsumePixelMutationTiming(
                    hasDrawState = state != null,
                    pendingWatermark = pendingPixelMutationWatermark
                )
            ) {
                consumePendingPixelMutationTimingLocked(inFlightInvalidationPostedNs)
            } else {
                null
            }
            if (state != null) {
                renderRequested = false
                drawnVersion = version
            } else {
                if (renderRequested && pages.isNotEmpty() &&
                    (!exactStructureHeld || !animateScroll)
                ) {
                    scheduleNoStateRetryLocked()
                }
                // No reader draw was recorded, so there is no reader HWUI submission to prove.
                // Waiting for an unrelated root commit here held the only admission token for
                // another 1-3 vsyncs during touch input. Unregister this no-op callback and release
                // immediately; any racing callback is rejected by its now-stale unique token.
                val callback = inFlightCommitCallback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    callback != null && inFlightCommitCallbackRegistered
                ) {
                    val observer = viewTreeObserver
                    if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
                }
                releasePostedAdmissionLocked(preserveDirty = true)
                if (exactStructureHeld && animateScroll) {
                    scheduleFrameLocked()
                }
            }
            RenderWork(request, boundary, state, admittedToken, admittedEpoch, version, mutation)
        }
        val state = work.state ?: run {
            dispatchWindowRequest(work.request)
            dispatchBoundaryRequest(work.boundary)
            synchronized(stateLock) {
                if (shouldLogProgrammaticScrollDiagnosticLocked()) {
                    Log.d(TAG, "reader_test_scroll_draw_skipped state=null")
                }
            }
            return false
        }
        val nativeSubmission = directSurface && canvas == null
        val timing = if (nativeSubmission) {
            submitNativeFrame(frameTimeNanos, callbackStartNs, state, work)
        } else {
            drawState(frameTimeNanos, callbackStartNs, state, checkNotNull(canvas), work.mutation)
        }
        if (nativeSubmission && !timing.posted) {
            dispatchWindowRequest(work.request)
            dispatchBoundaryRequest(work.boundary)
            synchronized(stateLock) {
                if (work.frameEpoch == lifecycleEpoch && work.frameToken == inFlightToken) {
                    drawnVersion = latestSubmittedVersionLocked()
                    releasePostedAdmissionLocked(preserveDirty = true)
                    scheduleNoStateRetryLocked()
                    stateLock.notifyAll()
                }
            }
            return false
        }
        dispatchWindowRequest(work.request)
        dispatchBoundaryRequest(work.boundary)
        val nowMs = SystemClock.uptimeMillis()
            val slowFrameLogBudget = IDLE_SLOW_FRAME_LOG_BUDGET_MS
        val renderWorkMs = timing.drawMs
        if (renderWorkMs > slowFrameLogBudget && nowMs - lastSlowFrameLogMs >= SLOW_FRAME_LOG_INTERVAL_MS) {
            lastSlowFrameLogMs = nowMs
            Log.d(
                TAG,
                "reader_slow_frame busy=${state.busy} items=${state.items.size} " +
                    "visibleLoading=${state.visibleLoading} prepMs=${fmt(timing.lockWaitMs)} drawMs=${fmt(timing.drawMs)} " +
                    "totalMs=${fmt(timing.totalMs)} visibleItems=${formatDrawItems(state.items)}"
            )
        }
        val coverage = coverageStats(state)
        if (listener?.shouldReportVisibleStats() != false) {
            if (lastVisibleLoading != state.visibleLoading) {
                lastVisibleLoading = state.visibleLoading
                Log.i(
                    TAG,
                    "reader_visible_loading=${state.visibleLoading} busy=${state.busy} " +
                        "items=${state.items.size} visibleItems=${formatDrawItems(state.items)}"
                )
                logCoverageIfNeeded(state, coverage, force = true)
            }
            logCoverageIfNeeded(state, coverage, force = false)
        }
        val completedCoverage = updateVisibleCoverageSnapshot(state, coverage)
        val traversalProof = frameTraversalProof(state, completedCoverage)
        val rollingVisiblePageIndexes = state.items.asSequence()
            .filter { item ->
                item.cardText == null &&
                    item.top < state.height.toFloat() &&
                    item.top + item.pageHeight > 0f
            }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()
        // Identity belongs to the exact submitted pixels even when the generic traversal counter
        // is not armed for this frame. Keeping it only inside FrameTraversalProof made a valid
        // old-structure buffer fall back to live session indexes while a prefix removal was
        // crossing from the session control lane to the Surface main-thread callback.
        val rollingVisiblePageIdentities = state.items.asSequence()
            .filter { item -> item.top < state.height.toFloat() && item.top + item.pageHeight > 0f }
            .mapNotNull { item ->
                item.committedIdentity?.copy(displayPageIndex = item.index)
            }
            .distinctBy { it.displayPageIndex }
            .sortedBy { it.displayPageIndex }
            .toList()
        if (traversalProof != null &&
            (traversalProof.viewportDefect || traversalProof.runwayDefect)
        ) {
            val runway = state.forwardRunway
            Log.w(
                TAG,
                "reader_traversal_pixel_defect viewport=${traversalProof.viewportDefect}," +
                    "runway=${traversalProof.runwayDefect},scroll=${fmt(state.scrollOffset)}," +
                    "coverage=drawable:${completedCoverage.drawablePx}/" +
                    "${completedCoverage.physicalViewportPx},missing:${completedCoverage.missingPx}," +
                    "placeholder:${completedCoverage.placeholderPx}," +
                    "lowRes:${completedCoverage.lowResolutionItems}," +
                    "runwayProof=${runway?.drawableAheadPx ?: -1}/" +
                    "${runway?.availableAheadPx ?: -1}," +
                    "runwayRequired=${runway?.requiredAheadPx ?: -1}," +
                    "runwayMissing=${runway?.missingAheadPx ?: -1}," +
                    "runwayLowRes=${runway?.lowResolutionItems ?: -1}," +
                    "runwayFirstMissing=${runway?.firstMissingPage ?: -1}," +
                    "items=${formatDrawItems(state.items)}"
            )
        }
        var hardwareCommitUnavailable = false
        var earlyNativePresentation: EarlyNativePresentation? = null
        val fallbackCommit = synchronized(stateLock) {
            if (work.frameEpoch != lifecycleEpoch || work.frameToken != inFlightToken ||
                framePipe != FramePipe.INVALIDATION_POSTED
            ) {
                false
            } else {
                val callback = inFlightCommitCallback
                val callbackRegistered = inFlightCommitCallbackRegistered
                val pendingProof = PendingCommittedDraw(
                    work.frameToken,
                    work.drawnVersion,
                    canvas?.isHardwareAccelerated == true,
                    completedCoverage,
                    traversalProof,
                    state.traversalEpoch,
                    rollingVisiblePageIndexes,
                    rollingVisiblePageIdentities,
                    state.scrollOffset
                )
                if (callback == null) {
                    drawnVersion = latestSubmittedVersionLocked()
                    renderRequested = true
                    releasePostedAdmissionLocked(preserveDirty = true)
                    scheduleNoStateRetryLocked()
                    false
                } else if (!directSurface && !callbackRegistered &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas?.isHardwareAccelerated == true
                ) {
                    // Never turn Canvas-recording completion into a hardware submission proof.
                    // Drop this admission and retry explicitly; the strict API35 path must bind a
                    // real ViewTreeObserver frame-commit callback.
                    hardwareCommitUnavailable = true
                    drawnVersion = latestSubmittedVersionLocked()
                    renderRequested = true
                    releasePostedAdmissionLocked(preserveDirty = true)
                    scheduleNoStateRetryLocked()
                    false
                } else {
                    recordSubmittedTraversalProofLocked(traversalProof)
                    pendingFrameCommits[work.frameToken] = PendingFrameCommit(
                        frameEpoch = work.frameEpoch,
                        nativeSurfaceEpoch = timing.nativeSurfaceEpoch,
                        callback = callback,
                        callbackRegistered = callbackRegistered,
                        surfaceQueueSubmission = directSurface && !nativeSubmission,
                        surfaceControlSubmission = nativeSubmission,
                        proof = pendingProof
                    )
                    // The native renderer owns a different thread and can complete a very cheap
                    // queueBuffer before nativeSubmit() returns to this method. Consume that exact
                    // token's buffered proof now that its immutable Kotlin proof is registered.
                    earlyNativePresentation = earlyNativePresentations.remove(work.frameToken)
                    // Recording this display list completes the admission.  Its immutable proof now
                    // waits independently for HWUI commit, so the next vsync is not serialized behind
                    // host-GPU/RenderThread latency.
                    releasePostedAdmissionLocked(preserveDirty = false)
                    if (renderRequested || desiredVersion > drawnVersion || !scroller.isFinished) {
                        if (!scroller.isFinished) {
                            pendingPixelReasons = pendingPixelReasons or DIRTY_ANIMATION
                        }
                        scheduleFrameLocked()
                    }
                    !directSurface && !callbackRegistered
                }
            }
        }
        if (hardwareCommitUnavailable) {
            Log.e(TAG, "reader_frame_commit_callback_unavailable api=${Build.VERSION.SDK_INT}")
        }
        earlyNativePresentation?.let { early ->
            completeOrBufferNativePresentation(
                work.frameToken,
                early.presentedUptimeNanos,
                early.presentationKind,
                allowBuffer = false,
            )
        }
        if (fallbackCommit) {
            // API < 29/software fallback cannot provide a swap-chain callback. API 35 hardware,
            // which is the strict production path, always uses the pre-draw registered callback.
            mainHandler.post { onFrameCommitted(work.frameEpoch, work.frameToken) }
        }
        if (timing.posted) synchronized(stateLock) { lastPostedFrameEndNs = timing.postEndNs }
        val nowForStats = SystemClock.uptimeMillis()
        val edgeNoMovementSuppressed = nowForStats <= edgeNoMovementStatsSuppressedUntilMs
        val statsActive = !edgeNoMovementSuppressed &&
            (state.busy ||
                nowForStats <= programmaticScrollStatsUntilMs ||
                (nowForStats - lastScrollInteractionMs <= PROGRAMMATIC_SCROLL_STATS_RECENT_MS))
        recordFrameStats(timing, statsActive)
        return true
    }

    /**
     * Publishes only real, identity-bound pixels to the rolling native renderer. A rejected or
     * incomplete state leaves the preceding SurfaceControl buffer on screen and is retried; it
     * can never replace the screen with a placeholder/blank transaction.
     */
    private fun submitNativeFrame(
        frameTimeNs: Long,
        callbackStartNs: Long,
        state: DrawState,
        work: RenderWork
    ): DrawTiming {
        val submitBeginNs = System.nanoTime()
        val coverage = coverageStats(state)
        val cleanPixels = state.hasDrawableContent && state.visibleLoading == 0 &&
            coverage.missingPx == 0 && coverage.placeholderPx == 0 &&
            coverage.lowResolutionItems == 0 && coverage.drawableItems > 0
        var filterDirectWifiNativeTiles = false
        val nativeSnapshot = synchronized(stateLock) {
            // Only source-qualified direct-Wi-Fi episode paths may omit dead native uploads.
            // Requiring every real tiled item in this frame to carry one of those immutable
            // identities leaves carrier/SNI, previous episodes and unbound state unchanged.
            var sawTiledRealItem = false
            var everyTiledItemAuthorized = true
            for (item in state.items) {
                if (item.cardText != null || item.bitmap != null || item.tiles.isEmpty()) continue
                sawTiledRealItem = true
                if (item.committedIdentity?.normalizedEpisodePath !in
                    directWifiExpandedNativeTextureEpisodePaths
                ) {
                    everyTiledItemAuthorized = false
                    break
                }
            }
            filterDirectWifiNativeTiles = directWifiExpandedNativeTextureRunway &&
                directWifiExpandedNativeTextureEpisodePaths.isNotEmpty() &&
                sawTiledRealItem && everyTiledItemAuthorized
            longArrayOf(
                rollingNativeHandle,
                rollingNativeAttachEpoch,
                rollingNativeWidth.toLong(),
                rollingNativeHeight.toLong(),
                if (rollingNativeFatal) 1L else 0L,
                rollingNativeViewportWidth.toLong(),
                rollingNativeViewportHeight.toLong()
            )
        }
        // The SurfaceControl parent remains display-sized. Geometry is expressed in the smaller
        // source-native render target and the child layer is scaled once at composition time.
        val nativeWidth = nativeSnapshot[2].toInt()
        val nativeHeight = nativeSnapshot[3].toInt()
        val native = if (nativeSnapshot[0] != 0L && nativeSnapshot[1] > 0L &&
            nativeWidth > 0 && nativeHeight > 0 && nativeSnapshot[4] == 0L &&
            nativeSnapshot[5].toInt() == state.width &&
            nativeSnapshot[6].toInt() == state.height
        ) {
            nativeSnapshot[0]
        } else {
            0L
        }
        val geometryScaleY = if (native != 0L && state.height > 0) {
            nativeHeight.toFloat() / state.height.toFloat()
        } else {
            1f
        }
        val bitmaps = ArrayList<Bitmap>()
        val integers = ArrayList<Int>()
        val geometry = ArrayList<Float>()
        if (cleanPixels) {
            for (item in state.items) {
                if (item.top >= state.height || item.top + item.pageHeight <= 0f) continue
                val pageBitmap = if (item.cardText != null) item.cardBitmap else item.bitmap
                if (pageBitmap != null && !pageBitmap.isRecycled) {
                    bitmaps += pageBitmap
                    integers += item.index
                    integers += 0
                    integers += 0
                    integers += pageBitmap.height
                    integers += pageBitmap.width
                    integers += pageBitmap.height
                    integers += System.identityHashCode(pageBitmap)
                    geometry += item.top * geometryScaleY
                    geometry += item.pageHeight * geometryScaleY
                } else {
                    item.tiles.forEachIndexed { slot, tile ->
                        val bitmap = tile.bitmap
                        if (bitmap.isRecycled) return@forEachIndexed
                        if (filterDirectWifiNativeTiles &&
                            !nativeTileIntersectsViewport(
                                item = item,
                                tile = tile,
                                geometryScaleY = geometryScaleY,
                                nativeHeight = nativeHeight,
                            )
                        ) return@forEachIndexed
                        bitmaps += bitmap
                        integers += item.index
                        integers += slot
                        integers += tile.sourceTop
                        integers += tile.sourceBottom
                        integers += tile.sourceWidth
                        integers += tile.sourceHeight
                        integers += System.identityHashCode(bitmap)
                        geometry += item.top * geometryScaleY
                        geometry += item.pageHeight * geometryScaleY
                    }
                }
            }
        }
        // The SurfaceControl parent and the reader frame pipe intentionally have different
        // lifetimes. Page/session mutations advance frameEpoch without replacing the physical
        // Surface. Requiring attachEpoch == frameEpoch therefore rejected every frame following
        // the first content-state reset even though the attached Surface was still valid.
        val accepted = cleanPixels && bitmaps.isNotEmpty() && native != 0L && runCatching {
            Trace.beginSection("ViewerSurfaceControlSubmission")
            NtkRollingNativeBridge.nativeSubmit(
                native,
                work.frameToken,
                state.traversalEpoch,
                nativeWidth,
                nativeHeight,
                integers.toIntArray(),
                geometry.toFloatArray(),
                bitmaps.toTypedArray()
            )
        }.getOrDefault(false).also {
            Trace.endSection()
        }
        val diagnosticNowMs = SystemClock.uptimeMillis()
        if (accepted && !firstNativeSubmitAccepted) {
            firstNativeSubmitAccepted = true
            Log.i(
                TAG,
                    "reader_native_submit_first token=${work.frameToken} frameEpoch=${work.frameEpoch} " +
                    "attachEpoch=${nativeSnapshot[1]} tiles=${bitmaps.size} " +
                    "render=${nativeWidth}x$nativeHeight viewport=${state.width}x${state.height} " +
                    "traversal=${state.traversalEpoch}"
            )
        } else if (!accepted &&
            diagnosticNowMs - lastNativeSubmitDiagnosticLogMs >= NATIVE_SUBMIT_DIAGNOSTIC_INTERVAL_MS
        ) {
            lastNativeSubmitDiagnosticLogMs = diagnosticNowMs
            Log.d(
                TAG,
                "reader_native_submit_rejected token=${work.frameToken} frameEpoch=${work.frameEpoch} " +
                    "attachEpoch=${nativeSnapshot[1]} handle=${nativeSnapshot[0] != 0L} " +
                    "fatal=${nativeSnapshot[4] != 0L} clean=$cleanPixels bitmaps=${bitmaps.size} " +
                    "coverage=$coverage nativeSize=${nativeSnapshot[2]}x${nativeSnapshot[3]} " +
                    "nativeViewport=${nativeSnapshot[5]}x${nativeSnapshot[6]} " +
                    "frameSize=${state.width}x${state.height}"
            )
        }
        val submitEndNs = System.nanoTime()
        val mutation = work.mutation
        return DrawTiming(
            frameTimeNs = frameTimeNs,
            callbackStartNs = callbackStartNs,
            lockWaitMs = nsToMs(submitBeginNs - callbackStartNs),
            drawMs = nsToMs(submitEndNs - submitBeginNs),
            postMs = 0f,
            totalMs = nsToMs(submitEndNs - callbackStartNs),
            postEndNs = submitEndNs,
            posted = accepted,
            mutationWatermark = mutation?.watermark ?: 0L,
            mutationOldestToCallbackMs = causalPixelMutationAgeMs(
                mutation?.oldestNs ?: 0L,
                callbackStartNs
            ),
            mutationNewestToCallbackMs = causalPixelMutationAgeMs(
                mutation?.newestNs ?: 0L,
                callbackStartNs
            ),
            mutationOldestToPostMs = causalPixelMutationAgeMs(
                mutation?.oldestNs ?: 0L,
                submitEndNs
            ),
            mutationNewestToPostMs = causalPixelMutationAgeMs(
                mutation?.newestNs ?: 0L,
                submitEndNs
            ),
            nativeSurfaceEpoch = if (accepted) nativeSnapshot[1] else 0L,
            invalidationToCallbackMs = causalPixelMutationAgeMs(
                mutation?.invalidationPostedNs ?: 0L,
                callbackStartNs
            ),
            invalidationToPostMs = causalPixelMutationAgeMs(
                mutation?.invalidationPostedNs ?: 0L,
                submitEndNs
            )
        )
    }

    /** Mirrors the native renderer's draw-time rejection before its texture-upload loop. */
    private fun nativeTileIntersectsViewport(
        item: DrawItem,
        tile: ReaderTile,
        geometryScaleY: Float,
        nativeHeight: Int,
    ): Boolean {
        if (nativeHeight <= 0 || item.pageHeight <= 0f || tile.sourceHeight <= 0 ||
            tile.sourceTop < 0 || tile.sourceBottom <= tile.sourceTop ||
            tile.sourceBottom > tile.sourceHeight
        ) return true
        val pageTop = item.top * geometryScaleY
        val pageHeight = item.pageHeight * geometryScaleY
        val tileTop = pageTop + pageHeight *
            tile.sourceTop.toFloat() / tile.sourceHeight.toFloat()
        val tileBottom = pageTop + pageHeight *
            tile.sourceBottom.toFloat() / tile.sourceHeight.toFloat()
        return tileBottom > 0f && tileTop < nativeHeight.toFloat()
    }

    private fun drawState(
        frameTimeNs: Long,
        callbackStartNs: Long,
        state: DrawState,
        canvas: Canvas,
        mutation: PixelMutationTiming?
    ): DrawTiming {
        val drawStartNs = System.nanoTime()
        var drawEndNs = drawStartNs
        try {
            Trace.beginSection("RSV.draw")
            if (!state.realPixelsOnly && !drawableContentCoversViewport(state)) {
                canvas.drawColor(PAGE_PLACEHOLDER_COLOR)
            }
            if (!state.empty) {
                val directPreparedBitmap = state.realPixelsOnly || state.directPreparedBitmapBatch
                if (directPreparedBitmap || !drawPreparedRenderScene(canvas, state)) {
                    var index = 0
                    while (index < state.items.size) {
                        drawItem(
                            canvas,
                            state,
                            state.items[index],
                            allowRetainedPageNode = !directPreparedBitmap
                        )
                        index++
                    }
                }
            }
            drawScrollbar(canvas, state)
            if (state.hasDrawableContent && !hasDrawnContentFrame) {
                hasDrawnContentFrame = true
            }
        } finally {
            Trace.endSection()
            drawEndNs = System.nanoTime()
        }
        val postEndNs = System.nanoTime()
        val mutationOldestToCallbackMs = causalPixelMutationAgeMs(
            mutation?.oldestNs ?: 0L,
            callbackStartNs
        )
        val mutationNewestToCallbackMs = causalPixelMutationAgeMs(
            mutation?.newestNs ?: 0L,
            callbackStartNs
        )
        val mutationOldestToPostMs = causalPixelMutationAgeMs(
            mutation?.oldestNs ?: 0L,
            postEndNs
        )
        val mutationNewestToPostMs = causalPixelMutationAgeMs(
            mutation?.newestNs ?: 0L,
            postEndNs
        )
        return DrawTiming(
            frameTimeNs = frameTimeNs,
            callbackStartNs = callbackStartNs,
            lockWaitMs = nsToMs(drawStartNs - callbackStartNs),
            drawMs = nsToMs(drawEndNs - drawStartNs),
            postMs = nsToMs(postEndNs - drawEndNs),
            totalMs = nsToMs(postEndNs - callbackStartNs),
            postEndNs = postEndNs,
            posted = true,
            mutationWatermark = mutation?.watermark ?: 0L,
            mutationOldestToCallbackMs = mutationOldestToCallbackMs,
            mutationNewestToCallbackMs = mutationNewestToCallbackMs,
            mutationOldestToPostMs = mutationOldestToPostMs,
            mutationNewestToPostMs = mutationNewestToPostMs,
            invalidationToCallbackMs = causalPixelMutationAgeMs(
                mutation?.invalidationPostedNs ?: 0L,
                callbackStartNs
            ),
            invalidationToPostMs = causalPixelMutationAgeMs(
                mutation?.invalidationPostedNs ?: 0L,
                postEndNs
            )
        )
    }

    private fun drawableContentCoversViewport(state: DrawState): Boolean {
        if (state.empty || !state.hasDrawableContent || state.items.isEmpty()) return false
        val coverageTolerance = if (state.realPixelsOnly) 0f else DRAW_COVERAGE_EPSILON_PX
        var coveredUntil = 0f
        var sawDrawable = false
        for (item in state.items) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) continue
            if (!itemHasDrawable(item)) return false
            if (!sawDrawable) {
                if (visibleTop > coverageTolerance) return false
                sawDrawable = true
            } else if (visibleTop - coveredUntil > coverageTolerance) {
                return false
            }
            coveredUntil = max(coveredUntil, visibleBottom)
            if (coveredUntil >= state.height - coverageTolerance) return true
        }
        return false
    }

    private fun lastVisibleCoverageIsClean(): Boolean {
        val coverage = lastVisibleCoverageSnapshot ?: return false
        return coverage.drawablePx > 0 &&
            coverage.missingPx == 0 &&
            coverage.placeholderPx == 0 &&
            coverage.visibleLoading == 0
    }

    private fun drawItem(
        canvas: Canvas,
        state: DrawState,
        item: DrawItem,
        allowRetainedPageNode: Boolean
    ) {
        val bitmap = item.bitmap
        val cardText = item.cardText
        if (cardText != null) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) return
            val centerY = item.top + item.pageHeight / 2f
            val save = canvas.save()
            canvas.clipRect(0f, visibleTop, state.width.toFloat(), visibleBottom)
            // Fill the complete structural slot. The old 12 px transparent margins retained
            // pixels from a previous Surface buffer at the exact episode boundary.
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawRect(
                0f,
                item.top,
                state.width.toFloat(),
                item.top + item.pageHeight,
                paint
            )
            drawTransitionCardText(
                canvas = canvas,
                width = state.width,
                centerY = centerY,
                title = cardText,
                labelPaint = textPaint
            )
            canvas.restoreToCount(save)
            return
        }
        val errorText = item.errorText
        if (errorText != null) {
            val visibleTop = max(0f, item.top)
            val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
            if (visibleBottom <= visibleTop) return
            val save = canvas.save()
            canvas.clipRect(0f, visibleTop, state.width.toFloat(), visibleBottom)
            paint.style = Paint.Style.FILL
            paint.color = PAGE_PLACEHOLDER_COLOR
            canvas.drawRect(0f, visibleTop, state.width.toFloat(), visibleBottom, paint)
            textPaint.textSize = 42f
            textPaint.color = Color.rgb(90, 90, 90)
            canvas.drawText(errorText, state.width / 2f, item.top + item.pageHeight / 2f + 15f, textPaint)
            canvas.restoreToCount(save)
            return
        }
        if (allowRetainedPageNode && drawRetainedPageNode(canvas, state, item)) return
        if (bitmap != null && !bitmap.isRecycled) {
            drawBitmapVisibleCrop(
                canvas,
                bitmap,
                item.top,
                item.pageHeight,
                state.width,
                state.height
            )
            return
        }
        if (item.tiles.isNotEmpty()) {
            drawTiles(canvas, state, item)
            return
        }
        if (state.realPixelsOnly) return
        paint.color = PAGE_PLACEHOLDER_COLOR
        dst.set(0f, max(0f, item.top), state.width.toFloat(), min(state.height.toFloat(), item.top + item.pageHeight))
        canvas.drawRect(dst, paint)
    }

    private fun drawBitmapVisibleCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        pageTop: Float,
        pageHeight: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || pageHeight <= 0f) return
        val visibleTop = max(0f, pageTop)
        val visibleBottom = min(viewHeight.toFloat(), pageTop + pageHeight)
        if (visibleBottom <= visibleTop) return

        val sourcePerDestination = bitmap.height / pageHeight
        val sourceTop = floor((visibleTop - pageTop) * sourcePerDestination)
            .toInt()
            .coerceIn(0, bitmap.height - 1)
        val sourceBottom = ceil((visibleBottom - pageTop) * sourcePerDestination)
            .toInt()
            .coerceIn(sourceTop + 1, bitmap.height)
        val destinationPerSource = pageHeight / bitmap.height.toFloat()
        val mappedTop = pageTop + sourceTop * destinationPerSource
        val mappedBottom = pageTop + sourceBottom * destinationPerSource

        src.set(0, sourceTop, bitmap.width, sourceBottom)
        dst.set(0f, mappedTop, viewWidth.toFloat(), mappedBottom)
        prepareBitmapPaint()
        paint.isFilterBitmap = bitmap.width != viewWidth
        val save = canvas.save()
        canvas.clipRect(0f, visibleTop, viewWidth.toFloat(), visibleBottom)
        canvas.drawBitmap(bitmap, src, dst, paint)
        canvas.restoreToCount(save)
    }

    private fun drawTiles(
        canvas: Canvas,
        state: DrawState,
        item: DrawItem
    ) {
        val visibleTop = max(0f, item.top)
        val visibleBottom = min(state.height.toFloat(), item.top + item.pageHeight)
        if (visibleBottom <= visibleTop) return
        prepareBitmapPaint()
        paint.isFilterBitmap = item.tiles.firstOrNull { !it.bitmap.isRecycled }
            ?.bitmap?.width != state.width
        val sourceHeight = item.tiles.firstOrNull()?.sourceHeight?.takeIf { it > 0 } ?: return
        val pageScale = item.pageHeight / sourceHeight.toFloat()
        val directTileDraw = abs(pageScale - 1f) <= DIRECT_TILE_DRAW_SCALE_EPSILON
        val visibleSourceTop = ((visibleTop - item.top) / pageScale).coerceAtLeast(0f)
        var low = 0
        var high = item.tiles.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (item.tiles[middle].sourceBottom <= visibleSourceTop) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        var tileIndex = low
        while (tileIndex < item.tiles.size) {
            val tile = item.tiles[tileIndex]
            tileIndex++
            val tileTop = item.top + tile.sourceTop * pageScale
            val tileBottom = item.top + tile.sourceBottom * pageScale
            if (tileBottom < visibleTop) continue
            if (tileTop > visibleBottom) break
            val bitmap = tile.bitmap
            if (bitmap.isRecycled || tile.sourceHeight <= 0) continue
            val drawTop = max(visibleTop, tileTop)
            val drawBottom = min(visibleBottom, tileBottom)
            if (drawBottom <= drawTop || tileBottom <= tileTop) continue
            val drawWidth = state.width
            val dstLeft = (state.width - drawWidth) / 2
            val bitmapSourceTop = tile.bitmapSourceTop()
            val bitmapSourceBottom = bitmapSourceTop + tile.sourceSpan
            if (directTileDraw && bitmap.width == drawWidth) {
                val srcTop = (bitmapSourceTop + floor(drawTop - tileTop).toInt())
                    .coerceIn(bitmapSourceTop, bitmapSourceBottom - 1)
                val srcBottom = (bitmapSourceTop + ceil(drawBottom - tileTop).toInt())
                    .coerceIn(srcTop + 1, bitmapSourceBottom)
                val dstTopInt = floor(drawTop).toInt()
                val dstBottomInt = (dstTopInt + (srcBottom - srcTop)).coerceAtLeast(dstTopInt + 1)
                src.set(0, srcTop, bitmap.width, srcBottom)
                dstInt.set(dstLeft, dstTopInt, dstLeft + drawWidth, dstBottomInt)
                canvas.drawBitmap(bitmap, src, dstInt, paint)
                continue
            }
            val srcTop = (bitmapSourceTop +
                (drawTop - tileTop) / (tileBottom - tileTop) * tile.sourceSpan)
                .toInt()
                .coerceIn(bitmapSourceTop, bitmapSourceBottom - 1)
            val srcBottom = (bitmapSourceTop +
                (drawBottom - tileTop) / (tileBottom - tileTop) * tile.sourceSpan)
                .toInt()
                .coerceIn(srcTop + 1, bitmapSourceBottom)
            src.set(0, srcTop, bitmap.width, srcBottom)
            val dstTop = if (drawTop > visibleTop) drawTop - TILE_SEAM_OVERLAP_PX else drawTop
            val dstBottom = if (drawBottom < visibleBottom) drawBottom + TILE_SEAM_OVERLAP_PX else drawBottom
            val dstTopInt = floor(dstTop).toInt()
            val dstBottomInt = ceil(dstBottom).toInt().coerceAtLeast(dstTopInt + 1)
            dstInt.set(dstLeft, dstTopInt, dstLeft + drawWidth, dstBottomInt)
            canvas.drawBitmap(bitmap, src, dstInt, paint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        synchronized(stateLock) {
            if (visibility != View.VISIBLE || !isShown) {
                // An invalidate posted while an ancestor is INVISIBLE can retain an old
                // IntendedVsync until the reader is revealed. Do not let staging consume the
                // one frame slot needed by the first real, visible reader frame.
                if (hasFrameWorkLocked()) clearFramePipeLocked(preserveDirty = true)
                return
            }
            if (renderRunning && (renderRequested || desiredVersion > committedVersion) && pages.isNotEmpty() &&
                !shouldBlockInitialEmptyFrameLocked()
            ) {
                scheduleFrameLocked()
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) refreshCachedDisplayTiming()
        synchronized(stateLock) {
            if (visibility != View.VISIBLE || !isShown) {
                if (hasFrameWorkLocked()) clearFramePipeLocked(preserveDirty = true)
                return
            }
            if (renderRunning && (renderRequested || desiredVersion > committedVersion) && pages.isNotEmpty() &&
                !shouldBlockInitialEmptyFrameLocked()
            ) {
                scheduleFrameLocked()
            }
        }
    }

    private fun prepareBitmapPaint() {
        paint.alpha = 255
        paint.colorFilter = null
        paint.isDither = false
        paint.isFilterBitmap = false
    }

    private fun drawRetainedPageNode(canvas: Canvas, state: DrawState, item: DrawItem): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !canvas.isHardwareAccelerated) return false
        if (item.bitmap?.isRecycled == true) return false
        if (item.bitmap == null && item.tiles.none { !it.bitmap.isRecycled }) return false
        val entry = retainedPageNode(item, state.width) ?: return false
        entry.node.translationX = 0f
        entry.node.translationY = item.top
        canvas.drawRenderNode(entry.node)
        return true
    }

    private fun drawPreparedRenderScene(canvas: Canvas, state: DrawState): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !canvas.isHardwareAccelerated) return false
        // Keep a captured scene alive through submission. Invalidators use state -> retained;
        // this path takes retained only and therefore cannot recreate the former ABBA cycle.
        return synchronized(retainedNodeLock) {
            val scene = state.preparedScene ?: return@synchronized false
            if (scene.generation != state.visualGeneration ||
                scene.pageCount != state.pageCount ||
                scene.width != state.width ||
                abs(scene.height - state.contentHeight) > PREPARED_SCENE_HEIGHT_EPSILON_PX
            ) {
                return@synchronized false
            }
            val viewportTop = state.scrollOffset
            val viewportBottom = viewportTop + state.height
            val chunks = scene.chunks
            var low = 0
            var high = chunks.size
            // Chunks are ordered and non-overlapping. Locate the first chunk whose bottom is
            // inside or beyond the viewport instead of scanning every prepared page each frame.
            while (low < high) {
                val middle = (low + high) ushr 1
                if (chunks[middle].bottom <= viewportTop) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            var drew = false
            var index = low
            while (index < chunks.size) {
                val chunk = chunks[index]
                if (chunk.top >= viewportBottom) break
                if (!chunk.node.hasDisplayList()) return@synchronized false
                chunk.node.translationX = 0f
                chunk.node.translationY = chunk.top - state.scrollOffset
                canvas.drawRenderNode(chunk.node)
                drew = true
                index++
            }
            drew
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun retainedPageNode(item: DrawItem, drawWidth: Int): RetainedPageNode? {
        val safeWidth = max(1, drawWidth)
        val safeHeight = max(1, ceil(item.pageHeight).toInt())
        val liveTiles = if (item.tiles.none { it.bitmap.isRecycled }) {
            item.tiles
        } else {
            item.tiles.filter { !it.bitmap.isRecycled }
        }
        synchronized(retainedNodeLock) {
            val cached = retainedPageNodes[item.index]
            if (cached != null &&
                cached.width == safeWidth &&
                cached.height == safeHeight &&
                cached.bitmap === item.bitmap &&
                sameBitmapIdentity(cached.tileBitmaps, liveTiles)
            ) {
                if (cached.node.hasDisplayList()) return cached
                retainedPageNodes.remove(item.index)
            }
            if (item.bitmap == null && liveTiles.isEmpty()) {
                retainedPageNodes.remove(item.index)?.node?.discardDisplayList()
                return null
            }
            if (cached != null && retainedPageNodes[item.index] === cached) {
                retainedPageNodes.remove(item.index)
                cached.node.discardDisplayList()
            }
            val node = RenderNode("reader-page-${item.index}")
            node.setPosition(0, 0, safeWidth, safeHeight)
            val recording = node.beginRecording()
            try {
                prepareBitmapPaint()
                val bitmap = item.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    recording.drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, safeWidth.toFloat(), item.pageHeight),
                        paint
                    )
                } else {
                    val sourceHeight = liveTiles.firstOrNull()?.sourceHeight?.takeIf { it > 0 }
                        ?: return null
                    val pageScale = item.pageHeight / sourceHeight.toFloat()
                    for (tile in liveTiles) {
                        val top = tile.sourceTop * pageScale
                        val bottom = tile.sourceBottom * pageScale
                        if (bottom <= top) continue
                        val bitmapTop = tile.bitmapSourceTop()
                        recording.drawBitmap(
                            tile.bitmap,
                            Rect(
                                0,
                                bitmapTop,
                                tile.bitmap.width,
                                bitmapTop + tile.sourceSpan,
                            ),
                            RectF(0f, top, safeWidth.toFloat(), bottom),
                            paint
                        )
                    }
                }
            } finally {
                node.endRecording()
            }
            val entry = RetainedPageNode(
                node = node,
                bitmap = item.bitmap,
                tileBitmaps = liveTiles.map { it.bitmap },
                width = safeWidth,
                height = safeHeight
            )
            retainedPageNodes[item.index] = entry
            return entry
        }
    }

    private fun sameBitmapIdentity(cached: List<Bitmap>, tiles: List<ReaderTile>): Boolean {
        if (cached.size != tiles.size) return false
        for (index in cached.indices) {
            if (cached[index] !== tiles[index].bitmap) return false
        }
        return true
    }

    private fun buildPreparedBitmapPageNodes(
        bitmaps: List<Bitmap>,
        drawWidth: Int
    ): Map<Int, RetainedPageNode> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        return buildPreparedBitmapPageNodesApi29(bitmaps, drawWidth)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun buildPreparedBitmapScene(
        bitmaps: List<Bitmap>,
        drawWidth: Int
    ): PreparedRenderScene? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || bitmaps.isEmpty()) return null
        val safeWidth = max(1, drawWidth)
        var totalHeight = 0f
        val pageTops = FloatArray(bitmaps.size)
        val pageHeights = FloatArray(bitmaps.size)
        var allOpaque = true
        for (index in bitmaps.indices) {
            val bitmap = bitmaps[index]
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
            val pageHeight = max(1f, safeWidth * (bitmap.height / bitmap.width.toFloat()))
            pageTops[index] = totalHeight
            pageHeights[index] = pageHeight
            totalHeight += pageHeight
            if (index != bitmaps.lastIndex) totalHeight += pageGapPx
            allOpaque = allOpaque && !bitmap.hasAlpha()
        }
        val nodePaint = Paint().apply {
            alpha = 255
            colorFilter = null
            isDither = false
            isFilterBitmap = false
        }
        val chunks = ArrayList<PreparedRenderChunk>(bitmaps.size)
        var chunkStart = 0
        while (chunkStart < bitmaps.size) {
            val chunkEnd = chunkStart
            val chunkTop = pageTops[chunkStart]
            val chunkBottom = pageTops[chunkEnd] + pageHeights[chunkEnd]
            val node = RenderNode("reader-prepared-scene-$chunkStart")
            node.setPosition(0, 0, safeWidth, max(1, ceil(chunkBottom - chunkTop).toInt()))
            val recording = node.beginRecording()
            var failed: Throwable? = null
            try {
                for (index in chunkStart..chunkEnd) {
                    val localTop = pageTops[index] - chunkTop
                    val localBottom = localTop + pageHeights[index]
                    recording.drawBitmap(
                        bitmaps[index],
                        null,
                        RectF(0f, localTop, safeWidth.toFloat(), localBottom),
                        nodePaint
                    )
                }
            } catch (error: Throwable) {
                failed = error
            } finally {
                node.endRecording()
            }
            if (failed != null) {
                node.discardDisplayList()
                for (chunk in chunks) chunk.node.discardDisplayList()
                Log.d(TAG, "reader_prepared_scene_record_failed error=${failed.javaClass.simpleName}")
                return null
            }
            chunks.add(PreparedRenderChunk(node, chunkTop, chunkBottom))
            chunkStart = chunkEnd + 1
        }
        return PreparedRenderScene(chunks, bitmaps.size, safeWidth, max(1f, totalHeight), allOpaque)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun buildPreparedBitmapPageNodesApi29(
        bitmaps: List<Bitmap>,
        drawWidth: Int
    ): Map<Int, RetainedPageNode> {
        val safeWidth = max(1, drawWidth)
        val result = LinkedHashMap<Int, RetainedPageNode>(bitmaps.size)
        val nodePaint = Paint().apply {
            alpha = 255
            colorFilter = null
            isDither = false
            isFilterBitmap = false
        }
        try {
            for (index in bitmaps.indices) {
                val bitmap = bitmaps[index]
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalStateException("prepared bitmap invalid at $index")
                }
                val pageHeight = max(1f, safeWidth * (bitmap.height / bitmap.width.toFloat()))
                val safeHeight = max(1, ceil(pageHeight).toInt())
                val node = RenderNode("reader-page-$index")
                node.setPosition(0, 0, safeWidth, safeHeight)
                val recording = node.beginRecording()
                try {
                    recording.drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, safeWidth.toFloat(), pageHeight),
                        nodePaint
                    )
                } finally {
                    node.endRecording()
                }
                result[index] = RetainedPageNode(
                    node = node,
                    bitmap = bitmap,
                    tileBitmaps = emptyList(),
                    width = safeWidth,
                    height = safeHeight
                )
            }
            return result
        } catch (error: Throwable) {
            for (entry in result.values) entry.node.discardDisplayList()
            Log.d(TAG, "reader_prepared_node_record_failed error=${error.javaClass.simpleName}")
            return emptyMap()
        }
    }

    private fun discardRetainedPageNodesLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preparedRenderScene?.chunks?.forEach { it.node.discardDisplayList() }
            for (entry in retainedPageNodes.values) {
                entry.node.discardDisplayList()
            }
        }
        preparedRenderScene = null
        retainedPageNodes.clear()
    }

    /** Must be called with [stateLock] held. */
    private fun clearRetainedPageNodesStateLocked() {
        advanceVisualGenerationLocked()
        synchronized(retainedNodeLock) {
            discardRetainedPageNodesLocked()
        }
    }

    /** Must be called with [stateLock] held. Lock order is always state -> retained node. */
    private fun invalidateRetainedPageNodeStateLocked(index: Int) {
        advanceVisualGenerationLocked()
        synchronized(retainedNodeLock) {
            discardPreparedRenderSceneLocked()
            val removed = retainedPageNodes.remove(index)
            if (removed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                removed.node.discardDisplayList()
            }
        }
    }

    /** Must be called with [stateLock] held. */
    private fun invalidatePreparedRenderSceneStateLocked() {
        advanceVisualGenerationLocked()
        synchronized(retainedNodeLock) {
            discardPreparedRenderSceneLocked()
        }
    }

    private fun discardPreparedRenderSceneLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preparedRenderScene?.chunks?.forEach { it.node.discardDisplayList() }
        }
        preparedRenderScene = null
    }

    private fun advanceVisualGenerationLocked(): Long {
        directPreparedBitmapGeneration = Long.MIN_VALUE
        directPreparedBitmapWidth = 0
        directPreparedBitmapOpaque = false
        visualGeneration = if (visualGeneration == Long.MAX_VALUE) 1L else visualGeneration + 1L
        return visualGeneration
    }

    private fun preparedSceneForDrawLocked(viewWidth: Int): PreparedRenderScene? {
        val scene = preparedRenderScene ?: return null
        if (scene.generation != visualGeneration ||
            scene.pageCount != pages.size ||
            scene.width != viewWidth ||
            abs(scene.height - contentHeight) > PREPARED_SCENE_HEIGHT_EPSILON_PX
        ) {
            return null
        }
        return scene
    }

    private fun drawScrollbar(canvas: Canvas, state: DrawState) {
        if (!scrollbarVisible && !scrollbarDragging) return
        if (state.height <= 0 || state.contentHeight <= state.height) return
        val thumb = scrollbarThumbRectLocked(state.scrollOffset, state.contentHeight, state.height, state.width)
        paint.style = Paint.Style.FILL
        paint.color = SCROLLBAR_TRACK_COLOR
        dst.set(
            state.width - SCROLLBAR_RIGHT_MARGIN_PX - SCROLLBAR_TRACK_WIDTH_PX,
            0f,
            state.width - SCROLLBAR_RIGHT_MARGIN_PX,
            state.height.toFloat()
        )
        canvas.drawRoundRect(dst, SCROLLBAR_TRACK_RADIUS_PX, SCROLLBAR_TRACK_RADIUS_PX, paint)
        paint.color = if (scrollbarDragging) SCROLLBAR_THUMB_ACTIVE_COLOR else SCROLLBAR_THUMB_COLOR
        canvas.drawRoundRect(thumb, SCROLLBAR_THUMB_RADIUS_PX, SCROLLBAR_THUMB_RADIUS_PX, paint)
        paint.color = SCROLLBAR_GRIP_COLOR
        val gripLeft = thumb.left + SCROLLBAR_GRIP_INSET_PX
        val gripRight = thumb.right - SCROLLBAR_GRIP_INSET_PX
        val gripCenterY = thumb.centerY()
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY - SCROLLBAR_GRIP_GAP_PX,
            gripRight,
            gripCenterY - SCROLLBAR_GRIP_GAP_PX + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY,
            gripRight,
            gripCenterY + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
        canvas.drawRoundRect(
            gripLeft,
            gripCenterY + SCROLLBAR_GRIP_GAP_PX,
            gripRight,
            gripCenterY + SCROLLBAR_GRIP_GAP_PX + SCROLLBAR_GRIP_HEIGHT_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            SCROLLBAR_GRIP_RADIUS_PX,
            paint
        )
    }

    private fun startScrollbarDragLocked(x: Float, y: Float): Boolean {
        rebuildLayoutLocked()
        val viewWidth = width
        val viewHeight = height
        val maxScroll = max(0f, contentHeight - viewHeight)
        if (!scrollbarVisible) return false
        if (viewWidth <= 0 || viewHeight <= 0 || maxScroll <= 0f) return false
        if (x < viewWidth - SCROLLBAR_TOUCH_WIDTH_PX) return false
        val thumb = scrollbarThumbRectLocked(scrollOffset, contentHeight, viewHeight, viewWidth)
        scrollbarDragging = true
        scrollbarDragOffset = if (y in thumb.top..thumb.bottom) {
            y - thumb.top
        } else {
            thumb.height() / 2f
        }
        moveScrollbarDragLocked(y)
        return true
    }

    private fun moveScrollbarDragLocked(y: Float): WindowRequest? {
        rebuildLayoutLocked()
        val viewHeight = height
        val viewWidth = width
        val maxScroll = max(0f, contentHeight - viewHeight)
        if (viewWidth <= 0 || viewHeight <= 0 || maxScroll <= 0f) return null
        val thumbHeight = scrollbarThumbHeightLocked(contentHeight, viewHeight)
        val trackRange = max(1f, viewHeight - thumbHeight)
        val targetTop = (y - scrollbarDragOffset).coerceIn(0f, trackRange)
        val nextScroll = (targetTop / trackRange) * maxScroll
        if (abs(nextScroll - scrollOffset) <= 0.5f) return null
        setScrollOffsetLocked(nextScroll)
        clampScrollLocked()
        beginPhysicalScrollTraceLocked()
        lastScrollInteractionMs = SystemClock.uptimeMillis()
        boundaryArmedDirection = 0
        renderRequested = true
        scheduleFrameLocked()
        stateLock.notifyAll()
        return windowRequestLocked(true)
    }

    private fun scrollbarThumbRectLocked(scroll: Float, totalContentHeight: Float, viewHeight: Int, viewWidth: Int): RectF {
        val thumbHeight = scrollbarThumbHeightLocked(totalContentHeight, viewHeight)
        val maxScroll = max(1f, totalContentHeight - viewHeight)
        val trackRange = max(1f, viewHeight - thumbHeight)
        val top = (scroll.coerceIn(0f, maxScroll) / maxScroll) * trackRange
        val right = viewWidth - SCROLLBAR_RIGHT_MARGIN_PX - ((SCROLLBAR_TRACK_WIDTH_PX - SCROLLBAR_THUMB_WIDTH_PX) / 2f)
        return RectF(
            right - SCROLLBAR_THUMB_WIDTH_PX,
            top,
            right,
            top + thumbHeight
        )
    }

    private fun scrollbarThumbHeightLocked(totalContentHeight: Float, viewHeight: Int): Float {
        if (totalContentHeight <= 0f || viewHeight <= 0) return SCROLLBAR_MIN_THUMB_HEIGHT_PX
        val proportional = viewHeight * (viewHeight / totalContentHeight)
        return proportional.coerceIn(SCROLLBAR_MIN_THUMB_HEIGHT_PX, viewHeight.toFloat())
    }

    private fun buildDrawStateLocked(
        busy: Boolean = lastBusy,
        allowVisibleLoadingState: Boolean = false
    ): DrawState? {
        val viewWidth = max(1, width)
        val viewHeight = max(1, height)
        if (pages.isEmpty()) {
            return DrawState(viewWidth, viewHeight, busy, true, 1, false, scrollOffset, contentHeight, 0, emptyList())
        }
        applyLockedRestorePositionLocked()
        clampScrollLocked()
        rebuildLayoutLocked()
        if (shouldHoldInitialAnchorRenderLocked()) {
            if (shouldLogProgrammaticScrollDiagnosticLocked()) {
                Log.d(
                    TAG,
                    "reader_test_scroll_build_null reason=initial_anchor_hold " +
                        "holdPage=$initialRenderHoldPage drawable=${pageHasDrawableContentLocked(initialRenderHoldPage)} " +
                        "anyDrawable=${hasAnyDrawableContentLocked()}"
                )
            }
            renderRequested = true
            scheduleFrameLocked()
            return null
        }
        val items = ArrayList<DrawItem>()
        var visibleLoading = 0
        var hasDrawableContent = false
        var index = firstVisiblePageLocked(scrollOffset)
        while (index > 0 && pageTopLocked(index) - scrollOffset > 0f) {
            index--
        }
        while (index < pages.size) {
            val page = pages[index]
            val laidOutTop = pageTopLocked(index) - scrollOffset
            val pageHeight = pageDrawHeightLocked(page)
            var top = laidOutTop
            val previousBottom = items.lastOrNull()?.let { it.top + it.pageHeight }
            if (!inlineRealPixelsOnly) {
                if (previousBottom == null) {
                    if (top > COVERAGE_EDGE_FILL_PX && top < viewHeight) {
                        top = 0f
                    }
                } else {
                    if (previousBottom < viewHeight) {
                        if (laidOutTop > previousBottom + COVERAGE_EDGE_FILL_PX ||
                            laidOutTop < previousBottom - COVERAGE_EDGE_FILL_PX
                        ) {
                            top = previousBottom
                        }
                    }
                }
            }
            if (top > viewHeight) break
            val bottom = top + pageHeight
            if (bottom > 0f && top < viewHeight) {
                val item = DrawItem(
                    index = index,
                    bitmap = page.bitmap,
                    cardBitmap = page.cardBitmap,
                    tiles = page.tiles,
                    sourceWidth = page.width,
                    sourceHeight = page.height,
                    originalProof = page.originalProof,
                    stripAuthoritative = stripAuthorityToken != 0L &&
                        page.stripAuthority == stripAuthorityToken,
                    adjacentExactAuthoritative = page.adjacentExactOwner != null,
                    stripAsset = page.stripAsset ?: page.adjacentExactOwner?.canonicalAsset,
                    committedIdentity = page.committedIdentity,
                    loading = page.loading,
                    cardText = page.cardText,
                    errorText = page.errorText,
                    top = top,
                    pageHeight = pageHeight
                )
                val hasLiveDrawable = itemHasDrawable(item)
                val isImagePlaceholder = !hasLiveDrawable && page.cardText == null && page.errorText == null
                if (inlineRealPixelsOnly && !hasLiveDrawable && page.cardText == null) {
                    index++
                    continue
                }
                if (
                    shouldSkipLeadingStructuralPlaceholderLocked(
                        index,
                        top,
                        bottom,
                        viewHeight,
                        items.isEmpty()
                    )
                ) {
                    index++
                    continue
                }
                if (!inlineRealPixelsOnly &&
                    isImagePlaceholder &&
                    top >= viewHeight - COVERAGE_EDGE_PLACEHOLDER_FILL_PX &&
                    ceil(min(viewHeight.toFloat(), bottom) - max(0f, top)).toInt() <= COVERAGE_EDGE_PLACEHOLDER_FILL_PX &&
                    items.lastOrNull()?.let { itemHasDrawable(it) } == true
                ) {
                    renderRequested = true
                    scheduleFrameLocked()
                    index++
                    continue
                }
                if (isImagePlaceholder) {
                    visibleLoading++
                }
                if (hasLiveDrawable) {
                    hasDrawableContent = true
                }
                items.add(item)
            }
            index++
        }
        // Never stretch the terminal bitmap to manufacture physical-viewport coverage. The
        // fixed page scale remains authoritative; any real uncovered pixels are background and
        // any not-yet-decoded pixels remain visible to coverage accounting.
        val visibleContentPxOverride = -1
        val compatiblePreparedScene = if (inlineRealPixelsOnly) {
            null
        } else {
            preparedSceneForDrawLocked(viewWidth)
        }
        // Progressive current pixels remain visible immediately. Once the exact forward runway
        // fills a short terminal restore, classify only the source-qualified combined viewport.
        // This live DrawState check also covers a p0 too short to fill the screen until p1/p2 arrive.
        val forwardOnlyInitialResumeViewport = if (emulatorNativeSurfaceRuntime) {
            qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()
        } else {
            directWifiForwardOnlyInitialResumeRevealQualified &&
                directWifiForwardOnlyInitialResumeViewportOpaqueLocked()
        }
        val forwardOnlyTerminalTailActual =
            directWifiForwardOnlyTerminalTailRevealQualified ||
                directWifiForwardOnlyTerminalTailActualLocked()
        val state = DrawState(
            viewWidth,
            viewHeight,
            busy,
            false,
            visibleLoading,
            hasDrawableContent,
            scrollOffset,
            contentHeight,
            pages.size,
            items,
            visibleContentPxOverride,
            compatiblePreparedScene,
            visualGeneration,
            !inlineRealPixelsOnly &&
                (BITMAP_SUBMISSION_MODE == BitmapSubmissionMode.DIRECT_VISIBLE_CROP ||
                    (directPreparedBitmapGeneration == visualGeneration &&
                        directPreparedBitmapWidth == viewWidth)),
            inlineRealPixelsOnly,
            traversalStructureEpoch,
            if (inlineRealPixelsOnly) {
                forwardRunwaySnapshotLocked(DEFAULT_FORWARD_RUNWAY_AHEAD_VIEWPORTS)
            } else {
                null
            },
            forwardOnlyInitialResumeViewport,
            forwardOnlyTerminalTailActual,
            // The expanded-runway bit is installed asynchronously after the exact manifest. A
            // slow current-page response can therefore leave it false for the very frame that
            // would skip that page (for example display 16 -> 19). Structural continuity belongs
            // to the host-emulator exact native surface itself; the helper below still requires
            // an authoritative exact item before it can hold a frame.
            NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce(
                emulatorRuntime = emulatorNativeSurfaceRuntime,
                realPixelsOnly = inlineRealPixelsOnly,
            ),
        )
        val nowMs = SystemClock.uptimeMillis()
        val recentScroll = nowMs <= programmaticScrollStatsUntilMs ||
            nowMs - lastScrollInteractionMs <= PROGRAMMATIC_SCROLL_STATS_RECENT_MS
        val holdInitialViewport = shouldHoldInitialViewportRenderLocked()
        val firstInitialVisibleItemMissing = !hasDrawnContentFrame &&
            state.items.firstOrNull()?.let { !itemHasDrawable(it) } == true
        val shouldHoldVisibleLoadingFrame = state.visibleLoading > 0 &&
            (limitScrollToDrawablePrefix ||
                (!recentScroll &&
                    (!hasDrawnContentFrame || holdInitialViewport || firstInitialVisibleItemMissing)))
        if (shouldHoldVisibleLoadingFrame) {
            val prefixState = drawablePrefixDrawStateOrNull(state, viewHeight)
            if (prefixState != null) {
                return prefixState
            }
            if (allowVisibleLoadingState) return state
            if (shouldLogProgrammaticScrollDiagnosticLocked()) {
                Log.d(
                    TAG,
                    "reader_test_scroll_build_null reason=visible_loading " +
                        "loading=${state.visibleLoading} drawn=$hasDrawnContentFrame viewportHold=$holdInitialViewport " +
                        "firstMissing=$firstInitialVisibleItemMissing recent=$recentScroll drawable=${state.hasDrawableContent}"
                )
            }
            renderRequested = true
            scheduleVisibleLoadingHoldRetryLocked()
            return null
        }
        if (state.visibleLoading == 0 || !holdInitialViewport) {
            clearInitialRenderHoldLocked()
        }
        if (!inlineRealPixelsOnly && hasDrawnContentFrame && !state.hasDrawableContent && !recentScroll) {
            if (shouldLogProgrammaticScrollDiagnosticLocked()) {
                Log.d(
                    TAG,
                    "reader_test_scroll_build_null reason=no_drawable_after_draw " +
                        "loading=${state.visibleLoading} recent=$recentScroll items=${state.items.size}"
                )
            }
            renderRequested = true
            scheduleFrameLocked()
            return null
        }
        return state
    }

    private fun shouldSkipLeadingStructuralPlaceholderLocked(
        index: Int,
        top: Float,
        bottom: Float,
        viewHeight: Int,
        leadingItem: Boolean
    ): Boolean {
        if (!leadingItem) return false
        if (SystemClock.uptimeMillis() > structuralScrollAdjustUntilMs) return false
        if (top >= 0f || bottom <= 0f) return false
        val visiblePx = ceil(min(viewHeight.toFloat(), bottom) - max(0f, top)).toInt()
        if (visiblePx <= 0) return false
        if (visiblePx > (viewHeight * PREPENDED_BOUNDARY_HOLD_MAX_FRACTION).toInt()) return false
        val page = pages.getOrNull(index) ?: return false
        if (page.cardText != null || page.errorText != null || page.bitmap != null || page.tiles.isNotEmpty()) {
            return false
        }
        if (!pageHasDrawableContentLocked(index + 1)) return false
        renderRequested = true
        scheduleFrameLocked()
        return true
    }

    private fun logCoverageIfNeeded(state: DrawState, coverage: CoverageStats, force: Boolean) {
        if (!force) {
            val now = SystemClock.uptimeMillis()
            val activeScroll = pointerDown || dragging || !scroller.isFinished || statsActive
            if (activeScroll) return
            val minInterval = if (state.busy || activeScroll) {
                ACTIVE_SCROLL_COVERAGE_LOG_INTERVAL_MS
            } else {
                BUSY_COVERAGE_LOG_INTERVAL_MS
            }
            if (now - lastCoverageLogMs < minInterval) return
        }
        if (force || coverage != lastCoverageLog) {
            lastCoverageLog = coverage
            lastCoverageLogMs = SystemClock.uptimeMillis()
            Log.i(
                TAG,
                "reader_visible_coverage drawablePx=${coverage.drawablePx} " +
                    "missingPx=${coverage.missingPx} placeholderPx=${coverage.placeholderPx} " +
                    "drawableItems=${coverage.drawableItems} items=${coverage.totalItems} " +
                    "lowResItems=${coverage.lowResolutionItems} minSourceWidth=${coverage.minDrawableSourceWidth}"
            )
            if (coverage.missingPx > COVERAGE_EDGE_FILL_PX) {
                Log.i(
                    TAG,
                    "reader_visible_gap scroll=${formatFloat(state.scrollOffset)} " +
                        "content=${formatFloat(state.contentHeight)} pages=${state.pageCount} " +
                        "busy=${state.busy} loading=${state.visibleLoading} " +
                        "items=${formatDrawItems(state.items)}"
                )
            }
        }
    }

    private fun updateVisibleCoverageSnapshot(state: DrawState, coverage: CoverageStats): VisibleCoverageSnapshot {
        val snapshot = visibleCoverageSnapshotFromState(state, coverage)
        val previous: VisibleCoverageSnapshot?
        synchronized(stateLock) {
            previous = lastVisibleCoverageSnapshot
            lastVisibleCoverageSnapshot = snapshot
        }
        if (
            snapshot.drawablePx > 0 &&
            snapshot.visibleLoading == 0 &&
            snapshot.missingPx == 0 &&
            snapshot.placeholderPx == 0
        ) {
            if (snapshot != previous)
                mainHandler.post { listener?.onVisibleCoverageChanged(snapshot) }
        }
        return snapshot
    }

    private fun visibleCoverageSnapshotFromState(
        state: DrawState,
        coverage: CoverageStats = coverageStats(state)
    ): VisibleCoverageSnapshot {
        val visibleContentPx = visibleContentPx(state)
        var visibleErrors = 0
        var visibleCards = 0
        for (item in state.items) {
            if (item.errorText != null) visibleErrors++
            if (item.cardText != null) visibleCards++
        }
        return VisibleCoverageSnapshot(
            viewportPx = visibleContentPx,
            drawablePx = coverage.drawablePx,
            missingPx = coverage.missingPx,
            placeholderPx = coverage.placeholderPx,
            drawableItems = coverage.drawableItems,
            totalItems = coverage.totalItems,
            visibleLoading = state.visibleLoading,
            visibleErrors = visibleErrors,
            visibleCards = visibleCards,
            busy = state.busy,
            pageCount = state.pageCount,
            lowResolutionItems = coverage.lowResolutionItems,
            minDrawableSourceWidth = coverage.minDrawableSourceWidth,
            physicalViewportPx = state.height,
            directWifiForwardOnlyInitialResume = state.directWifiForwardOnlyInitialResume,
            directWifiForwardOnlyTerminalTailActual =
                state.directWifiForwardOnlyTerminalTailActual
        )
    }

    private fun formatDrawItems(items: List<DrawItem>): String {
        if (items.isEmpty()) return "none"
        return items.joinToString(separator = "|") { item ->
            val bottom = item.top + item.pageHeight
            val state = when {
                itemHasDrawable(item) -> "draw"
                item.loading -> "load"
                else -> "empty"
            }
            "${item.index}:${formatFloat(item.top)}-${formatFloat(bottom)}:$state"
        }
    }

    private fun formatFloat(value: Float): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun coverageStats(state: DrawState): CoverageStats {
        if (state.empty) return CoverageStats(0, visibleContentPx(state), 0, 0, 0, 0, 0)
        val visibleContentPx = visibleContentPx(state)
        var drawablePx = 0
        var placeholderPx = 0
        var drawableItems = 0
        var lowResolutionItems = 0
        var minDrawableSourceWidth = Int.MAX_VALUE
        var coveredPx = 0
        var coveredUntilPx = 0
        val minimumReadableSourceWidth = minimumReadableSourceWidth(state.width, state.realPixelsOnly)
        for (item in state.items) {
            val top = floor(max(0f, item.top)).toInt().coerceIn(0, visibleContentPx)
            val bottom = ceil(min(visibleContentPx.toFloat(), item.top + item.pageHeight)).toInt().coerceIn(top, visibleContentPx)
            if (bottom <= top) continue
            // Adjacent float page edges can round outward to the same physical pixel. Counting
            // every item span independently double-counts that boundary (and can cancel a real
            // gap elsewhere). Accumulate the ordered vertical union instead. Draw items are built
            // in display order, so only the portion after the prior covered edge is new coverage.
            val uniqueTop = max(top, coveredUntilPx)
            val uniquePx = max(0, bottom - uniqueTop)
            coveredUntilPx = max(coveredUntilPx, bottom)
            if (uniquePx <= 0) continue
            coveredPx += uniquePx
            if (state.realPixelsOnly &&
                (item.stripAuthoritative || item.adjacentExactAuthoritative)
            ) {
                val sourceWidth = authoritativeStripSourceWidth(item)
                val tilePx = if (sourceWidth > 0) {
                    min(uniquePx, authoritativeStripDrawablePx(item, top, bottom))
                } else {
                    0
                }
                drawablePx += tilePx
                placeholderPx += max(0, uniquePx - tilePx)
                if (tilePx > 0) {
                    drawableItems++
                    minDrawableSourceWidth = min(minDrawableSourceWidth, sourceWidth)
                    if (sourceWidth < minimumReadableSourceWidth) lowResolutionItems++
                }
                continue
            }
            if (itemHasDrawable(item)) {
                val sourceWidth = drawableSourceWidth(
                    item,
                    requireOriginalProof = state.realPixelsOnly
                )
                if (!state.realPixelsOnly || sourceWidth > 0) {
                    drawablePx += uniquePx
                    drawableItems++
                    minDrawableSourceWidth = min(minDrawableSourceWidth, sourceWidth)
                    if (sourceWidth < minimumReadableSourceWidth) lowResolutionItems++
                } else {
                    placeholderPx += uniquePx
                    lowResolutionItems++
                }
            } else {
                placeholderPx += uniquePx
            }
        }
        val rawMissingPx = max(0, visibleContentPx - coveredPx)
        val missingTolerancePx = if (state.realPixelsOnly) 0 else COVERAGE_EDGE_FILL_PX
        val missingPx = if (rawMissingPx <= missingTolerancePx && placeholderPx == 0 && drawablePx > 0) {
            0
        } else {
            rawMissingPx
        }
        val boundedPlaceholderPx = placeholderPx.coerceIn(0, visibleContentPx)
        val boundedDrawablePx = (
            if (missingPx == 0) {
                max(drawablePx, visibleContentPx - boundedPlaceholderPx)
            } else {
                drawablePx
            }
        ).coerceIn(0, visibleContentPx)
        return CoverageStats(
            drawablePx = boundedDrawablePx,
            missingPx = missingPx,
            placeholderPx = boundedPlaceholderPx,
            drawableItems = drawableItems,
            totalItems = state.items.size,
            lowResolutionItems = lowResolutionItems,
            minDrawableSourceWidth = if (minDrawableSourceWidth == Int.MAX_VALUE) 0 else minDrawableSourceWidth
        )
    }

    private fun visibleContentPx(state: DrawState): Int {
        if (state.visibleContentPxOverride > 0) {
            return state.visibleContentPxOverride.coerceIn(0, state.height)
        }
        return ceil(
            min(
                state.height.toFloat(),
                max(0f, state.contentHeight - state.scrollOffset)
            )
        ).toInt().coerceIn(0, state.height)
    }

    private fun drawablePrefixDrawStateOrNull(state: DrawState, viewHeight: Int): DrawState? {
        if (!limitScrollToDrawablePrefix || state.items.isEmpty()) return null
        val prefixItems = ArrayList<DrawItem>()
        for (item in state.items) {
            if (!itemHasDrawable(item)) break
            prefixItems.add(item)
        }
        if (prefixItems.isEmpty()) return null
        return state.copy(
            visibleLoading = 0,
            hasDrawableContent = true,
            items = prefixItems,
            visibleContentPxOverride = viewHeight
        )
    }

    private fun authoritativeStripSourceWidth(item: DrawItem): Int {
        if ((!item.stripAuthoritative && !item.adjacentExactAuthoritative) ||
            item.sourceWidth <= 0 || item.sourceHeight <= 0 ||
            item.stripAsset.isNullOrBlank() ||
            !ReaderPreparedStore.isCanonicalOriginalProof(
                item.originalProof,
                item.stripAsset,
                item.sourceWidth,
                item.sourceHeight
            )
        ) return 0
        for (tile in item.tiles) {
            val span = tile.sourceBottom - tile.sourceTop
            if (tile.sourceTop < 0 || span <= 0 || tile.sourceBottom > item.sourceHeight ||
                tile.sourceWidth != item.sourceWidth || tile.sourceHeight != item.sourceHeight ||
                tile.bitmap.isRecycled || tile.bitmap.config != Bitmap.Config.ARGB_8888 ||
                tile.bitmap.isMutable || tile.bitmap.width != item.sourceWidth ||
                tile.bitmap.height != span
            ) return 0
        }
        return if (item.tiles.isEmpty()) 0 else item.sourceWidth
    }

    /** Counts the union of real resident tile rows intersecting this physical viewport page. */
    private fun authoritativeStripDrawablePx(item: DrawItem, visibleTop: Int, visibleBottom: Int): Int {
        if (visibleBottom <= visibleTop || item.pageHeight <= 0f || item.sourceHeight <= 0) return 0
        var covered = 0
        var cursor = visibleTop
        for (tile in item.tiles) {
            val tileTop = floor(item.top + tile.sourceTop * item.pageHeight / item.sourceHeight).toInt()
            val tileBottom = ceil(item.top + tile.sourceBottom * item.pageHeight / item.sourceHeight).toInt()
            val start = max(cursor, max(visibleTop, tileTop))
            val end = min(visibleBottom, tileBottom)
            if (end > start) {
                covered += end - start
                cursor = end
                if (cursor >= visibleBottom) break
            }
        }
        return covered.coerceIn(0, visibleBottom - visibleTop)
    }

    private fun itemHasDrawable(item: DrawItem): Boolean {
        if (item.cardText != null) return true
        val bitmap = item.bitmap
        if (bitmap != null && !bitmap.isRecycled) return true
        return item.tiles.any { !it.bitmap.isRecycled }
    }

    private fun drawableSourceWidth(item: DrawItem, requireOriginalProof: Boolean): Int {
        if (item.cardText != null) return Int.MAX_VALUE
        if (requireOriginalProof) {
            return if (usableAuthoritativeOriginalTilePage(
                    item.sourceWidth,
                    item.sourceHeight,
                    item.tiles,
                    item.originalProof
                )
            ) {
                item.originalProof!!.originalWidth
            } else {
                0
            }
        }
        val bitmap = item.bitmap
        if (bitmap != null && !bitmap.isRecycled) return bitmap.width
        return item.tiles
            .asSequence()
            .filter { !it.bitmap.isRecycled }
            .map { if (it.sourceWidth > 0) it.sourceWidth else it.bitmap.width }
            .minOrNull() ?: 0
    }

    private fun frameTraversalProof(
        state: DrawState,
        coverage: VisibleCoverageSnapshot
    ): FrameTraversalProof? {
        if (!state.realPixelsOnly || state.pageCount <= 0 || state.traversalEpoch <= 0L) {
            return null
        }
        val physicalViewport = coverage.physicalViewportPx
        val visibleItems = state.items.filter { item ->
            item.top < state.height.toFloat() && item.top + item.pageHeight > 0f
        }
        val exactStructureContinuous = !hasNonContiguousExactNativeStructure(state)
        val authoritativeVisible = exactStructureContinuous && visibleItems.isNotEmpty() &&
            visibleItems.all { item ->
            if (item.cardText != null) true else if (
                item.stripAuthoritative || item.adjacentExactAuthoritative
            ) {
                authoritativeStripSourceWidth(item) > 0
            } else {
                usableAuthoritativeOriginalTilePage(
                    item.sourceWidth,
                    item.sourceHeight,
                    item.tiles,
                    item.originalProof
                )
            }
        }
        val viewportDefect = physicalViewport <= 0 ||
            coverage.viewportPx < physicalViewport ||
            coverage.drawablePx < physicalViewport ||
            coverage.missingPx != 0 || coverage.placeholderPx != 0 ||
            coverage.visibleLoading != 0 || coverage.visibleErrors != 0 ||
            coverage.widthFillFailures != 0 ||
            coverage.lowResolutionItems != 0 || !authoritativeVisible
        val runway = state.forwardRunway
        val runwayDefect = runway == null || runway.missingAheadPx != 0 ||
            runway.lowResolutionItems != 0 ||
            (!runway.contentExhausted && runway.availableAheadPx < runway.requiredAheadPx)
        val visibleIndexes = if (viewportDefect) {
            IntArray(0)
        } else {
            visibleItems
                .filter { it.cardText == null }
                .map { it.index }
                .distinct()
                .sorted()
                .toIntArray()
        }
        val visibleIdentities = if (viewportDefect) {
            emptyList()
        } else {
            visibleItems.mapNotNull { item ->
                item.committedIdentity?.copy(displayPageIndex = item.index)
            }
        }
        return FrameTraversalProof(
            structureEpoch = state.traversalEpoch,
            visiblePageIndexes = visibleIndexes,
            visiblePageIdentities = visibleIdentities,
            viewportDefect = viewportDefect,
            runwayDefect = runwayDefect
        )
    }

    private fun hasNonContiguousExactNativeStructure(state: DrawState): Boolean {
        if (!state.enforceContiguousExactStructure) return false
        val visibleItems = state.items.filter { item ->
            item.top < state.height.toFloat() && item.top + item.pageHeight > 0f
        }
        if (visibleItems.none {
                it.stripAuthoritative || it.adjacentExactAuthoritative ||
                    it.committedIdentity != null
            }
        ) {
            return false
        }
        return !NtkDirectWifiExactVisibleStructurePolicy.isContiguous(
            visibleItems.map { it.index }.toIntArray(),
        )
    }

    private fun recordSubmittedTraversalProofLocked(proof: FrameTraversalProof?) {
        if (proof == null || proof.structureEpoch != traversalStructureEpoch ||
            traversalExpectedPageCount <= 0
        ) {
            return
        }
        traversalSubmittedFrames++
        if (proof.viewportDefect) traversalSubmittedViewportDefectFrames++
        if (proof.runwayDefect) traversalSubmittedRunwayDefectFrames++
    }

    private fun recordCommittedTraversalProofLocked(proof: FrameTraversalProof?) {
        if (proof == null || proof.structureEpoch != traversalStructureEpoch ||
            traversalExpectedPageCount <= 0
        ) {
            return
        }
        traversalCommittedFrames++
        if (proof.viewportDefect) traversalCommittedViewportDefectFrames++
        if (proof.runwayDefect) traversalCommittedRunwayDefectFrames++
        if (!proof.viewportDefect) {
            proof.visiblePageIndexes.forEach { index ->
                if (index in traversalCommittedPages.indices) {
                    traversalCommittedPages[index] = true
                }
            }
        }
    }

    private fun shouldHoldInitialAnchorRenderLocked(): Boolean {
        val page = initialRenderHoldPage
        if (page !in 0 until pages.size) return false
        val now = SystemClock.uptimeMillis()
        if (now > initialRenderHoldUntilMs) {
            clearInitialRenderHoldLocked()
            return false
        }
        if (isRecentScrollStatsActiveLocked(now) && hasAnyDrawableContentLocked()) {
            clearInitialRenderHoldLocked()
            return false
        }
        if (!pageHasDrawableContentLocked(page)) return true
        return false
    }

    private fun shouldHoldInitialViewportRenderLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (isRecentScrollStatsActiveLocked(now) && hasAnyDrawableContentLocked()) return false
        return now <= max(initialRenderHoldUntilMs, initialViewportHoldUntilMs)
    }

    private fun shouldDeferDrawableReplacementLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        return lastBusy ||
            pointerDown ||
            dragging ||
            !scroller.isFinished ||
            isRecentScrollStatsActiveLocked(now)
    }

    private fun clearInitialRenderHoldLocked() {
        initialRenderHoldPage = -1
        initialRenderHoldUntilMs = 0L
        initialViewportHoldUntilMs = 0L
    }

    private fun shouldSuppressInitialEmptyRenderLocked(): Boolean {
        val page = initialRenderHoldPage
        return page in 0 until pages.size &&
            SystemClock.uptimeMillis() <= initialRenderHoldUntilMs &&
            !pageHasDrawableContentLocked(page)
    }

    private fun shouldDeferInitialEmptyDrawLocked(): Boolean {
        if (!deferInitialEmptyDraw || pages.isEmpty()) return false
        if (hasAnyDrawableContentLocked()) {
            deferInitialEmptyDraw = false
            return false
        }
        return true
    }

    private fun shouldBlockInitialEmptyFrameLocked(): Boolean {
        return shouldSuppressInitialEmptyRenderLocked() || shouldDeferInitialEmptyDrawLocked()
    }

    private fun hasAnyDrawableContentLocked(): Boolean {
        for (index in pages.indices) {
            if (pageHasDrawableContentLocked(index)) return true
        }
        return false
    }

    private fun isRecentScrollStatsActiveLocked(nowMs: Long): Boolean {
        return nowMs <= programmaticScrollStatsUntilMs ||
            (lastScrollInteractionMs > 0L &&
                nowMs - lastScrollInteractionMs <= PROGRAMMATIC_SCROLL_STATS_RECENT_MS)
    }

    private fun pageHasDrawableContentLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        if (page.cardText != null || page.errorText != null) return true
        val bitmap = page.bitmap
        if (bitmap != null && !bitmap.isRecycled) return true
        return page.tiles.any { !it.bitmap.isRecycled }
    }

    /** Null means this is not an adjacent sparse page; otherwise returns its drawable prefix Y. */
    private fun adjacentExactPrefixBottomLocked(index: Int): Float? {
        val page = pages.getOrNull(index) ?: return null
        val owner = page.adjacentExactOwner ?: return null
        val plan = page.adjacentExactPlan ?: return pageTopOrElseLocked(index, 0f)
        if (owner.planDigest != plan.planDigest || owner.sourceKey != plan.sourceKey ||
            page.adjacentExactSlots.size != plan.tiles.size ||
            !ReaderPreparedStore.isCanonicalOriginalProof(
                page.originalProof,
                owner.canonicalAsset,
                plan.sourceWidth,
                plan.sourceHeight,
            )
        ) return pageTopOrElseLocked(index, 0f)
        val sourceBottom = contiguousAdjacentExactSourceBottom(plan, page.adjacentExactSlots)
        return pageTopOrElseLocked(index, 0f) +
            pageDrawHeightLocked(page) * sourceBottom / plan.sourceHeight.toFloat()
    }

    private fun pageHasCompleteDrawableContentLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        val plan = page.adjacentExactPlan
        if (page.adjacentExactOwner != null && plan != null) {
            return contiguousAdjacentExactSourceBottom(plan, page.adjacentExactSlots) ==
                plan.sourceHeight
        }
        return pageHasDrawableContentLocked(index)
    }

    private fun pageHasDrawablePrefixContentLocked(index: Int): Boolean {
        return pageHasDrawableContentLocked(index)
    }

    fun requestRender() {
        synchronized(stateLock) {
            if (pages.isEmpty()) return
            if (shouldBlockInitialEmptyFrameLocked()) {
                renderRequested = false
                stateLock.notifyAll()
                return
            }
            // Focus/resume/controller bookkeeping does not change reader pixels.  The staged
            // activation path already carries a real dirty version; only bootstrap a draw when
            // this surface has never produced content, otherwise merely service existing work.
            if (!hasDrawnContentFrame) renderRequested = true
            if (!renderRequested && desiredVersion <= committedVersion && scroller.isFinished) {
                return
            }
            scheduleFrameLocked()
            stateLock.notifyAll()
        }
    }

    private fun shouldLogProgrammaticScrollDiagnosticLocked(): Boolean {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs > programmaticScrollStatsUntilMs) return false
        if (nowMs - lastTestScrollDiagnosticLogMs < TEST_SCROLL_DIAGNOSTIC_LOG_INTERVAL_MS) return false
        lastTestScrollDiagnosticLogMs = nowMs
        return true
    }

    private fun activateScrollStatsLocked(eventTimeMs: Long) {
        edgeNoMovementStatsSuppressedUntilMs = 0L
        programmaticScrollStatsUntilMs = max(
            programmaticScrollStatsUntilMs,
            eventTimeMs + PROGRAMMATIC_SCROLL_STATS_ACTIVE_MS
        )
    }

    /**
     * A physical ACTION_DOWN starts a new moving cadence.  Preserve samples collected by earlier
     * gestures in the same immutable sweep, but do not turn the stationary UP-to-DOWN interval
     * into a fabricated missed frame.  Every callback interval after the first rendered frame of
     * this gesture is still measured exactly.
     */
    private fun startPhysicalGestureFrameCadenceLocked() {
        // A previous finger-down segment may have ended in a stationary hold. Never bridge that
        // idle wall-clock interval into the next physical motion interval.
        endPhysicalScrollTraceLocked()
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
    }

    private fun isEmpty(): Boolean = synchronized(stateLock) { pages.isEmpty() }

    private fun clearInputStateLocked() {
        velocityTracker?.recycle()
        velocityTracker = null
        pointerDown = false
        dragging = false
        scroller.forceFinished(true)
        endPhysicalScrollTraceLocked()
        setNativeTexturePrewarmPausedLocked(false)
    }

    private fun interruptPhysicalScrollForLifecycleLocked(): Boolean {
        val hadPhysicalMotion = pointerDown || dragging || !scroller.isFinished ||
            physicalScrollTraceCookie != 0
        clearInputStateLocked()
        activeScrollerOffsetShift = 0f
        activeInputDirection = 0
        pendingReverseWindowFirstPageHint = -1
        pendingWindowRequest = pendingWindowRequest?.copy(
            directionHint = 0,
            reverseFirstPageHint = -1,
        )
        boundaryArmedDirection = 0
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        return hadPhysicalMotion
    }

    private fun setBusyLocked(busy: Boolean): WindowRequest? {
        if (lastBusy == busy) return null
        lastBusy = busy
        if (busy) {
            val nowMs = SystemClock.uptimeMillis()
            lastScrollInteractionMs = nowMs
            activateScrollStatsLocked(nowMs)
        }
        // Busy/progress are controller state, not reader pixels. Only a full-quality page resolve
        // released by the transition is visual and therefore allowed to request a GPU frame.
        if (!busy && applyPendingPageResolvesLocked()) {
            markPixelsDirtyLocked(DIRTY_CONTENT)
        }
        return windowRequestLocked(busy)
    }

    private fun markPixelsDirtyLocked(reason: Int, recordCausalMutation: Boolean = true) {
        if (recordCausalMutation) recordPixelMutationLocked(reason, System.nanoTime())
        if (desiredVersion <= drawnVersion) advanceDesiredVersionLocked()
        pendingPixelReasons = pendingPixelReasons or reason
        renderRequested = true
        scheduleFrameLocked()
    }

    private fun recordPixelMutationLocked(reason: Int, mutationNs: Long) {
        check(pixelMutationWatermark != Long.MAX_VALUE) {
            "pixel mutation watermark overflow"
        }
        pixelMutationWatermark++
        pendingPixelMutationWatermark = pixelMutationWatermark
        pendingPixelMutationOldestNs = mergeOldestPixelMutationNs(
            pendingPixelMutationOldestNs,
            mutationNs
        )
        pendingPixelMutationNewestNs = mergeNewestPixelMutationNs(
            pendingPixelMutationNewestNs,
            mutationNs
        )
        pendingPixelMutationReasons = pendingPixelMutationReasons or reason
    }

    private fun consumePendingPixelMutationTimingLocked(
        invalidationPostedNs: Long
    ): PixelMutationTiming? {
        if (pendingPixelMutationWatermark <= 0L || pendingPixelMutationOldestNs <= 0L) return null
        val timing = PixelMutationTiming(
            watermark = pendingPixelMutationWatermark,
            oldestNs = pendingPixelMutationOldestNs,
            newestNs = pendingPixelMutationNewestNs,
            reasons = pendingPixelMutationReasons,
            invalidationPostedNs = invalidationPostedNs
        )
        pendingPixelMutationWatermark = 0L
        pendingPixelMutationOldestNs = 0L
        pendingPixelMutationNewestNs = 0L
        pendingPixelMutationReasons = 0
        return timing
    }

    private fun advanceDesiredVersionLocked() {
        if (desiredVersion == Long.MAX_VALUE) {
            check(framePipe == FramePipe.IDLE && inFlightToken == 0L && pendingFrameCommits.isEmpty()) {
                "reader frame version overflow while a frame is in flight"
            }
            desiredVersion = 0L
            drawnVersion = 0L
            committedVersion = 0L
        }
        desiredVersion++
    }

    private fun scheduleFrameLocked(): Boolean {
        if (!renderRunning) return false
        if (frameSchedulingSuppressed) return false
        if (pages.isEmpty()) return false
        if (shouldBlockInitialEmptyFrameLocked()) {
            return false
        }
        if (surfaceAttachmentDeferredUntilActualPixels) {
            if (hasRevealableActualPixelsLocked()) postSurfaceRevealLocked()
            return false
        }
        if (!isShown || windowVisibility != View.VISIBLE) {
            // Staging is allowed to build immutable draw state, but it must not enqueue a hidden
            // Choreographer callback. Activation will request a fresh callback after the same-root
            // host becomes VISIBLE.
            return false
        }
        // Record a real pending mutation even while older submissions await commit. This makes a
        // post-draw content install observable without serializing the next display interval on
        // RenderThread/GPU latency.
        if (renderRequested && desiredVersion <= drawnVersion) {
            advanceDesiredVersionLocked()
            pendingPixelReasons = pendingPixelReasons or DIRTY_INVALIDATION
        }
        if (framePipe != FramePipe.IDLE) {
            statsCoalescedRequests++
            return false
        }
        if (!canAdmitPendingFrameCommit(pendingFrameCommits.size)) {
            // Bound retained proofs/callbacks. The oldest commit callback re-enters this scheduler
            // as soon as one GPU slot retires, while all newer input/content remains coalesced in
            // desiredVersion/renderRequested.
            statsCoalescedRequests++
            return false
        }
        if (!renderRequested && scroller.isFinished) return false

        val epoch = lifecycleEpoch
        val token = nextFrameToken++.also {
            if (nextFrameToken <= 0L) nextFrameToken = 1L
        }
        val callback = Runnable { onFrameCommitted(epoch, token) }
        inFlightEpoch = epoch
        inFlightToken = token
        inFlightCommitCallback = callback
        inFlightCommitCallbackRegistered = false
        inFlightInvalidationPostedNs = 0L
        framePipe = FramePipe.INVALIDATION_POSTED
        if (directSurfaceReady && rollingNativeHandle != 0L &&
            rollingNativeAttachEpoch > 0L && !rollingNativeFatal
        ) {
            // The producer-thread Choreographer owns this token. Registering an HWUI invalidation
            // as well races the same token through two render paths and reintroduces ViewRoot/
            // RenderThread backpressure at every page boundary.
            postDirectFrameCallbackLocked()
            return true
        }
        val postHwuiFrame = Runnable {
            var shouldInvalidate = false
            synchronized(stateLock) {
                if (renderRunning && lifecycleEpoch == epoch && inFlightEpoch == epoch &&
                    inFlightToken == token && framePipe == FramePipe.INVALIDATION_POSTED
                ) {
                    val observer = viewTreeObserver
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        isHardwareAccelerated && observer.isAlive
                    ) {
                        observer.registerFrameCommitCallback(callback)
                        inFlightCommitCallbackRegistered = true
                    }
                    inFlightInvalidationPostedNs = System.nanoTime()
                    shouldInvalidate = true
                }
            }
            if (shouldInvalidate) postInvalidateOnAnimation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            postHwuiFrame.run()
        } else {
            mainHandler.post(postHwuiFrame)
        }
        return true
    }

    /** Must be called with [stateLock] held. */
    private fun hasContinuousActualViewportPixelsLocked(): Boolean {
        if (width <= 0 || height <= 0 || pages.isEmpty()) return false
        val geometry = stripGeometry
        if (stripAuthorityToken > 0L && geometry != null) {
            val viewportTop = max(0L, floor(scrollOffset.toDouble()).toLong())
            val viewportBottom = minOf(
                geometry.contentHeightPx,
                viewportTop + height.toLong(),
            )
            return viewportBottom > viewportTop &&
                stripResidentCoverage.continuousEndFrom(viewportTop) >= viewportBottom
        }
        if (currentViewportDrawableOpaqueLocked()) return true
        return qualifyDirectWifiForwardOnlyInitialResumeRevealLocked()
    }

    /** Must be called with [stateLock] held. */
    private fun hasVisibleActualPixelsLocked(): Boolean {
        if (width <= 0 || height <= 0 || pages.isEmpty()) return false
        rebuildLayoutLocked()
        val viewportTop = scrollOffset
        val viewportBottom = viewportTop + height
        for (index in pages.indices) {
            val page = pages[index]
            val top = pageTopOrElseLocked(index, 0f)
            val bottom = top + pageDrawHeightLocked(page)
            if (bottom <= viewportTop) continue
            if (top >= viewportBottom) break
            if (min(viewportBottom, bottom) <= max(viewportTop, top)) continue
            if (page.committedIdentity != null && pageHasCompleteActualPixelsLocked(page)) {
                return true
            }
        }
        return false
    }

    /** Must be called with [stateLock] held. */
    private fun hasRevealableActualPixelsLocked(): Boolean {
        val visibleActualPixels = hasVisibleActualPixelsLocked()
        val scopedResume = emulatorNativeSurfaceRuntime &&
            directWifiForwardOnlyInitialResumeEnabled
        val exactTerminalTailActual = scopedResume &&
            directWifiForwardOnlyTerminalTailActualLocked()
        if (exactTerminalTailActual) {
            directWifiForwardOnlyTerminalTailRevealQualified = true
        }
        return NtkDeferredSurfaceRevealPolicy.shouldReveal(
            emulatorRuntime = emulatorNativeSurfaceRuntime,
            directWifiForwardOnlyResume = directWifiForwardOnlyInitialResumeEnabled,
            visibleActualPixels = visibleActualPixels,
            continuousActualViewport = scopedResume &&
                hasContinuousActualViewportPixelsLocked(),
            exactTerminalTailActual = exactTerminalTailActual,
        )
    }

    /**
     * Proof deliveries temporarily retain viewport-space layout bounds while their pending source
     * bounds settle. Validate completeness from the immutable tile source geometry itself.
     */
    private fun hasCompleteFullQualityTilePixelsLocked(page: Page): Boolean {
        val first = page.tiles.firstOrNull() ?: return false
        val sourceWidth = first.sourceWidth
        val sourceHeight = first.sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0 || first.sourceTop != 0) return false
        var coveredBottom = 0
        for (tile in page.tiles) {
            val bitmap = tile.bitmap
            val span = tile.sourceBottom - tile.sourceTop
            if (tile.sourceWidth != sourceWidth || tile.sourceHeight != sourceHeight ||
                tile.sourceTop < 0 || tile.sourceTop > coveredBottom || span <= 0 ||
                tile.sourceBottom > sourceHeight || bitmap.isRecycled ||
                !tile.hasExactSourcePixelStorage()
            ) {
                return false
            }
            coveredBottom = max(coveredBottom, tile.sourceBottom)
        }
        return coveredBottom == sourceHeight
    }

    /** Re-checks the attachment gate after every complete image-resource installation. */
    private fun reevaluateDeferredSurfaceRevealLocked(reason: String, changedIndex: Int) {
        if (!surfaceAttachmentDeferredUntilActualPixels) return
        if (hasRevealableActualPixelsLocked()) {
            Log.d(
                TAG,
                "reader_surface_progressive_reveal_ready reason=$reason,index=$changedIndex," +
                    "scroll=${scrollOffset.toInt()},viewport=${width}x$height"
            )
            postSurfaceRevealLocked()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSurfaceRevealProbeMs < SURFACE_REVEAL_PROBE_LOG_INTERVAL_MS) return
        lastSurfaceRevealProbeMs = now
        rebuildLayoutLocked()
        val viewportTop = scrollOffset
        val viewportBottom = viewportTop + height
        val visible = pages.indices.asSequence()
            .map { index ->
                val page = pages[index]
                val top = pageTopOrElseLocked(index, 0f)
                Triple(index, top, top + pageDrawHeightLocked(page))
            }
            .filter { (_, top, bottom) -> bottom > viewportTop && top < viewportBottom }
            .joinToString(";") { (index, top, bottom) ->
                val page = pages[index]
                val kind = when {
                    page.cardText != null -> "card"
                    page.errorText != null -> "error"
                    page.bitmap?.isRecycled == false -> "bitmap"
                    hasCompleteFullQualityTilePixelsLocked(page) ->
                        "fullTiles:${page.tiles.size}"
                    page.tiles.isNotEmpty() -> "partialTiles:${page.tiles.size}"
                    else -> "missing"
                }
                "$index:$kind:${top.toInt()}-${bottom.toInt()}:${page.width}x${page.height}"
            }
        Log.d(
            TAG,
            "reader_surface_reveal_wait reason=$reason,index=$changedIndex," +
                "scroll=${viewportTop.toInt()},viewport=${width}x$height," +
                "stripAuthority=$stripAuthorityToken,visible=$visible"
        )
    }

    /** Must be called with [stateLock] held. */
    private fun postSurfaceRevealLocked() {
        if (!surfaceAttachmentDeferredUntilActualPixels ||
            !deferredSurfaceIdentityActivated ||
            surfaceRevealPosted
        ) return
        surfaceRevealPosted = true
        mainHandler.post {
            val reveal = synchronized(stateLock) {
                surfaceRevealPosted = false
                if (!surfaceAttachmentDeferredUntilActualPixels ||
                    !hasRevealableActualPixelsLocked()
                ) {
                    false
                } else {
                    surfaceAttachmentDeferredUntilActualPixels = false
                    // Reveal the parent HWUI scene immediately. The native child remains gone
                    // until a complete exact HWUI viewport commits and its renderer is prepared.
                    nativeSurfaceRevealAfterFirstHwuiCommitPending =
                        rollingNativePresentationEnabled &&
                            nativeSurfaceView.visibility != View.VISIBLE
                    renderRequested = true
                    true
                }
            }
            if (!reveal) return@post
            // Publish the first available exact pixels through HWUI without waiting for the rest
            // of the viewport or any native producer setup.
            invalidate()
            (parent as? View)?.invalidate()
            postInvalidateOnAnimation()
            synchronized(stateLock) {
                if (renderRunning && pages.isNotEmpty()) {
                    renderRequested = true
                    scheduleFrameLocked()
                    stateLock.notifyAll()
                }
            }
            Log.d(
                TAG,
                "reader_surface_actual_viewport_admitted producerPrepared=" +
                    "${nativeSurfaceView.visibility == View.VISIBLE}," +
                    "attached=$isAttachedToWindow,size=${width}x$height"
            )
        }
    }

    /**
     * Stage two of the cold producer transition. The listener receives the clean HWUI proof first,
     * allowing current-episode bodies to proceed before any host EGL/BufferQueue work begins.
     */
    private fun revealNativeSurfaceAfterFirstHwuiCommit(expectedLifecycleEpoch: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!rollingNativePresentationEnabled) return
        val reveal = synchronized(stateLock) {
            val eligible = renderRunning &&
                lifecycleEpoch == expectedLifecycleEpoch &&
                !surfaceAttachmentDeferredUntilActualPixels &&
                isAttachedToWindow &&
                nativeSurfaceView.visibility != View.VISIBLE
            if (!eligible) {
                nativeSurfaceRevealAfterPrepareEpoch = 0L
                false
            } else if (rollingNativeHandle == 0L) {
                nativeSurfaceRevealAfterPrepareEpoch = expectedLifecycleEpoch
                prepareRollingNativeRendererLocked()
                false
            } else {
                nativeSurfaceRevealAfterPrepareEpoch = 0L
                true
            }
        }
        if (!reveal) return
        nativeSurfaceView.alpha = 1f
        nativeSurfaceView.visibility = View.VISIBLE
        nativeSurfaceView.requestLayout()
        invalidate()
        (parent as? View)?.invalidate()
        postInvalidateOnAnimation()
        Log.d(
            TAG,
            "reader_native_surface_revealed_after_hwui_commit " +
                "epoch=$expectedLifecycleEpoch,size=${width}x$height"
        )
    }

    /** Commits the already-clamped physical position even when no newer model mutation exists. */
    fun requestCurrentPositionCommit() {
        synchronized(stateLock) {
            if (pages.isEmpty() || shouldBlockInitialEmptyFrameLocked()) return
            // Reaching an edge is a real viewport change whose final native submission can race the
            // boundary callback. requestRender() intentionally ignores a clean model; this explicit
            // path creates one new frame version so the displayed tail can be identity/coverage
            // qualified without inventing movement or changing the scroll coordinate.
            markPixelsDirtyLocked(DIRTY_INVALIDATION, recordCausalMutation = false)
            stateLock.notifyAll()
        }
    }

    private fun onFrameCommitted(
        epoch: Long,
        token: Long,
        surfaceControlLatchObserved: Boolean = false,
        surfaceControlPresentedUptimeNanos: Long = 0L,
        nativePresentationKind: Int = NATIVE_PRESENTATION_NONE,
        allowNativeProducerThread: Boolean = false
    ) {
        if (!allowNativeProducerThread && Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                onFrameCommitted(
                    epoch,
                    token,
                    surfaceControlLatchObserved,
                    surfaceControlPresentedUptimeNanos,
                    nativePresentationKind,
                    allowNativeProducerThread = false
                )
            }
            return
        }
        var completed: CompletedDrawProof? = null
        var revealNativeSurfaceAfterCompletedHwui = false
        synchronized(stateLock) {
            if (epoch != lifecycleEpoch) return
            val submission = pendingFrameCommits.remove(token)
            if (submission == null) {
                // Defensive fail-closed recovery for a callback that somehow arrives before the
                // matching onDraw installs its immutable proof. It must never qualify an unrelated
                // root commit or monopolize the sole posted-invalidation admission.
                if (token == inFlightToken && epoch == inFlightEpoch) {
                    releasePostedAdmissionLocked(preserveDirty = true)
                    scheduleNoStateRetryLocked()
                }
                stateLock.notifyAll()
                return
            }
            val pending = submission.proof
            val matchingReaderDraw = submission.frameEpoch == epoch &&
                pending.frameToken == token && pending.drawnVersion > 0L
            val surfaceQueueObserved = nativePresentationKind == NATIVE_PRESENTATION_BUFFER_QUEUE
            val exactNativePresentationObserved = surfaceControlLatchObserved ||
                surfaceQueueObserved

            if (matchingReaderDraw) {
                Trace.beginSection(
                    when {
                        surfaceControlLatchObserved -> "ViewerSurfaceControlLatch"
                        surfaceQueueObserved -> "ViewerSurfaceQueueSubmission"
                        submission.surfaceQueueSubmission -> "ViewerSurfaceQueueSubmission"
                        else -> "ViewerHwuiFrameCommit"
                    }
                )
                try {
                    committedVersion = maxOf(committedVersion, pending.drawnVersion)
                    // The dedicated Surface path proves the exact buffer only after
                    // unlockCanvasAndPost succeeds. The legacy path remains restricted to its
                    // registered HWUI frame-commit callback.
                    if (Build.VERSION.SDK_INT >= 35 &&
                        ((pending.hardwareAccelerated && submission.callbackRegistered) ||
                            submission.surfaceQueueSubmission ||
                            (submission.surfaceControlSubmission && exactNativePresentationObserved))
                    ) {
                        recordCommittedTraversalProofLocked(pending.traversal)
                    }
                    if (submission.surfaceControlSubmission && exactNativePresentationObserved &&
                        pending.coverage.drawableItems > 0
                    ) {
                        hasDrawnContentFrame = true
                    }
                    completedDrawSequence++
                    completed = CompletedDrawProof(
                        sequence = completedDrawSequence,
                        completedUptimeNanos = SystemClock.elapsedRealtimeNanos(),
                        hardwareAccelerated = pending.hardwareAccelerated,
                        coverage = pending.coverage,
                        frameToken = token,
                        desiredVersion = desiredVersion,
                        drawnVersion = pending.drawnVersion,
                        committedVersion = committedVersion,
                        structureEpoch = pending.structureEpoch,
                        visiblePageIndexes = pending.visiblePageIndexes.copyOf(),
                        visiblePageIdentities = pending.visiblePageIdentities,
                        runwayDefect = pending.traversal?.runwayDefect == true,
                        registeredHwuiFrameCommitCallbackObserved =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                pending.hardwareAccelerated && submission.callbackRegistered,
                        surfaceQueueSubmissionObserved =
                            submission.surfaceQueueSubmission || surfaceQueueObserved,
                        surfaceControlLatchObserved =
                            submission.surfaceControlSubmission && surfaceControlLatchObserved,
                        surfaceLifecycleEpoch = epoch,
                        presentedUptimeNanos = surfaceControlPresentedUptimeNanos.takeIf {
                            submission.surfaceControlSubmission &&
                                exactNativePresentationObserved && it > 0L
                        } ?: 0L,
                        scrollOffsetPx = pending.scrollOffsetPx
                    )
                    val cleanCommittedHwuiActualPixels =
                        nativeSurfaceRevealAfterFirstHwuiCommitPending &&
                            !submission.surfaceQueueSubmission &&
                            !submission.surfaceControlSubmission &&
                            pending.hardwareAccelerated &&
                            submission.callbackRegistered &&
                            pending.coverage.drawableItems > 0 &&
                            pending.coverage.missingPx == 0 &&
                            pending.coverage.placeholderPx == 0 &&
                            pending.coverage.visibleLoading == 0 &&
                            pending.coverage.visibleErrors == 0 &&
                            (pending.coverage.visibleCards == 0 ||
                                pending.coverage.directWifiForwardOnlyInitialResume) &&
                            pending.coverage.lowResolutionItems == 0
                    if (cleanCommittedHwuiActualPixels) {
                        nativeSurfaceRevealAfterFirstHwuiCommitPending = false
                        revealNativeSurfaceAfterCompletedHwui = true
                    }
                } finally {
                    Trace.endSection()
                }
            } else {
                drawnVersion = latestSubmittedVersionLocked()
                if (pages.isNotEmpty()) renderRequested = true
            }
            if (desiredVersion <= committedVersion) pendingPixelReasons = 0

            // A physical trace represents pixel-producing motion, not the entire time a finger
            // remains down.  Host input can deliver sparse MOVE events, so keeping the interval
            // open after the exact physical version has latched fabricates a long compositor gap
            // even though only later content work remains. Do not close while the last actual
            // offset mutation is outstanding: a real render stall remains measured.
            if (shouldClosePhysicalMotionInterval(
                    traceActive = physicalScrollTraceCookie != 0,
                    scrollerFinished = scroller.isFinished,
                    requiredVersion = physicalMotionRequiredVersion,
                    committedVersion = committedVersion
                )
            ) {
                endPhysicalScrollTraceLocked()
                statsLastCallbackStartNs = 0L
                statsLastPostEndNs = 0L
            }

            val needsImmediateFrame = renderRequested || desiredVersion > drawnVersion ||
                !scroller.isFinished
            if (needsImmediateFrame) {
                if (desiredVersion > drawnVersion) {
                    renderRequested = true
                }
                if (!scroller.isFinished) pendingPixelReasons = pendingPixelReasons or DIRTY_ANIMATION
                scheduleFrameLocked()
            }
            stateLock.notifyAll()
        }
        completed?.let { proof ->
            publishBenchmarkAdjacentP0CandidateIfEligible(proof)
            val offer = completedDrawDispatchQueue.offer(
                proof,
                revealNativeSurfaceAfterCompletedHwui,
                SystemClock.elapsedRealtimeNanos(),
            )
            if (offer.criticalOverflowed) {
                // A fixed-capacity queue must never silently discard a first-seen episode/p0-p4
                // transition. Rejecting the proof is fail-closed and this hard metric makes the
                // capacity defect visible in production and regression traces.
                Log.e(
                    "ViewerPerf",
                    "reader_completed_draw_dispatch_critical_overflow " +
                        "epoch=${proof.surfaceLifecycleEpoch},structure=${proof.structureEpoch}," +
                        "visible=${proof.visiblePageIndexes.joinToString("|")}",
                )
            }
            if (offer.shouldPost) {
                val accepted = if (offer.delayNanos <= 0L) {
                    mainHandler.post(completedDrawDispatchRunnable)
                } else {
                    val delayMillis = ceil(offer.delayNanos / 1_000_000.0)
                        .toLong()
                        .coerceAtLeast(1L)
                    mainHandler.postDelayed(completedDrawDispatchRunnable, delayMillis)
                }
                if (!accepted) completedDrawDispatchQueue.onPostRejected()
            }
        }
    }

    /** Drains semantic transitions promptly while ordinary same-viewport frames remain bounded. */
    private fun drainCompletedDrawDispatch() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!completedDrawDispatchQueue.beginRun()) return
        var deliveries = 0
        var repost: CompletedDrawDispatchQueue.Repost
        try {
            while (deliveries < MAX_COMPLETED_DRAW_DELIVERIES_PER_RUN) {
                val delivery = completedDrawDispatchQueue.pollReady(
                    SystemClock.elapsedRealtimeNanos(),
                ) ?: break
                val proof = delivery.proof
                // A pause, Surface replacement, or page-table mutation may retire a proof while
                // it waits in the bounded mailbox. Both identities must still match immediately
                // before the Activity can publish `actual:` semantics or reveal the native child.
                val lifecycleAndStructureCurrent = synchronized(stateLock) {
                    isCompletedDrawProofLifecycleCurrent(
                        proof.surfaceLifecycleEpoch,
                        lifecycleEpoch,
                    ) && proof.structureEpoch == traversalStructureEpoch
                }
                if (!lifecycleAndStructureCurrent) {
                    Log.w(
                        "ViewerPerf",
                        "reader_ntk_strict_proof_lifecycle_skip " +
                            "proofEpoch=${proof.surfaceLifecycleEpoch},currentEpoch=$lifecycleEpoch," +
                            "proofStructure=${proof.structureEpoch}," +
                            "currentStructure=$traversalStructureEpoch",
                    )
                }
                try {
                    if (lifecycleAndStructureCurrent) {
                        listener?.onCompletedDraw(proof)
                        if (delivery.revealNativeSurface) {
                            revealNativeSurfaceAfterFirstHwuiCommit(
                                proof.surfaceLifecycleEpoch,
                            )
                        }
                    }
                } finally {
                    completedDrawDispatchQueue.recordDeliveryResult(
                        lifecycleAndStructureCurrent,
                    )
                }
                deliveries++
            }
        } finally {
            repost = completedDrawDispatchQueue.finishRun(
                SystemClock.elapsedRealtimeNanos(),
            )
        }
        if (!repost.shouldPost) return
        val accepted = if (repost.delayNanos <= 0L) {
            mainHandler.post(completedDrawDispatchRunnable)
        } else {
            val delayMillis = ceil(repost.delayNanos / 1_000_000.0)
                .toLong()
                .coerceAtLeast(1L)
            mainHandler.postDelayed(completedDrawDispatchRunnable, delayMillis)
        }
        if (!accepted) completedDrawDispatchQueue.onPostRejected()
    }

    /**
     * Stops benchmark input from the native-present producer instead of waiting behind the main
     * listener queue. The benchmark receiver still requires the later strict Activity/UI proof
     * and its identical presented timestamp, so this fast candidate cannot weaken qualification.
     * The non-benchmark implementation returns false and R8 removes this path from release.
     */
    private fun publishBenchmarkAdjacentP0CandidateIfEligible(proof: CompletedDrawProof) {
        if (!BenchmarkAdjacentCommitSignal.isEnabled()) return
        val presentedAtNanos = proof.presentedUptimeNanos
        if (presentedAtNanos <= 0L || proof.frameToken <= 0L || proof.drawnVersion <= 0L ||
            proof.committedVersion < proof.drawnVersion || proof.structureEpoch <= 0L
        ) return
        val exactPresentationProofs = listOf(
            proof.hardwareAccelerated && proof.registeredHwuiFrameCommitCallbackObserved,
            proof.surfaceQueueSubmissionObserved,
            proof.surfaceControlLatchObserved,
        ).count { it }
        if (exactPresentationProofs != 1) return
        val coverage = proof.coverage
        if (coverage.physicalViewportPx <= 0 ||
            coverage.viewportPx < coverage.physicalViewportPx ||
            coverage.drawablePx < coverage.physicalViewportPx ||
            coverage.drawableItems <= 0 || coverage.missingPx != 0 ||
            coverage.placeholderPx != 0 || coverage.visibleLoading != 0 ||
            coverage.visibleErrors != 0 || coverage.widthFillFailures != 0 ||
            coverage.lowResolutionItems != 0
        ) return
        val lifecycleAndStructureCurrent = synchronized(stateLock) {
            proof.surfaceLifecycleEpoch == lifecycleEpoch &&
                proof.structureEpoch == traversalStructureEpoch
        }
        if (!lifecycleAndStructureCurrent) return
        val visible = proof.visiblePageIndexes
            .filter { it >= 0 }
            .distinct()
            .sorted()
        val identities = proof.visiblePageIdentities
        if (visible.isEmpty() || identities.size != visible.size ||
            identities.map { it.displayPageIndex } != visible
        ) return
        val generation = ViewerTelemetry.activeGeneration()
        if (generation <= 0L) return
        identities.asSequence()
            .filter { it.sourcePageIndex == 0 }
            .forEach { identity ->
                BenchmarkAdjacentCommitSignal.publish(
                    identity.normalizedEpisodePath,
                    identity.sourcePageIndex,
                    presentedAtNanos,
                    generation,
                )
            }
    }

    private fun releasePostedAdmissionLocked(preserveDirty: Boolean) {
        framePipe = FramePipe.IDLE
        inFlightToken = 0L
        inFlightEpoch = 0L
        inFlightCommitCallback = null
        inFlightCommitCallbackRegistered = false
        inFlightInvalidationPostedNs = 0L
        if (preserveDirty && pages.isNotEmpty()) {
            renderRequested = true
        }
    }

    private fun latestSubmittedVersionLocked(): Long {
        var latest = committedVersion
        for (submission in pendingFrameCommits.values) {
            latest = maxOf(latest, submission.proof.drawnVersion)
        }
        return latest
    }

    private fun hasFrameWorkLocked(): Boolean {
        return framePipe != FramePipe.IDLE || inFlightToken != 0L || pendingFrameCommits.isNotEmpty()
    }

    private fun scheduleNoStateRetryLocked() {
        if (noStateRetryPosted || !renderRunning || pages.isEmpty()) return
        noStateRetryPosted = true
        mainHandler.postDelayed({
            synchronized(stateLock) {
                noStateRetryPosted = false
                if (!renderRunning || frameSchedulingSuppressed || pages.isEmpty()) return@synchronized
                if (!renderRequested && desiredVersion <= committedVersion && scroller.isFinished) {
                    return@synchronized
                }
                scheduleFrameLocked()
            }
        }, NO_STATE_FRAME_RETRY_MS)
    }

    private fun clearFramePipeLocked(preserveDirty: Boolean) {
        // Remove only this dispatcher's wakeup before releasing its scheduled owner. A callback
        // already running keeps runnerRunning=true and will either consume a new-lifecycle offer
        // or atomically release ownership, so lifecycle teardown cannot create a lost wakeup.
        mainHandler.removeCallbacks(completedDrawDispatchRunnable)
        completedDrawDispatchQueue.clearAfterScheduledCallbackRemoval()
        lifecycleEpoch++
        if (lifecycleEpoch <= 0L) lifecycleEpoch = 1L
        val callback = inFlightCommitCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callback != null && inFlightCommitCallbackRegistered
        ) {
            val observer = viewTreeObserver
            if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingFrameCommits.isNotEmpty()) {
            val observer = viewTreeObserver
            if (observer.isAlive) {
                for (submission in pendingFrameCommits.values) {
                    if (submission.callbackRegistered) {
                        observer.unregisterFrameCommitCallback(submission.callback)
                    }
                }
            }
        }
        pendingFrameCommits.clear()
        earlyNativePresentations.clear()
        releasePostedAdmissionLocked(preserveDirty = false)
        drawnVersion = committedVersion
        if (preserveDirty && pages.isNotEmpty()) {
            renderRequested = true
            pendingPixelReasons = pendingPixelReasons or DIRTY_INVALIDATION
        } else if (!preserveDirty) {
            renderRequested = false
            pendingPixelReasons = 0
            pendingPixelMutationWatermark = 0L
            pendingPixelMutationOldestNs = 0L
            pendingPixelMutationNewestNs = 0L
            pendingPixelMutationReasons = 0
        }
    }

    private fun shouldFinishScrollerAtInputEdgeLocked(direction: Int, rawNext: Float): Boolean {
        if (direction == 0 || pages.isEmpty() || height <= 0) return false
        return when (direction) {
            DIRECTION_NEXT -> {
                val maxScroll = effectiveMaxScrollLocked(maxScrollLocked())
                scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX &&
                    rawNext >= maxScroll - BOUNDARY_EPSILON_PX
            }
            DIRECTION_PREVIOUS -> {
                scrollOffset <= BOUNDARY_EPSILON_PX &&
                    rawNext <= BOUNDARY_EPSILON_PX
            }
            else -> {
                false
            }
        }
    }

    /**
     * The reader owns a Surface producer that is independent from ViewRoot's window buffer.
     * Creating this lane only after the View is attached keeps cold-entry work demand-bound while
     * preventing RenderThread/BLAST backpressure from blocking input delivery on the main thread.
     */
    private fun startRenderThreadLocked() {
        if (directRenderThread != null) return
        val thread = HandlerThread(
            "ReaderSurfaceProducer",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        )
        thread.start()
        val handler = Handler(thread.looper)
        directRenderThread = thread
        directRenderHandler = handler
        handler.post {
            synchronized(stateLock) {
                if (directRenderThread !== thread) return@synchronized
                directChoreographer = Choreographer.getInstance()
                if (framePipe == FramePipe.INVALIDATION_POSTED && inFlightToken != 0L) {
                    postDirectFrameCallbackLocked()
                }
                stateLock.notifyAll()
            }
        }
    }

    private fun computeNativeRenderTargetSizeLocked(
        viewportWidth: Int,
        viewportHeight: Int
    ): Pair<Int, Int> {
        var widestKnownSourceWidth = 0
        for (page in pages) {
            widestKnownSourceWidth = max(widestKnownSourceWidth, page.width)
            page.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    widestKnownSourceWidth = max(widestKnownSourceWidth, bitmap.width)
                }
            }
            for (tile in page.tiles) {
                widestKnownSourceWidth = max(widestKnownSourceWidth, tile.sourceWidth)
            }
        }
        return sourceNativeRenderTargetSize(
            sourceNativeWebtoonCompositingEnabled,
            viewportWidth,
            viewportHeight,
            widestKnownSourceWidth
        )
    }

    /** Captures target dimensions only; it cannot allocate, attach, upload or publish pixels. */
    private fun captureNativeRenderTargetGeometryLocked(
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (!rollingNativePresentationEnabled || rollingNativeAttachEpoch != 0L ||
            viewportWidth <= 0 || viewportHeight <= 0 ||
            (rollingNativeFrozenViewportWidth == viewportWidth &&
                rollingNativeFrozenViewportHeight == viewportHeight &&
                rollingNativeFrozenTargetWidth > 0 && rollingNativeFrozenTargetHeight > 0)
        ) return
        val target = computeNativeRenderTargetSizeLocked(viewportWidth, viewportHeight)
        rollingNativeFrozenViewportWidth = viewportWidth
        rollingNativeFrozenViewportHeight = viewportHeight
        rollingNativeFrozenTargetWidth = target.first
        rollingNativeFrozenTargetHeight = target.second
        Log.d(
            TAG,
            "reader_native_target_geometry_frozen render=${target.first}x${target.second} " +
                "viewport=${viewportWidth}x$viewportHeight"
        )
    }

    private fun nativeRenderTargetSizeLocked(
        viewportWidth: Int,
        viewportHeight: Int,
    ): Pair<Int, Int> {
        if (rollingNativeFrozenViewportWidth == viewportWidth &&
            rollingNativeFrozenViewportHeight == viewportHeight &&
            rollingNativeFrozenTargetWidth > 0 && rollingNativeFrozenTargetHeight > 0
        ) {
            return Pair(rollingNativeFrozenTargetWidth, rollingNativeFrozenTargetHeight)
        }
        return computeNativeRenderTargetSizeLocked(viewportWidth, viewportHeight)
    }

    /**
     * Queues the production GPU target allocation while the strict SurfaceView is still detached.
     * There is intentionally no Surface or Bitmap argument: this can allocate render targets but
     * cannot attach, submit, or draw before actual image pixels are ready.
     */
    private fun prepareRollingNativeRenderTargetsLocked(
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val target = nativeRenderTargetSizeLocked(viewportWidth, viewportHeight)
        if (!renderRunning || rollingNativeFatal ||
            rollingNativeHandle == 0L || rollingNativeAttachEpoch != 0L ||
            viewportWidth <= 0 || viewportHeight <= 0 ||
            (rollingNativePreparedWidth == target.first &&
                rollingNativePreparedHeight == target.second)
        ) return
        val handle = rollingNativeHandle
        val accepted = try {
            NtkRollingNativeBridge.nativePrepare(handle, target.first, target.second)
        } catch (failure: Throwable) {
            Log.e(
                TAG,
                "reader_native_targets_prepare_failed render=${target.first}x${target.second} " +
                    "viewport=${viewportWidth}x$viewportHeight",
                failure
            )
            false
        }
        if (accepted && rollingNativeHandle == handle && rollingNativeAttachEpoch == 0L) {
            rollingNativePreparedWidth = target.first
            rollingNativePreparedHeight = target.second
            Log.d(
                TAG,
                "reader_native_targets_prepare_queued render=${target.first}x${target.second} " +
                    "viewport=${viewportWidth}x$viewportHeight"
            )
        }
    }

    /**
     * Starts the native renderer/EGL lane only after a complete exact HWUI viewport committed.
     * It cannot delay the first actual pixels and never submits a placeholder/fake frame. Must be
     * called with [stateLock] held.
     */
    private fun prepareRollingNativeRendererLocked() {
        if (!rollingNativePresentationEnabled || !renderRunning || rollingNativeFatal ||
            rollingNativeHandle != 0L ||
            rollingNativeCreatePending
        ) return
        val handler = directRenderHandler ?: return
        rollingNativeCreatePending = true
        val generation = ++rollingNativeCreateGeneration
        Log.d(TAG, "reader_native_cold_prepare_started generation=$generation")
        val posted = handler.post {
            val createdHandle = try {
                NtkRollingNativeBridge.nativeCreate(this)
            } catch (failure: Throwable) {
                Log.e(TAG, "reader_native_cold_prepare_failed generation=$generation", failure)
                0L
            }
            val installed = synchronized(stateLock) {
                val ownsAttempt = rollingNativeCreatePending &&
                    rollingNativeCreateGeneration == generation
                if (ownsAttempt) rollingNativeCreatePending = false
                if (ownsAttempt && renderRunning && isAttachedToWindow &&
                    !rollingNativeFatal && rollingNativeHandle == 0L && createdHandle != 0L
                ) {
                    rollingNativeHandle = createdHandle
                    NtkRollingNativeBridge.nativeSetDirectWifiTextureProfile(
                        createdHandle,
                        directWifiExpandedNativeTextureRunway,
                        directWifiExpandedNativeTextureRunway &&
                            emulatorNativeSurfaceRuntime,
                    )
                    if (nativeTexturePrewarmPaused) {
                        NtkRollingNativeBridge.nativeSetPrewarmPaused(createdHandle, true)
                    }
                    val prepareWidth = width
                    val prepareHeight = height
                    if (prepareWidth > 0 && prepareHeight > 0) {
                        // The first complete exact HWUI viewport has already committed. Allocate
                        // the fixed AHB ring without competing with the first real frame.
                        prepareRollingNativeRenderTargetsLocked(prepareWidth, prepareHeight)
                    }
                    stateLock.notifyAll()
                    true
                } else {
                    if (ownsAttempt && createdHandle == 0L) rollingNativeFatal = true
                    stateLock.notifyAll()
                    false
                }
            }
            if (!installed) {
                if (createdHandle != 0L) NtkRollingNativeBridge.nativeDestroy(createdHandle)
                return@post
            }
            Log.d(TAG, "reader_native_cold_prepare_completed generation=$generation")
            synchronized(stateLock) {
                // A very fast response may have installed pixels during EGL creation. Preserve
                // the same demand-bounded behavior by enqueueing only those resident pages now.
                postResidentNativeTexturePrewarmLocked()
            }
            mainHandler.post {
                val revealEpoch = synchronized(stateLock) {
                    nativeSurfaceRevealAfterPrepareEpoch
                }
                if (revealEpoch > 0L) {
                    revealNativeSurfaceAfterFirstHwuiCommit(revealEpoch)
                } else {
                    attachPreparedRollingNativeSurfaceIfReady(generation)
                }
            }
        }
        if (!posted) {
            rollingNativeCreatePending = false
            rollingNativeCreateGeneration += 1L
            Log.w(TAG, "reader_native_cold_prepare_post_rejected generation=$generation")
        }
    }

    private fun attachPreparedRollingNativeSurfaceIfReady(generation: Long) {
        val ready = synchronized(stateLock) {
            val surface = rollingTextureSurface
            if (rollingNativeCreateGeneration != generation || !renderRunning ||
                !isAttachedToWindow || !directSurfaceReady || surface?.isValid != true ||
                rollingNativeHandle == 0L
            ) return@synchronized null
            Triple(checkNotNull(surface), width, height)
        } ?: return
        attachRollingNativeSurface(ready.first, ready.second, ready.third)
    }

    /** Must be called with [stateLock] held. Keeps the producer thread but retires one Surface. */
    private fun retireDirectSurfaceSchedulingLocked() {
        directRenderHandler?.removeCallbacks(directFramePostRunnable)
        directRenderHandler?.removeCallbacks(directCadenceWatchdog)
        directRenderHandler?.removeCallbacks(directLateInputCatchup)
        directRenderHandler?.removeCallbacks(directAdjacentExactP0Catchup)
        val choreographer = directChoreographer
        if (choreographer != null && directFrameCallbackPosted) {
            choreographer.removeFrameCallback(directFrameCallback)
        }
        directFrameCallbackPosted = false
        directLateInputCatchupPosted = false
        directAdjacentExactP0CatchupPosted = false
        directAdjacentExactP0CatchupEpoch = 0L
        directAdjacentExactP0CatchupToken = 0L
        directAdjacentExactP0CatchupAttachEpoch = 0L
        directAdjacentExactP0CatchupOwner = null
        directCallbackObservedDragTargetRevision = 0L
        directCallbackObservedPhysicalGestureRevision = 0L
        directCallbackObservedAtNanos = 0L
        directCallbackHadAdmission = false
    }

    private fun stopRenderThreadLocked(): HandlerThread? {
        val thread = directRenderThread ?: return null
        retireDirectSurfaceSchedulingLocked()
        directChoreographer = null
        directRenderHandler = null
        directRenderThread = null
        return thread
    }

    private val directFrameCallback: Choreographer.FrameCallback =
        Choreographer.FrameCallback { frameTimeNanos ->
        Trace.beginSection("ViewerDirectChoreographer")
        try {
            directRenderHandler?.removeCallbacks(directCadenceWatchdog)
            renderDirectSurfaceFrame(frameTimeNanos)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * A MOVE can reach the main thread just after the producer callback observed the prior target
     * and re-armed itself for the next vsync. Execute at most one producer-loop catch-up for that
     * exact revision race. If a due Choreographer callback observes the MOVE first, the revision
     * check turns this runnable into a no-op.
     */
    private val directLateInputCatchup: Runnable = Runnable {
        Trace.beginSection("ViewerDirectLateInputCatchup")
        try {
            val shouldRender = synchronized(stateLock) {
                directLateInputCatchupPosted = false
                val targetStillUnobserved =
                    dragTargetRevision != directCallbackObservedDragTargetRevision
                val newGestureTargetStillUnobserved =
                    physicalGestureRevision != directCallbackObservedPhysicalGestureRevision
                val canReplaceCallback = renderRunning && directSurfaceReady &&
                    directFrameCallbackPosted && pointerDown && dragging &&
                    (!directCallbackHadAdmission || newGestureTargetStillUnobserved) &&
                    targetStillUnobserved &&
                    rollingNativeHandle != 0L &&
                    rollingNativeAttachEpoch > 0L &&
                    rollingTextureSurface?.isValid == true
                if (canReplaceCallback) {
                    directChoreographer?.removeFrameCallback(directFrameCallback)
                    directRenderHandler?.removeCallbacks(directCadenceWatchdog)
                    directFrameCallbackPosted = false
                }
                canReplaceCallback
            }
            if (shouldRender) {
                renderDirectSurfaceFrame(System.nanoTime())
            }
        } finally {
            Trace.endSection()
        }
    }

    /** Replaces one pending producer callback for the exact content token captured at install. */
    private val directAdjacentExactP0Catchup: Runnable = Runnable {
        Trace.beginSection("ViewerDirectAdjacentExactP0Catchup")
        try {
            val shouldRender = synchronized(stateLock) {
                val expectedEpoch = directAdjacentExactP0CatchupEpoch
                val expectedToken = directAdjacentExactP0CatchupToken
                val expectedAttachEpoch = directAdjacentExactP0CatchupAttachEpoch
                val expectedOwner = directAdjacentExactP0CatchupOwner
                directAdjacentExactP0CatchupPosted = false
                directAdjacentExactP0CatchupEpoch = 0L
                directAdjacentExactP0CatchupToken = 0L
                directAdjacentExactP0CatchupAttachEpoch = 0L
                directAdjacentExactP0CatchupOwner = null
                val ownerStillAuthorized = expectedOwner != null &&
                    directWifiExpandedNativeTextureRunway &&
                    expectedOwner.normalizedEpisodePath in
                        directWifiExpandedNativeTextureEpisodePaths &&
                    pages.any { it.adjacentExactOwner == expectedOwner }
                val canReplaceCallback = emulatorNativeSurfaceRuntime && renderRunning &&
                    directSurfaceReady && !frameSchedulingSuppressed &&
                    directFrameCallbackPosted && !directLateInputCatchupPosted &&
                    ownerStillAuthorized &&
                    framePipe == FramePipe.INVALIDATION_POSTED &&
                    inFlightEpoch == expectedEpoch && inFlightToken == expectedToken &&
                    expectedToken != 0L && rollingNativeAttachEpoch == expectedAttachEpoch &&
                    expectedAttachEpoch > 0L && rollingNativeHandle != 0L &&
                    !rollingNativeFatal && rollingTextureSurface?.isValid == true
                if (canReplaceCallback) {
                    directChoreographer?.removeFrameCallback(directFrameCallback)
                    // scheduleFrameLocked may still have its registration message queued. Remove
                    // it before renderDirectSurfaceFrame reserves the moving successor; otherwise
                    // the stale message can register that successor a second time.
                    directRenderHandler?.removeCallbacks(directFramePostRunnable)
                    directRenderHandler?.removeCallbacks(directCadenceWatchdog)
                    directFrameCallbackPosted = false
                }
                canReplaceCallback
            }
            if (shouldRender) renderDirectSurfaceFrame(System.nanoTime())
        } finally {
            Trace.endSection()
        }
    }

    /**
     * A host-backed emulator can occasionally stop delivering producer-thread Choreographer
     * callbacks for 60-100 ms even though EGL submission, app CPU and the main thread are idle.
     * Keep Choreographer as the normal authority, but replace an overdue callback on the same
     * producer Looper with one demand-bounded submission. No callback is fabricated while the
     * reader is stationary, and removing the pending callback prevents a duplicate frame when
     * the emulator's vsync lane resumes.
     */
    private val directCadenceWatchdog: Runnable = Runnable {
        Trace.beginSection("ViewerDirectCadenceWatchdog")
        try {
            val shouldRecover = synchronized(stateLock) {
                if (!directFrameCallbackPosted) {
                    false
                } else {
                    val hasAdmittedFrame =
                        framePipe == FramePipe.INVALIDATION_POSTED && inFlightToken != 0L
                    val hasCurrentDemand = hasAdmittedFrame || shouldKeepDirectCadenceArmedLocked()
                    val canRender = renderRunning && directSurfaceReady &&
                        rollingTextureSurface?.isValid == true

                    directChoreographer?.removeFrameCallback(directFrameCallback)
                    directFrameCallbackPosted = false
                    canRender && hasCurrentDemand
                }
            }
            if (shouldRecover) {
                renderDirectSurfaceFrame(System.nanoTime())
            }
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Choreographer may enter DisplayEventReceiver/Binder while registering the next producer
     * vsync. Running that call while holding [stateLock] makes a real MOVE wait behind an emulator
     * display-service stall. Reserve the unique callback under the lock, then perform the platform
     * registration as the next producer-loop message with no reader state lock held.
     */
    private val directFramePostRunnable: Runnable = Runnable {
        val choreographer = synchronized(stateLock) {
            if (!directFrameCallbackPosted || !renderRunning || !directSurfaceReady) {
                null
            } else {
                directChoreographer
            }
        }
        if (choreographer == null) {
            synchronized(stateLock) {
                directFrameCallbackPosted = false
            }
            return@Runnable
        }
        postReservedDirectFrameCallback(choreographer)
    }

    /**
     * Registers an already-reserved callback without holding [stateLock].
     *
     * The generic path reaches this method through [directFramePostRunnable], because callers can
     * be on the main/session thread. The recurring producer callback invokes it directly after
     * releasing [stateLock], so decoded-image/prewarm messages already queued on the same Looper
     * cannot postpone the next display-vsync registration.
     */
    private fun postReservedDirectFrameCallback(choreographer: Choreographer) {
        val stillCurrent = synchronized(stateLock) {
            directFrameCallbackPosted && renderRunning && directSurfaceReady &&
                choreographer === directChoreographer
        }
        if (!stillCurrent) {
            synchronized(stateLock) {
                if (choreographer !== directChoreographer || !renderRunning || !directSurfaceReady) {
                    directFrameCallbackPosted = false
                }
            }
            return
        }
        try {
            choreographer.postFrameCallback(directFrameCallback)
            directRenderHandler?.removeCallbacks(directCadenceWatchdog)
            directRenderHandler?.postDelayed(
                directCadenceWatchdog,
                directCadenceWatchdogDelayMs()
            )
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                directFrameCallbackPosted = false
            }
            Log.e(TAG, "reader_direct_frame_callback_post_failed", failure)
        }
    }

    private fun postDirectFrameCallbackLocked(): Boolean {
        if (!renderRunning || !directSurfaceReady || directFrameCallbackPosted ||
            rollingNativeHandle == 0L || rollingNativeAttachEpoch == 0L
        ) return false
        val hasAdmittedFrame = framePipe == FramePipe.INVALIDATION_POSTED && inFlightToken != 0L
        if (!hasAdmittedFrame && !shouldKeepDirectCadenceArmedLocked()) return false
        val choreographer = directChoreographer ?: return false
        val handler = directRenderHandler ?: return false
        directFrameCallbackPosted = true
        // Validate the Choreographer here so teardown cannot reserve an impossible callback, but
        // make the platform call in directFramePostRunnable after stateLock has been released.
        if (choreographer !== directChoreographer || !handler.post(directFramePostRunnable)) {
            directFrameCallbackPosted = false
            return false
        }
        return true
    }

    private fun postLateDirectInputCatchupLocked(): Boolean {
        val refreshPeriodNanos =
            (frameBudgetMs() * NANOS_PER_MILLISECOND.toFloat()).toLong().coerceAtLeast(1L)
        if (!NtkLateInputCatchupPolicy.shouldPost(
                renderRunning = renderRunning,
                directSurfaceReady = directSurfaceReady,
                callbackPosted = directFrameCallbackPosted,
                callbackHadAdmission = directCallbackHadAdmission,
                catchupPosted = directLateInputCatchupPosted ||
                    directAdjacentExactP0CatchupPosted,
                newPhysicalGesture =
                    physicalGestureRevision != directCallbackObservedPhysicalGestureRevision,
                pointerDown = pointerDown,
                dragging = dragging,
                targetRevision = dragTargetRevision,
                callbackObservedTargetRevision = directCallbackObservedDragTargetRevision,
                callbackObservedAtNanos = directCallbackObservedAtNanos,
                nowNanos = System.nanoTime(),
                refreshPeriodNanos = refreshPeriodNanos
            )
        ) {
            return false
        }
        val handler = directRenderHandler ?: return false
        directLateInputCatchupPosted = true
        if (!handler.postAtFrontOfQueue(directLateInputCatchup)) {
            directLateInputCatchupPosted = false
            return false
        }
        return true
    }

    private fun shouldKeepDirectCadenceArmedLocked(): Boolean {
        return pointerDown || dragging || scrollbarDragging || !scroller.isFinished
    }

    private fun renderDirectSurfaceFrame(frameTimeNanos: Long) {
        var nextFrameChoreographer: Choreographer? = null
        val admission = synchronized(stateLock) {
            directFrameCallbackPosted = false
            directCallbackHadAdmission = false
            if (!renderRunning || !directSurfaceReady || frameSchedulingSuppressed ||
                rollingTextureSurface?.isValid != true
            ) {
                null
            } else {
                // MotionEvent has already applied the exact physical finger position. This
                // callback may submit that position, but never invent an in-between viewport.
                directCallbackObservedDragTargetRevision = dragTargetRevision
                directCallbackObservedPhysicalGestureRevision = physicalGestureRevision
                directCallbackObservedAtNanos = System.nanoTime()
                // Request the next display callback before doing any draw/submission work. This is
                // important on the host-GPU emulator: requesting it near the end of this callback
                // frequently misses the next vsync even though native submission itself is <2ms.
                if (shouldKeepDirectCadenceArmedLocked() &&
                    !directFrameCallbackPosted &&
                    rollingNativeHandle != 0L &&
                    rollingNativeAttachEpoch > 0L
                ) {
                    directChoreographer?.let { choreographer ->
                        directFrameCallbackPosted = true
                        nextFrameChoreographer = choreographer
                    }
                }
                val admittedFrame = if (
                    framePipe == FramePipe.INVALIDATION_POSTED && inFlightToken != 0L
                ) {
                    inFlightEpoch to inFlightToken
                } else {
                    null
                }
                directCallbackHadAdmission = admittedFrame != null
                admittedFrame
            }
        }
        // This callback already runs on ReaderSurfaceProducer. Register the successor now, with no
        // reader lock held, rather than placing it behind image-install/prewarm messages in this
        // Looper's queue.
        nextFrameChoreographer?.let(::postReservedDirectFrameCallback)
        if (admission == null) {
            synchronized(stateLock) {
                // A sparse host/device MOVE can be physically committed before the next MOVE
                // arrives. There is then no frame to submit, so close only after every requested
                // viewport version has committed.
                if (shouldClosePhysicalMotionInterval(
                        traceActive = physicalScrollTraceCookie != 0,
                        scrollerFinished = scroller.isFinished,
                        requiredVersion = physicalMotionRequiredVersion,
                        committedVersion = committedVersion
                    )
                ) {
                    endPhysicalScrollTraceLocked()
                    statsLastCallbackStartNs = 0L
                    statsLastPostEndNs = 0L
                }
            }
            return
        }

        var rendered = false
        try {
            // Canvas submission is intentionally absent. Hardware Canvas re-enters HWUI's
            // RenderThread/BLAST wait and software Canvas round-trips the entire color buffer
            // through gfxstream. The native lane queues an AHardwareBuffer transaction and later
            // returns the exact SurfaceControl OnCommit identity for this token.
            rendered = renderFrame(frameTimeNanos, null, directSurface = true)
        } catch (failure: Throwable) {
            Log.e(
                TAG,
                    "reader_direct_surface_submit_failed epoch=${admission.first}," +
                    "token=${admission.second},valid=" +
                    (rollingTextureSurface?.isValid == true),
                failure
            )
        }
        synchronized(stateLock) {
            // Close after submission/computation so the final moving setBuffer belongs to the
            // physical interval. A later MOVE starts a new interval without counting a stationary
            // finger hold as a missed display frame.
            if (shouldClosePhysicalMotionInterval(
                    traceActive = physicalScrollTraceCookie != 0,
                    scrollerFinished = scroller.isFinished,
                    requiredVersion = physicalMotionRequiredVersion,
                    committedVersion = committedVersion
                )
            ) {
                endPhysicalScrollTraceLocked()
                statsLastCallbackStartNs = 0L
                statsLastPostEndNs = 0L
            }
        }
        if (!rendered) {
            recoverDirectSurfaceSubmission(admission.first, admission.second)
        }
    }

    @Keep
    fun onNtkRollingFrameLatched(
        token: Long,
        latchNanos: Long,
        observedNanos: Long,
        presentationKind: Int
    ) {
        // ASurfaceTransaction reports CLOCK_MONOTONIC timestamps, whereas the Java telemetry
        // session uses elapsedRealtimeNanos. Convert by subtracting the native callback latency
        // from the Java receipt time instead of assuming the absolute clocks share an epoch.
        val callbackReceivedUptimeNanos = SystemClock.elapsedRealtimeNanos()
        val presentedUptimeNanos = surfaceLatchPresentedUptimeNanos(
            callbackReceivedUptimeNanos,
            latchNanos,
            observedNanos
        )
        if (presentationKind == NATIVE_PRESENTATION_BUFFER_QUEUE) {
            // The window renderer invokes this callback synchronously on its Choreographer/EGL
            // owner immediately after a successful queueBuffer. Committing the immutable proof
            // on that same owner removes two main-Looper hops from every moving frame. State is
            // protected by stateLock and the Activity listener is still dispatched once on main.
            completeOrBufferNativePresentation(
                token,
                presentedUptimeNanos,
                presentationKind,
                allowBuffer = true,
            )
            return
        }
        mainHandler.post {
            val submission = synchronized(stateLock) {
                pendingFrameCommits[token]?.takeIf { it.surfaceControlSubmission }
            } ?: return@post
            val epoch = submission.frameEpoch
            if (presentedUptimeNanos <= 0L) {
                onNtkRollingFrameDropped(token)
                return@post
            }
            synchronized(stateLock) {
                if (rollingNativeAttachEpoch == submission.nativeSurfaceEpoch &&
                    submission.nativeSurfaceEpoch > 0L && !rollingNativeFatal &&
                    traversalStructureEpoch == submission.proof.structureEpoch
                ) {
                    nativePresentationVisible = true
                    nativePresentedStructureEpoch = submission.proof.structureEpoch
                }
            }
            onFrameCommitted(
                epoch,
                token,
                surfaceControlLatchObserved = presentationKind == NATIVE_PRESENTATION_SURFACE_CONTROL,
                surfaceControlPresentedUptimeNanos = presentedUptimeNanos,
                nativePresentationKind = presentationKind
            )
        }
    }

    private fun completeOrBufferNativePresentation(
        token: Long,
        presentedUptimeNanos: Long,
        presentationKind: Int,
        allowBuffer: Boolean,
    ) {
        var unmatchedDiagnostic: String? = null
        val submission = synchronized(stateLock) {
            val registered = pendingFrameCommits[token]
                ?.takeIf { it.surfaceControlSubmission }
            if (registered == null && allowBuffer &&
                framePipe == FramePipe.INVALIDATION_POSTED &&
                token == inFlightToken && token != 0L
            ) {
                // Exactly one frame can own the posted admission. A callback for that token is
                // therefore safe to hold until renderFrame installs the corresponding immutable
                // proof; unrelated or stale native callbacks remain fail-closed.
                earlyNativePresentations[token] = EarlyNativePresentation(
                    presentedUptimeNanos,
                    presentationKind,
                )
            }
            if (registered == null && token !in earlyNativePresentations) {
                // Page delivery, focus, or Surface lifecycle work can retire the Kotlin proof
                // while the native producer is already swapping that exact buffer. The swap is
                // real, but it cannot be identity-qualified after its proof was retired. Do not
                // leave that first real-pixel buffer as the only submission forever: coalesce one
                // fresh proof-bearing frame from the current immutable page state. This performs
                // no loading/prewarming and never qualifies the unmatched buffer itself.
                if (renderRunning && pages.isNotEmpty() && framePipe == FramePipe.IDLE &&
                    directSurfaceReady && rollingNativeHandle != 0L &&
                    rollingNativeAttachEpoch > 0L && !rollingNativeFatal
                ) {
                    renderRequested = true
                    scheduleFrameLocked()
                }
                unmatchedNativePresentationCount++
                if (unmatchedNativePresentationCount == 1L ||
                    unmatchedNativePresentationCount % NATIVE_PRESENTATION_DIAGNOSTIC_INTERVAL == 0L
                ) {
                    unmatchedDiagnostic =
                        "reader_native_presentation_unmatched count=$unmatchedNativePresentationCount," +
                            "token=$token,kind=$presentationKind,pipe=$framePipe," +
                            "inFlight=$inFlightToken,pending=${pendingFrameCommits.size}," +
                            "running=$renderRunning,surface=$directSurfaceReady," +
                            "attach=$rollingNativeAttachEpoch,requested=$renderRequested"
                }
            }
            registered
        }
        unmatchedDiagnostic?.let { Log.w(TAG, it) }
        if (submission == null) return
        if (presentedUptimeNanos <= 0L) {
            onNtkRollingFrameDropped(token)
            return
        }
        synchronized(stateLock) {
            if (rollingNativeAttachEpoch == submission.nativeSurfaceEpoch &&
                submission.nativeSurfaceEpoch > 0L && !rollingNativeFatal &&
                traversalStructureEpoch == submission.proof.structureEpoch
            ) {
                nativePresentationVisible = true
                nativePresentedStructureEpoch = submission.proof.structureEpoch
            }
        }
        onFrameCommitted(
            submission.frameEpoch,
            token,
            surfaceControlLatchObserved = false,
            surfaceControlPresentedUptimeNanos = presentedUptimeNanos,
            nativePresentationKind = presentationKind,
            allowNativeProducerThread = true,
        )
    }

    @Keep
    fun onNtkRollingFrameDropped(token: Long) {
        // Native mailbox supersession is already serialized by [stateLock]. Retire the token on
        // this producer callback instead of placing two Runnables behind the completed-draw/UI
        // queue. Otherwise six delayed drop callbacks can fill the commit window and turn a
        // transiently busy main thread into a persistent render stall until Home recreates it.
        val epoch = synchronized(stateLock) { pendingFrameCommits[token]?.frameEpoch } ?: return
        recoverDirectSurfaceSubmission(epoch, token)
    }

    @Keep
    fun onNtkRollingRendererFatal(reason: String) {
        mainHandler.post {
            var retiredHandle = 0L
            var retiredSubmissionCount = 0
            val shouldRecover = synchronized(stateLock) {
                if (rollingNativeRecoveryPending || rollingNativeHandle == 0L) {
                    false
                } else {
                    retiredHandle = rollingNativeHandle
                    retiredSubmissionCount = pendingFrameCommits.values.count {
                        it.surfaceControlSubmission
                    }
                    // The failure belongs to this physical backend instance, not the reader
                    // session. Retire its handle before destruction so late callbacks cannot
                    // qualify frames produced by the replacement renderer.
                    rollingNativeHandle = 0L
                    advanceRollingNativeSurfaceEpochLocked()
                    rollingNativeAttachEpoch = 0L
                    rollingNativeSurfaceIdentity = 0
                    rollingNativeWidth = 0
                    rollingNativeHeight = 0
                    rollingNativeViewportWidth = 0
                    rollingNativeViewportHeight = 0
                    rollingNativePreparedWidth = 0
                    rollingNativePreparedHeight = 0
                    nativePresentationVisible = false
                    nativePresentedStructureEpoch = 0L
                    rollingNativeFatal = true
                    rollingNativeRecoveryPending = true
                    clearFramePipeLocked(preserveDirty = true)
                    renderRequested = false
                    stateLock.notifyAll()
                    true
                }
            }
            if (!shouldRecover) return@post
            Log.w(
                TAG,
                "reader_native_surface_control_reset reason=$reason " +
                    "pending=$retiredSubmissionCount"
            )

            val destroyAndRecover = Runnable {
                NtkRollingNativeBridge.nativeDestroy(retiredHandle)
                mainHandler.post { completeRollingNativeRecovery(reason) }
            }
            val renderHandler = synchronized(stateLock) { directRenderHandler }
            if (renderHandler?.post(destroyAndRecover) != true) {
                Thread(destroyAndRecover, "ReaderSurfaceNativeRecovery").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                    start()
                }
            }
        }
    }

    private fun completeRollingNativeRecovery(reason: String) {
        val reattach = synchronized(stateLock) {
            rollingNativeRecoveryPending = false
            rollingNativeFatal = false
            firstNativeSubmitAccepted = false
            val surface = rollingTextureSurface
            val canReattach = isAttachedToWindow && renderRunning && directSurfaceReady &&
                surface?.isValid == true
            if (canReattach) {
                Triple(checkNotNull(surface), width, height)
            } else {
                null
            }
        }
        Log.i(
            TAG,
            "reader_native_surface_control_reset_complete reason=$reason " +
                "reattach=${reattach != null}"
        )
        if (reattach != null) {
            attachRollingNativeSurface(reattach.first, reattach.second, reattach.third)
        }
    }

    private fun recoverDirectSurfaceSubmission(epoch: Long, token: Long) {
        synchronized(stateLock) {
            if (epoch != lifecycleEpoch || pendingFrameCommits[token]?.frameEpoch != epoch) return
            pendingFrameCommits.remove(token)
            if (token == inFlightToken && epoch == inFlightEpoch) {
                releasePostedAdmissionLocked(preserveDirty = true)
            }
            drawnVersion = latestSubmittedVersionLocked()
            if (pages.isNotEmpty()) renderRequested = true
            // This method posts the eventual retry to main; no View mutation occurs on the native
            // callback thread itself.
            scheduleNoStateRetryLocked()
            stateLock.notifyAll()
        }
    }

    private fun windowRequestLocked(busy: Boolean): WindowRequest? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        val anchor = anchorPageLocked()
        val first = max(0, anchor - ReaderPipelinePolicy.windowBefore(busy))
        val last = min(pages.lastIndex, anchor + ReaderPipelinePolicy.windowAfter(busy))
        val liveReverseEvidence = NtkReverseWindowEvidencePolicy.capture(
            busy = busy,
            activeDirection = activeInputDirection,
            firstVisiblePage = firstVisiblePageLocked(scrollOffset)
                .coerceIn(0, pages.lastIndex),
        )
        if (liveReverseEvidence.reverseFirstPageHint >= 0) {
            pendingReverseWindowFirstPageHint = if (pendingReverseWindowFirstPageHint >= 0) {
                minOf(
                    pendingReverseWindowFirstPageHint,
                    liveReverseEvidence.reverseFirstPageHint,
                )
            } else {
                liveReverseEvidence.reverseFirstPageHint
            }
        }
        val boundaryPx = height * NEAR_BOUNDARY_SCREENFULS
        val nearStart = scrollOffset <= boundaryPx ||
            anchor <= NEAR_BOUNDARY_PAGE_THRESHOLD
        val remainingPx = contentHeight - (scrollOffset + height)
        val nearEnd = remainingPx <= boundaryPx ||
            anchor >= pages.size - NEAR_BOUNDARY_PAGE_THRESHOLD
        val progress = progressPositionLocked() ?: return null
        val nearChanged = nearStart != lastNearStart || nearEnd != lastNearEnd
        var notifyNearStart = nearStart && nearStart != lastNearStart
        var notifyNearEnd = nearEnd && nearEnd != lastNearEnd
        if (busy && lastRequestedBusy) {
            val now = SystemClock.uptimeMillis()
            val anchorMoved = lastAnchor < 0 || abs(anchor - lastAnchor) >= BUSY_WINDOW_ANCHOR_STEP
            val intervalElapsed = now - lastBusyWindowDispatchMs >= BUSY_WINDOW_MIN_DISPATCH_MS
            if ((notifyNearStart || notifyNearEnd) &&
                now - lastBusyNearDispatchMs < BUSY_NEAR_BOUNDARY_MIN_DISPATCH_MS
            ) {
                notifyNearStart = false
                notifyNearEnd = false
            }
            if (!anchorMoved && !intervalElapsed && !nearChanged) return null
        }
        if (anchor == lastAnchor && busy == lastRequestedBusy && !nearChanged) return null
        if (busy && (notifyNearStart || notifyNearEnd)) {
            lastBusyNearDispatchMs = SystemClock.uptimeMillis()
        }
        lastAnchor = anchor
        lastNearStart = nearStart
        lastNearEnd = nearEnd
        lastRequestedBusy = busy
        if (busy) lastBusyWindowDispatchMs = SystemClock.uptimeMillis()
        val reverseEvidence = NtkReverseWindowEvidencePolicy.merge(
            NtkReverseWindowEvidencePolicy.Evidence(
                directionHint = if (pendingReverseWindowFirstPageHint >= 0) {
                    DIRECTION_PREVIOUS
                } else {
                    0
                },
                reverseFirstPageHint = pendingReverseWindowFirstPageHint,
            ),
            liveReverseEvidence,
        )
        return WindowRequest(
            first,
            last,
            anchor,
            progress.page,
            progress.offset,
            busy,
            nearStart,
            nearEnd,
            notifyNearStart,
            notifyNearEnd,
            reverseEvidence.directionHint,
            reverseEvidence.reverseFirstPageHint,
        )
    }

    private fun dispatchWindowRequest(request: WindowRequest?, fromInput: Boolean = false) {
        if (request == null) return
        synchronized(stateLock) {
            val previous = pendingWindowRequest
            val previousEvidence = previous?.let {
                NtkReverseWindowEvidencePolicy.Evidence(
                    it.directionHint,
                    it.reverseFirstPageHint,
                )
            }
            val cadenceSuppressedEvidence = NtkReverseWindowEvidencePolicy.Evidence(
                directionHint = if (pendingReverseWindowFirstPageHint >= 0) {
                    DIRECTION_PREVIOUS
                } else {
                    0
                },
                reverseFirstPageHint = pendingReverseWindowFirstPageHint,
            )
            val requestEvidence = NtkReverseWindowEvidencePolicy.merge(
                cadenceSuppressedEvidence,
                NtkReverseWindowEvidencePolicy.Evidence(
                    request.directionHint,
                    request.reverseFirstPageHint,
                ),
            )
            val evidence = NtkReverseWindowEvidencePolicy.merge(
                previousEvidence,
                requestEvidence,
            )
            pendingReverseWindowFirstPageHint = -1
            pendingWindowRequest = request.copy(
                directionHint = evidence.directionHint,
                reverseFirstPageHint = evidence.reverseFirstPageHint,
            )
            if (windowDispatchPosted) return
            windowDispatchPosted = true
        }
        val deliver = Runnable {
            val latest = synchronized(stateLock) {
                windowDispatchPosted = false
                val next = pendingWindowRequest
                pendingWindowRequest = null
                next
            } ?: return@Runnable
            deliverWindowRequest(latest)
        }
        val busyDispatch = fromInput || request.busy
        if (busyDispatch) {
            postOnAnimation(deliver)
        } else {
            mainHandler.post(deliver)
        }
    }

    private fun deliverWindowRequest(latest: WindowRequest) {
        val currentListener = listener ?: return
        currentListener.onWindowChanged(
            latest.firstPage,
            latest.lastPage,
            latest.anchorPage,
            latest.progressPage,
            latest.progressOffset,
            latest.busy,
            latest.directionHint,
            latest.reverseFirstPageHint,
        )
        if (latest.notifyNearStart) currentListener.onNearBoundary(DIRECTION_PREVIOUS, latest.anchorPage)
        if (latest.notifyNearEnd) currentListener.onNearBoundary(DIRECTION_NEXT, latest.anchorPage)
    }

    private fun dispatchBoundaryRequest(request: BoundaryRequest?) {
        if (request == null) return
        postOnAnimationDelayed({
            listener?.onBoundaryReached(request.direction, request.anchorPage)
        }, BOUNDARY_DISPATCH_POST_FRAME_DELAY_MS)
    }

    private fun anchorPageLocked(): Int {
        rebuildLayoutLocked()
        val probe = scrollOffset + height * 0.35f
        return firstVisiblePageLocked(probe).coerceIn(0, pages.lastIndex)
    }

    private fun pageOffsetLocked(index: Int): Int {
        if (index < 0 || index >= pages.size) return 0
        rebuildLayoutLocked()
        return (pageTopOrElseLocked(index, 0f) - scrollOffset).toInt()
    }

    private fun applyLockedRestorePositionLocked() {
        val target = lockedRestorePage
        if (target !in 0 until pages.size) return
        if (SystemClock.uptimeMillis() > lockedRestoreUntilMs) {
            clearLockedRestorePositionLocked()
            return
        }
        rebuildLayoutLocked()
        val restoreOffset = repairedDirectWifiInitialRestoreOffsetLocked(target)
        val desiredScroll = pageTopOrElseLocked(target, 0f) - restoreOffset
        val maxScroll = maxScrollLocked()
        val restoredScroll = desiredScroll.coerceIn(0f, maxScroll)
        setScrollOffsetLocked(restoredScroll)
        val preserveForwardOnlyUntilPhysicalReveal =
            directWifiForwardOnlyInitialResumeEnabled &&
                surfaceAttachmentDeferredUntilActualPixels &&
                directWifiForwardOnlyInitialResumePage == target
        // A different forward page can become drawable a few milliseconds before the bookmark
        // page. Progressive HWUI must remain free to show those real pixels, but that unrelated
        // draw must not retire the bookmark lock while its exact geometry is still unresolved.
        // User input clears the lock independently, so this never fights an intentional scroll.
        val preserveUntilBookmarkGeometryResolves =
            directWifiForwardOnlyInitialResumeEnabled &&
                !pageHasCompleteActualPixelsLocked(pages[target])
        if (
            !preserveForwardOnlyUntilPhysicalReveal &&
            !preserveUntilBookmarkGeometryResolves &&
            hasDrawnContentFrame &&
            pageHasDrawableContentLocked(target) &&
            abs(restoredScroll - desiredScroll) <= RESTORE_POSITION_EPSILON_PX
        ) {
            clearLockedRestorePositionLocked()
        }
    }

    private fun clearLockedRestorePositionLocked() {
        lockedRestorePage = -1
        lockedRestoreOffset = 0
        lockedRestoreUntilMs = 0L
    }

    private fun progressPositionLocked(): ProgressPosition? {
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        rebuildLayoutLocked()
        val maxScroll = maxScrollLocked()
        if (maxScroll > 0f && scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX) {
            val lastPage = pages.lastIndex
            return ProgressPosition(lastPage, pageOffsetLocked(lastPage))
        }
        val page = firstVisiblePageLocked(scrollOffset + height * PROGRESS_PAGE_PROBE_SCREEN_RATIO)
            .coerceIn(0, pages.lastIndex)
        return ProgressPosition(page, pageOffsetLocked(page))
    }

    private fun restoreViewportAnchorLocked(
        anchor: ProgressPosition?,
        reason: String,
        index: Int = -1,
        oldHeight: Float = 0f,
        newHeight: Float = 0f
    ) {
        val target = anchor?.page ?: return
        if (target !in pages.indices) return
        val now = SystemClock.uptimeMillis()
        val recentScrollSettling = lastScrollInteractionMs > 0L &&
            now - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
        if (!shouldRestoreAnchorAfterPendingResolves(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling
            )
        ) {
            return
        }
        rebuildLayoutLocked()
        val before = scrollOffset
        val desired = pageTopOrElseLocked(target, 0f) - anchor.offset
        if (
            recentScrollSettling &&
            desired < before - height * HEIGHT_CHANGE_RESTORE_BACKSTEP_SCREEN_LIMIT
        ) {
            Log.d(
                TAG,
                "reader_viewport_anchor_restore_skip_backstep reason=$reason index=$index " +
                    "anchor=$target anchorOffset=${anchor.offset} from=${before.toInt()} " +
                    "to=${desired.toInt()} old=${oldHeight.toInt()} new=${newHeight.toInt()} " +
                    "lastBusy=$lastBusy"
            )
            return
        }
        setScrollOffsetLocked(desired)
        clampScrollLocked()
        if (abs(scrollOffset - before) > HEIGHT_CHANGE_EPSILON_PX) {
            Log.d(
                TAG,
                "reader_viewport_anchor_restore reason=$reason index=$index anchor=$target " +
                    "anchorOffset=${anchor.offset} old=${oldHeight.toInt()} new=${newHeight.toInt()} " +
                    "from=${before.toInt()} to=${scrollOffset.toInt()} lastBusy=$lastBusy"
            )
        }
    }

    private fun clampScrollLocked() {
        val maxScroll = effectiveMaxScrollLocked(maxScrollLocked())
        val minScroll = if (prependedRevealHoldPage in pages.indices) {
            min(pageTopOrElseLocked(prependedRevealHoldPage, 0f), maxScroll)
        } else {
            0f
        }.coerceAtMost(maxScroll)
        setScrollOffsetLocked(scrollOffset.coerceIn(minScroll, maxScroll))
    }

    private fun clampForwardScrollLocked() {
        val contentMaxScroll = effectiveMaxScrollLocked(maxScrollLocked())
        val maxScroll = effectiveMaxScrollLocked(
            if (limitScrollToDrawablePrefix) forwardScrollLimitLocked() else maxScrollLocked()
        )
        val minScroll = if (prependedRevealHoldPage in pages.indices) {
            min(pageTopOrElseLocked(prependedRevealHoldPage, 0f), maxScroll)
        } else {
            0f
        }.coerceAtMost(maxScroll)
        val before = scrollOffset
        val clamped = scrollOffset.coerceIn(minScroll, maxScroll)
        val contentBoundsCorrection = shouldApplyContentMaxShrinkCorrection(
            scrollOffset,
            contentMaxScroll,
            contentMaxScroll
        )
        val activeOrRecentInput = pointerDown ||
            dragging ||
            !scroller.isFinished ||
            (lastScrollInteractionMs > 0L &&
                SystemClock.uptimeMillis() - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS)
        if (contentBoundsCorrection) {
            // A content-height shrink can leave the current viewport physically beyond the new
            // end. This is bounds repair, not a user-direction reversal, and must remove the
            // exposed terminal strip immediately even while a forward gesture is active.
            setScrollOffsetLocked(
                contentMaxScroll,
                allowContentMaxShrinkCorrection = true
            )
        } else if (
            clamped < scrollOffset - SCROLL_OFFSET_EPSILON_PX &&
            activeOrRecentInput
        ) {
            scheduleBlockedForwardWindowRequestLocked()
        } else {
            setScrollOffsetLocked(clamped)
        }
        if (
            !isNextBoundaryGeometryHeldLocked() &&
            limitScrollToDrawablePrefix &&
            scrollOffset < before - SCROLL_OFFSET_EPSILON_PX
        ) {
            scheduleBlockedForwardWindowRequestLocked()
        }
    }

    /**
     * A bookmark page is selected by the progress probe, so a valid saved page/offset pair always
     * leaves meaningful pixels from that page in the resumed viewport. Old placeholder geometry
     * can violate that invariant after the original dimensions arrive (for example, a short manga
     * page paired with a large negative offset). In the direct-Wi-Fi forward-only cold path there
     * are intentionally no predecessor pixels to fill such a viewport. Keep the stable saved page
     * identity and repair only the impossible offset after that page's complete original pixels
     * establish its real draw height. Progressive HWUI admission is deliberately independent:
     * another forward page may reveal first without invalidating the still-pending bookmark
     * correction. This changes neither surface admission nor reveal timing.
     */
    private fun repairedDirectWifiInitialRestoreOffsetLocked(target: Int): Int {
        val savedOffset = lockedRestoreOffset
        if (!directWifiForwardOnlyInitialResumeEnabled ||
            target != lockedRestorePage ||
            target !in pages.indices || width <= 0 || height <= 0
        ) return savedOffset
        val page = pages[target]
        if (page.pendingResolveType != PENDING_NONE ||
            !pageHasCompleteActualPixelsLocked(page)
        ) return savedOffset
        val pageHeight = pageDrawHeightLocked(page)
        val repairedOffset = repairForwardOnlyRestoreOffset(
            savedOffset,
            pageHeight,
            height,
        )
        if (repairedOffset == savedOffset) return savedOffset
        lockedRestoreOffset = repairedOffset
        if (directWifiForwardOnlyInitialResumePage == target) {
            directWifiForwardOnlyInitialResumeOffset = repairedOffset
        }
        Log.d(
            TAG,
            "reader_initial_restore_offset_repaired page=$target saved=$savedOffset " +
                "repaired=$repairedOffset pageHeight=${pageHeight.toInt()} viewportHeight=$height"
        )
        return repairedOffset
    }

    private fun applyPreparedStartAnchorLocked(clearPending: Boolean) {
        val target = pendingPreparedStartPage
        if (target !in pages.indices) return
        val viewportHeight = if (height > 0) height else pendingPreparedViewportHeight
        val desiredScroll = pageTopOrElseLocked(target, 0f) - pendingPreparedStartOffset
        val maxScroll = max(0f, contentHeight - max(1, viewportHeight))
        scrollOffset = desiredScroll.coerceIn(0f, maxScroll)
        if (clearPending) clearPreparedStartAnchorLocked()
    }

    private fun clearPreparedStartAnchorLocked() {
        pendingPreparedStartPage = -1
        pendingPreparedStartOffset = 0
        pendingPreparedViewportWidth = 0
        pendingPreparedViewportHeight = 0
    }

    private fun scheduleVisibleLoadingHoldRetryLocked() {
        if (visibleLoadingHoldRetryPosted) return
        visibleLoadingHoldRetryPosted = true
        mainHandler.postDelayed({
            synchronized(stateLock) {
                visibleLoadingHoldRetryPosted = false
                if (!renderRunning || pages.isEmpty()) return@synchronized
                if (shouldBlockInitialEmptyFrameLocked()) return@synchronized
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }, VISIBLE_LOADING_HOLD_RETRY_MS)
    }

    private fun maybeReleasePrependedReadyHoldLocked(index: Int) {
        if (prependedReadyHoldRequired <= 0 || prependedReadyHoldInserted <= 0) return
        if (index !in 0 until prependedReadyHoldInserted) return
        var ready = 0
        val end = min(prependedReadyHoldInserted, pages.size)
        for (i in 0 until end) {
            val page = pages[i]
            if (page.cardText != null) continue
            if (page.bitmap != null || page.tiles.isNotEmpty()) ready++
            if (ready >= prependedReadyHoldRequired) {
                clearPrependedReadyHoldLocked()
                return
            }
        }
    }

    private fun clearPrependedReadyHoldLocked() {
        prependedReadyHoldInserted = 0
        prependedReadyHoldRequired = 0
        if (prependedRevealHoldPage >= 0) prependedRevealHoldPage = -1
    }

    private fun maxScrollLocked(): Float {
        val viewportHeight = if (height > 0) height else pendingPreparedViewportHeight
        val contentMax = max(0f, totalHeightLocked() - max(1, viewportHeight))
        val forwardOnlyTarget = directWifiForwardOnlyInitialResumePage
        if (!directWifiForwardOnlyInitialResumeEnabled ||
            !surfaceAttachmentDeferredUntilActualPixels ||
            forwardOnlyTarget !in pages.indices
        ) return contentMax
        // This is a temporary pre-presentation bound only. It creates no motion and disappears as
        // soon as the exact forward pages make the ordinary content max large enough.
        val forwardOnlyScroll = pageTopOrElseLocked(forwardOnlyTarget, contentMax) -
            directWifiForwardOnlyInitialResumeOffset
        return max(contentMax, forwardOnlyScroll)
    }

    private fun forwardScrollLimitLocked(scheduleBlocked: Boolean = true): Float {
        val fullMaxScroll = maxScrollLocked()
        if (!limitScrollToDrawablePrefix || pages.isEmpty() || height <= 0) return fullMaxScroll
        if (allPagesHaveDrawableContentLocked()) return fullMaxScroll
        val firstRelevant = firstVisiblePageLocked(scrollOffset).coerceIn(0, pages.lastIndex)
        var start = firstRelevant
        while (start > 0 && pageTopOrElseLocked(start, 0f) > scrollOffset) {
            if (!pageHasDrawableContentLocked(start - 1)) break
            start--
        }
        if (!drawableViewportCleanAtScrollLocked(scrollOffset)) {
            if (scheduleBlocked) scheduleBlockedForwardWindowRequestLocked()
            return scrollOffset.coerceIn(0f, fullMaxScroll)
        }
        for (index in start..pages.lastIndex) {
            val adjacentPrefixBottom = adjacentExactPrefixBottomLocked(index)
            if (adjacentPrefixBottom != null &&
                !pageHasCompleteDrawableContentLocked(index)
            ) {
                if (scheduleBlocked) scheduleBlockedForwardWindowRequestLocked()
                return min(
                    fullMaxScroll,
                    adjacentPrefixBottom - height + COVERAGE_EDGE_FILL_PX,
                ).coerceAtLeast(0f)
            }
            if (!pageHasDrawableContentLocked(index)) {
                if (scheduleBlocked) scheduleBlockedForwardWindowRequestLocked()
                val missingTop = pageTopOrElseLocked(index, fullMaxScroll + height)
                return min(fullMaxScroll, missingTop - height + COVERAGE_EDGE_FILL_PX)
                    .coerceAtLeast(0f)
            }
        }
        return fullMaxScroll
    }

    private fun capForwardInputScrollLocked(rawNext: Float, direction: Int): Float {
        if (!limitScrollToDrawablePrefix || direction != DIRECTION_NEXT) return rawNext
        if (pages.isEmpty() || height <= 0) return rawNext
        if (hasPendingPageResolvesLocked()) {
            applyVisiblePendingDrawableResolvesLocked()
        }
        if (allPagesHaveDrawableContentLocked()) return rawNext
        val maxScroll = maxScrollLocked()
        val targetPosition = rawNext.coerceIn(0f, maxScroll)
        if (drawableViewportCleanAtScrollLocked(targetPosition)) return rawNext
        if (
            applyPendingDrawableResolvesForViewportLocked(targetPosition) &&
            drawableViewportCleanAtScrollLocked(targetPosition)
        ) {
            return rawNext
        }
        val limit = forwardScrollLimitLocked(scheduleBlocked = false)
        if (rawNext <= limit + SCROLL_OFFSET_EPSILON_PX) return rawNext
        scheduleBlockedForwardWindowRequestLocked()
        if (shouldLogForwardCapLocked()) {
            val blockedForward = blockedForwardTargetPageLocked()
            Log.d(
                TAG,
                "reader_forward_cap raw=${rawNext.toInt()} limit=${limit.toInt()} " +
                    "scroll=${scrollOffset.toInt()} first=${firstVisiblePageLocked(scrollOffset)} " +
                    "blocked=$blockedForward " +
                    "blockedTop=${drawablePageTopLocked(blockedForward).toInt()} " +
                    "globalMissing=${firstMissingDrawablePageLocked()} max=${maxScrollLocked().toInt()}"
            )
        }
        return max(scrollOffset, limit)
    }

    private fun shouldLogForwardCapLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastForwardCapLogMs < FORWARD_CAP_LOG_INTERVAL_MS) return false
        lastForwardCapLogMs = now
        return true
    }

    private fun firstMissingDrawablePageLocked(): Int {
        for (index in pages.indices) {
            if (!pageHasCompleteDrawableContentLocked(index)) return index
        }
        return -1
    }

    private fun blockedForwardTargetPageLocked(): Int {
        if (pages.isEmpty()) return -1
        val visible = firstVisiblePageLocked(scrollOffset).coerceIn(0, pages.lastIndex)
        return (visible..pages.lastIndex).firstOrNull { index ->
            !pageHasCompleteDrawableContentLocked(index)
        } ?: -1
    }

    private fun firstMissingDrawableTopLocked(): Float {
        val page = firstMissingDrawablePageLocked()
        return if (page in pages.indices) pageTopOrElseLocked(page, -1f) else -1f
    }

    private fun drawablePageTopLocked(index: Int): Float {
        return if (index in pages.indices) pageTopOrElseLocked(index, -1f) else -1f
    }

    private fun drawableViewportCleanAtScrollLocked(position: Float): Boolean {
        if (pages.isEmpty() || height <= 0) return false
        val viewBottom = position + height
        var index = firstVisiblePageLocked(position).coerceIn(0, pages.lastIndex)
        while (index > 0 && pageTopOrElseLocked(index, 0f) > position) index--
        var coveredBottom = position
        var sawDrawable = false
        while (index < pages.size) {
            val pageTop = pageTopOrElseLocked(index, 0f)
            val pageBottom = pageTop + pageDrawHeightLocked(pages[index])
            if (pageTop >= viewBottom) break
            if (pageBottom > position && pageTop < viewBottom) {
                if (!pageHasDrawableContentLocked(index)) return false
                val adjacentPrefixBottom = adjacentExactPrefixBottomLocked(index)
                if (adjacentPrefixBottom != null &&
                    min(viewBottom, pageBottom) > adjacentPrefixBottom + COVERAGE_EDGE_FILL_PX
                ) return false
                sawDrawable = true
                coveredBottom = max(
                    coveredBottom,
                    min(viewBottom, min(pageBottom, adjacentPrefixBottom ?: pageBottom)),
                )
                if (coveredBottom >= viewBottom - COVERAGE_EDGE_FILL_PX) return true
            }
            index++
        }
        return sawDrawable && coveredBottom >= viewBottom - COVERAGE_EDGE_FILL_PX
    }

    private fun applyPendingDrawableResolvesForViewportLocked(position: Float): Boolean {
        if (pages.isEmpty() || height <= 0) return false
        rebuildLayoutLocked()
        val viewportTop = position.coerceIn(0f, maxScrollLocked())
        val viewportBottom = viewportTop + max(1, height)
        var index = firstVisiblePageLocked(viewportTop).coerceIn(0, pages.lastIndex)
        while (index > 0 && pageTopOrElseLocked(index, 0f) > viewportTop) index--
        var appliedCount = 0
        while (index < pages.size) {
            val page = pages[index]
            val pageTop = pageTopOrElseLocked(index, 0f)
            if (pageTop >= viewportBottom) break
            val pageBottom = pageTop + pageDrawHeightLocked(page)
            if (
                pageBottom > viewportTop + COVERAGE_EDGE_FILL_PX &&
                pageTop < viewportBottom - COVERAGE_EDGE_FILL_PX &&
                (page.pendingResolveType == PENDING_BITMAP || page.pendingResolveType == PENDING_TILES) &&
                page.bitmap == null &&
                page.tiles.isEmpty() &&
                applyPendingPageResolveLocked(index)
            ) {
                appliedCount++
            }
            index++
        }
        if (appliedCount <= 0) return false
        renderRequested = true
        Log.d(
            TAG,
            "reader_target_pending_resolve applied=$appliedCount target=${position.toInt()} " +
                "first=${firstVisiblePageLocked(viewportTop)}"
        )
        return true
    }

    private fun scheduleBlockedForwardWindowRequestLocked() {
        if (pages.isEmpty() || width <= 0 || height <= 0) return
        val firstBlocked = blockedForwardTargetPageLocked()
        if (firstBlocked !in pages.indices) return
        val now = SystemClock.uptimeMillis()
        if (
            firstBlocked == lastBlockedForwardPage &&
            (blockedForwardDispatchPosted ||
                now - lastBlockedForwardRequestAtMs < BLOCKED_FORWARD_REQUEST_THROTTLE_MS)
        ) {
            return
        }
        lastBlockedForwardPage = firstBlocked
        lastBlockedForwardRequestAtMs = now
        pendingBlockedForwardRequest = WindowRequest(
            firstPage = max(0, firstBlocked - 1),
            lastPage = min(
                pages.lastIndex,
                firstBlocked + max(
                    ReaderPipelinePolicy.windowAfter(true),
                    BLOCKED_FORWARD_RUNWAY_AFTER_PAGES
                )
            ),
            anchorPage = firstBlocked,
            progressPage = firstBlocked,
            progressOffset = pageOffsetLocked(firstBlocked),
            busy = true,
            nearStart = false,
            nearEnd = firstBlocked >= pages.size - NEAR_BOUNDARY_PAGE_THRESHOLD,
            notifyNearStart = false,
            notifyNearEnd = firstBlocked >= pages.size - NEAR_BOUNDARY_PAGE_THRESHOLD
        )
        if (blockedForwardDispatchPosted) return
        blockedForwardDispatchPosted = true
        val deliver = Runnable {
            val latest = synchronized(stateLock) {
                blockedForwardDispatchPosted = false
                val next = pendingBlockedForwardRequest
                pendingBlockedForwardRequest = null
                next
            } ?: return@Runnable
            deliverWindowRequest(latest)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.post(deliver)
        } else {
            mainHandler.post(deliver)
        }
    }

    private fun markBlockedDrawableResolvedLocked(index: Int) {
        if (index == lastBlockedForwardPage && pageHasCompleteDrawableContentLocked(index)) {
            lastBlockedForwardPage = -1
            lastBlockedForwardRequestAtMs = 0L
        }
    }

    private fun shouldSuppressDrawablePrefixFlingLocked(wasReleased: Boolean, dragDistance: Float): Boolean {
        if (!wasReleased || !limitScrollToDrawablePrefix || dragDistance <= touchSlop) return false
        if (width <= 0 || height <= 0 || pages.isEmpty()) return false
        if (!hasDrawnContentFrame && !hasAnyDrawableContentLocked()) return false
        if (allPagesHaveDrawableContentLocked()) return false
        return true
    }

    private fun allPagesHaveDrawableContentLocked(): Boolean {
        if (pages.isEmpty()) return false
        for (index in pages.indices) {
            if (!pageHasCompleteDrawableContentLocked(index)) return false
        }
        return true
    }

    private fun boundaryRequestLocked(clearDirection: Boolean = true): BoundaryRequest? {
        if (boundaryDispatchInFlight) return null
        val direction = boundaryArmedDirection
        if (clearDirection) boundaryArmedDirection = 0
        if (direction == 0 || pages.isEmpty() || width <= 0 || height <= 0) return null
        val cappedMaxScroll = maxScrollLocked()
        val fullMaxScroll = max(0f, totalHeightLocked() - height)
        val atStart = scrollOffset <= BOUNDARY_EPSILON_PX
        val atEnd = scrollOffset >= fullMaxScroll - BOUNDARY_EPSILON_PX
        if (direction == DIRECTION_NEXT && atEnd) {
            armNextBoundaryGeometryHoldLocked(pages.lastIndex)
        }
        if (
            direction == DIRECTION_NEXT &&
            limitScrollToDrawablePrefix &&
            cappedMaxScroll < fullMaxScroll - BOUNDARY_EPSILON_PX
        ) {
            Log.d(
                TAG,
                "reader_boundary_next_blocked_unresolved_tail " +
                    "scroll=${scrollOffset.toInt()} capped=${cappedMaxScroll.toInt()} full=${fullMaxScroll.toInt()}"
            )
            return null
        }
        val request = when {
            direction == DIRECTION_PREVIOUS && atStart -> BoundaryRequest(direction, 0)
            direction == DIRECTION_NEXT && atEnd -> BoundaryRequest(direction, pages.lastIndex)
            else -> null
        }
        if (request != null) boundaryDispatchInFlight = true
        return request
    }

    private fun totalHeightLocked(): Float {
        rebuildLayoutLocked()
        return contentHeight
    }

    private fun pageDrawHeightLocked(page: Page): Float {
        val viewWidth = max(
            1,
            if (width > 0) width else pendingPreparedViewportWidth
        )
        if (page.cardText != null) return TRANSITION_CARD_PAGE_HEIGHT_PX
        if (page.errorText != null) return TRANSITION_CARD_PAGE_HEIGHT_PX
        if (page.width > 0 && page.height > 0) {
            val drawWidth = viewWidth
            return max(1f, drawWidth * (page.height / page.width.toFloat()))
        }
        return max(1f, viewWidth * page.placeholderRatio)
    }

    private fun newPageLocked(placeholderRatio: Float = placeholderPageHeightRatio): Page {
        return Page(
            placeholderRatio = placeholderRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        )
    }

    private fun representativeResolvedPageRatioLocked(): Float {
        if (pages.isEmpty()) {
            return placeholderPageHeightRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        }
        val ratios = ArrayList<Float>(pages.size)
        for (page in pages) {
            if (page.cardText != null || page.errorText != null) continue
            if (page.width <= 0 || page.height <= 0) continue
            ratios.add(
                (page.height / page.width.toFloat()).coerceIn(
                    MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                    MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
                )
            )
        }
        if (ratios.isEmpty()) {
            return placeholderPageHeightRatio.coerceIn(
                MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
                MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
            )
        }
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    private fun resolvedPageDrawHeightLocked(pageWidth: Int, pageHeight: Int): Float {
        val viewWidth = max(1, width)
        if (pageWidth > 0 && pageHeight > 0) {
            val drawWidth = viewWidth
            return max(1f, drawWidth * (pageHeight / pageWidth.toFloat()))
        }
        return max(1f, viewWidth * placeholderPageHeightRatio)
    }

    private fun layoutBoundsForDrawableLocked(page: Page, drawableWidth: Int, drawableHeight: Int): Pair<Int, Int> {
        val safeDrawableWidth = max(1, drawableWidth)
        val safeDrawableHeight = max(1, drawableHeight)
        if (
            page.width > 0 &&
            page.height > 0 &&
            shouldPreserveLayoutBoundsForDrawableLocked(
                page.width,
                page.height,
                safeDrawableWidth,
                safeDrawableHeight
            )
        ) {
            return Pair(page.width, page.height)
        }
        if (
            page.pendingWidth > 0 &&
            page.pendingHeight > 0 &&
            (page.pendingResolveType == PENDING_BOUNDS || page.pendingResolveType == PENDING_SIZE) &&
            shouldPreserveLayoutBoundsForDrawableLocked(
                page.pendingWidth,
                page.pendingHeight,
                safeDrawableWidth,
                safeDrawableHeight
            )
        ) {
            return Pair(page.pendingWidth, page.pendingHeight)
        }
        return Pair(safeDrawableWidth, safeDrawableHeight)
    }

    private fun shouldPreserveLayoutBoundsForDrawableLocked(
        layoutWidth: Int,
        layoutHeight: Int,
        drawableWidth: Int,
        drawableHeight: Int
    ): Boolean {
        if (layoutWidth <= 0 || layoutHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) return false
        val layoutRatio = layoutHeight / layoutWidth.toFloat()
        val drawableRatio = drawableHeight / drawableWidth.toFloat()
        val ratioTolerance = max(0.05f, drawableRatio * 0.12f)
        if (abs(layoutRatio - drawableRatio) > ratioTolerance) return false
        val layoutDrawHeight = resolvedPageDrawHeightLocked(layoutWidth, layoutHeight)
        val drawableDrawHeight = resolvedPageDrawHeightLocked(drawableWidth, drawableHeight)
        return layoutDrawHeight + HEIGHT_CHANGE_EPSILON_PX >= drawableDrawHeight
    }

    private fun shouldDeferHeightChangingResolveLocked(
        oldTop: Float,
        oldHeight: Float,
        newHeight: Float,
        hasCurrentDrawable: Boolean
    ): Boolean {
        if (!hasDrawnContentFrame) return false
        if (!isScrollMovingLocked()) return false
        if (oldHeight <= 0f || abs(newHeight - oldHeight) <= HEIGHT_CHANGE_EPSILON_PX) return false
        if (!hasCurrentDrawable) return false
        val viewBottom = scrollOffset + max(1, height)
        if (oldTop >= viewBottom + COVERAGE_EDGE_FILL_PX) return false
        return true
    }

    private fun shouldDeferInitialDrawableSizeLocked(
        oldTop: Float,
        oldHeight: Float,
        newHeight: Float,
        hasCurrentDrawable: Boolean
    ): Boolean {
        if (hasCurrentDrawable) return false
        if (!isRecentGeometrySensitiveScrollLocked()) return false
        if (oldHeight <= 0f || abs(newHeight - oldHeight) <= HEIGHT_CHANGE_EPSILON_PX) return false
        val viewportBottom = scrollOffset + max(1, height)
        val oldBottom = oldTop + oldHeight
        if (oldBottom <= scrollOffset - COVERAGE_EDGE_FILL_PX) return true
        return oldBottom > scrollOffset + COVERAGE_EDGE_FILL_PX &&
            oldTop < viewportBottom - COVERAGE_EDGE_FILL_PX
    }

    private fun armNextBoundaryGeometryHoldLocked(anchorPage: Int) {
        rebuildLayoutLocked()
        nextBoundaryAppendInFlight = true
        nextBoundaryHoldUntilMs = SystemClock.uptimeMillis() + NEXT_BOUNDARY_GEOMETRY_HOLD_MS
        nextBoundaryHoldMinScroll = max(nextBoundaryHoldMinScroll, scrollOffset)
        nextBoundaryHoldAnchorPage = anchorPage.coerceIn(0, pages.lastIndex)
    }

    private fun isNextBoundaryGeometryHeldLocked(now: Long = SystemClock.uptimeMillis()): Boolean {
        return nextBoundaryAppendInFlight || now <= nextBoundaryHoldUntilMs
    }

    private fun effectiveMaxScrollLocked(rawMax: Float): Float {
        val nonNegativeRaw = rawMax.coerceAtLeast(0f)
        var effective = nonNegativeRaw
        val now = SystemClock.uptimeMillis()
        if (isNextBoundaryGeometryHeldLocked(now)) {
            val holdMin = nextBoundaryHoldMinScroll.coerceAtLeast(0f)
            if (holdMin <= nonNegativeRaw || drawableViewportCleanAtScrollLocked(holdMin)) {
                effective = max(effective, holdMin)
            }
        }
        return effective
    }

    private fun shouldFreezeGeometryForNextBoundaryLocked(
        index: Int,
        oldTop: Float,
        oldHeight: Float,
        newHeight: Float
    ): Boolean {
        if (!isNextBoundaryGeometryHeldLocked()) return false
        if (abs(newHeight - oldHeight) <= HEIGHT_CHANGE_EPSILON_PX) return false
        val anchor = nextBoundaryHoldAnchorPage.takeIf { it in pages.indices } ?: anchorPageLocked()
        val tailWindowStart = max(0, anchor - 2)
        val oldBottom = oldTop + oldHeight
        return index >= tailWindowStart ||
            oldBottom >= scrollOffset - height ||
            oldTop <= scrollOffset + height * 2f
    }

    private fun isScrollMovingLocked(): Boolean {
        return lastBusy || pointerDown || dragging || !scroller.isFinished || isRecentScrollSettlingLocked()
    }

    private fun clearPendingResolveLocked(page: Page) {
        page.pendingResolveType = PENDING_NONE
        page.pendingBitmap = null
        page.pendingTiles = emptyList()
        page.pendingWidth = 0
        page.pendingHeight = 0
    }

    private fun hasPendingPageResolvesLocked(): Boolean {
        for (page in pages) {
            if (page.pendingResolveType != PENDING_NONE) return true
        }
        return false
    }

    private fun isRecentScrollSettlingLocked(): Boolean {
        return lastScrollInteractionMs > 0L &&
            SystemClock.uptimeMillis() - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
    }

    private fun schedulePendingResolveRetryLocked(markLayoutDirty: Boolean = true) {
        if (markLayoutDirty) layoutDirty = true
        if (pendingResolveRetryPosted) return
        pendingResolveRetryPosted = true
        mainHandler.postDelayed({
            synchronized(stateLock) {
                pendingResolveRetryPosted = false
                if (!hasPendingPageResolvesLocked()) return@synchronized
                renderRequested = true
                scheduleFrameLocked()
                stateLock.notifyAll()
            }
        }, HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS + 32L)
    }

    private fun applyPendingPageResolvesLocked(): Boolean {
        if (pages.isEmpty()) return false
        if (isNextBoundaryGeometryHeldLocked()) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        rebuildLayoutLocked()
        val viewportAnchor = progressPositionLocked() ?: return false
        val recentScrollSettling = isRecentScrollSettlingLocked()
        if (recentScrollSettling) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        if (!shouldRestoreAnchorAfterPendingResolves(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling
            )
        ) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        var appliedCount = 0
        for (index in pages.indices) {
            if (!shouldApplyPendingPageResolveLocked(index)) continue
            if (applyPendingPageResolveLocked(index)) appliedCount++
        }
        if (appliedCount > 0) {
            structuralScrollAdjustUntilMs = max(
                structuralScrollAdjustUntilMs,
                SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
            )
            val beforeRestore = scrollOffset
            restoreViewportAnchorLocked(viewportAnchor, "pending_resolve")
            Log.d(
                TAG,
                "reader_pending_resolve_restore applied=$appliedCount anchor=${viewportAnchor.page} " +
                    "anchorOffset=${viewportAnchor.offset} from=${beforeRestore.toInt()} to=${scrollOffset.toInt()} " +
                    "lastBusy=$lastBusy pointerDown=$pointerDown dragging=$dragging " +
                    "scrollerFinished=${scroller.isFinished} recentSettling=$recentScrollSettling"
            )
        }
        return appliedCount > 0
    }

    private fun applyVisiblePendingDrawableResolvesLocked(): Boolean {
        if (pages.isEmpty()) return false
        val hasVisiblePendingDrawable = hasVisiblePendingDrawableWithoutContentLocked()
        if (isNextBoundaryGeometryHeldLocked() && !hasVisiblePendingDrawable) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        if (shouldDeferVisiblePendingDrawableResolvesLocked()) {
            schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        rebuildLayoutLocked()
        val viewportAnchor = progressPositionLocked() ?: return false
        if (isRecentScrollSettlingLocked() && !hasVisiblePendingDrawable) {
            if (hasPendingPageResolvesLocked()) schedulePendingResolveRetryLocked(markLayoutDirty = false)
            return false
        }
        var appliedCount = 0
        for (index in pages.indices) {
            val page = pages[index]
            if (page.pendingResolveType != PENDING_BITMAP && page.pendingResolveType != PENDING_TILES) continue
            if (shouldApplyPendingPageResolveLocked(index) && applyPendingPageResolveLocked(index)) {
                appliedCount++
            }
        }
        if (appliedCount <= 0) return false
        structuralScrollAdjustUntilMs = max(
            structuralScrollAdjustUntilMs,
            SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
        )
        val beforeRestore = scrollOffset
        restoreViewportAnchorLocked(viewportAnchor, "visible_pending_resolve")
        renderRequested = true
        Log.d(
            TAG,
            "reader_visible_pending_resolve_restore applied=$appliedCount anchor=${viewportAnchor.page} " +
                "anchorOffset=${viewportAnchor.offset} from=${beforeRestore.toInt()} to=${scrollOffset.toInt()}"
        )
        return true
    }

    private fun shouldDeferVisiblePendingDrawableResolvesLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        val activeScroll = lastBusy ||
            pointerDown ||
            dragging ||
            !scroller.isFinished ||
            isRecentScrollStatsActiveLocked(now)
        if (!activeScroll) return false
        if (hasVisiblePendingDrawableWithoutContentLocked()) return false
        return lastVisibleCoverageIsClean()
    }

    private fun hasVisiblePendingDrawableWithoutContentLocked(): Boolean {
        if (pages.isEmpty() || height <= 0) return false
        rebuildLayoutLocked()
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + max(1, height)
        for (index in pages.indices) {
            val page = pages[index]
            if (page.pendingResolveType != PENDING_BITMAP && page.pendingResolveType != PENDING_TILES) continue
            if (page.bitmap != null || page.tiles.isNotEmpty()) continue
            val pageTop = pageTopOrElseLocked(index, 0f)
            val pageBottom = pageTop + pageDrawHeightLocked(page)
            if (
                pageBottom > viewportTop + COVERAGE_EDGE_FILL_PX &&
                pageTop < viewportBottom - COVERAGE_EDGE_FILL_PX
            ) {
                return true
            }
        }
        return false
    }

    private fun shouldApplyPendingPageResolveLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        if (page.pendingResolveType == PENDING_NONE) return false
        val pageTop = pageTopOrElseLocked(index, 0f)
        val pageBottom = pageTop + pageDrawHeightLocked(page)
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + max(1, height)
        val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
        if (
            hasCurrentDrawable &&
            page.pendingResolveType != PENDING_BOUNDS &&
            isRecentGeometrySensitiveScrollLocked() &&
            pageTop < viewportBottom + height
        ) {
            Log.d(
                TAG,
                "reader_pending_resolve_geometry_deferred index=$index top=${pageTop.toInt()} " +
                    "bottom=${pageBottom.toInt()} scroll=${scrollOffset.toInt()} height=$height"
            )
            return false
        }
        val intersectsViewport = pageBottom > viewportTop + COVERAGE_EDGE_FILL_PX &&
            pageTop < viewportBottom - COVERAGE_EDGE_FILL_PX
        if (intersectsViewport) {
            if (
                !hasCurrentDrawable &&
                (page.pendingResolveType == PENDING_BITMAP || page.pendingResolveType == PENDING_TILES)
            ) {
                return true
            }
            Log.d(
                TAG,
                "reader_pending_resolve_visible_deferred index=$index top=${pageTop.toInt()} " +
                    "bottom=${pageBottom.toInt()} scroll=${scrollOffset.toInt()} height=$height"
            )
            return false
        }
        return true
    }

    private fun isRecentGeometrySensitiveScrollLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        return lastBusy ||
            pointerDown ||
            dragging ||
            !scroller.isFinished ||
            now <= programmaticScrollStatsUntilMs ||
            (lastScrollInteractionMs > 0L &&
                now - lastScrollInteractionMs <= ACTIVE_GEOMETRY_RESOLVE_QUIET_MS)
    }

    private fun shouldDeferOffscreenBoundsOnlyResolveLocked(oldTop: Float): Boolean {
        if (height <= 0) return false
        val viewportBottom = scrollOffset + height
        return oldTop > viewportBottom + height * OFFSCREEN_BOUNDS_ONLY_DEFER_VIEWPORTS
    }

    private fun applyPendingPageResolveLocked(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        val type = page.pendingResolveType
        if (type == PENDING_NONE) return false
        val oldHeight = pageDrawHeightLocked(page)
        val pendingWidth = page.pendingWidth
        val pendingHeight = page.pendingHeight
        val hasCurrentDrawable = page.bitmap != null || page.tiles.isNotEmpty()
        if (
            hasCurrentDrawable &&
            type != PENDING_BITMAP &&
            type != PENDING_TILES &&
            pendingWidth > 0 &&
            pendingHeight > 0
        ) {
            val pendingDrawHeight = resolvedPageDrawHeightLocked(pendingWidth, pendingHeight)
            if (abs(pendingDrawHeight - oldHeight) <= ACTIVE_DRAWABLE_BOUNDS_DELTA_SUPPRESS_PX) {
                clearPendingResolveLocked(page)
                return false
            }
        }
        when (type) {
            PENDING_SIZE -> {
                if (page.bitmap == null && page.tiles.isEmpty()) {
                    clearPendingResolveLocked(page)
                    return false
                }
            }
            PENDING_BITMAP -> {
                val bitmap = page.pendingBitmap ?: return false
                if (hasSameBitmapIdentity(page, bitmap) &&
                    page.width == max(1, pendingWidth) &&
                    page.height == max(1, pendingHeight) &&
                    !page.loading &&
                    page.cardText == null &&
                    page.errorText == null
                ) {
                    clearPendingResolveLocked(page)
                    return false
                }
                invalidateRetainedPageNodeIfBitmapChanged(index, page, bitmap)
                page.bitmap = bitmap
                page.tiles = emptyList()
            }
            PENDING_TILES -> {
                val tiles = page.pendingTiles
                if (hasSameTilesIdentity(page, tiles) &&
                    page.width == max(1, pendingWidth) &&
                    page.height == max(1, pendingHeight) &&
                    !page.loading &&
                    page.cardText == null &&
                    page.errorText == null
                ) {
                    clearPendingResolveLocked(page)
                    return false
                }
                invalidateRetainedPageNodeIfTilesChanged(index, page, tiles)
                page.bitmap = null
                page.tiles = tiles
            }
            PENDING_BOUNDS -> {
                if (page.cardText != null || page.errorText != null) {
                    clearPendingResolveLocked(page)
                    return false
                }
            }
        }
        if (type == PENDING_SIZE || type == PENDING_BOUNDS) {
            invalidatePreparedRenderSceneStateLocked()
        }
        page.width = max(1, pendingWidth)
        page.height = max(1, pendingHeight)
        page.loading = false
        page.cardText = null
        page.errorText = null
        clearPendingResolveLocked(page)
        noteResolvedPageAspectLocked(page.width, page.height)
        val newHeight = pageDrawHeightLocked(page)
        updatePageHeightDeltaLocked(index, newHeight - oldHeight)
        return true
    }

    private fun noteResolvedPageAspectLocked(pageWidth: Int, pageHeight: Int) {
        if (pageWidth <= 0 || pageHeight <= 0) return
        val ratio = (pageHeight / pageWidth.toFloat()).coerceIn(
            MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO,
            MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO
        )
        placeholderPageHeightRatio =
            placeholderPageHeightRatio * (1f - PLACEHOLDER_RATIO_LEARNING_RATE) +
                ratio * PLACEHOLDER_RATIO_LEARNING_RATE
    }

    private fun rebuildLayoutLocked() {
        if (!layoutDirty && pageTops.size == pages.size && pageTopDeltas.size == pages.size) return
        if (pageTops.size != pages.size) pageTops = FloatArrayList(pages.size)
        if (pageTopDeltas.size != pages.size) pageTopDeltas = RangeAddPointQuery(pages.size)
        var top = 0f
        for (i in pages.indices) {
            pageTops[i] = top
            top += pageDrawHeightLocked(pages[i]) + pageGapPx
        }
        contentHeight = max(0f, top - pageGapPx)
        pageTopDeltas.clear()
        layoutDirty = false
    }

    private fun appendEmptyPagesLocked(additionalCount: Int) {
        if (additionalCount <= 0) return
        materializeLayoutDeltasLocked()
        val appendedPlaceholderRatio = representativeResolvedPageRatioLocked()
        var top = if (pages.isEmpty()) 0f else contentHeight + pageGapPx
        repeat(additionalCount) {
            val page = newPageLocked(appendedPlaceholderRatio)
            pages.add(page)
            if (pageTops.size != pages.size) pageTops = pageTops.copyWithSize(pages.size)
            if (pageTopDeltas.size != pages.size) pageTopDeltas = pageTopDeltas.copyWithSize(pages.size)
            pageTops[pages.lastIndex] = top
            top += pageDrawHeightLocked(page) + pageGapPx
        }
        contentHeight = max(0f, top - pageGapPx)
        layoutDirty = false
    }

    private fun updatePageHeightDeltaLocked(index: Int, delta: Float) {
        if (abs(delta) <= 0.01f) return
        /*
         * The old incremental range-delta path is cheap, but it is fragile when several
         * NTK pages resolve size while the placeholder aspect ratio is still learning.
         * A stale delta can make later pages overlap the current page, which looks like
         * the top of the image is pinned while only the lower part scrolls.
         */
        structuralScrollAdjustUntilMs = max(
            structuralScrollAdjustUntilMs,
            SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
        )
        layoutDirty = true
    }

    private fun applyPageHeightChangeLocked(index: Int, oldTop: Float, oldHeight: Float, delta: Float) {
        if (abs(delta) <= 0.01f) return
        structuralScrollAdjustUntilMs = max(
            structuralScrollAdjustUntilMs,
            SystemClock.uptimeMillis() + RESTORE_POSITION_LOCK_MS
        )
        val oldBottom = oldTop + oldHeight
        val recentScrollSettling = isRecentScrollSettlingLocked()
        if (shouldAdjustScrollForChangedPageHeight(
                lastBusy = lastBusy,
                pointerDown = pointerDown,
                dragging = dragging,
                scrollerFinished = scroller.isFinished,
                recentScrollSettling = recentScrollSettling,
                oldBottom = oldBottom,
                scrollOffset = scrollOffset
            )
        ) {
            setScrollOffsetLocked(scrollOffset + delta)
        }
        updatePageHeightDeltaLocked(index, delta)
    }

    private fun firstVisiblePageLocked(position: Float): Int {
        if (pages.isEmpty()) return 0
        rebuildLayoutLocked()
        var low = 0
        var high = pages.lastIndex
        var result = pages.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val bottom = pageTopLocked(mid) + pageDrawHeightLocked(pages[mid]) + pageGapPx
            if (position <= bottom) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun pageTopLocked(index: Int): Float {
        return pageTops[index] + pageTopDeltas.get(index)
    }

    private fun pageTopOrElseLocked(index: Int, fallback: Float): Float {
        return if (index in 0 until pageTops.size) pageTopLocked(index) else fallback
    }

    private fun materializeLayoutDeltasLocked() {
        if (pageTopDeltas.isEmpty()) return
        for (i in 0 until pageTops.size) {
            pageTops[i] = pageTopLocked(i)
        }
        pageTopDeltas.clear()
    }

    private fun setScrollOffsetLocked(
        requested: Float,
        allowContentMaxShrinkCorrection: Boolean = false
    ) {
        var next = requested
        if (height > 0) {
            val now = SystemClock.uptimeMillis()
            if (scrollOffset > height && next <= SCROLL_OFFSET_EPSILON_PX) {
                Log.w(
                    TAG,
                    "reader_scroll_reset_trace from=${scrollOffset.toInt()} to=${next.toInt()} " +
                        "activeDirection=$activeInputDirection pointer=$pointerDown dragging=$dragging " +
                        "scroller=${!scroller.isFinished}\n${Log.getStackTraceString(Throwable())}"
                )
            }
            val lockedRestoreActive = lockedRestorePage >= 0 &&
                now <= lockedRestoreUntilMs
            if (!allowContentMaxShrinkCorrection &&
                lockedRestoreActive && lockedRestorePage in pages.indices
            ) {
                rebuildLayoutLocked()
                val restoreMin = (pageTopOrElseLocked(lockedRestorePage, 0f) - lockedRestoreOffset)
                    .coerceAtLeast(0f)
                if (scrollOffset >= restoreMin - RESTORE_POSITION_EPSILON_PX && next < restoreMin) {
                    next = restoreMin
                }
            }
            if (
                (activeInputDirection == DIRECTION_NEXT ||
                    ((pointerDown || dragging) && activeInputDirection != DIRECTION_PREVIOUS)) &&
                next < scrollOffset - SCROLL_OFFSET_EPSILON_PX &&
                (pointerDown || dragging || !scroller.isFinished) &&
                !allowContentMaxShrinkCorrection
            ) {
                if (now - lastDirectionClampLogMs >= ACTIVE_DIRECTION_CLAMP_LOG_INTERVAL_MS) {
                    lastDirectionClampLogMs = now
                    Log.d(
                        TAG,
                        "reader_active_forward_backstep_clamped from=${scrollOffset.toInt()} " +
                            "to=${next.toInt()} pointer=$pointerDown dragging=$dragging scroller=${!scroller.isFinished}"
                    )
                }
                next = scrollOffset
            } else if (
                activeInputDirection == DIRECTION_PREVIOUS &&
                next > scrollOffset + SCROLL_OFFSET_EPSILON_PX &&
                (pointerDown || dragging || !scroller.isFinished)
            ) {
                if (now - lastDirectionClampLogMs >= ACTIVE_DIRECTION_CLAMP_LOG_INTERVAL_MS) {
                    lastDirectionClampLogMs = now
                    Log.d(
                        TAG,
                        "reader_active_backward_forwardstep_clamped from=${scrollOffset.toInt()} " +
                            "to=${next.toInt()} pointer=$pointerDown dragging=$dragging scroller=${!scroller.isFinished}"
                    )
                }
                next = scrollOffset
            }
            val delta = next - scrollOffset
            val structuralAdjustActive = now <= structuralScrollAdjustUntilMs
            val programmaticScrollActive = now <= programmaticScrollStatsUntilMs
            val recentUserScrollActive = lastScrollInteractionMs > 0L &&
                now - lastScrollInteractionMs <= HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS
            val visibleForJumpCheck = isAttachedToWindow && isShown && windowVisibility == VISIBLE
            if (
                abs(delta) > 0.5f &&
                !lockedRestoreActive &&
                !structuralAdjustActive
            ) {
                lastScrollInteractionMs = now
            }
            if (
                abs(delta) >= height * SCROLL_JUMP_LOG_SCREEN_RATIO &&
                !pointerDown &&
                !dragging &&
                scroller.isFinished &&
                visibleForJumpCheck &&
                !lockedRestoreActive &&
                !structuralAdjustActive &&
                !programmaticScrollActive &&
                !recentUserScrollActive
            ) {
                Log.w(
                    TAG,
                    "reader_scroll_jump delta=${delta.toInt()} from=${scrollOffset.toInt()} to=${next.toInt()} " +
                        "anchor=${if (pages.isEmpty()) -1 else anchorPageLocked()} busy=$lastBusy lockedRestore=$lockedRestorePage"
                )
            }
        }
        val changed = abs(next - scrollOffset) > SCROLL_OFFSET_EPSILON_PX
        scrollOffset = next
        if (changed) {
            markPixelsDirtyLocked(DIRTY_SCROLL)
            if ((inlineRealPixelsOnly || forwardNativeTexturePrewarmEnabled) && pages.isNotEmpty()) {
                val currentAnchor = effectiveNativeTexturePrewarmAnchorLocked(scrollOffset)
                if (currentAnchor != nativeTexturePrewarmAnchorPage) {
                    requestResidentNativeTexturePrewarmLocked()
                }
            }
        }
    }

    private fun directionForDelta(delta: Float): Int {
        return when {
            delta > 0f -> DIRECTION_NEXT
            delta < 0f -> DIRECTION_PREVIOUS
            else -> 0
        }
    }

    private fun shouldDispatchCancelledBoundaryLocked(dragDistance: Float): Boolean {
        val direction = boundaryArmedDirection
        if (direction == 0 || pages.isEmpty() || width <= 0 || height <= 0) return false
        val minDistance = max(
            touchSlop * BOUNDARY_CANCEL_MIN_DRAG_TOUCH_SLOP_MULTIPLIER,
            height * BOUNDARY_CANCEL_MIN_DRAG_SCREEN_RATIO
        )
        if (dragDistance < minDistance) return false
        val maxScroll = maxScrollLocked()
        return when (direction) {
            DIRECTION_PREVIOUS -> scrollOffset <= BOUNDARY_EPSILON_PX
            DIRECTION_NEXT -> scrollOffset >= maxScroll - BOUNDARY_EPSILON_PX
            else -> false
        }
    }

    private fun resetDragTrackingLocked(y: Float) {
        dragOriginY = y
        dragOriginScrollOffset = scrollOffset
    }

    /**
     * Applies the authoritative physical finger position directly. Rendering may coalesce multiple
     * MOVE events into the newest position, but it must never synthesize motion between them.
     */
    private fun applyPhysicalDragPositionLocked(y: Float, eventTimeNs: Long): Boolean {
        val requestedOffset = dragOriginScrollOffset +
            (dragOriginY - y) * DRAG_SCROLL_MULTIPLIER
        val direction = directionForDelta(requestedOffset - scrollOffset)
        if (direction == 0 || isAtInputEdgeLocked(direction)) return false
        clearLockedRestorePositionLocked()
        if (!dragging) dragging = true
        setBusyLocked(true)
        boundaryArmedDirection = direction
        activeInputDirection = direction
        lastScrollInteractionMs = (eventTimeNs / NANOS_PER_MILLISECOND)
            .coerceAtLeast(SystemClock.uptimeMillis() - 1L)
        if (dragTargetRevision == Long.MAX_VALUE) {
            dragTargetRevision = 1L
            directCallbackObservedDragTargetRevision = 0L
        } else {
            dragTargetRevision++
        }
        return applyDragOffsetLocked(requestedOffset)
    }

    private fun applyDragOffsetLocked(requestedOffset: Float): Boolean {
        val direction = directionForDelta(requestedOffset - scrollOffset)
        if (direction == 0) return false
        boundaryArmedDirection = direction
        activeInputDirection = direction
        lastScrollInteractionMs = SystemClock.uptimeMillis()
        val before = scrollOffset
        val boundedNext = capForwardInputScrollLocked(requestedOffset, direction)
        setScrollOffsetLocked(boundedNext)
        if (!(limitScrollToDrawablePrefix && direction == DIRECTION_NEXT)) {
            clampForwardScrollLocked()
        }
        val moved = abs(scrollOffset - before) > SCROLL_OFFSET_EPSILON_PX
        if (moved) {
            edgeNoMovementStatsSuppressedUntilMs = 0L
            beginPhysicalScrollTraceLocked()
        }
        return moved
    }

    private fun suppressEdgeNoMovementScrollStatsLocked(nowMs: Long): Boolean {
        if (pages.isEmpty()) return false
        rebuildLayoutLocked()
        if (!isAtInputEdgeLocked(activeInputDirection)) return false
        edgeNoMovementStatsSuppressedUntilMs = max(
            edgeNoMovementStatsSuppressedUntilMs,
            nowMs + PROGRAMMATIC_SCROLL_STATS_RECENT_MS
        )
        programmaticScrollStatsUntilMs = min(programmaticScrollStatsUntilMs, nowMs)
        mainHandler.removeCallbacks(frameStatsFinalizeRunnable)
        // Reaching the edge ends pixel movement, but it must not erase the real frames that
        // brought the viewport here. Preserve the completed segment for the telemetry drain.
        if (statsActive) finalizeActiveFrameStatsLocked(log = false)
        return true
    }

    private fun isAtInputEdgeLocked(direction: Int): Boolean {
        if (pages.isEmpty()) return false
        return when (direction) {
            DIRECTION_NEXT -> scrollOffset >= effectiveMaxScrollLocked(maxScrollLocked()) - BOUNDARY_EPSILON_PX
            DIRECTION_PREVIOUS -> scrollOffset <= BOUNDARY_EPSILON_PX
            else -> false
        }
    }

    private fun isNearVisibleLocked(index: Int, extraPages: Int): Boolean {
        if (pages.isEmpty()) return false
        val anchor = anchorPageLocked()
        return index in (anchor - extraPages)..(anchor + extraPages)
    }

    private fun noteInputLocked(event: MotionEvent) {
        statsAwaitingFirstInput = false
        lastScrollInteractionMs = max(lastScrollInteractionMs, SystemClock.uptimeMillis())
        fun addInputTime(uptimeMs: Long) {
            val eventNs = uptimeMsToNanoTime(uptimeMs)
            if (pendingOldestInputNs == 0L || eventNs < pendingOldestInputNs) pendingOldestInputNs = eventNs
            if (eventNs > pendingNewestInputNs) pendingNewestInputNs = eventNs
        }
        for (i in 0 until event.historySize) addInputTime(event.getHistoricalEventTime(i))
        addInputTime(event.eventTime)
        pendingInputEvents++
        pendingHistorySamples += event.historySize
    }

    private fun uptimeMsToNanoTime(uptimeMs: Long): Long {
        val nowNs = System.nanoTime()
        val nowUptimeNs = SystemClock.uptimeMillis() * 1_000_000L
        return nowNs - (nowUptimeNs - uptimeMs * 1_000_000L)
    }

    private fun consumePendingInputLocked(): PendingInput? {
        if (pendingInputEvents <= 0 || pendingOldestInputNs <= 0L) return null
        val input = PendingInput(
            oldestNs = pendingOldestInputNs,
            newestNs = pendingNewestInputNs,
            events = pendingInputEvents,
            history = pendingHistorySamples
        )
        pendingOldestInputNs = 0L
        pendingNewestInputNs = 0L
        pendingInputEvents = 0
        pendingHistorySamples = 0
        return input
    }

    private fun recordFrameStats(timing: DrawTiming, active: Boolean) {
        val inputState = synchronized(stateLock) {
            val input = consumePendingInputLocked()
            if (input != null) statsAwaitingFirstInput = false
            input to statsAwaitingFirstInput
        }
        val pendingInput = inputState.first
        val awaitingFirstInput = inputState.second
        if (awaitingFirstInput && pendingInput == null) return
        val coverage = synchronized(stateLock) { lastVisibleCoverageSnapshot }
        if (active) {
            mainHandler.removeCallbacks(frameStatsFinalizeRunnable)
            mainHandler.postDelayed(frameStatsFinalizeRunnable, PROGRAMMATIC_SCROLL_STATS_FINALIZE_MS)
            if (!statsActive) {
                statsActive = true
                clearStatsSamples()
                beginActiveScrollTrace()
            }
            if (timing.posted) {
                if (statsLastCallbackStartNs > 0L &&
                    timing.callbackStartNs >= statsLastCallbackStartNs
                ) {
                    statsCallbackSpacingMs.add(
                        nsToMs(timing.callbackStartNs - statsLastCallbackStartNs)
                    )
                }
                if (statsLastPostEndNs > 0L && timing.postEndNs >= statsLastPostEndNs) {
                    statsPostSpacingMs.add(nsToMs(timing.postEndNs - statsLastPostEndNs))
                }
                statsLastCallbackStartNs = timing.callbackStartNs
                statsLastPostEndNs = timing.postEndNs
                statsLockWaitMs.add(timing.lockWaitMs)
                statsDrawMs.add(timing.drawMs)
                statsPostMs.add(timing.postMs)
                statsTotalMs.add(timing.totalMs)
                pendingInput?.let {
                    statsInputOldestMs.add(nsToMs(timing.postEndNs - it.oldestNs))
                    statsInputNewestMs.add(nsToMs(timing.postEndNs - it.newestNs))
                }
                recordPixelMutationFrameStats(timing, coverage)
            } else {
                statsNoCanvasFrames++
            }
            recordFrameCoverageStats(coverage)
            logActiveFrameStatsIfReady()
            return
        }
        if (!statsActive) return
        mainHandler.removeCallbacks(frameStatsFinalizeRunnable)
        val snapshot = synchronized(stateLock) {
            finalizeActiveFrameStatsLocked(log = true)
        } ?: return
        logFrameStatsSnapshot(snapshot)
    }

    private fun recordPixelMutationFrameStats(
        timing: DrawTiming,
        coverage: VisibleCoverageSnapshot?
    ) {
        if (timing.mutationWatermark <= 0L) return
        statsMutationOldestToCallbackMs.add(timing.mutationOldestToCallbackMs)
        statsMutationNewestToCallbackMs.add(timing.mutationNewestToCallbackMs)
        statsMutationOldestToPostMs.add(timing.mutationOldestToPostMs)
        statsMutationNewestToPostMs.add(timing.mutationNewestToPostMs)

        val callbackOverBudget = isCausalPixelMutationOverBudget(
            timing.mutationOldestToCallbackMs
        )
        val postOverBudget = isCausalPixelMutationOverBudget(timing.mutationOldestToPostMs)
        if (!callbackOverBudget && !postOverBudget) return
        val phase = if (callbackOverBudget) "callback" else "post"
        val ageMs = if (callbackOverBudget) {
            timing.mutationOldestToCallbackMs
        } else {
            timing.mutationOldestToPostMs
        }
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastPixelMutationGapLogMs < PIXEL_MUTATION_GAP_LOG_INTERVAL_MS) {
            // Every interval remains in the aggregate arrays and therefore in the terminal
            // p95/max/missed-frame evidence. Avoid synchronously formatting and writing a large
            // log line for each animation frame: logcat itself otherwise becomes scroll work.
            suppressedPixelMutationGapLogs++
            return
        }
        val suppressed = suppressedPixelMutationGapLogs
        suppressedPixelMutationGapLogs = 0L
        lastPixelMutationGapLogMs = nowMs
        Log.d(
            TAG,
            "reader_scroll_callback_gap ms=${fmt(ageMs)} cause=pixel_mutation phase=$phase " +
                "watermark=${timing.mutationWatermark} " +
                "oldestCallbackMs=${fmt(timing.mutationOldestToCallbackMs)} " +
                "newestCallbackMs=${fmt(timing.mutationNewestToCallbackMs)} " +
                "oldestPostMs=${fmt(timing.mutationOldestToPostMs)} " +
                "newestPostMs=${fmt(timing.mutationNewestToPostMs)} " +
                "invalidationCallbackMs=${fmt(timing.invalidationToCallbackMs)} " +
                "invalidationPostMs=${fmt(timing.invalidationToPostMs)} " +
                "suppressedSinceLast=$suppressed " +
                "coverage=${formatCoverageForGap(coverage)}"
        )
        MainThreadStallMonitor.warn("reader_pixel_mutation_$phase", ageMs)
    }

    private fun logActiveFrameStatsIfReady() {
        val enoughSamples = statsCallbackSpacingMs.size >= MIN_FRAME_SAMPLES ||
            statsPostSpacingMs.size >= MIN_FRAME_SAMPLES ||
            statsTotalMs.size >= MIN_FRAME_SAMPLES
        if (!statsActive || !enoughSamples) return
        val nowMs = SystemClock.uptimeMillis()
        if (isRecentScrollStatsActiveLocked(nowMs)) return
        mainHandler.removeCallbacks(frameStatsFinalizeRunnable)
        val snapshot = synchronized(stateLock) {
            finalizeActiveFrameStatsLocked(log = true)
        } ?: return
        logFrameStatsSnapshot(snapshot)
    }

    private fun finalizeActiveFrameStatsLocked(log: Boolean): FrameStatsSnapshot? {
        if (!statsActive) return lastFrameStatsSnapshot
        val callbackIntervals = ArrayList(statsCallbackSpacingMs)
        val postIntervals = ArrayList(statsPostSpacingMs)
        val lockWait = ArrayList(statsLockWaitMs)
        val draw = ArrayList(statsDrawMs)
        val post = ArrayList(statsPostMs)
        val total = ArrayList(statsTotalMs)
        val renderTotal = ArrayList(statsDrawMs)
        val inputOldest = ArrayList(statsInputOldestMs)
        val inputNewest = ArrayList(statsInputNewestMs)
        val mutationOldestToCallback = ArrayList(statsMutationOldestToCallbackMs)
        val mutationNewestToCallback = ArrayList(statsMutationNewestToCallbackMs)
        val mutationOldestToPost = ArrayList(statsMutationOldestToPostMs)
        val mutationNewestToPost = ArrayList(statsMutationNewestToPostMs)
        val noCanvas = statsNoCanvasFrames
        val coalesced = statsCoalescedRequests
        val maxMissingPx = statsMaxMissingPx
        val maxPlaceholderPx = statsMaxPlaceholderPx
        val maxVisibleLoading = statsMaxVisibleLoading
        statsActive = false
        endActiveScrollTrace()
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        statsLastScrollOffset = Float.NaN
        statsMotionStarted = false
        clearStatsSamples()
        if (callbackIntervals.isEmpty() && postIntervals.isEmpty() && total.isEmpty()) return null
        callbackIntervals.sort()
        postIntervals.sort()
        lockWait.sort()
        draw.sort()
        post.sort()
        total.sort()
        renderTotal.sort()
        inputOldest.sort()
        inputNewest.sort()
        mutationOldestToCallback.sort()
        mutationNewestToCallback.sort()
        mutationOldestToPost.sort()
        mutationNewestToPost.sort()
        val nominalBudget = frameBudgetMs()
        val measuredBudget = if (callbackIntervals.size >= MIN_FRAME_SAMPLES) {
            percentile(callbackIntervals, 0.50f).coerceIn(nominalBudget * 0.90f, nominalBudget * 1.25f)
        } else {
            nominalBudget
        }
        val schedulerIntervals = callbackIntervals
        val sampleCount = max(max(schedulerIntervals.size, postIntervals.size), total.size)
        val renderP95ForBudget = percentile(renderTotal, 0.95f)
        val strictOverBudget = if (renderP95ForBudget > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS) {
            renderTotal.count { it > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS }
        } else {
            0
        }
        val missedThreshold = measuredBudget * MISSED_VSYNC_FACTOR
        val missedIntervals = schedulerIntervals.count { it > missedThreshold }
        var missedFrames = 0
        for (interval in schedulerIntervals) {
            if (interval > missedThreshold) {
                missedFrames += max(1, kotlin.math.floor(interval / measuredBudget - 1f).toInt())
            }
        }
        val droppedFrames = if (renderP95ForBudget > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS) {
            renderTotal.count { it > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS }
        } else {
            0
        }
        var droppedFrameDebt = 0
        if (renderP95ForBudget > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS) {
            for (duration in renderTotal) droppedFrameDebt += max(0, kotlin.math.floor(duration / SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS).toInt())
        }
        val strictPercent = if (renderTotal.isEmpty()) 0f else strictOverBudget * 100f / renderTotal.size
        val missedPercent = if (schedulerIntervals.isEmpty()) 0f else missedIntervals * 100f / schedulerIntervals.size
        val droppedPercent = if (total.isEmpty()) 0f else droppedFrames * 100f / total.size
        val callbackP95 = percentile(callbackIntervals, 0.95f)
        val callbackMax = maxOrZero(callbackIntervals)
        val prepP95 = percentile(lockWait, 0.95f)
        val prepMax = maxOrZero(lockWait)
        val drawP95 = percentile(draw, 0.95f)
        val drawMax = maxOrZero(draw)
        val totalP95 = percentile(renderTotal, 0.95f)
        val totalMax = maxOrZero(renderTotal)
        val inputOldestP95 = percentile(inputOldest, 0.95f)
        val inputOldestMax = maxOrZero(inputOldest)
        val inputNewestP95 = percentile(inputNewest, 0.95f)
        val inputNewestMax = maxOrZero(inputNewest)
        val mutationCallbackOverBudget = mutationOldestToCallback.count {
            isCausalPixelMutationOverBudget(it)
        }
        val mutationCallbackMax = maxOrZero(mutationOldestToCallback)
        val mutationPostOverBudget = mutationOldestToPost.count {
            isCausalPixelMutationOverBudget(it)
        }
        val mutationPostMax = maxOrZero(mutationOldestToPost)
        val mutationNewestCallbackMax = maxOrZero(mutationNewestToCallback)
        val mutationNewestPostMax = maxOrZero(mutationNewestToPost)
        val snapshot = FrameStatsSnapshot(
            samples = sampleCount,
            strictOverBudget = strictOverBudget,
            missedIntervals = missedIntervals,
            missedFrames = missedFrames,
            droppedFrames = droppedFrames,
            droppedFrameDebt = droppedFrameDebt,
            callbackP95 = callbackP95,
            callbackMax = callbackMax,
            prepP95 = prepP95,
            prepMax = prepMax,
            drawP95 = drawP95,
            drawMax = drawMax,
            totalP95 = totalP95,
            totalMax = totalMax,
            maxMissingPx = maxMissingPx,
            maxPlaceholderPx = maxPlaceholderPx,
            maxVisibleLoading = maxVisibleLoading,
            noCanvas = noCanvas,
            coalesced = coalesced,
            inputFrames = inputNewest.size,
            inputOldestToPostP95 = inputOldestP95,
            inputOldestToPostMax = inputOldestMax,
            inputNewestToPostP95 = inputNewestP95,
            inputNewestToPostMax = inputNewestMax,
            mutationFrames = mutationOldestToCallback.size,
            mutationCallbackOverBudget = mutationCallbackOverBudget,
            mutationCallbackMax = mutationCallbackMax,
            mutationPostOverBudget = mutationPostOverBudget,
            mutationPostMax = mutationPostMax,
            mutationNewestCallbackMax = mutationNewestCallbackMax,
            mutationNewestPostMax = mutationNewestPostMax
        )
        lastFrameStatsSnapshot = snapshot
        if (log) return snapshot
        return snapshot
    }

    private fun logFrameStatsSnapshot(snapshot: FrameStatsSnapshot) {
        val nominalBudget = frameBudgetMs()
        val strictPercent = if (snapshot.samples == 0) 0f else snapshot.strictOverBudget * 100f / snapshot.samples
        val missedPercent = if (snapshot.samples == 0) 0f else snapshot.missedIntervals * 100f / snapshot.samples
        val droppedPercent = if (snapshot.samples == 0) 0f else snapshot.droppedFrames * 100f / snapshot.samples
        val renderDropped = snapshot.drawP95 > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS ||
            snapshot.totalP95 > SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS
        Log.i(
            TAG,
            "${if (renderDropped) "surface_jank_v3" else "surface_frame_stats"} " +
                "samples=${snapshot.samples} nominalBudget=${fmt(nominalBudget)} measuredBudget=${fmt(nominalBudget)} " +
                "strictOverBudget=${snapshot.strictOverBudget} strictPct=${fmt(strictPercent)} " +
                "missedIntervals=${snapshot.missedIntervals} missedFrames=${snapshot.missedFrames} missedPct=${fmt(missedPercent)} " +
                "droppedFrames=${snapshot.droppedFrames} droppedFrameDebt=${snapshot.droppedFrameDebt} droppedPct=${fmt(droppedPercent)} " +
                "callbackP95=${fmt(snapshot.callbackP95)} callbackMax=${fmt(snapshot.callbackMax)} " +
                "prepP95=${fmt(snapshot.prepP95)} prepMax=${fmt(snapshot.prepMax)} " +
                "drawP95=${fmt(snapshot.drawP95)} drawMax=${fmt(snapshot.drawMax)} " +
                "totalP95=${fmt(snapshot.totalP95)} totalMax=${fmt(snapshot.totalMax)} " +
                "inputFrames=${snapshot.inputFrames} " +
                "inputOldestToPostP95=${fmt(snapshot.inputOldestToPostP95)} " +
                "inputOldestToPostMax=${fmt(snapshot.inputOldestToPostMax)} " +
                "inputNewestToPostP95=${fmt(snapshot.inputNewestToPostP95)} " +
                "inputNewestToPostMax=${fmt(snapshot.inputNewestToPostMax)} " +
                "mutationFrames=${snapshot.mutationFrames} " +
                "mutationCallbackOverBudget=${snapshot.mutationCallbackOverBudget} " +
                "mutationCallbackMax=${fmt(snapshot.mutationCallbackMax)} " +
                "mutationPostOverBudget=${snapshot.mutationPostOverBudget} " +
                "mutationPostMax=${fmt(snapshot.mutationPostMax)} " +
                "mutationNewestCallbackMax=${fmt(snapshot.mutationNewestCallbackMax)} " +
                "mutationNewestPostMax=${fmt(snapshot.mutationNewestPostMax)} " +
                "maxMissingPx=${snapshot.maxMissingPx} maxPlaceholderPx=${snapshot.maxPlaceholderPx} " +
                "maxVisibleLoading=${snapshot.maxVisibleLoading} " +
                "noCanvas=${snapshot.noCanvas} coalesced=${snapshot.coalesced} " +
                "surfaceEpoch=${snapshot.surfaceEpoch} frameId=${snapshot.frameId} " +
                "latchProofState=${snapshot.latchProofState} " +
                "logicalUnlatchedSubmissions=${snapshot.logicalUnlatchedSubmissions} " +
                "maxLogicalUnlatchedSubmissions=${snapshot.maxLogicalUnlatchedSubmissions} " +
                "oldestUnlatchedAgeNanos=${snapshot.oldestUnlatchedAgeNanos} " +
                "latchQueryError=${snapshot.latchQueryError} " +
                "latchEvidenceDeadlineNanos=${snapshot.latchEvidenceDeadlineNanos} " +
                "cadenceQualificationFailed=${snapshot.cadenceQualificationFailed} " +
                "causalTimestampIdentityOrOrderInvalidFrames=${snapshot.causalTimestampIdentityOrOrderInvalidFrames} " +
                "causalLatchHorizonViolationFrames=${snapshot.causalLatchHorizonViolationFrames} " +
                "latchLostFrames=${snapshot.latchLostFrames} " +
                "latchInvalidStateFrames=${snapshot.latchInvalidStateFrames} " +
                "latchQueryErrorFrames=${snapshot.latchQueryErrorFrames} " +
                "functionalSubmissionSamples=${snapshot.functionalSubmissionSamples} " +
                "functionalSubmissionP95=${fmt(snapshot.functionalSubmissionP95)} " +
                "functionalSubmissionP99=${fmt(snapshot.functionalSubmissionP99)} " +
                "functionalSubmissionMax=${fmt(snapshot.functionalSubmissionMax)} " +
                "functionalSubmissionMissedFrames=${snapshot.functionalSubmissionMissedFrames} " +
                "functionalSubmissionDroppedFrames=${snapshot.functionalSubmissionDroppedFrames} " +
                "functionalSubmissionPauseFrames=${snapshot.functionalSubmissionPauseFrames} " +
                "functionalSubmissionMaxOverBudgetStreak=${snapshot.functionalSubmissionMaxOverBudgetStreak} " +
                "functionalSubmissionEligiblePairs=${snapshot.functionalSubmissionEligiblePairs} " +
                "functionalSubmissionInvalidPairs=${snapshot.functionalSubmissionInvalidPairs} " +
                "functionalInputGestures=${snapshot.functionalInputGestures} " +
                "functionalGesturesWithValidPair=${snapshot.functionalGesturesWithValidPair} " +
                "functionalDrawToSwapReturnDiagnosticSamples=${snapshot.functionalCpuSubmitWorkSamples} " +
                "functionalDrawToSwapReturnDiagnosticEligibleFrames=${snapshot.functionalCpuSubmitWorkEligibleFrames} " +
                "functionalDrawToSwapReturnDiagnosticInvalidFrames=${snapshot.functionalCpuSubmitWorkInvalidFrames} " +
                "functionalDrawToSwapReturnDiagnosticP95=${fmt(snapshot.functionalCpuSubmitWorkP95)} " +
                "functionalDrawToSwapReturnDiagnosticMax=${fmt(snapshot.functionalCpuSubmitWorkMax)} " +
                "functionalDrawIssueSamples=${snapshot.functionalDrawIssueSamples} " +
                "functionalDrawIssueEligibleFrames=${snapshot.functionalDrawIssueEligibleFrames} " +
                "functionalDrawIssueInvalidFrames=${snapshot.functionalDrawIssueInvalidFrames} " +
                "functionalDrawIssueP95=${fmt(snapshot.functionalDrawIssueP95)} " +
                "functionalDrawIssueMax=${fmt(snapshot.functionalDrawIssueMax)} " +
                "functionalSwapCallSamples=${snapshot.functionalSwapCallSamples} " +
                "functionalSwapCallEligibleFrames=${snapshot.functionalSwapCallEligibleFrames} " +
                "functionalSwapCallInvalidFrames=${snapshot.functionalSwapCallInvalidFrames} " +
                "functionalSwapCallP95=${fmt(snapshot.functionalSwapCallP95)} " +
                "functionalSwapCallP99=${fmt(snapshot.functionalSwapCallP99)} " +
                "functionalSwapCallMax=${fmt(snapshot.functionalSwapCallMax)} " +
                "functionalSwapCallPauseFrames=${snapshot.functionalSwapCallPauseFrames} " +
                "functionalNextWorkStartDelayMax=${fmt(snapshot.functionalNextWorkStartDelayMax)} " +
                "functionalBackendPreparationP95=${fmt(snapshot.functionalBackendPreparationP95)} " +
                "functionalBackendPreparationMax=${fmt(snapshot.functionalBackendPreparationMax)} " +
                "functionalResidualPriorTargetGateP95=${fmt(snapshot.functionalResidualPriorTargetGateP95)} " +
                "functionalResidualPriorTargetGateMax=${fmt(snapshot.functionalResidualPriorTargetGateMax)} " +
                "functionalPhaseAdmissionAfterBothReadyP95=${fmt(snapshot.functionalPhaseAdmissionAfterBothReadyP95)} " +
                "functionalPhaseAdmissionAfterBothReadyMax=${fmt(snapshot.functionalPhaseAdmissionAfterBothReadyMax)} " +
                "functionalPreparationOverlapSamples=${snapshot.functionalPreparationOverlapSamples} " +
                "functionalPreparationOverlapP95=${fmt(snapshot.functionalPreparationOverlapP95)} " +
                "functionalPreparationOverlapMax=${fmt(snapshot.functionalPreparationOverlapMax)} " +
                "functionalTargetRetirementSamples=${snapshot.functionalTargetRetirementSamples} " +
                "functionalTargetRetirementP95=${fmt(snapshot.functionalTargetRetirementP95)} " +
                "functionalTargetRetirementMax=${fmt(snapshot.functionalTargetRetirementMax)} " +
                "functionalRendererReadyToQueueSamples=${snapshot.functionalRendererReadyToQueueSamples} " +
                "functionalRendererReadyToQueueEligiblePairs=${snapshot.functionalRendererReadyToQueueEligiblePairs} " +
                "functionalRendererReadyToQueueInvalidPairs=${snapshot.functionalRendererReadyToQueueInvalidPairs} " +
                "functionalRendererReadyToQueueP95=${fmt(snapshot.functionalRendererReadyToQueueP95)} " +
                "functionalRendererReadyToQueueMax=${fmt(snapshot.functionalRendererReadyToQueueMax)} " +
                "functionalRendererReadyToQueueMissedFrames=${snapshot.functionalRendererReadyToQueueMissedFrames} " +
                "functionalRendererReadyToQueueDroppedFrames=${snapshot.functionalRendererReadyToQueueDroppedFrames} " +
                "functionalPhaseDecompositionInvalidPairs=${snapshot.functionalPhaseDecompositionInvalidPairs} " +
                "functionalGpuInvariantFailedFrames=${snapshot.functionalGpuInvariantFailedFrames}"
        )
    }

    private fun recordFrameCoverageStats(coverage: VisibleCoverageSnapshot?) {
        if (coverage == null) return
        statsMaxMissingPx = max(statsMaxMissingPx, coverage.missingPx)
        statsMaxPlaceholderPx = max(statsMaxPlaceholderPx, coverage.placeholderPx)
        statsMaxVisibleLoading = max(statsMaxVisibleLoading, coverage.visibleLoading)
    }

    private fun formatCoverageForGap(coverage: VisibleCoverageSnapshot?): String {
        if (coverage == null) return "null"
        return "drawable=${coverage.drawablePx},missing=${coverage.missingPx}," +
            "placeholder=${coverage.placeholderPx},loading=${coverage.visibleLoading}," +
            "items=${coverage.drawableItems}/${coverage.totalItems},busy=${coverage.busy}"
    }

    private fun clearStatsSamples() {
        statsCallbackSpacingMs.clear()
        statsPostSpacingMs.clear()
        statsLockWaitMs.clear()
        statsDrawMs.clear()
        statsPostMs.clear()
        statsTotalMs.clear()
        statsInputOldestMs.clear()
        statsInputNewestMs.clear()
        statsMutationOldestToCallbackMs.clear()
        statsMutationNewestToCallbackMs.clear()
        statsMutationOldestToPostMs.clear()
        statsMutationNewestToPostMs.clear()
        statsNoCanvasFrames = 0
        statsCoalescedRequests = 0
        statsMaxMissingPx = 0
        statsMaxPlaceholderPx = 0
        statsMaxVisibleLoading = 0
    }

    private fun resetActiveFrameStatsLocked() {
        statsActive = false
        endActiveScrollTrace()
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        statsLastScrollOffset = Float.NaN
        statsMotionStarted = false
        clearStatsSamples()
    }

    private fun beginActiveScrollTrace() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || activeScrollTraceCookie != 0) return
        val cookie = nextActiveScrollTraceCookie++.also {
            if (nextActiveScrollTraceCookie <= 0) nextActiveScrollTraceCookie = 1
        }
        activeScrollTraceCookie = cookie
        Trace.setCounter(
            ACTIVE_SCROLL_REFRESH_PERIOD_COUNTER,
            (frameBudgetMs() * 1_000_000f).toLong().coerceAtLeast(1L)
        )
        Trace.beginAsyncSection(ACTIVE_SCROLL_TRACE_NAME, cookie)
    }

    private fun endActiveScrollTrace() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cookie = activeScrollTraceCookie
        if (cookie == 0) return
        activeScrollTraceCookie = 0
        Trace.endAsyncSection(ACTIVE_SCROLL_TRACE_NAME, cookie)
    }

    private fun beginPhysicalScrollTraceLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Every caller reaches this method only after a real scrollOffset mutation. Refresh the
        // gesture-owned watermark even when an earlier moving segment is still open. Image/body
        // installs after this point may advance desiredVersion, but are not physical motion.
        physicalMotionRequiredVersion = desiredVersion
        if (physicalScrollTraceCookie != 0) return
        if (BenchmarkAdjacentCommitSignal.isEnabled()) {
            mainHandler.removeCallbacks(benchmarkPhysicalMotionIdleRunnable)
        }
        // Each interval starts with actual motion. Never bridge a completed motion segment and a
        // later MOVE across a stationary finger hold in the native cadence statistics.
        statsLastCallbackStartNs = 0L
        statsLastPostEndNs = 0L
        val cookie = nextPhysicalScrollTraceCookie++.also {
            if (nextPhysicalScrollTraceCookie <= 0) nextPhysicalScrollTraceCookie = 1
        }
        physicalScrollTraceCookie = cookie
        Trace.setCounter(
            PHYSICAL_SCROLL_REFRESH_PERIOD_COUNTER,
            (frameBudgetMs() * NANOS_PER_MILLISECOND.toFloat()).toLong().coerceAtLeast(1L)
        )
        Trace.beginAsyncSection(PHYSICAL_SCROLL_TRACE_NAME, cookie)
    }

    private fun scheduleBenchmarkPhysicalMotionIdleCheckLocked(
        completedPhysicalVersion: Long = 0L
    ) {
        if (!BenchmarkAdjacentCommitSignal.isEnabled()) return
        // A later trace close replaces the prior value with the final fling viewport. An UP with
        // no live trace deliberately preserves that physical watermark (or the already-committed
        // baseline for a zero-pixel edge gesture). Never recapture desiredVersion here: adjacent
        // image installation is content work and can advance it after the finger has stopped.
        benchmarkPhysicalMotionIdleRequiredVersion =
            benchmarkPhysicalMotionIdleRequiredVersion(
                completedPhysicalVersion = completedPhysicalVersion,
                existingRequiredVersion = benchmarkPhysicalMotionIdleRequiredVersion,
                committedVersion = committedVersion,
            )
        mainHandler.removeCallbacks(benchmarkPhysicalMotionIdleRunnable)
        mainHandler.postDelayed(
            benchmarkPhysicalMotionIdleRunnable,
            BENCHMARK_PHYSICAL_MOTION_IDLE_CONFIRM_MS,
        )
    }

    private fun endPhysicalScrollTraceLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cookie = physicalScrollTraceCookie
        val completedPhysicalVersion = physicalMotionRequiredVersion
        if (cookie != 0) {
            physicalScrollTraceCookie = 0
            Trace.endAsyncSection(PHYSICAL_SCROLL_TRACE_NAME, cookie)
        }
        physicalMotionRequiredVersion = 0L
        if (BenchmarkAdjacentCommitSignal.isEnabled()) {
            // A final edge gesture can be physically valid but move zero pixels. Its preceding
            // trace may have closed while the finger was still down; arm the confirmation again
            // on UP even when there is no live trace cookie, otherwise the pointer-down sample
            // consumes the only pending callback and the harness never observes final idle.
            scheduleBenchmarkPhysicalMotionIdleCheckLocked(completedPhysicalVersion)
        }
    }

    private fun percentile(sorted: List<Float>, percentile: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun maxOrZero(sorted: List<Float>): Float = if (sorted.isEmpty()) 0f else sorted.last()

    private fun nsToMs(ns: Long): Float = ns / 1_000_000f

    /**
     * Refreshes display timing only from lifecycle/display callbacks. Reading Display.refreshRate
     * performs a synchronous IDisplayManager transaction on Android; doing that from ACTION_MOVE
     * made a single display-service lock stall consume an otherwise healthy 60 Hz frame.
     */
    private fun refreshCachedDisplayTiming(): Float {
        val refreshRate = NtkDisplayTimingPolicy.normalizedRefreshRate(display?.refreshRate)
        cachedDisplayRefreshRate = refreshRate
        return refreshRate
    }

    private fun frameBudgetMs(): Float =
        NtkDisplayTimingPolicy.frameBudgetMs(cachedDisplayRefreshRate)

    private fun directCadenceWatchdogDelayMs(): Long {
        return NtkDirectCadenceWatchdogPolicy.delayMs(
            frameBudgetMs = frameBudgetMs(),
            emulatorRuntime = emulatorNativeSurfaceRuntime,
        )
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

    private class FloatArrayList(size: Int) {
        private var values = FloatArray(max(1, size))
        var size: Int = size
            private set

        operator fun get(index: Int): Float = values[index]

        operator fun set(index: Int, value: Float) {
            if (index >= values.size) values = values.copyOf(max(index + 1, values.size * 2))
            values[index] = value
            if (index >= size) size = index + 1
        }

        fun copyWithSize(nextSize: Int): FloatArrayList {
            val copy = FloatArrayList(0)
            copy.values = values.copyOf(max(1, nextSize))
            copy.size = nextSize
            return copy
        }

        fun getOrElse(index: Int, fallback: Float): Float {
            return if (index in 0 until size) values[index] else fallback
        }
    }

    private class RangeAddPointQuery(size: Int) {
        private var tree = FloatArray(max(2, size + 2))
        var size: Int = size
            private set
        private var dirty = false

        fun add(startIndex: Int, delta: Float) {
            if (startIndex >= size || abs(delta) <= 0.01f) return
            addInternal(startIndex + 1, delta)
            dirty = true
        }

        fun get(index: Int): Float {
            if (index !in 0 until size) return 0f
            var i = index + 1
            var sum = 0f
            while (i > 0) {
                sum += tree[i]
                i -= i and -i
            }
            return sum
        }

        fun clear() {
            if (!dirty) return
            java.util.Arrays.fill(tree, 0f)
            dirty = false
        }

        fun isEmpty(): Boolean = !dirty

        fun copyWithSize(nextSize: Int): RangeAddPointQuery {
            val copy = RangeAddPointQuery(nextSize)
            val limit = min(size, nextSize)
            for (i in 0 until limit) {
                val value = get(i)
                if (abs(value) > 0.01f) copy.add(i, value)
            }
            return copy
        }

        private fun addInternal(index: Int, delta: Float) {
            var i = index
            while (i < tree.size) {
                tree[i] += delta
                i += i and -i
            }
        }
    }

    companion object {
        private const val TEST_FORCE_HWUI_SYSTEM_PROPERTY =
            "mangaview.reader.force_hwui_for_test"
        const val DIRECTION_PREVIOUS = -1
        const val DIRECTION_NEXT = 1
        private const val ROLLING_AUTHORITATIVE_RECYCLE_DELAY_MS = 250L

        @JvmStatic
        fun transitionCardPageHeightForTest(): Float = TRANSITION_CARD_PAGE_HEIGHT_PX

        @JvmStatic
        fun shouldStartFlingForTest(dragDistance: Float, velocityY: Int, minVelocity: Int, touchSlop: Int): Boolean {
            return shouldStartFling(dragDistance, velocityY, minVelocity, touchSlop)
        }

        @JvmStatic
        fun isTapGestureForTest(released: Boolean, deltaX: Float, deltaY: Float, touchSlop: Int): Boolean {
            return isTapGesture(released, deltaX, deltaY, touchSlop)
        }

        @JvmStatic
        fun authoritativeOriginalProofMetadataAcceptedForTest(
            pageWidth: Int,
            pageHeight: Int,
            proof: ReaderPreparedStore.PreparedOriginalProof?
        ): Boolean {
            return authoritativeOriginalProofMetadataAccepted(pageWidth, pageHeight, proof)
        }

        @JvmStatic
        fun initialSoftwarePrepareBottomForTest(
            scrollOffset: Int,
            viewportHeight: Int,
            contentHeight: Int
        ): Int {
            if (scrollOffset < 0 || viewportHeight <= 0 || contentHeight <= 0) return 0
            val requiredAheadPx = ceil(
                INITIAL_SOFTWARE_PREPARE_AHEAD_VIEWPORTS * viewportHeight.toFloat()
            ).toInt()
            return min(
                contentHeight.toLong(),
                scrollOffset.toLong() + viewportHeight.toLong() + requiredAheadPx.toLong()
            ).toInt()
        }

        private fun authoritativeOriginalProofMetadataAccepted(
            pageWidth: Int,
            pageHeight: Int,
            proof: ReaderPreparedStore.PreparedOriginalProof?
        ): Boolean {
            return proof != null &&
                proof.variant == ReaderPreparedStore.PreparedAssetVariant.ORIGINAL &&
                proof.canonicalAsset.isNotEmpty() && proof.inSampleSize == 1 &&
                !proof.postDecodeResized && proof.originalWidth == pageWidth &&
                proof.originalHeight == pageHeight && pageWidth > 0 && pageHeight > 0
        }

        private fun shouldStartFling(dragDistance: Float, velocityY: Int, minVelocity: Int, touchSlop: Int): Boolean {
            return dragDistance > touchSlop * FLING_MIN_DRAG_TOUCH_SLOP_MULTIPLIER &&
                abs(velocityY) > minVelocity * FLING_MIN_VELOCITY_MULTIPLIER
        }

        private fun isTapGesture(
            released: Boolean,
            deltaX: Float,
            deltaY: Float,
            touchSlop: Int
        ): Boolean {
            if (!released || touchSlop < 0) return false
            val slop = touchSlop.toFloat()
            return deltaX * deltaX + deltaY * deltaY <= slop * slop
        }

        private fun shouldAdvanceDesiredVersionForScrollerFrame(
            movedByFrame: Boolean,
            desiredVersion: Long,
            drawnVersion: Long
        ): Boolean {
            return movedByFrame && desiredVersion <= drawnVersion
        }

        private fun shouldClosePhysicalMotionInterval(
            traceActive: Boolean,
            scrollerFinished: Boolean,
            requiredVersion: Long,
            committedVersion: Long
        ): Boolean {
            return traceActive && scrollerFinished && requiredVersion <= committedVersion
        }

        private fun benchmarkPhysicalMotionIdleRequiredVersion(
            completedPhysicalVersion: Long,
            existingRequiredVersion: Long,
            committedVersion: Long,
        ): Long {
            return when {
                completedPhysicalVersion > 0L -> completedPhysicalVersion
                existingRequiredVersion > 0L -> existingRequiredVersion
                else -> committedVersion.coerceAtLeast(0L)
            }
        }

        private fun mergeOldestPixelMutationNs(existingNs: Long, mutationNs: Long): Long {
            if (mutationNs <= 0L) return existingNs
            return if (existingNs <= 0L) mutationNs else min(existingNs, mutationNs)
        }

        private fun mergeNewestPixelMutationNs(existingNs: Long, mutationNs: Long): Long {
            if (mutationNs <= 0L) return existingNs
            return max(existingNs, mutationNs)
        }

        private fun shouldConsumePixelMutationTiming(
            hasDrawState: Boolean,
            pendingWatermark: Long
        ): Boolean {
            return hasDrawState && pendingWatermark > 0L
        }

        private fun causalPixelMutationAgeMs(mutationNs: Long, endpointNs: Long): Float {
            if (mutationNs <= 0L || endpointNs <= mutationNs) return 0f
            return (endpointNs - mutationNs) / 1_000_000f
        }

        private fun isCausalPixelMutationOverBudget(ageMs: Float): Boolean {
            return ageMs > DEFAULT_FRAME_BUDGET_MS
        }

        private fun isCompletedDrawProofLifecycleCurrent(
            proofLifecycleEpoch: Long,
            currentLifecycleEpoch: Long
        ): Boolean {
            return proofLifecycleEpoch > 0L && proofLifecycleEpoch == currentLifecycleEpoch
        }

        private fun surfaceLatchPresentedUptimeNanos(
            callbackReceivedUptimeNanos: Long,
            nativeLatchNanos: Long,
            nativeCallbackObservedNanos: Long
        ): Long {
            if (callbackReceivedUptimeNanos <= 0L || nativeLatchNanos <= 0L ||
                nativeCallbackObservedNanos < nativeLatchNanos
            ) return 0L
            val nativeCallbackDelayNanos = nativeCallbackObservedNanos - nativeLatchNanos
            if (nativeCallbackDelayNanos >= callbackReceivedUptimeNanos) return 0L
            return callbackReceivedUptimeNanos - nativeCallbackDelayNanos
        }

        private fun shouldApplyContentMaxShrinkCorrection(
            currentOffset: Float,
            requestedOffset: Float,
            contentMaxScroll: Float
        ): Boolean {
            return currentOffset > contentMaxScroll + SCROLL_OFFSET_EPSILON_PX &&
                requestedOffset <= contentMaxScroll + SCROLL_OFFSET_EPSILON_PX
        }

        private fun repairForwardOnlyRestoreOffset(
            savedOffsetPx: Int,
            resolvedPageDrawHeightPx: Float,
            viewportHeightPx: Int,
        ): Int {
            if (!resolvedPageDrawHeightPx.isFinite() || resolvedPageDrawHeightPx <= 0f ||
                viewportHeightPx <= 0
            ) return savedOffsetPx
            // The ordinary progress probe owns 35% of the viewport. Preserve the exact bookmark
            // whenever at least that much target-page context remains; otherwise retain either
            // that context or the complete page when the page itself is shorter.
            val minimumVisibleTargetPx = min(
                resolvedPageDrawHeightPx,
                viewportHeightPx * PROGRESS_PAGE_PROBE_SCREEN_RATIO,
            )
            val minimumOffsetPx = ceil(
                minimumVisibleTargetPx - resolvedPageDrawHeightPx
            ).toInt()
            return min(0, savedOffsetPx).coerceAtLeast(minimumOffsetPx)
        }

        @JvmStatic
        fun repairForwardOnlyRestoreOffsetForTest(
            savedOffsetPx: Int,
            resolvedPageDrawHeightPx: Float,
            viewportHeightPx: Int,
        ): Int = repairForwardOnlyRestoreOffset(
            savedOffsetPx,
            resolvedPageDrawHeightPx,
            viewportHeightPx,
        )

        private fun sourceNativeRenderTargetSize(
            enabled: Boolean,
            viewportWidth: Int,
            viewportHeight: Int,
            widestKnownSourceWidth: Int
        ): Pair<Int, Int> {
            if (!enabled || viewportWidth <= SOURCE_NATIVE_WEBTOON_TARGET_WIDTH_PX ||
                viewportWidth <= 0 || viewportHeight <= 0
            ) return Pair(viewportWidth, viewportHeight)
            // Never introduce a downsample below a known encoded source. If a rare webtoon is
            // wider than the common 690-800 px source family, its target grows up to display size.
            val targetWidth = min(
                viewportWidth,
                max(SOURCE_NATIVE_WEBTOON_TARGET_WIDTH_PX, widestKnownSourceWidth)
            )
            val targetHeight = (
                viewportHeight.toDouble() * targetWidth.toDouble() / viewportWidth.toDouble()
            ).roundToInt().coerceAtLeast(1)
            return Pair(targetWidth, targetHeight)
        }

        @JvmStatic
        fun sourceNativeRenderTargetSizeForTest(
            enabled: Boolean,
            viewportWidth: Int,
            viewportHeight: Int,
            widestKnownSourceWidth: Int
        ): IntArray {
            val target = sourceNativeRenderTargetSize(
                enabled,
                viewportWidth,
                viewportHeight,
                widestKnownSourceWidth
            )
            return intArrayOf(target.first, target.second)
        }

        @JvmStatic
        fun shouldAdvanceDesiredVersionForScrollerFrameForTest(
            movedByFrame: Boolean,
            desiredVersion: Long,
            drawnVersion: Long
        ): Boolean {
            return shouldAdvanceDesiredVersionForScrollerFrame(
                movedByFrame,
                desiredVersion,
                drawnVersion
            )
        }

        @JvmStatic
        fun shouldClosePhysicalMotionIntervalForTest(
            traceActive: Boolean,
            scrollerFinished: Boolean,
            requiredVersion: Long,
            committedVersion: Long
        ): Boolean {
            return shouldClosePhysicalMotionInterval(
                traceActive,
                scrollerFinished,
                requiredVersion,
                committedVersion
            )
        }

        @JvmStatic
        fun benchmarkPhysicalMotionIdleRequiredVersionForTest(
            completedPhysicalVersion: Long,
            existingRequiredVersion: Long,
            committedVersion: Long,
        ): Long {
            return benchmarkPhysicalMotionIdleRequiredVersion(
                completedPhysicalVersion,
                existingRequiredVersion,
                committedVersion,
            )
        }

        @JvmStatic
        fun mergeOldestPixelMutationNsForTest(existingNs: Long, mutationNs: Long): Long {
            return mergeOldestPixelMutationNs(existingNs, mutationNs)
        }

        @JvmStatic
        fun mergeNewestPixelMutationNsForTest(existingNs: Long, mutationNs: Long): Long {
            return mergeNewestPixelMutationNs(existingNs, mutationNs)
        }

        @JvmStatic
        fun shouldConsumePixelMutationTimingForTest(
            hasDrawState: Boolean,
            pendingWatermark: Long
        ): Boolean {
            return shouldConsumePixelMutationTiming(hasDrawState, pendingWatermark)
        }

        @JvmStatic
        fun causalPixelMutationAgeMsForTest(mutationNs: Long, endpointNs: Long): Float {
            return causalPixelMutationAgeMs(mutationNs, endpointNs)
        }

        @JvmStatic
        fun isCausalPixelMutationOverBudgetForTest(ageMs: Float): Boolean {
            return isCausalPixelMutationOverBudget(ageMs)
        }

        @JvmStatic
        fun isCompletedDrawProofLifecycleCurrentForTest(
            proofLifecycleEpoch: Long,
            currentLifecycleEpoch: Long
        ): Boolean = isCompletedDrawProofLifecycleCurrent(
            proofLifecycleEpoch,
            currentLifecycleEpoch
        )

        @JvmStatic
        fun surfaceLatchPresentedUptimeNanosForTest(
            callbackReceivedUptimeNanos: Long,
            nativeLatchNanos: Long,
            nativeCallbackObservedNanos: Long
        ): Long = surfaceLatchPresentedUptimeNanos(
            callbackReceivedUptimeNanos,
            nativeLatchNanos,
            nativeCallbackObservedNanos
        )

        @JvmStatic
        fun canAdmitPendingFrameCommitForTest(pendingCommitCount: Int): Boolean =
            canAdmitPendingFrameCommit(pendingCommitCount)

        @JvmStatic
        fun maxPendingFrameCommitsForTest(): Int = MAX_PENDING_FRAME_COMMITS

        private fun canAdmitPendingFrameCommit(pendingCommitCount: Int): Boolean {
            return pendingCommitCount in 0 until MAX_PENDING_FRAME_COMMITS
        }

        @JvmStatic
        fun shouldApplyContentMaxShrinkCorrectionForTest(
            currentOffset: Float,
            requestedOffset: Float,
            contentMaxScroll: Float
        ): Boolean = shouldApplyContentMaxShrinkCorrection(
            currentOffset,
            requestedOffset,
            contentMaxScroll
        )

        private const val TAG = "ReaderSurfaceStats"
        private const val NATIVE_PRESENTATION_NONE = 0
        private const val NATIVE_PRESENTATION_SURFACE_CONTROL = 1
        private const val NATIVE_PRESENTATION_BUFFER_QUEUE = 2
        private const val NATIVE_PRESENTATION_DIAGNOSTIC_INTERVAL = 90L
        private const val ACTIVE_SCROLL_TRACE_NAME = "ViewerActiveScroll"
        private const val ACTIVE_SCROLL_REFRESH_PERIOD_COUNTER = "ViewerActiveScrollRefreshPeriodNs"
        private const val PHYSICAL_SCROLL_TRACE_NAME = "ViewerPhysicalScrollMotion"
        private const val PHYSICAL_SCROLL_REFRESH_PERIOD_COUNTER =
            "ViewerPhysicalScrollRefreshPeriodNs"
        private const val BENCHMARK_PHYSICAL_MOTION_IDLE_CONFIRM_MS = 96L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val DEFAULT_FRAME_BUDGET_MS = 16.67f
        private const val MISSED_VSYNC_FACTOR = 2.0f
        private const val MIN_FRAME_SAMPLES = 8
        private const val DEFAULT_PAGE_GAP_PX = 0
        private const val PREPARED_SUBSET_TILE_SOURCE_HEIGHT =
            ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
        private const val SOURCE_NATIVE_WEBTOON_TARGET_WIDTH_PX = 800
        // Current viewport plus six forward viewports covers fast downward flings without asking
        // RenderThread to upload an entire long episode in one burst.
        private const val HWUI_FORWARD_PREPARE_VIEWPORTS = 6f
        // Encoded/decoded originals remain available for every page. GPU upload is limited to the
        // current viewport plus a forward runway so physical input cannot compete with a complete
        // 100+ page episode upload.
        private const val NATIVE_PREWARM_AHEAD_VIEWPORTS = 6f
        private const val NATIVE_PREWARM_FALLBACK_AHEAD_PAGES = 6
        private const val NATIVE_PREWARM_MAX_TILES = 12
        private const val DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 16
        // A resumed r90 manhwa can have twelve current-tail pages, one transition card and the
        // required p0-p4 runway ahead of the first visible page. Sixteen therefore stopped at p2:
        // p3 paid its first glTexImage upload in the presented frame even though its decoded body
        // had been resident for seconds. Expand only the host-GPU/direct-Wi-Fi profile; physical
        // direct Wi-Fi retains its established sixteen-page window.
        private const val HOST_GPU_DIRECT_WIFI_NATIVE_PREWARM_AHEAD_PAGES = 24
        private const val DIRECT_WIFI_NATIVE_PREWARM_MAX_TILES = 48
        private const val DIRECT_WIFI_NATIVE_PREWARM_MAX_BYTES = 288L * 1024L * 1024L
        private const val DIRECT_WIFI_SHORT_WEBTOON_PREWARM_AHEAD_VIEWPORTS = 6f
        // Kept in one production policy point while the exact no-sampling NTK proof is bound.
        private const val FORWARD_REQUEST_END_EPSILON_PX = 0.01f
        private const val TILE_SEAM_OVERLAP_PX = 1f
        private const val TRANSITION_CARD_WIDTH_RATIO = 0.82f
        private const val TRANSITION_CARD_PAGE_HEIGHT_PX = 168f
        private const val DEFAULT_PLACEHOLDER_PAGE_HEIGHT_RATIO = 1.45f
        private const val MIN_PLACEHOLDER_PAGE_HEIGHT_RATIO = 0.85f
        private const val MAX_PLACEHOLDER_PAGE_HEIGHT_RATIO = 3.8f
        private const val PLACEHOLDER_RATIO_LEARNING_RATE = 0.4f
        private const val NEAR_BOUNDARY_SCREENFULS = 10
        private const val NEAR_BOUNDARY_PAGE_THRESHOLD = 16
        private const val BUSY_WINDOW_ANCHOR_STEP = 2
        private const val BUSY_WINDOW_MIN_DISPATCH_MS = 48L
        private const val BUSY_NEAR_BOUNDARY_MIN_DISPATCH_MS = 250L
        private const val BOUNDARY_DISPATCH_POST_FRAME_DELAY_MS = 16L
        private const val BUSY_COVERAGE_LOG_INTERVAL_MS = 250L
        private const val ACTIVE_SCROLL_COVERAGE_LOG_INTERVAL_MS = 350L
        private const val ACTIVE_DIRECTION_CLAMP_LOG_INTERVAL_MS = 750L
        // At 90 Hz this spans about 67 ms, enough to absorb the measured host-GPU commit latency
        // while keeping callbacks, immutable coverage proofs and retained page references bounded.
        private const val MAX_PENDING_FRAME_COMMITS = 6
        // Preserve 60 Hz telemetry cadence while preventing a 90 Hz native producer from posting
        // an unbounded stream of Activity-heavy callbacks. Semantic identity changes bypass it.
        private const val COMPLETED_DRAW_ORDINARY_CADENCE_NANOS = 16_000_000L
        private const val MAX_COMPLETED_DRAW_SEMANTIC_TRANSITIONS = 64
        private const val MAX_COMPLETED_DRAW_DELIVERIES_PER_RUN = 6
        private const val VISIBLE_LOADING_HOLD_RETRY_MS = 48L
        private const val NO_STATE_FRAME_RETRY_MS = 48L
        private const val EXACT_STRUCTURE_HOLD_LOG_INTERVAL_MS = 250L
        private const val BLOCKED_FORWARD_REQUEST_THROTTLE_MS = 48L
        private const val BLOCKED_FORWARD_RUNWAY_AFTER_PAGES = 5
        private const val IDLE_SLOW_FRAME_LOG_BUDGET_MS = 200f
        private const val SUSTAINED_SLOW_FRAME_LOG_BUDGET_MS = 32f
        private const val HARD_SCROLL_CALLBACK_GAP_MS = 50f
        private const val CLEAN_INPUT_CALLBACK_GAP_MAX_INPUT_AGE_MS = 24f
        private const val CLEAN_NO_INPUT_CALLBACK_GAP_MAX_MS = 100f
        private const val CLEAN_VISUAL_CALLBACK_GAP_MAX_MS = 120f
        private const val IDLE_STATS_GAP_FACTOR = 4f
        private const val SLOW_FRAME_LOG_INTERVAL_MS = 500L
        private const val PIXEL_MUTATION_GAP_LOG_INTERVAL_MS = 500L
        private const val NATIVE_SUBMIT_DIAGNOSTIC_INTERVAL_MS = 1000L
        private const val PROGRAMMATIC_SCROLL_STATS_RECENT_MS = 1800L
        private const val BOUNDARY_REMOVE_RECENT_SCROLL_MS = 5000L
        private const val REMOVE_PAGE_RANGE_SCROLL_QUIET_MS = 3000L
        private const val PROGRAMMATIC_SCROLL_STATS_ACTIVE_MS = 6500L
        private const val PROGRAMMATIC_SCROLL_STATS_FINALIZE_MS = 820L
        private const val TEST_SCROLL_DIAGNOSTIC_LOG_INTERVAL_MS = 250L
        private const val SURFACE_REVEAL_PROBE_LOG_INTERVAL_MS = 250L
        private const val COVERAGE_EDGE_FILL_PX = 8
        private const val DRAW_COVERAGE_EPSILON_PX = 1f
        private const val COVERAGE_EDGE_PLACEHOLDER_FILL_PX = 192
        private const val MIN_READABLE_SOURCE_WIDTH_PERMILLE = 450
        private const val PREPENDED_BOUNDARY_HOLD_MAX_FRACTION = 0.35f
        private const val SCROLLBAR_TOUCH_WIDTH_PX = 96f
        private const val SCROLLBAR_RIGHT_MARGIN_PX = 10f
        private const val SCROLLBAR_TRACK_WIDTH_PX = 48f
        private const val SCROLLBAR_TRACK_RADIUS_PX = 24f
        private const val SCROLLBAR_THUMB_WIDTH_PX = 40f
        private const val SCROLLBAR_THUMB_RADIUS_PX = 20f
        private const val SCROLLBAR_MIN_THUMB_HEIGHT_PX = 188f
        private const val SCROLLBAR_GRIP_INSET_PX = 10f
        private const val SCROLLBAR_GRIP_HEIGHT_PX = 4f
        private const val SCROLLBAR_GRIP_GAP_PX = 13f
        private const val SCROLLBAR_GRIP_RADIUS_PX = 2f
        private const val BOUNDARY_EPSILON_PX = 2f
        private const val BOUNDARY_FLING_EXTEND_EPSILON_PX = 4
        private const val SCROLL_OFFSET_EPSILON_PX = 0.5f
        private const val BOUNDARY_FLING_MIN_VELOCITY_MULTIPLIER = 2f
        private const val BOUNDARY_CANCEL_MIN_DRAG_SCREEN_RATIO = 0.08f
        private const val BOUNDARY_CANCEL_MIN_DRAG_TOUCH_SLOP_MULTIPLIER = 4f
        private const val PROGRESS_PAGE_PROBE_SCREEN_RATIO = 0.35f
        private const val DRAG_SCROLL_MULTIPLIER = 1.0f
        private const val FLING_SCROLL_MULTIPLIER = 1.0f
        private const val DRAWABLE_PREFIX_FORWARD_RUNWAY_SCREENFULS = 1.0f
        private const val FLING_MIN_DRAG_TOUCH_SLOP_MULTIPLIER = 1.0f
        private const val FLING_MIN_VELOCITY_MULTIPLIER = 1
        private const val RESTORE_POSITION_LOCK_MS = 4000L
        private const val NEXT_BOUNDARY_GEOMETRY_HOLD_MS = 12000L
        private const val RESTORE_POSITION_EPSILON_PX = 2f
        private const val INITIAL_RENDER_HOLD_MS = 0L
        private const val SCROLL_JUMP_LOG_SCREEN_RATIO = 0.75f
        private const val HEIGHT_CHANGE_SCROLL_ADJUST_QUIET_MS = 6500L
        private const val HEIGHT_CHANGE_RESTORE_BACKSTEP_SCREEN_LIMIT = 0.5f
        private const val ACTIVE_GEOMETRY_RESOLVE_QUIET_MS = 15000L
        private const val PREFIX_LIMIT_FAST_SCROLL_GRACE_MS = 9000L
        private const val HEIGHT_CHANGE_EPSILON_PX = 0.01f
        private const val PREPARED_SCENE_HEIGHT_EPSILON_PX = 0.5f
        // Acceptance requires one physical viewport plus 1.5 viewports ahead.  GPU preparation
        // uses that same 2.5-total physical interval; runway snapshots express only the ahead part.
        private const val INITIAL_SOFTWARE_PREPARE_AHEAD_VIEWPORTS = 1.5f
        private const val DEFAULT_FORWARD_RUNWAY_AHEAD_VIEWPORTS =
            INITIAL_SOFTWARE_PREPARE_AHEAD_VIEWPORTS
        private val BITMAP_SUBMISSION_MODE = BitmapSubmissionMode.DIRECT_VISIBLE_CROP
        private const val ACTIVE_DRAWABLE_BOUNDS_DELTA_SUPPRESS_PX = 4f
        private const val OFFSCREEN_BOUNDS_ONLY_DEFER_VIEWPORTS = 1.0f
        private const val BUSY_RESOLVE_RENDER_EXTRA_PAGES = 2
        private const val MOVE_VELOCITY_SAMPLE_MS = 16L
        private const val RENDER_THREAD_STOP_JOIN_MS = 500L
        private const val DIRECT_TILE_DRAW_SCALE_EPSILON = 0.01f
        private const val ACTIVE_SCROLL_RESOLVE_RENDER_MARGIN_PX = 0f
        private const val PENDING_NONE = 0
        private const val PENDING_BITMAP = 1
        private const val PENDING_TILES = 2
        private const val PENDING_BOUNDS = 3
        private const val PENDING_SIZE = 4
        private const val DIRTY_SCROLL = 1 shl 0
        private const val DIRTY_CONTENT = 1 shl 1
        private const val DIRTY_SCROLLBAR = 1 shl 2
        private const val DIRTY_ANIMATION = 1 shl 3
        private const val DIRTY_INVALIDATION = 1 shl 4
        private const val PAGE_PLACEHOLDER_COLOR = -0x1
        private const val SCROLLBAR_TRACK_COLOR = 0x1A000000
        private const val SCROLLBAR_THUMB_COLOR = -0xf0f10
        private const val SCROLLBAR_THUMB_ACTIVE_COLOR = -0x1
        private const val SCROLLBAR_GRIP_COLOR = 0x4D000000
        private const val FORWARD_CAP_LOG_INTERVAL_MS = 250L

        private fun effectiveDrawablePrefixScrollLimit(
            requested: Boolean,
            @Suppress("UNUSED_PARAMETER") inlineRealPixelsOnly: Boolean
        ): Boolean = requested

        @JvmStatic
        fun effectiveDrawablePrefixScrollLimitForTest(
            requested: Boolean,
            inlineRealPixelsOnly: Boolean
        ): Boolean = effectiveDrawablePrefixScrollLimit(requested, inlineRealPixelsOnly)

        private fun shouldAdjustScrollForChangedPageHeight(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean,
            oldBottom: Float,
            scrollOffset: Float
        ): Boolean {
            if (oldBottom > scrollOffset) return false
            if (lastBusy || recentScrollSettling) return false
            return !pointerDown && !dragging && scrollerFinished
        }

        private fun shouldRestoreAnchorAfterPendingResolves(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean
        ): Boolean {
            if (lastBusy || recentScrollSettling) return false
            return !pointerDown && !dragging && scrollerFinished
        }

        @JvmStatic
        fun shouldAdjustScrollForChangedPageHeightForTest(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean,
            oldBottom: Float,
            scrollOffset: Float
        ): Boolean {
            return shouldAdjustScrollForChangedPageHeight(
                lastBusy,
                pointerDown,
                dragging,
                scrollerFinished,
                recentScrollSettling,
                oldBottom,
                scrollOffset
            )
        }

        @JvmStatic
        fun shouldRestoreAnchorAfterPendingResolvesForTest(
            lastBusy: Boolean,
            pointerDown: Boolean,
            dragging: Boolean,
            scrollerFinished: Boolean,
            recentScrollSettling: Boolean
        ): Boolean {
            return shouldRestoreAnchorAfterPendingResolves(
                lastBusy,
                pointerDown,
                dragging,
                scrollerFinished,
                recentScrollSettling
            )
        }
    }
}
